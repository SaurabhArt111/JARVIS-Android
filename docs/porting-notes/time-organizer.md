# Porting notes: time parser, organizer, notes/reminders, greeting

Python source → Kotlin (`:core`, pure JVM, no Android types):

| Python | Kotlin |
|---|---|
| `Backend/TimeParse.py`, time helpers of `Tools.py` | `core/time/TimeParser.kt` (`Parsed`, `parse`, `parseDurationSeconds`, `nice`, `parseWhen`, `parseIso`, `parseDay`, `parseClock`) |
| `Backend/Util.py` (wake-word stripping) | `core/util/TextUtil.kt` (`stripWake`) |
| `Backend/Notes.py` | `core/organizer/NoteService.kt` over the `NoteStore` port |
| reminder half of `Backend/Scheduler.py` | `core/organizer/ReminderService.kt` over `ReminderStore` + `ReminderScheduler` ports |
| `Backend/Organizer.py` | `core/organizer/Organizer.kt` (all 9 handlers, same order and regexes) |
| `Backend/Greeting.py` | `core/greeting/Greeting.kt` |

## Behaviour that is kept
* Parser rules: nearest-ahead AM/PM, day word → sensible AM/PM, `pastToday`, date-only → ask for the time, relative durations.
  **Verified against the original:** `TimeParserGoldenTest` replays 129 rows (43 phrases × 3 "now" values) produced by running the Python `TimeParse.parse`.
* Organizer: ask-and-wait follow-ups through the shared `Dialog`, "it/that/again" resolution, ordinal and by-name selection, "which one?" disambiguation,
  "no match → say so, never guess another item", a note with a time also becomes a reminder, nothing is ever deleted (cancel/done are flags, edits keep `history`).

## Deliberate differences
1. **Honest scheduling.** `ReminderService.add` schedules first, checks `ReminderScheduler.isScheduled`, inserts, then reads the row back. Any failure
   leaves nothing behind and returns `AddResult.error`; the Organizer then says "Nothing was created". An inexact alarm (no exact-alarm permission) is
   created, and the reply says it may be late and carries a `GRANT EXACT_ALARM` action. The Python always claimed success.
2. **Reminder states** `PENDING/FIRED/DONE/CANCELLED` replace the Python `done`/`cancelled` booleans. `onFired` is idempotent (a duplicate or stale alarm is silent); an alarm
   that arrives >2 min early is re-registered instead of delivered.
3. **Time zones.** Reminders store wall-clock `LocalDateTime` + `zoneId` + `floating`. `rescheduleAllPending()` (boot / zone / time change / update) re-registers all pending
   reminders and returns a `RescheduleReport` (not `Unit`): `overdue` ones are still PENDING for the caller to deliver, `failed` ones stay PENDING.
   Relative times use `ZonedDateTime` overloads, so "in 60 minutes" across a DST gap is 60 real minutes.
4. **Bug fixed in the port:** Python said "9:00 AM tomorrow has already passed today" (it passed `now - 1 day` to `nice`). The port says "9:00 AM has already passed today."
5. Replies quoting notes/reminders are `sensitive = true` (lock-screen policy hides them). Multi-line `history` entries are plain strings:
   reminders `"<changed>|<old due>|<old text>"`, notes `"<changed>|<old text>"`.
6. `Greeting` takes an optional `Random` (tests are deterministic); `startupMessage` filters to today's PENDING reminders itself.
7. API additions beyond the contract: `Parsed.ok` (computed), `ZonedDateTime` overloads, `ReminderService.rescheduleChecked`, `AddResult.ok/inexact`,
   `Organizer(services, reminders, notes)` (share the container's services), `Greeting.period/address/lines`.
8. An out-of-range ordinal ("the third reminder" with two) now says "You don't have that many…" instead of the Python's "You don't have any upcoming reminders".

## Verification (JVM only)
119 JUnit tests in this stream (`time`, `organizer`, `greeting`, `util`): all pass under `tools/offline-verify/build_core.sh` (Kotlin 2.0.21, coroutines 1.6.4 locally).
Not verified: real AlarmManager delivery, Doze behaviour, boot rescheduling on a device — those belong to the platform adapters.
