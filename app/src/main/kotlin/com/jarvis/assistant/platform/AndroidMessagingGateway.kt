package com.jarvis.assistant.platform

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.telephony.TelephonyManager
import com.jarvis.core.contacts.PhoneNumbers
import com.jarvis.core.ports.ActionResult
import com.jarvis.core.ports.MessagingApp
import com.jarvis.core.ports.MessagingGateway
import com.jarvis.core.util.JarvisLog
import java.util.Locale

/**
 * Opens a chat with the message already typed. JARVIS NEVER sends it: the user reads it and taps send in the other app (that is also why no SMS permission is
 * requested). WhatsApp takes a wa.me deep link (needs the number in international form; if the country can't be worked out, the share screen is used instead).
 * Telegram has no way to pre-fill a chat from a phone number, so it opens Telegram's own share screen and the user picks the chat.
 */
class AndroidMessagingGateway(context: Context, private val starter: ActivityStarter, private val log: JarvisLog = JarvisLog.None) : MessagingGateway {
    private val ctx = context.applicationContext
    private val pm: PackageManager = ctx.packageManager

    private val whatsappPackages = listOf("com.whatsapp", "com.whatsapp.w4b")
    private val telegramPackages = listOf("org.telegram.messenger", "org.telegram.messenger.web", "org.thunderdog.challegram")

    private fun installedOf(pkgs: List<String>): String? = pkgs.firstOrNull { p -> try { pm.getLaunchIntentForPackage(p) != null } catch (_: Exception) { false } }

    override fun isAvailable(app: MessagingApp): Boolean = when (app) {
        MessagingApp.WHATSAPP -> installedOf(whatsappPackages) != null
        MessagingApp.TELEGRAM -> installedOf(telegramPackages) != null
        MessagingApp.SMS -> starter.resolves(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:0")))
    }

    private fun regionIso(): String? {
        val tm = ctx.getSystemService(TelephonyManager::class.java)
        return listOf(tm?.simCountryIso, tm?.networkCountryIso, Locale.getDefault().country).firstOrNull { !it.isNullOrBlank() }
    }

    private fun share(pkg: String, text: String) = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text).setPackage(pkg)

    override fun openChat(app: MessagingApp, number: String?, text: String): ActionResult = when (app) {
        MessagingApp.WHATSAPP -> {
            val pkg = installedOf(whatsappPackages)
            if (pkg == null) ActionResult.fail("WhatsApp isn't installed on this phone.")
            else {
                val intl = number?.let { PhoneNumbers.toInternational(it, regionIso()) }
                if (intl != null) {
                    val link = Uri.parse("https://wa.me/$intl").buildUpon().appendQueryParameter("text", text).build()
                    starter.start(Intent(Intent.ACTION_VIEW, link).setPackage(pkg), "WhatsApp", "Opened WhatsApp with your message ready. It has not been sent — tap send.")
                } else {
                    log.i("Messaging", "no usable international number — using WhatsApp's share screen")
                    starter.start(share(pkg, text), "WhatsApp", "Opened WhatsApp's share screen with your message; pick the chat and tap send. It has not been sent.")
                }
            }
        }
        MessagingApp.TELEGRAM -> {
            val pkg = installedOf(telegramPackages)
            if (pkg == null) ActionResult.fail("Telegram isn't installed on this phone.")
            else starter.start(share(pkg, text), "Telegram", "Opened Telegram's share screen with your message; pick the chat and tap send. It has not been sent.")
        }
        MessagingApp.SMS -> {
            val to = number?.let { PhoneNumbers.dialable(it) }
            val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + (to ?: ""))).putExtra("sms_body", text)
            starter.start(intent, "your messages app", "Opened your messages app with the text ready. It has not been sent — tap send.")
        }
    }
}
