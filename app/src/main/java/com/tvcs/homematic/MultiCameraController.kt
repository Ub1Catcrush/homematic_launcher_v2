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
        val configs = CameraConfigStore.load(prefs)
        rotationSec = CameraConfigStore.loadRotationSec(prefs)

        if (configs.isEmpty()) {
            cameraPanel.visibility = View.GONE
            return
        }
        cameraPanel.visibility = View.VISIBLE

        configs.forEach { cfg ->
            val slot = buildSlot(cfg)
            slots.add(slot)
        }

        buildDots()
        installTapOverlay()

        showSlot(0, initial = true)
        if (started) startAllSlots()
        if (rotationSec > 0 && slots.size > 1) scheduleRotation()
    }

    fun applyPrefsChange() {
        applyConfig()
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
        if (slots.isEmpty()) applyConfig()
        else {
            // Start ALL slots so background buffering is active immediately.
            startAllSlots()
            if (rotationSec > 0 && slots.size > 1) scheduleRotation()
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        cancelRotation()
        slots.forEach { it.vc.stop() }
        started = false
    }

    override fun onDestroy(owner: LifecycleOwner) {
        teardown()
    }

    // ── Slot switching ──────────────────────────────────────────────────────

    /**
     * Make slot [idx] the visible one. All other slots go INVISIBLE (never GONE)
     * so their Surfaces remain valid and engines keep streaming.
     *
     * @param initial  true on first layout — skips VLC restart optimisation
     *                 because no slot was previously visible.
     */
    private fun showSlot(idx: Int, initial: Boolean = false) {
        if (slots.isEmpty()) return
        activeIdx = idx.coerceIn(0, slots.lastIndex)

        slots.forEachIndexed { i, slot ->
            val isActive = (i == activeIdx)
            slot.vc.isActiveSlot = isActive

            if (isActive) {
                // Make the active slot's container fully visible.
                slot.container.visibility = View.VISIBLE

                when {
                    !slot.vc.isStarted() && started -> {
                        // Should only happen if start() raced with applyConfig().
                        slot.vc.start()
                    }
                    !initial && slot.vc.isVlcActive() -> {
                        // VLC has been streaming into its Surface the whole time the
                        // slot was INVISIBLE — the Surface is never destroyed while
                        // INVISIBLE, only while GONE.  Simply making the container
                        // VISIBLE is enough; the already-decoded frames start
                        // appearing immediately with zero reconnect delay.
                        // reattachViews() is a no-cost safety call in case the
                        // window compositor recycled the SurfaceHolder.
                        Log.d(TAG, "Slot $i VLC active — revealing (no reconnect)")
                        slot.vc.reattachVlcViews()
                        slot.vc.refreshStatus()
                    }
                    else -> {
                        slot.vc.refreshStatus()
                    }
                }
            } else {
                // Keep INVISIBLE — the Surface stays valid, engine keeps running.
                slot.container.visibility = View.INVISIBLE
            }
        }

        updateDots()
        if (slots.size > 1) {
            statusLabel.text = slots[activeIdx].config.name
        }
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
            // Start INVISIBLE — showSlot() will make the first slot VISIBLE.
            visibility = View.INVISIBLE
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
