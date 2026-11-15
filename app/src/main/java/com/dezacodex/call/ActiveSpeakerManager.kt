package com.dezacodex.call

/**
 * Deterministic Active Speaker & Smart Camera / PiP Tile Manager.
 * Prevents speaker flickering via hold-time hysteresis and dB hysteresis margin,
 * preserves pinned participant preferences, and selects best camera tile for PiP/compact views.
 */
class ActiveSpeakerManager(
    private val switchHoldMs: Long = 1200L,
    private val switchMarginDb: Float = 4.0f
) {
    private var currentSpeakerId: String? = null
    private var lastSwitchTimeMs: Long = 0L

    fun reset() {
        currentSpeakerId = null
        lastSwitchTimeMs = 0L
    }

    /**
     * Evaluates speaking participants and returns the stabilized active speaker ID.
     */
    fun resolveActiveSpeaker(
        participants: List<ParticipantStream>,
        nowMs: Long = System.currentTimeMillis()
    ): String? {
        val activeTalkers = participants.filter { !it.isMicMuted && it.isSpeaking }
        if (activeTalkers.isEmpty()) {
            // Keep last active speaker for a brief grace period to avoid abrupt UI jumps
            if (currentSpeakerId != null && participants.any { it.participantId == currentSpeakerId }) {
                return currentSpeakerId
            }
            currentSpeakerId = null
            return null
        }

        val loudest = activeTalkers.maxByOrNull { it.audioDb } ?: return currentSpeakerId
        val existing = activeTalkers.find { it.participantId == currentSpeakerId }

        if (existing == null) {
            // Previous speaker stopped speaking or left
            if (currentSpeakerId == null || (nowMs - lastSwitchTimeMs) >= 300L || participants.none { it.participantId == currentSpeakerId }) {
                currentSpeakerId = loudest.participantId
                lastSwitchTimeMs = nowMs
            }
            return currentSpeakerId
        }

        if (loudest.participantId != existing.participantId) {
            val holdElapsed = (nowMs - lastSwitchTimeMs) >= switchHoldMs
            val exceedsMargin = loudest.audioDb >= (existing.audioDb + switchMarginDb)
            if (holdElapsed && exceedsMargin) {
                currentSpeakerId = loudest.participantId
                lastSwitchTimeMs = nowMs
            }
        }
        return currentSpeakerId
    }

    /**
     * Selects the best participant stream for Picture-in-Picture, Minimized Call Window,
     * and Compact Mobile Spotlight without unnecessary reinitialization.
     *
     * Priority:
     * 1. Explicitly pinned participant (always preserved!)
     * 2. Active speaker IF they have webcam or screen share enabled
     * 3. Any remote participant sharing screen
     * 4. Any remote participant with webcam enabled
     * 5. Active speaker (audio/avatar tile if no one has video)
     * 6. First remote participant, or local participant as final fallback
     */
    fun selectSpotlightParticipant(
        participants: List<ParticipantStream>,
        pinnedParticipantId: String?,
        activeSpeakerId: String?
    ): ParticipantStream? {
        if (participants.isEmpty()) return null

        // 1. Pinned participant takes top priority
        if (pinnedParticipantId != null) {
            participants.find { it.participantId == pinnedParticipantId }?.let { return it }
        }

        // 2. Active speaker with active video/screen feed
        if (activeSpeakerId != null) {
            participants.find { it.participantId == activeSpeakerId && it.hasAnyVideo }?.let { return it }
        }

        // 3. Active screen sharer
        participants.find { it.isScreenSharing && it.screenTrackId != null }?.let { return it }

        // 4. Remote participant with webcam enabled
        participants.find { !it.isLocal && it.isWebcamEnabled && it.webcamTrackId != null }?.let { return it }

        // 5. Local participant with webcam enabled
        participants.find { it.isLocal && it.isWebcamEnabled && it.webcamTrackId != null }?.let { return it }

        // 6. Active speaker even with camera off (shows speaking avatar cleanly)
        if (activeSpeakerId != null) {
            participants.find { it.participantId == activeSpeakerId }?.let { return it }
        }

        // 7. First remote participant or local fallback
        return participants.firstOrNull { !it.isLocal } ?: participants.firstOrNull()
    }
}
