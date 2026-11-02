package com.jarvis.assistant.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.R
import com.jarvis.assistant.di.AppContainer
import com.jarvis.assistant.platform.AppForeground
import com.jarvis.assistant.platform.IslandState
import com.jarvis.assistant.platform.Intents
import com.jarvis.assistant.platform.ListeningState
import com.jarvis.assistant.platform.NotificationHelper
import com.jarvis.assistant.ui.kit.MainThread
import com.jarvis.assistant.voice.VoiceEvent
import com.jarvis.assistant.voice.WakeController
import com.jarvis.assistant.voice.WakeEvent
import com.jarvis.assistant.voice.WakeStatus
import com.jarvis.core.model.Origin
import com.jarvis.core.model.Requirement
import com.jarvis.core.ports.PermState
import com.jarvis.core.settings.Keys
import com.jarvis.core.wake.WakeText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The always-on listening service (foreground service of type "microphone", with an ongoing notification the user can't miss).
 *
 * What it does: runs [WakeController]; when the wake word is heard it plays a short tone, listens for ONE request with Android's recogniser, hands it to the
 * engine (origin WAKE, with the real keyguard state so the lock policy applies), lets the engine speak the answer, shows it on the island if that's running
 * (otherwise as a notification) and goes back to listening.
 *
 * What it can't do (see docs/BACKGROUND_LISTENING.md): start itself from the background on Android 12+/14+ (the user starts it from the app; after a reboot a
 * notification asks them to tap), survive aggressive OEM battery managers, or listen while a call is active or another app holds the microphone.
 */
class AssistantForegroundService : Service() {
    private lateinit var c: AppContainer
    private lateinit var scope: CoroutineScope
    private var wake: WakeController? = null
    private var request: Job? = null
    private var foregroundStarted = false
    private var userPaused = false
    private var lastStatusText = ""
    private val main = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        c = JarvisApp.container
        scope = CoroutineScope(SupervisorJob() + MainThread + c.appLog.coroutineHandler)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            Intents.ACTION_LISTEN_STOP -> { stopByUser(); return START_NOT_STICKY }
            ACTION_TOGGLE_PAUSE -> {
                if (!enterForeground()) return START_NOT_STICKY
                togglePause()
                return START_STICKY
            }
            ACTION_TALK -> {
                if (!enterForeground()) return START_NOT_STICKY
                ensureWake().start()
                talkNow()
                return START_STICKY
            }
        }
        // START_STICKY restarts arrive with a null intent; the explicit start is ACTION_LISTEN_START
        if (!enterForeground()) return START_NOT_STICKY
        ensureWake().start()
        return START_STICKY
    }

    /** The user swiped JARVIS out of the recents list. The foreground service keeps running by design; some phones kill it anyway, which is why the status is read back from [ListeningState]. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        c.appLog.i("Listening", "task removed from recents; service keeps running")
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        wake?.shutdown()
        wake = null
        request?.cancel()
        scope.cancel()
        c.engine.setListening(false)
        ListeningState.running = false
        ListeningState.detail = ""
        super.onDestroy()
    }

    // ------------------------------------------------------------------------------------------------ foreground / notification
    private fun enterForeground(): Boolean {
        if (foregroundStarted) return true
        if (c.permissions.state(Requirement.MICROPHONE) != PermState.GRANTED) { refuse(getString(R.string.svc_needs_permission)); return false }
        return try {
            startForeground(NotificationHelper.ID_LISTENING, notification(getString(R.string.svc_status_starting)), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            foregroundStarted = true
            ListeningState.running = true
            true
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException (Android 12+ from the background) or SecurityException (Android 14+ without the microphone permission)
            c.appLog.w("Listening", "startForeground refused (${e.javaClass.simpleName})")
            refuse(getString(R.string.svc_refused_text))
            false
        }
    }

    private fun refuse(text: String) {
        ListeningState.running = false
        c.notificationHelper.postResult(getString(R.string.svc_refused_title), text, NotificationHelper.ID_RESUME)
        stopSelf()
    }

    private fun action(code: Int, label: Int, action: String): Notification.Action {
        val pi = PendingIntent.getService(this, code, Intent(this, AssistantForegroundService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Action.Builder(null, getString(label), pi).build()
    }

    private fun notification(text: String): Notification {
        lastStatusText = text
        val actions = listOf(
            action(1, R.string.svc_talk, ACTION_TALK),
            action(2, if (userPaused) R.string.svc_resume else R.string.svc_pause, ACTION_TOGGLE_PAUSE),
            action(3, R.string.svc_stop, Intents.ACTION_LISTEN_STOP),
        )
        val lockActions = com.jarvis.assistant.lock.LockNotificationActions.build(this, c.settings)
        return c.notificationHelper.listeningNotification(text, actions + lockActions, showOnLockScreen = lockActions.isNotEmpty())
    }

    private fun showStatus(text: String) {
        ListeningState.detail = text
        if (!foregroundStarted) return
        c.notificationHelper.post(NotificationHelper.ID_LISTENING, notification(text))
    }

    // ------------------------------------------------------------------------------------------------ wake controller
    private fun ensureWake(): WakeController = wake ?: WakeController(
        context = this, settings = c.settings, permissions = c.permissions, models = c.wakeModels, log = c.appLog,
        onWake = { ev -> handle(ev) },
    ).also { w ->
        wake = w
        scope.launch { w.status.collect { onStatus(it) } }
    }

    private fun onStatus(st: WakeStatus) {
        when (st) {
            WakeStatus.Off -> {}
            WakeStatus.NeedsModels -> endWith(getString(R.string.svc_needs_models))
            WakeStatus.NeedsPermission -> endWith(getString(R.string.svc_needs_permission))
            WakeStatus.Starting -> showStatus(getString(R.string.svc_status_starting))
            is WakeStatus.Listening -> showStatus(getString(R.string.svc_status_listening, st.phrase))
            is WakeStatus.Paused -> showStatus(st.reason)
            is WakeStatus.Error -> if (st.willRetry) showStatus(st.message) else endWith(st.message)
        }
    }

    /** Can't listen and nothing the service can do alone will fix it: say so in a normal notification, record that listening is off, and stop. */
    private fun endWith(text: String) {
        c.settings.setFlag(Keys.BACKGROUND_LISTENING, false)
        c.notificationHelper.postResult(getString(R.string.svc_stopped_title), text, NotificationHelper.ID_RESUME)
        shutDown()
    }

    private fun stopByUser() {
        c.settings.setFlag(Keys.BACKGROUND_LISTENING, false)
        shutDown()
    }

    private fun shutDown() {
        wake?.stop()
        request?.cancel()
        ListeningState.running = false
        if (foregroundStarted) { stopForeground(STOP_FOREGROUND_REMOVE); foregroundStarted = false }
        stopSelf()
    }

    private fun togglePause() {
        val w = wake ?: return
        userPaused = !userPaused
        if (userPaused) w.pause(WakeController.REASON_FOR_USER) else w.resume(WakeController.REASON_FOR_USER)
        showStatus(lastStatusText)
    }

    // ------------------------------------------------------------------------------------------------ one request
    private fun talkNow() {
        val w = wake ?: return
        w.pause(WakeController.REASON_FOR_COMMAND)
        handle(WakeEvent("tap", 1f, "", com.jarvis.core.wake.WakeBackend.NONE))
    }

    private fun handle(ev: WakeEvent) {
        if (request?.isActive == true) return
        request = scope.launch {
            val lock = c.lockState.read()
            val locked = lock.deviceLocked
            try {
                c.engine.setListening(true)
                AssistantBus.tryShow(ShowRequest(locked, Origin.WAKE, ShowState.LISTENING))
                earcon()
                var command = ev.command.trim()
                if (command.isEmpty()) command = listenForCommand(locked) ?: return@launch
                AssistantBus.tryShow(ShowRequest(locked, Origin.WAKE, ShowState.HEARD, text = command.takeUnless { locked }))
                c.engine.setListening(false)
                var turns = 0
                while (true) {
                    val reply = c.engine.submit(command, Origin.WAKE, c.lockState.read())
                    deliver(reply.text, reply.sensitive, locked, needsScreen = reply.actions.isNotEmpty())
                    if (reply.extras["expect_reply"] != true || ++turns >= MAX_FOLLOW_UPS) break
                    c.engine.setListening(true)
                    command = listenForCommand(locked) ?: break
                    c.engine.setListening(false)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                c.appLog.e("Listening", "handling the wake word failed (${e.javaClass.simpleName})", e)
                AssistantBus.tryShow(ShowRequest(locked, Origin.WAKE, ShowState.FAILED, text = getString(R.string.svc_failed)))
            } finally {
                c.engine.setListening(false)
                wake?.resume(WakeController.REASON_FOR_COMMAND)
            }
        }
    }

    /** One spoken request, or null (after telling the surfaces why) if nothing usable was heard. A command that ends mid-sentence gets one more listen. */
    private suspend fun listenForCommand(locked: Boolean): String? {
        var text = ""
        for (pass in 0 until 2) {
            var final: String? = null
            var failure: VoiceEvent.Failed? = null
            withTimeoutOrNull(LISTEN_TIMEOUT_MS) {
                c.voice.listenOnce().collect { e ->
                    when (e) {
                        is VoiceEvent.Partial -> AssistantBus.tryShow(ShowRequest(locked, Origin.WAKE, ShowState.HEARD, text = "$text ${e.text}".trim().takeUnless { locked }))
                        is VoiceEvent.Level -> AssistantBus.tryLevel(e.value)
                        is VoiceEvent.Final -> final = e.text
                        is VoiceEvent.Failed -> failure = e
                        else -> {}
                    }
                }
            }
            val f = final
            if (f == null) {
                if (text.isNotEmpty()) break // the first half was understood; use it
                val msg = failure?.message ?: getString(R.string.svc_heard_nothing)
                AssistantBus.tryShow(ShowRequest(locked, Origin.WAKE, ShowState.FAILED, text = msg))
                return null
            }
            text = "$text $f".trim()
            if (pass == 0 && WakeText.isDangling(text)) continue
            break
        }
        return text.ifBlank { null }
    }

    /** The engine already spoke the reply (origin WAKE). Here the visual side: the island if it runs, otherwise a notification when JARVIS isn't on screen. */
    private fun deliver(text: String, sensitive: Boolean, locked: Boolean, needsScreen: Boolean) {
        val hide = locked && sensitive
        AssistantBus.tryShow(ShowRequest(locked, Origin.WAKE, ShowState.DONE, text = if (hide) getString(R.string.svc_locked_reply) else text))
        // A question with buttons (confirm a call, pick a contact, unlock) cannot be answered by voice alone on a locked phone if speech recognition fails: bring up the lock-screen assistant.
        if (locked && needsScreen && c.settings.flag(com.jarvis.core.settings.Keys.LOCK_MODE_ENABLED, false) && !AppForeground.activityVisible) {
            val how = c.lockLauncher.show()
            c.appLog.i("Listening", "lock-screen assistant requested for a reply with buttons: $how")
            if (how != com.jarvis.assistant.lock.LockScreenLauncher.How.SKIPPED) return
        }
        if (!IslandState.running && !AppForeground.activityVisible) {
            c.notificationHelper.postResult(getString(R.string.app_name), if (hide) getString(R.string.svc_locked_reply) else text)
        }
    }

    /** A short quiet tone so the user knows the microphone is open even with the screen off. */
    private fun earcon() {
        try {
            val tg = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 45)
            tg.startTone(ToneGenerator.TONE_PROP_ACK, 120)
            main.postDelayed({ try { tg.release() } catch (_: Exception) { } }, 400)
        } catch (_: Exception) { }
    }

    companion object {
        const val ACTION_TALK = "${Intents.PKG}.action.LISTEN_TALK"
        const val ACTION_TOGGLE_PAUSE = "${Intents.PKG}.action.LISTEN_TOGGLE_PAUSE"
        private const val LISTEN_TIMEOUT_MS = 30_000L
        private const val MAX_FOLLOW_UPS = 3
    }
}
