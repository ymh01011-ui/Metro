package code.name.monkey.retromusic.animatedcover

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.view.TextureView
import android.view.View
import android.widget.ImageView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
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
import java.io.File

/**
 * Two looks for the player cover:
 *  - no video  -> the original square cover (this controller stays invisible);
 *  - video     -> the cover goes full screen and the muted looping video plays on it.
 *
 * The video keeps playing whether the music plays or not.
 *
 * Videos are shared with the album page: same per-album cache entry, same on-disk ExoPlayer
 * cache, same background download and same saved first frame ("poster"). The poster is used as
 * the base image under the video and (through [onFrame]) to color the player background, so
 * the player is colored by the video's first frame instead of the album cover.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class FullCoverController(
    private val fragment: Fragment,
    private val container: View,
    private val image: ImageView,
    private val videoView: PlayerView,
    private val onFullModeChanged: (Boolean) -> Unit,
    /**
     * bitmap != null: the video's first frame for [albumId], use it to color the background.
     * bitmap == null: reset (new song) - the album cover colors the background until a frame exists.
     */
    private val onFrame: (Bitmap?, Long) -> Unit,
) {
    private val context = image.context.applicationContext
    private val resolver = AnimatedCoverResolver.get(context)
    private val scope get() = fragment.viewLifecycleOwner.lifecycleScope

    private var player: ExoPlayer? = null
    private var resolveJob: Job? = null
    private var posterJob: Job? = null
    private var currentSongId = Long.MIN_VALUE
    private var currentAlbumId = -1L
    private var videoUrl: String? = null
    private var resumed = false
    private var fullMode = false
    private var frameRatio = 1.33f

    private val listener = object : Player.Listener {
        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.width == 0 || videoSize.height == 0) return
            var w = videoSize.width * videoSize.pixelWidthHeightRatio
            var h = videoSize.height.toFloat()
            if (videoSize.unappliedRotationDegrees == 90 || videoSize.unappliedRotationDegrees == 270) {
                val t = w
                w = h
                h = t
            }
            frameRatio = h / w
        }

        override fun onRenderedFirstFrame() {
            setFullMode(true)
            captureFirstFrame()
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
        posterJob?.cancel()
        resolveJob = null
        videoUrl = null
        releasePlayer()
        onFrame(null, song.albumId) // reset: the cover colors the background until a frame exists

        if (!isEnabled() || song.albumId < 0) {
            setFullMode(false)
            return
        }

        val knownUrl = AlbumDetailsFragment.sharedEntry(context, song.albumId)?.url
        when {
            knownUrl != null -> {
                // Already known (album page or earlier play): shared cache. The current mode is
                // kept so two songs with videos never flash the square cover.
                videoUrl = knownUrl
                showPoster(song)
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

    fun onResume() {
        resumed = true
        if (videoUrl != null && player == null) startVideo() else player?.playWhenReady = true
    }

    fun onPause() {
        resumed = false
        releasePlayer() // frees the decoder; the cover layer stays as it is
    }

    fun release() {
        resolveJob?.cancel()
        posterJob?.cancel()
        resolveJob = null
        posterJob = null
        releasePlayer()
        container.animate().cancel()
    }

    // ------------------------------------------------------------------ lookup

    private fun lookup(song: Song) {
        resolveJob = scope.launch {
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
            showPoster(song)
            if (resumed) startVideo()
        }
    }

    // ------------------------------------------------------------------ images

    /** Base layer under the video: the saved first frame if we have it, else the album cover. */
    private fun showPoster(song: Song) {
        posterJob?.cancel()
        posterJob = scope.launch {
            val poster = withContext(Dispatchers.IO) {
                AlbumDetailsFragment.sharedPosterFile(context, song.albumId)?.let { decode(it) }
            }
            if (currentSongId != song.id) return@launch
            if (poster != null) {
                Glide.with(fragment).clear(image)
                image.setImageBitmap(poster)
                onFrame(poster, song.albumId)
            } else {
                loadCover(song)
            }
        }
    }

    private fun loadCover(song: Song) {
        Glide.with(fragment)
            .load(RetroGlideExtension.getSongModel(song))
            .simpleSongCoverOptions(song)
            .transition(DrawableTransitionOptions.withCrossFade(CROSSFADE_MS))
            .into(image)
    }

    private fun decode(file: File): Bitmap? {
        val maxSide = minOf(context.resources.displayMetrics.widthPixels * 3 / 2, 2048)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** First rendered frame -> background color now, and saved (shared with the album page). */
    private fun captureFirstFrame() {
        val albumId = currentAlbumId
        if (albumId < 0) return
        val entry = AlbumDetailsFragment.sharedEntry(context, albumId)
        if (entry?.posterColor != null && AlbumDetailsFragment.sharedPosterFile(context, albumId) != null) return
        val textureView = videoView.videoSurfaceView as? TextureView ?: return
        val ratio = frameRatio
        textureView.post {
            if (textureView.width == 0 || textureView.height == 0 || currentAlbumId != albumId) return@post
            val frame = try {
                textureView.bitmap
            } catch (e: Exception) {
                null
            } ?: return@post
            scope.launch {
                val usable = withContext(Dispatchers.IO) {
                    if (isMostlyBlack(frame)) {
                        false
                    } else {
                        AlbumDetailsFragment.sharedStorePoster(context, albumId, frame, ratio)
                        true
                    }
                }
                if (usable && currentAlbumId == albumId) onFrame(frame, albumId)
            }
        }
    }

    /** Guards against saving a black frame (the surface may not have drawn yet). */
    private fun isMostlyBlack(bitmap: Bitmap): Boolean {
        val stepX = maxOf(bitmap.width / 12, 1)
        val stepY = maxOf(bitmap.height / 12, 1)
        var sum = 0L
        var count = 0
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val c = bitmap.getPixel(x, y)
                sum += (Color.red(c) + Color.green(c) + Color.blue(c)) / 3
                count++
                x += stepX
            }
            y += stepY
        }
        return count == 0 || sum / count < 8
    }

    // ------------------------------------------------------------------ modes

    private fun setFullMode(full: Boolean) {
        if (fullMode == full) return
        fullMode = full
        container.animate().cancel()
        container.animate().alpha(if (full) 1f else 0f).setDuration(FADE_MS).start()
        onFullModeChanged(full)
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
        exo.playWhenReady = true // never tied to the music being paused
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
