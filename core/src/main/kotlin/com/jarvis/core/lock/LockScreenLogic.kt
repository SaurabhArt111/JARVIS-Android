package com.jarvis.core.lock

import com.jarvis.core.model.ActionType
import com.jarvis.core.model.AssistantReply
import com.jarvis.core.model.Requirement
import com.jarvis.core.model.UiAction

/** What a button on the lock-screen assistant does. Pure mapping so it can be tested without a device. */
sealed class LockButton {
    /** Send [answer] to the assistant as the user's next message (yes / no / a numbered choice). */
    data class Answer(val label: String, val answer: String, val primary: Boolean) : LockButton()
    /** Ask Android to show the keyguard; the request that needed it is retried only after it succeeds. */
    data class Unlock(val label: String) : LockButton()
    /** Needs the phone unlocked and the full app; offered as "unlock, then open JARVIS". */
    data class OpenFullApp(val label: String) : LockButton()
}

/**
 * Decisions of the lock-screen assistant that do not need Android: which buttons to show, whether typing is allowed, when to retry after an unlock and
 * when to close. Nothing here unlocks anything: unlocking is always the system keyguard's job.
 */
object LockScreenLogic {

    fun buttonsFor(reply: AssistantReply): List<LockButton> {
        val out = ArrayList<LockButton>()
        for (a in reply.actions) {
            when (a.type) {
                ActionType.CONFIRM -> out += answer(a, primary = true)
                ActionType.CANCEL -> out += answer(a, primary = false)
                ActionType.CHOOSE -> out += answer(a, primary = false)
                ActionType.UNLOCK -> out += LockButton.Unlock(a.label.ifBlank { "Unlock" })
                else -> out += LockButton.OpenFullApp("Unlock and open JARVIS")
            }
        }
        // one "open" button is enough however many app-only actions came back
        val firstOpen = out.indexOfFirst { it is LockButton.OpenFullApp }
        return out.filterIndexed { i, b -> b !is LockButton.OpenFullApp || i == firstOpen }.filter { it !is LockButton.Answer || it.answer.isNotBlank() }
    }

    private fun answer(a: UiAction, primary: Boolean) = LockButton.Answer(a.label, a.payload["answer"].orEmpty(), primary)

    /** True when the reply says the request could not run until the phone is unlocked. */
    fun needsUnlock(reply: AssistantReply): Boolean = Requirement.UNLOCK in reply.missing || reply.actions.any { it.type == ActionType.UNLOCK }

    /**
     * Typing on the lock screen goes through the keyboard app, which may learn from or show suggestions about what is typed. It is therefore offered only when
     * the user switched lock-screen mode on, or when there is nothing to protect (no secure lock, or already unlocked).
     */
    fun typingAllowed(lock: LockState, modeOn: Boolean): Boolean = !lock.protectPersonalData || modeOn

    /** The microphone is always offered: speaking a request is what the lock-screen assistant is for, and the policy decides what runs. */
    fun micAllowed(): Boolean = true

    /** Should the activity close itself? Never while something is running or the mic is open. */
    fun shouldClose(nowMs: Long, lastInteractionMs: Long, busy: Boolean, timeoutMs: Long = IDLE_TIMEOUT_MS): Boolean = !busy && nowMs - lastInteractionMs >= timeoutMs

    const val IDLE_TIMEOUT_MS = 45_000L
}

/**
 * Remembers the one request that was refused because the phone was locked, so it can be run again once the user has authenticated, and only then.
 * Authentication cancelled or failed -> the request is dropped (it is never run on a locked phone as a fallback). A new request replaces an old one.
 */
class UnlockRetry {
    private var pending: String? = null

    /** Call after each reply. Remembers [requestText] when the reply is the "unlock first" kind, otherwise forgets any earlier one. */
    fun onReply(requestText: String, reply: AssistantReply) {
        pending = if (LockScreenLogic.needsUnlock(reply)) requestText else null
    }

    /** Whether there is something waiting for the unlock. */
    fun hasPending(): Boolean = pending != null

    /** Call with the outcome of the keyguard prompt; returns the text to submit again, or null. Consumes the pending request either way. */
    fun onUnlockResult(succeeded: Boolean): String? {
        val p = pending
        pending = null
        return if (succeeded) p else null
    }

    fun clear() { pending = null }
}
