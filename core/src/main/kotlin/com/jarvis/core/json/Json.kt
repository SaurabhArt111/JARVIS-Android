package com.jarvis.core.json

/**
 * Tiny dependency-free JSON reader/writer.
 *
 * Values are plain Kotlin: `Map<String, Any?>`, `List<Any?>`, `String`, `Long`/`Double`, `Boolean`, `null`.
 * It exists so that `:core` has no serialization-library version to keep in sync with the Android app, and so
 * that provider payload handling (tool calls, error bodies, malformed model output) is fully unit-tested.
 */
object Json {
    class JsonException(message: String) : RuntimeException(message)

    /** Parses [text]. Throws [JsonException] on malformed input. */
    fun parse(text: String): Any? {
        val p = Parser(text)
        p.skipWs()
        val v = p.readValue()
        p.skipWs()
        if (!p.atEnd()) throw JsonException("Unexpected trailing data at ${p.pos}")
        return v
    }

    /** Parses [text] and returns the value only if it is a JSON object, otherwise null (never throws). */
    @Suppress("UNCHECKED_CAST")
    fun parseObjectOrNull(text: String?): Map<String, Any?>? {
        if (text.isNullOrBlank()) return null
        return try {
            parse(text) as? Map<String, Any?>
        } catch (e: JsonException) {
            null
        }
    }

    /** Like [parseObjectOrNull] but also tries to recover the first `{...}` block inside surrounding prose. */
    fun parseObjectLenient(text: String?): Map<String, Any?>? {
        parseObjectOrNull(text)?.let { return it }
        if (text == null) return null
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val block = text.substring(start, end + 1)
        return parseObjectOrNull(block) ?: parseObjectOrNull(repair(block))
    }

    /**
     * Fixes what small models write instead of JSON: single-quoted strings, trailing commas, Python's True/False/None. Only used after strict parsing failed,
     * and only on the `{...}` block; the result is still parsed strictly.
     */
    fun repair(s: String): String {
        val sb = StringBuilder(s.length + 8)
        var quote = '\u0000'
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (quote != '\u0000') {
                when {
                    c == '\\' && i + 1 < s.length -> {
                        val n = s[i + 1]
                        if (n == '\'') sb.append('\'') else sb.append(c).append(n)
                        i += 2; continue
                    }
                    c == quote -> { sb.append('"'); quote = '\u0000' }
                    c == '"' -> sb.append("\\\"")
                    else -> sb.append(c)
                }
                i++; continue
            }
            when {
                c == '"' || c == '\'' -> { quote = c; sb.append('"'); i++ }
                c.isLetter() -> {
                    var j = i
                    while (j < s.length && (s[j].isLetterOrDigit() || s[j] == '_')) j++
                    sb.append(when (val w = s.substring(i, j)) { "True" -> "true"; "False" -> "false"; "None" -> "null"; else -> w })
                    i = j
                }
                else -> { sb.append(c); i++ }
            }
        }
        return TRAILING_COMMA.replace(sb.toString(), "$1")
    }

    private val TRAILING_COMMA = Regex(",\\s*([}\\]])")

    fun stringify(value: Any?, pretty: Boolean = false): String {
        val sb = StringBuilder()
        write(sb, value, if (pretty) 0 else -1)
        return sb.toString()
    }

    // ---------------------------------------------------------------- typed accessors (null-safe)
    @Suppress("UNCHECKED_CAST")
    fun obj(v: Any?): Map<String, Any?>? = v as? Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    fun list(v: Any?): List<Any?>? = v as? List<Any?>

    fun str(v: Any?): String? = when (v) {
        null -> null
        is String -> v
        is Number, is Boolean -> v.toString()
        else -> null
    }

    fun num(v: Any?): Double? = when (v) {
        is Number -> v.toDouble()
        is String -> v.trim().toDoubleOrNull()
        else -> null
    }

    fun int(v: Any?): Int? = num(v)?.toInt()

    fun bool(v: Any?): Boolean? = when (v) {
        is Boolean -> v
        is String -> when (v.trim().lowercase()) {
            "true", "yes", "on", "1" -> true
            "false", "no", "off", "0" -> false
            else -> null
        }
        is Number -> v.toInt() != 0
        else -> null
    }

    /** Walks nested maps/lists with string keys and integer indexes: `path(json, "choices", 0, "message")`. */
    fun path(root: Any?, vararg keys: Any): Any? {
        var cur = root
        for (k in keys) {
            cur = when {
                k is String && cur is Map<*, *> -> cur[k]
                k is Int && cur is List<*> -> cur.getOrNull(k)
                else -> return null
            }
        }
        return cur
    }

    // ---------------------------------------------------------------- writer
    private fun write(sb: StringBuilder, v: Any?, indent: Int) {
        when (v) {
            null -> sb.append("null")
            is String -> quote(sb, v)
            is Boolean -> sb.append(v.toString())
            is Int, is Long, is Short, is Byte -> sb.append(v.toString())
            is Float -> sb.append(if (v.isNaN() || v.isInfinite()) "null" else numberText(v.toDouble()))
            is Double -> sb.append(if (v.isNaN() || v.isInfinite()) "null" else numberText(v))
            is Number -> sb.append(v.toString())
            is Map<*, *> -> {
                if (v.isEmpty()) { sb.append("{}"); return }
                sb.append('{')
                var first = true
                for ((k, value) in v) {
                    if (!first) sb.append(',')
                    first = false
                    newline(sb, indent + 1)
                    quote(sb, k.toString())
                    sb.append(if (indent >= 0) ": " else ":")
                    write(sb, value, if (indent >= 0) indent + 1 else -1)
                }
                newline(sb, indent)
                sb.append('}')
            }
            is Iterable<*> -> {
                val items = v.toList()
                if (items.isEmpty()) { sb.append("[]"); return }
                sb.append('[')
                var first = true
                for (item in items) {
                    if (!first) sb.append(',')
                    first = false
                    newline(sb, indent + 1)
                    write(sb, item, if (indent >= 0) indent + 1 else -1)
                }
                newline(sb, indent)
                sb.append(']')
            }
            is Array<*> -> write(sb, v.toList(), indent)
            else -> quote(sb, v.toString())
        }
    }

    private fun numberText(d: Double): String =
        if (d == Math.rint(d) && Math.abs(d) < 1e15) d.toLong().toString() else d.toString()

    private fun newline(sb: StringBuilder, depth: Int) {
        if (depth < 0) return
        sb.append('\n')
        repeat(depth) { sb.append("  ") }
    }

    private fun quote(sb: StringBuilder, s: String) {
        sb.append('"')
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (ch < ' ') sb.append("\\u").append(String.format("%04x", ch.code)) else sb.append(ch)
            }
        }
        sb.append('"')
    }

    // ---------------------------------------------------------------- parser
    private class Parser(private val s: String) {
        var pos = 0
        fun atEnd() = pos >= s.length

        fun skipWs() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }

        fun readValue(): Any? {
            if (atEnd()) throw JsonException("Unexpected end of input")
            return when (val c = s[pos]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) readNumber() else throw JsonException("Unexpected '$c' at $pos")
            }
        }

        private fun literal(word: String, value: Any?): Any? {
            if (!s.startsWith(word, pos)) throw JsonException("Bad literal at $pos")
            pos += word.length
            return value
        }

        private fun readObject(): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            pos++ // {
            skipWs()
            if (pos < s.length && s[pos] == '}') { pos++; return out }
            while (true) {
                skipWs()
                if (atEnd() || s[pos] != '"') throw JsonException("Expected string key at $pos")
                val key = readString()
                skipWs()
                if (atEnd() || s[pos] != ':') throw JsonException("Expected ':' at $pos")
                pos++
                skipWs()
                out[key] = readValue()
                skipWs()
                if (atEnd()) throw JsonException("Unterminated object")
                when (s[pos]) {
                    ',' -> pos++
                    '}' -> { pos++; return out }
                    else -> throw JsonException("Expected ',' or '}' at $pos")
                }
            }
        }

        private fun readArray(): List<Any?> {
            val out = ArrayList<Any?>()
            pos++ // [
            skipWs()
            if (pos < s.length && s[pos] == ']') { pos++; return out }
            while (true) {
                skipWs()
                out.add(readValue())
                skipWs()
                if (atEnd()) throw JsonException("Unterminated array")
                when (s[pos]) {
                    ',' -> pos++
                    ']' -> { pos++; return out }
                    else -> throw JsonException("Expected ',' or ']' at $pos")
                }
            }
        }

        private fun readString(): String {
            pos++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) throw JsonException("Unterminated string")
                val c = s[pos++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (atEnd()) throw JsonException("Bad escape")
                        when (val e = s[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > s.length) throw JsonException("Bad \\u escape")
                                val hex = s.substring(pos, pos + 4)
                                sb.append(hex.toIntOrNull(16)?.toChar() ?: throw JsonException("Bad \\u escape '$hex'"))
                                pos += 4
                            }
                            else -> throw JsonException("Bad escape '\\$e'")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun readNumber(): Any {
            val start = pos
            if (s[pos] == '-') pos++
            while (pos < s.length && (s[pos].isDigit() || s[pos] in ".eE+-")) pos++
            val text = s.substring(start, pos)
            return text.toLongOrNull() ?: text.toDoubleOrNull() ?: throw JsonException("Bad number '$text'")
        }
    }
}
