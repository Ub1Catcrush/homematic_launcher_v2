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
 * set of views (PlayerView + VLCVideoLayout + ImageView) that are all kept alive
 * and streaming in the background. Only one slot is VISIBLE at a time; the rest
 * are INVISIBLE (not GONE — they must remain attached to the window for
 * ExoPlayer / VLC surface rendering to work).
 *
 * ── Rotation ─────────────────────────────────────────────────────────────────
 * When rotationSec > 0 the controller automatically advances to the next slot
 * every [rotationSec] seconds. A tap on [tapOverlay] always advances manually
 * and resets the rotation timer.
 *
 * ── Single-camera mode ───────────────────────────────────────────────────────
 * When there is only one camera (or zero) the controller behaves identically to
 * the old CameraViewController: no indicator dots, no rotation, full tap-to-
 * reload as before.
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

        // Build one slot per config
        configs.forEach { cfg ->
            val slot = buildSlot(cfg)
            slots.add(slot)
        }

        buildDots()
        installTapOverlay()

        if (started) startAllSlots()
        showSlot(0)
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
            // Only start the active slot — others start lazily on tap
            slots.getOrNull(activeIdx)?.let { slot ->
                slot.vc.isActiveSlot = true
                slot.vc.start()
            }
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

    private fun showSlot(idx: Int) {
        if (slots.isEmpty()) return
        val prevIdx = activeIdx
        activeIdx = idx.coerceIn(0, slots.lastIndex)

        slots.forEachIndexed { i, slot ->
            val isActive = (i == activeIdx)
            slot.vc.isActiveSlot = isActive

            if (isActive) {
                // Make container visible
                (slot.playerView.parent as? View)?.visibility = View.VISIBLE
                // Start the slot if it hasn't been started yet (lazy start)
                if (!slot.vc.isStarted() && started) slot.vc.start()
                // Restore correct view visibility and status label
                slot.vc.refreshStatus()
            } else {
                // Keep container INVISIBLE — surface stays attached for background VLC/Exo
                // but we stop streaming to save connections and CPU
                if (i == prevIdx && prevIdx != activeIdx) {
                    slot.vc.stop()
                }
                (slot.playerView.parent as? View)?.visibility = View.INVISIBLE
                slot.playerView.visibility = View.INVISIBLE
                slot.vlcLayout.visibility  = View.INVISIBLE
                slot.snapshot.visibility   = View.INVISIBLE
            }
        }
        updateDots()
        // Prefix camera name in multi-cam mode
        if (slots.size > 1) {
            val name = slots[activeIdx].config.name
            val cur  = statusLabel.text.toString()
            if (!cur.startsWith(name)) {
                statusLabel.text = if (cur.isNotBlank()) "$name · $cur" else name
            }
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
        // Each slot gets its own FrameLayout child inside cameraPanel
        val container = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
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

        // Status label and mute button are shared (belong to the panel overlay,
        // not the slot containers). Only the active slot's VC updates them.
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

        // Insert slot container at index 0 — below tap-overlay, dot-container and
        // status-row which are declared first in the XML and thus have higher Z-order.
        cameraPanel.addView(container, 0)

        return Slot(cfg, playerView, vlcLayout, snapshotView, vc)
    }

    // ── Start / stop helpers ────────────────────────────────────────────────

    /** Starts only the currently active slot. Other slots start lazily when shown. */
    private fun startAllSlots() {
        slots.forEachIndexed { i, slot ->
            slot.vc.isActiveSlot = (i == activeIdx)
        }
        slots.getOrNull(activeIdx)?.vc?.start()
    }

    private fun teardown() {
        cancelRotation()
        slots.forEach { slot ->
            slot.vc.stop()
            cameraPanel.removeView(slot.playerView.parent as? View ?: slot.playerView)
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
                // Multi-cam: advance to next camera
                advance()
            } else {
                // Single-cam: reload (same as before)
                slots.firstOrNull()?.vc?.applyPrefsChange()
            }
        }
    }

    // ── dp extension ────────────────────────────────────────────────────────

    private val Int.dp: Int get() =
        (this * context.resources.displayMetrics.density + 0.5f).toInt()
}
