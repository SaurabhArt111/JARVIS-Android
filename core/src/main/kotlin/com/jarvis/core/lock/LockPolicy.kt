package com.jarvis.core.lock

import com.jarvis.core.model.ActionType
import com.jarvis.core.model.AssistantReply
import com.jarvis.core.model.Requirement
import com.jarvis.core.model.UiAction
import com.jarvis.core.settings.Keys
import com.jarvis.core.settings.Settings
import com.jarvis.core.tools.Args
import com.jarvis.core.tools.LockClass
import com.jarvis.core.tools.ToolSpec
import com.jarvis.core.util.JarvisClock
import com.jarvis.core.util.SystemClock

/**
 * What the assistant may do while the device is locked. This is a POLICY, not a bypass: JARVIS never dismisses, skips or weakens the keyguard.
 * Anything that needs the user to be present goes through [LockVerdict.REQUIRE_UNLOCK], which the UI turns into the system's own
 * authentication screen (`KeyguardManager.requestDismissKeyguard`).
 *
 * Privacy levels (setting `LockPrivacy`, default STRICT) on a locked device WITH a secure lock (PIN / pattern / password / biometric):
 *
 *  | tool class                        | STRICT                  | BALANCED                                   | OPEN (user accepted the risk)           |
 *  |-----------------------------------|-------------------------|--------------------------------------------|-----------------------------------------|
 *  | OPEN (time, timers, volume, ...)  | allow                   | allow                                      | allow                                   |
 *  | PERSONAL write (dictate note/..)  | allow if LockAllowDictation + LockModeEnabled, reply redacted | same | same                          |
 *  | PERSONAL read (notes, reminders)  | unlock                  | unlock                                     | allow (reply is still redacted for speech unless OPEN) |
 *  | notifications read                | unlock                  | allow if LockReadNotifications is On       | allow if LockReadNotifications is On    |
 *  | CONSEQUENTIAL call                | unlock                  | confirm, needs LockAllowCalls (+ contact lookup toggle for names) | same           |
 *  | message / quick reply             | unlock                  | unlock                                     | confirm (message still needs unlock: it opens another app) |
 *  | ADMIN (open apps, settings, ...)  | unlock                  | unlock                                     | unlock                                  |
 *
 * On a locked device WITHOUT a secure lock (swipe only) personal data is not protected, so reads are allowed; anything that opens another app
 * still needs the swipe-to-dismiss. A system authentication within the last [AUTH_WINDOW_MS] counts as unlocked.
 */
class LockPolicy(private val settings: Settings, private val clock: JarvisClock = SystemClock) {

    fun privacy(): LockPrivacy = LockPrivacy.parse(settings.get(Keys.LOCK_PRIVACY))

    fun authenticatedRecently(lock: LockState): Boolean =
        lock.authenticatedAtMs > 0 && clock.nowMs() - lock.authenticatedAtMs in 0..AUTH_WINDOW_MS

    /** True when personal content must be hidden right now. */
    fun effectivelyLocked(lock: LockState): Boolean = lock.protectPersonalData && !authenticatedRecently(lock)

    /** Some tools are several things in one (`system` = volume AND clipboard AND settings). The class is decided per action. */
    fun classFor(spec: ToolSpec, args: Args): LockClass {
        val action = args.str("action").lowercase().replace(' ', '_')
        return when (spec.name) {
            "system" -> when {
                action == "clipboard" || action == "clipboard_get" -> LockClass.PERSONAL
                action in setOf("open_settings", "screenshot", "lock", "lock_screen", "power_menu", "power") -> LockClass.ADMIN
                else -> LockClass.OPEN
            }
            "navigate" -> if (action in setOf("lock", "lock_screen", "lock_phone")) LockClass.OPEN else LockClass.ADMIN
            else -> spec.lock
        }
    }

    fun isWrite(spec: ToolSpec, args: Args): Boolean {
        val action = args.str("action").lowercase()
        return when (spec.name) {
            "reminder", "note" -> action in setOf("add", "append")
            "save_document", "image" -> true
            else -> false
        }
    }

    fun decide(spec: ToolSpec, args: Args, lock: LockState, origin: com.jarvis.core.model.Origin): LockDecision {
        if (!lock.deviceLocked || authenticatedRecently(lock)) return LockDecision(LockVerdict.ALLOW)
        val cls = classFor(spec, args)
        val secure = lock.secure
        val p = privacy()
        val modeOn = settings.flag(Keys.LOCK_MODE_ENABLED, false)
        val unlock = { why: String -> LockDecision(LockVerdict.REQUIRE_UNLOCK, why) }
        return when (cls) {
            LockClass.OPEN -> LockDecision(LockVerdict.ALLOW)
            LockClass.ADMIN -> unlock("it opens other apps or changes settings, which Android only shows after you unlock")
            LockClass.PERSONAL -> when {
                !secure -> LockDecision(LockVerdict.ALLOW, "no secure lock is set, so there is nothing to protect")
                spec.name == "read_notifications" ->
                    if (modeOn && p != LockPrivacy.STRICT && settings.flag(Keys.LOCK_READ_NOTIFICATIONS, false)) LockDecision(LockVerdict.ALLOW, "allowed by your lock-screen settings")
                    else unlock("notifications can contain private messages")
                isWrite(spec, args) ->
                    if (modeOn && settings.flag(Keys.LOCK_ALLOW_DICTATION, true)) LockDecision(LockVerdict.ALLOW, "dictation is allowed on the lock screen")
                    else unlock("saving things from the lock screen is switched off")
                p == LockPrivacy.OPEN && modeOn -> LockDecision(LockVerdict.ALLOW, "allowed by your lock-screen privacy level")
                else -> unlock("it shows personal information")
            }
            LockClass.CONSEQUENTIAL -> when (spec.name) {
                "call" -> {
                    val who = args.str("contact", "to", "person", "number", "name")
                    // answering or ending a call names nobody, so there is no contact lookup to switch on
                    val controlsCall = com.jarvis.core.tools.impl.callControl(args) != null
                    val isNumber = controlsCall || (who.isNotEmpty() && who.count { it.isDigit() } >= 3 && who.all { it.isDigit() || it in "+ ()-." })
                    when {
                        args.str("service").lowercase().contains("meet") -> unlock("it opens another app")
                        !secure -> LockDecision(LockVerdict.CONFIRM, "calls from the lock screen need a confirmation")
                        !modeOn || p == LockPrivacy.STRICT || !settings.flag(Keys.LOCK_ALLOW_CALLS, false) -> unlock("calls from the lock screen are switched off")
                        !isNumber && !settings.flag(Keys.LOCK_ALLOW_CONTACT_LOOKUP, false) -> unlock("looking up contacts from the lock screen is switched off")
                        else -> LockDecision(LockVerdict.CONFIRM, "calls from the lock screen need a confirmation")
                    }
                }
                "reply_notification" ->
                    if (secure && !(modeOn && p == LockPrivacy.OPEN)) unlock("replying sends a message in your name") else LockDecision(LockVerdict.ALLOW, "the tool asks for its own confirmation")
                else -> unlock("it sends something in your name")
            }
        }
    }

    /** Spoken / shown details are only allowed when the content is not marked sensitive, or when nothing is protected. */
    fun maySpeakDetails(reply: AssistantReply, lock: LockState): Boolean = !(reply.sensitive && effectivelyLocked(lock))

    /** The reply as it may appear on a locked secure device: personal text is replaced by a neutral line and an Unlock button. */
    fun redact(reply: AssistantReply, lock: LockState): AssistantReply {
        if (maySpeakDetails(reply, lock)) return reply
        if (!reply.ok) return reply.copy(card = null, sensitive = false, extras = reply.extras - "display_text")
        return AssistantReply(
            "Done. Unlock your phone to see the details.", ok = true, kind = reply.kind, icon = reply.icon, card = null, speak = reply.speak,
            actions = listOf(UiAction("Unlock", ActionType.UNLOCK)), missing = listOf(Requirement.UNLOCK), sensitive = false,
        )
    }

    companion object {
        const val AUTH_WINDOW_MS = 120_000L
    }
}
