package code.name.monkey.retromusic.animatedcover

import android.widget.ImageView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.preference.PreferenceManager
import code.name.monkey.retromusic.glide.RetroGlideExtension
import code.name.monkey.retromusic.glide.RetroGlideExtension.simpleSongCoverOptions
import code.name.monkey.retromusic.model.Song
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Drives the full-screen cover of the player: a static cover image, and on top of it (when
 * Apple Music has one for the song) a muted looping video that fades in after its first frame.
 *
 * The fragment forwards: onSongChanged, setPlaying, onResume, onPause, release.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class FullCoverController(
    private val fragment: Fragment,
    private val image: ImageView,
    private val videoView: PlayerView,
) {
    private val context = image.context.applicationContext
    private val resolver = AnimatedCoverResolver.get(context)

    private var player: ExoPlayer? = null
    private var resolveJob: Job? = null
    private var currentSongId = Long.MIN_VALUE
    private var videoUrl: String? = null
    private var isPlaying = false
    private var resumed = false

    private val listener = object : Player.Listener {
        override fun onRenderedFirstFrame() {
            videoView.animate().alpha(1f).setDuration(FADE_MS).start()
        }

        override fun onPlayerError(error: PlaybackException) {
            // Give up on the video; the static cover stays.
            videoUrl = null
            releasePlayer()
        }
    }

    init {
        videoView.alpha = 0f
    }

    /** Call when the current song changes (and once when the service connects). */
    fun onSongChanged(song: Song) {
        if (song.id == currentSongId) return
        currentSongId = song.id

        resolveJob?.cancel()
        videoUrl = null
        releasePlayer()
        loadStatic(song)

        if (!isEnabled()) return
        resolveJob = fragment.viewLifecycleOwner.lifecycleScope.launch {
            val cover = resolver.resolve(song.title, song.artistName, song.albumName)
            if (cover != null && currentSongId == song.id) {
                // The container is roughly 3:4, so prefer the tall video.
                videoUrl = cover.tallUrl ?: cover.squareUrl
                if (resumed) startVideo()
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
        releasePlayer() // frees the decoder; the static cover stays visible
    }

    fun release() {
        resolveJob?.cancel()
        resolveJob = null
        releasePlayer()
    }

    private fun loadStatic(song: Song) {
        Glide.with(fragment)
            .load(RetroGlideExtension.getSongModel(song))
            .simpleSongCoverOptions(song)
            .transition(DrawableTransitionOptions.withCrossFade(CROSSFADE_MS))
            .into(image)
    }

    private fun startVideo() {
        val url = videoUrl ?: return
        val exo = player ?: createPlayer().also {
            player = it
            videoView.player = it
        }
        exo.setMediaItem(MediaItem.fromUri(url))
        exo.prepare()
        exo.playWhenReady = isPlaying && resumed
    }

    private fun createPlayer(): ExoPlayer =
        ExoPlayer.Builder(context).build().apply {
            volume = 0f
            repeatMode = Player.REPEAT_MODE_ONE
            trackSelectionParameters = trackSelectionParameters
                .buildUpon()
                .setMaxVideoSize(1080, 1440)
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                .build()
            addListener(listener)
        }

    private fun releasePlayer() {
        videoView.animate().cancel()
        videoView.alpha = 0f
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
        private const val FADE_MS = 400L
        private const val CROSSFADE_MS = 350
    }
}
