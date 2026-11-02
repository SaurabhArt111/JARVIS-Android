package com.jarvis.assistant.platform

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings as AndroidSettings
import com.jarvis.core.model.Requirement
import com.jarvis.core.ports.ActionResult
import com.jarvis.core.ports.BatteryInfo
import com.jarvis.core.ports.DeviceControl
import com.jarvis.core.ports.DeviceReport
import com.jarvis.core.ports.NetworkInfo
import com.jarvis.core.util.JarvisLog
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

/**
 * Volume, brightness, flashlight, Do Not Disturb, battery, network and phone details, through public framework APIs only.
 * Every setter re-reads the value afterwards and reports what the phone REALLY says (`verified = true` only then); a setter that Android refuses (missing special
 * access, no flashlight, fixed-volume device) returns ok = false with the missing [Requirement].
 */
class AndroidDeviceControl(context: Context, private val log: JarvisLog = JarvisLog.None) : DeviceControl {
    private val ctx = context.applicationContext
    private val audio: AudioManager = ctx.getSystemService(AudioManager::class.java)
    private val nm: NotificationManager = ctx.getSystemService(NotificationManager::class.java)
    private val cm: ConnectivityManager = ctx.getSystemService(ConnectivityManager::class.java)
    private val camera: CameraManager? = ctx.getSystemService(CameraManager::class.java)

    // ------------------------------------------------------------------------------------------------ volume (music stream)
    private val stream = AudioManager.STREAM_MUSIC
    private fun maxIdx() = audio.getStreamMaxVolume(stream).coerceAtLeast(1)
    private fun pct(idx: Int) = (idx * 100f / maxIdx()).roundToInt()

    override fun volume(): Int? = try { pct(audio.getStreamVolume(stream)) } catch (_: Exception) { null }

    override fun setVolume(percent: Int): ActionResult = applyIndex((percent.coerceIn(0, 100) * maxIdx() / 100f).roundToInt())

    private fun applyIndex(want: Int): ActionResult = try {
        audio.setStreamVolume(stream, want, 0)
        val got = audio.getStreamVolume(stream)
        if (got == want) ActionResult.ok("Volume is ${pct(got)}%.", verified = true)
        else ActionResult(false, "I asked for ${pct(want)}% but the phone is at ${pct(got)}% (some outputs, like Bluetooth speakers, control their own volume).", verified = true)
    } catch (e: SecurityException) {
        ActionResult.fail("Android wouldn't let JARVIS change the volume right now (Do Not Disturb may be restricting it).", Requirement.DND_ACCESS)
    } catch (e: Exception) { ActionResult.fail("I couldn't change the volume (${e.javaClass.simpleName}).") }

    override fun changeVolume(deltaPercent: Int): ActionResult {
        val cur = try { audio.getStreamVolume(stream) } catch (e: Exception) { return ActionResult.fail("I can't read the volume on this phone.") }
        val step = (deltaPercent * maxIdx() / 100f).roundToInt().let { if (it == 0 && deltaPercent != 0) (if (deltaPercent > 0) 1 else -1) else it }
        return applyIndex((cur + step).coerceIn(0, maxIdx()))
    }

    override fun isMuted(): Boolean = try { audio.isStreamMute(stream) || audio.getStreamVolume(stream) == 0 } catch (_: Exception) { false }

    override fun setMuted(muted: Boolean): ActionResult = try {
        audio.adjustStreamVolume(stream, if (muted) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE, 0)
        if (!muted && audio.getStreamVolume(stream) == 0) audio.setStreamVolume(stream, (maxIdx() * 0.3f).roundToInt().coerceAtLeast(1), 0)   // nothing to restore: pick a gentle level
        val now = isMuted()
        if (now == muted) ActionResult.ok(if (muted) "Muted." else "Unmuted.", verified = true)
        else ActionResult(false, "Android did not ${if (muted) "mute" else "unmute"} the volume.", verified = true)
    } catch (e: SecurityException) { ActionResult.fail("Android wouldn't let JARVIS change the volume right now.", Requirement.DND_ACCESS) }
    catch (e: Exception) { ActionResult.fail("I couldn't change mute (${e.javaClass.simpleName}).") }

    // ------------------------------------------------------------------------------------------------ brightness
    override fun brightness(): Int? = try {
        val raw = AndroidSettings.System.getInt(ctx.contentResolver, AndroidSettings.System.SCREEN_BRIGHTNESS, -1)
        if (raw < 0) null else (raw * 100f / 255f).roundToInt().coerceIn(0, 100)
    } catch (_: Exception) { null }

    override fun setBrightness(percent: Int): ActionResult {
        if (!AndroidSettings.System.canWrite(ctx)) return ActionResult.fail("I need permission to change system settings before I can change the brightness.", Requirement.WRITE_SETTINGS)
        val raw = (percent.coerceIn(0, 100) * 255 / 100f).roundToInt().coerceIn(1, 255)   // 0 would black the screen on some phones
        return try {
            val resolver = ctx.contentResolver
            val auto = AndroidSettings.System.getInt(resolver, AndroidSettings.System.SCREEN_BRIGHTNESS_MODE, AndroidSettings.System.SCREEN_BRIGHTNESS_MODE_MANUAL) ==
                AndroidSettings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
            if (auto) AndroidSettings.System.putInt(resolver, AndroidSettings.System.SCREEN_BRIGHTNESS_MODE, AndroidSettings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            val wrote = AndroidSettings.System.putInt(resolver, AndroidSettings.System.SCREEN_BRIGHTNESS, raw)
            val got = AndroidSettings.System.getInt(resolver, AndroidSettings.System.SCREEN_BRIGHTNESS, -1)
            if (wrote && got == raw) ActionResult.ok("Brightness is ${brightness()}%." + if (auto) " Adaptive brightness was switched off." else "", verified = true)
            else ActionResult(false, "Android did not accept the new brightness.", verified = true)
        } catch (e: SecurityException) {
            ActionResult.fail("I need permission to change system settings before I can change the brightness.", Requirement.WRITE_SETTINGS)
        } catch (e: Exception) { ActionResult.fail("I couldn't change the brightness (${e.javaClass.simpleName}).") }
    }

    // ------------------------------------------------------------------------------------------------ flashlight
    @Volatile private var torchOn: Boolean? = null
    private var torchRegistered = false
    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) { if (cameraId == flashCameraId) torchOn = enabled }
        override fun onTorchModeUnavailable(cameraId: String) { /* camera in use by another app */ }
    }

    private val flashCameraId: String? by lazy {
        try {
            camera?.cameraIdList?.firstOrNull { id ->
                val c = camera.getCameraCharacteristics(id)
                c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true && c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            }
        } catch (_: Exception) { null }
    }

    override fun setFlashlight(on: Boolean): ActionResult {
        val cam = camera
        val id = flashCameraId
        if (cam == null || id == null) return ActionResult.fail("This phone doesn't have a flashlight I can control.")
        return try {
            synchronized(this) { if (!torchRegistered) { cam.registerTorchCallback(torchCallback, Handler(Looper.getMainLooper())); torchRegistered = true } }
            torchOn = null
            cam.setTorchMode(id, on)
            // the callback (delivered on the main thread) is the only way Android reports the real torch state; wait briefly for it
            val deadline = SystemClock.elapsedRealtime() + 900
            while (torchOn == null && SystemClock.elapsedRealtime() < deadline) Thread.sleep(30)
            when (torchOn) {
                on -> ActionResult.ok(if (on) "Flashlight on." else "Flashlight off.", verified = true)
                null -> ActionResult(true, "Asked Android to turn the flashlight ${if (on) "on" else "off"}; it didn't report back.", verified = false)
                else -> ActionResult(false, "Android did not turn the flashlight ${if (on) "on" else "off"}.", verified = true)
            }
        } catch (e: CameraAccessException) {
            ActionResult.fail("The flashlight is in use by another app right now.")
        } catch (e: IllegalArgumentException) {
            ActionResult.fail("The flashlight isn't available right now.")
        } catch (e: Exception) { ActionResult.fail("I couldn't use the flashlight (${e.javaClass.simpleName}).") }
    }

    // ------------------------------------------------------------------------------------------------ Do Not Disturb
    override fun setDoNotDisturb(on: Boolean): ActionResult {
        if (!nm.isNotificationPolicyAccessGranted) return ActionResult.fail("I need Do Not Disturb access before I can change it.", Requirement.DND_ACCESS)
        return try {
            // "priority only" (alarms and priority contacts still get through) is the safe version of DND: an alarm will never be silenced by JARVIS
            nm.setInterruptionFilter(if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL)
            val now = nm.currentInterruptionFilter
            val isOn = now != NotificationManager.INTERRUPTION_FILTER_ALL && now != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
            if (isOn == on) ActionResult.ok(if (on) "Do Not Disturb is on (alarms and priority callers still get through)." else "Do Not Disturb is off.", verified = true)
            else ActionResult(false, "Android did not change Do Not Disturb.", verified = true)
        } catch (e: SecurityException) {
            ActionResult.fail("I need Do Not Disturb access before I can change it.", Requirement.DND_ACCESS)
        } catch (e: Exception) { ActionResult.fail("I couldn't change Do Not Disturb (${e.javaClass.simpleName}).") }
    }

    // ------------------------------------------------------------------------------------------------ battery / network / report
    override fun battery(): BatteryInfo {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val temp = i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val percent = if (level >= 0 && scale > 0) (level * 100f / scale).roundToInt() else -1
        return BatteryInfo(percent, status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL, if (temp == Int.MIN_VALUE) null else temp / 10.0)
    }

    override fun network(): NetworkInfo {
        val net = cm.activeNetwork ?: return NetworkInfo(false, "none", false)
        val caps = cm.getNetworkCapabilities(net) ?: return NetworkInfo(false, "none", false)
        val type = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile data"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "Bluetooth"
            else -> "unknown"
        }
        return NetworkInfo(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET), type, !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
    }

    override fun report(): DeviceReport {
        fun gb(b: Long) = String.format(Locale.US, "%.1f GB", b / 1_073_741_824.0)
        val lines = ArrayList<Pair<String, String>>()
        lines.add("Device" to "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}")
        lines.add("Android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        try { lines.add("Security patch" to Build.VERSION.SECURITY_PATCH) } catch (_: Exception) { }
        try {
            val st = StatFs(Environment.getDataDirectory().path)
            lines.add("Storage" to "${gb(st.availableBytes)} free of ${gb(st.totalBytes)}")
        } catch (_: Exception) { }
        try {
            val mi = ActivityManager.MemoryInfo()
            ctx.getSystemService(ActivityManager::class.java).getMemoryInfo(mi)
            lines.add("Memory" to "${gb(mi.availMem)} free of ${gb(mi.totalMem)}")
        } catch (_: Exception) { }
        val up = SystemClock.elapsedRealtime() / 1000
        lines.add("Uptime" to if (up >= 86_400) "${up / 86_400} d ${up % 86_400 / 3600} h" else "${up / 3600} h ${up % 3600 / 60} min")
        lines.add("Time zone" to TimeZone.getDefault().id)
        lines.add("Language" to Locale.getDefault().toLanguageTag())
        return DeviceReport(lines)
    }

    // ------------------------------------------------------------------------------------------------ clipboard
    /** Android only lets the foreground app read the clipboard (Android 10+); in the background this returns null and JARVIS says so. */
    override fun clipboardText(): String? = try {
        val cb = ctx.getSystemService(ClipboardManager::class.java)
        cb.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString()?.takeIf { it.isNotBlank() }
    } catch (_: Exception) { null }
}
