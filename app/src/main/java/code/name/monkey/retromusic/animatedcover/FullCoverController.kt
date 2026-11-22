package code.name.monkey.retromusic.animatedcover

import android.view.View
import android.widget.ImageView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.ui.PlayerView
import androidx.preference.PreferenceManager
import code.name.monkey.retromusic.fragments.albums.AlbumDetailsFragment
import code.name.monkey.retromusic.glide.RetroGlideExtension
import code.name.monkey.retromusic.glide.RetroGlideExtension.simpleSongCoverOptions
import code.name.monkey.retromusic.model.Song
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Two looks for the player cover:
 *  - no video  -> the original square cover (this controller stays invisible);
 *  - video     -> the cover goes full screen and the muted looping video plays on it.
 *
 * Videos are shared with the album page: same per-album cache entry, same on-disk ExoPlayer
 * cache and same background download, so an album loaded in one place never loads again in
 * the other, and nothing is re-downloaded after the app is closed.
 *
 * The container fades in only once the video has actually rendered its first frame, so the
 * original cover never flashes away for a video that fails to start.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class FullCoverController(
    private val fragment: Fragment,
    private val container: View,
    private val image: ImageView,
    private val videoView: PlayerView,
    private val onFullModeChanged: (Boolean) -> Unit,
) {
    private val context = image.context.applicationContext
    private val resolver = AnimatedCoverResolver.get(context)

    private var player: ExoPlayer? = null
    private var resolveJob: Job? = null
    private var currentSongId = Long.MIN_VALUE
    private var currentAlbumId = -1L
    private var videoUrl: String? = null
    private var isPlaying = false
    private var resumed = false
    private var fullMode = false

    private val listener = object : Player.Listener {
        override fun onRenderedFirstFrame() {
            setFullMode(true)
        }

        override fun onPlayerError(error: PlaybackException) {
            // A dead link (404/403) is forgotten so it gets fetched again next time.
            // Network errors keep the entry: the video may still be playable later/offline.
            if (error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS && currentAlbumId >= 0) {
                AlbumDetailsFragment.sharedForget(context, currentAlbumId)
            }
            videoUrl = null
            releasePlayer()
            setFullMode(false)
        }
    }

    init {
        container.alpha = 0f
    }

    /** Call when the current song changes (and once when the service connects). */
    fun onSongChanged(song: Song) {
        if (song.id == currentSongId) return
        currentSongId = song.id
        currentAlbumId = song.albumId

        resolveJob?.cancel()
        resolveJob = null
        videoUrl = null
        releasePlayer()

        if (!isEnabled() || song.albumId < 0) {
            setFullMode(false)
            return
        }

        val knownUrl = AlbumDetailsFragment.sharedEntry(context, song.albumId)?.url
        when {
            knownUrl != null -> {
                // Already known (from the album page or an earlier play): use the shared cache.
                // The current mode is kept so two songs with videos never flash the square cover.
                videoUrl = knownUrl
                loadStatic(song)
                AlbumDetailsFragment.sharedEnsureDownloaded(context, song.albumId)
                if (resumed) startVideo()
            }
            AlbumDetailsFragment.sharedKnownNoVideo(context, song.albumId) -> setFullMode(false)
            else -> {
                setFullMode(false)
                lookup(song)
            }
        }
    }

    /** The user turned the setting on/off. */
    fun refresh(song: Song) {
        currentSongId = Long.MIN_VALUE
        onSongChanged(song)
    }

    fun setPlaying(playing: Boolean) {
        isPlaying = playing
        player?.playWhenReady = playing && resumed
    }

    fun onResume(playing: Boolean) {
        resumed = true
        isPlaying = playing
        if (videoUrl != null && player == null) {
            startVideo()
        } else {
            player?.playWhenReady = playing
        }
    }

    fun onPause() {
        resumed = false
        releasePlayer() // frees the decoder; the cover layer stays as it is
    }

    fun release() {
        resolveJob?.cancel()
        resolveJob = null
        releasePlayer()
        container.animate().cancel()
    }

    // ------------------------------------------------------------------ lookup

    private fun lookup(song: Song) {
        resolveJob = fragment.viewLifecycleOwner.lifecycleScope.launch {
            val cover = resolver.resolve(song.title, song.artistName, song.albumName) ?: return@launch
            if (currentSongId != song.id) return@launch
            val master = cover.tallUrl ?: cover.squareUrl
            val width = context.resources.displayMetrics.widthPixels
            val variant = withContext(Dispatchers.IO) {
                AlbumDetailsFragment.sharedResolveVariant(master, width)
            }
            if (currentSongId != song.id) return@launch
            // Stores it in the album cache (the album page will see it) and starts the download.
            videoUrl = AlbumDetailsFragment.sharedStoreFound(context, song.albumId, variant)
            loadStatic(song)
            if (resumed) startVideo()
        }
    }

    // ------------------------------------------------------------------ modes

    private fun setFullMode(full: Boolean) {
        if (fullMode == full) return
        fullMode = full
        container.animate().cancel()
        container.animate().alpha(if (full) 1f else 0f).setDuration(FADE_MS).start()
        onFullModeChanged(full)
    }

    private fun loadStatic(song: Song) {
        Glide.with(fragment)
            .load(RetroGlideExtension.getSongModel(song))
            .simpleSongCoverOptions(song)
            .transition(DrawableTransitionOptions.withCrossFade(CROSSFADE_MS))
            .into(image)
    }

    // ------------------------------------------------------------------ player

    private fun startVideo() {
        val url = videoUrl ?: return
        val exo = player ?: createPlayer().also {
            player = it
            videoView.player = it
        }
        val source = HlsMediaSource.Factory(AlbumDetailsFragment.sharedCacheFactory(context))
            .createMediaSource(MediaItem.fromUri(url))
        exo.setMediaSource(source)
        exo.prepare()
        exo.playWhenReady = isPlaying && resumed
    }

    private fun createPlayer(): ExoPlayer {
        // Small buffers: the clip is short, muted and looped.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(2_000, 10_000, 500, 1_000)
            .build()
        return ExoPlayer.Builder(context).setLoadControl(loadControl).build().apply {
            volume = 0f
            repeatMode = Player.REPEAT_MODE_ONE
            trackSelectionParameters = trackSelectionParameters
                .buildUpon()
                .setMaxVideoSize(1080, 1920)
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                .build()
            addListener(listener)
        }
    }

    private fun releasePlayer() {
        videoView.player = null
        player?.let {
            it.removeListener(listener)
            it.release()
        }
        player = null
    }

    private fun isEnabled(): Boolean =
        PreferenceManager.getDefaultSharedPreferences(context).getBoolean(PREF_KEY, true)

    companion object {
        /** SharedPreferences key (boolean, default true) for the settings switch. */
        const val PREF_KEY = "animated_cover_enabled"
        private const val FADE_MS = 350L
        private const val CROSSFADE_MS = 350
    }
}
