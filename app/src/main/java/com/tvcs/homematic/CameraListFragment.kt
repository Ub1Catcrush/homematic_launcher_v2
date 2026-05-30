package com.tvcs.homematic

import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.*
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.preference.PreferenceManager

/**
 * CameraListFragment — camera settings screen.
 *
 * Features:
 *  • Add / edit / delete cameras with dual-resolution URLs (High + Low)
 *  • Per-camera resolution toggle (High / Low)
 *  • Per-camera engine stack: drag-to-reorder EXO / VLC / SNAPSHOT,
 *    individual enable/disable toggles
 *  • "Reset player" button — clears any permanent engine-skip state and
 *    forces a fresh attempt from the top of the stack
 *  • Camera rotation interval SeekBar
 */
class CameraListFragment : Fragment() {

    private val prefs by lazy { PreferenceManager.getDefaultSharedPreferences(requireContext()) }
    private val configs get() = CameraConfigStore.load(prefs).toMutableList()

    private lateinit var listLayout:    LinearLayout
    private lateinit var rotationLabel: TextView
    private lateinit var rotationSeek:  SeekBar

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View {
        val ctx = requireContext()
        return ScrollView(ctx).apply {
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(8), dp(16), dp(16))

                addView(sectionLabel(ctx, ctx.getString(R.string.pref_category_camera)))

                listLayout = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
                addView(listLayout)

                addView(Button(ctx).apply {
                    text = ctx.getString(R.string.camera_list_add)
                    setOnClickListener { openEditDialog(null) }
                })

                // ── Rotation ──────────────────────────────────────────────────
                addView(sectionLabel(ctx, ctx.getString(R.string.pref_category_camera_rotation)))
                rotationLabel = TextView(ctx).apply { textSize = 13f; setPadding(0, dp(4), 0, 0) }
                addView(rotationLabel)
                rotationSeek = SeekBar(ctx).apply {
                    max = 25
                    progress = secToProgress(CameraConfigStore.loadRotationSec(prefs))
                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(sb: SeekBar, p: Int, user: Boolean) {
                            val sec = progressToSec(p)
                            CameraConfigStore.saveRotationSec(prefs, sec)
                            updateRotationLabel(sec)
                        }
                        override fun onStartTrackingTouch(sb: SeekBar) {}
                        override fun onStopTrackingTouch(sb: SeekBar) {}
                    })
                }
                addView(rotationSeek)
                updateRotationLabel(progressToSec(rotationSeek.progress))
            })
        }
    }

    override fun onResume() { super.onResume(); rebuildList() }

    // ── Camera list ───────────────────────────────────────────────────────

    private fun rebuildList() {
        listLayout.removeAllViews()
        val ctx  = requireContext()
        val list = configs
        if (list.isEmpty()) {
            listLayout.addView(TextView(ctx).apply {
                text = ctx.getString(R.string.camera_list_empty)
                setTextColor(0x99FFFFFF.toInt())
                setPadding(0, dp(4), 0, dp(8))
            })
            return
        }
        list.forEachIndexed { idx, cfg ->
            listLayout.addView(buildRow(ctx, cfg, idx, list.size))
        }
    }

    private fun buildRow(ctx: Context, cfg: CameraConfig, idx: Int, total: Int): View {
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))

            // ── Up / down ────────────────────────────────────────────────────
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(iconButton(ctx, "▲") {
                    if (idx > 0) moveCamera(idx, idx - 1)
                })
                addView(iconButton(ctx, "▼") {
                    if (idx < total - 1) moveCamera(idx, idx + 1)
                })
            })

            // ── Info column ──────────────────────────────────────────────────
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setPadding(dp(8), 0, dp(8), 0)

                addView(TextView(ctx).apply {
                    text      = cfg.name.ifBlank { "Kamera ${idx + 1}" }
                    textSize  = 14f
                    setTextColor(0xFFFFFFFF.toInt())
                })

                // Active URL summary
                val activeUrl = cfg.rtspUrl.ifBlank { cfg.snapshotUrl }
                addView(TextView(ctx).apply {
                    text = buildString {
                        append(if (cfg.useHighRes) "HD  " else "SD  ")
                        append(activeUrl.let { if (it.length > 35) it.take(32) + "…" else it }.ifBlank { "—" })
                    }
                    textSize = 11f
                    setTextColor(0x99FFFFFF.toInt())
                })

                // Engine stack summary
                val engines = cfg.engineOrder.ifEmpty { listOf("exo", "vlc", "snapshot") }
                addView(TextView(ctx).apply {
                    text = engines.joinToString(" → ") { e ->
                        if (e.startsWith("-")) "[${e.drop(1).uppercase()}]"
                        else e.uppercase()
                    }
                    textSize = 10f
                    setTextColor(0x77FFFFFF.toInt())
                })
            })

            // ── Res toggle ───────────────────────────────────────────────────
            addView(Button(ctx).apply {
                text = if (cfg.useHighRes) "HD" else "SD"
                textSize = 11f
                setPadding(dp(4), 0, dp(4), 0)
                minWidth = 0; minimumWidth = 0
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
                setOnClickListener {
                    val list = configs
                    val i = list.indexOfFirst { it.id == cfg.id }
                    if (i >= 0) {
                        list[i] = list[i].copy(useHighRes = !list[i].useHighRes)
                        CameraConfigStore.save(prefs, list)
                        rebuildList()
                    }
                }
            })

            // ── Edit ─────────────────────────────────────────────────────────
            addView(iconButton(ctx, "✏") { openEditDialog(cfg) })

            // ── Delete ───────────────────────────────────────────────────────
            addView(iconButton(ctx, "🗑") {
                AlertDialog.Builder(ctx)
                    .setTitle(ctx.getString(R.string.camera_delete_title))
                    .setMessage(ctx.getString(R.string.camera_delete_msg, cfg.name))
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        val list = configs
                        list.removeAll { it.id == cfg.id }
                        CameraConfigStore.save(prefs, list)
                        rebuildList()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            })
        }
    }

    private fun moveCamera(from: Int, to: Int) {
        val list  = configs
        val moved = list.removeAt(from)
        list.add(to, moved)
        CameraConfigStore.save(prefs, list)
        rebuildList()
    }

    // ── Edit dialog ───────────────────────────────────────────────────────

    private fun openEditDialog(existing: CameraConfig?) {
        val ctx   = requireContext()
        val isNew = existing == null

        val scroll = ScrollView(ctx)
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(8))
        }
        scroll.addView(layout)

        fun label(text: String) = layout.addView(
            TextView(ctx).apply {
                this.text = text
                setTextColor(0xAAFFFFFF.toInt())
                textSize = 11f
                setPadding(0, dp(8), 0, 0)
            }
        )

        fun field(hint: String, value: String, inputType: Int = InputType.TYPE_CLASS_TEXT): EditText {
            return EditText(ctx).apply {
                this.hint      = hint
                setText(value)
                this.inputType = inputType
                setSingleLine(true)
                layout.addView(this)
            }
        }

        label(ctx.getString(R.string.camera_field_name))
        val fName = field(ctx.getString(R.string.camera_field_name_hint), existing?.name ?: "")

        // ── High-res ─────────────────────────────────────────────────────────
        label("${ctx.getString(R.string.pref_title_camera_rtsp_url)} (HD)")
        val fRtspHi = field("rtsp://…/high", existing?.rtspUrlHigh ?: "",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)

        label("${ctx.getString(R.string.pref_title_camera_snapshot_url)} (HD)")
        val fSnapHi = field("http://…/high", existing?.snapshotUrlHigh ?: "",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)

        // ── Low-res ──────────────────────────────────────────────────────────
        label("${ctx.getString(R.string.pref_title_camera_rtsp_url)} (SD)")
        val fRtspLo = field("rtsp://…/low", existing?.rtspUrlLow ?: "",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)

        label("${ctx.getString(R.string.pref_title_camera_snapshot_url)} (SD)")
        val fSnapLo = field("http://…/low", existing?.snapshotUrlLow ?: "",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)

        // ── Resolution toggle ─────────────────────────────────────────────────
        label("Aktive Auflösung")
        var useHigh = existing?.useHighRes ?: true
        val resButton = Button(ctx).apply {
            text = if (useHigh) "HD (High-Res)" else "SD (Low-Res)"
            setOnClickListener {
                useHigh = !useHigh
                text = if (useHigh) "HD (High-Res)" else "SD (Low-Res)"
            }
            layout.addView(this)
        }

        // ── Credentials ───────────────────────────────────────────────────────
        label(ctx.getString(R.string.pref_title_camera_username))
        val fUser = field("admin", existing?.username ?: "")

        label(ctx.getString(R.string.pref_title_camera_password))
        val fPass = field("••••••", existing?.password ?: "",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)

        // ── Engine stack ──────────────────────────────────────────────────────
        label("Player-Reihenfolge (Tap zum De-/Aktivieren, Pfeile zum Sortieren)")

        val defaultOrder = listOf("exo", "vlc", "snapshot")
        // Start from existing config or default
        val initOrder = (existing?.engineOrder?.ifEmpty { null } ?: defaultOrder).toMutableList()
        // Make sure all three engines are present (even if disabled)
        defaultOrder.forEach { name ->
            if (!initOrder.any { it.trimStart('-') == name }) initOrder.add("-$name")
        }

        val engineStack = initOrder.toMutableList()

        val engineContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layout.addView(this)
        }

        fun rebuildEngineRows() {
            engineContainer.removeAllViews()
            engineStack.forEachIndexed { ei, entry ->
                val disabled = entry.startsWith("-")
                val name     = entry.trimStart('-')
                val label    = when (name) {
                    "exo"      -> "Media3 / ExoPlayer (RTSP)"
                    "vlc"      -> "libVLC (RTSP)"
                    "snapshot" -> "MJPEG Snapshot"
                    else       -> name
                }
                engineContainer.addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity     = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(2), 0, dp(2))

                    // Up / down
                    addView(LinearLayout(ctx).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(iconButton(ctx, "▲") {
                            if (ei > 0) { engineStack.add(ei - 1, engineStack.removeAt(ei)); rebuildEngineRows() }
                        })
                        addView(iconButton(ctx, "▼") {
                            if (ei < engineStack.lastIndex) { engineStack.add(ei + 1, engineStack.removeAt(ei)); rebuildEngineRows() }
                        })
                    })

                    // Enable/disable toggle + label
                    addView(LinearLayout(ctx).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity     = Gravity.CENTER_VERTICAL
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        setPadding(dp(6), 0, 0, 0)

                        val toggle = CheckBox(ctx).apply {
                            isChecked = !disabled
                            setOnCheckedChangeListener { _, checked ->
                                engineStack[ei] = if (checked) name else "-$name"
                            }
                        }
                        addView(toggle)
                        addView(TextView(ctx).apply {
                            text = "${ei + 1}. $label"
                            textSize = 12f
                            setTextColor(if (disabled) 0x66FFFFFF.toInt() else 0xFFFFFFFF.toInt())
                            setPadding(dp(6), 0, 0, 0)
                        })
                    })
                })
            }
        }
        rebuildEngineRows()

        // ── Reset player button ───────────────────────────────────────────────
        label("Player-Reset")
        layout.addView(Button(ctx).apply {
            text = "🔄  Player-Automatik zurücksetzen"
            setOnClickListener {
                // Notify the activity / fragment result so MainActivity can call
                // resetEngineSkip() on the affected CameraViewController.
                // We use a SharedPrefs flag that MainActivity watches.
                val id = existing?.id ?: ""
                prefs.edit()
                    .putString("camera_reset_engine_skip_id", id)
                    .putLong("camera_reset_engine_skip_ts", System.currentTimeMillis())
                    .apply()
                Toast.makeText(ctx, "Player-Automatik zurückgesetzt", Toast.LENGTH_SHORT).show()
            }
        })

        AlertDialog.Builder(ctx)
            .setTitle(if (isNew) ctx.getString(R.string.camera_list_add) else ctx.getString(R.string.camera_edit_title))
            .setView(scroll)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val updated = CameraConfig(
                    id                  = existing?.id ?: java.util.UUID.randomUUID().toString(),
                    name                = fName.text.toString().trim().ifBlank { "Kamera" },
                    rtspUrlHigh         = fRtspHi.text.toString().trim(),
                    snapshotUrlHigh     = fSnapHi.text.toString().trim(),
                    rtspUrlLow          = fRtspLo.text.toString().trim(),
                    snapshotUrlLow      = fSnapLo.text.toString().trim(),
                    useHighRes          = useHigh,
                    username            = fUser.text.toString().trim(),
                    password            = fPass.text.toString(),
                    engineOrder         = engineStack.toList()
                )
                val list = configs
                if (isNew) list.add(updated)
                else {
                    val i = list.indexOfFirst { it.id == updated.id }
                    if (i >= 0) list[i] = updated else list.add(updated)
                }
                CameraConfigStore.save(prefs, list)
                rebuildList()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun iconButton(ctx: Context, text: String, onClick: () -> Unit) =
        Button(ctx).apply {
            this.text = text
            textSize  = 16f
            setPadding(dp(6), 0, dp(6), 0)
            minWidth = 0; minimumWidth = 0
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            setOnClickListener { onClick() }
        }

    private fun sectionLabel(ctx: Context, title: String) =
        TextView(ctx).apply {
            text      = title
            textSize  = 12f
            setTextColor(0xAAFFFFFF.toInt())
            setPadding(0, dp(12), 0, dp(4))
        }

    private fun updateRotationLabel(sec: Int) {
        rotationLabel.text = if (sec == 0)
            requireContext().getString(R.string.camera_rotation_off)
        else
            requireContext().getString(R.string.camera_rotation_every, sec)
    }

    private fun progressToSec(p: Int) = if (p == 0) 0 else p * 5
    private fun secToProgress(s: Int) = if (s == 0) 0 else (s / 5).coerceIn(1, 24)
    private fun dp(n: Int) = (n * resources.displayMetrics.density + 0.5f).toInt()
}
