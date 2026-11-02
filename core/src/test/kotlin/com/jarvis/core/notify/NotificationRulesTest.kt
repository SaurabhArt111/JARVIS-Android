package com.jarvis.core.notify

import com.jarvis.core.lock.LockPrivacy
import com.jarvis.core.notify.NotificationRules.AnnounceContext
import com.jarvis.core.ports.NotificationInfo
import com.jarvis.core.ports.NotificationKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationRulesTest {
    private fun info(kind: NotificationKind = NotificationKind.MESSAGE, title: String = "Asha", text: String = "Are you free tonight?", pkg: String = "com.whatsapp", ongoing: Boolean = false, conv: String? = null) =
        NotificationInfo("k", pkg, "WhatsApp", title, text, 0, kind, canReply = true, ongoing = ongoing, conversation = conv)

    private fun ctx(aloud: Boolean = true, locked: Boolean = false, lockRead: Boolean = false, privacy: LockPrivacy = LockPrivacy.STRICT, now: Long = 100_000, last: Long = 0) =
        AnnounceContext(aloud, locked, lockRead, privacy, now, last, "com.jarvis.assistant")

    @Test
    fun platformCategoriesWin() {
        assertEquals(NotificationKind.MESSAGE, NotificationRules.categorize("msg", "x.y", false))
        assertEquals(NotificationKind.CALL, NotificationRules.categorize("call", "x.y", false))
        assertEquals(NotificationKind.EMAIL, NotificationRules.categorize("email", "x.y", false))
        assertEquals(NotificationKind.SOCIAL, NotificationRules.categorize("social", "x.y", false))
        assertEquals(NotificationKind.MEDIA, NotificationRules.categorize("transport", "x.y", false))
        assertEquals(NotificationKind.PROMO, NotificationRules.categorize("promo", "x.y", false))
        assertEquals(NotificationKind.SYSTEM, NotificationRules.categorize("sys", "x.y", false))
        assertEquals(NotificationKind.SYSTEM, NotificationRules.categorize("progress", "x.y", false))
    }

    @Test
    fun packageAndMessagingStyleAreTheFallback() {
        assertEquals(NotificationKind.MESSAGE, NotificationRules.categorize(null, "com.whatsapp", false))
        assertEquals(NotificationKind.MESSAGE, NotificationRules.categorize(null, "some.new.chat", true))
        assertEquals(NotificationKind.EMAIL, NotificationRules.categorize(null, "com.google.android.gm", false))
        assertEquals(NotificationKind.CALL, NotificationRules.categorize(null, "com.google.android.dialer", false))
        assertEquals(NotificationKind.OTHER, NotificationRules.categorize(null, "com.random.game", false))
        assertEquals(NotificationKind.SYSTEM, NotificationRules.categorize(null, "android", false))
    }

    @Test
    fun labelsAreKnownOrDerivedFromThePackage() {
        assertEquals("WhatsApp", NotificationRules.labelFromPackage("com.whatsapp"))
        assertEquals("Gmail", NotificationRules.labelFromPackage("com.google.android.gm"))
        assertEquals("Foo", NotificationRules.labelFromPackage("com.example.foo"))
        assertEquals("Calendar", NotificationRules.labelFromPackage("com.vendor.calendar.app"))
    }

    @Test
    fun whatsAppDetection() {
        assertTrue(NotificationRules.isWhatsApp("com.whatsapp"))
        assertTrue(NotificationRules.isWhatsApp("com.whatsapp.w4b"))
        assertFalse(NotificationRules.isWhatsApp("com.whatsapp.fake"))
        assertFalse(NotificationRules.isWhatsApp("org.telegram.messenger"))
    }

    @Test
    fun nothingIsReadUnlessTheSwitchIsOn() {
        assertNull(NotificationRules.announcement(info(), ctx(aloud = false)))
    }

    @Test
    fun unlockedMessageIsReadWithSenderAndText() {
        assertEquals("New message from Asha: Are you free tonight?", NotificationRules.announcement(info(), ctx()))
        assertEquals("group chat title wins over the sender", "New message from Family: Are you free tonight?", NotificationRules.announcement(info(conv = "Family"), ctx()))
    }

    @Test
    fun longTextIsTrimmedAndWhitespaceCollapsed() {
        val s = NotificationRules.announcement(info(text = "a\n\n  b " + "x".repeat(300)), ctx())!!
        assertTrue(s.length < 190)
        assertFalse(s.contains("\n"))
    }

    @Test
    fun onlyMessagesAndCallsOngoingAndOwnAreSkipped() {
        assertNull(NotificationRules.announcement(info(kind = NotificationKind.PROMO), ctx()))
        assertNull(NotificationRules.announcement(info(kind = NotificationKind.EMAIL), ctx()))
        assertNull(NotificationRules.announcement(info(ongoing = true), ctx()))
        assertNull(NotificationRules.announcement(info(pkg = "com.jarvis.assistant"), ctx()))
    }

    @Test
    fun rateLimited() {
        assertNull(NotificationRules.announcement(info(), ctx(now = 100_000, last = 98_000)))
        assertTrue(NotificationRules.announcement(info(), ctx(now = 100_000, last = 95_000)) != null)
    }

    @Test
    fun lockedScreenReadsNothingByDefault() {
        assertNull(NotificationRules.announcement(info(), ctx(locked = true, lockRead = false)))
        assertNull(NotificationRules.announcement(info(), ctx(locked = true, lockRead = false, privacy = LockPrivacy.OPEN)))
    }

    @Test
    fun lockedScreenWithPermissionReadsOnlyTheSenderUnlessOpen() {
        assertEquals("New message from Asha.", NotificationRules.announcement(info(), ctx(locked = true, lockRead = true, privacy = LockPrivacy.BALANCED)))
        assertEquals("New message from Asha.", NotificationRules.announcement(info(), ctx(locked = true, lockRead = true, privacy = LockPrivacy.STRICT)))
        assertEquals("New message from Asha: Are you free tonight?", NotificationRules.announcement(info(), ctx(locked = true, lockRead = true, privacy = LockPrivacy.OPEN)))
    }

    @Test
    fun callsAreAnnouncedWithoutTheNameWhenLocked() {
        val call = info(kind = NotificationKind.CALL, title = "Incoming call", text = "Mom", pkg = "com.google.android.dialer")
        assertEquals("Incoming call from Mom.", NotificationRules.announcement(call, ctx()))
        assertEquals("Incoming call.", NotificationRules.announcement(call, ctx(locked = true, lockRead = true)))
    }

    @Test
    fun lockReadNeedsModePrivacyAndSwitch() {
        assertTrue(NotificationRules.lockReadAllowed(true, LockPrivacy.BALANCED, true))
        assertTrue(NotificationRules.lockReadAllowed(true, LockPrivacy.OPEN, true))
        assertFalse(NotificationRules.lockReadAllowed(true, LockPrivacy.STRICT, true))
        assertFalse(NotificationRules.lockReadAllowed(false, LockPrivacy.OPEN, true))
        assertFalse(NotificationRules.lockReadAllowed(true, LockPrivacy.OPEN, false))
    }
}
