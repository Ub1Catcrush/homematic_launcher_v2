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
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * TransitDetailBottomSheet — shows the full journey when a departure row is tapped.
 *
 * Displays every leg with origin → destination, line, and all intermediate
 * stopovers with planned time, realtime time, and delay colour-coded.
 *
 * Because Departure.legs is not Parcelable we store the index into the last
 * fetched departure list and re-read it from a companion-object cache.
 */
class TransitDetailBottomSheet : BottomSheetDialogFragment() {

    companion object {
        private const val TAG   = "TransitDetail"
        private const val K_IDX = "dep_idx"

        // Simple in-memory cache — set before calling show()
        var cachedDepartures: List<DbTransitRepository.Departure> = emptyList()

        fun show(fm: FragmentManager, departures: List<DbTransitRepository.Departure>, index: Int) {
            if (fm.findFragmentByTag(TAG) != null) return
            cachedDepartures = departures
            TransitDetailBottomSheet().apply {
                arguments = Bundle().apply { putInt(K_IDX, index) }
            }.show(fm, TAG)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        val dp  = resources.displayMetrics.density
        fun px(d: Int) = (d * dp + 0.5f).toInt()
        fun px(d: Float) = (d * dp + 0.5f).toInt()

        val idx = arguments?.getInt(K_IDX, -1) ?: -1
        val dep = cachedDepartures.getOrNull(idx)

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        // ── Drag handle ───────────────────────────────────────────────────────
        root.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(px(40), px(4)).also {
                it.gravity = Gravity.CENTER_HORIZONTAL
                it.topMargin = px(8); it.bottomMargin = px(8)
            }
            setBackgroundColor(0x44FFFFFF)
        })

        if (dep == null) {
            root.addView(TextView(ctx).apply {
                text = "Keine Daten verfügbar."
                textSize = 14f; setTextColor(Color.WHITE)
                setPadding(px(16), px(8), px(16), px(16))
            })
            return root
        }

        // ── Header: Line → Final destination ─────────────────────────────────
        root.addView(TextView(ctx).apply {
            text = "${dep.line}  →  ${dep.direction}"
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(if (dep.cancelled) 0xFFFF4444.toInt() else Color.WHITE)
            setPadding(px(16), 0, px(16), 0)
        })
        // Sub-header: origin → departure time
        root.addView(TextView(ctx).apply {
            val rt = dep.realtimeTime
            val delayStr = dep.delayMinutes?.let { d ->
                when { d <= 0 -> "  ✓" ; else -> "  +${d}'" }
            } ?: ""
            text = "Ab ${dep.origin}  ${rt ?: dep.plannedTime}$delayStr"
            textSize = 12f
            setTextColor(0xBBFFFFFF.toInt())
            setPadding(px(16), px(2), px(16), px(10))
        })

        // ── Scrollable legs ───────────────────────────────────────────────────
        val scroll = android.widget.ScrollView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(12), 0, px(12), px(24))
        }

        for ((legIdx, leg) in dep.legs.withIndex()) {
            val isWalk = leg.mode == "walking" || leg.mode == "transfer"

            // ── Leg header ────────────────────────────────────────────────────
            content.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.topMargin = if (legIdx == 0) 0 else px(10)
                layoutParams = lp
                setPadding(0, px(4), 0, px(2))

                // Coloured line pill
                addView(TextView(ctx).apply {
                    text = leg.lineName
                    textSize = 11f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(Color.WHITE)
                    setBackgroundColor(if (isWalk) 0xFF444444.toInt() else 0xFF003399.toInt())
                    setPadding(px(6), px(2), px(6), px(2))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT).also { it.marginEnd = px(8) }
                })
                addView(TextView(ctx).apply {
                    text = "${leg.origin}  →  ${leg.destination}"
                    textSize = 11f
                    setTextColor(0xCCFFFFFF.toInt())
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                })
                if (leg.cancelled) {
                    addView(TextView(ctx).apply {
                        text = "Ausfall"
                        textSize = 10f
                        setTextColor(0xFFFF4444.toInt())
                        gravity = Gravity.CENTER_VERTICAL
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.MATCH_PARENT)
                    })
                }
            })

            // ── Stopovers ─────────────────────────────────────────────────────
            if (!isWalk) {
                for ((svIdx, sv) in leg.stopovers.withIndex()) {
                    val isFirst = svIdx == 0
                    val isLast  = svIdx == leg.stopovers.lastIndex
                    val bullet  = when { isFirst -> "◉" ; isLast -> "◎" ; else -> "·" }
                    val tsColor = when { isFirst && legIdx == 0 -> Color.WHITE ; isLast -> 0xBBFFFFFF.toInt() ; else -> 0x88FFFFFF.toInt() }
                    val nameColor = when { isFirst || isLast -> Color.WHITE ; else -> 0x99FFFFFF.toInt() }
                    val nameSize  = if (isFirst || isLast) 12f else 11f

                    content.addView(LinearLayout(ctx).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(px(8), px(1), 0, px(1))

                        // Bullet
                        addView(TextView(ctx).apply {
                            text = bullet
                            textSize = if (isFirst || isLast) 14f else 10f
                            setTextColor(0x66FFFFFF)
                            gravity = Gravity.CENTER_VERTICAL
                            layoutParams = LinearLayout.LayoutParams(px(20), LinearLayout.LayoutParams.MATCH_PARENT)
                        })

                        // Station name
                        addView(TextView(ctx).apply {
                            text = sv.stationName
                            textSize = nameSize
                            setTextColor(nameColor)
                            gravity = Gravity.CENTER_VERTICAL
                            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                        })

                        // Planned time
                        addView(TextView(ctx).apply {
                            text = sv.plannedTime
                            textSize = 12f
                            setTextColor(tsColor)
                            gravity = Gravity.CENTER_VERTICAL or Gravity.END
                            layoutParams = LinearLayout.LayoutParams(px(40), LinearLayout.LayoutParams.MATCH_PARENT)
                        })

                        // Realtime / delay
                        val delay = sv.delayMinutes
                        val rt    = sv.realtimeTime
                        addView(TextView(ctx).apply {
                            text = when {
                                sv.cancelled  -> "❌"
                                delay == null -> ""
                                delay <= 0    -> "✓"
                                else          -> "+${delay}'"
                            }
                            textSize = 11f
                            setTextColor(when {
                                sv.cancelled  -> 0xFFFF4444.toInt()
                                delay == null -> 0x00000000
                                delay <= 0    -> 0xFF66DD66.toInt()
                                delay <= 5    -> 0xFFFFAA00.toInt()
                                else          -> 0xFFFF4444.toInt()
                            })
                            gravity = Gravity.CENTER_VERTICAL or Gravity.END
                            layoutParams = LinearLayout.LayoutParams(px(32), LinearLayout.LayoutParams.MATCH_PARENT)
                            setPadding(0, 0, px(4), 0)
                        })
                    })
                }
            }
        }

        scroll.addView(content)
        root.addView(scroll)
        return root
    }
}
