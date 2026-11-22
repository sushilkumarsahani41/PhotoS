package app.photos

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class Album(val id: String, val name: String, val members: Set<String>)

class CollectionsStore(context: Context) {
    private val prefs = context.getSharedPreferences("collections", Context.MODE_PRIVATE)
    fun favorites(): Set<String> = prefs.getStringSet("favorites", emptySet())!!.toSet()
    fun favorite(uri: String) {
        val old = favorites()
        prefs.edit().putStringSet("favorites", if (uri in old) old - uri else old + uri).apply()
    }
    fun albums(): List<Album> {
        val a = JSONArray(prefs.getString("albums", "[]"))
        return (0 until a.length()).map { i ->
            val j = a.getJSONObject(i); val members = j.getJSONArray("members")
            Album(j.getString("id"), j.getString("name"), (0 until members.length()).map { members.getString(it) }.toSet())
        }
    }
    fun save(name: String, members: Set<String>, id: String? = null) {
        require(name.isNotBlank())
        val previous = albums()
        val album = Album(id ?: UUID.randomUUID().toString(), name.trim().take(80), members + (previous.find { it.id == id }?.members ?: emptySet()))
        val all = previous.filterNot { it.id == album.id } + album
        val json = JSONArray()
        all.forEach { json.put(JSONObject().put("id", it.id).put("name", it.name).put("members", JSONArray(it.members.toList()))) }
        prefs.edit().putString("albums", json.toString()).apply()
    }
}
