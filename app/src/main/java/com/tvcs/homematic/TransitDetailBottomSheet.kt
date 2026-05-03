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

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        val dp  = resources.displayMetrics.density
        fun px(d: Int)   = (d * dp + 0.5f).toInt()
        fun px(d: Float) = (d * dp + 0.5f).toInt()

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
                text = "Verbindungsdaten nicht mehr verfügbar.\nBitte Anzeige antippen um neu zu laden."
                textSize = 13f; setTextColor(0xBBFFFFFF.toInt())
                setPadding(px(16), px(12), px(16), px(16))
            })
            root.addView(android.widget.Button(ctx).apply {
                text = "Schließen"
                setOnClickListener { dismiss() }
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.marginStart = px(16); it.bottomMargin = px(16) }
            })
            return root
        }

        // ── Journey header ────────────────────────────────────────────────────
        // "Line  Origin → Destination"
        root.addView(TextView(ctx).apply {
            text = "${dep.line}  ${dep.origin} → ${dep.direction}"
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(if (dep.cancelled) 0xFFFF4444.toInt() else Color.WHITE)
            setPadding(px(16), 0, px(16), px(2))
        })
        // Sub: departure time + delay
        root.addView(TextView(ctx).apply {
            val rt  = dep.realtimeTime
            val del = dep.delayMinutes
            val delStr = when {
                del == null -> ""
                del <= 0   -> "  ✓"
                else       -> "  +${del}'"
            }
            text = "Ab ${dep.plannedTime}${if (rt != null && rt != dep.plannedTime) " ($rt)" else ""}$delStr"
            textSize = 12f
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
                // Spacer between legs
                content.addView(View(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, px(1)).also {
                        it.topMargin = px(6); it.bottomMargin = px(6)
                    }
                    setBackgroundColor(0x22FFFFFF)
                })
            }

            // ── Leg header: [Line pill] ───────────────────────────────────────
            content.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, px(2), 0, px(4))

                addView(TextView(ctx).apply {
                    text = leg.lineName
                    textSize = 11f
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
                        text = context.getString(R.string.transit_cancelled)
                        textSize = 11f; setTextColor(0xFFFF4444.toInt())
                        gravity = Gravity.CENTER_VERTICAL
                    })
                }
            })

            // ── Origin row ────────────────────────────────────────────────────
            content.addView(stationRow(ctx, dp,
                bullet    = "◉",
                name      = leg.origin,
                planned   = leg.depPlanned,
                realtime  = leg.depRealtime,
                delay     = leg.depDelay,
                cancelled = leg.cancelled,
                nameSize  = 13f,
                dimmed    = false
            ))

            // ── Destination row ───────────────────────────────────────────────
            content.addView(stationRow(ctx, dp,
                bullet    = "◎",
                name      = leg.destination,
                planned   = leg.arrPlanned,
                realtime  = leg.arrRealtime,
                delay     = leg.arrDelay,
                cancelled = leg.cancelled,
                nameSize  = 13f,
                dimmed    = false
            ))
        }

        scroll.addView(content)
        root.addView(scroll)
        return root
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * One station row:
     *   [bullet]  [Station name          ]  [planned]  [delay badge]
     */
    private fun stationRow(
        ctx: android.content.Context,
        dp: Float,
        bullet: String,
        name: String,
        planned: String,
        realtime: String?,
        delay: Int?,
        cancelled: Boolean,
        nameSize: Float,
        dimmed: Boolean
    ): LinearLayout {
        fun px(d: Int) = (d * dp + 0.5f).toInt()
        val nameColor   = if (dimmed) 0x99FFFFFF.toInt() else Color.WHITE
        val timeColor   = if (dimmed) 0x77FFFFFF.toInt() else 0xCCFFFFFF.toInt()

        // Delay badge text + colour
        val (delayText, delayColor) = when {
            cancelled     -> "❌" to 0xFFFF4444.toInt()
            delay == null -> ""   to 0x00000000
            delay <= 0    -> "✓"  to 0xFF66DD66.toInt()
            delay <= 5    -> "+${delay}'" to 0xFFFFAA00.toInt()
            else          -> "+${delay}'" to 0xFFFF4444.toInt()
        }
        // Show realtime in parentheses next to planned if different
        val timeStr = if (realtime != null && realtime != planned) "$planned ($realtime)" else planned

        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(px(8), px(2), 0, px(2))

            // Bullet
            addView(TextView(ctx).apply {
                text = bullet; textSize = 13f
                setTextColor(0x66FFFFFF.toInt()); gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(px(20), LinearLayout.LayoutParams.MATCH_PARENT)
            })
            // Station name
            addView(TextView(ctx).apply {
                text = name; textSize = nameSize
                setTextColor(nameColor); gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            })
            // Planned (+ realtime) time
            addView(TextView(ctx).apply {
                text = timeStr; textSize = 12f
                setTextColor(timeColor)
                gravity = Gravity.CENTER_VERTICAL or Gravity.END
                setPadding(px(4), 0, px(4), 0)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT)
            })
            // Delay badge
            addView(TextView(ctx).apply {
                text = delayText; textSize = 11f
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
