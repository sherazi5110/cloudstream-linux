package android.content

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** SharedPreferences persisted as one JSON object per preferences name. */
class FileSharedPreferences(private val file: File) : SharedPreferences {
    private val values: MutableMap<String, Any?> = load()

    private fun load(): MutableMap<String, Any?> {
        if (!file.exists()) return LinkedHashMap()
        val json = JSONObject(file.readText())
        return json.keySet().associateWithTo(LinkedHashMap()) { key ->
            when (val v = json.get(key)) {
                is JSONArray -> v.map { it.toString() }.toSet()
                JSONObject.NULL -> null
                else -> v
            }
        }
    }

    @Synchronized
    private fun save() {
        file.parentFile.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(JSONObject(values).toString(2))
        tmp.renameTo(file)
    }

    @Synchronized override fun getAll(): Map<String, *> = HashMap(values)
    @Synchronized override fun getString(key: String, defValue: String?) = values[key] as? String ?: defValue
    @Suppress("UNCHECKED_CAST")
    @Synchronized override fun getStringSet(key: String, defValues: Set<String>?) = values[key] as? Set<String> ?: defValues
    @Synchronized override fun getInt(key: String, defValue: Int) = (values[key] as? Number)?.toInt() ?: defValue
    @Synchronized override fun getLong(key: String, defValue: Long) = (values[key] as? Number)?.toLong() ?: defValue
    @Synchronized override fun getFloat(key: String, defValue: Float) = (values[key] as? Number)?.toFloat() ?: defValue
    @Synchronized override fun getBoolean(key: String, defValue: Boolean) = values[key] as? Boolean ?: defValue
    @Synchronized override fun contains(key: String) = values.containsKey(key)
    override fun edit(): SharedPreferences.Editor = EditorImpl()

    private inner class EditorImpl : SharedPreferences.Editor {
        private val changes = LinkedHashMap<String, Any?>()
        private val removed = HashSet<String>()
        private var clear = false

        override fun putString(key: String, value: String?) = apply { changes[key] = value }
        override fun putStringSet(key: String, values: Set<String>?) = apply { changes[key] = values?.toSet() }
        override fun putInt(key: String, value: Int) = apply { changes[key] = value }
        override fun putLong(key: String, value: Long) = apply { changes[key] = value }
        override fun putFloat(key: String, value: Float) = apply { changes[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { changes[key] = value }
        override fun remove(key: String) = apply { removed += key }
        override fun clear() = apply { clear = true }

        override fun commit(): Boolean {
            synchronized(this@FileSharedPreferences) {
                if (clear) values.clear()
                removed.forEach { values.remove(it) }
                changes.forEach { (k, v) -> if (v == null) values.remove(k) else values[k] = v }
                save()
            }
            return true
        }

        override fun apply() {
            commit()
        }
    }
}
