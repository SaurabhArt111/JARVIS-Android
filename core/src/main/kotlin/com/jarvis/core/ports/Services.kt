package com.jarvis.core.ports

import com.jarvis.core.settings.Settings
import com.jarvis.core.util.JarvisClock
import com.jarvis.core.util.JarvisLog
import com.jarvis.core.util.SystemClock

// ---- network-backed services. Implemented in core (over HttpClient); declared here so tools can be tested with fakes.
data class SearchHit(val title: String, val snippet: String, val url: String)

interface WebSearch {
    /** Empty list = offline / blocked / nothing found. */
    suspend fun search(query: String, n: Int = 5): List<SearchHit>
}

data class WeatherInfo(val city: String, val tempC: Double, val feelsC: Double, val description: String, val humidity: Int, val windKmh: Double)
data class WeatherResult(val info: WeatherInfo?, val error: String? = null)

interface WeatherProvider {
    suspend fun current(city: String?): WeatherResult
}

enum class ImageEventType { START, STAGE, DONE, ERROR }

data class ImageEvent(
    val type: ImageEventType,
    val jobId: String,
    val prompt: String = "",
    val message: String = "",
    /** Saved files for DONE. */
    val files: List<StoredFile> = emptyList(),
)

interface ImageService {
    /** (available, why-not). Unavailable e.g. when no provider key is configured and the free fallback is unreachable. */
    fun available(): Pair<Boolean, String>
    /** Starts a background job and returns its id immediately; progress and results arrive through [events]. */
    fun start(prompt: String, count: Int?, style: String, aspect: String): String
    val events: kotlinx.coroutines.flow.SharedFlow<ImageEvent>
}

/**
 * Everything the core can reach. The app builds one of these in its AppContainer; tests build one from fakes.
 * Optional capabilities are nullable only where Android may genuinely not offer them; most are always present and
 * report `missing` requirements through [ActionResult].
 */
class Services(
    val settings: Settings,
    val clock: JarvisClock = SystemClock,
    val log: JarvisLog = JarvisLog.None,
    val permissions: PermissionChecker,
    val reminders: ReminderStore,
    val reminderScheduler: ReminderScheduler,
    val notes: NoteStore,
    val timers: TimerService,
    val clockApp: ClockApp,
    val apps: AppLauncher,
    val contacts: ContactsGateway,
    val calls: CallGateway,
    val messaging: MessagingGateway,
    val notifications: NotificationGateway,
    val device: DeviceControl,
    val navigator: SystemNavigator,
    val media: MediaGateway,
    val speaker: Speaker,
    val documents: DocumentStore,
    val memory: ConversationMemory,
    val web: WebSearch,
    val weather: WeatherProvider,
    val images: ImageService,
    val control: AssistantControl = AssistantControl.Unavailable,
    /** Optional AI second opinion for misheard contact names (see [ContactNameChecker]); null = not available. */
    val contactChecker: ContactNameChecker? = null,
)
