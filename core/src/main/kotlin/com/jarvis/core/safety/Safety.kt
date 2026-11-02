package com.jarvis.core.safety

/**
 * Safety layer (port of Backend/Safety.py): the assistant can NOT delete anything on its own.
 *  1. [isDeleteRequest]  - catches user requests (typed or spoken).
 *  2. [isBlockedCommand] - catches anything an LLM decision might emit.
 * No tool has a delete path. Deleting reminders/notes/files/conversations exists ONLY as an explicit tap-and-confirm action in the
 * app UI (the "Library" and "Reminders" screens), never through the AI, voice or notification actions.
 *
 * Android replaces the Windows "protected process" rules with [protectedPackageReason] (JARVIS itself, SystemUI, the package installer ...).
 */
object Safety {
    val REFUSALS = listOf(
        "I'm not able to delete anything. That capability is deliberately switched off.",
        "Deleting isn't something I can do by voice or chat. You can remove items yourself from the Library or Reminders screens.",
        "I don't have permission to delete or erase anything, so I'll leave that one for you to do manually.",
    )

    private const val HARD =
        "(delete|erase|wipe|shred|destroy|uninstall|rmdir|purge|trash|obliterate|annihilate|factory\\s+reset|" +
            "format\\s+(?:the\\s+)?(?:drive|disk|disc|usb|partition|phone|sd\\s*card)|rm\\s+-?r?f?|remove-item|empty\\s+(?:the\\s+)?(?:recycle\\s*bin|trash|bin))"
    private const val SOFT_VERB = "(remove|clear|get\\s+rid\\s+of|throw\\s+away|discard|drop)"
    private const val SOFT_OBJ =
        "(file|files|folder|folders|directory|document|documents|photo|photos|picture|pictures|" +
            "image|images|video|videos|app|apps|application|program|software|history|log|logs|chat|" +
            "chats|data|cache|recycle|bin|download|downloads|everything|all|memory|notes?|reminders?|" +
            "conversation|messages?|it|them|that|this)"

    private val HARD_RE = Regex("\\b$HARD\\b", RegexOption.IGNORE_CASE)
    private val SOFT_RE = Regex("\\b$SOFT_VERB\\b(?:\\s+\\w+){0,3}?\\s+\\b$SOFT_OBJ\\b", RegexOption.IGNORE_CASE)
    private val ALLOW = Regex("\\b(remove|clear)\\s+(?:the\\s+)?(?:background\\s+noise|noise|distractions?|focus|filter)\\b", RegexOption.IGNORE_CASE)

    fun isDeleteRequest(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        val t = text.trim()
        if (HARD_RE.containsMatchIn(t)) return true
        if (ALLOW.containsMatchIn(t)) return false
        return SOFT_RE.containsMatchIn(t)
    }

    fun isBlockedCommand(cmd: String?): Boolean {
        val c = cmd?.trim()?.lowercase() ?: ""
        return c.startsWith("blocked") || isDeleteRequest(c)
    }

    fun refusal(seed: String = ""): String =
        if (seed.isEmpty()) REFUSALS[0] else REFUSALS[seed.sumOf { it.code } % REFUSALS.size]

    // ----------------------------------------------------------------------------------------------- Android system protection
    /** Packages JARVIS never tries to control beyond opening them: itself and core system UI. */
    private val PROTECTED_PACKAGES = setOf(
        "com.android.systemui", "com.android.packageinstaller", "com.google.android.packageinstaller",
        "com.android.permissioncontroller", "com.google.android.permissioncontroller", "android",
        "com.android.shell", "com.android.providers.settings",
    )

    fun protectedPackageReason(packageName: String, ownPackage: String): String? = when {
        packageName == ownPackage -> "That's JARVIS itself, so I won't act on it."
        packageName in PROTECTED_PACKAGES -> "That's part of the Android system, so I leave it alone."
        else -> null
    }

    /** Paths JARVIS never opens, saves into, or searches: other apps' private data and system partitions. */
    fun isProtectedPath(path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        val p = path.trim().lowercase().replace('\\', '/')
        return p.startsWith("/system") || p.startsWith("/data/data") || p.startsWith("/data/user") || p.startsWith("/proc") ||
            p.startsWith("/vendor") || p.startsWith("/apex") || p.contains("/android/data/") || p.contains("/android/obb/")
    }

    fun protectedPathReason(path: String?): String? =
        if (isProtectedPath(path)) "That's a protected system or app-private location. I don't open, search or change anything there." else null
}
