package com.jarvis.core.contacts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactMatcherTest {
    private val people = listOf(
        "1" to "Mom", "2" to "Priya Sharma", "3" to "Priya Nair", "4" to "José Álvarez", "5" to "Rahul", "6" to "Dad ❤️",
        "7" to "Dr. Anita Rao", "8" to "Anil Kumar", "9" to "Rahul Verma",
    )

    private fun ids(q: String) = ContactMatcher.rank(q, people).map { it.id }

    @Test fun exactNameIsReturnedAlone() = assertEquals(listOf("1"), ids("Mom"))
    @Test fun exactBeatsLongerNamesThatContainIt() = assertEquals(listOf("5"), ids("rahul"))
    @Test fun nicknameMatchesTheSameContact() { assertEquals(listOf("1"), ids("mother")); assertEquals(listOf("1"), ids("mummy")) }
    @Test fun dadWithEmojiAndPunctuation() = assertEquals(listOf("6"), ids("dad"))
    @Test fun accentsAreIgnoredBothWays() { assertEquals(listOf("4"), ids("jose alvarez")); assertEquals(listOf("4"), ids("José")) }
    @Test fun sharedFirstNameIsAmbiguousSoBothComeBack() = assertEquals(setOf("2", "3"), ids("priya").toSet())
    @Test fun fullNameNarrowsToOne() = assertEquals(listOf("2"), ids("Priya Sharma"))
    @Test fun wordOrderDoesNotMatter() = assertEquals(listOf("2"), ids("sharma priya"))
    @Test fun titlesAndPunctuationInTheContactAreIgnored() = assertEquals(listOf("7"), ids("anita rao"))
    @Test fun aSlightMisspellingOfAFirstNameStillFindsThePerson() = assertEquals(setOf("5", "9"), ids("rahull").toSet())
    @Test fun aSlightMisspellingOfAFirstNameMatches() = assertTrue(ids("priyaa").containsAll(listOf("2", "3")))
    @Test fun unknownNameMatchesNothing() = assertEquals(emptyList<String>(), ids("Zebediah"))
    @Test fun similarButDifferentNamesAreNotMerged() = assertEquals(listOf("7"), ids("anita"))
    @Test fun shortPrefixMatchesStartOfName() = assertTrue(ids("pri").containsAll(listOf("2", "3")))
    @Test fun blankQueryMatchesNothing() = assertEquals(emptyList<String>(), ids("  "))
    @Test fun limitIsRespected() = assertEquals(1, ContactMatcher.rank("priya", people, limit = 1).size)
    @Test fun resultsAreBestFirst() {
        val r = ContactMatcher.rank("rahul", people + ("10" to "Rahul Mehra"))
        assertEquals("5", r.first().id)
    }
}
