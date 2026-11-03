package com.jarvis.core.contacts

import com.jarvis.core.ports.ContactMatch
import com.jarvis.core.ports.ContactNumber
import com.jarvis.core.settings.Keys
import com.jarvis.core.testing.ToolHarness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Speech recognisers mangle names; the phone must still find the right contact, ask when several fit, and honour "on speaker". */
class PhoneticContactsTest {
    private val people = listOf(
        "1" to "Dhruv Indori SSL GD", "2" to "Dhruv Sharma", "3" to "Mom", "4" to "Saurabh Art 111", "5" to "Priya Nair", "6" to "Rohit Verma", "7" to "Rahul",
    )
    private fun ids(q: String) = ContactMatcher.rank(q, people).map { it.id }

    // ------------------------------------------------------------------------------------------------ sound-alike keys
    @Test fun skeletonsIgnoreVowelsAndSpellingVariants() {
        assertEquals(NamePhonetics.key("indori"), NamePhonetics.key("indoor"))
        assertEquals(NamePhonetics.key("dhruv"), NamePhonetics.key("druv"))
        assertEquals(NamePhonetics.key("dhruv"), NamePhonetics.key("dhruva"))
        assertEquals(NamePhonetics.key("saurabh"), NamePhonetics.key("sourabh"))
    }

    @Test fun wordsThatMerelyShareLettersAreNotSoundAlikes() {
        assertTrue(NamePhonetics.wordSimilarity("rahul", "rohit") < 0.6)
        assertTrue(NamePhonetics.wordSimilarity("pari", "pura") < 0.78)
    }

    // ------------------------------------------------------------------------------------------------ the reported case
    @Test fun dhruvBindoriFindsDhruvIndori() = assertEquals("1", ids("dhruv bindori").first())
    @Test fun dhruvIndoorFindsDhruvIndori() = assertEquals("1", ids("dhruv indoor").first())
    @Test fun druvIndoriFindsDhruvIndori() = assertEquals("1", ids("druv indori").first())
    @Test fun singleWordIndoriFindsHim() = assertEquals(listOf("1"), ids("indori"))
    @Test fun runTogetherNameStillMatches() = assertEquals("1", ids("dhruvindori").first())
    @Test fun sourabhFindsSaurabh() = assertEquals("4", ids("sourabh").first())
    @Test fun firstNameAloneIsAmbiguousBetweenTheDhruvs() = assertEquals(setOf("1", "2"), ids("dhruv").toSet())
    @Test fun unrelatedNamesStayUnmatched() { assertEquals(emptyList<String>(), ids("zebediah")); assertFalse("6" in ids("rahul")) }

    @Test fun shortlistOffersSoundAlikesForTheSecondOpinion() {
        val names = ContactMatcher.shortlist("dhruv bandori", people).map { it.name }
        assertTrue(names.any { it.startsWith("Dhruv Indori") })
    }

    // ------------------------------------------------------------------------------------------------ "call ... on speaker"
    private val h = ToolHarness()
    private val env get() = h.env
    private fun person(id: String, name: String, vararg nums: String) = ContactMatch(id, name, nums.map { ContactNumber(it, "Mobile") })

    @Test fun onSpeakerIsNotPartOfTheName() {
        env.contacts.contacts = listOf(person("1", "Dhruv Indori", "+91 90000 11111"))
        val o = h.run("call", "contact" to "dhruv indori on speaker")
        assertTrue(o.ok)
        assertEquals("Dhruv Indori", env.calls.calls.single().second)
        assertEquals(listOf(true), env.calls.speakerFlags)
        assertEquals(listOf("dhruv indori"), env.contacts.searched)
    }

    @Test fun speakerVariantsAreAllUnderstood() {
        env.contacts.contacts = listOf(person("1", "Mom", "+91 90000 22222"))
        for (phrase in listOf("mom on speakerphone", "mom on the loudspeaker", "mom with loud speaker", "mom in speaker mode", "mom on speak", "on speaker mom")) {
            env.calls.calls.clear(); env.calls.speakerFlags.clear()
            assertTrue(phrase, h.run("call", "contact" to phrase).ok)
            assertEquals(phrase, listOf(true), env.calls.speakerFlags)
        }
    }

    @Test fun plainCallStaysOffSpeaker() {
        env.contacts.contacts = listOf(person("1", "Mom", "+91 90000 22222"))
        h.run("call", "contact" to "mom")
        assertEquals(listOf(false), env.calls.speakerFlags)
    }

    @Test fun speakerFlagFromTheAgentIsHonoured() {
        env.contacts.contacts = listOf(person("1", "Mom", "+91 90000 22222"))
        h.run("call", "contact" to "mom", "speaker" to true)
        assertEquals(listOf(true), env.calls.speakerFlags)
    }

    // ------------------------------------------------------------------------------------------------ AI second opinion and "did you mean"
    @Test fun aiPicksTheRightContactWhenNothingMatchedLocally() {
        val indori = person("1", "Dhruv Indori SSL GD", "+91 90000 33333")
        env.contacts.contacts = emptyList(); env.contacts.suggestions = listOf(indori, person("2", "Dhruv Sharma", "+91 90000 44444"))
        env.nameChecker.answer = listOf("Dhruv Indori SSL GD")
        val o = h.run("call", "contact" to "dhruv bandory")
        assertTrue(o.ok)
        assertEquals("Dhruv Indori SSL GD", env.calls.calls.single().second)
        assertEquals(listOf("Dhruv Indori SSL GD", "Dhruv Sharma"), env.nameChecker.asked.single().second)
    }

    @Test fun onlyNamesGoToTheAiNeverNumbers() {
        env.contacts.suggestions = listOf(person("1", "Dhruv Indori", "+91 90000 33333"))
        h.run("call", "contact" to "dhruv bandory")
        assertTrue(env.nameChecker.asked.single().second.none { it.any(Char::isDigit) && it.contains("+91") })
    }

    @Test fun aiCheckCanBeSwitchedOff() {
        env.contacts.suggestions = listOf(person("1", "Dhruv Indori", "+91 90000 33333"))
        env.nameChecker.answer = listOf("Dhruv Indori")
        env.settings.setFlag(Keys.CONTACT_AI_CHECK, false)
        h.run("call", "contact" to "dhruv bandory")
        assertTrue(env.nameChecker.asked.isEmpty())
    }

    @Test fun aiCheckIsSkippedWhenPersonalDetailsMustStayOnThePhone() {
        env.contacts.suggestions = listOf(person("1", "Dhruv Indori", "+91 90000 33333"))
        env.settings.setFlag(Keys.AI_SHARE_PERSONAL, false)
        h.run("call", "contact" to "dhruv bandory")
        assertTrue(env.nameChecker.asked.isEmpty())
    }

    @Test fun whenTheAiFindsNobodyTheUserIsAskedDidYouMeanAndNothingIsDialed() {
        env.contacts.suggestions = listOf(person("1", "Dhruv Indori", "+91 90000 33333"), person("2", "Dhruv Sharma", "+91 90000 44444"))
        env.nameChecker.answer = emptyList()
        val o = h.run("call", "contact" to "dhruv bandory")
        assertTrue(env.calls.calls.isEmpty())
        assertTrue(o.reply!!.text.contains("Did you mean"))
        h.answer("2")
        assertEquals("Dhruv Sharma", env.calls.calls.single().second)
    }

    @Test fun twoEqualContactsAreAskedAboutNotGuessed() {
        env.contacts.contacts = listOf(person("1", "Dhruv Indori", "+91 90000 33333"), person("2", "Dhruv Indori Work", "+91 90000 55555"))
        val o = h.run("call", "contact" to "dhruv")
        assertTrue(env.calls.calls.isEmpty())
        assertTrue(o.reply!!.text.contains("Which one"))
        h.answer("1")
        assertEquals("Dhruv Indori", env.calls.calls.single().second)
    }

    @Test fun aSureLocalMatchNeverBothersTheAi() {
        env.contacts.contacts = listOf(person("1", "Mom", "+91 90000 22222"))
        h.run("call", "contact" to "mom")
        assertTrue(env.nameChecker.asked.isEmpty())
    }
}
