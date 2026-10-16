package nl.jdatje.framily.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Settings to and from a small JSON file, e.g. to move them to a new phone.
 * Only settings: not the scan results or the family faces (those are rebuilt by the scan).
 */
object SettingsBackup {
    /** Things that belong to this moment or this phone's scan, not to your preferences. */
    private val SKIP = setOf(
        Prefs.CURRENT, Prefs.CAPTION_TEXT, Prefs.WEATHER_DATA, Prefs.SCORER,
    )

    fun export(prefs: Prefs): String {
        val values = JSONObject()
        for ((key, v) in prefs.sp.all) {
            if (key in SKIP || v == null) continue
            val entry = JSONObject()
            when (v) {
                is Boolean -> entry.put("type", "bool").put("value", v)
                is Int -> entry.put("type", "int").put("value", v)
                is Long -> entry.put("type", "long").put("value", v)
                is Float -> entry.put("type", "float").put("value", v.toDouble())
                is String -> entry.put("type", "string").put("value", v)
                is Set<*> -> entry.put("type", "set").put("value", JSONArray(v.map { it.toString() }))
                else -> continue
            }
            values.put(key, entry)
        }
        return JSONObject()
            .put("app", "Framily")
            .put("version", 1)
            .put("settings", values)
            .toString(2)
    }

    /** Returns how many settings were restored. Throws when the file isn't a Framily backup. */
    fun import(prefs: Prefs, json: String): Int {
        val root = JSONObject(json)
        require(root.optString("app") == "Framily") { "This isn't a Framily settings file." }
        val values = root.getJSONObject("settings")
        val editor = prefs.sp.edit()
        var count = 0
        for (key in values.keys()) {
            if (key in SKIP) continue
            val e = values.getJSONObject(key)
            when (e.getString("type")) {
                "bool" -> editor.putBoolean(key, e.getBoolean("value"))
                "int" -> editor.putInt(key, e.getInt("value"))
                "long" -> editor.putLong(key, e.getLong("value"))
                "float" -> editor.putFloat(key, e.getDouble("value").toFloat())
                "string" -> editor.putString(key, e.getString("value"))
                "set" -> {
                    val a = e.getJSONArray("value")
                    editor.putStringSet(key, (0 until a.length()).map { a.getString(it) }.toSet())
                }
                else -> continue
            }
            count++
        }
        editor.apply()
        return count
    }
}
