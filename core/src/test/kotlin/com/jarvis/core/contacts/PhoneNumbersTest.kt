package com.jarvis.core.contacts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneNumbersTest {
    private fun intl(n: String, r: String?) = PhoneNumbers.toInternational(n, r)

    @Test fun indianLocalNumber() = assertEquals("919876543210", intl("98765 43210", "IN"))
    @Test fun indianNumberWithTrunkZero() = assertEquals("919876543210", intl("09876543210", "in"))
    @Test fun plusPrefixIsKept() = assertEquals("919876543210", intl("+91 98765-43210", "US"))
    @Test fun numberWrittenWithCountryCodeButNoPlus() = assertEquals("919876543210", intl("919876543210", "IN"))
    @Test fun doubleZeroInternationalPrefix() = assertEquals("447911123456", intl("00 44 7911 123456", "IN"))
    @Test fun usNumberWithFormatting() = assertEquals("14155552671", intl("(415) 555-2671", "US"))
    @Test fun usNumberAlreadyStartingWithOne() = assertEquals("14155552671", intl("1 415 555 2671", "US"))
    @Test fun ukNationalNumber() = assertEquals("447911123456", intl("07911 123456", "GB"))
    @Test fun unknownRegionWithoutPrefixIsNotGuessed() = assertNull(intl("98765 43210", null))
    @Test fun unsupportedRegionIsNotGuessed() = assertNull(intl("12345678", "ZZ"))
    @Test fun tooShortIsRejected() = assertNull(intl("123", "IN"))
    @Test fun tooLongIsRejected() = assertNull(intl("+1234567890123456", "IN"))
    @Test fun emptyIsRejected() = assertNull(intl("abc", "IN"))

    @Test fun dialableKeepsDigitsAndPlus() = assertEquals("+919876543210", PhoneNumbers.dialable(" +91 (98765) 43-210 "))
    @Test fun dialableRefusesUssdAndMmiCodes() { assertNull(PhoneNumbers.dialable("*#06#")); assertNull(PhoneNumbers.dialable("*21*123#")); assertNull(PhoneNumbers.dialable("#31#555")) }
    @Test fun dialableRefusesNonsense() { assertNull(PhoneNumbers.dialable("")); assertNull(PhoneNumbers.dialable("12")); assertNull(PhoneNumbers.dialable("abc")) }
    @Test fun emergencyLengthNumbersAreAllowedToBeDialedByTheUser() = assertEquals("112", PhoneNumbers.dialable("112"))
}
