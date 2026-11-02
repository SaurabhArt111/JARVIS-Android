package com.jarvis.core.lock

import com.jarvis.core.model.ActionType
import com.jarvis.core.model.AssistantReply
import com.jarvis.core.model.Origin
import com.jarvis.core.model.Requirement
import com.jarvis.core.settings.Keys
import com.jarvis.core.testing.TestEnv
import com.jarvis.core.tools.Args
import com.jarvis.core.tools.impl.ToolRegistry
import org.junit.Assert.*
import org.junit.Test

class LockPolicyTest {
    private val env = TestEnv()
    private val policy = LockPolicy(env.settings, env.clock)
    private val registry = ToolRegistry.default(env.services, settleMs = 0)
    private val secureLocked = LockState.LOCKED_SECURE
    private val swipeOnly = LockState(deviceLocked = true, secure = false)

    private fun decide(tool: String, args: Map<String, Any?> = emptyMap(), lock: LockState = secureLocked): LockVerdict =
        policy.decide(registry.get(tool)!!.spec, Args(args), lock, Origin.LOCK_SCREEN).verdict

    private fun set(vararg kv: Pair<String, String>) = kv.forEach { env.settings.set(it.first, it.second) }

    @Test fun unlockedPhoneAllowsEverything() {
        for (t in registry.names()) assertEquals("$t", LockVerdict.ALLOW, decide(t, lock = LockState.UNLOCKED))
    }

    @Test fun openToolsWorkOnTheLockScreen() {
        assertEquals(LockVerdict.ALLOW, decide("timer", mapOf("action" to "start")))
        assertEquals(LockVerdict.ALLOW, decide("calculate"))
        assertEquals(LockVerdict.ALLOW, decide("system", mapOf("action" to "volume_set")))
        assertEquals(LockVerdict.ALLOW, decide("system", mapOf("action" to "flashlight_on")))
        assertEquals(LockVerdict.ALLOW, decide("weather"))
    }

    @Test fun launchingAppsAlwaysNeedsUnlock() {
        assertEquals(LockVerdict.REQUIRE_UNLOCK, decide("open"))
        assertEquals(LockVerdict.REQUIRE_UNLOCK, decide("system", mapOf("action" to "open_settings")))
        assertEquals(LockVerdict.REQUIRE_UNLOCK, decide("navigate", mapOf("action" to "home")))
        assertEquals(LockVerdict.REQUIRE_UNLOCK, decide("play"))
    }

    @Test fun clipboardIsPersonal() {
        assertEquals(LockVerdict.REQUIRE_UNLOCK, decide("system", mapOf("action" to "clipboard")))
    }

    @Test fun navigateLockIsAllowedBecauseItOnlyLocks() {
        assertEquals(LockVerdict.ALLOW, decide("navigate", mapOf("action" to "lock_screen")))
    }

    @Test fun readingPersonalDataNeedsUnlockByDefault() {
        assertEquals(LockVerdict.REQUIRE_UNLOCK, decide("reminder", mapOf("action" to "list")))
        assertEquals(LockVerdict.REQUIRE_UNLOCK, decide("note", mapOf("action" to "list")))
        assertEquals(LockVerdict.REQUIRE_UNLOCK, decide("read_notifications"))
    }

    @Test fun dictationIsAllowedOnlyWhenLockModeIsOn() {
        assertEquals(LockVerdict.REQUIRE_UNLOCK, decide("note", mapOf("action" to "add")))
        set(Keys.LOCK_MODE_ENABLED to "On")
        assertEquals(LockVerdict.ALLOW, decide("note", mapOf("action" to "add")))
        assertEquals(LockVerdict.ALLOW, decide("reminder", mapOf("action" to "add")))
        set(Keys.LOCK_ALLOW_DICTATION to "Off")
        assertEquals(LockVerdict.REQUIRE_UNLOCK, decide("note", mapOf("action" to "add")))
    }

    @Test fun notificationsOnTheLockScreenNeedBalancedPlusItsOwnToggle() {
        set(Keys.LOCK_MODE_ENABLED to "On", Keys.LOCK_READ_NOTIFICATIONS to "On")
        assertEquals("strict never reads", LockVerdict.REQUIRE_UNLOCK, decide("read_notifications"))
        set(Keys.LOCK_PRIVACY to "balanced")
        assertEquals(LockVerdict.ALLOW, decide("read_notifications"))
        set(Keys.LOCK_READ_NOTIFICATIONS to "Off")
        assertEquals(LockVerdict.REQUIRE_UNLOCK, decide("read_notifications"))
    }

    @Test fun openPrivacyLetsPersonalReadsThroughOnlyWithLockMode() {
        set(Keys.LOCK_PRIVACY to "open")
        assertEquals("lock mode still off", LockVerdict.REQUIRE_UNLOCK, decide("reminder", mapOf("action" to "list")))
        set(Keys.LOCK_MODE_ENABLED to "On")
        assertEquals(LockVerdict.ALLOW, decide("reminder", mapOf("action" to "list")))
    }

    @Test fun answeringOrEndingACallNeedsNoContactLookupSwitch() {
        val answer = mapOf("action" to "answer"); val hangUp = mapOf("action" to "hang_up")
        assertEquals("default: unlock", LockVerdict.REQUIRE_UNLOCK, decide("call", answer))
        set(Keys.LOCK_MODE_ENABLED to "On", Keys.LOCK_ALLOW_CALLS to "On", Keys.LOCK_PRIVACY to "balanced")
        assertEquals("calls allowed, no lookup switch needed: confirm", LockVerdict.CONFIRM, decide("call", answer))
        assertEquals(LockVerdict.CONFIRM, decide("call", hangUp))
        set(Keys.LOCK_ALLOW_CALLS to "Off")
        assertEquals("calls switched off: unlock", LockVerdict.REQUIRE_UNLOCK, decide("call", answer))
    }

    @Test fun callsFromTheLockScreenMatrix() {
        val byName = mapOf("contact" to "mom"); val byNumber = mapOf("contact" to "+91 98765 43210")
        assertEquals("default: unlock", LockVerdict.REQUIRE_UNLOCK, decide("call", byNumber))
        set(Keys.LOCK_MODE_ENABLED to "On", Keys.LOCK_ALLOW_CALLS to "On")
        assertEquals("strict still unlock", LockVerdict.REQUIRE_UNLOCK, decide("call", byNumber))
        set(Keys.LOCK_PRIVACY to "balanced")
        assertEquals("number: confirm", LockVerdict.CONFIRM, decide("call", byNumber))
        assertEquals("name without contact lookup: unlock", LockVerdict.REQUIRE_UNLOCK, decide("call", byName))
        set(Keys.LOCK_ALLOW_CONTACT_LOOKUP to "On")
        assertEquals("name with lookup: confirm", LockVerdict.CONFIRM, decide("call", byName))
        assertEquals("never silent", LockVerdict.REQUIRE_UNLOCK, decide("call", mapOf("contact" to "x", "service" to "google meet")))
    }

    @Test fun messagesNeedUnlockOnASecureLock() {
        assertEquals(LockVerdict.REQUIRE_UNLOCK, decide("message", mapOf("to" to "mom", "text" to "hi")))
        set(Keys.LOCK_MODE_ENABLED to "On", Keys.LOCK_PRIVACY to "open")
        assertEquals(LockVerdict.REQUIRE_UNLOCK, decide("message", mapOf("to" to "mom", "text" to "hi")))
    }

    @Test fun swipeOnlyLockStillProtectsAppLaunchesButNotPersonalReads() {
        assertEquals(LockVerdict.ALLOW, decide("reminder", mapOf("action" to "list"), swipeOnly))
        assertEquals(LockVerdict.REQUIRE_UNLOCK, decide("open", lock = swipeOnly))
        assertEquals(LockVerdict.CONFIRM, decide("call", mapOf("contact" to "123"), swipeOnly))
    }

    @Test fun aRecentSystemAuthenticationCountsAsUnlocked() {
        val auth = LockState(deviceLocked = true, secure = true, authenticatedAtMs = env.clock.nowMs() - 30_000)
        assertEquals(LockVerdict.ALLOW, decide("open", lock = auth))
        val old = LockState(deviceLocked = true, secure = true, authenticatedAtMs = env.clock.nowMs() - LockPolicy.AUTH_WINDOW_MS - 1)
        assertEquals(LockVerdict.REQUIRE_UNLOCK, decide("open", lock = old))
    }

    @Test fun anAuthenticationFromTheFutureIsIgnored() {
        val bogus = LockState(true, true, authenticatedAtMs = env.clock.nowMs() + 60_000)
        assertEquals(LockVerdict.REQUIRE_UNLOCK, decide("open", lock = bogus))
    }

    @Test fun lockPrivacyParsingFallsBackToStrict() {
        assertEquals(LockPrivacy.STRICT, LockPrivacy.parse(null)); assertEquals(LockPrivacy.STRICT, LockPrivacy.parse("nonsense"))
        assertEquals(LockPrivacy.OPEN, LockPrivacy.parse(" Open "))
    }

    @Test fun sensitiveRepliesAreRedactedOnALockedSecureDevice() {
        val r = AssistantReply("You have a reminder: pay rent", sensitive = true)
        val red = policy.redact(r, secureLocked)
        assertFalse(red.text.contains("rent")); assertNull(red.card)
        assertEquals(ActionType.UNLOCK, red.actions.single().type)
        assertEquals(listOf(Requirement.UNLOCK), red.missing)
        assertFalse(policy.maySpeakDetails(r, secureLocked))
    }

    @Test fun nonSensitiveAndUnlockedRepliesPassThrough() {
        val r = AssistantReply("It's 10 AM", sensitive = false)
        assertSame(r, policy.redact(r, secureLocked))
        val p = AssistantReply("pay rent", sensitive = true)
        assertSame(p, policy.redact(p, LockState.UNLOCKED))
        assertSame(p, policy.redact(p, swipeOnly))
    }

    @Test fun failedSensitiveRepliesKeepTheirErrorButLoseTheCard() {
        val r = AssistantReply("I couldn't find that reminder.", ok = false, sensitive = true)
        assertEquals("I couldn't find that reminder.", policy.redact(r, secureLocked).text)
    }
}
