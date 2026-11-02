package com.jarvis.assistant.island

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.SystemClock
import android.view.Gravity
import android.view.WindowManager
import com.jarvis.assistant.R
import com.jarvis.assistant.di.AppContainer
import com.jarvis.assistant.platform.AppForeground
import com.jarvis.assistant.platform.Intents
import com.jarvis.assistant.service.AssistantBus
import com.jarvis.assistant.service.ShowState
import com.jarvis.assistant.ui.AssistantActivity
import com.jarvis.assistant.ui.kit.MainThread
import com.jarvis.assistant.ui.kit.Themes
import com.jarvis.core.island.CueKind
import com.jarvis.core.island.IslandConfig
import com.jarvis.core.island.IslandGeometry
import com.jarvis.core.island.IslandInput
import com.jarvis.core.island.IslandInputs
import com.jarvis.core.island.IslandMode
import com.jarvis.core.island.IslandPlacement
import com.jarvis.core.island.IslandPosition
import com.jarvis.core.island.IslandReducer
import com.jarvis.core.island.IslandUiState
import com.jarvis.core.island.RectPx
import com.jarvis.core.settings.Keys
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Connects the engine and the voice service to the overlay window: it feeds their events through the (unit-tested) [IslandReducer], places the result with
 * [IslandGeometry] using the cutout information from [CutoutDetector], and adds / updates / removes the [IslandView] window. When nothing is shown the window is
 * REMOVED, so an invisible overlay can never swallow touches. Must run on the main thread.
 *
 * [windowContext] should be a window context for TYPE_APPLICATION_OVERLAY on API 30+ (so metrics follow folding); on API 29 the service itself.
 */
class IslandController(private val service: Context, private val c: AppContainer, private val windowContext: Context) : IslandView.Listener {
    private val wm: WindowManager = windowContext.getSystemService(WindowManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + MainThread + c.appLog.coroutineHandler)
    private val reducer = IslandReducer(
        collapseMs = { c.settings.int(Keys.ISLAND_COLLAPSE_SECONDS, 4).coerceIn(2, 20) * 1000L },
        alwaysVisible = { c.settings.flag(Keys.ISLAND_ALWAYS_VISIBLE, false) },
        lockedText = service.getString(R.string.svc_locked_reply),
        lockedReminderText = service.getString(R.string.notif_reminder_title),
    )

    private var state = IslandUiState()
    private var view: IslandView? = null
    private var lp: WindowManager.LayoutParams? = null
    private var winRect: RectPx? = null
    private var display: DisplayInfo? = null
    private var tickJob: Job? = null
    private var shrinkJob: Job? = null
    private var moveJob: Job? = null
    private var dragStartX = 0
    private var dragStartY = 0
    private var lastExpanded = false

    // ------------------------------------------------------------------------------------------------ life cycle
    fun start() {
        scope.launch { c.engine.state.collect { dispatch(IslandInput.Engine(it)) } }
        scope.launch { c.engine.events.collect { dispatch(IslandInput.Event(it)) } }
        scope.launch {
            AssistantBus.requests.collect { r ->
                val kind = when (r.state) { ShowState.LISTENING -> CueKind.LISTENING; ShowState.HEARD -> CueKind.HEARD; ShowState.DONE -> CueKind.DONE; ShowState.FAILED -> CueKind.FAILED }
                dispatch(IslandInput.Cue(kind, r.text, r.locked))
            }
        }
        scope.launch { AssistantBus.levels.collect { dispatch(IslandInput.Level(it)) } }
    }

    fun stop() {
        scope.cancel()
        detach()
        c.appLog.i("Island", "controller stopped")
    }

    /** The display, window insets or settings changed: read everything again and re-place. */
    fun relayout() {
        display = null
        render(animate = false)
    }

    /** Shows a short sample so the user can judge position and size. */
    fun preview() = dispatch(IslandInput.Preview(service.getString(R.string.island_preview_text)))

    // ------------------------------------------------------------------------------------------------ state
    private fun dispatch(input: IslandInput) {
        val lock = c.lockState.read()
        var s = if (state.locked != lock.protectPersonalData && input !is IslandInput.Cue) state.copy(locked = lock.protectPersonalData) else state
        s = reducer.reduce(s, input, SystemClock.elapsedRealtime())
        if (input is IslandInput.Level && s.copy(level = state.level) == state) { // only the loudness moved: redraw, don't re-place
            state = s
            view?.updateState(s)
            return
        }
        val changed = s != state
        val modeChanged = s.mode != state.mode || s.expanded != state.expanded
        state = s
        if (changed) render(animate = true, relayoutWindow = modeChanged)
        ensureTicker()
    }

    private fun ensureTicker() {
        if (!reducer.needsTick(state) || tickJob?.isActive == true) return
        tickJob = scope.launch {
            while (reducer.needsTick(state)) { delay(500); dispatch(IslandInput.Tick) }
        }
    }

    // ------------------------------------------------------------------------------------------------ window
    private fun config(): IslandConfig = IslandConfig(
        IslandPosition.fromSetting(c.settings.get(Keys.ISLAND_POSITION)),
        c.settings.int(Keys.ISLAND_OFFSET_X, 0).toFloat(), c.settings.int(Keys.ISLAND_OFFSET_Y, 0).toFloat(),
        c.settings.int(Keys.ISLAND_SIZE, 100),
    )

    private fun place(expanded: Boolean, cfg: IslandConfig = config()): IslandPlacement {
        val info = display ?: CutoutDetector.read(windowContext, view).also { display = it }
        return IslandGeometry.place(IslandInputs(info.width, info.height, info.density, info.rotation, info.statusBar, info.insets, info.cutouts, cfg, expanded))
    }

    private fun render(animate: Boolean, relayoutWindow: Boolean = true) {
        val s = state
        if (s.mode == IslandMode.HIDDEN) { detach(); return }
        val p = place(s.expanded)
        val v = view ?: attach(p)
        if (v == null) return
        val shrinking = lastExpanded && !s.expanded
        lastExpanded = s.expanded
        val win = winRect ?: p.hit
        if (shrinking) {
            // keep the large window until the collapse animation has finished, then shrink it
            v.bind(s, p, win, animate)
            shrinkJob?.cancel()
            shrinkJob = scope.launch { delay(260); applyWindow(p); view?.bind(state, p, p.hit, animate = false) }
        } else {
            shrinkJob?.cancel()
            if (relayoutWindow || winRect != p.hit) applyWindow(p)
            v.bind(s, p, p.hit, animate)
        }
    }

    private fun params(p: IslandPlacement): WindowManager.LayoutParams = WindowManager.LayoutParams(
        p.hit.width, p.hit.height, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = p.hit.left
        y = p.hit.top
        layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30) WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        title = "JARVIS island"
    }

    private fun attach(p: IslandPlacement): IslandView? {
        val accent = Themes.palette(service, c.settings).accent
        val v = IslandView(windowContext, accent)
        v.listener = this
        val params = params(p)
        return try {
            wm.addView(v, params)
            view = v; lp = params; winRect = p.hit
            AppForeground.overlayShown = true
            // On API 29 (and right after adding) the cutout is only known once the view has insets: re-read when they arrive, and only re-place if they differ.
            v.setOnApplyWindowInsetsListener { _, insets ->
                v.post {
                    val fresh = CutoutDetector.read(windowContext, v)
                    if (fresh != display) { display = fresh; render(animate = false) }
                }
                insets
            }
            v
        } catch (e: Exception) {
            // BadTokenException / SecurityException: the permission was taken away, or the display went away
            c.appLog.w("Island", "could not add the overlay (${e.javaClass.simpleName})")
            state = IslandUiState()
            null
        }
    }

    private fun applyWindow(p: IslandPlacement) {
        val v = view ?: return
        val params = lp ?: return
        params.x = p.hit.left; params.y = p.hit.top; params.width = p.hit.width; params.height = p.hit.height
        try { wm.updateViewLayout(v, params); winRect = p.hit } catch (e: Exception) { c.appLog.w("Island", "could not move the overlay (${e.javaClass.simpleName})") }
    }

    private fun detach() {
        shrinkJob?.cancel(); moveJob?.cancel()
        val v = view ?: run { AppForeground.overlayShown = false; return }
        view = null; lp = null; winRect = null; lastExpanded = false
        try { wm.removeViewImmediate(v) } catch (_: Exception) { }
        AppForeground.overlayShown = false
    }

    // ------------------------------------------------------------------------------------------------ IslandView.Listener
    override fun onTap() = dispatch(IslandInput.Tap)

    override fun onTalk() { openChat(listen = true); dispatch(IslandInput.Close) }

    override fun onType() { openChat(listen = false); dispatch(IslandInput.Close) }

    override fun onClose() = dispatch(IslandInput.Close)

    private fun openChat(listen: Boolean) {
        // The overlay is a visible window, which is what lets Android 10+ start an activity from here.
        val i = Intent().setClassName(service.packageName, Intents.ASSISTANT_ACTIVITY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(AssistantActivity.EXTRA_LISTEN, listen)
        try { service.startActivity(i) } catch (e: Exception) { c.appLog.w("Island", "could not open the chat (${e.javaClass.simpleName})") }
    }

    override fun onMoveStart() {
        val params = lp ?: return
        dragStartX = params.x; dragStartY = params.y
        if (state.expanded) dispatch(IslandInput.Tap) // drag the small pill, not the card
        moveJob?.cancel()
        moveJob = scope.launch { delay(12_000); view?.endMoveMode() } // armed (Move button) but never touched: give up
    }

    override fun onDrag(dx: Int, dy: Int) {
        val v = view ?: return
        val params = lp ?: return
        val info = display ?: return
        params.x = (dragStartX + dx).coerceIn(0, maxOf(info.width - params.width, 0))
        params.y = (dragStartY + dy).coerceIn(0, maxOf(info.height - params.height, 0))
        try { wm.updateViewLayout(v, params) } catch (_: Exception) { }
        winRect = RectPx(params.x, params.y, params.x + params.width, params.y + params.height)
    }

    override fun onDragEnd() {
        moveJob?.cancel()
        val params = lp ?: return
        val info = display ?: return
        // offsets are stored relative to where the geometry would have put the island with no offsets
        val base = place(expanded = false, cfg = config().copy(offsetXdp = 0f, offsetYdp = 0f))
        val ox = ((params.x - base.hit.left) / info.density).roundToInt()
        val oy = ((params.y - base.hit.top) / info.density).roundToInt()
        c.settings.set(Keys.ISLAND_OFFSET_X, ox.toString())
        c.settings.set(Keys.ISLAND_OFFSET_Y, oy.toString())
        c.appLog.i("Island", "moved by the user to offset ($ox, $oy) dp")
        view?.endMoveMode()
        render(animate = false)
    }
}
