package com.jarvis.core.tools.impl

import com.jarvis.core.organizer.NoteService
import com.jarvis.core.organizer.Organizer
import com.jarvis.core.organizer.ReminderService
import com.jarvis.core.ports.Services
import com.jarvis.core.tools.Tool
import com.jarvis.core.tools.ToolSpec

/** Every tool the assistant can call, plus the reminder / note services they share (one instance each, so their locks really serialise changes). */
class ToolRegistry(
    val tools: List<Tool>,
    val reminders: ReminderService,
    val notes: NoteService,
    val organizer: Organizer,
) {
    private val byName: Map<String, Tool> = tools.associateBy { it.spec.name }

    fun get(name: String): Tool? = byName[name]
    fun names(): List<String> = tools.map { it.spec.name }
    fun specs(): List<ToolSpec> = tools.map { it.spec }

    companion object {
        /** [settleMs] is how long play/media wait before re-reading the media session to verify the result (tests pass 0). */
        fun default(
            services: Services,
            reminders: ReminderService = ReminderService(services),
            notes: NoteService = NoteService(services),
            settleMs: Long = -1L,
        ): ToolRegistry {
            val organizer = Organizer(services, reminders, notes)
            lateinit var registry: ToolRegistry
            val list = listOf<Tool>(
                OpenTool(),
                if (settleMs >= 0) PlayTool(settleMs) else PlayTool(),
                if (settleMs >= 0) MediaTool(settleMs) else MediaTool(),
                SystemTool(),
                NavigateTool(),
                ReminderTool(reminders, notes),
                NoteTool(notes, reminders),
                TimerTool(),
                AlarmTool(reminders),
                WebSearchTool(),
                SearchInBrowserTool(),
                WeatherTool(),
                MessageTool(),
                CallTool(),
                ReadNotificationsTool(),
                ReplyNotificationTool(),
                SaveDocumentTool(),
                ImageTool(),
                CalculateTool(),
                JarvisTool { registry.get(it) },
            )
            registry = ToolRegistry(list, reminders, notes, organizer)
            return registry
        }
    }
}
