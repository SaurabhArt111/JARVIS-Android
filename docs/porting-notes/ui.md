# Porting notes: UI (`Frontend/GUI/*` -> `app/ui`)

* **Toolkit.** No AndroidX, Compose or Material: views are built in Kotlin (`ui/kit/Ui.kt`), lists use `ListView`/`ScrollView`, navigation is a small `ScreenHost` back stack. Reason: the whole app compiles against
  the plain Android framework, which is what could be checked offline.
* **Screens.** Chat (history, cards, voice orb, confirmations, image generation progress), reminders/timers/notes, library (browse, search, open, share, export, delete), settings (AI providers, voice,
  wake word, island, lock screen, integrations, storage, permissions, about/diagnostics), compact assistant sheet.
* **Theme.** System / light / dark, eight accents, three text sizes; edge-to-edge with cutout and keyboard insets (API 29-36), predictive-back callbacks.
* **Responsive.** Configuration changes are handled in place (rotation, folding, font scale, density) so typed text, scroll and running work survive; layouts use the measured window width.
* **Accessibility.** Content descriptions on icon buttons, 48 dp touch targets, live regions for status and replies. Not checked with TalkBack on a device.
* **Dropped from the desktop.** Orb families other than the aura orb, dot-matrix animation, hover behaviour, the designer canvas, window chrome.
* **English only.** All screen text is in `res/values/strings*.xml` (ready to translate); no other languages were written.
* **Not verified.** Visual rendering on real screens, density/size variants, dark/light contrast by eye, and any interaction test: there is no emulator here. `app/src/androidTest` (3 test classes) is type-checked only and has never been run.
