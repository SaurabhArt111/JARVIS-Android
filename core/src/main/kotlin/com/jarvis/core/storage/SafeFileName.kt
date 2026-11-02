package com.jarvis.core.storage

import java.text.Normalizer

/** Safe, readable, collision-free file names. Pure logic (no I/O): the platform passes in the names that already exist. */
object SafeFileName {
    private val RESERVED = setOf(
        "con", "prn", "aux", "nul", "com1", "com2", "com3", "com4", "lpt1", "lpt2", "lpt3", "clock\$",
    )

    /** "Plan for Q3 / budget?" -> "plan_for_q3_budget". Never empty, never starts with a dot, max [maxLen] chars. */
    fun stem(title: String, maxLen: Int = 60, fallback: String = "file"): String {
        val ascii = Normalizer.normalize(title, Normalizer.Form.NFKD).replace(Regex("\\p{M}+"), "")
        var s = ascii.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
        if (s.length > maxLen) s = s.take(maxLen).trim('_')
        if (s.isEmpty()) s = fallback
        if (s in RESERVED) s = "${s}_file"
        return s
    }

    /** Keeps only a..z 0..9 in the extension, lower case, max 8 chars; "" when nothing usable remains. */
    fun extension(ext: String): String = ext.trim().trimStart('.').lowercase().filter { it in 'a'..'z' || it in '0'..'9' }.take(8)

    /**
     * Returns "stem.ext", or "stem_2.ext", "stem_3.ext" ... so that it is not in [existing] (compared case-insensitively).
     * Existing files are NEVER overwritten.
     */
    fun unique(title: String, extension: String, existing: Collection<String>, maxLen: Int = 60): String {
        val taken = existing.map { it.lowercase() }.toHashSet()
        val ext = extension(extension)
        val stem = stem(title, maxLen)
        val suffix = if (ext.isEmpty()) "" else ".$ext"
        var candidate = "$stem$suffix"
        var i = 2
        while (candidate.lowercase() in taken) {
            candidate = "${stem}_$i$suffix"
            i++
            if (i > 10_000) { candidate = "${stem}_${System.nanoTime()}$suffix"; break }
        }
        return candidate
    }

    /** True if [name] is a plain file name that cannot escape its folder. */
    fun isSafeName(name: String): Boolean =
        name.isNotEmpty() && name.length <= 120 && !name.contains('/') && !name.contains('\\') && !name.contains("..") &&
            !name.startsWith(".") && name.none { it.code < 32 }
}
