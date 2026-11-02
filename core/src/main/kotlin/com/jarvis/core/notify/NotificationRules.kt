package com.jarvis.core.notify

import com.jarvis.core.lock.LockPrivacy
import com.jarvis.core.ports.NotificationInfo
import com.jarvis.core.ports.NotificationKind

/** Pure decisions about notifications, kept out of the Android listener so they can be unit-tested. */
object NotificationRules {
    private val MESSAGING_PACKAGES = setOf(
        "com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "org.thoughtcrime.securesms", "com.google.android.apps.messaging",
        "com.samsung.android.messaging", "com.facebook.orca", "com.discord", "com.slack", "com.microsoft.teams", "com.viber.voip", "jp.naver.line.android",
    )
    private val EMAIL_PACKAGES = setOf("com.google.android.gm", "com.microsoft.office.outlook", "com.yahoo.mobile.client.android.mail", "com.fsck.k9", "ch.protonmail.android")
    private val CALL_PACKAGES = setOf("com.android.dialer", "com.google.android.dialer", "com.samsung.android.dialer", "com.android.server.telecom")
    private val LABELS = mapOf(
        "com.whatsapp" to "WhatsApp", "com.whatsapp.w4b" to "WhatsApp Business", "org.telegram.messenger" to "Telegram", "org.thoughtcrime.securesms" to "Signal",
        "com.google.android.apps.messaging" to "Messages", "com.samsung.android.messaging" to "Messages", "com.facebook.orca" to "Messenger", "com.discord" to "Discord",
        "com.slack" to "Slack", "com.microsoft.teams" to "Teams", "com.google.android.gm" to "Gmail", "com.microsoft.office.outlook" to "Outlook",
        "com.instagram.android" to "Instagram", "com.twitter.android" to "X", "com.facebook.katana" to "Facebook", "com.google.android.dialer" to "Phone",
        "com.android.dialer" to "Phone", "com.spotify.music" to "Spotify", "com.google.android.youtube" to "YouTube",
    )

    fun isWhatsApp(pkg: String) = pkg == "com.whatsapp" || pkg == "com.whatsapp.w4b"

    /**
     * Maps Notification.category (the platform's own hint; strings such as "msg", "call", "email", "social", "transport", "promo") and, when that is
     * absent or generic, the package and message style to a [NotificationKind].
     */
    fun categorize(category: String?, packageName: String, messagingStyle: Boolean): NotificationKind {
        when (category?.lowercase()) {
            "msg" -> return NotificationKind.MESSAGE
            "call", "missed_call" -> return NotificationKind.CALL
            "email" -> return NotificationKind.EMAIL
            "social" -> return NotificationKind.SOCIAL
            "transport" -> return NotificationKind.MEDIA
            "promo", "recommendation" -> return NotificationKind.PROMO
            "sys", "service", "status", "progress", "err", "alarm", "reminder", "event" -> return NotificationKind.SYSTEM
        }
        return when {
            messagingStyle || packageName in MESSAGING_PACKAGES -> NotificationKind.MESSAGE
            packageName in CALL_PACKAGES -> NotificationKind.CALL
            packageName in EMAIL_PACKAGES -> NotificationKind.EMAIL
            packageName.startsWith("com.android.systemui") || packageName == "android" -> NotificationKind.SYSTEM
            else -> NotificationKind.OTHER
        }
    }

    /** A readable app name when the system won't tell us (packages we can't see): known names first, otherwise the last package segment, capitalised. */
    fun labelFromPackage(pkg: String): String {
        LABELS[pkg]?.let { return it }
        val seg = pkg.split('.').lastOrNull { it.isNotBlank() && it !in setOf("android", "app", "apps", "mobile", "client") } ?: pkg
        return seg.replaceFirstChar { it.uppercase() }
    }

    /** Same rule as LockPolicy for the read_notifications tool: lock-screen mode on, privacy above STRICT, and the "read notifications on the lock screen" switch on. */
    fun lockReadAllowed(lockModeOn: Boolean, privacy: LockPrivacy, readSwitchOn: Boolean): Boolean =
        lockModeOn && privacy != LockPrivacy.STRICT && readSwitchOn

    data class AnnounceContext(
        /** Master switch: "read notifications aloud". */
        val aloud: Boolean,
        /** Locked and the lock is secure: personal data is protected. */
        val lockProtected: Boolean,
        /** "Read notifications on the lock screen" is on. */
        val lockReadAllowed: Boolean,
        val privacy: LockPrivacy,
        val nowMs: Long,
        val lastAnnouncementMs: Long,
        val ownPackage: String,
        val minGapMs: Long = 4_000,
    )

    /**
     * What to say aloud for a newly posted notification, or null for silence. Only messages and calls are ever read, never ongoing or own notifications,
     * never faster than [AnnounceContext.minGapMs]. On a protected lock screen nothing is read unless the user allowed lock-screen notification reading, and then
     * only who it is from (the body too only with privacy OPEN).
     */
    fun announcement(n: NotificationInfo, c: AnnounceContext): String? {
        if (!c.aloud || n.ongoing || n.packageName == c.ownPackage) return null
        if (n.kind != NotificationKind.MESSAGE && n.kind != NotificationKind.CALL) return null
        if (c.nowMs - c.lastAnnouncementMs < c.minGapMs) return null
        val showBody = !c.lockProtected || c.privacy == LockPrivacy.OPEN
        if (c.lockProtected && !c.lockReadAllowed) return null
        val sender = (n.conversation ?: n.title).ifBlank { n.appLabel }
        val body = n.text.replace(Regex("\\s+"), " ").trim().take(140)
        return when (n.kind) {
            NotificationKind.CALL -> if (showBody) "Incoming call from ${n.text.ifBlank { sender }.take(60)}." else "Incoming call."
            else -> if (showBody && body.isNotEmpty()) "New message from $sender: $body" else "New message from $sender."
        }
    }
}
