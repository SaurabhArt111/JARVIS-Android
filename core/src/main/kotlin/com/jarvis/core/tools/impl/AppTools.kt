package com.jarvis.core.tools.impl

import com.jarvis.core.model.ActionType
import com.jarvis.core.model.AssistantReply
import com.jarvis.core.model.Card
import com.jarvis.core.model.Outcome
import com.jarvis.core.model.ReplyKind
import com.jarvis.core.model.Requirement
import com.jarvis.core.model.UiAction
import com.jarvis.core.ports.AppMatch
import com.jarvis.core.ports.MediaAction
import com.jarvis.core.ports.PlayState
import com.jarvis.core.ports.Services
import com.jarvis.core.ports.TrackInfo
import com.jarvis.core.safety.Safety
import com.jarvis.core.settings.Keys
import com.jarvis.core.tools.Args
import com.jarvis.core.tools.Capability
import com.jarvis.core.tools.LockClass
import com.jarvis.core.tools.Tool
import com.jarvis.core.tools.ToolContext
import com.jarvis.core.tools.ToolSpec
import kotlinx.coroutines.delay
import java.net.URLEncoder

private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

/** Well-known websites JARVIS may open in a browser when no installed app with that name exists. */
internal val KNOWN_SITES = mapOf(
    "youtube" to "https://www.youtube.com", "google" to "https://www.google.com", "gmail" to "https://mail.google.com",
    "google maps" to "https://maps.google.com", "maps" to "https://maps.google.com", "google drive" to "https://drive.google.com",
    "drive" to "https://drive.google.com", "google meet" to "https://meet.google.com", "meet" to "https://meet.google.com",
    "wikipedia" to "https://www.wikipedia.org", "github" to "https://github.com", "reddit" to "https://www.reddit.com",
    "netflix" to "https://www.netflix.com", "amazon" to "https://www.amazon.com", "twitter" to "https://x.com", "x" to "https://x.com",
    "facebook" to "https://www.facebook.com", "instagram" to "https://www.instagram.com", "linkedin" to "https://www.linkedin.com",
    "whatsapp web" to "https://web.whatsapp.com", "chatgpt" to "https://chatgpt.com", "stack overflow" to "https://stackoverflow.com",
)

private val SETTINGS_PAGE = Regex(
    "^(?:the |my )?(wi-?fi|bluetooth|display|sound|volume|battery|apps|notifications?|location|accessibility|date(?: and time)?|time|language|storage|" +
        "privacy|network|internet|airplane(?: mode)?|developer|security|data usage|nfc|default apps)\\s+settings?$|^settings$",
    RegexOption.IGNORE_CASE,
)

internal fun settingsPageKey(raw: String): String {
    val k = raw.lowercase().trim()
    return when {
        k.startsWith("wi") -> "wifi"
        k.startsWith("notification") -> "notifications"
        k.startsWith("date") || k == "time" -> "date"
        k.startsWith("airplane") -> "airplane"
        k == "internet" || k == "network" || k == "data usage" -> "network"
        k == "volume" -> "sound"
        k == "settings" -> "main"
        else -> k
    }
}

/** Resolves a browser the user named (or the configured preferred one). Returns (browser, problem). */
internal fun resolveBrowser(services: Services, named: String): Pair<AppMatch?, String?> {
    val browsers = services.apps.browsers()
    val wanted = named.ifBlank { services.settings.get(Keys.PREFERRED_BROWSER) }
    if (wanted.isBlank()) return null to null
    val hit = services.apps.resolve(wanted).firstOrNull { b -> browsers.any { it.packageName == b.packageName } }
        ?: browsers.firstOrNull { it.label.equals(wanted, true) || it.label.lowercase().contains(wanted.lowercase()) }
    return if (hit != null) hit to null
    else if (named.isNotBlank()) null to "$named isn't installed as a browser on this phone."
    else null to null // a stale preferred browser silently falls back to the system default
}

// =========================================================================================================== open
class OpenTool : Tool {
    override val spec = ToolSpec(
        "open",
        "Open an app, a website or a Settings page on the phone. Use in_app to open a website inside a specific browser (for example youtube in brave).",
        mapOf("target" to T.p("App name, website (youtube.com) or settings page (wifi settings)"), "in_app" to T.p("Browser to open it with (optional)")),
        listOf("target"), Capability.APP_LAUNCH, LockClass.ADMIN,
    )

    override suspend fun execute(args: Args, ctx: ToolContext): Outcome {
        val svc = ctx.services
        val target = args.str("target", "app", "name", "url", "path")
        if (target.isEmpty()) return T.fail("What should I open?", "external-link")
        Safety.protectedPathReason(target)?.let { return Outcome.blocked(it) }
        val inApp = args.str("in_app", "in", "with", "browser")

        // a link
        if (T.URL_SCHEME.containsMatchIn(target) || T.DOMAIN.matches(target.trim())) {
            val url = T.safeWebUrl(target) ?: return T.fail("I only open web links (http or https), not “${target.take(40)}”.", "shield")
            return openUrl(svc, url, url.substringAfter("://").removePrefix("www.").substringBefore('/'), inApp)
        }
        // a settings page
        SETTINGS_PAGE.matchEntire(target.trim())?.let { m ->
            val page = settingsPageKey(m.groupValues[1].ifBlank { "settings" })
            val r = svc.apps.openSettingsPage(page)
            return Outcome(r.ok, if (r.ok) "Opened the ${page.replace('_', ' ')} settings screen." else r.message,
                if (r.ok) T.reply("Opening $page settings.", "settings") else AssistantReply.error(r.message.ifBlank { "I couldn't open that settings page." }), verified = r.verified)
        }
        val key = target.lowercase().trim()
        // asked for a site inside a browser ("youtube in brave")
        if (inApp.isNotEmpty()) {
            val site = KNOWN_SITES[key]
            return if (site != null) openUrl(svc, site, key, inApp)
            else T.fail("I can open websites inside a browser, but “$target” isn't a website I recognise. Give me its address, like example.com.", "globe")
        }
        // an installed app
        val matches = svc.apps.resolve(target)
        val best = matches.firstOrNull()
        if (best != null) {
            Safety.protectedPackageReason(best.packageName, OWN_PACKAGE)?.let { return Outcome.blocked(it) }
            val r = svc.apps.launch(best)
            return if (r.ok) Outcome(true, "Opening ${best.label}." + if (r.verified) " (The app's launch screen was found.)" else "",
                T.reply("Opening ${best.label}.", "external-link", Card("info", best.label, "Opening", "external-link")), verified = r.verified)
            else T.failed(r, "I couldn't open ${best.label}.")
        }
        // not installed -> a website if it is a well-known one
        KNOWN_SITES[key]?.let { site ->
            val o = openUrl(svc, site, key, "")
            return if (o.ok) o.copy(note = "No app called “$target” is installed, so it was opened as a website. ${o.note}") else o
        }
        return T.fail("I couldn't find an app called “$target” on this phone. Is it installed?", "search")
    }

    private fun openUrl(svc: Services, url: String, name: String, inApp: String): Outcome {
        val (browser, problem) = resolveBrowser(svc, inApp)
        if (problem != null) return T.fail(problem, "globe")
        val r = svc.apps.openUrl(url, browser)
        if (!r.ok) return T.failed(r, "I couldn't open $name.")
        val where = browser?.let { " in ${it.label}" } ?: ""
        return Outcome(true, "Opening $name$where in the browser.", T.reply("Opening $name$where.", "globe", Card("browse", name, "Opening$where", "globe", url = url)), verified = r.verified)
    }
}

// =========================================================================================================== play
class PlayTool(private val settleMs: Long = 2000L) : Tool {
    override val spec = ToolSpec(
        "play",
        "Start playing a song, artist or video in a music/video app. Use where to name the app (spotify, youtube, youtube music ...).",
        mapOf("query" to T.p("Song, artist or video"), "where" to T.p("App to play it in (optional)")),
        listOf("query"), Capability.MEDIA, LockClass.ADMIN,
    )

    override suspend fun execute(args: Args, ctx: ToolContext): Outcome {
        val svc = ctx.services
        val q = args.str("query", "song", "what")
        if (q.isEmpty()) return T.fail("What should I play?", "play-circle")
        val where = args.str("where", "on", "app")
        var app: AppMatch? = null
        if (where.isNotEmpty()) {
            app = svc.apps.resolve(where).firstOrNull()
            if (app == null) return T.fail("I couldn't find $where on this phone, so I didn't start anything.", "play-circle")
        }
        val r = svc.media.playFromSearch(q, app)
        if (!r.ok) return T.failed(r, "I couldn't ask a music app to play that.", "play-circle")
        if (settleMs > 0) delay(settleMs)
        val t = svc.media.nowPlaying()
        val appName = app?.label ?: "your music app"
        if (t != null && t.state == PlayState.PLAYING) {
            val line = "Now playing ${t.title}${if (t.artist.isNotBlank()) " by ${t.artist}" else ""} in ${t.appLabel.ifBlank { appName }}."
            return Outcome(true, line, T.reply(line, "music", Card("music", t.title, t.artist, "music", fields = mapOf("app" to t.appLabel, "state" to t.state.name))), verified = true)
        }
        val msg = "I asked $appName to play “$q”. I can't confirm it started playing — check the app if nothing is audible."
        return Outcome(true, msg, T.reply("Asked $appName to play $q.", "play-circle"), verified = false)
    }
}

// =========================================================================================================== media
class MediaTool(private val settleMs: Long = 500L) : Tool {
    override val spec = ToolSpec(
        "media",
        "Control or identify what is playing: now_playing (use this to answer 'which song is playing'), pause, resume, toggle, next, previous, stop.",
        mapOf("action" to T.p("now_playing | pause | resume | toggle | next | previous | stop", enum = listOf("now_playing", "pause", "resume", "toggle", "next", "previous", "stop"))),
        listOf("action"), Capability.MEDIA, LockClass.OPEN,
        isAction = true, needs = emptyList(),
    )

    private fun describe(t: TrackInfo) =
        "${t.title}${if (t.artist.isNotBlank()) " by ${t.artist}" else ""} in ${t.appLabel} (${t.state.name.lowercase()})"

    private fun musicCard(t: TrackInfo) =
        Card("music", t.title, t.artist, "music", fields = mapOf("app" to t.appLabel, "state" to t.state.name, "position" to t.positionMs.toString(), "duration" to t.durationMs.toString()))

    override suspend fun execute(args: Args, ctx: ToolContext): Outcome {
        val svc = ctx.services
        val act = args.str("action", default = "now_playing").lowercase().replace(' ', '_')
        if (act in setOf("now_playing", "whats_playing", "current", "status", "identify")) {
            val t = svc.media.nowPlaying()
            if (t == null) {
                return if (!svc.permissions.isGranted(Requirement.NOTIFICATION_LISTENER))
                    T.fail("I can't see what's playing because notification access is off. I can only read other apps' media with that permission.", "music", listOf(Requirement.NOTIFICATION_LISTENER))
                else T.info("Nothing is playing right now (no app has a media session).", T.reply("Nothing is playing right now.", "music", kind = ReplyKind.INFO))
            }
            return T.info(describe(t) + if (t.album.isNotBlank()) " Album: ${t.album}." else "", T.reply(describe(t), "music", musicCard(t), extras = mapOf("widget" to "music")))
        }
        val action = when (act) {
            "pause" -> MediaAction.PAUSE
            "resume", "play" -> MediaAction.PLAY
            "toggle", "play_pause" -> MediaAction.TOGGLE
            "next", "skip" -> MediaAction.NEXT
            "previous", "back" -> MediaAction.PREVIOUS
            "stop" -> MediaAction.STOP
            else -> return T.fail("I can pause, resume, skip, go back or tell you what's playing — not “$act”.", "music")
        }
        val before = svc.media.nowPlaying()
        val r = svc.media.control(action)
        if (!r.ok) return T.failed(r, "I couldn't control the music.", "play-circle")
        if (settleMs > 0) delay(settleMs)
        val after = svc.media.nowPlaying()
        val verified = after != null && when (action) {
            MediaAction.PAUSE -> after.state == PlayState.PAUSED || after.state == PlayState.STOPPED
            MediaAction.PLAY -> after.state == PlayState.PLAYING
            MediaAction.STOP -> after.state != PlayState.PLAYING
            MediaAction.TOGGLE -> before != null && after.state != before.state
            MediaAction.NEXT, MediaAction.PREVIOUS -> before != null && (after.title != before.title)
        }
        val label = when (action) {
            MediaAction.NEXT -> "Skipped to the next track."
            MediaAction.PREVIOUS -> "Went back a track."
            MediaAction.PAUSE -> "Paused."
            MediaAction.STOP -> "Stopped."
            MediaAction.PLAY -> "Playing."
            MediaAction.TOGGLE -> "Done."
        }
        val text = if (verified) label else "I sent “$act” to the media player, but I couldn't confirm it took effect."
        val note = if (verified && after != null && (action == MediaAction.NEXT || action == MediaAction.PREVIOUS)) "$label Now: ${after.title}" else text
        return Outcome(true, note, T.reply(text, "play-circle", after?.takeIf { verified }?.let(::musicCard), extras = mapOf("widget" to "music")), verified = verified)
    }
}

// =========================================================================================================== search_in_browser
class SearchInBrowserTool : Tool {
    override val spec = ToolSpec(
        "search_in_browser",
        "Open a Google or YouTube search page in the browser for the user to look at (not for answering questions yourself).",
        mapOf("query" to T.p("What to search"), "site" to T.p("google | youtube", enum = listOf("google", "youtube"))),
        listOf("query"), Capability.APP_LAUNCH, LockClass.ADMIN,
    )

    override suspend fun execute(args: Args, ctx: ToolContext): Outcome {
        val svc = ctx.services
        val q = args.str("query")
        if (q.isEmpty()) return T.fail("What should I search for?", "search")
        val yt = args.str("site", default = "google").lowercase().contains("youtube")
        val url = if (yt) "https://www.youtube.com/results?search_query=${enc(q)}" else "https://www.google.com/search?q=${enc(q)}"
        val (browser, problem) = resolveBrowser(svc, args.str("in_app"))
        if (problem != null) return T.fail(problem, "globe")
        val r = svc.apps.openUrl(url, browser)
        if (!r.ok) return T.failed(r, "I couldn't open the search page.", "search")
        val name = if (yt) "YouTube" else "Google"
        return Outcome(true, "Opened a $name search for “$q”.", T.reply("Searching $name for $q.", "search", Card("browse", "$name search", q, "search", url = url),
            actions = listOf(UiAction("Open again", ActionType.OPEN_URL, mapOf("url" to url)))), verified = r.verified)
    }
}
