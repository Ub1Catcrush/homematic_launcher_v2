package com.tvcs.homematic

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.FragmentManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * Detail sheet for a single departure.
 *
 * Shows each transit leg as:
 *   [Line pill]  Origin station          Dep planned  Dep delay
 *                Destination station     Arr planned  Arr delay
 *
 * No stopovers list, no walking legs — just the key journey skeleton.
 *
 * Uses in-memory cache to avoid Parcelable complexity.
 */
class TransitDetailBottomSheet : BottomSheetDialogFragment() {

    companion object {
        private const val TAG   = "TransitDetail"
        private const val K_IDX = "dep_idx"

        var cachedDepartures: List<DbTransitRepository.Departure> = emptyList()

        fun show(fm: FragmentManager, departures: List<DbTransitRepository.Departure>, index: Int) {
            if (fm.findFragmentByTag(TAG) != null) return
            cachedDepartures = departures
            TransitDetailBottomSheet().apply {
                arguments = Bundle().apply { putInt(K_IDX, index) }
            }.show(fm, TAG)
        }
    }

    override fun onStart() {
        super.onStart()
        (dialog as? BottomSheetDialog)?.behavior?.apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        val dp = resources.displayMetrics.density
        val base = AppThemeHelper.fontTransit(ctx)
        fun px(d: Int) = (d * dp + 0.5f).toInt()

        val dep = cachedDepartures.getOrNull(arguments?.getInt(K_IDX, -1) ?: -1)

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        // Drag handle
        root.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(px(40), px(4)).also {
                it.gravity = Gravity.CENTER_HORIZONTAL
                it.topMargin = px(8); it.bottomMargin = px(8)
            }
            setBackgroundColor(0x44FFFFFF)
        })

        if (dep == null) {
            root.addView(TextView(ctx).apply {
                setText(R.string.transit_detail_no_data)
                textSize = base
                setTextColor(0xBBFFFFFF.toInt())
                setPadding(px(16), px(12), px(16), px(16))
            })
            root.addView(android.widget.Button(ctx).apply {
                setText(R.string.transit_detail_close)
                setOnClickListener { dismiss() }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.marginStart = px(16); it.bottomMargin = px(16) }
            })
            return root
        }

        // ── Journey header ────────────────────────────────────────────────────
        root.addView(TextView(ctx).apply {
            text =
                ctx.getString(R.string.transit_detail_header, dep.line, dep.origin, dep.direction)
            textSize = base * 1.45f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(if (dep.cancelled) 0xFFFF4444.toInt() else Color.WHITE)
            setPadding(px(16), 0, px(16), px(2))
        })

        // Sub: departure time + delay
        root.addView(TextView(ctx).apply {
            val rt  = dep.realtimeTime
            val del = dep.delayMinutes
            text = when {
                del == null -> ctx.getString(R.string.transit_detail_dep_time, dep.plannedTime)
                del <= 0 && rt != null && rt != dep.plannedTime ->
                    ctx.getString(R.string.transit_detail_dep_time_rt_ontime, dep.plannedTime, rt)

                del <= 0 ->
                    ctx.getString(R.string.transit_detail_dep_time_ontime, dep.plannedTime)

                rt != null && rt != dep.plannedTime ->
                    ctx.getString(
                        R.string.transit_detail_dep_time_rt_delay,
                        dep.plannedTime,
                        rt,
                        del
                    )

                else ->
                    ctx.getString(R.string.transit_detail_dep_time_delay, dep.plannedTime, del)
            }
            textSize = base * 0.95f
            setTextColor(0xBBFFFFFF.toInt())
            setPadding(px(16), 0, px(16), px(10))
        })

        // ── Leg list ──────────────────────────────────────────────────────────
        val scroll = android.widget.ScrollView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(12), 0, px(12), px(24))
        }

        dep.legs.forEachIndexed { legIdx, leg ->
            if (legIdx > 0) {
                content.addView(View(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, px(1)).also {
                        it.topMargin = px(6); it.bottomMargin = px(6)
                    }
                    setBackgroundColor(0x22FFFFFF)
                })
            }

            // ── Walk leg ──────────────────────────────────────────────────────
            if (leg.isWalk) {
                val mins = leg.walkMinutes
                val durStr = if (mins != null && mins > 0) " · $mins Min." else ""
                content.addView(TextView(ctx).apply {
                    text = ctx.getString(
                        R.string.transit_detail_walk,
                        durStr,
                        leg.origin,
                        leg.destination
                    )
                    textSize = base * 0.95f
                    setTextColor(0x99FFFFFF.toInt())
                    setPadding(px(8), px(4), px(8), px(4))
                })
                return@forEachIndexed
            }

            // ── Leg header: [Line pill] + optional Ausfall ────────────────────
            content.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, px(2), 0, px(4))
                addView(TextView(ctx).apply {
                    text = leg.lineName
                    textSize = base * 0.9f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(Color.WHITE)
                    setBackgroundColor(0xFF003399.toInt())
                    setPadding(px(6), px(2), px(6), px(2))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT).also { it.marginEnd = px(8) }
                })
                if (leg.cancelled) {
                    addView(TextView(ctx).apply {
                        setText(R.string.transit_cancelled)
                        textSize = base * 0.9f
                        setTextColor(0xFFFF4444.toInt())
                        gravity = Gravity.CENTER_VERTICAL
                    })
                }
            })

            // ── Origin row ────────────────────────────────────────────────────
            content.addView(
                stationRow(
                    ctx, dp, base,
                bullet    = "◉",
                name      = leg.origin,
                planned   = leg.depPlanned,
                realtime  = leg.depRealtime,
                delay     = leg.depDelay,
                    cancelled = leg.cancelled
            ))

            // ── Destination row ───────────────────────────────────────────────
            content.addView(
                stationRow(
                    ctx, dp, base,
                bullet    = "◎",
                name      = leg.destination,
                planned   = leg.arrPlanned,
                realtime  = leg.arrRealtime,
                delay     = leg.arrDelay,
                    cancelled = leg.cancelled
            ))
        }

        scroll.addView(content)
        root.addView(scroll)
        return root
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * One station row:
     *   [bullet]  [Station name          ]  [planned (realtime)]  [delay badge]
     */
    private fun stationRow(
        ctx: android.content.Context,
        dp: Float,
        nameSize: Float,
        bullet: String,
        name: String,
        planned: String,
        realtime: String?,
        delay: Int?,
        cancelled: Boolean
    ): LinearLayout {
        fun px(d: Int) = (d * dp + 0.5f).toInt()

        val (delayText, delayColor) = when {
            cancelled -> ctx.getString(R.string.transit_cancelled) to 0xFFFF4444.toInt()
            delay == null -> "" to 0x00000000
            delay <= 0 -> ctx.getString(R.string.transit_on_time) to 0xFF66DD66.toInt()
            delay <= 5 -> ctx.getString(R.string.transit_delay_min, delay) to 0xFFFFAA00.toInt()
            else -> ctx.getString(R.string.transit_delay_min, delay) to 0xFFFF4444.toInt()
        }
        val timeStr = if (realtime != null && realtime != planned) "$planned ($realtime)" else planned

        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(px(8), px(2), 0, px(2))

            addView(TextView(ctx).apply {
                text = bullet
                textSize = nameSize
                setTextColor(0x66FFFFFF)
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(px(20), LinearLayout.LayoutParams.MATCH_PARENT)
            })
            addView(TextView(ctx).apply {
                text = name
                textSize = nameSize
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            })
            addView(TextView(ctx).apply {
                text = timeStr
                textSize = nameSize * 0.95f
                setTextColor(0xCCFFFFFF.toInt())
                gravity = Gravity.CENTER_VERTICAL or Gravity.END
                setPadding(px(4), 0, px(4), 0)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT)
            })
            addView(TextView(ctx).apply {
                text = delayText
                textSize = nameSize * 0.88f
                setTextColor(delayColor)
                gravity = Gravity.CENTER_VERTICAL or Gravity.END
                minWidth = px(32)
                setPadding(0, 0, px(4), 0)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT)
            })
        }
    }
}
