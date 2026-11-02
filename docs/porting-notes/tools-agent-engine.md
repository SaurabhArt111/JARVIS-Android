# Porting notes: tools, agent loop, engine (`:core`)

Python source → Kotlin, what changed on purpose, and what the tests cover.

## Map

| Python (desktop) | Kotlin (`core/src/main/kotlin/com/jarvis/core/…`) |
|---|---|
| `Backend/Tools.py` (tool schemas + implementations) | `tools/impl/*` (20 tools, one `Tool` each; `ToolRegistry.default`) |
| `Backend/Skills.py` (offline command parser, calculator, weather, time) | `offline/OfflineParser.kt`, `tools/impl/Calculator.kt` |
| `Backend/Agent.py` (agent loop, `garbled`) | `agent/AgentLoop.kt`, `agent/Honesty.kt` |
| `Backend/Context.py` (`claims_action`, world block) | `agent/Honesty.kt`, `agent/WorldContext.kt` |
| `Backend/Chatbot.py` (system prompts, history) | `agent/Prompts.kt`, `ports/Conversations.kt` (storage is an Android adapter) |
| `Backend/Engine.py` | `engine/DefaultAssistantEngine.kt`, `engine/Dialog.kt` |
| `Backend/Wake.py` (text part) | `wake/WakeText.kt` (audio part: `:app`, see voice stream) |
| – (new) | `lock/LockPolicy.kt`, `routing/CapabilityRouter.kt`, `agent/ToolExecutor.kt` |

## Desktop tools that do not exist on Android (and what replaced them)

* `window` (snap / move / list windows), `screenshot` to a PC folder, `close app`, `shutdown` etc.: Android has no window manager API for apps. Replaced by `navigate`
  (home / back / recents / notification shade / quick settings / lock / screenshot) which needs the **minimal accessibility service** and says so when it is off.
  Closing *other* apps is not possible and the tool says that instead of pretending.
* `system` on PC (volume, brightness, mute, specs, clipboard, battery, network) became the Android equivalents; `brightness` needs *Modify system settings*, DND needs
  *notification-policy access*. Every change is **read back** from the system and reported as verified or not.
* `play`: Spotify / YouTube Music search by deep link, verified only if a media session reports PLAYING (needs notification-listener access). Otherwise the reply says
  "I asked Spotify …" and `verified=false`.
* `message`: WhatsApp / Telegram / SMS are **prepared, never sent** ("tap send"), because Android gives no way to send for another app without accessibility
  automation; the optional quick reply uses the notification's own RemoteInput.
* New: `call`, `read_notifications`, `reply_notification`, `alarm` (Clock app), `timer`, `jarvis` (assistant's own settings/pages), `save_document`, `image`.

## Deliberate behavioural differences

1. **One execution path.** The Python offline parser ran skills directly; here the parser only *plans* `Invoke(tool, args)` steps and the engine runs them through the same
   `ToolExecutor` as the AI agent, so lock policy, permission checks, honest outcomes and logging are identical online and offline.
2. **Outcome truthfulness.** `Outcome(ok, verified)`: a tool that cannot confirm its effect returns `verified=false` and its text contains a caveat. `ResultValidator`
   rewrites an AI answer that contradicts the tool results: all tools failed → the failure text replaces any confident answer; unverified action → the caveat is added
   or replaces "Message sent!" wording; partial failure → the failure is appended.
3. **Honesty guard negation.** `claimsAction("Nothing was opened.")` is false (denials are not claims; found by a test). Completion words ("sent", "done", "delivered")
   are additionally checked by the validator when a tool result was unverified.
4. **Safety.** Deletion is never offered to the AI or the voice path. A "cancel reminder" tool only *flags* the reminder as CANCELLED; the data stays. `Safety.isDeleteRequest`
   is checked before anything reaches an AI.
5. **Lock screen.** New `LockPolicy` (ALLOW / CONFIRM / REQUIRE_UNLOCK / DENY) per tool class and privacy level; replies marked `sensitive` are redacted for display *and* speech
   on a locked secure device. See `docs/LOCK_SCREEN.md`.
6. **AI privacy toggle.** `AIShareContext` (default on): when off, or when locked, the model gets counts but not reminder/note/notification text.
7. **Wake text.** Two deviations from `Wake.py`: wake words ending in a symbol now match (`(?!\w)` instead of `\b`), and "what time is it" is no longer treated as a cut-off
   sentence just because it ends with "it".
8. **Reminder delivery.** `deliverReminder` ignores alarms for reminders that are early, cancelled or already delivered (a receiver may already have marked it FIRED; only a
   very recent FIRED is announced). Found by a test.
9. **Compound commands.** "open spotify and explain X" no longer sends "spotify and explain x" to `open`: when the first part is a real app and the rest is a sentence, the
   rest is reported as not understood.

## Verified by tests (JVM, `tools/offline-verify/build_core.sh`)

`ToolsTest` (≈90 tool-outcome tests incl. read-back, missing permission, ambiguity questions, never "sent"), `CalculatorTest`, `HonestyTest`, `LockPolicyTest` (matrix of
class × privacy × secure/swipe × settings), `CapabilityRouterTest`, `OfflineParserTest`, `AgentLoopTest` (tool→answer, forced tool, false-claim nudge, garbled output,
duplicate calls, max steps, LLM failure, validator), `EngineTest` (speech policy, duplicate suppression, blocked deletes, dialog resume, offline fallback, cancel,
lock redaction, reminder/timer delivery, greeting, image events), `WakeTextTest`.

## Not verified here

Nothing in `:core` touches Android. Everything that does (real media sessions, contacts, telecom, notification replies, torch, brightness) is covered only by the
fake-backed tests plus offline compilation of the adapters against `android.jar` – **not** by running on a phone.
