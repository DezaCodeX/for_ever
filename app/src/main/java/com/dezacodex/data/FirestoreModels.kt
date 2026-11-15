package com.dezacodex.data

import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.PropertyName
import com.google.firebase.firestore.ServerTimestamp
import java.util.Date

data class FirestoreUser(
    @DocumentId val id: String = "",
    val uid: String = "",
    val email: String = "",
    val username: String = "",
    val photoUrl: String? = null,
    val phone: String? = null,
    val bio: String? = null,
    val age: Int? = null,
    val gender: String? = null,
    val inviteCode: String = "",
    val partnerUid: String? = null,
    val partnerEmail: String? = null,
    val partnerUsername: String? = null,
    val coupleRoomId: String? = null,
    val streakCount: Int = 0,
    val snapScore: Int = 0,
    val totalMomentsCount: Int = 0,
    val currentTheme: String = "Vibrant Palette",
    val anniversaryDate: String = "2024-04-12",
    val notificationTone: String = "Romantic Bells",
    val callRingtone: String = "Sweet Symphony",
    val videoCallRingtone: String = "Cosmic Pulse",
    val vibrationIntensity: String = "Heartbeat Pulse",
    val voiceCallWallpaper: String = "Default Soft Lavender",
    val fcmToken: String? = null,
    val role: String = "user",
    @ServerTimestamp val createdAt: Date? = null,
    @ServerTimestamp val updatedAt: Date? = null
) {
    fun toCreateMap(): Map<String, Any> = mapOf(
        "uid" to uid,
        "email" to email,
        "username" to username,
        "photoUrl" to photoUrl,
        "phone" to phone,
        "bio" to bio,
        "age" to age,
        "gender" to gender,
        "inviteCode" to inviteCode,
        "partnerUid" to partnerUid,
        "partnerEmail" to partnerEmail,
        "partnerUsername" to partnerUsername,
        "coupleRoomId" to coupleRoomId,
        "streakCount" to streakCount,
        "snapScore" to snapScore,
        "totalMomentsCount" to totalMomentsCount,
        "currentTheme" to currentTheme,
        "anniversaryDate" to anniversaryDate,
        "notificationTone" to notificationTone,
        "callRingtone" to callRingtone,
        "videoCallRingtone" to videoCallRingtone,
        "vibrationIntensity" to vibrationIntensity,
        "voiceCallWallpaper" to voiceCallWallpaper,
        "fcmToken" to fcmToken,
        "role" to "user",
        "createdAt" to FieldValue.serverTimestamp(),
        "updatedAt" to FieldValue.serverTimestamp()
    ).filterValues { it != null }.mapValues { it.value!! }
}

data class FirestoreCoupleRoom(
    @DocumentId val id: String = "",
    val roomId: String = "",
    val ownerUid: String = "",
    val memberUids: List<String> = emptyList(),
    val myName: String? = null,
    val partnerName: String? = null,
    val statusText: String? = null,
    val anniversaryDate: String? = null,
    val streakCount: Int = 1,
    val totalScore: Int = 0,
    val wallpaper: String? = null,
    @ServerTimestamp val lastInteractionAt: Date? = null,
    @ServerTimestamp val createdAt: Date? = null,
    @ServerTimestamp val updatedAt: Date? = null
) {
    fun toCreateMap(): Map<String, Any> = mapOf(
        "roomId" to roomId,
        "ownerUid" to ownerUid,
        "memberUids" to memberUids,
        "myName" to myName,
        "partnerName" to partnerName,
        "statusText" to statusText,
        "anniversaryDate" to anniversaryDate,
        "streakCount" to streakCount,
        "totalScore" to totalScore,
        "wallpaper" to wallpaper,
        "lastInteractionAt" to FieldValue.serverTimestamp(),
        "createdAt" to FieldValue.serverTimestamp(),
        "updatedAt" to FieldValue.serverTimestamp()
    ).filterValues { it != null }.mapValues { it.value!! }
}

data class FirestoreCoupleMessage(
    @DocumentId val docId: String = "",
    val id: String = "",
    val roomId: String = "",
    val senderUid: String = "",
    val senderName: String = "",
    val memberUids: List<String> = emptyList(),
    val text: String = "",
    val mediaUrl: String? = null,
    @get:PropertyName("isDisappearing") @field:PropertyName("isDisappearing")
    val isDisappearing: Boolean = false,
    val durationSecs: Int? = null,
    val expiresAtMs: Long? = null,
    val viewed: Boolean = false,
    val reaction: String? = null,
    @get:PropertyName("isSavedInChat") @field:PropertyName("isSavedInChat")
    val isSavedInChat: Boolean = false,
    val msgEffect: String? = null,
    @ServerTimestamp val createdAt: Date? = null,
    @ServerTimestamp val updatedAt: Date? = null
) {
    fun toCreateMap(): Map<String, Any> = mapOf(
        "id" to id,
        "roomId" to roomId,
        "senderUid" to senderUid,
        "senderName" to senderName,
        "memberUids" to memberUids,
        "text" to text,
        "mediaUrl" to mediaUrl,
        "isDisappearing" to isDisappearing,
        "durationSecs" to durationSecs,
        "expiresAtMs" to expiresAtMs,
        "viewed" to viewed,
        "reaction" to reaction,
        "isSavedInChat" to isSavedInChat,
        "msgEffect" to msgEffect,
        "createdAt" to FieldValue.serverTimestamp(),
        "updatedAt" to FieldValue.serverTimestamp()
    ).filterValues { it != null }.mapValues { it.value!! }
}

data class FirestoreCoupleSnap(
    @DocumentId val docId: String = "",
    val id: String = "",
    val roomId: String = "",
    val senderUid: String = "",
    val senderName: String = "",
    val memberUids: List<String> = emptyList(),
    val snapType: String = "IMAGE",
    val description: String = "",
    val mediaUrl: String? = null,
    val durationSec: Int = 10,
    val viewed: Boolean = false,
    val screenshotAlert: Boolean = false,
    @get:PropertyName("isSavedInChat") @field:PropertyName("isSavedInChat")
    val isSavedInChat: Boolean = false,
    @ServerTimestamp val createdAt: Date? = null,
    @ServerTimestamp val updatedAt: Date? = null
) {
    fun toCreateMap(): Map<String, Any> = mapOf(
        "id" to id,
        "roomId" to roomId,
        "senderUid" to senderUid,
        "senderName" to senderName,
        "memberUids" to memberUids,
        "snapType" to snapType,
        "description" to description,
        "mediaUrl" to mediaUrl,
        "durationSec" to durationSec,
        "viewed" to viewed,
        "screenshotAlert" to screenshotAlert,
        "isSavedInChat" to isSavedInChat,
        "createdAt" to FieldValue.serverTimestamp(),
        "updatedAt" to FieldValue.serverTimestamp()
    ).filterValues { it != null }.mapValues { it.value!! }
}

data class FirestoreCoupleStory(
    @DocumentId val docId: String = "",
    val id: String = "",
    val roomId: String = "",
    val senderUid: String = "",
    val senderName: String = "",
    val memberUids: List<String> = emptyList(),
    val description: String = "",
    val imageType: String = "ROMANCE",
    val mediaUrl: String? = null,
    val expiresAtMs: Long = 0L,
    @get:PropertyName("isSavedInChat") @field:PropertyName("isSavedInChat")
    val isSavedInChat: Boolean = false,
    @ServerTimestamp val createdAt: Date? = null,
    @ServerTimestamp val updatedAt: Date? = null
) {
    fun toCreateMap(): Map<String, Any> = mapOf(
        "id" to id,
        "roomId" to roomId,
        "senderUid" to senderUid,
        "senderName" to senderName,
        "memberUids" to memberUids,
        "description" to description,
        "imageType" to imageType,
        "mediaUrl" to mediaUrl,
        "expiresAtMs" to expiresAtMs,
        "isSavedInChat" to isSavedInChat,
        "createdAt" to FieldValue.serverTimestamp(),
        "updatedAt" to FieldValue.serverTimestamp()
    ).filterValues { it != null }.mapValues { it.value!! }
}

data class FirestoreCallRoom(
    @DocumentId val docId: String = "",
    val roomId: String = "",
    val coupleRoomId: String? = null,
    val hostUid: String = "",
    val title: String? = null,
    val callType: String = "VIDEO",
    val status: String = "active",
    val layoutMode: String = "GRID",
    val pinnedUid: String? = null,
    val autoCamSwitch: Boolean = true,
    val hideNonVideo: Boolean = false,
    val memberUids: List<String> = emptyList(),
    @ServerTimestamp val createdAt: Date? = null,
    @ServerTimestamp val updatedAt: Date? = null,
    @ServerTimestamp val endedAt: Date? = null
) {
    fun toCreateMap(): Map<String, Any> = mapOf(
        "roomId" to roomId,
        "coupleRoomId" to coupleRoomId,
        "hostUid" to hostUid,
        "title" to title,
        "callType" to callType,
        "status" to status,
        "layoutMode" to layoutMode,
        "pinnedUid" to pinnedUid,
        "autoCamSwitch" to autoCamSwitch,
        "hideNonVideo" to hideNonVideo,
        "memberUids" to memberUids,
        "createdAt" to FieldValue.serverTimestamp(),
        "updatedAt" to FieldValue.serverTimestamp()
    ).filterValues { it != null }.mapValues { it.value!! }
}

data class FirestoreCallParticipant(
    @DocumentId val docId: String = "",
    val uid: String = "",
    val callRoomId: String = "",
    val memberUids: List<String> = emptyList(),
    val displayName: String = "",
    val photoUrl: String? = null,
    @get:PropertyName("isAudioMuted") @field:PropertyName("isAudioMuted")
    val isAudioMuted: Boolean = false,
    @get:PropertyName("isCameraEnabled") @field:PropertyName("isCameraEnabled")
    val isCameraEnabled: Boolean = true,
    @get:PropertyName("isScreenSharing") @field:PropertyName("isScreenSharing")
    val isScreenSharing: Boolean = false,
    val screenAudioEnabled: Boolean = false,
    @get:PropertyName("isSpeaking") @field:PropertyName("isSpeaking")
    val isSpeaking: Boolean = false,
    val audioLevelDb: Double = -90.0,
    val videoQuality: String = "simulcast",
    @ServerTimestamp val joinedAt: Date? = null,
    @ServerTimestamp val updatedAt: Date? = null
) {
    fun toCreateMap(): Map<String, Any> = mapOf(
        "uid" to uid,
        "callRoomId" to callRoomId,
        "memberUids" to memberUids,
        "displayName" to displayName,
        "photoUrl" to photoUrl,
        "isAudioMuted" to isAudioMuted,
        "isCameraEnabled" to isCameraEnabled,
        "isScreenSharing" to isScreenSharing,
        "screenAudioEnabled" to screenAudioEnabled,
        "isSpeaking" to isSpeaking,
        "audioLevelDb" to audioLevelDb,
        "videoQuality" to videoQuality,
        "joinedAt" to FieldValue.serverTimestamp(),
        "updatedAt" to FieldValue.serverTimestamp()
    ).filterValues { it != null }.mapValues { it.value!! }
}

data class FirestoreCallSignal(
    @DocumentId val docId: String = "",
    val id: String = "",
    val callRoomId: String = "",
    val fromUid: String = "",
    val toUid: String = "",
    val participantUids: List<String> = emptyList(),
    val streamKind: String = "CAMERA",
    val signalType: String = "OFFER",
    val sdp: String? = null,
    val iceCandidate: String? = null,
    val iceSdpMid: String? = null,
    val iceSdpMLineIndex: Int? = null,
    @ServerTimestamp val createdAt: Date? = null
) {
    fun toCreateMap(): Map<String, Any> = mapOf(
        "id" to id,
        "callRoomId" to callRoomId,
        "fromUid" to fromUid,
        "toUid" to toUid,
        "participantUids" to participantUids,
        "streamKind" to streamKind,
        "signalType" to signalType,
        "sdp" to sdp,
        "iceCandidate" to iceCandidate,
        "iceSdpMid" to iceSdpMid,
        "iceSdpMLineIndex" to iceSdpMLineIndex,
        "createdAt" to FieldValue.serverTimestamp()
    ).filterValues { it != null }.mapValues { it.value!! }
}

data class FirestoreUserNotification(
    @DocumentId val docId: String = "",
    val id: String = "",
    val senderUid: String = "",
    val senderName: String? = null,
    val coupleRoomId: String = "",
    val type: String = "MESSAGE",
    val title: String = "",
    val body: String = "",
    @get:PropertyName("isRead") @field:PropertyName("isRead")
    val isRead: Boolean = false,
    @ServerTimestamp val createdAt: Date? = null,
    @ServerTimestamp val updatedAt: Date? = null
) {
    fun toCreateMap(): Map<String, Any> = mapOf(
        "id" to id,
        "senderUid" to senderUid,
        "senderName" to senderName,
        "coupleRoomId" to coupleRoomId,
        "type" to type,
        "title" to title,
        "body" to body,
        "isRead" to isRead,
        "createdAt" to FieldValue.serverTimestamp(),
        "updatedAt" to FieldValue.serverTimestamp()
    ).filterValues { it != null }.mapValues { it.value!! }
}
