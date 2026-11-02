package com.jarvis.core.contacts

/**
 * Phone numbers for places that need the full international form (WhatsApp's wa.me links want digits only, with the country code, no leading zeros).
 * Pure logic with a small country table; it never guesses: when the country is unknown and the number has no "+" / "00" prefix it returns null.
 */
object PhoneNumbers {
    private val CALLING_CODE = mapOf(
        "IN" to "91", "US" to "1", "CA" to "1", "GB" to "44", "AU" to "61", "NZ" to "64", "IE" to "353", "DE" to "49", "FR" to "33", "ES" to "34",
        "IT" to "39", "NL" to "31", "BE" to "32", "CH" to "41", "AT" to "43", "SE" to "46", "NO" to "47", "DK" to "45", "FI" to "358", "PT" to "351",
        "PL" to "48", "RU" to "7", "UA" to "380", "TR" to "90", "AE" to "971", "SA" to "966", "QA" to "974", "KW" to "965", "OM" to "968", "BH" to "973",
        "EG" to "20", "ZA" to "27", "NG" to "234", "KE" to "254", "GH" to "233", "PK" to "92", "BD" to "880", "LK" to "94", "NP" to "977", "SG" to "65",
        "MY" to "60", "ID" to "62", "PH" to "63", "TH" to "66", "VN" to "84", "JP" to "81", "KR" to "82", "CN" to "86", "HK" to "852", "TW" to "886",
        "BR" to "55", "MX" to "52", "AR" to "54", "CO" to "57", "CL" to "56", "PE" to "51", "IL" to "972", "IQ" to "964", "JO" to "962", "LB" to "961",
        "MA" to "212", "DZ" to "213", "GR" to "30", "CZ" to "420", "RO" to "40", "HU" to "36",
    )

    fun callingCode(regionIso: String?): String? = regionIso?.trim()?.uppercase()?.let { CALLING_CODE[it] }

    /** Digits only (no "+"), e.g. "919876543210", or null when the country cannot be determined or the number is implausible. */
    fun toInternational(raw: String, regionIso: String?): String? {
        val t = raw.trim()
        val plus = t.startsWith("+")
        var d = t.filter { it.isDigit() }
        if (d.isEmpty()) return null
        if (plus) return d.takeIf { it.length in 8..15 }
        if (d.startsWith("00")) return d.drop(2).takeIf { it.length in 8..15 }
        val cc = callingCode(regionIso) ?: return null
        if (d.length >= 11 && d.startsWith(cc)) return d.takeIf { it.length <= 15 }        // already international, written without "+"
        if (d.startsWith("0")) d = d.drop(1)                                                // national trunk prefix
        val out = cc + d
        return out.takeIf { d.length >= 6 && it.length <= 15 }
    }

    /** What may be dialed: digits and a leading "+", nothing else. USSD / MMI codes (* and #) are refused: they can change phone settings or run carrier services. */
    fun dialable(raw: String): String? {
        val t = raw.trim()
        if (t.any { it == '*' || it == '#' }) return null
        val cleaned = (if (t.startsWith("+")) "+" else "") + t.filter { it.isDigit() }
        return cleaned.takeIf { it.count { c -> c.isDigit() } in 3..16 }
    }
}
