package com.jarvis.core.tools.impl

import com.jarvis.core.model.ActionType
import com.jarvis.core.model.AssistantReply
import com.jarvis.core.model.Card
import com.jarvis.core.model.Outcome
import com.jarvis.core.model.Requirement
import com.jarvis.core.model.UiAction
import com.jarvis.core.ports.ContentCategory
import com.jarvis.core.ports.StorageLocation
import com.jarvis.core.storage.FileTypes
import com.jarvis.core.storage.SafeFileName
import com.jarvis.core.tools.Args
import com.jarvis.core.tools.Capability
import com.jarvis.core.tools.LockClass
import com.jarvis.core.tools.Tool
import com.jarvis.core.tools.ToolContext
import com.jarvis.core.tools.ToolSpec

// ===================================================================================================== save_document
class SaveDocumentTool : Tool {
    override val spec = ToolSpec(
        "save_document",
        "Save text you have written (notes, essay, email, code, plan ...) as a file in the JARVIS folder (Documents/JARVIS). Write the full content yourself first. " +
            "category: generated (default) | notes | exports. extension: txt (default) | md | html | csv | json.",
        mapOf(
            "title" to T.p("Short file title"), "content" to T.p("The complete text"),
            "category" to T.p("generated | notes | exports", enum = listOf("generated", "notes", "exports")),
            "extension" to T.p("txt | md | html | csv | json"), "open" to T.p("Open it right after saving", "boolean"),
        ),
        listOf("title", "content"), Capability.FILES, LockClass.PERSONAL,
    )

    override suspend fun execute(args: Args, ctx: ToolContext): Outcome {
        val svc = ctx.services
        val title = args.str("title", "name", default = "document")
        val content = args.str("content", "text", "body")
        if (content.isBlank()) return T.fail("There's nothing to save yet.", "file-text")
        if (content.length > 1_000_000) return T.fail("That's too long to save as a single document (over 1 MB of text).", "file-text")
        val cat = when (args.str("category", default = "generated").lowercase()) {
            "notes", "note" -> ContentCategory.NOTES
            "exports", "export" -> ContentCategory.EXPORTS
            else -> ContentCategory.GENERATED_TEXT
        }
        var ext = SafeFileName.extension(args.str("extension", default = "txt")).ifEmpty { "txt" }
        if (!FileTypes.isAllowed(cat, ext)) ext = "txt"
        val file = try {
            svc.documents.saveText(cat, title, content, ext, source = args.str("source").ifEmpty { null })
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            svc.log.w("SaveDocument", "save failed: ${e.javaClass.simpleName}")
            return T.fail("I couldn't save the file (${e.message?.take(80) ?: e.javaClass.simpleName}). Nothing was written.", "file-text")
        }
        // read it back: the file must be listed in the library, otherwise we do not claim it was saved
        val listed = svc.documents.list(cat, file.name).any { it.id == file.id }
        if (!listed) return T.fail("I wrote the file, but it doesn't show up in the library, so I can't promise it was saved.", "file-text")
        val place = if (file.location == StorageLocation.PUBLIC_DOCUMENTS) "Documents/JARVIS/${cat.folder}" else "JARVIS's private storage (shared Documents wasn't available)"
        var opened = false
        if (args.bool("open") && !ctx.lock.deviceLocked) opened = svc.apps.openFile(file.uri, file.mime).ok
        val text = "Saved it as ${file.name} in $place${if (opened) " and opened it" else ""}."
        return Outcome(true, text, T.reply(text, "file-text", Card("file", "Document saved", file.name, "file-text", fields = mapOf("uri" to file.uri, "mime" to file.mime, "id" to file.id)),
            actions = listOf(UiAction("Open", ActionType.OPEN_FILE, mapOf("uri" to file.uri, "mime" to file.mime, "name" to file.name))), sensitive = false), verified = true)
    }
}

// ===================================================================================================== image
class ImageTool : Tool {
    override val spec = ToolSpec(
        "image", "Generate a picture. Write a rich, specific prompt (subject, style, lighting, composition, mood). It shows up in the chat when ready (and is saved in Documents/JARVIS/Images).",
        mapOf(
            "prompt" to T.p("Detailed image description"), "style" to T.p("Optional style: photo, cinematic, anime, 3d, watercolor ..."),
            "aspect" to T.p("square | wide | tall"), "count" to T.p("1-4 variations", "integer"),
        ),
        listOf("prompt"), Capability.IMAGE_GENERATION, LockClass.PERSONAL, needs = listOf(Requirement.NETWORK),
    )

    override suspend fun execute(args: Args, ctx: ToolContext): Outcome {
        val svc = ctx.services
        val prompt = args.str("prompt", "description", "text")
        if (prompt.isEmpty()) return T.fail("What should the picture show?", "image")
        val (ok, why) = svc.images.available()
        if (!ok) return T.fail(why.ifBlank { "Image generation isn't available right now." }, "image", if (why.contains("key", true)) listOf(Requirement.API_KEY) else emptyList())
        val count = args.int("count")?.coerceIn(1, 4)
        val job = svc.images.start(prompt, count, args.str("style"), args.str("aspect", "shape", "ratio", "orientation"))
        return Outcome(true,
            "Image generation started (job $job). It takes a little while; the picture appears in the chat when ready. Do not describe the picture — you have not seen it.",
            T.reply("On it — I'm creating that image now.", "image", Card("generating", "Creating image", prompt.take(120), "image", fields = mapOf("job" to job)), extras = mapOf("job" to job)), verified = false)
    }
}

// ===================================================================================================== jarvis
class JarvisTool(private val lookup: (String) -> Tool?) : Tool {
    override val spec = ToolSpec(
        "jarvis",
        "Control JARVIS itself: goto_page (chat, library, reminders, notes, settings, permissions, capabilities, island, help); mic_on/mic_off (background listening for the wake word); " +
            "mute_voice/unmute_voice; ai_mode (auto|cloud|local); show_widget (music, reminders, system, weather).",
        mapOf("action" to T.p("goto_page | mic_on | mic_off | mute_voice | unmute_voice | ai_mode | show_widget"), "value" to T.p("Page, mode or widget name")),
        listOf("action"), Capability.ASSISTANT_SETTINGS, LockClass.ADMIN,
    )

    override suspend fun execute(args: Args, ctx: ToolContext): Outcome {
        val svc = ctx.services
        val act = args.str("action", default = "goto_page").lowercase().replace(' ', '_')
        val value = args.str("value", "page", "mode", "widget").lowercase()
        when (act) {
            "goto_page", "open_page", "show_page" -> {
                val page = PAGE_ALIASES[value] ?: value
                if (page !in svc.control.pages()) return T.fail("Pages: ${svc.control.pages().joinToString(", ").ifEmpty { "not available from here" }}.", "layout")
                val r = svc.control.openPage(page)
                return if (r.ok) Outcome(true, "Opened the $page screen.", T.reply("Opening $page.", "layout", extras = mapOf("goto" to page)), verified = r.verified) else T.failed(r, "I couldn't open that screen.", "layout")
            }
            "mic_on", "mic_off" -> {
                val on = act == "mic_on"
                val r = svc.control.setBackgroundListening(on)
                if (!r.ok) return T.failed(r, "I couldn't change listening.", "mic")
                val t = r.message.ifBlank { if (on) "Background listening for the wake word is on." else "Background listening is off." }
                return Outcome(true, t, T.reply(t, if (on) "mic" else "mic-off"), verified = r.verified)
            }
            "mute_voice", "unmute_voice" -> {
                val mute = act == "mute_voice"
                svc.settings.setFlag(com.jarvis.core.settings.Keys.VOICE_MUTED, mute)
                if (mute) svc.speaker.stop()
                val t = if (mute) "Voice replies muted." else "Voice replies on."
                return Outcome(true, t, T.reply(t, if (mute) "volume-x" else "volume-2"), verified = svc.settings.flag(com.jarvis.core.settings.Keys.VOICE_MUTED, false) == mute)
            }
            "ai_mode" -> {
                val mode = mapOf("local" to "local", "offline" to "local", "cloud" to "cloud", "online" to "cloud", "auto" to "auto", "automatic" to "auto")[value]
                    ?: return T.fail("AI mode can be auto, cloud or local.", "cpu")
                svc.settings.set(com.jarvis.core.settings.Keys.AI_MODE, mode)
                val ok = svc.settings.get(com.jarvis.core.settings.Keys.AI_MODE) == mode
                return if (ok) Outcome(true, "AI mode is now $mode.", T.reply("AI mode: $mode.", "cpu"), verified = true) else T.fail("I couldn't save the AI mode.", "cpu")
            }
            "show_widget" -> {
                val (tool, a) = when (value) {
                    "music", "song", "player" -> "media" to mapOf("action" to "now_playing")
                    "reminders", "calendar" -> "reminder" to mapOf("action" to "list", "day" to "all")
                    "system", "hardware", "battery" -> "system" to mapOf("action" to "stats")
                    "weather" -> "weather" to emptyMap()
                    else -> return T.fail("Widgets: music, reminders, system, weather.", "layout")
                }
                val t = lookup(tool) ?: return T.fail("That widget isn't available.", "layout")
                return t.execute(Args(a), ctx)
            }
        }
        return T.fail("I can open a screen, switch background listening or my voice, change the AI mode, or show a widget.", "settings")
    }

    companion object {
        val PAGE_ALIASES = mapOf(
            "home" to "chat", "main" to "chat", "files" to "library", "documents" to "library", "reminder" to "reminders", "note" to "notes",
            "permission" to "permissions", "commands" to "capabilities", "skills" to "capabilities", "what can you do" to "capabilities",
            "customize" to "settings", "customise" to "settings", "preferences" to "settings", "dynamic island" to "island",
        )
    }
}
