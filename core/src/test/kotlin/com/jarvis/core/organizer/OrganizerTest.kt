package com.jarvis.core.organizer

import com.jarvis.core.engine.Dialog
import com.jarvis.core.lock.LockState
import com.jarvis.core.model.ActionType
import com.jarvis.core.model.AssistantReply
import com.jarvis.core.model.Origin
import com.jarvis.core.model.ReplyKind
import com.jarvis.core.ports.ReminderState
import com.jarvis.core.testing.TestEnv
import com.jarvis.core.tools.ToolContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/** Drives the organizer exactly like the engine does: a pending question gets the first chance to take the utterance. */
class OrganizerTest {
    private val env = TestEnv()          // Sunday 25 Oct 2026, 10:00, Asia/Kolkata
    private val dialog = Dialog(env.clock)
    private val org = Organizer(env.services)
    private val ctx = ToolContext(env.services, Origin.TEXT, LockState.UNLOCKED, dialog)

    private fun say(text: String): AssistantReply? = runBlocking { dialog.resume(text) ?: org.handle(text, ctx) }
    private fun sayOk(text: String): AssistantReply = say(text) ?: error("not handled: $text")
    private fun at(d: Int, h: Int, m: Int = 0) = LocalDateTime.of(2026, 10, d, h, m)
    private fun reminders() = env.reminders.items.values.sortedBy { it.due }
    private fun expectsReply(r: AssistantReply) = r.extras["expect_reply"] == true

    // ------------------------------------------------------------------------------------------------ routing
    @Test fun relatedDetectsNotesAndReminders() {
        assertTrue(org.related("remind me to call mom"))
        assertTrue(org.related("show my notes"))
        assertTrue(org.related("snooze it"))
        assertFalse(org.related("what's the weather"))
    }

    @Test fun unrelatedTextIsNotHandled() {
        assertNull(say("what's the weather in Pune"))
        assertNull(say("open spotify"))
        assertNull(say(""))
    }

    // ------------------------------------------------------------------------------------------------ add reminder
    @Test fun addsAReminderWithATime() {
        val r = sayOk("remind me to call mom at 5 pm")
        assertEquals("Okay, I'll remind you to call mom at 5:00 PM today.", r.text)
        assertEquals(ReplyKind.ACTION, r.kind)
        assertTrue("personal content must be protected on the lock screen", r.sensitive)
        val saved = reminders().single()
        assertEquals("call mom", saved.text)
        assertEquals(at(25, 17), saved.due)
        assertTrue(env.scheduler.isScheduled(saved.id))
    }

    @Test fun addsAReminderRelativeToNow() {
        sayOk("remind me to check the oven in 20 minutes")
        assertEquals(at(25, 10, 20), reminders().single().due)
    }

    @Test fun missingTimeIsAskedForAndTheAnswerIsUsed() {
        val q = sayOk("remind me to call mom")
        assertTrue(q.text, q.text.startsWith("When should I remind you to call mom?"))
        assertTrue(expectsReply(q))
        assertTrue(reminders().isEmpty())
        val r = sayOk("at 6 pm")
        assertTrue(r.text, r.text.contains("6:00 PM today"))
        assertEquals(at(25, 18), reminders().single().due)
    }

    @Test fun missingSubjectIsAskedForAndTheTimeAlreadyGivenIsKept() {
        val q = sayOk("remind me at 5 pm")
        assertEquals("What should I remind you about?", q.text)
        sayOk("pay the rent")
        val saved = reminders().single()
        assertEquals("pay the rent", saved.text)
        assertEquals(at(25, 17), saved.due)
    }

    @Test fun anAnswerThatNamesBothSubjectAndTimeIsEnough() {
        sayOk("remind me")
        sayOk("buy flowers at 4 pm")
        assertEquals("buy flowers", reminders().single().text)
    }

    @Test fun aTimeThatAlreadyPassedTodayIsConfirmedBeforeGoingToTomorrow() {
        val q = sayOk("remind me to stretch at 9 am today")
        assertEquals("9:00 AM has already passed today. Should I set it for tomorrow instead?", q.text)
        assertTrue(reminders().isEmpty())
        val r = sayOk("yes")
        assertTrue(r.text, r.text.contains("9:00 AM tomorrow"))
        assertEquals(at(26, 9), reminders().single().due)
    }

    @Test fun decliningTheTomorrowOfferAsksForAnotherTime() {
        sayOk("remind me to stretch at 9 am today")
        val q = sayOk("no")
        assertEquals("Okay. What time should I use instead?", q.text)
        sayOk("at 3 pm")
        assertEquals(at(25, 15), reminders().single().due)
    }

    @Test fun aDateWithoutATimeAsksWhatTime() {
        val q = sayOk("remind me to renew the passport on friday")
        assertEquals("What time on Friday?", q.text)
        sayOk("at 11 am")
        assertEquals(at(30, 11), reminders().single().due)
    }

    @Test fun twoUnparseableAnswersGiveUpPolitely() {
        sayOk("remind me to call mom")
        val second = sayOk("whenever")
        assertTrue(second.text, second.text.startsWith("Sorry, what time?"))
        val third = sayOk("soonish")
        assertTrue(third.text, third.text.startsWith("I still couldn't catch a time"))
        assertTrue(reminders().isEmpty())
    }

    @Test fun cancelWordsDropThePendingQuestion() {
        sayOk("remind me to call mom")
        assertEquals("Okay, I've dropped that.", sayOk("never mind").text)
        assertTrue(reminders().isEmpty())
    }

    @Test fun anAlarmUsesAlarmWordingAndMorningForBareHours() {
        val r = sayOk("wake me up at 7")
        assertEquals("Okay, I've set an alarm for 7:00 AM tomorrow. That time had already passed today, so it's for tomorrow.", r.text)
        val saved = reminders().single()
        assertTrue(saved.isAlarm)
        assertEquals(at(26, 7), saved.due)
    }

    @Test fun setAnAlarmForAnExplicitTime() {
        sayOk("set an alarm for 6:30 pm")
        assertEquals(at(25, 18, 30), reminders().single().due)
    }

    @Test fun anAssumedMeridiemIsSaidOutLoud() {
        val r = sayOk("remind me to call dad at 5")
        assertTrue(r.text, r.text.contains("I took that as PM. Say 'make it AM' to change it."))
    }

    @Test fun anIdenticalReminderIsNotDuplicated() {
        sayOk("remind me to call mom at 5 pm")
        val again = sayOk("remind me to call mom at 5 pm")
        assertEquals("You already have that reminder, 5:00 PM today.", again.text)
        assertEquals(1, reminders().size)
    }

    @Test fun whenTheAlarmCannotBeRegisteredTheUserIsToldAndNothingIsCreated() {
        env.scheduler.failToSchedule = true
        val r = sayOk("remind me to call mom at 5 pm")
        assertFalse(r.ok)
        assertEquals(ReplyKind.ERROR, r.kind)
        assertTrue(r.text, r.text.contains("Nothing was created"))
        assertTrue(reminders().isEmpty())
    }

    @Test fun inexactAlarmsAreDisclosedWithAGrantAction() {
        env.scheduler.exact = false
        val r = sayOk("remind me to call mom at 5 pm")
        assertTrue(r.text, r.text.contains("a few minutes late"))
        assertTrue(r.actions.any { it.type == ActionType.GRANT && it.payload["requirement"] == "EXACT_ALARM" })
        assertEquals(1, reminders().size)
    }

    // ------------------------------------------------------------------------------------------------ list
    @Test fun listsUpcomingReminders() {
        sayOk("remind me to call mom at 5 pm"); sayOk("remind me to buy milk tomorrow at 8 am")
        val r = sayOk("what reminders do i have")
        assertEquals("You have 2 upcoming reminders: call mom, 5:00 PM today; buy milk, 8:00 AM tomorrow.", r.text)
        assertEquals("You have 2 upcoming reminders.", r.extras["display_text"])
        assertEquals(2, r.card!!.items.size)
    }

    @Test fun listsRemindersForOneDayOnly() {
        sayOk("remind me to call mom at 5 pm"); sayOk("remind me to buy milk tomorrow at 8 am")
        val r = sayOk("show my reminders for tomorrow")
        assertEquals("You have 1 upcoming reminder for tomorrow: buy milk, 8:00 AM tomorrow.", r.text)
    }

    @Test fun emptyReminderListSaysSo() =
        assertEquals("You have no upcoming reminders. Say 'remind me to ... at ...' to add one.", sayOk("do i have any reminders").text)

    @Test fun answersYesOrNoForASpecificTime() {
        sayOk("remind me to call mom at 5 pm")
        assertTrue(sayOk("is there any reminder at 5 pm today").text.startsWith("Yes - call mom, at 5:00 PM today."))
        assertEquals("No, you have no reminder at 6:00 PM today.", sayOk("is there any reminder at 6 pm today").text)
    }

    // ------------------------------------------------------------------------------------------------ reschedule
    @Test fun rescheduleItUsesWhatWeWereJustTalkingAbout() {
        sayOk("remind me to call mom at 5 pm")
        val r = sayOk("reschedule it to 6 pm")
        assertEquals("Done. I moved 'call mom' from 5:00 PM today to 6:00 PM today.", r.text)
        val saved = reminders().single()
        assertEquals(at(25, 18), saved.due)
        assertEquals(1, saved.history.size)
    }

    @Test fun pushByADurationStartsFromTheReminderTime() {
        sayOk("remind me to call mom at 5 pm")
        sayOk("push it by 10 minutes")
        assertEquals(at(25, 17, 10), reminders().single().due)
    }

    @Test fun snoozeDefaultsToTenMinutes() {
        sayOk("remind me to call mom at 10:30 am")
        sayOk("snooze it")
        assertEquals(at(25, 10, 40), reminders().single().due)
    }

    @Test fun rescheduleAskingForTheTimeWhenNoneIsGiven() {
        sayOk("remind me to call mom at 5 pm")
        val q = sayOk("reschedule the reminder")
        assertEquals("What time should I move it to?", q.text)
        sayOk("8 pm")
        assertEquals(at(25, 20), reminders().single().due)
    }

    @Test fun rescheduleNamesTheReminderByItsWords() {
        sayOk("remind me to call mom at 5 pm"); sayOk("remind me to buy milk at 6 pm")
        sayOk("move the reminder about milk to 7 pm")
        assertEquals(listOf("call mom" to at(25, 17), "buy milk" to at(25, 19)), reminders().map { it.text to it.due })
    }

    @Test fun ambiguousTargetsAreChosenByNumber() {
        sayOk("remind me to call mom at 5 pm"); sayOk("remind me to call dad at 6 pm")
        val q = sayOk("reschedule the reminder to call to 8 pm")
        assertTrue(q.text, q.text.startsWith("Which reminder do you mean? 1, call mom"))
        sayOk("the second one")
        assertEquals(at(25, 20), reminders().first { it.text == "call dad" }.due)
        assertEquals(at(25, 17), reminders().first { it.text == "call mom" }.due)
    }

    @Test fun anUnknownNameIsReportedInsteadOfGuessing() {
        sayOk("remind me to call mom at 5 pm")
        val r = sayOk("reschedule the reminder about dentist to 6 pm")
        assertEquals("I couldn't find a reminder about dentist. Nothing was changed.", r.text)
        assertEquals(at(25, 17), reminders().single().due)
    }

    @Test fun makingANewThingIsNotAReschedule() {
        sayOk("remind me to call mom at 5 pm")
        sayOk("make a note buy milk")
        sayOk("set a reminder to buy bread at 6 pm")
        assertEquals(2, reminders().size)
        assertEquals(at(25, 17), reminders().first { it.text == "call mom" }.due)
    }

    @Test fun aFailedRescheduleIsReportedAndChangesNothing() {
        sayOk("remind me to call mom at 5 pm")
        env.scheduler.failToSchedule = true
        val r = sayOk("reschedule it to 6 pm")
        assertFalse(r.ok)
        assertEquals(at(25, 17), reminders().single().due)
    }

    @Test fun noRemindersMeansNothingToMove() =
        assertEquals("You don't have any upcoming reminders to change.", sayOk("reschedule the reminder to 6 pm").text)

    // ------------------------------------------------------------------------------------------------ complete / cancel / edit
    @Test fun cancelFlagsTheReminderAndKeepsIt() {
        sayOk("remind me to call mom at 5 pm")
        val r = sayOk("cancel the reminder")
        assertTrue(r.text, r.text.startsWith("Cancelled the reminder 'call mom'."))
        assertEquals(ReminderState.CANCELLED, reminders().single().state)
        assertTrue(env.scheduler.scheduled.isEmpty())
    }

    @Test fun markDone() {
        sayOk("remind me to call mom at 5 pm")
        assertEquals("Marked 'call mom' as done.", sayOk("mark the reminder as done").text)
        assertEquals(ReminderState.DONE, reminders().single().state)
    }

    @Test fun cancelAnAlarmClockAppIsNotAboutReminders() = assertNull(say("cancel the timer"))

    @Test fun editReminderTextKeepsItsTime() {
        sayOk("remind me to call mom at 5 pm")
        val r = sayOk("change the reminder to say call dad")
        assertEquals("Updated the reminder. It now says: call dad", r.text)
        val saved = reminders().single()
        assertEquals("call dad", saved.text)
        assertEquals(at(25, 17), saved.due)
    }

    @Test fun changingAReminderToATimeIsAReschedule() {
        sayOk("remind me to call mom at 5 pm")
        sayOk("change the reminder to 7 pm")
        val saved = reminders().single()
        assertEquals("call mom", saved.text)
        assertEquals(at(25, 19), saved.due)
    }

    // ------------------------------------------------------------------------------------------------ notes
    @Test fun savesAPlainNote() {
        val r = sayOk("make a note buy milk and eggs")
        assertEquals("Noted: buy milk and eggs.", r.text)
        assertEquals("buy milk and eggs", env.notes.items.values.single().text)
        assertTrue(reminders().isEmpty())
    }

    @Test fun aNoteWithATimeAlsoBecomesALinkedReminder() {
        val r = sayOk("make a note call the bank at 3 pm")
        assertEquals("Noted: call the bank at 3 pm. I also set a reminder for 3:00 PM today.", r.text)
        val note = env.notes.items.values.single()
        val rem = reminders().single()
        assertEquals("call the bank", rem.text)
        assertEquals(at(25, 15), rem.due)
        assertEquals(rem.id, note.reminderId)
        assertEquals(note.id, rem.noteId)
    }

    @Test fun aNoteWhoseTimeAlreadyPassedDoesNotMakeAReminder() {
        val r = sayOk("make a note stand-up was at 9 am today")
        assertTrue(r.text, r.text.contains("already passed today, so I didn't set a reminder"))
        assertTrue(reminders().isEmpty())
        assertEquals(1, env.notes.items.size)
    }

    @Test fun anEmptyNoteAsksWhatToWrite() {
        val q = sayOk("take a note")
        assertEquals("Sure. What should the note say?", q.text)
        assertTrue(expectsReply(q))
        sayOk("water the plants")
        assertEquals("water the plants", env.notes.items.values.single().text)
    }

    @Test fun rememberThatIsANote() {
        sayOk("remember that the wifi password is on the fridge")
        assertEquals("the wifi password is on the fridge", env.notes.items.values.single().text)
    }

    @Test fun listsNotesNewestFirst() {
        sayOk("make a note buy milk"); env.clock.advanceMs(60_000); sayOk("make a note call the plumber")
        val r = sayOk("what notes do i have")
        assertEquals("You have 2 notes. The latest: 1, call the plumber ... 2, buy milk.", r.text)
        assertEquals("You have 2 notes. The latest:", r.extras["display_text"])
    }

    @Test fun readsTheLastNote() {
        sayOk("make a note buy milk"); env.clock.advanceMs(60_000); sayOk("make a note call the plumber")
        assertEquals("Your last note: call the plumber", sayOk("read my last note").text)
    }

    @Test fun searchesNotesByWords() {
        sayOk("make a note buy milk"); env.clock.advanceMs(60_000); sayOk("make a note call the plumber")
        assertEquals("Here is what I found about plumber: 1, call the plumber.", sayOk("what is my note about the plumber").text)
        assertEquals("I couldn't find a note about zebra. You have 2 notes in total.", sayOk("show my note about zebra").text)
    }

    @Test fun noNotesYet() = assertTrue(sayOk("show my notes").text.startsWith("You don't have any notes yet."))

    @Test fun aGeneralQuestionAboutTheWordNoteIsLeftToTheChatModel() = assertNull(say("what is a note in music"))

    @Test fun appendsToTheLastNote() {
        sayOk("make a note buy milk")
        val r = sayOk("add and eggs to my last note")
        assertEquals("Added that to your note: buy milk. and eggs", r.text)
        assertEquals("buy milk. and eggs", env.notes.items.values.single().text)
        assertEquals(1, env.notes.items.values.single().history.size)
    }

    @Test fun editsANoteWithExplicitNewText() {
        sayOk("make a note buy milk")
        val r = sayOk("edit the note to say buy oat milk")
        assertEquals("Updated the note: buy oat milk", r.text)
        assertEquals("buy oat milk", env.notes.items.values.single().text)
    }

    @Test fun makeThatNoteAReminderAsksForATimeThenLinksIt() {
        sayOk("make a note call the bank")
        val q = sayOk("make that note a reminder")
        assertEquals("When should I remind you about that note?", q.text)
        sayOk("at 4 pm")
        val note = env.notes.items.values.single()
        val rem = reminders().single()
        assertEquals("call the bank", rem.text)
        assertEquals(rem.id, note.reminderId)
        assertEquals(at(25, 16), rem.due)
    }

    @Test fun rescheduleTheNoteMovesItsLinkedReminder() {
        sayOk("make a note call the bank at 3 pm")
        sayOk("reschedule the note to 5 pm")
        assertEquals(at(25, 17), reminders().single().due)
        assertEquals(1, reminders().size)
    }

    @Test fun nothingTheAssistantDoesEverDeletesAnything() {
        sayOk("remind me to call mom at 5 pm"); sayOk("make a note buy milk")
        sayOk("cancel the reminder"); sayOk("edit the note to say buy bread"); sayOk("reschedule the note to 6 pm")
        // the call-mom reminder (cancelled, still there) + the one made for the note; the note itself was edited, not replaced
        assertEquals(2, env.reminders.items.size)
        assertEquals(1, env.notes.items.size)
        assertEquals(ReminderState.CANCELLED, reminders().first { it.text == "call mom" }.state)
        assertEquals(1, env.notes.items.values.single().history.size)
        assertNotNull(env.notes.items.values.single().reminderId)
    }
}
