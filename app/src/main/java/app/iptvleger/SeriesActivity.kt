package app.iptvleger

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject

class SeriesActivity : AppCompatActivity() {

    private lateinit var item: Item
    private lateinit var status: TextView
    private lateinit var fav: TextView
    private lateinit var episodesView: RecyclerView

    private val seasonAdapter = TextAdapter { index -> showSeason(index, moveFocus = true) }
    private val episodeAdapter = TextAdapter { index -> play(index) }

    private var seasonNumbers: List<Int> = emptyList()
    private var seasons: Map<Int, List<Episode>> = emptyMap()
    private var currentSeason = -1
    private var currentEpisodes: List<Episode> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_series)
        item = Item.fromJson(JSONObject(intent.getStringExtra("item") ?: "{}"))

        status = findViewById(R.id.status)
        fav = findViewById(R.id.fav)
        episodesView = findViewById(R.id.episodes)
        findViewById<TextView>(R.id.title).text = item.name

        val seasonsView: RecyclerView = findViewById(R.id.seasons)
        seasonsView.layoutManager = LinearLayoutManager(this)
        seasonsView.adapter = seasonAdapter
        seasonsView.itemAnimator = null
        episodesView.layoutManager = LinearLayoutManager(this)
        episodesView.adapter = episodeAdapter
        episodesView.itemAnimator = null

        updateFavLabel()
        fav.setOnClickListener {
            Store.toggleFav(this, item)
            updateFavLabel()
        }

        status.text = "Chargement…"
        lifecycleScope.launch {
            try {
                val info = Api.seriesInfo(this@SeriesActivity, item.id)
                val plot: TextView = findViewById(R.id.plot)
                plot.text = info.plot
                seasons = info.seasons
                seasonNumbers = info.seasons.keys.toList()
                if (seasonNumbers.isEmpty()) {
                    status.text = "Aucun épisode disponible."
                    return@launch
                }
                status.visibility = View.GONE
                // On rouvre la saison du dernier épisode regardé.
                val last = Store.lastEpisode(this@SeriesActivity, item.id)
                var start = 0
                if (last != null) {
                    val found = seasonNumbers.indexOfFirst { n ->
                        seasons[n].orEmpty().any { it.id == last }
                    }
                    if (found >= 0) start = found
                }
                seasonAdapter.submit(seasonNumbers.map { "Saison $it" }, start)
                showSeason(start, moveFocus = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status.text = "Erreur : ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Au retour du lecteur, les marqueurs « en cours » ont pu changer.
        if (currentSeason >= 0) {
            val labels = labels()
            if (labels != episodeAdapter.current()) {
                episodeAdapter.submit(labels, lastIndex())
                focusEpisode()
            }
        }
    }

    private fun updateFavLabel() {
        fav.text = if (Store.isFav(this, item)) "★ Retirer des favoris" else "☆ Ajouter aux favoris"
    }

    private fun labels(): List<String> = currentEpisodes.map { ep ->
        val title = ep.title.ifBlank { "Épisode ${ep.num}" }
        val mark = if (Store.getPos(this, "$KIND_SERIES:${ep.id}") > 0) "   · en cours" else ""
        "${ep.num}. $title$mark"
    }

    private fun lastIndex(): Int {
        val last = Store.lastEpisode(this, item.id) ?: return -1
        return currentEpisodes.indexOfFirst { it.id == last }
    }

    private fun focusEpisode() {
        val target = lastIndex().coerceAtLeast(0)
        episodesView.scrollToPosition(target)
        episodesView.post {
            val holder = episodesView.findViewHolderForAdapterPosition(target)
            if (holder != null) holder.itemView.requestFocus() else episodesView.requestFocus()
        }
    }

    private fun showSeason(index: Int, moveFocus: Boolean) {
        val number = seasonNumbers.getOrNull(index) ?: return
        currentSeason = index
        currentEpisodes = seasons[number].orEmpty()
        seasonAdapter.select(index)
        episodeAdapter.submit(labels(), lastIndex())
        if (moveFocus) focusEpisode()
    }

    private fun play(index: Int) {
        val creds = Store.creds(this) ?: return
        if (index !in currentEpisodes.indices) return
        Store.addRecent(this, item)
        val number = seasonNumbers.getOrNull(currentSeason) ?: 0
        val urls = ArrayList<String>()
        val keys = ArrayList<String>()
        val titles = ArrayList<String>()
        // Toute la saison est envoyée au lecteur : l'épisode suivant s'enchaîne tout seul.
        for (ep in currentEpisodes) {
            urls.add(Api.streamUrl(creds, KIND_SERIES, ep.id, ep.ext))
            keys.add("$KIND_SERIES:${ep.id}")
            titles.add("${item.name} · S$number E${ep.num}")
        }
        startActivity(
            PlayerActivity.newIntent(
                this,
                urls = urls,
                keys = keys,
                titles = titles,
                index = index,
                live = false,
                seriesId = item.id
            )
        )
    }
}
