package com.jarvis.core.tools.impl

import com.jarvis.core.model.Card
import com.jarvis.core.model.Outcome
import com.jarvis.core.model.ReplyKind
import com.jarvis.core.model.Requirement
import com.jarvis.core.ports.ActionResult
import com.jarvis.core.ports.GlobalAction
import com.jarvis.core.ports.Services
import com.jarvis.core.tools.Args
import com.jarvis.core.tools.Capability
import com.jarvis.core.tools.LockClass
import com.jarvis.core.tools.Tool
import com.jarvis.core.tools.ToolContext
import com.jarvis.core.tools.ToolSpec
import kotlin.math.abs

// ===================================================================================================== navigate
/** System navigation (home / back / recents / notification shade / quick settings / lock / screenshot). Android allows this only through the accessibility service. */
class NavigateTool : Tool {
    override val spec = ToolSpec(
        "navigate",
        "Press a system navigation action on the phone: home, back, recents, notifications (pull down the shade), quick_settings, lock (lock the screen), screenshot, power_menu. " +
            "Needs JARVIS's accessibility service. Android does NOT let any assistant close other apps: for 'close X' use recents and tell the user to swipe it away.",
        mapOf("action" to T.p("home | back | recents | notifications | quick_settings | lock | screenshot | power_menu",
            enum = listOf("home", "back", "recents", "notifications", "quick_settings", "lock", "screenshot", "power_menu"))),
        listOf("action"), Capability.SYSTEM_NAVIGATION, LockClass.ADMIN, needs = listOf(Requirement.ACCESSIBILITY),
    )

    override suspend fun execute(args: Args, ctx: ToolContext): Outcome =
        perform(ctx.services, args.str("action", "target", "value").lowercase().replace(' ', '_'))

    companion object {
        fun mapAction(a: String): GlobalAction? = when (a) {
            "home", "go_home" -> GlobalAction.HOME
            "back", "go_back" -> GlobalAction.BACK
            "recents", "recent_apps", "overview", "app_switcher" -> GlobalAction.RECENTS
            "notifications", "notification_shade", "notification_panel", "pull_down_notifications" -> GlobalAction.NOTIFICATIONS
            "quick_settings", "quicksettings" -> GlobalAction.QUICK_SETTINGS
            "lock", "lock_screen", "lock_phone" -> GlobalAction.LOCK_SCREEN
            "screenshot", "take_screenshot", "screen_shot" -> GlobalAction.SCREENSHOT
            "power", "power_menu", "power_dialog" -> GlobalAction.POWER_DIALOG
            else -> null
        }

        suspend fun perform(svc: Services, action: String): Outcome {
            if (action in setOf("close", "close_app", "quit", "kill", "exit")) {
                return Outcome(false,
                    "Android doesn't allow an assistant to close other apps. Offer to open the recent-apps screen (navigate action recents) so the user can swipe the app away.",
                    com.jarvis.core.model.AssistantReply.error("Android doesn't let me close other apps. I can open the recent apps screen so you can swipe it away."))
            }
            val g = mapAction(action) ?: return T.fail("I can go home, go back, open recents, pull down notifications or quick settings, lock the screen, take a screenshot or open the power menu — not “$action”.", "settings")
            val r = svc.navigator.perform(g)
            if (!r.ok) {
                val missing = r.missing.ifEmpty { if (!svc.permissions.isGranted(Requirement.ACCESSIBILITY)) listOf(Requirement.ACCESSIBILITY) else emptyList() }
                return T.failed(r.copy(missing = missing), "I couldn't do that.", "settings")
            }
            val text = when (g) {
                GlobalAction.HOME -> "Went to the home screen."
                GlobalAction.BACK -> "Pressed back."
                GlobalAction.RECENTS -> "Opened the recent apps."
                GlobalAction.NOTIFICATIONS -> "Pulled down the notifications."
                GlobalAction.QUICK_SETTINGS -> "Opened quick settings."
                GlobalAction.LOCK_SCREEN -> "Locking the screen."
                GlobalAction.SCREENSHOT -> "Asked Android to take a screenshot. It saves to your Screenshots folder; I can't confirm it was taken."
                GlobalAction.POWER_DIALOG -> "Opened the power menu."
            }
            return Outcome(true, text, T.reply(text, "settings"), verified = false)
        }
    }
}

// ===================================================================================================== system
class SystemTool : Tool {
    override val spec = ToolSpec(
        "system",
        "Phone controls. volume_get/volume_set/volume_up/volume_down, mute/unmute/toggle_mute, brightness_get/brightness_set/brightness_up/brightness_down, " +
            "flashlight_on/flashlight_off, dnd_on/dnd_off (Do Not Disturb), battery, network, clipboard (what was copied), specs (phone details), stats (battery+network+specs), " +
            "open_settings (value: wifi, bluetooth, display, sound, battery, apps, notifications, location ...), screenshot, lock.",
        mapOf(
            "action" to T.p("e.g. volume_set, mute, brightness_up, flashlight_on, battery, stats, open_settings"),
            "value" to T.p("Number 0-100 for volume/brightness, on/off, or the settings page"),
        ),
        listOf("action"), Capability.DEVICE_CONTROL, LockClass.OPEN,
    )

    override suspend fun execute(args: Args, ctx: ToolContext): Outcome {
        val svc = ctx.services
        val dev = svc.device
        val act = args.str("action", default = "stats").lowercase().replace(' ', '_').replace('-', '_')
        val value = args.str("value", "target", "level")

        // ---- navigation-ish aliases
        if (act in setOf("screenshot", "lock", "lock_screen", "power_menu", "power")) return NavigateTool.perform(svc, act)
        if (act == "open_settings") return OpenTool().execute(Args(mapOf("target" to (if (value.isBlank()) "settings" else "$value settings"))), ctx)

        // ---- reads
        when (act) {
            "battery" -> {
                val b = dev.battery()
                val text = "Battery is at ${b.percent}%${if (b.charging) " and charging" else ""}." + (b.temperatureC?.let { " It's ${"%.0f".format(it)}°C." } ?: "")
                return T.info(text, T.reply(text, "battery", Card("system", "Battery", "${b.percent}%", "battery", fields = mapOf("percent" to b.percent.toString(), "charging" to b.charging.toString()))))
            }
            "network", "internet", "wifi_status" -> {
                val n = dev.network()
                val text = if (n.connected) "You're online via ${n.type}${if (n.metered) " (a metered connection)" else ""}." else "You're offline."
                return T.info(text, T.reply(text, if (n.connected) "wifi" else "wifi-off", Card("info", if (n.connected) "Online" else "Offline", n.type, if (n.connected) "wifi" else "wifi-off")))
            }
            "clipboard", "clipboard_get" -> {
                val c = dev.clipboardText()
                return if (c == null) T.info("The clipboard is empty or Android didn't let me read it (apps can only read the clipboard while they are in the foreground).",
                    T.reply("I can't read the clipboard right now. Android only allows it while JARVIS is on screen.", "clipboard", kind = ReplyKind.INFO))
                else if (c.isBlank()) T.info("The clipboard is empty.", T.reply("Your clipboard is empty.", "clipboard", kind = ReplyKind.INFO))
                else T.info(T.modelNote(svc, "Clipboard text: ${c.take(400)}", "The clipboard has text (${c.length} characters). It is private and shown to the user on screen; do not repeat it."),
                    T.reply("Your clipboard says: ${c.take(300)}", "clipboard", sensitive = true))
            }
            "stats", "specs", "system_info", "device", "device_info", "status" -> {
                val rep = dev.report()
                val b = dev.battery()
                val n = dev.network()
                val lines = rep.lines + listOf("Battery" to "${b.percent}%${if (b.charging) " (charging)" else ""}", "Network" to if (n.connected) n.type else "offline")
                val text = lines.joinToString(", ") { "${it.first}: ${it.second}" }
                return T.info(text, T.reply(lines.joinToString("; ") { "${it.first} ${it.second}" }.take(220), "cpu",
                    Card("system", "This phone", "", "cpu", items = lines.map { com.jarvis.core.model.CardItem(it.first, it.second) })))
            }
        }

        // ---- flashlight / DND
        if (act.startsWith("flashlight") || act.startsWith("torch")) {
            val on = onOff(act, value) ?: return T.fail("Should the flashlight be on or off?", "zap")
            val r = dev.setFlashlight(on)
            return result(r, if (on) "Flashlight on." else "Flashlight off.", "zap")
        }
        if (act.startsWith("dnd") || act.startsWith("do_not_disturb") || act == "silent_mode") {
            val on = onOff(act, value) ?: return T.fail("Should Do Not Disturb be on or off?", "moon")
            val r = dev.setDoNotDisturb(on)
            if (!r.ok && Requirement.DND_ACCESS in r.missing) return T.failed(r, "I need Do Not Disturb access first.", "moon")
            return result(r, "Do Not Disturb is ${if (on) "on" else "off"}.", "moon")
        }

        // ---- volume
        val isVolume = act.startsWith("volume") || act.startsWith("sound")
        val isBright = act.startsWith("brightness")
        if (act in setOf("mute", "unmute", "toggle_mute")) {
            val target = when (act) { "mute" -> true; "unmute" -> false; else -> !dev.isMuted() }
            val r = dev.setMuted(target)
            if (!r.ok) return T.failed(r, "I couldn't change mute.", "volume-x")
            val now = dev.isMuted()
            val ok = now == target
            val text = if (ok) (if (target) "Muted." else "Unmuted.") else "I asked Android to ${if (target) "mute" else "unmute"}, but the phone still reports ${if (now) "muted" else "not muted"}."
            return Outcome(ok, text, if (ok) T.reply(text, if (target) "volume-x" else "volume-2", T.levelCard("volume", if (target) "Muted" else "Volume", dev.volume() ?: 50, if (target) "volume-x" else "volume-2", target))
            else com.jarvis.core.model.AssistantReply.error(text, "volume-x"), verified = true)
        }
        if (isVolume || isBright) {
            val kind = if (isVolume) "volume" else "brightness"
            val icon = if (isVolume) "volume-2" else "sun"
            val suffix = act.substringAfter('_', "")
            val read = { if (isVolume) dev.volume() else dev.brightness() }
            val apply: (Int) -> ActionResult = { v -> if (isVolume) dev.setVolume(v) else dev.setBrightness(v) }
            val nums = Regex("\\d{1,3}").find(value)?.value?.toIntOrNull()
            val target: Int? = when {
                suffix == "up" -> null
                suffix == "down" -> null
                suffix in setOf("set", "to") || nums != null -> nums ?: when (value.lowercase()) { "max", "maximum", "full" -> 100; "half", "middle" -> 50; "min", "minimum" -> 0; else -> null }
                else -> null
            }
            if (suffix == "up" || suffix == "down") {
                val r = if (isVolume) dev.changeVolume(if (suffix == "up") 10 else -10) else run {
                    val cur = dev.brightness() ?: return T.fail("I can't read the screen brightness.", icon)
                    dev.setBrightness((cur + if (suffix == "up") 10 else -10).coerceIn(0, 100))
                }
                if (!r.ok) return T.failed(r, "I couldn't change the $kind.", icon)
                val v = read()
                val text = if (v != null) "${T.title(kind)} is now $v%." else r.message.ifBlank { "${T.title(kind)} changed." }
                return Outcome(true, text, T.reply(text, icon, v?.let { T.levelCard(kind, T.title(kind), it, icon) }), verified = v != null || r.verified)
            }
            if (target == null) {
                if (act.endsWith("set") || act.endsWith("to")) return T.fail("Give me a number from 0 to 100.", icon)
                val cur = read() ?: return T.fail("I can't read the $kind on this phone.", icon)
                return T.info("${T.title(kind)} is at $cur%.", T.reply("${T.title(kind)} is at $cur%.", icon, T.levelCard(kind, T.title(kind), cur, icon)))
            }
            val want = target.coerceIn(0, 100)
            val r = apply(want)
            if (!r.ok) return T.failed(r, "I couldn't set the $kind.", icon)
            val got = read()
            // volume has ~15 steps, so a read-back within 8 points counts as "it took"
            val tol = if (isVolume) 8 else 6
            val matches = got != null && abs(got - want) <= tol
            if (got != null && !matches) {
                val text = "I asked for $want% but the phone reports $got%."
                return Outcome(false, text, com.jarvis.core.model.AssistantReply.error(text, icon), verified = true)
            }
            val text = if (got != null) "${T.title(kind)} is now $got%." else "Set the $kind to $want%."
            return Outcome(true, text, T.reply(text, icon, T.levelCard(kind, T.title(kind), got ?: want, icon)), verified = got != null)
        }
        return T.fail("I can't do “$act”. I can handle volume, brightness, mute, flashlight, Do Not Disturb, battery, network, clipboard, phone details and settings pages.", "settings")
    }

    private fun onOff(act: String, value: String): Boolean? {
        val s = (act + " " + value).lowercase()
        // "_" is a word character, so "flashlight_on" needs its own separators
        return when {
            Regex("(?:^|[\\s_])(?:off|disable|stop)(?:$|[\\s_])").containsMatchIn(s) -> false
            Regex("(?:^|[\\s_])(?:on|enable|start)(?:$|[\\s_])").containsMatchIn(s) -> true
            else -> null
        }
    }

    private fun result(r: ActionResult, okText: String, icon: String): Outcome =
        if (r.ok) Outcome(true, okText + if (r.verified) "" else " (Android didn't confirm it.)", T.reply(okText, icon), verified = r.verified) else T.failed(r, "That didn't work.", icon)
}
