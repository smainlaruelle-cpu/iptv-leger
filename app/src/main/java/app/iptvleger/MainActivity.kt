package app.iptvleger

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private companion object {
        const val MODE_SEARCH = "search"
        const val CAT_RECENT = "__recent"
        const val CAT_FAV = "__fav"
        const val COLUMNS = 6
    }

    private lateinit var cats: RecyclerView
    private lateinit var grid: RecyclerView
    private lateinit var search: EditText
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var tabs: Map<String, TextView>

    private val catAdapter = TextAdapter { index -> selectCategory(index) }
    private val cardAdapter = CardAdapter({ item -> openItem(item) }, { item -> toggleFav(item) })

    private var mode = KIND_MOVIE
    private var categories: List<Category> = emptyList()
    private var selectedCat: Category? = null
    private var job: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Store.creds(this) == null) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }
        setContentView(R.layout.activity_main)
        cats = findViewById(R.id.cats)
        grid = findViewById(R.id.grid)
        search = findViewById(R.id.search)
        progress = findViewById(R.id.progress)
        status = findViewById(R.id.status)

        cats.layoutManager = LinearLayoutManager(this)
        cats.adapter = catAdapter
        cats.itemAnimator = null
        grid.layoutManager = GridLayoutManager(this, COLUMNS)
        grid.adapter = cardAdapter
        grid.itemAnimator = null
        grid.setHasFixedSize(true)

        tabs = mapOf(
            KIND_MOVIE to findViewById<TextView>(R.id.tabMovies),
            KIND_SERIES to findViewById<TextView>(R.id.tabSeries),
            KIND_LIVE to findViewById<TextView>(R.id.tabLive),
            MODE_SEARCH to findViewById<TextView>(R.id.tabSearch)
        )
        for ((m, tab) in tabs) {
            tab.setOnClickListener { setMode(m) }
        }
        findViewById<TextView>(R.id.tabAccount).setOnClickListener {
            startActivity(Intent(this, LoginActivity::class.java))
        }

        search.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                doSearch()
                true
            } else {
                false
            }
        }

        setMode(KIND_MOVIE)
    }

    override fun onResume() {
        super.onResume()
        // Au retour du lecteur, « Reprendre » et « Favoris » peuvent avoir changé.
        val cat = selectedCat ?: return
        if (mode == MODE_SEARCH) return
        if (cat.id == CAT_RECENT || cat.id == CAT_FAV) {
            val list = localItems(cat.id)
            if (list != cardAdapter.current()) {
                cardAdapter.submit(list)
                if (list.isEmpty()) showStatus("Rien ici pour l'instant.") else hideStatus()
            }
        }
    }

    // --- Affichage d'état ---

    private fun showProgress(message: String? = null) {
        progress.visibility = View.VISIBLE
        if (message == null) {
            status.visibility = View.GONE
        } else {
            status.text = message
            status.visibility = View.VISIBLE
        }
    }

    private fun showStatus(message: String) {
        progress.visibility = View.GONE
        status.text = message
        status.visibility = View.VISIBLE
    }

    private fun hideStatus() {
        progress.visibility = View.GONE
        status.visibility = View.GONE
    }

    /** Lance un chargement en annulant le précédent, et affiche l'erreur éventuelle. */
    private fun load(block: suspend () -> Unit) {
        job?.cancel()
        job = lifecycleScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showStatus("Erreur : ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    // --- Navigation ---

    private fun setMode(m: String) {
        mode = m
        for ((key, tab) in tabs) tab.isActivated = key == m
        selectedCat = null
        cardAdapter.submit(emptyList())
        if (m == MODE_SEARCH) {
            job?.cancel()
            cats.visibility = View.GONE
            search.visibility = View.VISIBLE
            showStatus("Tapez un titre puis validez.")
            search.requestFocus()
        } else {
            search.visibility = View.GONE
            cats.visibility = View.VISIBLE
            loadCategories(m)
        }
    }

    private fun loadCategories(kind: String) {
        catAdapter.submit(emptyList())
        load {
            showProgress()
            val fixed = listOf(Category(CAT_RECENT, "▶ Reprendre"), Category(CAT_FAV, "★ Favoris"))
            val all = fixed + Api.categories(this, kind)
            categories = all
            val start = when {
                localItems(CAT_RECENT).isNotEmpty() -> 0
                all.size > 2 -> 2
                else -> 1
            }
            catAdapter.submit(all.map { it.name }, start)
            showCategory(all[start])
        }
    }

    private fun selectCategory(index: Int) {
        val cat = categories.getOrNull(index) ?: return
        catAdapter.select(index)
        load { showCategory(cat) }
    }

    private fun localItems(catId: String): List<Item> =
        if (catId == CAT_FAV) {
            Store.favs(this, mode)
        } else {
            // Un film terminé n'a plus de position enregistrée : il sort de « Reprendre ».
            Store.recents(this, mode).filter { it.kind != KIND_MOVIE || Store.getPos(this, it.key) > 0 }
        }

    private suspend fun showCategory(cat: Category) {
        selectedCat = cat
        showProgress()
        val list = if (cat.id == CAT_RECENT || cat.id == CAT_FAV) {
            localItems(cat.id)
        } else {
            Api.items(this, mode, cat.id)
        }
        cardAdapter.submit(list)
        grid.scrollToPosition(0)
        if (list.isEmpty()) showStatus("Rien ici pour l'instant.") else hideStatus()
    }

    private fun doSearch() {
        val query = search.text.toString().trim()
        if (query.length < 2) return
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(search.windowToken, 0)
        load {
            cardAdapter.submit(emptyList())
            showProgress("Recherche… La première fois, le catalogue complet est téléchargé.")
            val results = Api.search(this, KIND_MOVIE, query) +
                Api.search(this, KIND_SERIES, query) +
                Api.search(this, KIND_LIVE, query)
            cardAdapter.submit(results)
            if (results.isEmpty()) {
                showStatus("Aucun résultat pour « $query ».")
            } else {
                hideStatus()
                grid.post { grid.requestFocus() }
            }
        }
    }

    // --- Actions ---

    private fun openItem(item: Item) {
        val creds = Store.creds(this) ?: return
        when (item.kind) {
            KIND_SERIES -> {
                val i = Intent(this, SeriesActivity::class.java)
                i.putExtra("item", item.toJson().toString())
                startActivity(i)
            }
            else -> {
                Store.addRecent(this, item)
                val url = Api.streamUrl(creds, item.kind, item.id, item.ext)
                startActivity(
                    PlayerActivity.newIntent(
                        this,
                        urls = arrayListOf(url),
                        keys = arrayListOf(item.key),
                        titles = arrayListOf(item.name),
                        index = 0,
                        live = item.kind == KIND_LIVE,
                        seriesId = null
                    )
                )
            }
        }
    }

    private fun toggleFav(item: Item) {
        val added = Store.toggleFav(this, item)
        Toast.makeText(
            this,
            if (added) "Ajouté aux favoris" else "Retiré des favoris",
            Toast.LENGTH_SHORT
        ).show()
        if (selectedCat?.id == CAT_FAV && mode != MODE_SEARCH) {
            cardAdapter.submit(localItems(CAT_FAV))
        }
    }
}
