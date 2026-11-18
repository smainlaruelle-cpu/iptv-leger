package app.iptvleger

import org.json.JSONObject

const val KIND_MOVIE = "movie"
const val KIND_SERIES = "series"
const val KIND_LIVE = "live"

data class Creds(val server: String, val user: String, val pass: String)

data class Category(val id: String, val name: String)

data class Episode(val id: String, val title: String, val ext: String, val num: Int)

class SeriesInfo(val plot: String, val seasons: Map<Int, List<Episode>>)

data class Item(
    val kind: String,
    val id: String,
    val name: String,
    val icon: String,
    val ext: String
) {
    val key: String get() = "$kind:$id"

    fun toJson(): JSONObject = JSONObject()
        .put("kind", kind)
        .put("id", id)
        .put("name", name)
        .put("icon", icon)
        .put("ext", ext)

    companion object {
        fun fromJson(o: JSONObject) = Item(
            o.optString("kind"),
            o.optString("id"),
            o.optString("name"),
            o.optString("icon"),
            o.optString("ext")
        )
    }
}
