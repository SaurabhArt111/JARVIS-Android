package com.jarvis.core.contacts

import com.jarvis.core.apps.AppMatcher
import java.text.Normalizer

/**
 * "Which contact did the user mean?" — pure logic, the platform supplies (id, display name) pairs read from the address book.
 * Case, accents and punctuation/emoji are ignored ("José ❤️" = "jose"), common family nicknames are treated as the same word (mom = mum = mother),
 * and speech-recognition near-misses ("Pria" for "Priya") are matched by similarity AND by sound: every word the user said is compared with the words of the
 * contact one by one ([NamePhonetics]), so "Dhruv bindori" / "Dhruv indoor" still find "Dhruv Indori SSL GD". An exact (or nickname-exact) match wins outright, so
 * a contact called "Mom" is returned alone and JARVIS does not ask "which one?" needlessly; only genuinely ambiguous names return several.
 */
object ContactMatcher {
    data class Scored(val id: String, val name: String, val score: Double)

    private val ALIAS_GROUPS = listOf(
        setOf("mom", "mum", "mother", "mama", "mummy", "mommy", "amma", "maa", "mother"),
        setOf("dad", "father", "papa", "daddy", "pop", "pops", "appa", "baba"),
        setOf("bro", "brother", "bhai"),
        setOf("sis", "sister", "didi"),
        setOf("grandma", "granny", "nani", "nana", "grandmother"),
        setOf("grandpa", "grandfather", "dada", "grandad"),
        setOf("wife", "wifey"),
        setOf("hubby", "husband"),
    )
    private val ALIAS_KEY: Map<String, Int> = buildMap { ALIAS_GROUPS.forEachIndexed { i, g -> g.forEach { put(it, i) } } }

    const val EXACT = 0.97
    const val MIN = 0.80

    fun tokens(s: String): List<String> {
        val t = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFKD).replace(Regex("\\p{M}+"), "")
        return Regex("[a-z0-9]+").findAll(t).map { it.value }.toList()
    }

    private fun canon(tok: String): String = ALIAS_KEY[tok]?.let { "#$it" } ?: tok

    fun score(query: String, name: String): Double {
        val q = tokens(query)
        val n = tokens(name)
        if (q.isEmpty() || n.isEmpty()) return 0.0
        if (q == n) return 1.0
        val qc = q.map(::canon)
        val nc = n.map(::canon)
        if (qc == nc) return 0.98                                   // nickname-exact: "mother" vs "Mom"
        if (qc.size == 1 && nc.first() == qc.first()) return 0.9     // first name only: "priya" vs "Priya Sharma"
        if (qc.all { it in nc }) return 0.88                         // every word the user said is in the name (any order)
        if (qc.size == 1 && q[0].length >= 3 && n.any { it.startsWith(q[0]) }) return 0.82   // "pri" -> "Priya"
        // near-miss spelling / recognition error, whole name or first name
        val whole = AppMatcher.ratio(q.joinToString(" "), n.joinToString(" "))
        val first = if (q.size == 1) AppMatcher.ratio(q[0], n[0]) else 0.0
        val best = maxOf(whole, first)
        val lexical = if (best >= 0.86) best * 0.92 else 0.0
        return maxOf(lexical, fuzzyWords(q, n), joined(q, n))
    }

    /**
     * Word-by-word comparison: each word the user said must sound like a DIFFERENT word of the contact's name. Extra words in the contact ("SSL GD", a company, an emoji
     * word) cost a little, because "Dhruv Indori" is a better answer than "Dhruv Indori SSL GD" when both exist. Needs every spoken word to match well; returns 0 otherwise.
     */
    private fun fuzzyWords(q: List<String>, n: List<String>): Double {
        val free = n.toMutableList()
        var total = 0.0
        for (w in q) {
            var bestIdx = -1; var bestSim = 0.0
            for ((i, cand) in free.withIndex()) { val s = NamePhonetics.wordSimilarity(w, cand); if (s > bestSim) { bestSim = s; bestIdx = i } }
            if (bestIdx < 0 || bestSim < WORD_MIN) return 0.0
            total += bestSim
            free.removeAt(bestIdx)
        }
        val avg = total / q.size
        val extras = (n.size - q.size).coerceAtLeast(0)
        val score = avg * 0.97 - minOf(extras * 0.02, 0.08)
        return if (score >= MIN) score else 0.0
    }

    /** "dhruvindori" (one word) against "Dhruv Indori" or the other way round; also against the first words of a longer name ("Dhruv Indori SSL GD"). */
    private fun joined(q: List<String>, n: List<String>): Double {
        val a = q.joinToString("")
        if (a.length < 5) return 0.0
        var best = 0.0
        for (k in 1..n.size) {
            val b = n.take(k).joinToString("")
            if (b.length < 5) continue
            val s = NamePhonetics.similarity(a, b)
            val ka = NamePhonetics.key(a); val kb = NamePhonetics.key(b)
            val sound = if (ka.length >= 4 && ka == kb) 0.9 else 0.0
            val extras = minOf((n.size - k) * 0.02, 0.08)
            best = maxOf(best, (if (s >= 0.88) s * 0.92 else 0.0) - extras, sound - extras)
        }
        return best
    }

    private const val WORD_MIN = 0.78

    /** Best matches first. Returns only the exact ones when any exist, otherwise those close to the best score (max [limit]). */
    fun rank(query: String, people: List<Pair<String, String>>, limit: Int = 5): List<Scored> {
        val scored = people.map { (id, name) -> Scored(id, name, score(query, name)) }.filter { it.score >= MIN }.sortedWith(compareByDescending<Scored> { it.score }.thenBy { it.name.lowercase() })
        if (scored.isEmpty()) return emptyList()
        val best = scored.first().score
        val kept = if (best >= EXACT) scored.filter { it.score >= EXACT } else scored.filter { best - it.score <= 0.08 }
        return kept.take(limit)
    }

    /**
     * A looser list for the "did you mean ...?" step: contacts that merely SOUND somewhat like [query] (score from [LOOSE_MIN]), best first, at most [limit].
     * Never used to act on its own: it is either shown to the user or given (names only) to the AI to pick from.
     */
    fun shortlist(query: String, people: List<Pair<String, String>>, limit: Int = 8): List<Scored> {
        val q = tokens(query)
        if (q.isEmpty()) return emptyList()
        return people.map { (id, name) ->
            val strict = score(query, name)
            val n = tokens(name)
            var loose = 0.0
            if (n.isNotEmpty()) {
                // the best any single spoken word does against any word of the name, averaged over the spoken words
                loose = q.map { w -> n.maxOf { NamePhonetics.wordSimilarity(w, it) } }.average() * 0.9
            }
            Scored(id, name, maxOf(strict, loose))
        }.filter { it.score >= LOOSE_MIN }.sortedWith(compareByDescending<Scored> { it.score }.thenBy { it.name.lowercase() }).take(limit)
    }

    const val LOOSE_MIN = 0.55
}
