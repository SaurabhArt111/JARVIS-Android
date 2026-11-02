# JARVIS Android – architecture & work contract

> This was the contract the code was built against (package roots, ports, rules). It is kept because it still describes the structure accurately. For what works and what is unverified, read `FEATURE_STATUS.md` and the README first.

Rebuild of the Python JARVIS (the original desktop project, used as a read-only reference) as a native Android app.
Package roots: `com.jarvis.core` (pure Kotlin/JVM, module `:core`) and `com.jarvis.assistant` (Android, module `:app`).

## Hard rules
1. **No AndroidX / Compose / Material / Hilt / Room / WorkManager.** `:app` uses only the Android framework + Kotlin stdlib +
   kotlinx-coroutines (+ onnxruntime for the optional wake-word engine). UI is built in Kotlin (programmatic Views), lists use `ListView`,
   persistence uses `SQLiteOpenHelper`, scheduling uses `AlarmManager`, DI is a hand-written `AppContainer`. Reason: everything can be
   compile-checked offline against the real `android.jar` (see "Verification").
2. **`:core` has no Android imports.** It depends on kotlin-stdlib + kotlinx-coroutines-core only. Device access goes through the *ports*
   in `core/ports/*` (interfaces already written: read `Ports.kt`, `Services.kt`). `:app` implements them.
3. **Truthful results.** A tool/port returns `ok=true` only if the Android API/DB says so; use `verified=true` only when state was re-read
   (row read back, `PendingIntent` exists, volume re-read, intent resolved). Never produce text claiming something happened that did not.
4. **No deletion by AI/voice** (`Safety`). Permanent deletes (`deletePermanently`) exist only for explicit confirmed UI actions.
5. **No secrets in source.** API keys live in the Keystore-backed secret store (`SettingsStore.secret`). Never log keys, bearer tokens,
   request bodies, or private message text (log lengths/ids instead).
6. **API 29+ compatible.** minSdk 29, compileSdk/targetSdk 36. Guard newer APIs with `Build.VERSION.SDK_INT` checks.
7. Don't edit another stream's files. Shared contract files (`core/model`, `core/ports`, `core/tools/Tool.kt`, `core/ai/LlmContract.kt`,
   `core/engine/Engine.kt`, `core/settings/Settings.kt`) may only get **additive** changes (new members with defaults / new files); report
   them in your final message. Edit `AndroidManifest.xml` only between the `<!-- X-BEGIN -->` / `<!-- X-END -->` markers.
8. Keep user-visible strings in `res/values/strings.xml` for UI text (prefix by area, e.g. `perm_`, `lock_`, `island_`). Core replies
   (spoken/tool text) stay in Kotlin as in the Python version.

## Verification (what every stream must run before finishing)
* Core: `tools/offline-verify/build_core.sh` – compiles `core/src/main` + `core/src/test`, runs all JUnit tests (JUnit 4, `kotlin.test`
  is NOT available; use `org.junit.Assert`). Tests use fakes in `core/src/test/.../testing/Fakes.kt` (`TestEnv()`), `runBlocking` for
  suspend code. Must be green before you finish.
* App: `tools/offline-verify/build_app.sh` – compiles core + app against `android.jar` (API 36) with a stub `R`, then checks resources and
  manifest (`check_res.py`). Must be green before you finish. (Compiles are serialized by a lock; don't run them in tight loops.)
* A real Gradle/AGP build is **not possible in this sandbox** (Google Maven/Maven Central are blocked). Do not claim "builds with Gradle".
  The android.jar used is AOSP-derived and also exposes hidden APIs: **use only public SDK APIs** (check the SDK docs you remember;
  anything with `@hide`/`@SystemApi` is forbidden even if it compiles).
* kotlinx-coroutines locally is 1.6.4 (Gradle declares 1.9.0): use only APIs present in both (`StateFlow`, `SharedFlow`, `flow {}`,
  `callbackFlow`, `withTimeout`, `suspendCancellableCoroutine`, `MutableStateFlow.update`, `Dispatchers.Main/IO/Default`).

## Core API surface other streams code against (implemented by the stream named in brackets)

### [time-organizer] `com.jarvis.core.time`, `com.jarvis.core.organizer`, `com.jarvis.core.greeting`
```kotlin
// core/time
data class Parsed(val ok: Boolean, val whenAt: LocalDateTime?, val rest: String, val hasTime: Boolean, val dateOnly: Boolean, /* + whatever the port needs */)
object TimeParser {
    fun parse(text: String, now: LocalDateTime): Parsed          // port of TimeParse.parse
    fun parseDurationSeconds(text: String): Int?                 // port of parse_duration ("an hour and a half" -> 5400)
    fun nice(whenAt: LocalDateTime, now: LocalDateTime): String  // "today at 5:00 PM", "tomorrow at 7:00 AM", ...
    /** Tools.parse_when: ISO ('2026-10-16T15:30') or natural language. Returns (time, null) or (null, problemSentence). */
    fun parseWhen(s: String, now: LocalDateTime): Pair<LocalDateTime?, String?>
}
// core/organizer
class ReminderService(services: Services) {
    suspend fun add(text: String, due: LocalDateTime, isAlarm: Boolean = false, noteId: String? = null): AddResult   // stores, schedules, READS BACK, reports exact/inexact/failed
    suspend fun reschedule(id: String, due: LocalDateTime): Reminder?
    suspend fun setText(id: String, text: String): Reminder?
    suspend fun markDone(id: String): Reminder?; suspend fun cancel(id: String): Reminder?   // flag only
    suspend fun get(id: String): Reminder?; suspend fun all(): List<Reminder>
    suspend fun upcoming(): List<Reminder>; suspend fun recentDone(n: Int): List<Reminder>
    suspend fun onFired(id: String): Reminder?                    // called by the alarm receiver: PENDING -> FIRED
    suspend fun rescheduleAllPending(): RescheduleReport            // after boot / zone / time change / update; report.overdue = still-PENDING reminders the CALLER must deliver now; report.failed stay PENDING
    suspend fun rescheduleChecked(id: String, due: LocalDateTime): AddResult
}
data class AddResult(val reminder: Reminder?, val schedule: ScheduleResult?, val error: String?)  // error != null => nothing was created
class NoteService(services: Services) { suspend fun add(text: String, reminderId: String? = null): Note; edit; append; get; latest(n); find(q); all(); linkReminder }
class Organizer(services: Services, reminders: ReminderService = ReminderService(services), notes: NoteService = NoteService(services)) {            // port of Organizer.py (multi-turn NL handling for notes + reminders)
    fun related(text: String): Boolean
    suspend fun handle(raw: String, ctx: ToolContext): AssistantReply?   // null = not about notes/reminders
}
object Greeting { fun startupMessage(upcomingToday: List<Reminder>, now: LocalDateTime, userName: String): String; fun replyTo(text: String, now: LocalDateTime, userName: String): String?; fun icon(now: LocalDateTime): String }
```

### [ai-gateway] `com.jarvis.core.net`, `com.jarvis.core.ai`, `com.jarvis.core.search`, `com.jarvis.core.weather`, `com.jarvis.core.image`
```kotlin
class JdkHttpClient : HttpClient                                              // HttpURLConnection, cancellable, HTTPS-only unless loopback
class DefaultLlmGateway(settings: Settings, http: HttpClient, clock: JarvisClock, log: JarvisLog) : LlmGateway
class ProviderHealth                                                          // counters + cooldown, exposed for the Settings screen
fun DefaultLlmGateway.health(): List<ProviderHealthSnapshot>; suspend fun DefaultLlmGateway.diagnose(only: String? = null): List<ProviderDiagnosis>
class DefaultWebSearch(http: HttpClient, settings: Settings, log: JarvisLog) : WebSearch   // SerpAPI if key, else DuckDuckGo HTML
class OpenMeteoWeather(http: HttpClient, settings: Settings, log: JarvisLog) : WeatherProvider
class DefaultImageService(http: HttpClient, settings: Settings, documents: DocumentStore, scope: CoroutineScope, clock: JarvisClock, log: JarvisLog) : ImageService
```

### [tools-agent-engine] `com.jarvis.core.tools.impl`, `core.agent`, `core.routing`, `core.engine`, `core.lock`
```kotlin
class DefaultAssistantEngine(services: Services, llm: LlmGateway, scope: CoroutineScope, registry: ToolRegistry = ToolRegistry.default(services)) : AssistantEngine
class ToolRegistry { companion object { fun default(services: Services): ToolRegistry }; fun specs(): List<ToolSpec>; fun get(name: String): Tool? }
class CapabilityRouter(services: Services, registry: ToolRegistry) { fun status(lock: LockState): List<CapabilityStatus>; fun classify(text: String): List<Capability>; fun toolsFor(lock: LockState, online: Boolean): List<ToolSpec> }
data class CapabilityStatus(val capability: Capability, val available: Boolean, val offlineCapable: Boolean, val missing: List<Requirement>, val note: String)
class LockPolicy(settings: Settings) { fun decide(spec: ToolSpec, args: Args, lock: LockState, origin: Origin): LockDecision; fun maySpeakDetails(reply: AssistantReply, lock: LockState): Boolean; fun redact(reply: AssistantReply, lock: LockState): AssistantReply }
fun AssistantEngine.onReminderFired(...) // reminder/timer delivery is handled by DefaultAssistantEngine.deliverReminder(id): speaks (policy aware) + emits ReminderFired
```
(`DefaultAssistantEngine` additionally exposes `suspend fun deliverReminder(id: String, isTimer: Boolean = false)`.)

### [android-data-platform] `com.jarvis.assistant.{di,data,platform}`
`AppContainer` (object graph; built in `JarvisApp.onCreate`, accessible as `JarvisApp.container`), implements every port in `Services`
(+ `SettingsStore`, `PermissionChecker`), plus the things UI/services need: `conversations: ConversationRepository`, `files: DocumentStore`,
`reminderRepo`, `notificationHelper`, `permissionHelper` (intents to grant each `Requirement`), `appLog` (redacting file logger),
`engine: AssistantEngine`, `llm: DefaultLlmGateway`. Receivers: reminder alarm, boot/time-zone/time-set/package-replaced rescheduling.

### [voice-service] `core.wake`, `com.jarvis.assistant.{voice,service}`
`VoiceInput` (SpeechRecognizer wrapper: `listenOnce()` -> `Flow<VoiceEvent>`), `AssistantForegroundService` (mic FGS, wake-word loop,
start/stop/resume actions, notification), wake engines (`WakeWordEngine` interface; `OpenWakeWordEngine` ONNX; `SpeechWakeEngine`), model downloader.

### [surfaces] `core.island`, `core.lock` (data only; policy in tools-agent-engine), `com.jarvis.assistant.{island,lock,access,assist}`
Island overlay service + view + cutout detection, lock-screen activity, notification listener, accessibility service, voice-interaction
service/session/recognition stub, Quick Settings tile.

### [ui] `com.jarvis.assistant.ui.*`
Theme/toolkit, `MainActivity` (Chat, Library, Reminders & Notes, Settings, Permissions, Onboarding), `AssistantActivity` (long-press Home), ViewModels.

## Original Python → Android map (what is ported, replaced, or dropped)
See `docs/PORTING_MAP.md` (maintained by the lead; streams append their rows to `docs/porting-notes/<stream>.md`).

## AppContainer – exact surface (UI and service streams may rely on it from the start)
```kotlin
// com.jarvis.assistant.JarvisApp   (Application)   companion: lateinit var container: AppContainer
// com.jarvis.assistant.di.AppContainer(val app: Application)
val settings: Settings;  val settingsStore: SettingsStore
val services: Services                       // every port wired to its Android adapter
val engine: AssistantEngine                  // DefaultAssistantEngine
val llm: DefaultLlmGateway
val conversations: ConversationRepository    // SQLite
val files: DocumentStore                     // == services.documents
val reminderService: ReminderService;  val noteService: NoteService
val permissions: PermissionHelper
val appLog: AppLog                           // redacting logger; AppLog.readTail(maxBytes): String for the diagnostics screen
val scope: CoroutineScope                    // app-wide, SupervisorJob + Dispatchers.Default + CoroutineExceptionHandler -> appLog
val lockPolicy: LockPolicy
val capabilityRouter: CapabilityRouter

// com.jarvis.assistant.platform.PermissionHelper(context)
fun state(req: Requirement): PermState
fun title(req: Requirement): String;  fun rationale(req: Requirement): String        // user-facing explanation (strings.xml)
fun runtimePermissions(req: Requirement): Array<String>                              // for requestPermissions (empty when it is a special access)
fun settingsIntent(req: Requirement): Intent?                                        // the system screen that grants it (special access / role / settings)
fun allRequirements(): List<Requirement>                                            // the ones shown on the Permissions screen
```
Service/UI entry points other streams must keep stable:
* `com.jarvis.assistant.service.AssistantForegroundService.start(context)/stop(context)/isRunning()` (voice-service)
* `com.jarvis.assistant.island.IslandService.start(context)/stop(context)/isRunning()` (surfaces)
* `com.jarvis.assistant.ui.MainActivity` (launcher), `com.jarvis.assistant.ui.AssistantActivity` (assist gesture + lock screen host: `LockScreenActivity` in surfaces)


## Status log
* `:core` is complete and verified on the JVM: **699 tests** pass (`tools/offline-verify/build_core.sh`): json, model/ports contracts, time + organizer (incl. a golden replay of the Python time parser),
  AI gateway (4 providers, fallback, cooldown), search, weather, images, storage names, app matcher, the 20 tools, tool executor, agent loop, honesty guards, lock policy and lock-screen logic,
  notification rules, capability router, offline parser, engine, island geometry and state machine, and the openWakeWord pipeline (golden test against the reference implementation).
* `:app` is complete and **compiles against the real `android.jar` (API 36)**; its resources and manifest are statically cross-checked; its SQL runs on real SQLite; its instrumented tests type-check.
* Honest limits of the verification environment: no Gradle/AGP build (Google Maven / Maven Central were unreachable) and no emulator or device. Behaviour on a device is **unverified**.
  See `FEATURE_STATUS.md` for the per-feature status. The build was organised into work areas ("streams": time-organizer, tools-agent-engine, ai-gateway, data-platform, ui, voice-service, surfaces); the briefs used for that are not part of this package.
