package com.dezacodex.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "users_table")
data class User(
    @PrimaryKey val email: String,
    val username: String,
    val passwordHash: String = "",
    val avatarUrl: String = "",
    val phone: String = "",
    val inviteCode: String = "", // Secret pairing code (e.g., "HS-4912")
    val connectedPartnerEmail: String? = null,
    val streakCount: Int = 0,
    val snapScore: Int = 0,
    val totalMomentsCount: Int = 0, // for admin stats
    val isGoogleUser: Boolean = false,
    val isOtpVerified: Boolean = false,
    val currentTheme: String = "Vibrant Palette",
    val age: Int = 0,
    val gender: String = ""
)

@Entity(tableName = "lovers_profiles")
data class LoversProfile(
    @PrimaryKey val id: Int = 1,
    val myName: String = "Alex",
    val partnerName: String = "Sophia",
    val statusText: String = "Holding hands forever & always ❤️",
    val avatarUrl: String = "",
    val partnerAvatarUrl: String = "",
    val streakCount: Int = 12, // Active days 🔥
    val totalScore: Int = 520, // Snap score
    val anniversaryDate: String = "2024-04-12",
    val currentTheme: String = "Vibrant Palette", // Pink, Dark Red, Lavender, Cosmic Slate
    val lastInteractionTime: Long = System.currentTimeMillis(),
    val loggedInUserEmail: String = "",
    val notificationTone: String = "Romantic Bells",
    val callRingtone: String = "Sweet Symphony",
    val videoCallRingtone: String = "Cosmic Pulse",
    val vibrationIntensity: String = "Heartbeat Pulse",
    val voiceCallWallpaper: String = "Default Soft Lavender"
)

@Entity(tableName = "love_messages")
data class LoveMessage(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val senderName: String,
    val senderEmail: String = "", // Dynamic user assignment
    val receiverEmail: String = "",
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isDisappearing: Boolean = false,
    val viewed: Boolean = false,
    val durationSecs: Int = 10,
    val reaction: String = "", // "❤️", "😍", "💖", "🔥" empty string for none
    val isSavedInChat: Boolean = false, // Snapchat style save in chat feature
    val msgEffect: String = "" // "HEART", "FIRE", "GLOW", "CONFETTI" or empty for none
)

@Entity(tableName = "couple_snaps")
data class CoupleSnap(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val senderName: String,
    val senderEmail: String = "",
    val receiverEmail: String = "",
    val snapType: String, // "IMAGE" or "VIDEO"
    val description: String, // Description or custom drawing visual description
    val durationSec: Int = 10,
    val viewed: Boolean = false,
    val viewedTimestamp: Long = 0, // timestamp when viewed (for deletions)
    val expiresAt: Long = 0,
    val screenshotAlert: Boolean = false, // Snapchat screenshot notifier alert
    val timestamp: Long = System.currentTimeMillis(),
    val isSavedInChat: Boolean = false, // Saved in chat flag
    val savedInLocalStoragePath: String = "" // Downloaded local storage file path indicator
)

@Entity(tableName = "couple_stories")
data class CoupleStory(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val senderName: String,
    val senderEmail: String = "",
    val description: String,
    val imageType: String, // Sweet moment type (e.g. ROMANCE, MEMORIES)
    val timestamp: Long = System.currentTimeMillis(),
    val expiresAt: Long = System.currentTimeMillis() + 24 * 60 * 60 * 1000L, // Deleted 24 hours after creation
    val isSavedInChat: Boolean = false,
    val savedInLocalStoragePath: String = ""
)
