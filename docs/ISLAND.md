# The Dynamic-Island-style overlay

A small pill drawn at the top of the screen that shows what JARVIS is doing: listening (waveform), thinking, speaking, the latest answer, a reminder or timer notice, an error.
It is **opt-in** (Settings > Dynamic Island) and needs Android's **"Display over other apps"** permission (`SYSTEM_ALERT_WINDOW`).

## What it will and will not claim

Android does **not** tell an app where the physical camera is. The island uses only what the window system reports about the *display cutout*
(`DisplayCutout.getBoundingRects()` and safe insets). On a phone with a punch-hole or notch it places itself around/beside the cutout rectangle Android reports;
with no usable cutout information it goes to a configurable top-centre position below the status bar. JARVIS never says it "detected the camera".

## How it works

| Piece | File | Role |
|---|---|---|
| Placement maths | `core/island/IslandGeometry.kt` | Pure Kotlin. Inputs: window size, density, rotation, status-bar height, safe insets, cutout rectangles, the user's position/offset/size, compact/expanded sizes. Output: `IslandPlacement` (x, y, size, anchor, source = CUTOUT/SETTING/FALLBACK, a touch window of at least 48 dp, and `keepOut` rectangles where nothing may be drawn). |
| State machine | `core/island/IslandModel.kt` (`IslandReducer`) | Pure Kotlin. Engine states, engine events, wake-service cues, taps and timeouts in; `IslandUiState` out (`HIDDEN, LISTENING, THINKING, SPEAKING, REPLY, NOTICE, ERROR`). Replies and notices collapse by themselves; with nothing to show the overlay window is **removed** so it can never block touches. |
| Cutout reader | `island/CutoutDetector.kt` | API 30+: `WindowManager.getCurrentWindowMetrics()` of an overlay window context (follows folding) and its `WindowInsets`; API 31+: `Display.getCutout()` as a fallback; API 29: real display metrics and the root insets of an attached view. Re-read on configuration, insets and display changes (rotation, fold). |
| Drawing | `island/IslandView.kt` | One Canvas-drawn view: compact pill, waveform from the microphone level, thinking dots, speaking ripple, expanded card with the last reply and Talk / Type / Close / Move buttons, spring-like animations (they respect Android's animation-scale setting). Content descriptions follow the state (polite live region). |
| Window + glue | `island/IslandController.kt` | Adds the view with `TYPE_APPLICATION_OVERLAY`, `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL | FLAG_LAYOUT_*`, sized to the **pill only** (so the status bar, notification shade and system gestures stay fully usable). Long-press or the Move button starts a drag; the position is saved (`IslandOffsetX/Y`). |
| Lifetime | `island/IslandService.kt`, `platform/IslandSwitch.kt` | A foreground service of type `specialUse` with a minimal-importance, lock-screen-secret notification that has a "Turn off" button. Restarted after boot and app update by `SystemEventsReceiver`. |

### Placement rules (each has a JVM test)
* A cutout in the top band decides the anchor. **Centred** -> the island wraps it and keeps its content off the cutout; **left/right** -> the island sits beside it, toward the middle.
* Several cutouts: the one nearest the top edge wins (the wider on a tie). Cutouts on the side edges (landscape) only push the island away.
* The user's choice (Auto / Left / Centre / Right, offsets in dp, size 60-160 %) overrides the automatic one, but never moves it off screen or into a cutout it could avoid.
* No cutout, bad data (empty, off-screen or absurdly large rectangles), zero-size or non-finite inputs -> top-centre fallback, no crash.
* Landscape (both rotations), tablets, foldable inner displays (uses the *current* window bounds), waterfall insets, a tiny screen, an expanded card that would run off the edge (shifted back inside).

## Without the permission

`IslandService` does not pretend. It posts a notification ("Island needs *Display over other apps*") that opens the permission screen, and stops. The in-app compact chat
(`AssistantActivity`: long-press Home, the Quick Settings tile, or the island's own Talk/Type buttons) is the fallback surface.

## Google Play

A foreground service of type `specialUse` must be declared in Play Console with the subtype text in the manifest `<property>`. That text says what it is: "keeps the status overlay
alive while the user has switched it on". Be ready to explain it to reviewers.

## Tested vs not tested

| Verified (JVM, `core/src/test/.../island`) | Not verified (needs a device) |
|---|---|
| 28 geometry tests incl. 15+ display configurations and a 3000-case randomised invariant check (inside the screen, >= 48 dp touch window, never over a cutout it could avoid); 17 state-machine tests | How `TYPE_APPLICATION_OVERLAY` windows behave on specific OEM skins; real cutout rectangles on specific phones; animation smoothness; behaviour while a full-screen video/game is shown; whether Android 15+ lets a background app keep the overlay alive after the service is killed |
| The Kotlin compiles against Android 36 (`build_app.sh`) | Touch interaction (drag, long-press, 48 dp targets) and the accessibility announcements with TalkBack |
