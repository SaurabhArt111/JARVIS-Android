package com.jarvis.core.routing

import com.jarvis.core.lock.LockState
import com.jarvis.core.model.Requirement
import com.jarvis.core.ports.PermState
import com.jarvis.core.testing.TestEnv
import com.jarvis.core.tools.Capability
import com.jarvis.core.tools.impl.ToolRegistry
import org.junit.Assert.*
import org.junit.Test

class CapabilityRouterTest {
    private val env = TestEnv()
    private val registry = ToolRegistry.default(env.services, settleMs = 0)
    private val router = CapabilityRouter(env.services, registry)

    @Test fun classifiesSingleCapabilities() {
        assertEquals(Capability.REMINDERS, router.classify("remind me to call mom").first())
        assertTrue(Capability.MEDIA in router.classify("play some music"))
        assertTrue(Capability.DEVICE_CONTROL in router.classify("set the volume to 30"))
        assertTrue(Capability.COMMUNICATION in router.classify("call mom"))
        assertTrue(Capability.INFORMATION in router.classify("what's the weather today"))
        assertTrue(Capability.IMAGE_GENERATION in router.classify("generate an image of a dragon"))
        assertTrue(Capability.SYSTEM_NAVIGATION in router.classify("take a screenshot"))
    }

    @Test fun compoundRequestsTouchSeveralCapabilities() {
        val c = router.classify("open spotify, set the volume to 30 and remind me at 5")
        assertTrue(Capability.APP_LAUNCH in c && Capability.DEVICE_CONTROL in c && Capability.REMINDERS in c)
    }

    @Test fun smallTalkIsConversation() {
        assertEquals(listOf(Capability.CONVERSATION), router.classify("why is the sky blue"))
        assertTrue(router.classify("   ").isEmpty())
    }

    @Test fun missingAccessibilityBlocksNavigation() {
        env.permissions.granted.remove(Requirement.ACCESSIBILITY)
        assertEquals(listOf(Requirement.ACCESSIBILITY), router.blockers("go home"))
        env.permissions.granted.add(Requirement.ACCESSIBILITY)
        assertTrue(router.blockers("go home").isEmpty())
    }

    @Test fun notificationReadingNeedsTheListener() {
        env.permissions.granted.remove(Requirement.NOTIFICATION_LISTENER)
        assertTrue(Requirement.NOTIFICATION_LISTENER in router.blockers("read my notifications"))
    }

    @Test fun chatNeedsAnAiProvider() {
        env.permissions.granted.remove(Requirement.API_KEY)
        assertEquals(listOf(Requirement.API_KEY), router.blockers("tell me a story"))
        assertTrue("simple commands don't", router.blockers("set a timer for 5 minutes").isEmpty())
    }

    @Test fun statusListsEveryCapabilityOnce() {
        val st = router.status(LockState.UNLOCKED)
        assertEquals(Capability.entries.toSet(), st.map { it.capability }.toSet()); assertEquals(Capability.entries.size, st.size)
    }

    @Test fun statusReportsMissingPermissionsAndKeepsOfflineFlag() {
        env.permissions.granted.remove(Requirement.NOTIFICATIONS)
        val reminders = router.status(LockState.UNLOCKED).first { it.capability == Capability.REMINDERS }
        assertTrue(reminders.available); assertTrue(reminders.offlineCapable)
        assertTrue(Requirement.NOTIFICATIONS in reminders.missing)
        assertTrue(reminders.note.contains("notification"))
    }

    @Test fun offlineHidesToolsThatNeedTheNetwork() {
        val online = router.toolsFor(LockState.UNLOCKED, online = true).map { it.name }
        val offline = router.toolsFor(LockState.UNLOCKED, online = false).map { it.name }
        assertTrue("web_search" in online && "weather" in online)
        assertFalse("web_search" in offline); assertFalse("weather" in offline); assertFalse("image" in offline)
        assertTrue("timer" in offline && "open" in offline && "calculate" in offline)
    }

    @Test fun promptBlockTellsTheModelWhatIsNotPossible() {
        env.permissions.granted.remove(Requirement.ACCESSIBILITY)
        val p = router.promptBlock(LockState.LOCKED_SECURE, online = false)
        assertTrue(p.contains("OFFLINE")); assertTrue(p.contains("LOCKED")); assertTrue(p.contains("system navigation")); assertTrue(p.contains("accessibility"))
    }

    @Test fun promptBlockIsEmptyWhenEverythingWorks() {
        assertEquals("", router.promptBlock(LockState.UNLOCKED, online = true))
    }

    @Suppress("unused") private val ref = PermState.GRANTED
}
