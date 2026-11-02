package com.jarvis.core.lock

import com.jarvis.core.model.ActionType
import com.jarvis.core.model.AssistantReply
import com.jarvis.core.model.Requirement
import com.jarvis.core.model.UiAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LockScreenLogicTest {
    private val unlockReply = AssistantReply.error("Please unlock your phone first.", "lock", listOf(Requirement.UNLOCK), listOf(UiAction("Unlock", ActionType.UNLOCK)))
    private val plain = AssistantReply("It is 10:15.")

    @Test
    fun yesNoBecomeAnswerButtonsAndYesIsPrimary() {
        val r = AssistantReply("Call Mom?", actions = listOf(UiAction("Yes", ActionType.CONFIRM, mapOf("answer" to "yes")), UiAction("No", ActionType.CANCEL, mapOf("answer" to "no"))))
        val b = LockScreenLogic.buttonsFor(r)
        assertEquals(listOf(LockButton.Answer("Yes", "yes", true), LockButton.Answer("No", "no", false)), b)
    }

    @Test
    fun numberedChoicesBecomeAnswers() {
        val r = AssistantReply("Which Mom?", actions = listOf(UiAction("Mom (mobile)", ActionType.CHOOSE, mapOf("answer" to "1")), UiAction("Mom (home)", ActionType.CHOOSE, mapOf("answer" to "2"))))
        assertEquals(listOf("1", "2"), LockScreenLogic.buttonsFor(r).map { (it as LockButton.Answer).answer })
    }

    @Test
    fun unlockActionBecomesTheKeyguardButton() {
        assertEquals(listOf(LockButton.Unlock("Unlock")), LockScreenLogic.buttonsFor(unlockReply))
    }

    @Test
    fun appOnlyActionsCollapseToOneOpenButton() {
        val r = AssistantReply("x", actions = listOf(UiAction("Open settings", ActionType.OPEN_SETTINGS_PAGE), UiAction("Open file", ActionType.OPEN_FILE), UiAction("Grant", ActionType.GRANT)))
        val b = LockScreenLogic.buttonsFor(r)
        assertEquals(1, b.size)
        assertTrue(b[0] is LockButton.OpenFullApp)
    }

    @Test
    fun answerButtonWithoutAnswerTextIsDropped() {
        val r = AssistantReply("x", actions = listOf(UiAction("Yes", ActionType.CONFIRM)))
        assertTrue(LockScreenLogic.buttonsFor(r).isEmpty())
    }

    @Test
    fun needsUnlockDetectsEitherSignal() {
        assertTrue(LockScreenLogic.needsUnlock(unlockReply))
        assertTrue(LockScreenLogic.needsUnlock(AssistantReply("x", missing = listOf(Requirement.UNLOCK))))
        assertTrue(LockScreenLogic.needsUnlock(AssistantReply("x", actions = listOf(UiAction("Unlock", ActionType.UNLOCK)))))
        assertFalse(LockScreenLogic.needsUnlock(plain))
        assertFalse(LockScreenLogic.needsUnlock(AssistantReply("x", missing = listOf(Requirement.MICROPHONE))))
    }

    @Test
    fun typingOnlyWhenModeOnOrNothingToProtect() {
        val secureLocked = LockState(deviceLocked = true, secure = true)
        assertFalse(LockScreenLogic.typingAllowed(secureLocked, modeOn = false))
        assertTrue(LockScreenLogic.typingAllowed(secureLocked, modeOn = true))
        assertTrue(LockScreenLogic.typingAllowed(LockState(deviceLocked = true, secure = false), modeOn = false))
        assertTrue(LockScreenLogic.typingAllowed(LockState.UNLOCKED, modeOn = false))
    }

    @Test
    fun closesOnlyWhenIdleAndNotBusy() {
        assertFalse(LockScreenLogic.shouldClose(nowMs = 44_999, lastInteractionMs = 0, busy = false))
        assertTrue(LockScreenLogic.shouldClose(nowMs = 45_000, lastInteractionMs = 0, busy = false))
        assertFalse(LockScreenLogic.shouldClose(nowMs = 600_000, lastInteractionMs = 0, busy = true))
    }

    @Test
    fun retryRunsTheRefusedRequestOnlyAfterASuccessfulUnlock() {
        val r = UnlockRetry()
        r.onReply("read my notes", unlockReply)
        assertTrue(r.hasPending())
        assertEquals("read my notes", r.onUnlockResult(true))
        assertFalse(r.hasPending())
        assertNull("only once", r.onUnlockResult(true))
    }

    @Test
    fun cancelledOrFailedUnlockDropsTheRequest() {
        val r = UnlockRetry()
        r.onReply("read my notes", unlockReply)
        assertNull(r.onUnlockResult(false))
        assertNull("not kept for a later success", r.onUnlockResult(true))
    }

    @Test
    fun aLaterNormalReplyForgetsTheOldRefusal() {
        val r = UnlockRetry()
        r.onReply("read my notes", unlockReply)
        r.onReply("what time is it", plain)
        assertFalse(r.hasPending())
        assertNull(r.onUnlockResult(true))
    }

    @Test
    fun aNewRefusalReplacesTheOldOne() {
        val r = UnlockRetry()
        r.onReply("a", unlockReply)
        r.onReply("b", unlockReply)
        assertEquals("b", r.onUnlockResult(true))
    }
}
