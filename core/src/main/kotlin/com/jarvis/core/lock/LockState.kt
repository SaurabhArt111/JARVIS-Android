package com.jarvis.core.lock

/**
 * State of the device keyguard at the time of a request.
 * [deviceLocked] = keyguard showing; [secure] = a PIN/pattern/password/biometric is configured (so "locked" actually protects something).
 * [authenticatedAtMs] = when the user last passed system authentication through JARVIS's unlock route (0 = never in this session).
 */
data class LockState(val deviceLocked: Boolean, val secure: Boolean, val authenticatedAtMs: Long = 0L) {
    /** True when personal content must be protected right now. */
    val protectPersonalData: Boolean get() = deviceLocked && secure

    companion object {
        val UNLOCKED = LockState(deviceLocked = false, secure = false)
        val LOCKED_SECURE = LockState(deviceLocked = true, secure = true)
    }
}

enum class LockVerdict {
    /** Run now. */
    ALLOW,
    /** Ask the user to confirm by voice/tap on the lock screen first. */
    CONFIRM,
    /** Not on a locked secure device: route the user to the system authentication screen (KeyguardManager.requestDismissKeyguard). */
    REQUIRE_UNLOCK,
    /** Never while locked (or disabled in settings). */
    DENY,
}

data class LockDecision(val verdict: LockVerdict, val reason: String = "")

enum class LockPrivacy { STRICT, BALANCED, OPEN;
    companion object { fun parse(s: String?): LockPrivacy = entries.firstOrNull { it.name.equals(s?.trim(), ignoreCase = true) } ?: STRICT }
}
