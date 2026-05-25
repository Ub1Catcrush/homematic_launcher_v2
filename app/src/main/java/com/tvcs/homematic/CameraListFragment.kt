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
 * CameraListFragment — shown inside the camera settings screen.
 *
 * Displays an ordered list of configured cameras with Add / Edit / Delete.
 * A SeekBar at the bottom controls the rotation interval (0 = off, 5..120 s).
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

                // ── Camera list ───────────────────────────────────────────────
                addView(sectionLabel(ctx, ctx.getString(R.string.pref_category_camera)))

                listLayout = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
                addView(listLayout)

                // Add button
                addView(Button(ctx).apply {
                    text = ctx.getString(R.string.camera_list_add)
                    setOnClickListener { openEditDialog(null) }
                })

                // ── Rotation ──────────────────────────────────────────────────
                addView(sectionLabel(ctx, ctx.getString(R.string.pref_category_camera_rotation)))

                rotationLabel = TextView(ctx).apply { textSize = 13f; setPadding(0, dp(4), 0, 0) }
                addView(rotationLabel)

                // 0 = off, then 5, 10, 15 ... 120
                rotationSeek = SeekBar(ctx).apply {
                    max = 25   // 0=off, 1=5s, 2=10s … 24=120s
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

    override fun onResume() {
        super.onResume()
        rebuildList()
    }

    // ── List ──────────────────────────────────────────────────────────────

    private fun rebuildList() {
        listLayout.removeAllViews()
        val ctx = requireContext()
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
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))

            // Up / down arrows
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(iconButton(ctx, "▲") {
                    if (idx > 0) {
                        val list = configs
                        val moved = list.removeAt(idx)
                        list.add(idx - 1, moved)
                        CameraConfigStore.save(prefs, list)
                        rebuildList()
                    }
                })
                addView(iconButton(ctx, "▼") {
                    if (idx < total - 1) {
                        val list = configs
                        val moved = list.removeAt(idx)
                        list.add(idx + 1, moved)
                        CameraConfigStore.save(prefs, list)
                        rebuildList()
                    }
                })
            })

            // Name + URL summary
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setPadding(dp(8), 0, dp(8), 0)
                addView(TextView(ctx).apply {
                    text = cfg.name.ifBlank { "Kamera ${idx + 1}" }
                    textSize = 14f
                    setTextColor(0xFFFFFFFF.toInt())
                })
                addView(TextView(ctx).apply {
                    text = listOf(cfg.rtspUrl, cfg.snapshotUrl)
                        .firstOrNull { it.isNotBlank() }
                        ?.let { if (it.length > 40) it.take(37) + "…" else it }
                        ?: "—"
                    textSize = 11f
                    setTextColor(0x99FFFFFF.toInt())
                })
            })

            // Edit
            addView(iconButton(ctx, "✏") { openEditDialog(cfg) })

            // Delete
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

    // ── Edit dialog ───────────────────────────────────────────────────────

    private fun openEditDialog(existing: CameraConfig?) {
        val ctx = requireContext()
        val isNew = existing == null

        val scroll = ScrollView(ctx)
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(8))
        }
        scroll.addView(layout)

        fun field(hint: String, value: String, inputType: Int = InputType.TYPE_CLASS_TEXT): EditText {
            return EditText(ctx).apply {
                this.hint      = hint
                setText(value)
                this.inputType = inputType
                setSingleLine(true)
                layout.addView(this)
            }
        }
        fun label(text: String) = layout.addView(
            TextView(ctx).apply { this.text = text; setTextColor(0xAAFFFFFF.toInt()); textSize = 11f; setPadding(0, dp(8), 0, 0) }
        )

        label(ctx.getString(R.string.camera_field_name))
        val fName = field(ctx.getString(R.string.camera_field_name_hint), existing?.name ?: "")

        label(ctx.getString(R.string.pref_title_camera_rtsp_url))
        val fRtsp = field("rtsp://…", existing?.rtspUrl ?: "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)

        label(ctx.getString(R.string.pref_title_camera_snapshot_url))
        val fSnap = field("http://…", existing?.snapshotUrl ?: "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)

        label(ctx.getString(R.string.pref_title_camera_username))
        val fUser = field("admin", existing?.username ?: "")

        label(ctx.getString(R.string.pref_title_camera_password))
        val fPass = field("••••••", existing?.password ?: "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)

        AlertDialog.Builder(ctx)
            .setTitle(if (isNew) ctx.getString(R.string.camera_list_add) else ctx.getString(R.string.camera_edit_title))
            .setView(scroll)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val updated = CameraConfig(
                    id          = existing?.id ?: java.util.UUID.randomUUID().toString(),
                    name        = fName.text.toString().trim().ifBlank { "Kamera" },
                    rtspUrl     = fRtsp.text.toString().trim(),
                    snapshotUrl = fSnap.text.toString().trim(),
                    username    = fUser.text.toString().trim(),
                    password    = fPass.text.toString()
                )
                val list = configs
                if (isNew) list.add(updated)
                else { val i = list.indexOfFirst { it.id == updated.id }; if (i >= 0) list[i] = updated else list.add(updated) }
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
            textSize = 16f
            setPadding(dp(6), 0, dp(6), 0)
            minWidth = 0; minimumWidth = 0
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            setOnClickListener { onClick() }
        }

    private fun sectionLabel(ctx: Context, title: String) =
        TextView(ctx).apply {
            text  = title
            textSize = 12f
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
