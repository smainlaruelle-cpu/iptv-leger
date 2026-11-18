package app.iptvleger

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

/** Stockage local léger : identifiants, favoris, reprises de lecture. */
object Store {
    private const val MAX_RECENTS = 40

    private fun p(c: Context): SharedPreferences =
        c.applicationContext.getSharedPreferences("iptv", Context.MODE_PRIVATE)

    // --- Identifiants ---

    fun creds(c: Context): Creds? {
        val sp = p(c)
        val server = sp.getString("server", null) ?: return null
        val user = sp.getString("user", null) ?: return null
        val pass = sp.getString("pass", null) ?: return null
        return Creds(server, user, pass)
    }

    fun saveCreds(c: Context, cr: Creds) {
        p(c).edit()
            .putString("server", cr.server)
            .putString("user", cr.user)
            .putString("pass", cr.pass)
            .apply()
    }

    // --- Listes (favoris, récents) ---

    private fun list(c: Context, name: String): MutableList<Item> {
        val out = ArrayList<Item>()
        try {
            val arr = JSONArray(p(c).getString(name, "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(Item.fromJson(o))
            }
        } catch (e: Exception) {
            // liste illisible : on repart de zéro
        }
        return out
    }

    private fun save(c: Context, name: String, items: List<Item>) {
        val arr = JSONArray()
        for (it in items) arr.put(it.toJson())
        p(c).edit().putString(name, arr.toString()).apply()
    }

    fun favs(c: Context, kind: String): List<Item> = list(c, "favs").filter { it.kind == kind }

    fun isFav(c: Context, item: Item): Boolean = list(c, "favs").any { it.key == item.key }

    /** Renvoie true si l'élément est maintenant en favori. */
    fun toggleFav(c: Context, item: Item): Boolean {
        val l = list(c, "favs")
        val removed = l.removeAll { it.key == item.key }
        if (!removed) l.add(0, item)
        save(c, "favs", l)
        return !removed
    }

    fun recents(c: Context, kind: String): List<Item> = list(c, "recents").filter { it.kind == kind }

    fun addRecent(c: Context, item: Item) {
        val l = list(c, "recents")
        l.removeAll { it.key == item.key }
        l.add(0, item)
        while (l.size > MAX_RECENTS) l.removeAt(l.size - 1)
        save(c, "recents", l)
    }

    // --- Positions de lecture ---

    fun getPos(c: Context, key: String): Long = p(c).getLong("pos:$key", 0L)

    fun setPos(c: Context, key: String, pos: Long, dur: Long) {
        val finished = dur > 0 && pos > dur * 0.95
        if (pos < 5000 || finished) {
            p(c).edit().remove("pos:$key").apply()
        } else {
            p(c).edit().putLong("pos:$key", pos).apply()
        }
    }

    fun lastEpisode(c: Context, seriesId: String): String? = p(c).getString("last:$seriesId", null)

    fun setLastEpisode(c: Context, seriesId: String, episodeId: String) {
        p(c).edit().putString("last:$seriesId", episodeId).apply()
    }
}
