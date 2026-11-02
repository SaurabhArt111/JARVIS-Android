package com.jarvis.core.tools

import com.jarvis.core.lock.LockState
import com.jarvis.core.model.ActionType
import com.jarvis.core.model.ReplyKind
import com.jarvis.core.model.Requirement
import com.jarvis.core.ports.*
import com.jarvis.core.settings.Keys
import com.jarvis.core.testing.ToolHarness
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ToolsTest {
    private val h = ToolHarness()
    private val env get() = h.env

    // ------------------------------------------------------------------------------------------------ open
    @Test fun openLaunchesInstalledApp() {
        val o = h.run("open", "target" to "spotify")
        assertTrue(o.ok); assertTrue(o.verified)
        assertEquals(listOf("com.spotify.music"), env.apps.launched)
    }

    @Test fun openMissingAppFailsAndLaunchesNothing() {
        val o = h.run("open", "target" to "gta vice city")
        assertFalse(o.ok)
        assertTrue(env.apps.launched.isEmpty())
        assertTrue(o.reply!!.text.contains("couldn't find"))
    }

    @Test fun openRefusesSystemUiAndOwnApp() {
        env.apps.installed = env.apps.installed + listOf(AppMatch("System UI", "com.android.systemui"), AppMatch("Jarvis", "com.jarvis.assistant"))
        assertEquals(ReplyKind.BLOCKED, h.run("open", "target" to "system ui").reply!!.kind)
        assertEquals(ReplyKind.BLOCKED, h.run("open", "target" to "jarvis").reply!!.kind)
        assertTrue(env.apps.launched.isEmpty())
    }

    @Test fun openRefusesNonWebSchemes() {
        for (bad in listOf("tel:112", "javascript:alert(1)", "intent://scan/#Intent;end", "file:///sdcard/x", "content://contacts/people")) {
            val o = h.run("open", "target" to bad)
            assertFalse("$bad must be refused", o.ok)
        }
        assertTrue(env.apps.urls.isEmpty())
    }

    @Test fun openProtectedPathIsBlocked() {
        val o = h.run("open", "target" to "/data/data/com.other.app/databases")
        assertEquals(ReplyKind.BLOCKED, o.reply!!.kind)
    }

    @Test fun openWebsiteInNamedBrowser() {
        val o = h.run("open", "target" to "youtube", "in_app" to "brave")
        assertTrue(o.ok)
        assertEquals("https://www.youtube.com" to "com.brave.browser", env.apps.urls.single())
    }

    @Test fun openWebsiteInMissingBrowserFails() {
        val o = h.run("open", "target" to "youtube", "in_app" to "firefox")
        assertFalse(o.ok); assertTrue(env.apps.urls.isEmpty())
        assertTrue(o.reply!!.text.contains("isn't installed"))
    }

    @Test fun openDomainBecomesHttps() {
        h.run("open", "target" to "example.com")
        assertEquals("https://example.com", env.apps.urls.single().first)
    }

    @Test fun openSettingsPage() {
        assertTrue(h.run("open", "target" to "wifi settings").ok)
        assertEquals(listOf("wifi"), env.apps.settingsOpened)
    }

    @Test fun openKnownSiteWhenAppMissingSaysSo() {
        env.apps.installed = emptyList()
        val o = h.run("open", "target" to "reddit")
        assertTrue(o.ok)
        assertTrue(o.note.contains("No app called"))
        assertEquals("https://www.reddit.com", env.apps.urls.single().first)
    }

    // ------------------------------------------------------------------------------------------------ play / media
    @Test fun playCannotConfirmWhenNothingReportsPlaying() {
        val o = h.run("play", "query" to "faded", "where" to "spotify")
        assertTrue(o.ok); assertFalse(o.verified)
        assertEquals("faded" to "com.spotify.music", env.media.searches.single())
        assertTrue(o.reply!!.text.contains("Asked Spotify"))
        assertFalse(o.reply!!.text.contains("Now playing"))
    }

    @Test fun playIsVerifiedOnlyWhenMediaSessionPlays() {
        env.media.track = TrackInfo("Faded", "Alan Walker", "", "Spotify", "com.spotify.music", PlayState.PLAYING)
        val o = h.run("play", "query" to "faded")
        assertTrue(o.verified)
        assertTrue(o.note.contains("Now playing Faded"))
    }

    @Test fun playInMissingAppDoesNothing() {
        val o = h.run("play", "query" to "faded", "where" to "tidal")
        assertFalse(o.ok); assertTrue(env.media.searches.isEmpty())
    }

    @Test fun mediaPauseIsVerifiedByReadingTheSession() {
        env.media.track = TrackInfo("Song", "A", "", "Spotify", "com.spotify.music", PlayState.PLAYING)
        env.media.applyControls = true
        val o = h.run("media", "action" to "pause")
        assertTrue(o.ok); assertTrue(o.verified)
        assertEquals("Paused.", o.reply!!.text)
    }

    @Test fun mediaPauseIsNotClaimedWhenStateDidNotChange() {
        env.media.track = TrackInfo("Song", "A", "", "Spotify", "com.spotify.music", PlayState.PLAYING)
        val o = h.run("media", "action" to "pause")
        assertTrue(o.ok); assertFalse(o.verified)
        assertTrue(o.reply!!.text.contains("couldn't confirm"))
    }

    @Test fun nowPlayingWithoutNotificationAccessSaysWhatIsMissing() {
        env.permissions.granted.remove(Requirement.NOTIFICATION_LISTENER)
        val o = h.run("media", "action" to "now_playing")
        assertFalse(o.ok)
        assertEquals(listOf(Requirement.NOTIFICATION_LISTENER), o.reply!!.missing)
        assertEquals(ActionType.GRANT, o.reply!!.actions.single().type)
    }

    // ------------------------------------------------------------------------------------------------ system
    @Test fun volumeSetReadsBack() {
        val o = h.run("system", "action" to "volume_set", "value" to "40")
        assertTrue(o.ok); assertTrue(o.verified)
        assertEquals(40, env.device.vol)
        assertEquals("Volume is now 40%.", o.reply!!.text)
    }

    @Test fun volumeSetIsReportedAsFailedWhenThePhoneKeepsAnotherValue() {
        env.device.volumeStuck = 10
        val o = h.run("system", "action" to "volume_set", "value" to "80")
        assertFalse(o.ok)
        assertTrue(o.reply!!.text.contains("reports 10%"))
    }

    @Test fun volumeUpAddsTen() {
        h.run("system", "action" to "volume_up")
        assertEquals(40, env.device.vol)
    }

    @Test fun muteIsVerified() {
        assertTrue(h.run("system", "action" to "mute").verified); assertTrue(env.device.muted)
        assertFalse(h.run("system", "action" to "unmute").let { !it.ok }); assertFalse(env.device.muted)
    }

    @Test fun brightnessNeedsWriteSettingsAndOffersTheGrant() {
        env.device.brightnessAllowed = false
        val o = h.run("system", "action" to "brightness_set", "value" to "70")
        assertFalse(o.ok)
        assertEquals(listOf(Requirement.WRITE_SETTINGS), o.reply!!.missing)
        assertEquals(Requirement.WRITE_SETTINGS.name, o.reply!!.actions.single().payload["requirement"])
    }

    @Test fun flashlightOnAndOff() {
        assertTrue(h.run("system", "action" to "flashlight_on").ok); assertTrue(env.device.torch)
        h.run("system", "action" to "flashlight_off"); assertFalse(env.device.torch)
    }

    @Test fun batteryIsAnInfoResult() {
        val o = h.run("system", "action" to "battery")
        assertTrue(o.info); assertTrue(o.note.contains("72%"))
    }

    @Test fun clipboardIsMarkedSensitiveAndHiddenFromTheAiWhenSharingIsOff() {
        env.device.clip = "my bank pin is 1234"
        env.settings.set(Keys.AI_SHARE_PERSONAL, "Off")
        val o = h.run("system", "action" to "clipboard")
        assertTrue(o.reply!!.sensitive)
        assertFalse(o.note.contains("1234"))
    }

    @Test fun unknownSystemActionIsRefused() {
        assertFalse(h.run("system", "action" to "format_phone").ok)
    }

    // ------------------------------------------------------------------------------------------------ navigate
    @Test fun navigateHomeNeedsAccessibility() {
        env.navigator.enabled = false
        val o = h.run("navigate", "action" to "home")
        assertFalse(o.ok)
        assertTrue(Requirement.ACCESSIBILITY in o.reply!!.missing)
    }

    @Test fun navigatePerformsTheGlobalAction() {
        assertTrue(h.run("navigate", "action" to "recents").ok)
        assertEquals(listOf(GlobalAction.RECENTS), env.navigator.performed)
    }

    @Test fun androidCannotCloseOtherAppsAndTheToolSaysSo() {
        val o = h.run("navigate", "action" to "close_app")
        assertFalse(o.ok); assertTrue(o.reply!!.text.contains("can't close") || o.reply!!.text.contains("doesn't let"))
        assertTrue(env.navigator.performed.isEmpty())
    }

    // ------------------------------------------------------------------------------------------------ reminders
    @Test fun reminderIsStoredScheduledAndVerified() {
        val o = h.run("reminder", "action" to "add", "text" to "call mom", "when" to "5 pm")
        assertTrue(o.ok); assertTrue(o.verified)
        val r = env.reminders.items.values.single()
        assertEquals("call mom", r.text); assertEquals(17, r.due.hour)
        assertTrue(env.scheduler.isScheduled(r.id))
        assertTrue(o.reply!!.text.contains("5:00 PM today"))
        assertTrue(o.reply!!.sensitive)
    }

    @Test fun reminderInThePastIsRefusedAndNothingIsCreated() {
        val o = h.run("reminder", "action" to "add", "text" to "x", "when" to "2026-10-25T08:00")
        assertFalse(o.ok); assertTrue(env.reminders.items.isEmpty())
    }

    @Test fun reminderIsNotCreatedWhenTheSchedulerFails() {
        env.scheduler.failToSchedule = true
        val o = h.run("reminder", "action" to "add", "text" to "x", "when" to "5 pm")
        assertFalse(o.ok); assertTrue(env.reminders.items.isEmpty())
        assertTrue(o.reply!!.text.contains("did not create"))
    }

    @Test fun inexactAlarmsAreDisclosedWithAGrantButton() {
        env.scheduler.exact = false
        val o = h.run("reminder", "action" to "add", "text" to "x", "when" to "5 pm")
        assertTrue(o.ok); assertTrue(o.reply!!.text.contains("late"))
        assertEquals(Requirement.EXACT_ALARM.name, o.reply!!.actions.single().payload["requirement"])
    }

    @Test fun retryingTheSameReminderDoesNotDoubleBook() {
        h.run("reminder", "action" to "add", "text" to "call mom", "when" to "5 pm")
        val o = h.run("reminder", "action" to "add", "text" to "Call Mom", "when" to "5 pm")
        assertTrue(o.note.contains("already exists")); assertEquals(1, env.reminders.items.size)
    }

    @Test fun reminderListAnswersYesOrNoForATime() {
        h.run("reminder", "action" to "add", "text" to "gym", "when" to "6 pm")
        assertTrue(h.run("reminder", "action" to "list", "day" to "today", "time" to "18:00").note.startsWith("YES"))
        assertTrue(h.run("reminder", "action" to "list", "day" to "today", "time" to "19:00").note.startsWith("NO"))
    }

    @Test fun reminderUpdateFindsByWordsAndMoves() {
        h.run("reminder", "action" to "add", "text" to "buy milk", "when" to "5 pm")
        val o = h.run("reminder", "action" to "update", "query" to "milk", "when" to "7 pm")
        assertTrue(o.ok)
        assertEquals(19, env.reminders.items.values.single().due.hour)
    }

    @Test fun cancelOnlyFlagsTheReminder() {
        h.run("reminder", "action" to "add", "text" to "buy milk", "when" to "5 pm")
        val id = env.reminders.items.keys.single()
        assertTrue(h.run("reminder", "action" to "cancel", "id" to id).ok)
        assertEquals(ReminderState.CANCELLED, env.reminders.items[id]!!.state)
        assertFalse(env.scheduler.isScheduled(id))
    }

    @Test fun reminderListIsRedactedForTheAiWhenSharingIsOff() {
        h.run("reminder", "action" to "add", "text" to "secret surprise party", "when" to "5 pm")
        env.settings.set(Keys.AI_SHARE_PERSONAL, "Off")
        val o = h.run("reminder", "action" to "list")
        assertFalse(o.note.contains("surprise")); assertTrue(o.reply!!.card!!.items.any { it.text.contains("surprise") })
    }

    // ------------------------------------------------------------------------------------------------ notes
    @Test fun noteAddListAppend() {
        assertTrue(h.run("note", "action" to "add", "text" to "buy milk").ok)
        assertTrue(h.run("note", "action" to "append", "text" to "and eggs").ok)
        val o = h.run("note", "action" to "list")
        assertTrue(o.note.contains("buy milk and eggs") || o.note.contains("buy milk") && o.note.contains("eggs"))
        assertEquals(1, env.notes.items.size)
    }

    @Test fun noteWithReminderCreatesBothAndLinksThem() {
        val o = h.run("note", "action" to "add", "text" to "pay rent", "remind_at" to "6 pm")
        assertTrue(o.ok)
        val n = env.notes.items.values.single(); val r = env.reminders.items.values.single()
        assertEquals(r.id, n.reminderId); assertEquals(n.id, r.noteId)
    }

    @Test fun noteWithAnUnusableTimeCreatesNothing() {
        val o = h.run("note", "action" to "add", "text" to "pay rent", "remind_at" to "yesterday")
        assertFalse(o.ok); assertTrue(env.notes.items.isEmpty())
    }

    // ------------------------------------------------------------------------------------------------ timers & alarms
    @Test fun timerStartsAndIsListedAsRunning() {
        val o = h.run("timer", "action" to "start", "duration" to "10 minutes")
        assertTrue(o.ok); assertTrue(o.verified)
        assertEquals(600, env.timers.active().single().seconds)
        assertEquals("Timer set for 10 minutes.", o.reply!!.text)
    }

    @Test fun timerSaysSoWhenAndroidOnlyAllowsAnApproximateAlarm() {
        env.timers.exact = false
        val o = h.run("timer", "action" to "start", "duration" to "10 minutes")
        assertTrue(o.ok)
        assertTrue(o.reply!!.text, o.reply!!.text.contains("a little late"))
        assertTrue(o.note, o.note.contains("approximate"))
    }

    @Test fun timerWithoutDurationAsksForIt() {
        val o = h.run("timer", "action" to "start")
        assertFalse(o.ok); assertTrue(o.reply!!.text.startsWith("For how long"))
    }

    @Test fun timerInTheClockAppIsNeverClaimedAsConfirmed() {
        val o = h.run("timer", "action" to "start", "seconds" to 90, "use_clock_app" to true)
        assertTrue(o.ok); assertFalse(o.verified)
        assertEquals(90 to "Timer", env.clockApp.timers.single())
    }

    @Test fun timerStopStopsAll() {
        h.run("timer", "action" to "start", "seconds" to 60)
        assertTrue(h.run("timer", "action" to "stop").ok)
        assertTrue(env.timers.active().isEmpty())
    }

    @Test fun alarmWithinADayGoesToTheClockAppAndIsNotVerified() {
        val o = h.run("alarm", "time" to "tomorrow at 6:30 am")
        assertTrue(o.ok); assertFalse(o.verified)
        assertEquals(6 to 30, env.clockApp.alarms.single().let { it.first to it.second })
        assertTrue(o.reply!!.text.contains("check the Clock app"))
    }

    @Test fun alarmDaysAwayBecomesAJarvisAlarmReminder() {
        val o = h.run("alarm", "time" to "2026-11-03T06:00")
        assertTrue(o.ok); assertTrue(o.verified)
        assertTrue(env.clockApp.alarms.isEmpty())
        assertTrue(env.reminders.items.values.single().isAlarm)
    }

    // ------------------------------------------------------------------------------------------------ information
    @Test fun webSearchWithNoResultsIsHonest() {
        val o = h.run("web_search", "query" to "xyz")
        assertTrue(o.note.contains("returned nothing"))
        assertFalse(o.verified)
    }

    @Test fun webSearchReturnsSourcesForTheModel() {
        env.web.hits = listOf(SearchHit("Title", "Snippet", "https://e.com"))
        val o = h.run("web_search", "query" to "q")
        assertTrue(o.info); assertTrue(o.note.contains("Snippet"))
        assertEquals(1, (o.reply!!.extras["sources"] as List<*>).size)
    }

    @Test fun weatherFailureIsAnError() {
        env.weather.result = WeatherResult(null, "no network")
        assertFalse(h.run("weather").ok)
    }

    @Test fun weatherSuccessReportsRealNumbers() {
        val o = h.run("weather", "city" to "Mumbai")
        assertTrue(o.note.contains("31°C") && o.note.contains("Haze"))
    }

    @Test fun calculateWorksWithoutEval() {
        assertEquals("15% of 240 = 36", h.run("calculate", "expression" to "15% of 240").note.replace("15 percent of 240", "15% of 240").let { if (it.contains("= 36")) "15% of 240 = 36" else it })
        assertTrue(h.run("calculate", "expression" to "(12+5)*3").note.endsWith("= 51"))
        assertFalse(h.run("calculate", "expression" to "1/0").ok)
        assertFalse(h.run("calculate", "expression" to "__import__('os').system('ls')").ok)
    }

    // ------------------------------------------------------------------------------------------------ messages & calls
    private val mom = ContactMatch("1", "Mom", listOf(ContactNumber("+91 98765 43210", "mobile", true)))
    private val momWork = ContactMatch("2", "Mom Work", listOf(ContactNumber("+91 22 5555 0000", "work")))

    @Test fun messageIsPreparedNeverSent() {
        env.contacts.contacts = listOf(mom)
        val o = h.run("message", "app" to "whatsapp", "to" to "mom", "text" to "running late")
        assertTrue(o.ok); assertFalse(o.verified)
        assertEquals(Triple(MessagingApp.WHATSAPP, "+91 98765 43210", "running late"), env.messaging.chats.single())
        assertTrue(o.reply!!.text.contains("tap send"))
        assertFalse(o.reply!!.text.lowercase().contains("sent to") || o.reply!!.text.lowercase().startsWith("sent"))
        assertTrue(o.note.contains("NOT been sent"))
    }

    @Test fun messageWithAmbiguousContactAsksAndDoesNothingUntilAnswered() {
        env.contacts.contacts = listOf(mom, momWork)
        val o = h.run("message", "to" to "mom", "text" to "hi")
        assertTrue(o.reply!!.extras["question"] == true)
        assertTrue(env.messaging.chats.isEmpty())
        assertEquals("contact", h.dialog.waiting())
        val r = h.answer("the second one")
        assertEquals("+91 22 5555 0000", env.messaging.chats.single().second)
        assertTrue(r!!.text.contains("Mom Work"))
    }

    @Test fun messageWithoutContactsPermissionExplainsAndOffersTheGrant() {
        env.contacts.missing = listOf(Requirement.CONTACTS)
        val o = h.run("message", "to" to "mom", "text" to "hi")
        assertFalse(o.ok); assertEquals(listOf(Requirement.CONTACTS), o.reply!!.missing)
        assertTrue(env.messaging.chats.isEmpty())
    }

    @Test fun messageToUnknownContactOpensNothing() {
        val o = h.run("message", "to" to "nobody", "text" to "hi")
        assertFalse(o.ok); assertTrue(env.messaging.chats.isEmpty())
    }

    @Test fun messageToANumberSkipsTheContactLookup() {
        val o = h.run("message", "app" to "sms", "to" to "+1 555 010 9999", "text" to "hello")
        assertTrue(o.ok); assertEquals(MessagingApp.SMS, env.messaging.chats.single().first)
    }

    @Test fun callByNameUsesTheContactsNumber() {
        env.contacts.contacts = listOf(mom)
        val o = h.run("call", "contact" to "mom")
        assertTrue(o.ok); assertEquals("+91 98765 43210" to "Mom", env.calls.calls.single())
        assertFalse(o.verified)
    }

    @Test fun callReportsWhenOnlyTheDialerOpened() {
        env.contacts.contacts = listOf(mom); env.calls.direct = false
        val o = h.run("call", "contact" to "mom")
        assertTrue(o.reply!!.text.contains("dialer"))
    }

    @Test fun answerAndHangUpGoThroughTheCallGatewayAndAreNeverVerified() {
        env.calls.ringing = true; env.calls.inCall = true
        val a = h.run("call", "action" to "answer")
        assertTrue(a.ok); assertFalse(a.verified); assertEquals(1, env.calls.answered)
        val e = h.run("call", "action" to "hang up")
        assertTrue(e.ok); assertFalse(e.verified); assertEquals(1, env.calls.ended)
        assertTrue("no call was placed", env.calls.calls.isEmpty())
    }

    @Test fun answerWithNothingRingingIsReportedAsAFailure() {
        val o = h.run("call", "action" to "answer")
        assertFalse(o.ok); assertEquals(0, env.calls.answered)
        assertFalse(h.run("call", "action" to "hang_up").ok)
    }

    @Test fun callWithAmbiguityAsksWhichOne() {
        env.contacts.contacts = listOf(mom, momWork)
        h.run("call", "contact" to "mom")
        assertTrue(env.calls.calls.isEmpty())
        h.answer("1")
        assertEquals("Mom", env.calls.calls.single().second)
    }

    @Test fun callWithTwoNumbersAsksWhichNumber() {
        env.contacts.contacts = listOf(ContactMatch("3", "Dad", listOf(ContactNumber("111 111 111", "home"), ContactNumber("222 222 222", "mobile"))))
        h.run("call", "contact" to "dad")
        assertEquals("number", h.dialog.waiting())
        h.answer("2")
        assertEquals("222 222 222", env.calls.calls.single().first)
    }

    // ------------------------------------------------------------------------------------------------ notifications
    private fun notif(key: String = "k1", canReply: Boolean = true) =
        NotificationInfo(key, "com.whatsapp", "WhatsApp", "Asha", "Are you coming?", env.clock.nowMs() - 120_000, NotificationKind.MESSAGE, canReply, conversation = "Asha")

    @Test fun readNotificationsWithoutAccessSaysWhatIsMissing() {
        env.notifications.granted = false
        val o = h.run("read_notifications")
        assertFalse(o.ok); assertTrue(Requirement.NOTIFICATION_LISTENER in o.reply!!.missing)
    }

    @Test fun readNotificationsListsThemAsPrivate() {
        env.notifications.list = listOf(notif())
        val o = h.run("read_notifications")
        assertTrue(o.ok); assertTrue(o.reply!!.sensitive); assertTrue(o.note.contains("Are you coming?"))
    }

    @Test fun replyToNotificationAsksFirstAndSendsOnlyAfterYes() {
        env.notifications.list = listOf(notif())
        val o = h.run("reply_notification", "text" to "Yes, 5 minutes")
        assertTrue(o.reply!!.extras["question"] == true)
        assertTrue(env.notifications.replies.isEmpty())
        val r = h.answer("send it")
        assertEquals("k1" to "Yes, 5 minutes", env.notifications.replies.single())
        assertTrue(r!!.text.contains("can't see a delivery confirmation"))
    }

    @Test fun replyToNotificationIsDroppedOnNo() {
        env.notifications.list = listOf(notif())
        h.run("reply_notification", "text" to "hi")
        h.answer("no")
        assertTrue(env.notifications.replies.isEmpty())
    }

    @Test fun replyWithoutAReplyActionIsRefusedHonestly() {
        env.notifications.list = listOf(notif(canReply = false))
        val o = h.run("reply_notification", "text" to "hi")
        assertFalse(o.ok); assertTrue(env.notifications.replies.isEmpty())
    }

    // ------------------------------------------------------------------------------------------------ documents / images / jarvis
    @Test fun saveDocumentWritesAndListsTheFile() {
        val o = h.run("save_document", "title" to "Trip plan", "content" to "Day 1: ...")
        assertTrue(o.ok); assertTrue(o.verified)
        val f = env.documents.files.single()
        assertEquals(ContentCategory.GENERATED_TEXT, f.category)
        assertEquals(ActionType.OPEN_FILE, o.reply!!.actions.single().type)
    }

    @Test fun saveDocumentRejectsEmptyContentAndNeverOverwrites() {
        assertFalse(h.run("save_document", "title" to "x", "content" to "  ").ok)
        h.run("save_document", "title" to "plan", "content" to "a"); h.run("save_document", "title" to "plan", "content" to "b")
        assertEquals(2, env.documents.files.map { it.name }.toSet().size)
    }

    @Test fun saveDocumentFallsBackToTxtForForbiddenExtensions() {
        h.run("save_document", "title" to "evil", "content" to "x", "extension" to "exe")
        assertTrue(env.documents.files.single().name.endsWith(".txt"))
    }

    @Test fun imageNeedsAProviderAndOtherwiseExplains() {
        env.images.availableNow = false to "No image provider key is set."
        assertFalse(h.run("image", "prompt" to "a cat").ok)
        assertTrue(env.images.started.isEmpty())
    }

    @Test fun imageStartsAJobAndDoesNotPretendItIsDone() {
        val o = h.run("image", "prompt" to "a cat in a hat")
        assertTrue(o.ok); assertFalse(o.verified)
        assertEquals(listOf("a cat in a hat"), env.images.started)
        assertEquals("generating", o.reply!!.card!!.type)
    }

    @Test fun jarvisOpensKnownPagesOnly() {
        assertTrue(h.run("jarvis", "action" to "goto_page", "value" to "reminders").ok)
        assertEquals(listOf("reminders"), env.control.pages)
        assertFalse(h.run("jarvis", "action" to "goto_page", "value" to "bank vault").ok)
    }

    @Test fun jarvisMicAndVoiceAndAiMode() {
        assertTrue(h.run("jarvis", "action" to "mic_off").ok); assertEquals(false, env.control.listening)
        h.run("jarvis", "action" to "mute_voice"); assertTrue(env.settings.flag(Keys.VOICE_MUTED, false))
        h.run("jarvis", "action" to "ai_mode", "value" to "local"); assertEquals("local", env.settings.get(Keys.AI_MODE))
        assertFalse(h.run("jarvis", "action" to "ai_mode", "value" to "quantum").ok)
    }

    @Test fun everyToolHasASchemaAndAUniqueName() {
        val names = h.registry.specs().map { it.name }
        assertEquals(names.size, names.toSet().size)
        assertTrue(names.containsAll(listOf("open", "play", "media", "system", "navigate", "reminder", "note", "timer", "alarm", "web_search", "search_in_browser",
            "weather", "message", "call", "read_notifications", "reply_notification", "save_document", "image", "calculate", "jarvis")))
        h.registry.specs().forEach { s ->
            val fn = s.toSchema()["function"] as Map<*, *>
            assertEquals(s.name, fn["name"]); assertTrue(s.required.all { it in s.params.keys })
        }
    }

    @Test fun lockedFlagDoesNotChangeToolsThemselves() {
        // the lock policy lives in ToolExecutor; the tools themselves are pure
        assertTrue(h.run("timer", mapOf("action" to "start", "seconds" to 30), h.ctx(LockState.LOCKED_SECURE)).ok)
    }

    @Suppress("unused") private fun unused() = runBlocking { }
}
