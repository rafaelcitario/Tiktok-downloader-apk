package app.baixadorhq

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Histórico dos últimos downloads (guardado como JSON em SharedPreferences). */
class History(context: Context) {

    data class Entry(
        val url: String,
        val title: String,
        val author: String,
        val uri: String,
        val audio: Boolean,
        val time: Long,
    )

    private val prefs = context.applicationContext.getSharedPreferences("history", Context.MODE_PRIVATE)

    fun all(): List<Entry> = synchronized(LOCK) { load() }

    fun add(entry: Entry) = synchronized(LOCK) {
        save((listOf(entry) + load().filterNot { it.uri == entry.uri }).take(MAX))
    }

    fun remove(entry: Entry) = synchronized(LOCK) {
        save(load().filterNot { it.uri == entry.uri && it.time == entry.time })
    }

    fun hasUrl(url: String): Boolean = all().any { !it.audio && key(it.url) == key(url) }

    private fun load(): List<Entry> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        val array = try {
            JSONArray(raw)
        } catch (e: JSONException) {
            return emptyList()
        }
        val result = ArrayList<Entry>()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            result.add(
                Entry(
                    url = o.optString("url"),
                    title = o.optString("title"),
                    author = o.optString("author"),
                    uri = o.optString("uri"),
                    audio = o.optBoolean("audio"),
                    time = o.optLong("time"),
                ),
            )
        }
        return result
    }

    private fun save(list: List<Entry>) {
        val array = JSONArray()
        for (e in list) {
            array.put(
                JSONObject()
                    .put("url", e.url)
                    .put("title", e.title)
                    .put("author", e.author)
                    .put("uri", e.uri)
                    .put("audio", e.audio)
                    .put("time", e.time),
            )
        }
        prefs.edit().putString(KEY, array.toString()).apply()
        version.update { it + 1 }
    }

    companion object {
        private val LOCK = Any()
        private const val KEY = "items"
        private const val MAX = 100

        /** Sobe a cada mudança; a tela observa para se atualizar sozinha. */
        val version = MutableStateFlow(0)

        fun key(url: String): String = url.substringBefore('?').trimEnd('/')
    }
}
