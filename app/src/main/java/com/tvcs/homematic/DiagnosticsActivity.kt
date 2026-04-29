package com.tvcs.homematic

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * DiagnosticsActivity — "Was hat die CCU geliefert, was nicht im Profil matcht?"
 *
 * Shows three lists after a CCU load:
 *   1. Unknown device_type values — in statelist but not in any profile category
 *   2. Unknown datapoint types — in state channels but not in any profile field set
 *   3. All known device_types seen — for easy copy-paste into the profile editor
 *
 * This makes profile configuration self-explaining: the user can see exactly which
 * new type strings need to be added without any guesswork.
 */
class DiagnosticsActivity : AppCompatActivity() {

    override fun attachBaseContext(base: Context) =
        super.attachBaseContext(LocaleHelper.wrap(base))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_diagnostics)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.title_diagnostics)

        val btnRefresh = findViewById<Button>(R.id.btn_diag_refresh)
        btnRefresh.setOnClickListener { renderDiagnostics() }
        renderDiagnostics()
    }

    override fun onOptionsItemSelected(item: MenuItem) =
        if (item.itemId == android.R.id.home) { finish(); true } else super.onOptionsItemSelected(item)

    private fun renderDiagnostics() {
        val state = HomeMatic.state
        val prof  = HomeMatic.profile

        val tvStatus = findViewById<TextView>(R.id.diag_load_status)
        val tvUnknownDevTypes  = findViewById<TextView>(R.id.diag_unknown_device_types)
        val tvUnknownDpTypes   = findViewById<TextView>(R.id.diag_unknown_dp_types)
        val tvKnownDevTypes    = findViewById<TextView>(R.id.diag_known_device_types)
        val tvStats            = findViewById<TextView>(R.id.diag_stats)

        if (state == null || !HomeMatic.isLoaded) {
            tvStatus.text = getString(R.string.diag_not_loaded)
            listOf(tvUnknownDevTypes, tvUnknownDpTypes, tvKnownDevTypes, tvStats).forEach {
                it.text = "–"
            }
            return
        }

        val allProfileDevTypes = prof.windowDeviceTypes + prof.thermostatDeviceTypes +
                prof.tempDeviceTypes + prof.humidityDeviceTypes

        val allProfileDpTypes  = prof.setTempFields + prof.actualTempFields +
                prof.humidityFields + prof.stateFields + prof.lowbatFields +
                prof.sabotageFields + prof.faultFields

        // Collect all device_type values seen in statelist
        val seenDevTypes     = mutableSetOf<String>()
        val unknownDevTypes  = mutableSetOf<String>()
        val seenDpTypes      = mutableSetOf<String>()
        val unknownDpTypes   = mutableSetOf<String>()

        for (dev in state.stateList.devices) {
            seenDevTypes.add(dev.device_type)
            if (dev.device_type.isNotBlank() && dev.device_type !in allProfileDevTypes)
                unknownDevTypes.add(dev.device_type)

            for (chan in dev.channels) {
                for (dp in chan.datapoints) {
                    if (dp.type.isBlank()) continue
                    seenDpTypes.add(dp.type)
                    if (dp.type !in allProfileDpTypes)
                        unknownDpTypes.add(dp.type)
                }
            }
        }

        // Also collect from devicelist
        for (dev in state.deviceList.devices) {
            seenDevTypes.add(dev.device_type)
            if (dev.device_type.isNotBlank() && dev.device_type !in allProfileDevTypes)
                unknownDevTypes.add(dev.device_type)
        }

        val loadAge = if (state.loadTime > 0L) {
            val secs = (System.currentTimeMillis() - state.loadTime) / 1000L
            getString(R.string.diag_load_age, secs)
        } else getString(R.string.diag_load_age_unknown)

        tvStatus.text = getString(R.string.diag_status_ok,
            state.deviceList.devices.size,
            state.roomList.rooms.size,
            loadAge
        )
        tvStats.text = getString(R.string.diag_stats,
            seenDevTypes.size, seenDpTypes.size,
            unknownDevTypes.size, unknownDpTypes.size
        )
        tvUnknownDevTypes.text = if (unknownDevTypes.isEmpty())
            getString(R.string.diag_none)
        else unknownDevTypes.sorted().joinToString("\n")

        tvUnknownDpTypes.text = if (unknownDpTypes.isEmpty())
            getString(R.string.diag_none)
        else unknownDpTypes.sorted().joinToString("\n")

        tvKnownDevTypes.text = if (seenDevTypes.isEmpty())
            getString(R.string.diag_none)
        else seenDevTypes.sorted().joinToString("\n")
    }

    companion object {
        fun start(context: Context) =
            context.startActivity(Intent(context, DiagnosticsActivity::class.java))
    }
}
