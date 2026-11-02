package com.jarvis.assistant.platform

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.telecom.TelecomManager
import com.jarvis.core.contacts.PhoneNumbers
import com.jarvis.core.model.Requirement
import com.jarvis.core.ports.ActionResult
import com.jarvis.core.ports.CallGateway
import com.jarvis.core.ports.PermissionChecker
import com.jarvis.core.util.JarvisLog

/**
 * Calls. With the CALL_PHONE permission the call is placed through Telecom; without it the dialer opens with the number filled in and the USER presses call - and the
 * result message says which one happened. Codes starting with * or # (USSD / MMI, which can change phone settings or run carrier services) are never dialed.
 * Answering and ending calls need ANSWER_PHONE_CALLS (asked together with CALL_PHONE).
 */
class AndroidCallGateway(
    context: Context,
    private val permissions: PermissionChecker,
    private val starter: ActivityStarter,
    private val log: JarvisLog = JarvisLog.None,
) : CallGateway {
    private val ctx = context.applicationContext
    private val telecom: TelecomManager? = ctx.getSystemService(TelecomManager::class.java)

    private fun hasTelephony() = ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
    private fun granted(p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    override fun call(number: String, displayName: String): ActionResult {
        val dial = PhoneNumbers.dialable(number) ?: return ActionResult.fail("I won't dial “${number.take(20)}” — it isn't a normal phone number.")
        if (!hasTelephony() || telecom == null) {
            val r = starter.start(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", dial, null)), "the dialer", "Opened the dialer with $displayName's number. Tap call to ring them.")
            return r
        }
        if (permissions.isGranted(Requirement.CALL_PHONE)) {
            try {
                telecom.placeCall(Uri.fromParts("tel", dial, null), Bundle())
                log.i("Calls", "placeCall requested")
                return ActionResult.ok("Calling $displayName.", verified = false)
            } catch (e: SecurityException) {
                log.w("Calls", "placeCall refused — falling back to the dialer")
            } catch (e: Exception) {
                log.w("Calls", "placeCall failed (${e.javaClass.simpleName}) — falling back to the dialer")
            }
        }
        return starter.start(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", dial, null)), "the dialer",
            "Opened the dialer with $displayName's number. Tap call to ring them — JARVIS doesn't have phone permission to do it for you.")
    }

    override fun answerRingingCall(): ActionResult {
        if (!granted(Manifest.permission.ANSWER_PHONE_CALLS)) return ActionResult.fail("I need phone permission to answer calls.", Requirement.CALL_PHONE)
        val t = telecom ?: return ActionResult.fail("This device has no phone.")
        return try {
            @Suppress("DEPRECATION") t.acceptRingingCall()
            ActionResult.ok("Asked the phone to answer the call.", verified = false)
        } catch (e: SecurityException) { ActionResult.fail("Android refused to let JARVIS answer the call.", Requirement.CALL_PHONE) }
        catch (e: Exception) { ActionResult.fail("I couldn't answer the call (${e.javaClass.simpleName}).") }
    }

    override fun endCall(): ActionResult {
        if (!granted(Manifest.permission.ANSWER_PHONE_CALLS)) return ActionResult.fail("I need phone permission to end calls.", Requirement.CALL_PHONE)
        val t = telecom ?: return ActionResult.fail("This device has no phone.")
        return try {
            if (t.endCall()) ActionResult.ok("Ended the call.", verified = false) else ActionResult.fail("There's no call in progress to end.")
        } catch (e: SecurityException) { ActionResult.fail("Android refused to let JARVIS end the call.", Requirement.CALL_PHONE) }
        catch (e: Exception) { ActionResult.fail("I couldn't end the call (${e.javaClass.simpleName}).") }
    }
}
