package com.tvcs.homematic

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder

/**
 * RoiPickerPreference
 *
 * A Preference that shows the current ROI zone as a thumbnail and opens
 * a touch-drag dialog to set it visually instead of typing coordinates.
 *
 * The value is stored as "left,top,right,bottom" fractions (0.0–1.0),
 * same format as [MotionDetectionEngine.RoiRect].
 */
class RoiPickerPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : Preference(context, attrs) {

    init {
        isPersistent = true
        widgetLayoutResource = 0
    }

    override fun onGetDefaultValue(a: android.content.res.TypedArray, index: Int): Any =
        a.getString(index) ?: ""

    override fun onSetInitialValue(defaultValue: Any?) {
        persistString(getPersistedString((defaultValue as? String) ?: ""))
    }

    override fun onClick() {
        showPicker()
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        updateSummaryFromValue()
    }

    private fun updateSummaryFromValue() {
        val v = getPersistedString("")
        summary = if (v.isBlank()) context.getString(R.string.roi_full_frame)
                  else formatRoi(v)
    }

    private fun formatRoi(raw: String): String {
        val p = raw.split(",").mapNotNull { it.trim().toFloatOrNull() }
        if (p.size != 4) return raw
        return "L:${pct(p[0])}  T:${pct(p[1])}  R:${pct(p[2])}  B:${pct(p[3])}"
    }
    private fun pct(f: Float) = "${(f * 100).toInt()}%"

    // ── Picker dialog ─────────────────────────────────────────────────────────

    private fun showPicker() {
        val ctx    = context
        val dp     = ctx.resources.displayMetrics.density
        val current = getPersistedString("")
        val parts  = current.split(",").mapNotNull { it.trim().toFloatOrNull() }
        val initL  = if (parts.size == 4) parts[0] else 0f
        val initT  = if (parts.size == 4) parts[1] else 0f
        val initR  = if (parts.size == 4) parts[2] else 1f
        val initB  = if (parts.size == 4) parts[3] else 1f

        val roiView = RoiPickerView(ctx, initL, initT, initR, initB)
        val tvHint  = TextView(ctx).apply {
            text    = ctx.getString(R.string.roi_picker_hint)
            textSize = 12f
            setTextColor(0xFF888888.toInt())
            setPadding((16*dp).toInt(), (8*dp).toInt(), (16*dp).toInt(), 0)
        }
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(tvHint)
            addView(roiView, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (200 * dp).toInt()
            ).also { it.setMargins((16*dp).toInt(), (8*dp).toInt(), (16*dp).toInt(), (8*dp).toInt()) })
        }

        AlertDialog.Builder(ctx)
            .setTitle(ctx.getString(R.string.roi_picker_title))
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val roi  = roiView.getRoi()
                val str  = if (roi[0] == 0f && roi[1] == 0f && roi[2] == 1f && roi[3] == 1f) ""
                           else "%.2f,%.2f,%.2f,%.2f".format(roi[0], roi[1], roi[2], roi[3])
                if (callChangeListener(str)) {
                    persistString(str)
                    updateSummaryFromValue()
                    notifyChanged()
                }
            }
            .setNeutralButton(ctx.getString(R.string.roi_reset)) { _, _ ->
                if (callChangeListener("")) {
                    persistString("")
                    updateSummaryFromValue()
                    notifyChanged()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}

/**
 * Interactive view: shows a camera-shaped rectangle.
 * Drag to draw the ROI zone. Corner handles for precise adjustment.
 */
class RoiPickerView(
    ctx: Context,
    initL: Float = 0f, initT: Float = 0f,
    initR: Float = 1f, initB: Float = 1f
) : View(ctx) {

    private var roiL = initL; private var roiT = initT
    private var roiR = initR; private var roiB = initB

    private var dragMode = DRAG_NONE
    private val handleRadius = 22f * resources.displayMetrics.density / 2

    companion object {
        private const val DRAG_NONE   = 0
        private const val DRAG_DRAW   = 1
        private const val DRAG_TL     = 2
        private const val DRAG_TR     = 3
        private const val DRAG_BL     = 4
        private const val DRAG_BR     = 5
        private const val DRAG_MOVE   = 6
    }

    private var dragStartX = 0f; private var dragStartY = 0f
    private var dragStartL = 0f; private var dragStartT = 0f
    private var dragStartR = 0f; private var dragStartB = 0f

    private val paintBg     = Paint().apply { color = 0xFF1A1A2E.toInt() }
    private val paintGrid   = Paint().apply { color = 0x33FFFFFF; strokeWidth = 1f; style = Paint.Style.STROKE }
    private val paintRoi    = Paint().apply { color = 0x6600AAFF; style = Paint.Style.FILL }
    private val paintBorder = Paint().apply { color = 0xFF00AAFF.toInt(); strokeWidth = 3f; style = Paint.Style.STROKE }
    private val paintHandle = Paint().apply { color = 0xFF00AAFF.toInt(); style = Paint.Style.FILL }
    private val paintLabel  = Paint().apply { color = Color.WHITE; textSize = 28f; isAntiAlias = true }
    // Camera silhouette
    private val paintCam    = Paint().apply { color = 0xFF2A2A3E.toInt(); style = Paint.Style.FILL }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        // Background
        canvas.drawRect(0f, 0f, w, h, paintBg)
        // Camera frame silhouette (rounded rect)
        canvas.drawRoundRect(RectF(4f, 4f, w-4f, h-4f), 12f, 12f, paintCam)
        // Grid lines (rule of thirds)
        for (i in 1..2) {
            canvas.drawLine(w*i/3, 0f, w*i/3, h, paintGrid)
            canvas.drawLine(0f, h*i/3, w, h*i/3, paintGrid)
        }
        // ROI fill
        val rx = roiL*w; val ry = roiT*h; val rx2 = roiR*w; val ry2 = roiB*h
        canvas.drawRect(rx, ry, rx2, ry2, paintRoi)
        canvas.drawRect(rx, ry, rx2, ry2, paintBorder)
        // Corner handles
        for ((cx, cy) in listOf(rx to ry, rx2 to ry, rx to ry2, rx2 to ry2)) {
            canvas.drawCircle(cx, cy, handleRadius, paintHandle)
        }
        // Percentage label
        val wPct = ((roiR - roiL) * 100).toInt()
        val hPct = ((roiB - roiT) * 100).toInt()
        canvas.drawText("${wPct}×${hPct}%", rx + 6f, ry2 - 6f, paintLabel)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val w = width.toFloat(); val h = height.toFloat()
        val fx = (event.x / w).coerceIn(0f, 1f)
        val fy = (event.y / h).coerceIn(0f, 1f)

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                dragStartX = fx; dragStartY = fy
                dragStartL = roiL; dragStartT = roiT
                dragStartR = roiR; dragStartB = roiB
                dragMode = detectHandle(event.x / w, event.y / h)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = fx - dragStartX; val dy = fy - dragStartY
                when (dragMode) {
                    DRAG_DRAW -> {
                        roiL = minOf(dragStartX, fx); roiT = minOf(dragStartY, fy)
                        roiR = maxOf(dragStartX, fx); roiB = maxOf(dragStartY, fy)
                    }
                    DRAG_TL -> { roiL = (dragStartL+dx).coerceIn(0f, roiR-0.05f)
                                 roiT = (dragStartT+dy).coerceIn(0f, roiB-0.05f) }
                    DRAG_TR -> { roiR = (dragStartR+dx).coerceIn(roiL+0.05f, 1f)
                                 roiT = (dragStartT+dy).coerceIn(0f, roiB-0.05f) }
                    DRAG_BL -> { roiL = (dragStartL+dx).coerceIn(0f, roiR-0.05f)
                                 roiB = (dragStartB+dy).coerceIn(roiT+0.05f, 1f) }
                    DRAG_BR -> { roiR = (dragStartR+dx).coerceIn(roiL+0.05f, 1f)
                                 roiB = (dragStartB+dy).coerceIn(roiT+0.05f, 1f) }
                    DRAG_MOVE -> {
                        val ww = dragStartR - dragStartL; val hh = dragStartB - dragStartT
                        roiL = (dragStartL+dx).coerceIn(0f, 1f-ww)
                        roiT = (dragStartT+dy).coerceIn(0f, 1f-hh)
                        roiR = roiL + ww; roiB = roiT + hh
                    }
                }
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                if (dragMode == DRAG_NONE) dragMode = DRAG_DRAW
                invalidate()
            }
        }
        return true
    }

    private fun detectHandle(fx: Float, fy: Float): Int {
        val hr = handleRadius / width  // handle radius in fraction coords
        fun near(ax: Float, ay: Float) = Math.abs(fx-ax) < hr*2 && Math.abs(fy-ay) < hr*2
        return when {
            near(roiL, roiT) -> DRAG_TL
            near(roiR, roiT) -> DRAG_TR
            near(roiL, roiB) -> DRAG_BL
            near(roiR, roiB) -> DRAG_BR
            fx in roiL..roiR && fy in roiT..roiB -> DRAG_MOVE
            else -> DRAG_DRAW
        }
    }

    fun getRoi() = floatArrayOf(
        roiL.coerceIn(0f,1f), roiT.coerceIn(0f,1f),
        roiR.coerceIn(0f,1f), roiB.coerceIn(0f,1f)
    )
}
