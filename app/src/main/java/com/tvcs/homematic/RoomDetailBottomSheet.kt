package com.tvcs.homematic

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.homematic.Room

/**
 * RoomDetailBottomSheet — shows ALL datapoints of a room in a scrollable bottom sheet.
 *
 * Opened by tapping a room tile in the grid. Displays every channel and its
 * datapoints with type, value, unit and timestamp so the user can diagnose
 * profile mapping issues and inspect raw CCU data without needing adb.
 *
 * Launch via: RoomDetailBottomSheet.show(supportFragmentManager, room)
 */
class RoomDetailBottomSheet : BottomSheetDialogFragment() {

    companion object {
        private const val TAG     = "RoomDetailSheet"
        private const val ARG_ISE = "room_ise_id"

        fun show(fm: FragmentManager, room: Room) {
            // Avoid duplicate sheets
            if (fm.findFragmentByTag(TAG) != null) return
            newInstance(room.ise_id).show(fm, TAG)
        }

        private fun newInstance(iseId: Int) = RoomDetailBottomSheet().apply {
            arguments = Bundle().apply { putInt(ARG_ISE, iseId) }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.bottom_sheet_room_detail, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val iseId = arguments?.getInt(ARG_ISE) ?: run { dismiss(); return }
        val state = HomeMatic.state ?: run { dismiss(); return }
        val prof  = HomeMatic.profile

        val room = state.roomList.rooms.firstOrNull { it.ise_id == iseId }
            ?: run { dismiss(); return }

        view.findViewById<TextView>(R.id.sheet_room_title).text = room.name

        val loadAge = if (state.loadTime > 0L) {
            val secs = (System.currentTimeMillis() - state.loadTime) / 1000L
            getString(R.string.detail_load_age, secs)
        } else ""
        view.findViewById<TextView>(R.id.sheet_load_age).text = loadAge

        val container = view.findViewById<LinearLayout>(R.id.sheet_channels_container)

        for (rc in room.channels) {
            val chan  = state.channels[rc.ise_id]
            val devId = chan?.let { state.channel2Device[it.ise_id] }
            val dev   = devId?.let { state.devices[it] }

            // ── Channel header ───────────────────────────────────────────────
            val chanHeader = TextView(requireContext()).apply {
                text = buildString {
                    append("▸ ")
                    append(dev?.name ?: rc.ise_id.toString())
                    if (dev != null) append("  [${dev.device_type}]")
                    if (chan == null) append("  ⚠ ${getString(R.string.detail_channel_missing)}")
                }
                setTextColor(ContextCompat.getColor(requireContext(), R.color.detail_channel_header))
                textSize = 12f
                setPadding(0, dpToPx(8), 0, dpToPx(2))
            }
            container.addView(chanHeader)

            if (chan == null) continue

            // ── Datapoint rows ───────────────────────────────────────────────
            if (chan.datapoints.isEmpty()) {
                container.addView(makeValueRow("  –", getString(R.string.detail_no_datapoints)))
            }

            for (dp in chan.datapoints) {
                val isKnown = dp.type in prof.setTempFields    ||
                              dp.type in prof.actualTempFields ||
                              dp.type in prof.humidityFields   ||
                              dp.type in prof.stateFields      ||
                              dp.type in prof.lowbatFields     ||
                              dp.type in prof.sabotageFields   ||
                              dp.type in prof.faultFields

                val label = dp.type + if (!isKnown) "  ★" else ""   // ★ = unknown, not in profile
                val value = buildString {
                    append(dp.value)
                    if (dp.valueunit.isNotBlank()) append(" ${dp.valueunit}")
                    if (dp.timestamp > 0L) {
                        val ts = java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.getDefault())
                            .format(java.util.Date(dp.timestamp * 1000L))
                        append("  ($ts)")
                    }
                }
                val row = makeValueRow(label, value)
                if (!isKnown) {
                    // Highlight datapoints not covered by profile — helps profile configuration
                    (row.getChildAt(0) as? TextView)?.setTextColor(0xFFFFAA00.toInt())
                }
                container.addView(row)
            }
        }

        // Legend for ★
        if (room.channels.any { rc ->
            val chan = state.channels[rc.ise_id] ?: return@any false
            chan.datapoints.any { dp ->
                dp.type !in prof.setTempFields && dp.type !in prof.actualTempFields &&
                dp.type !in prof.humidityFields && dp.type !in prof.stateFields &&
                dp.type !in prof.lowbatFields && dp.type !in prof.sabotageFields &&
                dp.type !in prof.faultFields
            }
        }) {
            container.addView(TextView(requireContext()).apply {
                text = getString(R.string.detail_unknown_legend)
                textSize = 10f
                alpha = 0.6f
                setPadding(0, dpToPx(8), 0, 0)
            })
        }
    }

    private fun makeValueRow(label: String, value: String): LinearLayout {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dpToPx(4), dpToPx(1), 0, dpToPx(1))
        }
        row.addView(TextView(requireContext()).apply {
            text = label
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(TextView(requireContext()).apply {
            text = value
            textSize = 11f
            textAlignment = View.TEXT_ALIGNMENT_VIEW_END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        return row
    }

    private fun dpToPx(dp: Int) =
        (dp * resources.displayMetrics.density + 0.5f).toInt()
}
