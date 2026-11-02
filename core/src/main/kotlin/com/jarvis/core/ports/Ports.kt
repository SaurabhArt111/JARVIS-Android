package com.jarvis.core.ports

import com.jarvis.core.model.Requirement
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

// =====================================================================================================================
// Ports = what the platform-independent core needs from the device. The Android app implements them (app/platform/*);
// unit tests use the in-memory fakes in core/src/test. A port method must report the REAL outcome: `ok` reflects what
// the Android API said, `verified` is true only when the adapter re-read the state afterwards.
// =====================================================================================================================

/** Result of a device operation. [missing] lists what the user must grant for it to work. */
data class ActionResult(
    val ok: Boolean,
    val message: String = "",
    val missing: List<Requirement> = emptyList(),
    val verified: Boolean = false,
) {
    companion object {
        fun ok(message: String = "", verified: Boolean = false) = ActionResult(true, message, verified = verified)
        fun fail(message: String, vararg missing: Requirement) = ActionResult(false, message, missing.toList())
    }
}

enum class PermState { GRANTED, DENIED, UNAVAILABLE }

interface PermissionChecker {
    fun state(requirement: Requirement): PermState
    fun isGranted(requirement: Requirement): Boolean = state(requirement) == PermState.GRANTED
}

// ---------------------------------------------------------------------------------------------------- reminders & notes
enum class ReminderState { PENDING, FIRED, DONE, CANCELLED }

/**
 * A reminder (or alarm-style reminder). [due] is wall-clock time in [zoneId]. When [floating] is true the wall-clock time is kept
 * if the device time zone changes (7:00 stays 7:00 local); the scheduler recomputes the absolute trigger time.
 */
data class Reminder(
    val id: String,
    val text: String,
    val due: LocalDateTime,
    val zoneId: String,
    val floating: Boolean = true,
    val state: ReminderState = ReminderState.PENDING,
    val isAlarm: Boolean = false,
    val createdAt: Instant,
    val firedAt: Instant? = null,
    val noteId: String? = null,
    /** Earlier wordings / times, newest last. Nothing the assistant does ever discards these. */
    val history: List<String> = emptyList(),
) {
    fun triggerAt(zone: ZoneId = ZoneId.of(zoneId)): Instant = due.atZone(zone).toInstant()
    val pending: Boolean get() = state == ReminderState.PENDING
}

interface ReminderStore {
    suspend fun insert(reminder: Reminder): Reminder
    suspend fun get(id: String): Reminder?
    /** Every reminder in every state, oldest due first. */
    suspend fun all(): List<Reminder>
    suspend fun update(reminder: Reminder): Reminder?
    /** Permanent removal. Only reachable from an explicit user action in the UI, never from the AI or voice tools. */
    suspend fun deletePermanently(id: String): Boolean
}

enum class ScheduleMode { EXACT, INEXACT, NONE }

data class ScheduleResult(val mode: ScheduleMode, val triggerAtMs: Long, val note: String = "") {
    val scheduled: Boolean get() = mode != ScheduleMode.NONE
}

/** Wakes the app when a reminder is due (AlarmManager on Android). */
interface ReminderScheduler {
    fun schedule(reminder: Reminder): ScheduleResult
    fun cancel(reminderId: String)
    /** Re-reads the platform state: is an alarm registered for this id? Used to verify [schedule]. */
    fun isScheduled(reminderId: String): Boolean
    fun canScheduleExact(): Boolean
}

data class Note(
    val id: String,
    val text: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    val reminderId: String? = null,
    val history: List<String> = emptyList(),
)

interface NoteStore {
    suspend fun insert(note: Note): Note
    suspend fun get(id: String): Note?
    suspend fun all(): List<Note>
    suspend fun update(note: Note): Note?
    suspend fun deletePermanently(id: String): Boolean
}

/** [exact] is false when Android would only allow an inexact alarm (exact-alarm access not granted), so the timer may ring a little late. */
data class TimerInfo(val id: String, val label: String, val seconds: Int, val endsAtMs: Long, val exact: Boolean = true)

interface TimerService {
    fun start(seconds: Int, label: String): TimerInfo?
    fun stopAll(): Int
    fun active(): List<TimerInfo>
}

/** Hands an alarm or timer to the system Clock app (AlarmClock intents). Cannot be read back, so results are never `verified`. */
interface ClockApp {
    fun setAlarm(hour: Int, minute: Int, label: String): ActionResult
    fun setTimer(seconds: Int, label: String): ActionResult
}

// ---------------------------------------------------------------------------------------------------- apps & intents
data class AppMatch(val label: String, val packageName: String, val score: Double = 1.0)

interface AppLauncher {
    /** Installed launchable apps matching [query], best first. Empty = not installed (the assistant must say so). */
    fun resolve(query: String): List<AppMatch>
    fun launch(app: AppMatch): ActionResult
    fun openUrl(url: String, browser: AppMatch? = null): ActionResult
    fun browsers(): List<AppMatch>
    /** [page]: wifi, bluetooth, display, sound, battery, apps, notifications, location, accessibility, date, language, storage, ... */
    fun openSettingsPage(page: String): ActionResult
    fun openFile(uri: String, mime: String?): ActionResult
    fun isInstalled(packageName: String): Boolean
}

// ---------------------------------------------------------------------------------------------------- contacts, calls, messaging
data class ContactNumber(val number: String, val label: String, val primary: Boolean = false)
data class ContactMatch(val id: String, val displayName: String, val numbers: List<ContactNumber>)
data class ContactSearch(val matches: List<ContactMatch>, val missing: List<Requirement> = emptyList(), val error: String? = null)

interface ContactsGateway {
    /** Resolves a spoken name against the device contacts (fuzzy: case, diacritics, nicknames). Never hard-codes people. */
    suspend fun search(name: String, limit: Int = 5): ContactSearch
}

interface CallGateway {
    /** Places the call with ACTION_CALL when CALL_PHONE is granted, otherwise opens the dialer pre-filled (ACTION_DIAL). Says which one happened. */
    fun call(number: String, displayName: String): ActionResult
    fun answerRingingCall(): ActionResult
    fun endCall(): ActionResult
}

enum class MessagingApp { WHATSAPP, TELEGRAM, SMS }

interface MessagingGateway {
    /** Opens a chat with the text pre-filled. The user presses send: no app is driven silently. */
    fun openChat(app: MessagingApp, number: String?, text: String): ActionResult
    fun isAvailable(app: MessagingApp): Boolean
}

// ---------------------------------------------------------------------------------------------------- notifications
enum class NotificationKind { MESSAGE, CALL, EMAIL, SOCIAL, MEDIA, SYSTEM, PROMO, OTHER }

data class NotificationInfo(
    val key: String,
    val packageName: String,
    val appLabel: String,
    val title: String,
    val text: String,
    val postedAtMs: Long,
    val kind: NotificationKind,
    val canReply: Boolean,
    val ongoing: Boolean = false,
    /** Messaging-style: the sender (title) differs from the app. */
    val conversation: String? = null,
)

interface NotificationGateway {
    fun isAccessGranted(): Boolean
    fun active(): List<NotificationInfo>
    /** Sends [text] through the notification's own reply action (RemoteInput). Fails honestly if the app offers none. */
    suspend fun quickReply(key: String, text: String): ActionResult
    fun dismiss(key: String): ActionResult
}

// ---------------------------------------------------------------------------------------------------- device control
data class BatteryInfo(val percent: Int, val charging: Boolean, val temperatureC: Double?)
data class NetworkInfo(val connected: Boolean, val type: String, val metered: Boolean)
data class DeviceReport(val lines: List<Pair<String, String>>)

interface DeviceControl {
    fun volume(): Int?
    fun setVolume(percent: Int): ActionResult
    fun changeVolume(deltaPercent: Int): ActionResult
    fun isMuted(): Boolean
    fun setMuted(muted: Boolean): ActionResult
    fun brightness(): Int?
    fun setBrightness(percent: Int): ActionResult
    fun setFlashlight(on: Boolean): ActionResult
    fun setDoNotDisturb(on: Boolean): ActionResult
    fun battery(): BatteryInfo
    fun network(): NetworkInfo
    fun report(): DeviceReport
    /** Clipboard text, or null when empty/unreadable (Android only allows this while JARVIS is in the foreground). */
    fun clipboardText(): String?
}

enum class GlobalAction { HOME, BACK, RECENTS, NOTIFICATIONS, QUICK_SETTINGS, LOCK_SCREEN, SCREENSHOT, POWER_DIALOG }

/** System navigation. Android offers this only through an enabled Accessibility Service; otherwise [ActionResult.missing] has ACCESSIBILITY. */
interface SystemNavigator {
    suspend fun perform(action: GlobalAction): ActionResult
}

// ---------------------------------------------------------------------------------------------------- media
enum class PlayState { PLAYING, PAUSED, STOPPED, BUFFERING, UNKNOWN }
data class TrackInfo(
    val title: String,
    val artist: String,
    val album: String,
    val appLabel: String,
    val packageName: String,
    val state: PlayState,
    val positionMs: Long = -1,
    val durationMs: Long = -1,
)

enum class MediaAction { PLAY, PAUSE, TOGGLE, NEXT, PREVIOUS, STOP }

interface MediaGateway {
    /** Current media session metadata, or null. Needs notification-listener access to read other apps' sessions. */
    fun nowPlaying(): TrackInfo?
    fun control(action: MediaAction): ActionResult
    /** Asks a media app to play [query] (voice-search intent) or, failing that, opens a search for it. */
    fun playFromSearch(query: String, app: AppMatch?): ActionResult
}

// ---------------------------------------------------------------------------------------------------- speech out
enum class SpeechChannel { REPLY, REMINDER, GREETING, SYSTEM }

interface Speaker {
    /** Speaks and suspends until finished (or stopped). Returns false if speech failed. Ducks other audio while speaking. */
    suspend fun speak(text: String, channel: SpeechChannel = SpeechChannel.REPLY): Boolean
    fun stop()
    fun isSpeaking(): Boolean
}

// ---------------------------------------------------------------------------------------------------- storage
enum class ContentCategory(val folder: String) {
    CONVERSATIONS("Conversations"), NOTES("Notes"), GENERATED_TEXT("Generated"), IMAGES("Images"),
    AUDIO("Audio"), EXPORTS("Exports"), OTHER("Other")
}

enum class StorageLocation { PUBLIC_DOCUMENTS, APP_PRIVATE }

data class StoredFile(
    val id: String,
    val name: String,
    val category: ContentCategory,
    /** content:// URI (public Documents) or file path (private). Opaque to the core. */
    val uri: String,
    val mime: String,
    val sizeBytes: Long,
    val createdAt: Instant,
    val location: StorageLocation,
    /** The request or prompt that produced it, when known. */
    val source: String? = null,
)

interface DocumentStore {
    /** Writes a new file with a safe, unique name (never overwrites). Falls back to private storage if public storage is unavailable. */
    suspend fun saveText(category: ContentCategory, title: String, content: String, extension: String = "txt", source: String? = null): StoredFile
    suspend fun saveBytes(category: ContentCategory, title: String, bytes: ByteArray, mime: String, extension: String, source: String? = null): StoredFile
    suspend fun list(category: ContentCategory? = null, query: String? = null): List<StoredFile>
    suspend fun readText(file: StoredFile, maxChars: Int = 200_000): String
    /** Permanent removal. Only reachable from an explicit user action in the UI. */
    suspend fun deletePermanently(file: StoredFile): Boolean
}

// ---------------------------------------------------------------------------------------------------- conversation memory
data class ChatTurn(val role: String, val content: String)

interface ConversationMemory {
    /** The last [n] turns, safe to send to any provider (role + content only). */
    suspend fun recent(n: Int): List<ChatTurn>
    suspend fun remember(role: String, content: String)
    suspend fun rememberTurn(user: String, assistant: String) {
        if (user.isNotBlank() && assistant.isNotBlank()) {
            remember("user", user)
            remember("assistant", assistant)
        }
    }
}

// ---------------------------------------------------------------------------------------------------- JARVIS itself
/** What the "jarvis" tool can ask of the app itself (navigate to a screen, switch the listening pipeline). Implemented in `:app`. */
interface AssistantControl {
    /** Opens one of the app's screens: chat, library, reminders, notes, settings, permissions, capabilities, island, help. */
    fun openPage(page: String): ActionResult
    /** Starts / stops background listening for the wake word (the foreground service). */
    fun setBackgroundListening(on: Boolean): ActionResult
    fun pages(): List<String>

    object Unavailable : AssistantControl {
        override fun openPage(page: String) = ActionResult.fail("The app screen isn't reachable from here.")
        override fun setBackgroundListening(on: Boolean) = ActionResult.fail("Background listening can't be changed from here.")
        override fun pages(): List<String> = emptyList()
    }
}
