package com.jarvis.assistant.ui

import com.jarvis.assistant.ui.chat.ChatScreen
import com.jarvis.assistant.ui.kit.Screen
import com.jarvis.assistant.ui.library.LibraryScreen
import com.jarvis.assistant.ui.reminders.RemindersScreen
import com.jarvis.assistant.ui.settings.AboutPage
import com.jarvis.assistant.ui.settings.AiPage
import com.jarvis.assistant.ui.settings.AssistantSetupPage
import com.jarvis.assistant.ui.settings.OnboardingScreen
import com.jarvis.assistant.ui.settings.DiagnosticsPage
import com.jarvis.assistant.ui.settings.IntegrationsPage
import com.jarvis.assistant.ui.settings.StoragePage
import com.jarvis.assistant.ui.settings.AppearancePage
import com.jarvis.assistant.ui.settings.CapabilitiesScreen
import com.jarvis.assistant.ui.settings.IslandPage
import com.jarvis.assistant.ui.settings.LockPage
import com.jarvis.assistant.ui.settings.PermissionsScreen
import com.jarvis.assistant.ui.settings.WakePage
import com.jarvis.assistant.ui.settings.GeneralPage
import com.jarvis.assistant.ui.settings.SettingsHomeScreen
import com.jarvis.assistant.ui.settings.VoicePage

/** Creates a screen from its id. Ids are what the back stack saves across rotation and process death, so every screen can be rebuilt from its id alone. */
object Screens {
    /** Pages that open as bottom-bar tabs. */
    val TABS = listOf("chat", "library", "reminders", "settings")

    fun create(id: String): Screen? = when (id) {
        "chat" -> ChatScreen()
        "library" -> LibraryScreen()
        "reminders" -> RemindersScreen()
        "settings" -> SettingsHomeScreen()
        "settings:general" -> GeneralPage()
        "settings:appearance" -> AppearancePage()
        "settings:voice" -> VoicePage()
        "settings:ai" -> AiPage()
        "settings:wake" -> WakePage()
        "settings:assistant" -> AssistantSetupPage()
        "onboarding" -> OnboardingScreen()
        "settings:island" -> IslandPage()
        "settings:lock" -> LockPage()
        "settings:integrations" -> IntegrationsPage()
        "settings:storage" -> StoragePage()
        "settings:diagnostics" -> DiagnosticsPage()
        "settings:about" -> AboutPage()
        "permissions" -> PermissionsScreen()
        "capabilities" -> CapabilitiesScreen()
        else -> null
    }

    /** Maps the page names used by notifications and the "open the X screen" tool to a screen id. */
    fun idForPage(page: String): String? = when (page.lowercase().trim()) {
        "chat", "home", "main" -> "chat"
        "library", "files" -> "library"
        "reminders", "notes", "timers" -> "reminders"
        "settings" -> "settings"
        "permissions" -> "permissions"
        "capabilities", "help" -> "capabilities"
        "island" -> "settings:island"
        "ai", "providers" -> "settings:ai"
        else -> null
    }
}
