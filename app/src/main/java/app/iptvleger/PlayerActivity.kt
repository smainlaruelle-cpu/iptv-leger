package app.iptvleger

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView

@androidx.annotation.OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    companion object {
        private const val SAVE_EVERY_MS = 15_000L
        private const val MAX_LIVE_RETRIES = 5

        fun newIntent(
            ctx: Context,
            urls: ArrayList<String>,
            keys: ArrayList<String>,
            titles: ArrayList<String>,
            index: Int,
            live: Boolean,
            seriesId: String?
        ): Intent {
            val i = Intent(ctx, PlayerActivity::class.java)
            i.putStringArrayListExtra("urls", urls)
            i.putStringArrayListExtra("keys", keys)
            i.putStringArrayListExtra("titles", titles)
            i.putExtra("index", index)
            i.putExtra("live", live)
            i.putExtra("seriesId", seriesId)
            return i
        }
    }

    private lateinit var playerView: PlayerView
    private lateinit var overlay: TextView
    private val handler = Handler(Looper.getMainLooper())

    private var player: ExoPlayer? = null
    private var urls: List<String> = emptyList()
    private var keys: List<String> = emptyList()
    private var titles: List<String> = emptyList()
    private var index = 0
    private var live = false
    private var seriesId: String? = null
    private var liveRetries = 0

    private val saver = object : Runnable {
        override fun run() {
            savePosition()
            handler.postDelayed(this, SAVE_EVERY_MS)
        }
    }
    private val hideOverlay = Runnable {
        if (!playerView.isControllerFullyVisible) overlay.visibility = View.GONE
    }
    private val retryLive = Runnable { player?.prepare() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        urls = intent.getStringArrayListExtra("urls").orEmpty()
        keys = intent.getStringArrayListExtra("keys").orEmpty()
        titles = intent.getStringArrayListExtra("titles").orEmpty()
        live = intent.getBooleanExtra("live", false)
        seriesId = intent.getStringExtra("seriesId")
        index = savedInstanceState?.getInt("index") ?: intent.getIntExtra("index", 0)
        if (urls.isEmpty() || keys.size != urls.size || titles.size != urls.size) {
            finish()
            return
        }
        index = index.coerceIn(0, urls.size - 1)

        playerView = findViewById(R.id.playerView)
        overlay = findViewById(R.id.overlay)
        playerView.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { visibility ->
                if (visibility == View.VISIBLE) {
                    handler.removeCallbacks(hideOverlay)
                    overlay.text = titles[index]
                    overlay.visibility = View.VISIBLE
                } else {
                    overlay.visibility = View.GONE
                }
            }
        )
        playerView.requestFocus()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("index", index)
    }

    override fun onStart() {
        super.onStart()
        if (urls.isNotEmpty()) startPlayer()
    }

    override fun onStop() {
        super.onStop()
        // Le lecteur est libéré dès qu'on quitte l'écran : l'abonnement
        // n'autorise souvent qu'une seule connexion à la fois.
        handler.removeCallbacksAndMessages(null)
        savePosition()
        if (::playerView.isInitialized) playerView.player = null
        player?.release()
        player = null
    }

    private fun startPlayer() {
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(Api.UA)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)
        val renderers = DefaultRenderersFactory(this).setEnableDecoderFallback(true)
        val p = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(this)
                    .setDataSourceFactory(DefaultDataSource.Factory(this, httpFactory))
            )
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(30_000)
            .build()

        p.trackSelectionParameters = p.trackSelectionParameters
            .buildUpon()
            .setPreferredAudioLanguage("fr")
            .build()

        val items = urls.indices.map { i ->
            MediaItem.Builder().setUri(urls[i]).setMediaId(keys[i]).build()
        }
        val startPosition = if (live) 0L else Store.getPos(this, keys[index])
        p.setMediaItems(items, index, startPosition)

        p.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val previous = index
                index = p.currentMediaItemIndex.coerceIn(0, urls.size - 1)
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && previous != index) {
                    // L'épisode précédent est allé au bout : plus de reprise à proposer.
                    Store.setPos(this@PlayerActivity, keys[previous], 0L, 0L)
                }
                onItemChanged()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) liveRetries = 0
            }

            override fun onPlayerError(error: PlaybackException) {
                if (live && liveRetries < MAX_LIVE_RETRIES) {
                    liveRetries++
                    handler.removeCallbacks(retryLive)
                    handler.postDelayed(retryLive, 3_000)
                } else {
                    Toast.makeText(
                        this@PlayerActivity,
                        "Lecture impossible (${error.errorCodeName})",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        })

        playerView.player = p
        player = p
        p.playWhenReady = true
        p.prepare()
        onItemChanged()
        if (!live) handler.postDelayed(saver, SAVE_EVERY_MS)
    }

    private fun onItemChanged() {
        val sid = seriesId
        if (sid != null) Store.setLastEpisode(this, sid, keys[index].substringAfter(':'))
        showOverlay(titles[index], 4_000)
    }

    private fun showOverlay(text: String, durationMs: Long) {
        overlay.text = text
        overlay.visibility = View.VISIBLE
        handler.removeCallbacks(hideOverlay)
        handler.postDelayed(hideOverlay, durationMs)
    }

    private fun savePosition() {
        val p = player ?: return
        if (live) return
        val key = p.currentMediaItem?.mediaId ?: return
        val duration = if (p.duration > 0) p.duration else 0L
        Store.setPos(this, key, p.currentPosition, duration)
    }

    private fun format(ms: Long): String {
        if (ms <= 0) return "0:00"
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%d:%02d", m, s)
    }

    /**
     * Tant que la barre de contrôle est masquée, gauche/droite font un saut
     * direct (-10 s / +30 s) sans passer par les menus.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val p = player
        val code = event.keyCode
        val isSeekKey = code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
        if (p != null && !live && isSeekKey && !playerView.isControllerFullyVisible) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (code == KeyEvent.KEYCODE_DPAD_LEFT) p.seekBack() else p.seekForward()
                showOverlay("${format(p.currentPosition)} / ${format(p.duration)}", 1_500)
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}
