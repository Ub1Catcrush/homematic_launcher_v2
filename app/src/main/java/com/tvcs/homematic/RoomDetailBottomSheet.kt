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
 * No longer calls HomeMatic directly. Reads from [HmRepository] which is stored
 * in [cachedRepo] before calling [show]. In production that is
 * [HomeMatic.asRepository()]; in tests inject any [FakeHmRepository].
 */
class RoomDetailBottomSheet : BottomSheetDialogFragment() {

    companion object {
        private const val TAG     = "RoomDetailSheet"
        private const val ARG_ISE = "room_ise_id"

        /** Set before calling show() — production code uses HomeMatic.asRepository(). */
        var cachedRepo: HmRepository? = null

        fun show(fm: FragmentManager, room: Room, repo: HmRepository) {
            if (fm.findFragmentByTag(TAG) != null) return
            cachedRepo = repo
            RoomDetailBottomSheet().apply {
                arguments = Bundle().apply { putInt(ARG_ISE, room.ise_id) }
            }.show(fm, TAG)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.bottom_sheet_room_detail, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val repo  = cachedRepo ?: run { dismiss(); return }
        val iseId = arguments?.getInt(ARG_ISE) ?: run { dismiss(); return }
        val state = repo.state ?: run { dismiss(); return }
        val prof  = repo.profile

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

            if (chan.datapoints.isEmpty()) {
                container.addView(makeValueRow("  –", getString(R.string.detail_no_datapoints)))
            }

            for (dp in chan.datapoints) {
                val isKnown = when {
                    dp.type in prof.setTempFields ->
                        dev != null && dev.device_type in prof.thermostatDeviceTypes
                    dp.type in prof.actualTempFields ->
                        dev != null && (dev.device_type in prof.tempDeviceTypes ||
                                        dev.device_type in prof.thermostatDeviceTypes)
                    dp.type in prof.humidityFields ->
                        dev != null && dev.device_type in prof.humidityDeviceTypes
                    dp.type in prof.stateFields ->
                        dev != null && dev.device_type in prof.windowDeviceTypes
                    dp.type in prof.lowbatFields ||
                    dp.type in prof.sabotageFields ||
                    dp.type in prof.faultFields -> true
                    else -> false
                }

                val label = dp.type + if (!isKnown) "  ★" else ""
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
                    (row.getChildAt(0) as? TextView)?.setTextColor(0xFFFFAA00.toInt())
                }
                container.addView(row)
            }
        }

        // ★ legend
        if (room.channels.any { rc ->
            val chan  = state.channels[rc.ise_id] ?: return@any false
            val devId = state.channel2Device[chan.ise_id]
            val dev   = devId?.let { state.devices[it] }
            chan.datapoints.any { dp ->
                !(when {
                    dp.type in prof.setTempFields     -> dev != null && dev.device_type in prof.thermostatDeviceTypes
                    dp.type in prof.actualTempFields  -> dev != null && (dev.device_type in prof.tempDeviceTypes || dev.device_type in prof.thermostatDeviceTypes)
                    dp.type in prof.humidityFields    -> dev != null && dev.device_type in prof.humidityDeviceTypes
                    dp.type in prof.stateFields       -> dev != null && dev.device_type in prof.windowDeviceTypes
                    dp.type in prof.lowbatFields || dp.type in prof.sabotageFields || dp.type in prof.faultFields -> true
                    else -> false
                })
            }
        }) {
            container.addView(TextView(requireContext()).apply {
                text = getString(R.string.detail_unknown_legend)
                textSize = 10f; alpha = 0.6f
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
            text = label; textSize = 11f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(TextView(requireContext()).apply {
            text = value; textSize = 11f
            textAlignment = View.TEXT_ALIGNMENT_VIEW_END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        return row
    }

    private fun dpToPx(dp: Int) = (dp * resources.displayMetrics.density + 0.5f).toInt()
}
