package com.tvcs.homematic

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.SeekBar
import android.widget.TextView
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder

/**
 * SeekBarPreference
 *
 * A Preference that shows an inline SeekBar with live value display.
 * Stores its value as a String (for compatibility with EditTextPreference).
 *
 * XML attributes (all optional, use app: namespace with declare-styleable):
 *   motionMin      minimum value (default 1)
 *   motionMax      maximum value (default 100)
 *   motionStep     step size     (default 1)
 *   motionUnit     unit suffix displayed after value (default "")
 *
 * Because we cannot add custom attrs without a full declare-styleable,
 * we pass min/max/step/unit via the key naming convention handled in
 * SettingsActivity, or configure them programmatically.
 */
class SeekBarPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : Preference(context, attrs) {

    var min:  Int    = 1
    var max:  Int    = 100
    var step: Int    = 1
    var unit: String = ""

    private var currentValue: Int = min

    init {
        layoutResource = R.layout.pref_seekbar
        isPersistent   = true
    }

    override fun onGetDefaultValue(a: android.content.res.TypedArray, index: Int): Any =
        a.getString(index) ?: min.toString()

    override fun onSetInitialValue(defaultValue: Any?) {
        val default = (defaultValue as? String)?.toIntOrNull() ?: min
        currentValue = getPersistedString(default.toString()).toIntOrNull()?.coerceIn(min, max) ?: default
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val seekBar  = holder.findViewById(R.id.seekbar)      as? SeekBar  ?: return
        val tvValue  = holder.findViewById(R.id.seekbar_value) as? TextView ?: return
        val tvMin    = holder.findViewById(R.id.seekbar_min)   as? TextView
        val tvMax    = holder.findViewById(R.id.seekbar_max)   as? TextView

        seekBar.max      = (max - min) / step
        seekBar.progress = ((currentValue - min) / step).coerceIn(0, seekBar.max)
        tvValue.text     = "${currentValue}${unit}"
        tvMin?.text      = "$min"
        tvMax?.text      = "$max"

        // Disable interaction if preference is disabled
        seekBar.isEnabled = isEnabled

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                currentValue = min + progress * step
                tvValue.text = "${currentValue}${unit}"
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {
                if (callChangeListener(currentValue.toString())) {
                    persistString(currentValue.toString())
                    notifyChanged()
                }
            }
        })
    }

    /** Call from code to set value programmatically. */
    fun setValue(v: Int) {
        val clamped = v.coerceIn(min, max)
        if (callChangeListener(clamped.toString())) {
            currentValue = clamped
            persistString(clamped.toString())
            notifyChanged()
        }
    }

    fun getValue(): Int = currentValue
}
