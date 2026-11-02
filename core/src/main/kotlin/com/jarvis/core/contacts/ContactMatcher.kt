package com.jarvis.core.contacts

import com.jarvis.core.apps.AppMatcher
import java.text.Normalizer

/**
 * "Which contact did the user mean?" — pure logic, the platform supplies (id, display name) pairs read from the address book.
 * Case, accents and punctuation/emoji are ignored ("José ❤️" = "jose"), common family nicknames are treated as the same word (mom = mum = mother),
 * and speech-recognition near-misses ("Pria" for "Priya") are matched by similarity. An exact (or nickname-exact) match wins outright, so
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
        return if (best >= 0.86) best * 0.92 else 0.0
    }

    /** Best matches first. Returns only the exact ones when any exist, otherwise those close to the best score (max [limit]). */
    fun rank(query: String, people: List<Pair<String, String>>, limit: Int = 5): List<Scored> {
        val scored = people.map { (id, name) -> Scored(id, name, score(query, name)) }.filter { it.score >= MIN }.sortedWith(compareByDescending<Scored> { it.score }.thenBy { it.name.lowercase() })
        if (scored.isEmpty()) return emptyList()
        val best = scored.first().score
        val kept = if (best >= EXACT) scored.filter { it.score >= EXACT } else scored.filter { best - it.score <= 0.08 }
        return kept.take(limit)
    }
}
