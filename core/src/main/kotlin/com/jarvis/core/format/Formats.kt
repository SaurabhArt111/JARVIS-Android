package com.jarvis.core.format

import java.util.Locale

/** Small number/text formatters shared by several screens (kept pure so they are unit-tested). */
object Formats {
    /** 83_000 -> "1:23", 3_725_000 -> "1:02:05". Negative values count as zero. */
    fun clock(ms: Long): String {
        val total = (ms.coerceAtLeast(0) + 500) / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%d:%02d", m, s)
    }

    /** Time left until [endsAtMs]: "0:42"; at or past the end -> "0:00". */
    fun countdown(endsAtMs: Long, nowMs: Long): String = clock(endsAtMs - nowMs)

    /** 1536 -> "1.5 KB". Uses 1024 steps, one decimal under 10, none above. */
    fun bytes(n: Long): String {
        if (n < 1024) return "$n B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var v = n / 1024.0
        var i = 0
        while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
        return if (v < 10) String.format(Locale.ROOT, "%.1f %s", v, units[i]) else String.format(Locale.ROOT, "%.0f %s", v, units[i])
    }

    /** "Pasted   text\n with\tgaps" -> one line, single spaces, cut at [max] with an ellipsis. */
    fun oneLine(s: String, max: Int = 80): String {
        val t = s.replace(Regex("\\s+"), " ").trim()
        return if (t.length <= max) t else t.take(max - 1).trimEnd() + "…"
    }

    /** Masks a secret for display: only the length class and the last 4 characters are shown. */
    fun maskSecret(s: String): String {
        if (s.isBlank()) return ""
        val tail = if (s.length >= 12) s.takeLast(4) else ""
        return "••••••••" + tail
    }
}
