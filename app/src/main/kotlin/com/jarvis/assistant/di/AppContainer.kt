package com.jarvis.assistant.di

import android.app.Application
import android.content.pm.ApplicationInfo
import com.jarvis.assistant.data.AndroidSettingsStore
import com.jarvis.assistant.data.AppLog
import com.jarvis.assistant.data.ChatRecorder
import com.jarvis.assistant.data.Db
import com.jarvis.assistant.data.FileIndex
import com.jarvis.assistant.data.SecretStore
import com.jarvis.assistant.data.SqlConversationMemory
import com.jarvis.assistant.data.SqlConversationRepository
import com.jarvis.assistant.data.SqlNoteStore
import com.jarvis.assistant.data.SqlReminderStore
import com.jarvis.assistant.data.TimerStore
import com.jarvis.assistant.platform.ActivityStarter
import com.jarvis.assistant.platform.AndroidAppLauncher
import com.jarvis.assistant.platform.AndroidCallGateway
import com.jarvis.assistant.platform.AndroidClockApp
import com.jarvis.assistant.platform.AndroidContactsGateway
import com.jarvis.assistant.platform.AndroidDeviceControl
import com.jarvis.assistant.platform.AndroidMediaGateway
import com.jarvis.assistant.platform.AndroidMessagingGateway
import com.jarvis.assistant.platform.AndroidReminderScheduler
import com.jarvis.assistant.platform.AndroidSpeaker
import com.jarvis.assistant.platform.AndroidTimerService
import com.jarvis.assistant.platform.AppAssistantControl
import com.jarvis.assistant.platform.DocumentStoreAndroid
import com.jarvis.assistant.platform.LockStateReader
import com.jarvis.assistant.platform.NotificationHelper
import com.jarvis.assistant.platform.PermissionHelper
import com.jarvis.assistant.platform.UnavailableNavigator
import com.jarvis.assistant.platform.UnavailableNotifications
import com.jarvis.core.ai.DefaultLlmGateway
import com.jarvis.core.engine.DefaultAssistantEngine
import com.jarvis.core.image.DefaultImageService
import com.jarvis.core.lock.LockPolicy
import com.jarvis.core.net.JdkHttpClient
import com.jarvis.core.organizer.NoteService
import com.jarvis.core.organizer.ReminderService
import com.jarvis.core.ports.ConversationRepository
import com.jarvis.core.ports.DocumentStore
import com.jarvis.core.ports.GlobalAction
import com.jarvis.core.ports.NotificationGateway
import com.jarvis.core.ports.Services
import com.jarvis.core.ports.SystemNavigator
import com.jarvis.core.routing.CapabilityRouter
import com.jarvis.core.search.DefaultWebSearch
import com.jarvis.core.settings.Settings
import com.jarvis.core.settings.SettingsStore
import com.jarvis.core.tools.impl.ToolRegistry
import com.jarvis.core.util.SystemClock
import com.jarvis.core.weather.OpenMeteoWeather
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The object graph, built by hand (no DI framework), created once in JarvisApp.onCreate. Every member is lazy, so a broadcast that wakes the process only builds what
 * it touches. Ports are implemented by the adapters in `platform` and `data`; the platform-independent logic lives in :core.
 *
 * [notificationGateway] and [navigator] come from the services that exist only when the user enables them (notification listener, accessibility): they are
 * registered through [attachNotificationGateway] / [attachNavigator] by those services, and until then report honestly that they are unavailable.
 */
class AppContainer(val app: Application) {
    private val debuggable = (app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    private val clock = SystemClock

    // ---- logging, settings, secrets
    val appLog: AppLog by lazy { AppLog(app, debuggable) }
    val secretStore: SecretStore by lazy { SecretStore(app, appLog) }
    val androidSettings: AndroidSettingsStore by lazy { AndroidSettingsStore(app, secretStore) }
    val settingsStore: SettingsStore get() = androidSettings
    val settings: Settings by lazy { Settings(androidSettings) }

    /** App-wide scope: failures are logged (redacted), never crash the process, and one failing job does not cancel the others. */
    val scope: CoroutineScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Default + appLog.coroutineHandler) }

    // ---- database & stores
    val db: Db by lazy { Db(app, appLog) }
    private val reminderStore by lazy { SqlReminderStore(db) }
    private val noteStore by lazy { SqlNoteStore(db) }
    val fileIndex: FileIndex by lazy { FileIndex(db) }
    private val timerStore by lazy { TimerStore(db) }

    val conversations: SqlConversationRepository by lazy { SqlConversationRepository(db, clock) { files } }
    private val memory by lazy { SqlConversationMemory(db, conversations) }

    // ---- Android adapters
    val permissions: PermissionHelper by lazy { PermissionHelper(app, settings) }
    val notificationHelper: NotificationHelper by lazy { NotificationHelper(app, settings, appLog) }
    val lockState: LockStateReader by lazy { LockStateReader(app) }
    val lockLauncher: com.jarvis.assistant.lock.LockScreenLauncher by lazy { com.jarvis.assistant.lock.LockScreenLauncher(app, notificationHelper, permissions, appLog) }
    val activityStarter: ActivityStarter by lazy { ActivityStarter(app, notificationHelper, appLog) }
    val timers: AndroidTimerService by lazy { AndroidTimerService(app, timerStore, clock, appLog) }
    val documents: DocumentStoreAndroid by lazy { DocumentStoreAndroid(app, settings, fileIndex, clock, appLog) }
    val files: DocumentStore get() = documents
    val speaker: AndroidSpeaker by lazy { AndroidSpeaker(app, settings, appLog) }
    val appLauncher: AndroidAppLauncher by lazy { AndroidAppLauncher(app, activityStarter, appLog) }
    val device: AndroidDeviceControl by lazy { AndroidDeviceControl(app, appLog) }
    val islandSwitch: com.jarvis.assistant.platform.IslandSwitch by lazy { com.jarvis.assistant.platform.IslandSwitch(app, settings, permissions, appLog) }
    val voice: com.jarvis.assistant.voice.VoiceInput by lazy { com.jarvis.assistant.voice.VoiceInput(app, settings, appLog) }
    val wakeModels: com.jarvis.assistant.voice.WakeModelStore by lazy { com.jarvis.assistant.voice.WakeModelStore(app) }
    val wakeDownloader: com.jarvis.assistant.voice.WakeModelDownloader by lazy { com.jarvis.assistant.voice.WakeModelDownloader(wakeModels, appLog) }
    val images: com.jarvis.assistant.ui.kit.ImageLoader by lazy { com.jarvis.assistant.ui.kit.ImageLoader(app, appLog) }

    @Volatile private var notifications: NotificationGateway? = null
    @Volatile private var navigatorImpl: SystemNavigator? = null
    private val notificationProxy: NotificationGateway by lazy { UnavailableNotifications(permissions) }
    private val navigatorFallback: SystemNavigator by lazy { UnavailableNavigator() }
    fun attachNotificationGateway(g: NotificationGateway?) { notifications = g }
    fun attachNavigator(n: SystemNavigator?) { navigatorImpl = n }
    val notificationGateway: NotificationGateway get() = notifications ?: notificationProxy
    val navigator: SystemNavigator get() = navigatorImpl ?: navigatorFallback

    // ---- network services (core)
    private val http by lazy { JdkHttpClient() }
    val llm: DefaultLlmGateway by lazy { DefaultLlmGateway(settings, http, clock, appLog) }

    // ---- the ports, assembled
    val services: Services by lazy {
        val control = AppAssistantControl(app, settings, permissions, activityStarter, notificationHelper, appLog)
        Services(
            settings = settings, clock = clock, log = appLog, permissions = permissions,
            reminders = reminderStore, reminderScheduler = AndroidReminderScheduler(app, clock, appLog),
            notes = noteStore, timers = timers, clockApp = AndroidClockApp(activityStarter),
            apps = appLauncher,
            contacts = AndroidContactsGateway(app, permissions, appLog),
            calls = AndroidCallGateway(app, permissions, activityStarter, appLog),
            messaging = AndroidMessagingGateway(app, activityStarter, appLog),
            // delegating wrappers: the real gateways attach later (when the user enables the listener / accessibility service)
            notifications = DelegatingNotifications { notificationGateway },
            device = device,
            navigator = object : SystemNavigator { override suspend fun perform(action: GlobalAction) = navigator.perform(action) },
            media = AndroidMediaGateway(app, activityStarter, appLauncher, appLog),
            speaker = speaker, documents = documents, memory = memory,
            web = DefaultWebSearch(http, settings, appLog), weather = OpenMeteoWeather(http, settings, appLog),
            images = DefaultImageService(http, settings, documents, scope, clock, appLog),
            control = control,
            contactChecker = com.jarvis.core.contacts.AiContactNameChecker(llm, appLog),
        )
    }

    val reminderService: ReminderService by lazy { ReminderService(services) }
    val noteService: NoteService by lazy { NoteService(services) }
    val lockPolicy: LockPolicy by lazy { LockPolicy(settings, clock) }
    private val registry: ToolRegistry by lazy { ToolRegistry.default(services, reminderService, noteService) }
    val capabilityRouter: CapabilityRouter by lazy { CapabilityRouter(services, registry) }

    val engine: DefaultAssistantEngine by lazy {
        DefaultAssistantEngine(
            services, llm, scope, registry, lockPolicy, capabilityRouter,
            lockProvider = { lockState.read() }, onlineProvider = { permissions.isOnline() },
        )
    }

    private var started = false

    /** Starts the background collectors (chat history recorder). Called once from JarvisApp.onCreate. */
    @Synchronized fun start() {
        if (started) return
        started = true
        ChatRecorder(engine, conversations, scope, appLog).start()
        pruneConversations()
    }

    /** Applies the "keep conversations for N days" choice (0 = keep until I clear them). Runs at start and when the choice changes; never touches the current conversation. */
    fun pruneConversations() {
        val days = settings.int(com.jarvis.core.settings.Keys.KEEP_CONVERSATION_DAYS, 0)
        if (days < 1) return
        scope.launch {
            try {
                val n = conversations.pruneOlderThan(clock.nowMs() - days * 86_400_000L)
                if (n > 0) appLog.i("Chat", "removed $n old conversations (kept for $days days)")
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { appLog.w("Chat", "could not remove old conversations (${e.javaClass.simpleName})") }
        }
    }

    private class DelegatingNotifications(private val target: () -> NotificationGateway) : NotificationGateway {
        override fun isAccessGranted() = target().isAccessGranted()
        override fun active() = target().active()
        override suspend fun quickReply(key: String, text: String) = target().quickReply(key, text)
        override fun dismiss(key: String) = target().dismiss(key)
    }
}
