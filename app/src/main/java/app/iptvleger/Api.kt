package app.iptvleger

import android.content.Context
import android.net.Uri
import android.util.JsonReader
import android.util.JsonToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStreamReader
import java.text.Normalizer
import java.util.TreeMap
import java.util.concurrent.TimeUnit

/**
 * Client Xtream Codes.
 * Les réponses sont enregistrées sur disque puis lues en flux (JsonReader) :
 * le catalogue complet n'est jamais chargé en mémoire.
 */
object Api {
    const val UA = "IPTVLeger/1.0"

    private const val HOUR = 3_600_000L
    private const val TTL_CATEGORIES = 12 * HOUR
    private const val TTL_LIST = 6 * HOUR
    private const val TTL_FULL = 24 * HOUR
    private const val TTL_SERIES = 2 * HOUR
    private const val SEARCH_LIMIT = 120

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    private val accents = Regex("\\p{Mn}+")

    fun normalizeServer(input: String): String {
        var s = input.trim()
        if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) s = "http://$s"
        val slash = s.indexOf('/', s.indexOf("://") + 3)
        if (slash > 0) s = s.substring(0, slash)
        return s
    }

    fun streamUrl(c: Creds, kind: String, id: String, ext: String): String {
        val u = Uri.encode(c.user)
        val p = Uri.encode(c.pass)
        return when (kind) {
            KIND_LIVE -> "${c.server}/live/$u/$p/$id.m3u8"
            KIND_SERIES -> "${c.server}/series/$u/$p/$id.${ext.ifBlank { "mp4" }}"
            else -> "${c.server}/movie/$u/$p/$id.${ext.ifBlank { "mp4" }}"
        }
    }

    private fun buildUrl(c: Creds, action: String, param: Pair<String, String>?): HttpUrl {
        val b = "${c.server}/player_api.php".toHttpUrl().newBuilder()
            .addQueryParameter("username", c.user)
            .addQueryParameter("password", c.pass)
        if (action.isNotEmpty()) b.addQueryParameter("action", action)
        if (param != null) b.addQueryParameter(param.first, param.second)
        return b.build()
    }

    /** Vérifie les identifiants. Renvoie null si tout va bien, sinon un message d'erreur. */
    suspend fun login(c: Creds): String? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(buildUrl(c, "", null)).header("User-Agent", UA).build()
            val text = http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("erreur serveur ${resp.code}")
                resp.body?.string() ?: ""
            }
            val info = JSONObject(text).optJSONObject("user_info")
            when {
                info == null -> "Réponse inattendue du serveur"
                info.optInt("auth", 0) != 1 -> "Identifiant ou mot de passe refusé"
                else -> null
            }
        } catch (e: Exception) {
            "Connexion impossible : ${e.message ?: e.javaClass.simpleName}"
        }
    }

    fun clearCache(ctx: Context) {
        ctx.cacheDir.listFiles()?.forEach { f ->
            if (f.isFile && f.name.startsWith("api_")) f.delete()
        }
    }

    private fun hasData(f: File) = f.exists() && f.length() > 2

    /** Renvoie le fichier de cache de la requête, en le téléchargeant s'il est absent ou périmé. */
    private fun fetch(ctx: Context, action: String, param: Pair<String, String>?, ttlMs: Long): File {
        val raw = if (param == null) action else "${action}_${param.first}_${param.second}"
        val f = File(ctx.cacheDir, "api_" + raw.replace(Regex("[^A-Za-z0-9_]"), "_") + ".json")
        if (hasData(f) && System.currentTimeMillis() - f.lastModified() < ttlMs) return f
        val c = Store.creds(ctx) ?: throw IOException("non connecté")
        try {
            download(buildUrl(c, action, param), f)
        } catch (e: Exception) {
            // Réseau en panne : on se rabat sur l'ancien cache s'il existe.
            if (!hasData(f)) throw e
        }
        return f
    }

    private fun download(url: HttpUrl, dest: File) {
        val tmp = File(dest.parentFile, dest.name + ".tmp")
        val req = Request.Builder().url(url).header("User-Agent", UA).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("erreur serveur ${resp.code}")
            val body = resp.body ?: throw IOException("réponse vide")
            tmp.outputStream().use { out -> body.byteStream().copyTo(out) }
        }
        if (!tmp.renameTo(dest)) {
            tmp.copyTo(dest, overwrite = true)
            tmp.delete()
        }
    }

    private fun JsonReader.str(): String = when (peek()) {
        JsonToken.NULL -> {
            nextNull()
            ""
        }
        JsonToken.STRING, JsonToken.NUMBER -> nextString()
        JsonToken.BOOLEAN -> nextBoolean().toString()
        else -> {
            skipValue()
            ""
        }
    }

    private fun reader(f: File): JsonReader {
        val r = JsonReader(InputStreamReader(FileInputStream(f), Charsets.UTF_8))
        r.isLenient = true
        return r
    }

    private fun norm(s: String): String =
        accents.replace(Normalizer.normalize(s, Normalizer.Form.NFD), "").lowercase()

    // --- Catégories ---

    suspend fun categories(ctx: Context, kind: String): List<Category> = withContext(Dispatchers.IO) {
        val action = when (kind) {
            KIND_SERIES -> "get_series_categories"
            KIND_LIVE -> "get_live_categories"
            else -> "get_vod_categories"
        }
        val out = ArrayList<Category>()
        reader(fetch(ctx, action, null, TTL_CATEGORIES)).use { r ->
            if (r.peek() != JsonToken.BEGIN_ARRAY) return@use
            r.beginArray()
            while (r.hasNext()) {
                if (r.peek() != JsonToken.BEGIN_OBJECT) {
                    r.skipValue()
                    continue
                }
                var id = ""
                var name = ""
                r.beginObject()
                while (r.hasNext()) {
                    when (r.nextName()) {
                        "category_id" -> id = r.str()
                        "category_name" -> name = r.str()
                        else -> r.skipValue()
                    }
                }
                r.endObject()
                if (id.isNotEmpty()) out.add(Category(id, name.ifBlank { id }))
            }
            r.endArray()
        }
        out
    }

    // --- Listes de films / séries / chaînes ---

    private fun listAction(kind: String) = when (kind) {
        KIND_SERIES -> "get_series"
        KIND_LIVE -> "get_live_streams"
        else -> "get_vod_streams"
    }

    private fun parseItems(f: File, kind: String, query: String?, limit: Int): List<Item> {
        val out = ArrayList<Item>()
        reader(f).use { r ->
            if (r.peek() != JsonToken.BEGIN_ARRAY) return@use
            r.beginArray()
            while (r.hasNext() && out.size < limit) {
                if (r.peek() != JsonToken.BEGIN_OBJECT) {
                    r.skipValue()
                    continue
                }
                var id = ""
                var name = ""
                var icon = ""
                var ext = ""
                r.beginObject()
                while (r.hasNext()) {
                    when (r.nextName()) {
                        "stream_id", "series_id" -> id = r.str()
                        "name" -> name = r.str()
                        "stream_icon", "cover" -> icon = r.str()
                        "container_extension" -> ext = r.str()
                        else -> r.skipValue()
                    }
                }
                r.endObject()
                if (id.isEmpty()) continue
                if (query == null || norm(name).contains(query)) {
                    out.add(Item(kind, id, name.trim(), icon.trim(), ext))
                }
            }
            // Pas de endArray : on peut s'arrêter avant la fin quand la limite est atteinte.
        }
        return out
    }

    suspend fun items(ctx: Context, kind: String, categoryId: String): List<Item> =
        withContext(Dispatchers.IO) {
            val f = fetch(ctx, listAction(kind), "category_id" to categoryId, TTL_LIST)
            parseItems(f, kind, null, Int.MAX_VALUE)
        }

    /** Recherche par titre dans le catalogue complet (téléchargé une fois par jour). */
    suspend fun search(ctx: Context, kind: String, query: String): List<Item> =
        withContext(Dispatchers.IO) {
            val f = fetch(ctx, listAction(kind), null, TTL_FULL)
            parseItems(f, kind, norm(query.trim()), SEARCH_LIMIT)
        }

    // --- Détail d'une série ---

    suspend fun seriesInfo(ctx: Context, seriesId: String): SeriesInfo = withContext(Dispatchers.IO) {
        val f = fetch(ctx, "get_series_info", "series_id" to seriesId, TTL_SERIES)
        val root = JSONObject(f.readText())
        val plot = root.optJSONObject("info")?.optString("plot", "") ?: ""
        val seasons = TreeMap<Int, MutableList<Episode>>()

        fun addSeason(season: Int, arr: JSONArray) {
            val list = seasons.getOrPut(season) { ArrayList() }
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id", "")
                if (id.isEmpty()) continue
                list.add(
                    Episode(
                        id = id,
                        title = o.optString("title", "").trim(),
                        ext = o.optString("container_extension", "mp4"),
                        num = o.optInt("episode_num", i + 1)
                    )
                )
            }
        }

        // Selon les serveurs, "episodes" est un objet {"1": [...]} ou un tableau [[...], [...]].
        when (val eps = root.opt("episodes")) {
            is JSONObject -> {
                val keys = eps.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val arr = eps.optJSONArray(k) ?: continue
                    addSeason(k.toIntOrNull() ?: 0, arr)
                }
            }
            is JSONArray -> {
                for (i in 0 until eps.length()) {
                    val arr = eps.optJSONArray(i) ?: continue
                    addSeason(i + 1, arr)
                }
            }
        }
        val clean = TreeMap<Int, List<Episode>>()
        for ((season, list) in seasons) {
            if (list.isNotEmpty()) clean[season] = list.sortedBy { it.num }
        }
        SeriesInfo(if (plot == "null") "" else plot, clean)
    }
}
