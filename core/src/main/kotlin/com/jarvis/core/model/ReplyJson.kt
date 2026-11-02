package com.jarvis.core.model

import com.jarvis.core.json.Json

/**
 * Stable JSON form of [AssistantReply] for the chat history, so old messages look the same after a restart (card, buttons, icon).
 * Unknown / future fields are ignored when reading, values of an unexpected type fall back to defaults, and `extras` keeps only JSON-safe values
 * (strings, numbers, booleans and lists / maps of those) so a odd tool result can never make a stored row unreadable.
 */
object ReplyJson {
    fun toJson(r: AssistantReply): String = Json.stringify(toMap(r))

    fun toMap(r: AssistantReply): Map<String, Any?> = linkedMapOf(
        "v" to 1, "text" to r.text, "ok" to r.ok, "kind" to r.kind.name, "icon" to r.icon, "speak" to r.speak, "sensitive" to r.sensitive,
        "card" to r.card?.let(::cardMap),
        "actions" to r.actions.map { mapOf("label" to it.label, "type" to it.type.name, "payload" to it.payload) },
        "missing" to r.missing.map { it.name },
        "extras" to safe(r.extras),
    )

    fun fromJson(text: String?): AssistantReply? {
        val j = Json.parseObjectOrNull(text) ?: return null
        val body = Json.str(j["text"]) ?: return null
        return AssistantReply(
            text = body,
            ok = Json.bool(j["ok"]) ?: true,
            kind = enumOf(Json.str(j["kind"]), ReplyKind.ACTION),
            icon = Json.str(j["icon"]) ?: "check",
            card = Json.obj(j["card"])?.let(::cardOf),
            speak = Json.bool(j["speak"]) ?: true,
            actions = Json.list(j["actions"]).orEmpty().mapNotNull { Json.obj(it) }.mapNotNull { a ->
                val label = Json.str(a["label"]) ?: return@mapNotNull null
                val type = enumOrNull<ActionType>(Json.str(a["type"])) ?: return@mapNotNull null
                UiAction(label, type, Json.obj(a["payload"]).orEmpty().mapNotNull { (k, v) -> Json.str(v)?.let { k to it } }.toMap())
            },
            missing = Json.list(j["missing"]).orEmpty().mapNotNull { enumOrNull<Requirement>(Json.str(it)) },
            sensitive = Json.bool(j["sensitive"]) ?: false,
            extras = Json.obj(j["extras"]).orEmpty(),
        )
    }

    private fun cardMap(c: Card): Map<String, Any?> = linkedMapOf(
        "type" to c.type, "title" to c.title, "subtitle" to c.subtitle, "icon" to c.icon, "url" to c.url, "fields" to c.fields,
        "items" to c.items.map { mapOf("text" to it.text, "sub" to it.sub) },
    )

    private fun cardOf(m: Map<String, Any?>): Card? {
        val type = Json.str(m["type"]) ?: return null
        return Card(
            type, Json.str(m["title"]).orEmpty(), Json.str(m["subtitle"]).orEmpty(), Json.str(m["icon"]).orEmpty(),
            Json.list(m["items"]).orEmpty().mapNotNull { Json.obj(it) }.map { CardItem(Json.str(it["text"]).orEmpty(), Json.str(it["sub"]).orEmpty()) },
            Json.str(m["url"]), Json.obj(m["fields"]).orEmpty().mapNotNull { (k, v) -> Json.str(v)?.let { k to it } }.toMap(),
        )
    }

    private fun safe(m: Map<String, Any?>): Map<String, Any?> = m.mapNotNull { (k, v) -> safeValue(v, 0)?.let { k to it } }.toMap()

    private fun safeValue(v: Any?, depth: Int): Any? = when {
        depth > 4 -> null
        v == null || v is String || v is Boolean || v is Int || v is Long || v is Double -> v
        v is Number -> v.toDouble()
        v is Enum<*> -> v.name
        v is List<*> -> v.mapNotNull { safeValue(it, depth + 1) }
        v is Map<*, *> -> v.entries.mapNotNull { (k, x) -> safeValue(x, depth + 1)?.let { k.toString() to it } }.toMap()
        v is Card -> cardMap(v)
        v is UiAction -> mapOf("label" to v.label, "type" to v.type.name, "payload" to v.payload)
        else -> null
    }

    private inline fun <reified E : Enum<E>> enumOrNull(s: String?): E? = enumValues<E>().firstOrNull { it.name == s }
    private inline fun <reified E : Enum<E>> enumOf(s: String?, default: E): E = enumOrNull<E>(s) ?: default
}
