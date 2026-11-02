# Porting notes: island, lock screen, notification access, accessibility, assistant gesture

## Island (`Frontend/GUI/island.py`, `islandchat.py`, `islandshapes*.py`, `islandwidgets.py` -> `core/island`, `app/island`)
| Desktop idea | Android |
|---|---|
| Always-on-top window at the top-centre of the active display | `TYPE_APPLICATION_OVERLAY` window sized to the pill only, placed by the tested `IslandGeometry` from what Android reports about the display cutout (never claims to know where the camera is) |
| Idle pill / "notch" bowl, hover to open a player | **Kept in spirit only**: the pill appears when JARVIS has something to show and disappears (the window is removed) when idle so it can never block touches. No hover on touch screens; tap expands, long-press moves |
| States listening / thinking / speaking / chat | `IslandMode` `LISTENING, THINKING, SPEAKING, REPLY, NOTICE, ERROR`, driven by engine state/events and wake-service cues through the pure `IslandReducer` |
| Chat input in the island | Talk / Type buttons open the compact chat (`AssistantActivity`); full typing happens there, not inside the overlay (an overlay that takes focus would block the keyboard of other apps) |
| Rich widgets, dot-matrix pictures, music player, StreamSync | **Dropped** (replies are text; pictures and cards live in the chat) |
| Shapes (`islandshapes_data.py`) | Canvas-drawn pill/card; no shape bitmaps |

## Lock screen (new)
Desktop JARVIS has no lock-screen concept. `core/lock` holds the policy and the testable logic, `app/lock` the screen, the launcher and the notification box. See `LOCK_SCREEN.md`.

## Notifications and WhatsApp (`WhatsappMessage.py`, `Messenger.py` -> `app/access`)
Desktop automation drove the WhatsApp window. Android forbids driving another app, so reading and replying use **only** the notification listener and the notification's own reply action
(`RemoteInput`). Independent switches: notification integration, read aloud, WhatsApp quick reply, accessibility shortcuts. Message text is kept in memory only (60 most recent) and dropped on disconnect.
Delivery of a reply is never confirmed by Android, so JARVIS says "handed to <app>", never "sent".

## Accessibility (`WindowControl.py`, `Automation.py` -> `JarvisAccessibilityService`)
Reduced to `performGlobalAction` (Home, Back, Recents, shade, quick settings, lock, screenshot, power menu). No events, no window content, no gestures. Play-policy disclosure text is in Settings > Integrations.

## Assistant gesture (new)
`JarvisVoiceInteractionService` + session + recognition forwarder + tile; role/settings helper. See `ASSISTANT_SETUP.md`.
