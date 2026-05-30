package com.tvcs.homematic

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.preference.PreferenceManager
import org.videolan.libvlc.util.VLCVideoLayout

/**
 * MultiCameraController
 *
 * Manages N CameraViewControllers simultaneously. Each controller owns its own
 * set of views (PlayerView + VLCVideoLayout + ImageView) that are ALL started
 * immediately and kept streaming in the background — even when not visible.
 * Only one slot is VISIBLE at a time; the rest are INVISIBLE (not GONE — they
 * must remain attached to the window for ExoPlayer / VLC surface rendering to
 * continue while hidden).
 *
 * ── Background buffering ──────────────────────────────────────────────────────
 * Because every slot runs continuously in the background, switching cameras is
 * effectively instant — the target slot is already live and buffered. There is
 * no reconnect delay when the user taps to the next camera.
 *
 * ── VLC surface handling ──────────────────────────────────────────────────────
 * VLC can render into an INVISIBLE SurfaceView without issues (the Surface stays
 * valid). However if the container was previously set to GONE (which destroys the
 * Surface), VLC must be restarted when the slot becomes visible again. The
 * controller avoids GONE for background slots to prevent this.
 *
 * ── Rotation ──────────────────────────────────────────────────────────────────
 * When rotationSec > 0 the controller automatically advances to the next slot
 * every [rotationSec] seconds. A tap on [tapOverlay] always advances manually
 * and resets the rotation timer.
 *
 * ── Single-camera mode ────────────────────────────────────────────────────────
 * When there is only one camera the controller behaves identically to the old
 * CameraViewController: no indicator dots, no rotation, tap-to-reload.
 */
@OptIn(UnstableApi::class)
class MultiCameraController(
    private val context:       Context,
    /** The outer FrameLayout that contains all camera slots and the overlay. */
    private val cameraPanel:   FrameLayout,
    private val statusLabel:   TextView,
    private val muteButton:    ImageButton?,
    /** Transparent view on top of everything — receives taps. */
    private val tapOverlay:    View,
    private val dotContainer:  android.widget.LinearLayout
) : DefaultLifecycleObserver {

    companion object {
        private const val TAG = "MultiCamCtrl"
        /** SharedPrefs key — remembers which slot was last visible across restarts / wakeups. */
        private const val KEY_LAST_ACTIVE_IDX = "multi_cam_last_active_idx"
    }

    private val prefs       = PreferenceManager.getDefaultSharedPreferences(context)
    private val mainHandler = Handler(Looper.getMainLooper())

    private data class Slot(
        val config:     CameraConfig,
        val container:  FrameLayout,
        val playerView: PlayerView,
        val vlcLayout:  VLCVideoLayout,
        val snapshot:   ImageView,
        val vc:         CameraViewController
    )

    private val slots      = mutableListOf<Slot>()
    private var activeIdx  = 0
    private var rotationSec = 0
    private var rotateJob: Runnable? = null
    private var started    = false

    /**
     * True once at least one slot has shown its first live frame.
     * Until then all slot containers stay INVISIBLE so the user never sees a
     * partial / wrong camera flash up on startup.
     */
    private var firstSlotShown = false

    // ── Public API ─────────────────────────────────────────────────────────

    fun attachToLifecycle(owner: LifecycleOwner) {
        owner.lifecycle.addObserver(this)
    }

    /**
     * Build slots from current config and start all controllers.
     * Safe to call multiple times — tears down existing slots first.
     */
    fun applyConfig() {
        teardown()
        val configs     = CameraConfigStore.load(prefs)
        rotationSec     = CameraConfigStore.loadRotationSec(prefs)
        firstSlotShown  = false
        fastestReadySlot = -1

        if (configs.isEmpty()) {
            cameraPanel.visibility = View.GONE
            return
        }
        cameraPanel.visibility = View.VISIBLE

        // Restore last-used slot index (clamped to current config size).
        val savedIdx  = prefs.getInt(KEY_LAST_ACTIVE_IDX, 0)
        activeIdx     = savedIdx.coerceIn(0, configs.lastIndex)

        configs.forEach { cfg ->
            val slot = buildSlot(cfg)
            slots.add(slot)
        }

        buildDots()
        installTapOverlay()

        // Register a first-live callback on every slot.
        // The FIRST slot that reports "live" (first frame from any engine) will
        // be presented — preferring the saved active index if it wins the race.
        // This avoids flashing WebCam2 before WebCam1's VLC has connected.
        slots.forEachIndexed { i, slot ->
            slot.vc.onFirstLive = { onSlotFirstLive(i) }
        }

        // All containers start GONE — onSlotFirstLive decides when to show.
        slots.forEach { it.container.visibility = View.GONE }
        updateDots()

        if (started) startAllSlots()
        if (rotationSec > 0 && slots.size > 1) scheduleRotation()
    }

    fun applyPrefsChange() {
        applyConfig()
    }

    /**
     * Clears the engine-skip state on all slots and restarts them from
     * the top of their engine stack. Called when the user taps the
     * "Reset player" button in the camera settings.
     *
     * Pass [cameraId] to reset only a specific camera, or null to reset all.
     */
    fun resetEngineSkip(cameraId: String? = null) {
        slots.forEach { slot ->
            if (cameraId == null || slot.config.id == cameraId) {
                slot.vc.resetEngineSkip()
            }
        }
    }

    /**
     * Check SharedPrefs for a pending reset request written by
     * CameraListFragment and apply it.  Call from MainActivity.onResume().
     */
    fun checkAndApplyPendingReset() {
        val key = "camera_reset_engine_skip_id"
        val tsKey = "camera_reset_engine_skip_ts"
        val id = prefs.getString(key, null) ?: return
        val ts = prefs.getLong(tsKey, 0L)
        // Only act on requests from the last 60 seconds
        if (System.currentTimeMillis() - ts > 60_000L) {
            prefs.edit().remove(key).remove(tsKey).apply()
            return
        }
        prefs.edit().remove(key).remove(tsKey).apply()
        resetEngineSkip(id.ifBlank { null })
    }

    fun isEnabled(): Boolean {
        val configs = CameraConfigStore.load(prefs)
        return configs.isNotEmpty() ||
               prefs.getBoolean(PreferenceKeys.CAMERA_ENABLED, false)
    }

    var onMotionDetected: (() -> Unit)? = null
        set(value) {
            field = value
            slots.forEach { it.vc.onMotionDetected = value }
        }

    fun toggleMute() { slots.getOrNull(activeIdx)?.vc?.toggleMute() }

    fun applyMotionPrefs() { slots.forEach { it.vc.applyMotionPrefs() } }

    // ── Lifecycle ───────────────────────────────────────────────────────────

    override fun onStart(owner: LifecycleOwner) {
        started = true
        if (slots.isEmpty()) {
            applyConfig()
            return
        }
        if (streamsSuspended) {
            // Streams kept alive across screen-off — just re-attach views.
            // showSlot() handles attachExoToPlayerView() / reattachVlcViews() internally.
            streamsSuspended = false
            slots.forEachIndexed { i, slot ->
                slot.vc.isActiveSlot = (i == activeIdx)
            }
            showSlot(activeIdx)
            firstSlotShown = true
            Log.i(TAG, "onStart: streams were suspended — instant re-attach, no reconnect")
        } else {
            // Normal start (first launch or streams were fully stopped)
            startAllSlots()
        }
        if (rotationSec > 0 && slots.size > 1) scheduleRotation()
    }

    override fun onStop(owner: LifecycleOwner) {
        cancelRotation()
        preferredSlotWaitJob?.let { mainHandler.removeCallbacks(it) }
        preferredSlotWaitJob = null
        firstSlotShown   = false
        fastestReadySlot = -1

        // Keep streams alive for instant wakeup when at least one slot is live.
        // Only detach views; network connections and decoders stay running.
        val anyLive = slots.any { it.vc.isExoActive() || it.vc.isVlcActive() }
        if (anyLive) {
            slots.forEach { slot ->
                slot.vc.isActiveSlot = false
                when {
                    slot.vc.isExoActive() -> slot.vc.detachExoToBackground()
                    slot.vc.isVlcActive() -> slot.vc.detachVlcViews()
                }
                slot.container.visibility = View.GONE
            }
            streamsSuspended = true
            Log.i(TAG, "onStop: streams detached (still alive) — ready for instant wake")
        } else {
            slots.forEach { it.vc.stop() }
            streamsSuspended = false
        }
        started = false
    }

    override fun onDestroy(owner: LifecycleOwner) {
        teardown()
    }

    // ── First-live arbitration ──────────────────────────────────────────────

    /**
     * Hard timeout before giving up on the preferred slot and showing
     * whichever slot is already live.  Should be longer than the slowest
     * expected engine startup (VLC RTSP ≈ 5–8 s on a local network).
     */
    private val PREFERRED_SLOT_TIMEOUT_MS = 8_000L   // LAN: schnelles Failover (war 30 000)
    private var preferredSlotWaitJob: Runnable? = null

    /**
     * Index of the fastest non-preferred slot that reported live during the
     * grace period.  Used as fallback if the preferred slot times out.
     */
    private var fastestReadySlot: Int = -1

    /**
     * True when [onStop] kept streams alive (detached but not stopped) so that
     * the next [onStart] (screen-on / app-resume) can show frames instantly
     * without reconnecting.
     */
    private var streamsSuspended = false

    /**
     * Called by vc.onFirstLive the first time a slot's engine renders a frame.
     *
     * Rules:
     *  • Preferred slot live  → show immediately, cancel any pending timeout.
     *  • Other slot live first → remember it as fallback, start the timeout
     *    once (idempotent), but DO NOT show it yet.  The screen stays dark
     *    rather than flash the wrong camera.
     *  • Timeout fires        → show the remembered fastest slot.
     *
     * This guarantees zero visible flash: either the preferred slot appears
     * directly, or the screen is blank until we are certain it won't connect.
     */
    private fun onSlotFirstLive(slotIdx: Int) {
        if (!started) return
        if (firstSlotShown) {
            // Already showing — if the preferred slot just came live after we
            // had already fallen back to another slot, switch to it silently.
            if (slotIdx == activeIdx) {
                Log.i(TAG, "Preferred slot $slotIdx live after fallback — switching back")
                preferredSlotWaitJob?.let { mainHandler.removeCallbacks(it) }
                preferredSlotWaitJob = null
                showSlot(slotIdx)
            }
            return
        }

        if (slotIdx == activeIdx) {
            // Preferred slot is live — show it immediately.
            preferredSlotWaitJob?.let { mainHandler.removeCallbacks(it) }
            preferredSlotWaitJob = null
            firstSlotShown = true
            showSlot(activeIdx)
            return
        }

        // A non-preferred slot is ready. Remember the fastest one as fallback
        // but do NOT show it — keep the screen dark and wait for the preferred slot.
        if (fastestReadySlot < 0) {
            fastestReadySlot = slotIdx
            Log.i(TAG, "Slot $slotIdx live first — waiting up to ${PREFERRED_SLOT_TIMEOUT_MS}ms for preferred slot $activeIdx")
        }

        // Start the timeout once.
        if (preferredSlotWaitJob == null) {
            preferredSlotWaitJob = Runnable {
                preferredSlotWaitJob = null
                if (!firstSlotShown && started && fastestReadySlot >= 0) {
                    Log.i(TAG, "Preferred slot $activeIdx timed out — showing fastest slot $fastestReadySlot")
                    firstSlotShown = true
                    showSlot(fastestReadySlot)
                }
            }
            mainHandler.postDelayed(preferredSlotWaitJob!!, PREFERRED_SLOT_TIMEOUT_MS)
        }
    }

    // ── Slot switching ──────────────────────────────────────────────────────

    /**
     * Make slot [idx] the visible one.
     *
     * Background slots are set to GONE (not INVISIBLE) to prevent SurfaceView
     * compositor bleed-through: a SurfaceView renders on its own hardware layer
     * and ignores parent INVISIBLE — it stays visible to the compositor regardless.
     * GONE removes the Surface entirely, which is safe because:
     *   • ExoPlayer buffers internally and reconnects to a new Surface instantly.
     *   • VLC is kept alive via detachViews() while GONE and reattached with
     *     attachViews() when shown — no network reconnect required.
     */
    private fun showSlot(idx: Int) {
        if (slots.isEmpty()) return
        activeIdx = idx.coerceIn(0, slots.lastIndex)
        prefs.edit().putInt(KEY_LAST_ACTIVE_IDX, activeIdx).apply()

        slots.forEachIndexed { i, slot ->
            val isActive = (i == activeIdx)
            slot.vc.isActiveSlot = isActive

            if (isActive) {
                slot.container.visibility = View.VISIBLE
                when {
                    !slot.vc.isStarted() && started -> slot.vc.start()
                    slot.vc.isVlcActive() -> {
                        Log.d(TAG, "Slot $i: VLC reattach on show")
                        slot.vc.reattachVlcViews()
                        slot.vc.refreshStatus()
                    }
                    slot.vc.isExoActive() -> {
                        // Switch ExoPlayer from dummy surface to the real PlayerView.
                        // The player has been buffering/decoding all along — first frame
                        // appears immediately.
                        Log.d(TAG, "Slot $i: ExoPlayer attach to PlayerView")
                        slot.vc.attachExoToPlayerView()
                        slot.vc.refreshStatus()
                    }
                    else -> slot.vc.refreshStatus()
                }
            } else {
                if (slot.vc.isVlcActive()) {
                    Log.d(TAG, "Slot $i: VLC detach before GONE")
                    slot.vc.detachVlcViews()
                } else if (slot.vc.isExoActive()) {
                    // Redirect ExoPlayer to dummy surface so it keeps decoding while GONE.
                    Log.d(TAG, "Slot $i: ExoPlayer detach to background")
                    slot.vc.detachExoToBackground()
                }
                slot.container.visibility = View.GONE
            }
        }

        updateDots()
        if (slots.size > 1) statusLabel.text = slots[activeIdx].config.name
    }

    private fun advance() {
        if (slots.size < 2) return
        showSlot((activeIdx + 1) % slots.size)
        cancelRotation()
        if (rotationSec > 0) scheduleRotation()
    }

    // ── Rotation timer ──────────────────────────────────────────────────────

    private fun scheduleRotation() {
        cancelRotation()
        rotateJob = Runnable {
            advance()
        }.also { mainHandler.postDelayed(it, rotationSec * 1000L) }
    }

    private fun cancelRotation() {
        rotateJob?.let { mainHandler.removeCallbacks(it) }
        rotateJob = null
    }

    // ── Slot construction ───────────────────────────────────────────────────

    private fun buildSlot(cfg: CameraConfig): Slot {
        val container = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            // Start GONE — showSlot() makes the active slot VISIBLE.
            // GONE is required (not INVISIBLE) to prevent SurfaceView bleed-through.
            visibility = View.GONE
        }

        val playerView = PlayerView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            useController = false
            visibility = View.GONE
        }

        val vlcContainer = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            clipChildren = true
            visibility = View.GONE
        }
        val vlcLayout = VLCVideoLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        vlcContainer.addView(vlcLayout)

        val snapshotView = ImageView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
        }

        container.addView(playerView)
        container.addView(vlcContainer)
        container.addView(snapshotView)

        val vc = CameraViewController(
            context      = context,
            playerView   = playerView,
            vlcLayout    = vlcLayout,
            snapshotView = snapshotView,
            statusLabel  = statusLabel,
            muteButton   = muteButton,
            cameraConfig = cfg
        )
        vc.onMotionDetected = onMotionDetected

        // Insert slot container at index 0 — below tap-overlay and dot-container.
        cameraPanel.addView(container, 0)

        return Slot(cfg, container, playerView, vlcLayout, snapshotView, vc)
    }

    // ── Start / stop helpers ────────────────────────────────────────────────

    /**
     * Starts ALL slots immediately. Background slots stream invisibly so that
     * switching to any slot is instant with no reconnect delay.
     */
    private fun startAllSlots() {
        slots.forEachIndexed { i, slot ->
            slot.vc.isActiveSlot = (i == activeIdx)
            if (!slot.vc.isStarted()) slot.vc.start()
        }
    }

    private fun teardown() {
        cancelRotation()
        preferredSlotWaitJob?.let { mainHandler.removeCallbacks(it) }
        preferredSlotWaitJob = null
        firstSlotShown = false
        fastestReadySlot = -1
        streamsSuspended = false   // ensure clean state for next applyConfig()
        slots.forEach { slot ->
            slot.vc.stop()
            cameraPanel.removeView(slot.container)
        }
        slots.clear()
        activeIdx = 0
        dotContainer.removeAllViews()
    }

    // ── Indicator dots ──────────────────────────────────────────────────────

    private fun buildDots() {
        dotContainer.removeAllViews()
        if (slots.size <= 1) {
            dotContainer.visibility = View.GONE
            return
        }
        dotContainer.visibility = View.VISIBLE
        slots.indices.forEach { i ->
            val dot = View(context).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(8.dp, 8.dp).also {
                    it.marginEnd = 4.dp
                }
                setBackgroundResource(R.drawable.camera_dot_indicator)
                tag = i
            }
            dotContainer.addView(dot)
        }
        updateDots()
    }

    private fun updateDots() {
        for (i in 0 until dotContainer.childCount) {
            dotContainer.getChildAt(i)?.alpha = if (i == activeIdx) 1f else 0.35f
        }
    }

    // ── Tap overlay ─────────────────────────────────────────────────────────

    private fun installTapOverlay() {
        tapOverlay.setOnClickListener {
            if (slots.size > 1) {
                advance()
            } else {
                slots.firstOrNull()?.vc?.applyPrefsChange()
            }
        }
    }

    // ── dp extension ────────────────────────────────────────────────────────

    private val Int.dp: Int get() =
        (this * context.resources.displayMetrics.density + 0.5f).toInt()
}
