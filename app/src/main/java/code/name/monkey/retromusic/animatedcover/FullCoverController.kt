package code.name.monkey.retromusic.animatedcover

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Rect
import android.view.View
import android.view.ViewOutlineProvider
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
import code.name.monkey.retromusic.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Two looks for the player cover:
 *  - no video  -> the original square cover (this controller stays invisible);
 *  - video     -> the cover goes full screen and the muted looping video plays on it.
 *
 * While a video is active NO cover image is ever drawn by this controller: there is no static
 * image under the video any more. Between two songs the same player is reused, so the last video
 * frame simply stays on screen until the next video renders its first frame.
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
    /** Kept for the constructor signature only: it is permanently hidden (never shows a cover). */
    private val image: ImageView,
    private val videoView: PlayerView,
    private val onFullModeChanged: (Boolean) -> Unit,
    /** Optional: first video frame (null = no frame, use the cover) and its album id. */
    @Suppress("unused")
    private val onFrame: (Bitmap?, Long) -> Unit = { _, _ -> },
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

    // While true (queue open) the video stays hidden and paused, whatever happens to the songs;
    // when it ends the container fades back in only if the CURRENT song really has a video.
    private var suppressed = false

    private fun shouldPlay() = resumed && !suppressed

    // Set when full mode was entered before the first frame (video already known/cached); if the
    // frame never comes the container falls back to the normal square cover.
    private var awaitingFrame = false
    private val frameTimeout = Runnable {
        if (awaitingFrame) {
            awaitingFrame = false
            dropVideo()
        }
    }

    private fun clearFrameWait() {
        awaitingFrame = false
        container.removeCallbacks(frameTimeout)
    }

    /** The video is already known: go full-screen right away instead of waiting for decoder start-up. */
    private fun enterFullEarly() {
        if (fullMode) return
        awaitingFrame = true
        container.removeCallbacks(frameTimeout)
        container.postDelayed(frameTimeout, FRAME_TIMEOUT_MS)
        setFullMode(true)
    }

    private val listener = object : Player.Listener {
        override fun onRenderedFirstFrame() {
            clearFrameWait()
            setFullMode(true)
        }

        override fun onPlayerError(error: PlaybackException) {
            // A dead link (404/403) is forgotten so it gets fetched again next time.
            // Network errors keep the entry: the video may still be playable later/offline.
            if (error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS && currentAlbumId >= 0) {
                AlbumDetailsFragment.sharedForget(context, currentAlbumId)
            }
            dropVideo()
        }
    }

    // While the sheet opens, the WHOLE cover (video + fade) is scaled down uniformly to the mini
    // player's artwork and grows with the drag until it is 1:1 (Apple Music style). A centered
    // window (the outline) keeps it a rounded square at rest and opens it to the full cover.
    private val reveal = Rect()
    private val miniRect = Rect() // mini artwork, relative to the container's layout origin
    private var hasMiniRect = false
    private var revealRadius = 0f
    private val loc = IntArray(2)
    private val loc2 = IntArray(2)

    val isFull: Boolean get() = fullMode

    init {
        // The cover image under the video is gone for good: nothing may show through the video
        image.setImageDrawable(null)
        image.visibility = View.GONE
        // No black shutter between two videos: the previous frame stays until the next one renders
        videoView.setShutterBackgroundColor(Color.TRANSPARENT)
        videoView.setKeepContentOnPlayerReset(true)
        container.alpha = 0f
        container.pivotX = 0f
        container.pivotY = 0f
        container.clipToOutline = true
        container.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) {
                if (reveal.isEmpty) o.setRect(0, 0, v.width, v.height)
                else o.setRoundRect(reveal, revealRadius)
            }
        }
    }

    /**
     * [p]: 0 = mini player, 1 = fully open. [miniCard]: the mini player's artwork card, used to
     * start the video exactly where that card is (may be null).
     */
    fun applySheetProgress(p: Float, miniCard: View?) {
        val w = container.width
        val h = container.height
        val density = container.resources.displayMetrics.density

        // Remember where the mini card is, relative to the container's LAYOUT origin (its parent's
        // screen position + left/top, so our own scale/translation never feed back into it).
        // Only while the card is shown: afterwards the remembered rect is used.
        val parent = container.parent as? View
        if (miniCard != null && miniCard.width > 0 && miniCard.isShown && parent != null && w > 0) {
            parent.getLocationOnScreen(loc)
            miniCard.getLocationOnScreen(loc2)
            val l = loc2[0] - loc[0] - container.left
            val t = loc2[1] - loc[1] - container.top
            miniRect.set(l, t, l + miniCard.width, t + miniCard.height)
            hasMiniRect = true
        }

        if (!fullMode || p >= 1f || w == 0 || h == 0) {
            if (container.scaleX != 1f || container.translationX != 0f || container.translationY != 0f) {
                container.scaleX = 1f
                container.scaleY = 1f
                container.translationX = 0f
                container.translationY = 0f
            }
            if (!reveal.isEmpty) {
                reveal.setEmpty()
                container.invalidateOutline()
            }
            return
        }
        if (!hasMiniRect) {
            val s = (36f * density).toInt()
            val m = (10f * density).toInt()
            miniRect.set(m, m, m + s, m + s)
            hasMiniRect = true
        }
        // same easing family as the choreographer's artwork: steady, lands with the sheet
        val t = p.coerceIn(0f, 1f)
        val e = 1f - Math.pow((1f - t).toDouble(), 1.25).toFloat()
        fun lerp(a: Float, b: Float) = a + (b - a) * e

        val cardW = miniRect.width().toFloat()
        val cardH = miniRect.height().toFloat()
        val s0 = cardW / w // the whole cover fits the card's width
        val s = lerp(s0, 1f)

        // Visible window in the container's own (unscaled) coordinates: a centered slice that is
        // exactly the card's shape at rest, opening up to the whole cover.
        val winH0 = cardH / s0
        val top0 = (h - winH0) / 2f
        val winTop = lerp(top0, 0f)
        val winBottom = lerp(top0 + winH0, h.toFloat())
        reveal.set(0, winTop.toInt(), w, winBottom.toInt())
        revealRadius = lerp(9f * density, 0f) / s // outline is scaled with the view

        container.scaleX = s
        container.scaleY = s
        // the window's top-left lands on the card's top-left at rest, on (0, 0) when open
        container.translationX = lerp(miniRect.left.toFloat(), 0f)
        container.translationY = lerp(miniRect.top.toFloat(), 0f) - winTop * s
        container.invalidateOutline()
    }

    /** Call when the current song changes (and once when the service connects). */
    fun onSongChanged(song: Song) {
        if (song.id == currentSongId) return
        currentSongId = song.id
        currentAlbumId = song.albumId

        resolveJob?.cancel()
        resolveJob = null
        clearFrameWait()

        if (!isEnabled() || song.albumId < 0) {
            dropVideo()
            return
        }

        val knownUrl = AlbumDetailsFragment.sharedEntry(context, song.albumId)?.url
        when {
            knownUrl != null -> {
                // Already known (from the album page or an earlier play): use the shared cache.
                AlbumDetailsFragment.sharedEnsureDownloaded(context, song.albumId)
                // Same video as before (next song of the same album): keep playing, no reload.
                if (knownUrl == videoUrl && player != null) return
                // Otherwise the SAME player gets the new source: the last frame of the previous
                // video stays visible until the new first frame, no cover image in between.
                videoUrl = knownUrl
                if (resumed) {
                    startVideo()
                    enterFullEarly() // no square-cover phase while the decoder starts
                }
            }
            AlbumDetailsFragment.sharedKnownNoVideo(context, song.albumId) -> dropVideo()
            else -> {
                // Looking up: the previous video (if any) freezes on its last frame, so the full
                // cover never flashes out and no cover image is drawn. It is dropped only once
                // we know there is no video for this song.
                videoUrl = null
                player?.playWhenReady = false
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
        player?.playWhenReady = shouldPlay()
    }

    /**
     * Queue open: hide + pause the video (Apple Music shows the small static artwork instead).
     * Closing it brings the video back only if the current song has one.
     */
    fun setSuppressed(value: Boolean, animate: Boolean = true) {
        if (suppressed == value) return
        suppressed = value
        container.animate().cancel()
        val target = if (fullMode && !value) 1f else 0f
        if (animate) container.animate().alpha(target).setDuration(FADE_MS).start()
        else container.alpha = target
        player?.playWhenReady = shouldPlay()
        if (!value && videoUrl != null && player == null && resumed) startVideo()
    }

    fun onResume(playing: Boolean) {
        resumed = true
        isPlaying = playing
        if (videoUrl != null && player == null) {
            startVideo()
        } else {
            player?.playWhenReady = shouldPlay()
        }
    }

    fun onPause() {
        resumed = false
        releasePlayer() // frees the decoder; the container stays as it is
    }

    fun release() {
        clearFrameWait()
        resolveJob?.cancel()
        resolveJob = null
        releasePlayer()
        container.animate().cancel()
    }

    // ------------------------------------------------------------------ lookup

    private fun lookup(song: Song) {
        resolveJob = fragment.viewLifecycleOwner.lifecycleScope.launch {
            val cover = resolver.resolve(song.title, song.artistName, song.albumName)
            if (cover == null) {
                if (currentSongId == song.id) dropVideo()
                return@launch
            }
            if (currentSongId != song.id) return@launch
            val master = cover.tallUrl ?: cover.squareUrl
            val width = context.resources.displayMetrics.widthPixels
            val variant = withContext(Dispatchers.IO) {
                AlbumDetailsFragment.sharedResolveVariant(master, width)
            }
            if (currentSongId != song.id) return@launch
            // Stores it in the album cache (the album page will see it) and starts the download.
            videoUrl = AlbumDetailsFragment.sharedStoreFound(context, song.albumId, variant)
            if (resumed) startVideo()
        }
    }

    // ------------------------------------------------------------------ modes

    private fun setFullMode(full: Boolean) {
        if (fullMode == full) return
        fullMode = full
        container.animate().cancel()
        val anim = container.animate().alpha(if (full && !suppressed) 1f else 0f).setDuration(FADE_MS)
        // Once the container has faded out the (now useless) player is released
        if (!full) anim.withEndAction { if (!fullMode && videoUrl == null) releasePlayer() }
        anim.start()
        onFullModeChanged(full)
    }

    /** There is no video for the current song: fade the container out, then free the player. */
    private fun dropVideo() {
        clearFrameWait()
        videoUrl = null
        player?.playWhenReady = false
        if (fullMode) setFullMode(false) // releases the player when the fade ends
        else releasePlayer()
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
        exo.playWhenReady = shouldPlay()
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

        // How long full mode may wait for the first frame before giving up on the video
        private const val FRAME_TIMEOUT_MS = 4_000L
    }
}
