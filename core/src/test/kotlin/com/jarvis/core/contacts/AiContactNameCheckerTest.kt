package com.jarvis.core.contacts

import org.junit.Assert.assertEquals
import org.junit.Test

class AiContactNameCheckerTest {
    private val names = listOf("Dhruv Indori SSL GD", "Dhruv Sharma", "Mom")

    @Test fun exactNameIsAccepted() = assertEquals(listOf("Dhruv Indori SSL GD"), AiContactNameChecker.parse("Dhruv Indori SSL GD", names))
    @Test fun bulletsQuotesAndNumbersAreIgnored() =
        assertEquals(listOf("Dhruv Indori SSL GD", "Mom"), AiContactNameChecker.parse("1. \"dhruv indori ssl gd\"\n- Mom.", names))
    @Test fun noneMeansNobody() = assertEquals(emptyList<String>(), AiContactNameChecker.parse("NONE", names))
    @Test fun inventedNamesAreNeverTrusted() = assertEquals(emptyList<String>(), AiContactNameChecker.parse("Dhruv Indori Singh\nSure! The answer is Dhruv.", names))
    @Test fun duplicatesCollapse() = assertEquals(listOf("Mom"), AiContactNameChecker.parse("Mom\nmom", names))
}
