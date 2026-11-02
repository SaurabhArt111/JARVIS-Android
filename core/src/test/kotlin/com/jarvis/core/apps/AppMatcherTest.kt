package com.jarvis.core.apps

import org.junit.Assert.*
import org.junit.Test

class AppMatcherTest {
    private val apps = listOf(
        "Chrome" to "com.android.chrome", "YouTube" to "com.google.android.youtube", "Spotify" to "com.spotify.music",
        "WhatsApp" to "com.whatsapp", "Brave" to "com.brave.browser", "Google Maps" to "com.google.android.apps.maps",
        "Photos" to "com.google.android.apps.photos", "Far Cry 5" to "com.ubisoft.farcry5", "Settings" to "com.android.settings",
    )

    @Test fun exactAndAliasMatches() {
        assertEquals("com.google.android.youtube", AppMatcher.resolve("youtube", apps).first().packageName)
        assertEquals("com.google.android.youtube", AppMatcher.resolve("yt", apps).first().packageName)
        assertEquals("com.whatsapp", AppMatcher.resolve("whats app", apps).first().packageName)
        assertEquals("com.android.chrome", AppMatcher.resolve("browser", apps).first().packageName)
        assertEquals("com.google.android.apps.maps", AppMatcher.resolve("maps", apps).first().packageName)
    }

    @Test fun typosStillMatchButStrangersDoNot() {
        assertEquals("com.spotify.music", AppMatcher.resolve("spotfy", apps).first().packageName)
        assertTrue(AppMatcher.resolve("photoshop", apps).isEmpty())
        assertTrue(AppMatcher.resolve("", apps).isEmpty())
    }

    @Test fun spokenAndRomanNumbers() {
        assertEquals("com.ubisoft.farcry5", AppMatcher.resolve("far cry five", apps).first().packageName)
        assertEquals("com.ubisoft.farcry5", AppMatcher.resolve("far cry v", apps).first().packageName)
    }

    @Test fun ratioMatchesDifflibExample() {
        assertEquals(0.75, AppMatcher.ratio("abcd", "bcde"), 1e-9) // difflib: 2*3/8
        assertEquals(1.0, AppMatcher.ratio("same", "same"), 1e-9)
    }
}
