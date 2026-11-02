package com.jarvis.assistant.platform

import android.app.NotificationManager
import android.app.SearchManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.view.KeyEvent
import com.jarvis.core.model.Requirement
import com.jarvis.core.ports.ActionResult
import com.jarvis.core.ports.AppLauncher
import com.jarvis.core.ports.AppMatch
import com.jarvis.core.ports.MediaAction
import com.jarvis.core.ports.MediaGateway
import com.jarvis.core.ports.PlayState
import com.jarvis.core.ports.TrackInfo
import com.jarvis.core.util.JarvisLog

/**
 * What is playing, transport controls, and "play X in <app>".
 *  - Reading other apps' media sessions needs notification access (Android ties it to a notification-listener component), so without it [nowPlaying] is null and the
 *    controls fall back to a media KEY event (works, but Android does not tell us whether it took effect -> never verified).
 *  - With a session, a control is re-read ~0.5 s later and `verified` is true only if the playback state really changed.
 *  - playFromSearch asks the app through the standard voice-search intent; Android never says whether the app then started playing.
 */
class AndroidMediaGateway(
    context: Context,
    private val starter: ActivityStarter,
    private val apps: AppLauncher,
    private val log: JarvisLog = JarvisLog.None,
) : MediaGateway {
    private val ctx = context.applicationContext
    private val sessions: MediaSessionManager = ctx.getSystemService(MediaSessionManager::class.java)
    private val audio: AudioManager = ctx.getSystemService(AudioManager::class.java)
    private val listener = ComponentName(ctx.packageName, PermissionHelper.NOTIFICATION_LISTENER_CLASS)

    private fun hasAccess() = ctx.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(listener)

    private fun controllers(): List<MediaController> = try {
        if (!hasAccess()) emptyList() else sessions.getActiveSessions(listener)
    } catch (e: SecurityException) { emptyList() } catch (e: Exception) { log.w("Media", "sessions unavailable (${e.javaClass.simpleName})"); emptyList() }

    private fun best(): MediaController? {
        val all = controllers()
        return all.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: all.firstOrNull { it.metadata != null } ?: all.firstOrNull()
    }

    private fun stateOf(s: Int) = when (s) {
        PlaybackState.STATE_PLAYING -> PlayState.PLAYING
        PlaybackState.STATE_PAUSED -> PlayState.PAUSED
        PlaybackState.STATE_STOPPED, PlaybackState.STATE_NONE -> PlayState.STOPPED
        PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING -> PlayState.BUFFERING
        else -> PlayState.UNKNOWN
    }

    private fun label(pkg: String) = try { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString() } catch (_: Exception) { pkg }

    override fun nowPlaying(): TrackInfo? {
        val c = best() ?: return null
        val md = c.metadata
        val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        if (title.isBlank() && c.playbackState?.state != PlaybackState.STATE_PLAYING) return null
        val st = c.playbackState
        return TrackInfo(
            title = title.ifBlank { "Unknown title" },
            artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: "",
            album = md?.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: "",
            appLabel = label(c.packageName), packageName = c.packageName,
            state = stateOf(st?.state ?: PlaybackState.STATE_NONE),
            positionMs = st?.position ?: -1, durationMs = md?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.takeIf { it > 0 } ?: -1,
        )
    }

    private fun key(code: Int): Boolean = try {
        val now = SystemClock.uptimeMillis()
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0))
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0))
        true
    } catch (e: Exception) { false }

    override fun control(action: MediaAction): ActionResult {
        val c = best()
        val word = when (action) {
            MediaAction.PLAY -> "play"; MediaAction.PAUSE -> "pause"; MediaAction.TOGGLE -> "play/pause"
            MediaAction.NEXT -> "skip to the next track"; MediaAction.PREVIOUS -> "go back a track"; MediaAction.STOP -> "stop"
        }
        if (c == null) {
            val code = when (action) {
                MediaAction.PLAY -> KeyEvent.KEYCODE_MEDIA_PLAY; MediaAction.PAUSE -> KeyEvent.KEYCODE_MEDIA_PAUSE; MediaAction.TOGGLE -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                MediaAction.NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT; MediaAction.PREVIOUS -> KeyEvent.KEYCODE_MEDIA_PREVIOUS; MediaAction.STOP -> KeyEvent.KEYCODE_MEDIA_STOP
            }
            val sent = key(code)
            return if (sent) ActionResult(true, "Sent the $word media key. Android doesn't say whether an app reacted" +
                (if (!hasAccess()) " — with notification access I could check." else "."), if (!hasAccess()) listOf(Requirement.NOTIFICATION_LISTENER) else emptyList(), verified = false)
            else ActionResult.fail("I couldn't send the media key.")
        }
        val before = c.playbackState?.state
        val t = c.transportControls
        try {
            when (action) {
                MediaAction.PLAY -> t.play(); MediaAction.PAUSE -> t.pause(); MediaAction.NEXT -> t.skipToNext(); MediaAction.PREVIOUS -> t.skipToPrevious(); MediaAction.STOP -> t.stop()
                MediaAction.TOGGLE -> if (before == PlaybackState.STATE_PLAYING) t.pause() else t.play()
            }
        } catch (e: Exception) { return ActionResult.fail("${label(c.packageName)} didn't accept the command (${e.javaClass.simpleName}).") }
        val app = label(c.packageName)
        if (action == MediaAction.NEXT || action == MediaAction.PREVIOUS) {
            val title0 = c.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
            val changed = waitFor { controllers().firstOrNull { it.packageName == c.packageName }?.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) != title0 }
            return if (changed) ActionResult.ok("Asked $app to $word, and the track changed.", verified = true)
            else ActionResult(true, "Asked $app to $word; the track didn't visibly change (it may be the first/last one).", verified = false)
        }
        val wantPlaying = when (action) { MediaAction.PLAY -> true; MediaAction.PAUSE, MediaAction.STOP -> false; else -> before != PlaybackState.STATE_PLAYING }
        val ok = waitFor { (controllers().firstOrNull { it.packageName == c.packageName }?.playbackState?.state == PlaybackState.STATE_PLAYING) == wantPlaying }
        return if (ok) ActionResult.ok("$app: ${if (wantPlaying) "playing" else "paused"}.", verified = true)
        else ActionResult(false, "I asked $app to $word, but it still reports ${if (wantPlaying) "not playing" else "playing"}.", verified = true)
    }

    private fun waitFor(timeoutMs: Long = 700, cond: () -> Boolean): Boolean {
        val end = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < end) { if (cond()) return true; Thread.sleep(80) }
        return cond()
    }

    override fun playFromSearch(query: String, app: AppMatch?): ActionResult {
        val q = query.trim()
        if (q.isEmpty()) return ActionResult.fail("What should I play?")
        val voice = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*").putExtra(SearchManager.QUERY, q)
        if (app != null) voice.setPackage(app.packageName)
        if (starter.resolves(voice)) {
            val r = starter.start(voice, app?.label ?: "a music app", "Asked ${app?.label ?: "your music app"} to play “$q”. Android doesn't confirm that playback started.")
            if (r.ok) return r.copy(verified = false)
            if (r.missing.isNotEmpty()) return r      // blocked in the background: the tap-to-open notification was offered
        }
        // No app handles the voice-search intent: open a search page instead (and say that it is only a search).
        val pkg = app?.packageName.orEmpty()
        val url = when {
            pkg.contains("spotify") -> "https://open.spotify.com/search/" + Uri.encode(q)
            else -> "https://www.youtube.com/results?search_query=" + Uri.encode(q)
        }
        val r = apps.openUrl(url, null)
        return if (r.ok) ActionResult(true, "That app can't be told to play by voice search, so I opened a search for “$q”. Pick a result to play it.", verified = false)
        else r
    }
}
