package com.tvcs.homematic

import android.content.Context
import android.graphics.Color
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.json.JSONArray

/**
 * HaTileViewController
 *
 * Manages the Home Assistant entity tile shown in the room grid.
 * Subscribes to [HaRepository.entityStates] and rebuilds the tile view
 * whenever a watched entity changes state.
 *
 * Entity config is stored as JSON array in SharedPreferences (HA_ENTITIES):
 *   [{"entity_id":"sensor.pv_power","label":"PV","unit":"W","icon":"☀️"}, …]
 *
 * The tile itself is a [LinearLayout] that the [RoomAdapter] displays in the
 * same slot as the weather tile — position 0 in the grid (if enabled).
 */
class HaTileViewController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    /** Called whenever tile content changes so the adapter can refresh. */
    private val onTileChanged: () -> Unit,
    /**
     * Optional: per-instance tile config. When non-null, this controller
     * uses the given config instead of reading HA_ENTITIES from prefs.
     * Used for the multi-tile feature. When null, falls back to legacy prefs.
     */
    private var tileConfig: HaTileConfig? = null
) : DefaultLifecycleObserver {

    companion object {
        private const val TAG = "HaTileVC"

        /** Parse the HA_ENTITIES JSON string into a list of EntityRow configs. */
        /**
         * Serialise/deserialise a list of HaTileConfig objects.
         * Format: [{"id":"tile_1","title":"Solar","entities":[...]}, …]
         */
        fun parseTiles(json: String): List<HaTileConfig> {
            if (json.isBlank()) return emptyList()
            return try {
                val arr = org.json.JSONArray(json)
                (0 until arr.length()).mapNotNull { i ->
                    val o   = arr.getJSONObject(i)
                    val id  = o.optString("id", "tile_$i").ifBlank { "tile_$i" }
                    val title = o.optString("title", "").ifBlank { "Home Assistant" }
                    val entities = parseEntities(o.optString("entities", ""))
                    HaTileConfig(id = id, title = title, entities = entities)
                }
            } catch (e: Exception) {
                Log.w(TAG, "parseTiles error: ${e.message}")
                emptyList()
            }
        }

        fun tilesToJson(tiles: List<HaTileConfig>): String {
            val arr = org.json.JSONArray()
            tiles.forEach { tile ->
                val entArr = org.json.JSONArray()
                tile.entities.forEach { e ->
                    entArr.put(org.json.JSONObject().apply {
                        put("entity_id", e.entityId)
                        put("label",     e.label)
                        put("unit",      e.unit)
                        put("icon",      e.icon)
                    })
                }
                arr.put(org.json.JSONObject().apply {
                    put("id",       tile.id)
                    put("title",    tile.title)
                    put("entities", entArr.toString())
                })
            }
            return arr.toString()
        }

        fun parseEntities(json: String): List<EntityRow> {
            if (json.isBlank()) return emptyList()
            return try {
                val arr = JSONArray(json)
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.getJSONObject(i)
                    val eid = o.optString("entity_id", "").trim()
                    if (eid.isEmpty()) null
                    else EntityRow(
                        entityId = eid,
                        label    = o.optString("label", "").ifBlank { eid.substringAfter(".") },
                        unit     = o.optString("unit", ""),
                        icon     = o.optString("icon", "")
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "parseEntities error: ${e.message}")
                emptyList()
            }
        }
    }

    data class EntityRow(
        val entityId: String,
        val label:    String,
        val unit:     String,
        val icon:     String
    )

    /** Represents one complete HA tile card with its own title and entity list. */
    data class HaTileConfig(
        val id:       String,
        val title:    String,
        val entities: List<EntityRow>
    )

    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)

    // Currently rendered tile view — recycled by RoomAdapter
    @Volatile private var tileView: LinearLayout? = null
    private var stateJob: Job? = null

    fun isEnabled(): Boolean = prefs.getBoolean(PreferenceKeys.HA_TILE_ENABLED, false)
            && wsUrl().isNotBlank() && token().isNotBlank()

    fun tileTitle(): String {
        val cfg = tileConfig
        if (cfg != null) return cfg.title
        return prefs.getString(PreferenceKeys.HA_TILE_TITLE, "").takeIf { !it.isNullOrBlank() }
            ?: context.getString(R.string.ha_tile_default_title)
    }

    private fun wsUrl()  = prefs.getString(PreferenceKeys.HA_WS_URL,    "") ?: ""
    private fun token()  = prefs.getString(PreferenceKeys.HA_TOKEN,     "") ?: ""
    private fun entities(): List<EntityRow> {
        val cfg = tileConfig
        return if (cfg != null) cfg.entities
        else parseEntities(prefs.getString(PreferenceKeys.HA_ENTITIES, "") ?: "")
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    fun attachToLifecycle(owner: LifecycleOwner) = owner.lifecycle.addObserver(this)

    override fun onStart(owner: LifecycleOwner) {
        try {
            if (isEnabled()) startWatching()
        } catch (e: Exception) {
            Log.e(TAG, "onStart HA init failed: ${e.message}", e)
            // HA tile will show error state — rest of app starts normally
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        stateJob?.cancel()
        stateJob = null
    }

    override fun onDestroy(owner: LifecycleOwner) {
        stateJob?.cancel()
    }

    /** Update the tile config (for multi-tile edits) without recreating the controller. */
    fun updateConfig(config: HaTileConfig) {
        tileConfig = config
        applyPrefsChange()
    }

    /** Call after settings change to reconnect / disconnect as needed. */
    fun applyPrefsChange() {
        stateJob?.cancel()
        try {
            if (isEnabled()) {
                HaRepository.reconnect(wsUrl(), token())
                startWatching()
            } else {
                HaRepository.disconnect()
                tileView = null
                onTileChanged()
            }
        } catch (e: Exception) {
            Log.e(TAG, "applyPrefsChange failed: ${e.message}", e)
            tileView = null
        }
    }

    // ── Watching ──────────────────────────────────────────────────────────────

    private fun startWatching() {
        try {
            HaRepository.connect(wsUrl(), token())
        } catch (e: Exception) {
            Log.e(TAG, "connect() failed: ${e.message}", e)
            // Connection error is shown in the tile via ConnState.Error — app continues
            return
        }
        stateJob?.cancel()
        stateJob = lifecycleOwner.lifecycleScope.launch {
            try {
                HaRepository.entityStates.collectLatest { states ->
                    try {
                        val rows = entities()
                        // Only rebuild if at least one watched entity is present
                        if (rows.isEmpty() || states.isEmpty()) return@collectLatest
                        val watched = rows.map { it.entityId }.toSet()
                        if (states.keys.none { it in watched }) return@collectLatest
                        tileView = buildTile(rows, states)
                        onTileChanged()
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e   // propagate cancellation correctly
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "State update failed: ${e.message}", e)
                        // Keep existing tileView — don't crash, just skip this update
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e   // normal lifecycle cancellation — must not be swallowed
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "collectLatest failed: ${e.message}", e)
            }
        }
    }

    // ── View building ─────────────────────────────────────────────────────────

    /**
     * Returns the current tile view, or a safe error placeholder if building fails.
     * RoomAdapter calls this to get the view to embed.
     */
    fun buildRoomTile(): LinearLayout {
        return try {
            tileView ?: buildTile(entities(), HaRepository.entityStates.value)
        } catch (e: Exception) {
            Log.e(TAG, "buildRoomTile failed: ${e.message}", e)
            buildErrorTile()
        }
    }

    private fun buildTile(
        rows:   List<EntityRow>,
        states: Map<String, HaRepository.EntityState>
    ): LinearLayout {
        val dp      = context.resources.displayMetrics.density
        val isLand  = context.resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val textSp  = AppThemeHelper.fontRoomData(context).let { if (isLand) it * 0.87f else it }
        val textCol = AppThemeHelper.textRoom(context)
        val dimCol  = AppThemeHelper.textDim(context)

        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (4 * dp).toInt()
            setPadding(pad, pad, pad, pad)

            val connState = HaRepository.connState.value
            if (connState is HaRepository.ConnState.Error ||
                connState is HaRepository.ConnState.Connecting ||
                connState is HaRepository.ConnState.Authenticating) {

                // Show connection status hint
                val hint = when (connState) {
                    is HaRepository.ConnState.Connecting     -> "⏳ Verbinde…"
                    is HaRepository.ConnState.Authenticating -> "🔑 Authentifiziere…"
                    is HaRepository.ConnState.Error          -> "⚠ ${connState.message}"
                }
                if (hint.isNotEmpty()) addView(buildStatusRow(hint, textSp, 0xFFFFAA00.toInt()))
            }

            val table = TableLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                setStretchAllColumns(true)
            }

            if (rows.isEmpty()) {
                table.addView(buildRow(
                    "–", context.getString(R.string.ha_tile_no_entities), textSp, dimCol, dimCol
                ))
            } else {
                rows.forEach { cfg ->
                    val es    = states[cfg.entityId]
                    val value = formatValue(es, cfg)
                    val label = buildLabel(cfg)
                    val valColor = colorForState(es, textCol)
                    table.addView(buildRow(label, value, textSp, dimCol, valColor))
                }
            }

            addView(table)
        }
    }

    private fun buildStatusRow(text: String, textSp: Float, color: Int) =
        TextView(context).apply {
            this.text = text
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp * 0.85f)
            gravity = Gravity.CENTER_HORIZONTAL
        }

    private fun buildRow(
        label: String, value: String,
        textSp: Float, labelColor: Int, valueColor: Int
    ): TableRow = TableRow(context).apply {
        val tv1 = TextView(context).apply {
            text = label
            setTextColor(labelColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp)
            layoutParams = TableRow.LayoutParams(0, TableRow.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tv2 = TextView(context).apply {
            text = value
            setTextColor(valueColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp)
            gravity = Gravity.END
        }
        addView(tv1); addView(tv2)
    }

    private fun buildLabel(cfg: EntityRow): String {
        val icon = cfg.icon.trim()
        return if (icon.isEmpty()) cfg.label else "$icon ${cfg.label}"
    }

    private fun formatValue(es: HaRepository.EntityState?, cfg: EntityRow): String {
        if (es == null) return "–"
        val raw   = es.state
        // Try numeric formatting
        val num   = raw.toDoubleOrNull()
        val unit  = cfg.unit.ifBlank { es.attributes["unit_of_measurement"] ?: "" }
        val formatted = if (num != null) {
            if (num == num.toLong().toDouble()) "${num.toLong()}" else "%.1f".format(num)
        } else {
            raw
        }
        return if (unit.isNotEmpty()) "$formatted $unit" else formatted
    }

    /** Minimal fallback tile shown when view construction throws unexpectedly. */
    private fun buildErrorTile(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val pad = (4 * context.resources.displayMetrics.density).toInt()
        setPadding(pad, pad, pad, pad)
        addView(buildStatusRow("⚠ HA-Fehler", AppThemeHelper.fontRoomData(context), 0xFFFF6666.toInt()))
    }

    /** Returns a text color based on the entity's state (on/off/unavailable/numeric). */
    private fun colorForState(es: HaRepository.EntityState?, default: Int): Int {
        if (es == null) return 0xFFAAAAAA.toInt()
        return when (es.state.lowercase()) {
            "unavailable", "unknown" -> 0xFF888888.toInt()
            "on"   -> 0xFF88DD88.toInt()
            "off"  -> 0xFFAAAAAA.toInt()
            else   -> default
        }
    }
}
