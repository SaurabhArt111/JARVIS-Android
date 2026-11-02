package com.jarvis.core.model

/** What kind of answer this is. Drives styling, speech, and whether it is stored as "something JARVIS did". */
enum class ReplyKind { ACTION, CHAT, ERROR, BLOCKED, INFO }

/** Where a request came from. Used for speech policy, lock-screen policy and logging. */
enum class Origin { TEXT, VOICE, WAKE, ISLAND, LOCK_SCREEN, ASSISTANT_GESTURE, NOTIFICATION, SCHEDULED }

/** Things the user (or the system) must provide before a capability can work. Shown in the permission screen and in replies. */
enum class Requirement {
    MICROPHONE, NOTIFICATIONS, CONTACTS, CALL_PHONE, OVERLAY, NOTIFICATION_LISTENER, ACCESSIBILITY,
    EXACT_ALARM, WRITE_SETTINGS, DND_ACCESS, FULL_SCREEN_INTENT, BATTERY_OPTIMIZATION, ASSISTANT_ROLE,
    UNLOCK, NETWORK, API_KEY, APP_INSTALLED
}

data class CardItem(val text: String, val sub: String = "")

/** Rich content for the chat list / island. [type] values: info, items, calendar, timer, weather, music, level, system, images, generating, browse, choice, file. */
data class Card(
    val type: String,
    val title: String = "",
    val subtitle: String = "",
    val icon: String = "",
    val items: List<CardItem> = emptyList(),
    val url: String? = null,
    val fields: Map<String, String> = emptyMap(),
)

enum class ActionType {
    /** Open the system screen / dialog that grants [Requirement] (payload: "requirement"). */
    GRANT, OPEN_URL, OPEN_FILE, OPEN_SETTINGS_PAGE,
    /** Answer a pending question with a fixed option (payload: "answer"). */
    CHOOSE, CONFIRM, CANCEL, UNLOCK, OPEN_APP_SCREEN,
}

/** A button under a reply. */
data class UiAction(val label: String, val type: ActionType, val payload: Map<String, String> = emptyMap())

/** The one answer type that flows from the engine to every UI surface (chat, island, lock screen, notification, speech). */
data class AssistantReply(
    val text: String,
    val ok: Boolean = true,
    val kind: ReplyKind = ReplyKind.ACTION,
    val icon: String = "check",
    val card: Card? = null,
    val speak: Boolean = true,
    val actions: List<UiAction> = emptyList(),
    val missing: List<Requirement> = emptyList(),
    /** Never speak or show the body on a locked screen when true (personal content). */
    val sensitive: Boolean = false,
    val extras: Map<String, Any?> = emptyMap(),
) {
    companion object {
        fun error(text: String, icon: String = "alert", missing: List<Requirement> = emptyList(), actions: List<UiAction> = emptyList()) =
            AssistantReply(text, ok = false, kind = ReplyKind.ERROR, icon = icon, missing = missing, actions = actions)

        fun chat(text: String, icon: String = "message") = AssistantReply(text, kind = ReplyKind.CHAT, icon = icon)
    }
}

/**
 * What a tool reports back. [note] is the factual text the LLM sees (never shown verbatim); [reply] is what the UI shows.
 * [verified] is true only when the tool re-checked its own post-condition (row read back, intent resolved, state re-read).
 */
data class Outcome(
    val ok: Boolean,
    val note: String,
    val reply: AssistantReply? = null,
    /** True when the model needs the data to word its answer (lists, weather, search results...). */
    val info: Boolean = false,
    val verified: Boolean = false,
) {
    fun toModel(limit: Int = 1400): String {
        val t = if (ok) note else "FAILED: $note"
        return if (t.length <= limit) t else t.take(limit - 1) + "…"
    }

    companion object {
        fun fail(text: String, icon: String = "alert", missing: List<Requirement> = emptyList(), actions: List<UiAction> = emptyList()) =
            Outcome(false, text, AssistantReply.error(text, icon, missing, actions))

        fun blocked(text: String) =
            Outcome(false, "BLOCKED for safety: $text Tell the user plainly and do not try again.",
                AssistantReply(text, ok = false, kind = ReplyKind.BLOCKED, icon = "shield"))
    }
}
