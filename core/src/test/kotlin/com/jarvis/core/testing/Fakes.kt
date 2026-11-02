package com.jarvis.core.testing

import com.jarvis.core.model.Requirement
import com.jarvis.core.ports.*
import com.jarvis.core.settings.InMemorySettingsStore
import com.jarvis.core.settings.Settings
import com.jarvis.core.util.FixedClock
import com.jarvis.core.util.JarvisLog
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/** Shared in-memory fakes for unit tests. Each fake records what it was asked to do so tests can assert on REAL outcomes. */

class FakePermissions(var granted: MutableSet<Requirement> = Requirement.values().toMutableSet()) : PermissionChecker {
    override fun state(requirement: Requirement) = if (requirement in granted) PermState.GRANTED else PermState.DENIED
}

class InMemoryReminderStore : ReminderStore {
    val items = LinkedHashMap<String, Reminder>()
    override suspend fun insert(reminder: Reminder): Reminder { items[reminder.id] = reminder; return reminder }
    override suspend fun get(id: String) = items[id]
    override suspend fun all() = items.values.sortedBy { it.due }
    override suspend fun update(reminder: Reminder): Reminder? { if (reminder.id !in items) return null; items[reminder.id] = reminder; return reminder }
    override suspend fun deletePermanently(id: String) = items.remove(id) != null
}

class FakeReminderScheduler(var exact: Boolean = true, var failToSchedule: Boolean = false) : ReminderScheduler {
    val scheduled = LinkedHashMap<String, Reminder>()
    override fun schedule(reminder: Reminder): ScheduleResult {
        if (failToSchedule) return ScheduleResult(ScheduleMode.NONE, 0, "scheduler unavailable")
        scheduled[reminder.id] = reminder
        return ScheduleResult(if (exact) ScheduleMode.EXACT else ScheduleMode.INEXACT, reminder.triggerAt().toEpochMilli())
    }
    override fun cancel(reminderId: String) { scheduled.remove(reminderId) }
    override fun isScheduled(reminderId: String) = reminderId in scheduled
    override fun canScheduleExact() = exact
}

class InMemoryNoteStore : NoteStore {
    val items = LinkedHashMap<String, Note>()
    override suspend fun insert(note: Note): Note { items[note.id] = note; return note }
    override suspend fun get(id: String) = items[id]
    override suspend fun all() = items.values.sortedBy { it.createdAt }
    override suspend fun update(note: Note): Note? { if (note.id !in items) return null; items[note.id] = note; return note }
    override suspend fun deletePermanently(id: String) = items.remove(id) != null
}

class FakeTimerService(private val clock: FixedClock) : TimerService {
    private val timers = ArrayList<TimerInfo>()
    var exact = true
    override fun start(seconds: Int, label: String): TimerInfo {
        val t = TimerInfo("t${timers.size + 1}", label, seconds, clock.nowMs() + seconds * 1000L, exact)
        timers.add(t); return t
    }
    override fun stopAll(): Int { val n = timers.size; timers.clear(); return n }
    override fun active() = timers.filter { it.endsAtMs > clock.nowMs() }
}

class FakeClockApp : ClockApp {
    val alarms = ArrayList<Triple<Int, Int, String>>()
    val timers = ArrayList<Pair<Int, String>>()
    override fun setAlarm(hour: Int, minute: Int, label: String): ActionResult { alarms.add(Triple(hour, minute, label)); return ActionResult.ok("Handed to the Clock app.") }
    override fun setTimer(seconds: Int, label: String): ActionResult { timers.add(seconds to label); return ActionResult.ok("Handed to the Clock app.") }
}

class FakeAppLauncher(var installed: List<AppMatch> = listOf(
    AppMatch("Chrome", "com.android.chrome"), AppMatch("Spotify", "com.spotify.music"), AppMatch("WhatsApp", "com.whatsapp"),
    AppMatch("YouTube", "com.google.android.youtube"), AppMatch("Settings", "com.android.settings"), AppMatch("Brave", "com.brave.browser"),
)) : AppLauncher {
    val launched = ArrayList<String>()
    val urls = ArrayList<Pair<String, String?>>()
    val settingsOpened = ArrayList<String>()
    override fun resolve(query: String): List<AppMatch> {
        val q = query.trim().lowercase()
        return installed.filter { it.label.lowercase() == q }.ifEmpty { installed.filter { it.label.lowercase().contains(q) || q.contains(it.label.lowercase()) } }
    }
    override fun launch(app: AppMatch): ActionResult { launched.add(app.packageName); return ActionResult.ok("Opened ${app.label}.", verified = true) }
    override fun openUrl(url: String, browser: AppMatch?): ActionResult { urls.add(url to browser?.packageName); return ActionResult.ok("Opening $url") }
    override fun browsers() = installed.filter { it.packageName.contains("chrome") || it.packageName.contains("brave") }
    override fun openSettingsPage(page: String): ActionResult { settingsOpened.add(page); return ActionResult.ok("Opened $page settings.") }
    override fun openFile(uri: String, mime: String?) = ActionResult.ok("Opened.")
    override fun isInstalled(packageName: String) = installed.any { it.packageName == packageName }
}

class FakeContacts(var contacts: List<ContactMatch> = emptyList(), var missing: List<Requirement> = emptyList()) : ContactsGateway {
    override suspend fun search(name: String, limit: Int): ContactSearch {
        if (missing.isNotEmpty()) return ContactSearch(emptyList(), missing)
        val q = name.trim().lowercase()
        return ContactSearch(contacts.filter { it.displayName.lowercase().contains(q) }.take(limit))
    }
}

class FakeCalls : CallGateway {
    val calls = ArrayList<Pair<String, String>>()
    var direct = true
    override fun call(number: String, displayName: String): ActionResult {
        calls.add(number to displayName)
        return ActionResult.ok(if (direct) "Calling $displayName." else "Opened the dialer for $displayName.")
    }
    var ringing = false
    var inCall = false
    var answered = 0
    var ended = 0
    override fun answerRingingCall(): ActionResult {
        if (!ringing) return ActionResult.fail("No ringing call.")
        answered++
        return ActionResult.ok("Asked the phone to answer the call.")
    }
    override fun endCall(): ActionResult {
        if (!inCall) return ActionResult.fail("No call in progress.")
        ended++
        return ActionResult.ok("Asked the phone to end the call.")
    }
}

class FakeMessaging : MessagingGateway {
    val chats = ArrayList<Triple<MessagingApp, String?, String>>()
    override fun openChat(app: MessagingApp, number: String?, text: String): ActionResult { chats.add(Triple(app, number, text)); return ActionResult.ok("Opened ${app.name} with the text ready.") }
    override fun isAvailable(app: MessagingApp) = true
}

class FakeNotifications(var granted: Boolean = true, var list: List<NotificationInfo> = emptyList()) : NotificationGateway {
    val replies = ArrayList<Pair<String, String>>()
    override fun isAccessGranted() = granted
    override fun active() = if (granted) list else emptyList()
    override suspend fun quickReply(key: String, text: String): ActionResult {
        if (!granted) return ActionResult.fail("Notification access is off.", Requirement.NOTIFICATION_LISTENER)
        val n = list.firstOrNull { it.key == key } ?: return ActionResult.fail("That notification is gone.")
        if (!n.canReply) return ActionResult.fail("${n.appLabel} offers no quick reply for this notification.")
        replies.add(key to text); return ActionResult.ok("Reply sent through ${n.appLabel}.")
    }
    override fun dismiss(key: String) = ActionResult.ok()
}

class FakeDevice : DeviceControl {
    var vol = 30; var muted = false; var bright = 50; var torch = false; var dnd = false; var clip: String? = null
    var brightnessAllowed = true
    /** When set, setVolume reports success but the phone keeps this value (a stuck / managed volume). */
    var volumeStuck: Int? = null
    override fun volume() = vol
    override fun setVolume(percent: Int): ActionResult { vol = volumeStuck ?: percent.coerceIn(0, 100); return ActionResult.ok("Volume is $vol%.") }
    override fun changeVolume(deltaPercent: Int) = setVolume(vol + deltaPercent)
    override fun isMuted() = muted
    override fun setMuted(muted: Boolean): ActionResult { this.muted = muted; return ActionResult.ok(if (muted) "Muted." else "Unmuted.", verified = true) }
    override fun brightness() = bright
    override fun setBrightness(percent: Int): ActionResult {
        if (!brightnessAllowed) return ActionResult.fail("I need permission to change system settings.", Requirement.WRITE_SETTINGS)
        bright = percent.coerceIn(0, 100); return ActionResult.ok("Brightness is $bright%.", verified = true)
    }
    override fun setFlashlight(on: Boolean): ActionResult { torch = on; return ActionResult.ok(if (on) "Flashlight on." else "Flashlight off.", verified = true) }
    override fun setDoNotDisturb(on: Boolean): ActionResult { dnd = on; return ActionResult.ok("Do Not Disturb ${if (on) "on" else "off"}.") }
    override fun battery() = BatteryInfo(72, false, 31.0)
    override fun network() = NetworkInfo(true, "Wi-Fi", false)
    override fun report() = DeviceReport(listOf("Model" to "Test Phone", "Android" to "16"))
    override fun clipboardText() = clip
}

class FakeNavigator(var enabled: Boolean = true) : SystemNavigator {
    val performed = ArrayList<GlobalAction>()
    override suspend fun perform(action: GlobalAction): ActionResult {
        if (!enabled) return ActionResult.fail("Accessibility access is off.", Requirement.ACCESSIBILITY)
        performed.add(action); return ActionResult.ok("Done.")
    }
}

class FakeMedia(var track: TrackInfo? = null, var applyControls: Boolean = false) : MediaGateway {
    val controls = ArrayList<MediaAction>()
    val searches = ArrayList<Pair<String, String?>>()
    override fun nowPlaying() = track
    override fun control(action: MediaAction): ActionResult {
        controls.add(action)
        val t = track
        if (applyControls && t != null) track = when (action) {
            MediaAction.PAUSE -> t.copy(state = PlayState.PAUSED)
            MediaAction.PLAY -> t.copy(state = PlayState.PLAYING)
            MediaAction.STOP -> t.copy(state = PlayState.STOPPED)
            MediaAction.TOGGLE -> t.copy(state = if (t.state == PlayState.PLAYING) PlayState.PAUSED else PlayState.PLAYING)
            MediaAction.NEXT -> t.copy(title = t.title + " (next)")
            MediaAction.PREVIOUS -> t.copy(title = t.title + " (prev)")
        }
        return ActionResult.ok("ok")
    }
    override fun playFromSearch(query: String, app: AppMatch?): ActionResult { searches.add(query to app?.packageName); return ActionResult.ok("Searching") }
}

class FakeSpeaker : Speaker {
    val spoken = ArrayList<Pair<String, SpeechChannel>>()
    override suspend fun speak(text: String, channel: SpeechChannel): Boolean { spoken.add(text to channel); return true }
    override fun stop() {}
    override fun isSpeaking() = false
}

class InMemoryDocuments(private val clock: FixedClock) : DocumentStore {
    val files = ArrayList<StoredFile>()
    val contents = HashMap<String, String>()
    private fun safe(title: String) = title.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifEmpty { "file" }.take(60)
    override suspend fun saveText(category: ContentCategory, title: String, content: String, extension: String, source: String?): StoredFile {
        var name = "${safe(title)}.$extension"; var i = 2
        while (files.any { it.name == name && it.category == category }) name = "${safe(title)}_${i++}.$extension"
        val f = StoredFile("f${files.size + 1}", name, category, "mem://${category.folder}/$name", "text/plain", content.length.toLong(), clock.instant(), StorageLocation.APP_PRIVATE, source)
        files.add(f); contents[f.id] = content; return f
    }
    override suspend fun saveBytes(category: ContentCategory, title: String, bytes: ByteArray, mime: String, extension: String, source: String?): StoredFile {
        val f = StoredFile("f${files.size + 1}", "${safe(title)}.$extension", category, "mem://x", mime, bytes.size.toLong(), clock.instant(), StorageLocation.APP_PRIVATE, source)
        files.add(f); return f
    }
    override suspend fun list(category: ContentCategory?, query: String?) =
        files.filter { (category == null || it.category == category) && (query.isNullOrBlank() || it.name.contains(query, true)) }
    override suspend fun readText(file: StoredFile, maxChars: Int) = contents[file.id].orEmpty().take(maxChars)
    override suspend fun deletePermanently(file: StoredFile) = files.remove(file)
}

class InMemoryMemory : ConversationMemory {
    val turns = ArrayList<ChatTurn>()
    override suspend fun recent(n: Int) = turns.takeLast(n).toList()
    override suspend fun remember(role: String, content: String) { turns.add(ChatTurn(role, content)) }
}

class FakeWeb(var hits: List<SearchHit> = emptyList()) : WebSearch {
    val queries = ArrayList<String>()
    override suspend fun search(query: String, n: Int): List<SearchHit> { queries.add(query); return hits.take(n) }
}

class FakeWeather(var result: WeatherResult = WeatherResult(WeatherInfo("Mumbai", 31.0, 35.0, "Haze", 70, 12.0))) : WeatherProvider {
    override suspend fun current(city: String?) = result
}

class FakeImages(var availableNow: Pair<Boolean, String> = true to "") : ImageService {
    val started = ArrayList<String>()
    private val flow = MutableSharedFlow<ImageEvent>(extraBufferCapacity = 16)
    override fun available() = availableNow
    override fun start(prompt: String, count: Int?, style: String, aspect: String): String { started.add(prompt); return "job${started.size}" }
    override val events: SharedFlow<ImageEvent> get() = flow
    fun emit(ev: ImageEvent) = flow.tryEmit(ev)
}

class FakeControl : AssistantControl {
    val pages = ArrayList<String>()
    var listening: Boolean? = null
    override fun openPage(page: String): ActionResult { pages.add(page); return ActionResult.ok("Opened $page.") }
    override fun setBackgroundListening(on: Boolean): ActionResult { listening = on; return ActionResult.ok(if (on) "Listening." else "Stopped listening.", verified = true) }
    override fun pages() = listOf("chat", "library", "reminders", "notes", "settings", "permissions", "capabilities", "island", "help")
}

/** One-stop builder: `val env = TestEnv()`; every fake is reachable as a property. Clock: Sunday 25 Oct 2026 10:00 in Asia/Kolkata. */
class TestEnv(zone: ZoneId = ZoneId.of("Asia/Kolkata")) {
    val clock = FixedClock(ZonedDateTime.of(2026, 10, 25, 10, 0, 0, 0, zone).toInstant(), zone)
    val settingsStore = InMemorySettingsStore()
    val settings = Settings(settingsStore)
    val permissions = FakePermissions()
    val reminders = InMemoryReminderStore()
    val scheduler = FakeReminderScheduler()
    val notes = InMemoryNoteStore()
    val timers = FakeTimerService(clock)
    val clockApp = FakeClockApp()
    val apps = FakeAppLauncher()
    val contacts = FakeContacts()
    val calls = FakeCalls()
    val messaging = FakeMessaging()
    val notifications = FakeNotifications()
    val device = FakeDevice()
    val navigator = FakeNavigator()
    val media = FakeMedia()
    val speaker = FakeSpeaker()
    val documents = InMemoryDocuments(clock)
    val memory = InMemoryMemory()
    val web = FakeWeb()
    val weather = FakeWeather()
    val images = FakeImages()
    val control = FakeControl()
    val logLines = ArrayList<String>()
    val log = object : JarvisLog {
        override fun d(tag: String, msg: String) { logLines.add("D/$tag: $msg") }
        override fun i(tag: String, msg: String) { logLines.add("I/$tag: $msg") }
        override fun w(tag: String, msg: String, t: Throwable?) { logLines.add("W/$tag: $msg") }
        override fun e(tag: String, msg: String, t: Throwable?) { logLines.add("E/$tag: $msg") }
    }

    val services: Services = Services(
        settings, clock, log, permissions, reminders, scheduler, notes, timers, clockApp, apps, contacts, calls, messaging, notifications,
        device, navigator, media, speaker, documents, memory, web, weather, images, control,
    )

    fun instantOf(y: Int, mo: Int, d: Int, h: Int, mi: Int): Instant = ZonedDateTime.of(y, mo, d, h, mi, 0, 0, clock.zone()).toInstant()
}
