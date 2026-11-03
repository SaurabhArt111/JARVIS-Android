package com.jarvis.core.contacts

import java.text.Normalizer

/**
 * Sound-alike helpers for personal names, tuned for what speech recognisers do to names (especially Indian-English ones):
 * "Dhruv Indori" is heard as "Dhruv bindori", "dhruv indoor", "druv indori" and so on.
 *
 * [key] reduces one word to a short consonant skeleton ("indori" and "indoor" both become "andr"; "dhruv", "druv", "dhruva" become "drv"). Two words that share a
 * skeleton, or whose skeletons differ by one letter, sound alike for our purposes. Pure Kotlin, no Android, unit-tested.
 */
object NamePhonetics {
    /** Lowercase, accents and punctuation removed; one string per word. */
    fun words(s: String): List<String> {
        val t = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFKD).replace(Regex("\\p{M}+"), "")
        return Regex("[a-z0-9]+").findAll(t).map { it.value }.toList()
    }

    private val REPLACEMENTS = listOf(
        "ph" to "f", "ck" to "k", "kh" to "k", "gh" to "g", "dh" to "d", "bh" to "b", "th" to "t", "jh" to "j", "chh" to "c", "sh" to "s", "ch" to "c",
        "tch" to "c", "wh" to "w", "ee" to "i", "ii" to "i", "oo" to "u", "uu" to "u", "ou" to "u", "au" to "o", "ai" to "e", "ei" to "e", "ey" to "e", "ay" to "e",
        "x" to "ks", "q" to "k", "z" to "j", "w" to "v", "y" to "i",
    )

    private fun isVowel(c: Char) = c == 'a' || c == 'e' || c == 'i' || c == 'o' || c == 'u'

    /** Consonant skeleton of one word: "dhruv" -> "drv", "indoor" -> "andr", "bindori" -> "bndr". Digits are kept. */
    fun key(word: String): String {
        var w = word.lowercase().filter { it in 'a'..'z' || it in '0'..'9' }
        if (w.isEmpty()) return ""
        for ((from, to) in REPLACEMENTS) w = w.replace(from, to)
        // "c" sounds like k before a/o/u and like s before e/i
        val sb = StringBuilder()
        for ((i, ch) in w.withIndex()) {
            val c = if (ch == 'c') { val next = w.getOrNull(i + 1); if (next == 'e' || next == 'i') 's' else 'k' } else ch
            sb.append(c)
        }
        w = sb.toString()
        val out = StringBuilder()
        for ((i, c) in w.withIndex()) {
            if (i == 0) { out.append(if (isVowel(c)) 'a' else c); continue }
            if (isVowel(c) || c == 'h') continue          // vowels and a silent h carry almost no identity for name matching
            if (out.isNotEmpty() && out.last() == c) continue // double letters sound like one
            out.append(c)
        }
        return out.toString()
    }

    /** Plain Levenshtein distance. */
    fun distance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    /** Ratcliff/Obershelp-like similarity 0..1 by edit distance (cheap and symmetric). */
    fun similarity(a: String, b: String): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        val m = maxOf(a.length, b.length)
        return 1.0 - distance(a, b).toDouble() / m
    }

    /**
     * How alike two single words sound/look, 0..1.
     *  1.00 identical · 0.95 same skeleton · 0.86 skeletons differ by one letter (only for skeletons of 4+) · else the spelling similarity.
     * A word that merely STARTS with the query (3+ letters: "pri" for "priya") counts 0.85.
     */
    fun wordSimilarity(heard: String, name: String): Double {
        if (heard == name) return 1.0
        if (heard.isEmpty() || name.isEmpty()) return 0.0
        val spelling = similarity(heard, name)
        val kh = key(heard)
        val kn = key(name)
        var best = spelling
        if (kh.isNotEmpty() && kh == kn && (kh.length >= 3 || spelling >= 0.7)) best = maxOf(best, 0.95)
        else if (kh.length >= 4 && kn.length >= 4 && distance(kh, kn) == 1) best = maxOf(best, 0.86)
        else if (kh.length >= 5 && kn.length >= 5 && distance(kh, kn) == 2 && spelling >= 0.6) best = maxOf(best, 0.78)
        if (heard.length >= 3 && name.startsWith(heard)) best = maxOf(best, 0.85)
        return best
    }
}
