/*
 * Copyright (c) 2020 Hemanth Savarla.
 *
 * Licensed under the GNU General Public License v3
 *
 * This is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 */
package code.name.monkey.retromusic.fragments.player.blur

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.ActionMenuView
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.Toolbar
import androidx.core.view.children
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import code.name.monkey.appthemehelper.util.ToolbarContentTintHelper
import code.name.monkey.retromusic.animatedcover.FullCoverController
import code.name.monkey.retromusic.NEW_BLUR_AMOUNT
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.adapter.song.PlayingQueueAdapter
import code.name.monkey.retromusic.databinding.FragmentBlurBinding
import code.name.monkey.retromusic.extensions.drawAboveSystemBars
import code.name.monkey.retromusic.extensions.whichFragment
import code.name.monkey.retromusic.fragments.base.AbsPlayerFragment
import code.name.monkey.retromusic.fragments.player.PlayerAlbumCoverFragment
import code.name.monkey.retromusic.glide.BlurTransformation
import code.name.monkey.retromusic.glide.RetroGlideExtension
import code.name.monkey.retromusic.glide.RetroGlideExtension.simpleSongCoverOptions
import code.name.monkey.retromusic.glide.crossfadeListener
import code.name.monkey.retromusic.helper.MusicPlayerRemote
import code.name.monkey.retromusic.lyrics.word.AppleLyricsView
import code.name.monkey.retromusic.lyrics.word.WordLyricsLoader
import code.name.monkey.retromusic.model.Song
import code.name.monkey.retromusic.util.PreferenceUtil
import code.name.monkey.retromusic.util.PreferenceUtil.blurAmount
import code.name.monkey.retromusic.util.color.MediaNotificationProcessor
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestBuilder
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.h6ah4i.android.widget.advrecyclerview.animator.DraggableItemAnimator
import com.h6ah4i.android.widget.advrecyclerview.draggable.RecyclerViewDragDropManager
import com.h6ah4i.android.widget.advrecyclerview.swipeable.RecyclerViewSwipeManager
import com.h6ah4i.android.widget.advrecyclerview.touchguard.RecyclerViewTouchActionGuardManager
import com.h6ah4i.android.widget.advrecyclerview.utils.WrapperAdapterUtils
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs


class BlurPlayerFragment : AbsPlayerFragment(R.layout.fragment_blur),
    SharedPreferences.OnSharedPreferenceChangeListener {

    private companion object {
        // Queue rows are shifted left by this much so the small covers line up with the progress
        // bar's left edge (32dp). Row metrics below were measured from item_queue.
        const val ROW_SHIFT_DP = 9f

        // Center of the drag handle inside a row (before the shift) ...
        const val ROW_HANDLE_CENTER_DP = 21f

        // ... and where it should end up: distance of its center from the screen's right edge
        // (right edge of the handle lines up with the progress bar's right edge)
        const val ROW_HANDLE_RIGHT_DP = 40f

        // The handle takes the place of the row's three dots, which are hidden
        const val HIDE_ROW_MENU = true

        // Corner radius of the small cover
        const val COVER_CORNER_DP = 8f

        // Gap between the end of the list (where its fade reaches zero) and the small cover
        const val LIST_BOTTOM_GAP_DP = 6f

        // Same as the video container's fade in FullCoverController
        const val PAGER_FADE_MS = 350L

        // Square cover fading out when a video takes over (kept short)
        const val PAGER_HIDE_MS = 200L
    }

    private var lastRequest: RequestBuilder<Drawable>? = null

    // Liquid background (rotating, saturated, blurred cover). The view only exists in layouts
    // that include it; where it is missing (e.g. landscape) the old blur background is used.
    private var liquidTarget: CustomTarget<Bitmap>? = null
    private var liquidSongId: Long = -1L

    // Full-screen cover (image + animated Apple Music video). Only exists in the portrait layout.
    private var fullCover: FullCoverController? = null

    // Album whose video first frame currently colors the Liquid background (-1 = use the cover).
    private var liquidPosterAlbumId: Long = -1L

    override fun isFullCoverActive() = fullCover?.isFull == true

    // Queue open: no artwork for the choreographer, so the cover never flies to the mini player
    override fun bigCoverImage(): View? = if (queueMode) null else super.bigCoverImage()

    // Last sheet state, so the full cover can be re-synced when the queue closes mid-drag
    private var lastSheetProgress = 1f
    private var lastMiniCard: View? = null

    override fun onSheetProgress(progress: Float, miniCard: View?) {
        lastSheetProgress = progress
        lastMiniCard = miniCard
        // Queue open: the cover must not fly to the mini player; the queue layout stays as it is
        if (queueMode) return
        fullCover?.applySheetProgress(progress, miniCard)
    }

    override fun playerToolbar(): Toolbar {
        return binding.playerToolbar
    }

    private lateinit var playbackControlsFragment: BlurPlaybackControlsFragment

    private var lastColor: Int = 0

    // ~45% white for the bottom icon row (matches Apple Music)
    private val iconColor = Color.argb(115, 255, 255, 255)

    // Slow drifting motion of the blurred cover (rotate / slide / breathe). Different durations
    // per property so the pattern never visibly repeats. Runs only while music is playing.
    private var bgAnimators: List<ObjectAnimator> = emptyList()

    private var _binding: FragmentBlurBinding? = null
    private val binding get() = _binding!!


    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentBlurBinding.bind(view)
        setUpSubFragments()
        setUpPlayerToolbar()
        setUpCircleButtons()
        binding.playerToolbar.drawAboveSystemBars()
        setUpFullCover()
    }

    private fun setUpFullCover() {
        val container = binding.fullCoverContainer ?: return
        val image = binding.fullCoverImage ?: return
        val video = binding.fullCoverVideo ?: return
        val cover: PlayerAlbumCoverFragment = whichFragment(R.id.playerAlbumCoverFragment)
        // Full mode (video playing): the pager keeps swipes/colors but draws nothing.
        // No video: the original square cover is shown, exactly as before.
        fullCover = FullCoverController(
            fragment = this,
            container = container,
            image = image,
            videoView = video,
            onFullModeChanged = { full ->
                fullModeNow = full
                // Queue open: the small cover stays; the video waits until the queue closes
                if (!queueMode) applyPagerForFullMode(full)
                cover.refreshPageCovers() // a dropped video gives the cover back to its page
            },
            onFrame = { bitmap, albumId ->
                if (bitmap == null) {
                    liquidPosterAlbumId = -1L // no video frame yet: the cover colors the background
                } else {
                    // The video's first frame colors the background, not the album cover.
                    liquidPosterAlbumId = albumId
                    _binding?.liquidBackground?.setArtwork(bitmap)
                }
            },
        )
        // Pages of songs that have a video stay empty (no cover while swiping to them);
        // with the queue open the small cover must show for every song
        cover.hideCoverOf = { song -> !queueMode && fullCover?.expectsVideo(song) == true }
    }

    // Real state of the full cover (video ready or not), tracked even while the queue is open
    private var fullModeNow = false

    // Cross-fades the square cover pager with the full-screen video instead of popping it in/out
    // (the pager sits above the video, so a pop used to flash the cover over the video).
    private var pagerAlphaAnimator: ObjectAnimator? = null

    private fun animatePagerAlpha(v: View, to: Float, delay: Long, dur: Long, end: (() -> Unit)?) {
        pagerAlphaAnimator?.cancel()
        pagerAlphaAnimator = ObjectAnimator.ofFloat(v, View.ALPHA, v.alpha, to).apply {
            startDelay = delay
            duration = dur
            if (end != null) {
                addListener(object : AnimatorListenerAdapter() {
                    private var cancelled = false
                    override fun onAnimationCancel(animation: Animator) {
                        cancelled = true
                    }

                    override fun onAnimationEnd(animation: Animator) {
                        if (!cancelled) end()
                    }
                })
            }
            start()
        }
    }

    private fun applyPagerForFullMode(full: Boolean) {
        pagerAlphaAnimator?.cancel()
        val cover: PlayerAlbumCoverFragment = whichFragment(R.id.playerAlbumCoverFragment)
        val cv = view?.findViewById<View>(R.id.playerAlbumCoverFragment)
        if (cv == null || !cv.isShown) {
            cover.invisiblePager = full
            cv?.alpha = 1f
            return
        }
        cv.animate().cancel()
        if (full) {
            // Short fade: the square cover is over the video only for this brief moment. When it
            // ends the pager is hidden instantly (see invisiblePager) BEFORE the alpha is restored.
            cv.animate().setStartDelay(0).alpha(0f).setDuration(PAGER_HIDE_MS).withEndAction {
                if (fullModeNow && !queueMode) cover.invisiblePager = true
                cv.alpha = 1f
            }.start()
        } else {
            cover.invisiblePager = false
            cv.alpha = 0f
            cv.animate().setStartDelay(0).alpha(1f).setDuration(PAGER_FADE_MS).start()
        }
    }

    private fun setUpSubFragments() {
        playbackControlsFragment = whichFragment(R.id.playbackControlsFragment)
        val playerAlbumCoverFragment: PlayerAlbumCoverFragment =
            whichFragment(R.id.playerAlbumCoverFragment)
        playerAlbumCoverFragment.setCallbacks(this)
    }

    private fun setUpPlayerToolbar() {
        binding.playerToolbar.apply {
            inflateMenu(R.menu.menu_player_blur)
            // (no back arrow in portrait; kept for the landscape layout which still has one)
            setNavigationOnClickListener { requireActivity().onBackPressedDispatcher.onBackPressed() }
            ToolbarContentTintHelper.colorizeToolbar(this, iconColor, activity)
        }.setOnMenuItemClickListener(this)
        spaceToolbarIcons()
    }

    // Favorite + "more" circles next to the title. "More" opens the overflow items in a popup.
    private fun setUpCircleButtons() {
        binding.favoriteCircleButton?.setOnClickListener {
            toggleFavorite(MusicPlayerRemote.currentSong)
        }
        binding.moreCircleButton?.setOnClickListener { anchor ->
            PopupMenu(requireContext(), anchor).apply {
                menuInflater.inflate(R.menu.menu_player_blur_overflow, menu)
                setOnMenuItemClickListener { onMenuItemClick(it) }
                show()
            }
        }
    }

    override fun onFavoriteStateUpdated(isFavorite: Boolean) {
        _binding?.favoriteCircleButton?.setImageResource(
            if (isFavorite) R.drawable.ic_favorite else R.drawable.ic_favorite_border
        )
    }

    // Spreads the bottom icons so their centers are exactly 103dp apart (Apple Music spacing),
    // centered on the screen, with no press ripple.
    private fun spaceToolbarIcons() {
        val toolbar = binding.playerToolbar
        toolbar.clipChildren = false
        toolbar.post {
            val menuView = toolbar.children.firstOrNull { it is ActionMenuView } as? ActionMenuView
                ?: return@post
            menuView.clipChildren = false
            // Full-width menu view so the spread-out icons stay inside its bounds (touch area)
            menuView.layoutParams = menuView.layoutParams.apply { width = ViewGroup.LayoutParams.MATCH_PARENT }
            val stepPx = 103f * resources.displayMetrics.density
            val apply = {
                val items = menuView.children.filter { it.visibility == View.VISIBLE }.toList()
                if (items.size > 1) {
                    val centers = items.map { it.left + it.width / 2f }
                    val mid = menuView.width / 2f
                    items.forEachIndexed { i, v ->
                        v.background = null // no white press halo
                        v.translationX = mid + (i - (items.size - 1) / 2f) * stepPx - centers[i]
                    }
                }
            }
            menuView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> apply() }
            apply()
        }
    }

    // ---------------------------------------------------------------------------------------
    // Inline queue (instead of opening the queue page): the cover shrinks next to the title, the
    // title block + controls slide down and get closer, and the queue list appears above them.
    // ---------------------------------------------------------------------------------------
    private var queueMode = false
    private var queueSettled = false
    private var queueAdapter: PlayingQueueAdapter? = null
    private var queueWrappedAdapter: RecyclerView.Adapter<*>? = null
    private var queueDragDropManager: RecyclerViewDragDropManager? = null
    private var queueSwipeManager: RecyclerViewSwipeManager? = null
    private var queueTouchGuardManager: RecyclerViewTouchActionGuardManager? = null

    // scale, translationX, translationY of the cover, the downward shift of the controls, then the
    // cover image rect inside the cover view (x, y, width, height) used for the rounded clip
    private var queueGeometry: FloatArray? = null

    // What fills the space above the title block while the panel is open. The panel itself
    // (small cover, controls moved down, pinning) is the one `queueMode` describes.
    private enum class Panel { QUEUE, LYRICS }

    private var panel = Panel.QUEUE

    override fun onMenuItemClick(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.now_playing -> {
                // Landscape layout has no inline queue: keep the old behaviour there
                if (_binding?.queueRecyclerView == null) return super.onMenuItemClick(item)
                when {
                    !queueMode -> setQueueMode(true, which = Panel.QUEUE)
                    panel == Panel.QUEUE -> setQueueMode(false)
                    else -> switchPanel(Panel.QUEUE)
                }
                return true
            }
            R.id.action_toggle_lyrics -> {
                // Lyrics take the queue's place; layouts without the inline panel keep the old behaviour
                if (_binding?.queueRecyclerView == null || _binding?.lyricsView == null) {
                    return super.onMenuItemClick(item)
                }
                when {
                    !queueMode -> setQueueMode(true, which = Panel.LYRICS)
                    panel == Panel.LYRICS -> setQueueMode(false)
                    else -> switchPanel(Panel.LYRICS)
                }
                return true
            }
        }
        return super.onMenuItemClick(item)
    }

    private fun setPanelChecked(which: Panel?) {
        _binding?.playerToolbar?.menu?.findItem(R.id.action_toggle_lyrics)?.isChecked =
            which == Panel.LYRICS
    }

    // Queue <-> lyrics while the panel stays open: only the content cross-fades
    private fun switchPanel(which: Panel) {
        if (!queueMode || panel == which || _binding == null) return
        setPanelChecked(which)
        showPanelContent(which, true)
    }

    private fun showPanelContent(which: Panel, animate: Boolean) {
        val list = _binding?.queueRecyclerView ?: return
        val lyrics = _binding?.lyricsView ?: return
        val d = resources.displayMetrics.density
        val duration = if (animate) 350L else 0L
        panel = which
        val shown: View = if (which == Panel.QUEUE) list else lyrics
        val hidden: View = if (which == Panel.QUEUE) lyrics else list

        shown.animate().cancel()
        hidden.animate().cancel()
        shown.visibility = View.VISIBLE
        if (which == Panel.QUEUE) {
            ensureQueueList(list)
            // Rows are shifted left so the small covers line up with the progress bar's left edge
            list.translationX = -ROW_SHIFT_DP * d
            (list.layoutManager as? LinearLayoutManager)
                ?.scrollToPositionWithOffset(MusicPlayerRemote.position + 1, 0)
        } else {
            prepareLyricsView(lyrics)
            loadLyrics()
        }
        shown.animate().setStartDelay(duration / 4).alpha(1f).setDuration(duration).start()
        hidden.animate().setStartDelay(0).alpha(0f).setDuration(duration * 2 / 3)
            .withEndAction { if (!queueMode || panel == which) hidden.visibility = View.GONE }
            .start()
    }

    private fun hidePanelContent(duration: Long) {
        listOfNotNull<View>(_binding?.queueRecyclerView, _binding?.lyricsView).forEach { v ->
            v.animate().cancel()
            v.animate().setStartDelay(0).alpha(0f).setDuration(duration * 2 / 3)
                .withEndAction { if (!queueMode) v.visibility = View.GONE }
                .start()
        }
    }

    // ---- Lyrics ---------------------------------------------------------------------------
    private var lyricsConfigured = false
    private var lyricsLoadJob: Job? = null
    private var lyricsLoadedSongId = -1L

    private fun prepareLyricsView(v: AppleLyricsView) {
        if (lyricsConfigured) return
        lyricsConfigured = true
        v.apply {
            textColor = Color.WHITE
            textSizeSp = 30f // dp; the sung line is drawn at 31dp
            horizontalPaddingDp = 32f // same left edge as the progress bar and the small cover
            anchorFraction = 0.27f // lower than before, closer to Apple Music
            positionProvider = { MusicPlayerRemote.songProgressMillis.toLong() }
            isPlayingProvider = { MusicPlayerRemote.isPlaying }
            onSeek = { MusicPlayerRemote.seekTo(it.toInt()) }
        }
    }

    private fun loadLyrics() {
        val v = _binding?.lyricsView ?: return
        val song = MusicPlayerRemote.currentSong
        if (song.id == lyricsLoadedSongId) return
        lyricsLoadJob?.cancel()
        // The previous song's lines must not stay on screen while the new file is read
        v.emptyLabel = ""
        v.setLyrics(null)
        lyricsLoadJob = viewLifecycleOwner.lifecycleScope.launch {
            val synced = WordLyricsLoader.load(song)
            _binding?.lyricsView?.let {
                it.setLyrics(synced)
                it.emptyLabel = if (synced == null) getString(R.string.no_lyrics_found) else ""
            }
            lyricsLoadedSongId = song.id
        }
    }

    private fun setQueueMode(show: Boolean, animate: Boolean = true, which: Panel = Panel.QUEUE) {
        if (queueMode == show || _binding == null) return
        val list = binding.queueRecyclerView ?: return
        val target = binding.queueCoverTarget ?: return
        val guide = binding.queueListBottomGuideline ?: return
        val rootView = requireView()
        val coverView = rootView.findViewById<View>(R.id.playerAlbumCoverFragment) ?: return
        val controlsView = rootView.findViewById<View>(R.id.playbackControlsFragment) ?: return
        val cover: PlayerAlbumCoverFragment = whichFragment(R.id.playerAlbumCoverFragment)
        val d = resources.displayMetrics.density
        val duration = if (animate) 350L else 0L
        val interpolator = DecelerateInterpolator(1.6f)
        val movers = listOfNotNull<View>(
            controlsView, binding.favoriteCircleButton, binding.moreCircleButton, target
        )

        if (show) {
            // The queue needs at least one song
            if (MusicPlayerRemote.playingQueue.isEmpty()) return
            queueMode = true
            queueSettled = false
            if (PreferenceUtil.showLyrics) {
                PreferenceUtil.showLyrics = false
                binding.playerToolbar.menu.findItem(R.id.action_toggle_lyrics)?.isChecked = false
            }
            setPanelChecked(which)

            // The small cover is drawn by the (scaled) cover pager, so make sure it is visible even
            // when the full-screen animated cover is active
            // (the video is hidden and paused while the queue is open)
            val pagerWasHidden = cover.invisiblePager
            coverView.animate().cancel()
            pagerAlphaAnimator?.cancel()
            coverView.alpha = if (pagerWasHidden) 0f else 1f
            cover.invisiblePager = false
            cover.refreshPageCovers() // queue open: every song shows its small cover
            // Over a video the cover only appears after the video has mostly faded out
            if (pagerWasHidden) animatePagerAlpha(coverView, 1f, duration / 3, duration * 2 / 3, null)
            fullCover?.setSuppressed(true, animate)

            // Geometry is measured while the cover is untouched; reused if we re-enter mid-animation
            var geo = queueGeometry
            if (geo == null || (coverView.scaleX == 1f && coverView.translationX == 0f)) {
                val playBtn = controlsView.findViewById<View>(R.id.playPauseButton)
                val dy = if (playBtn != null) {
                    // free space under the transport row once it has moved 20dp up (see
                    // BlurPlaybackControlsFragment.setQueueMode), keeping a 12dp gap above the toolbar
                    val slack = controlsView.height - (playBtn.bottom - 20 * d)
                    (slack - 12 * d).coerceIn(0f, 48 * d)
                } else {
                    32 * d
                }
                var scale = 0.18f
                var tx = 0f
                var ty = 0f
                var ix = 0f
                var iy = 0f
                var iw = coverView.width.toFloat()
                var ih = coverView.height.toFloat()
                val img = cover.currentCoverImage()
                if (img != null && img.width > 0 && target.width > 0) {
                    val a = IntArray(2)
                    val c = IntArray(2)
                    img.getLocationInWindow(a)
                    coverView.getLocationInWindow(c)
                    ix = (a[0] - c[0]).toFloat()
                    iy = (a[1] - c[1]).toFloat()
                    iw = img.width.toFloat()
                    ih = img.height.toFloat()
                    scale = target.width.toFloat() / img.width
                    tx = target.left - coverView.left - ix * scale
                    ty = target.top + dy - coverView.top - iy * scale
                }
                geo = floatArrayOf(scale, tx, ty, dy, ix, iy, iw, ih)
                queueGeometry = geo
            }
            val g = geo ?: return
            val scale = g[0]
            val tx = g[1]
            val ty = g[2]
            val dy = g[3]

            // The list ends right above the small cover; the fade reaches zero exactly at that edge
            guide.setGuidelineBegin((target.top + dy - LIST_BOTTOM_GAP_DP * d).toInt())

            coverView.pivotX = 0f
            coverView.pivotY = 0f
            coverView.animate().setStartDelay(0).scaleX(scale).scaleY(scale)
                .translationX(tx).translationY(ty)
                .setDuration(duration).setInterpolator(interpolator).start()
            movers.forEach {
                it.animate().setStartDelay(0).translationY(dy)
                    .setDuration(duration).setInterpolator(interpolator).start()
            }
            playbackControlsFragment.setQueueMode(true, animate)

            // Rounded corners on the small cover: clip the cover image area with a rounded rect.
            // The radius is given in the cover's own (unscaled) coordinates.
            coverClipRect.set(
                g[4].toInt(), g[5].toInt(), (g[4] + g[6]).toInt(), (g[5] + g[7]).toInt()
            )
            startCoverClip(coverView, COVER_CORNER_DP * d / scale, duration, interpolator, false)

            // Swallow touches on the small cover so it cannot be swiped by accident
            target.visibility = View.VISIBLE
            target.isClickable = true

            // Queue list or lyrics, in the same place
            showPanelContent(which, animate)

            // After the opening animation, keep the queue layout pinned against anything that
            // moves these views (sheet drag, etc.)
            rootView.removeCallbacks(queueSettleRunnable)
            rootView.postDelayed(queueSettleRunnable, duration + 60)
            startPinning()
        } else {
            queueMode = false
            queueSettled = false
            rootView.removeCallbacks(queueSettleRunnable)
            stopPinning()
            // Once the cover is back (or faded out over the video), empty the video songs' pages again
            coverView.postDelayed({ if (!queueMode) cover.refreshPageCovers() }, duration + 50)
            // The cover flies back; if the current song has a video the cover fades out into it
            coverView.animate().setStartDelay(0).scaleX(1f).scaleY(1f)
                .translationX(0f).translationY(0f)
                .setDuration(duration).setInterpolator(interpolator).start()
            // With a video the small cover fades out at once and never grows back over it
            pagerAlphaAnimator?.cancel()
            if (fullModeNow) {
                animatePagerAlpha(coverView, 0f, 0L, duration / 3) {
                    if (fullModeNow && !queueMode) cover.invisiblePager = true
                    coverView.alpha = 1f
                }
            } else {
                coverView.alpha = 1f
            }
            movers.forEach {
                it.animate().setStartDelay(0).translationY(0f)
                    .setDuration(duration).setInterpolator(interpolator).start()
            }
            playbackControlsFragment.setQueueMode(false, animate)
            startCoverClip(coverView, 0f, duration, interpolator, true)

            target.visibility = View.INVISIBLE
            target.isClickable = false

            hidePanelContent(duration)
            setPanelChecked(null)
            lyricsLoadedSongId = -1L // reopening re-reads the file (picks up edits)

            fullCover?.setSuppressed(false, animate)
            // The sheet may have been dragged while the queue was open: re-sync the full cover
            if (lastSheetProgress < 1f) fullCover?.applySheetProgress(lastSheetProgress, lastMiniCard)
        }
    }

    // ---- Queue layout pinning -------------------------------------------------------------
    // While the queue is open, the sheet drag (or anything else) must not reset the transforms
    // of the cover / controls / circle buttons. Re-applied right before every frame is drawn.
    private val queueSettleRunnable = Runnable { if (queueMode) queueSettled = true }

    private val pinListener = ViewTreeObserver.OnPreDrawListener {
        if (queueMode) {
            enforceQueueRows()
            if (queueSettled) pinQueueLayout()
        }
        true
    }

    // Row views are re-bound / recycled all the time, so the row tweaks (menu hidden, handle on
    // the right) are re-asserted before every frame instead of only when a row is attached.
    private val queueIdNames = HashMap<Int, String>()

    private fun queueViewName(v: View): String {
        if (v.id == View.NO_ID) return ""
        return queueIdNames.getOrPut(v.id) {
            try {
                resources.getResourceEntryName(v.id)
            } catch (e: Exception) {
                ""
            }
        }
    }

    private fun enforceQueueRows() {
        val list = _binding?.queueRecyclerView ?: return
        val shift = handleShiftPx()
        fun walk(v: View) {
            val name = queueViewName(v)
            if (HIDE_ROW_MENU && name.contains("menu", ignoreCase = true)) {
                if (v.visibility != View.INVISIBLE) v.visibility = View.INVISIBLE
                if (v.isClickable) v.isClickable = false
                return
            }
            if (name.startsWith("drag") && abs(v.translationX - shift) > 0.5f) {
                v.translationX = shift
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        for (i in 0 until list.childCount) walk(list.getChildAt(i))
    }

    private fun startPinning() {
        stopPinning()
        val vto = view?.viewTreeObserver ?: return
        if (vto.isAlive) vto.addOnPreDrawListener(pinListener)
    }

    private fun stopPinning() {
        val vto = view?.viewTreeObserver ?: return
        if (vto.isAlive) vto.removeOnPreDrawListener(pinListener)
    }

    private fun differs(a: Float, b: Float, eps: Float) = abs(a - b) > eps

    private fun pinQueueLayout() {
        val g = queueGeometry ?: return
        val root = view ?: return
        root.findViewById<View>(R.id.playerAlbumCoverFragment)?.let { c ->
            if (c.pivotX != 0f) c.pivotX = 0f
            if (c.pivotY != 0f) c.pivotY = 0f
            if (differs(c.scaleX, g[0], 0.001f)) c.scaleX = g[0]
            if (differs(c.scaleY, g[0], 0.001f)) c.scaleY = g[0]
            if (differs(c.translationX, g[1], 0.5f)) c.translationX = g[1]
            if (differs(c.translationY, g[2], 0.5f)) c.translationY = g[2]
        }
        val b = _binding ?: return
        listOfNotNull<View>(
            root.findViewById(R.id.playbackControlsFragment),
            b.favoriteCircleButton, b.moreCircleButton, b.queueCoverTarget
        ).forEach {
            if (differs(it.translationY, g[3], 0.5f)) it.translationY = g[3]
        }
    }

    // ---- Rounded corners of the small cover --------------------------------------------------
    private val coverClipRect = Rect()
    private var coverClipRadiusLocal = 0f
    private var coverClipAnimator: ValueAnimator? = null

    private val coverClipProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(coverClipRect, coverClipRadiusLocal)
        }
    }

    private fun startCoverClip(
        coverView: View,
        toRadius: Float,
        duration: Long,
        interpolator: TimeInterpolator,
        disableAtEnd: Boolean
    ) {
        coverClipAnimator?.cancel()
        coverClipAnimator = null
        coverView.outlineProvider = coverClipProvider
        coverView.clipToOutline = true
        if (duration <= 0L) {
            coverClipRadiusLocal = toRadius
            coverView.invalidateOutline()
            if (disableAtEnd) disableCoverClip(coverView)
            return
        }
        coverClipAnimator = ValueAnimator.ofFloat(coverClipRadiusLocal, toRadius).apply {
            this.duration = duration
            this.interpolator = interpolator
            addUpdateListener {
                coverClipRadiusLocal = it.animatedValue as Float
                coverView.invalidateOutline()
            }
            if (disableAtEnd) {
                addListener(object : AnimatorListenerAdapter() {
                    private var cancelled = false
                    override fun onAnimationCancel(animation: Animator) {
                        cancelled = true
                    }

                    override fun onAnimationEnd(animation: Animator) {
                        if (!cancelled) disableCoverClip(coverView)
                    }
                })
            }
            start()
        }
    }

    private fun disableCoverClip(coverView: View) {
        coverClipRadiusLocal = 0f
        coverView.clipToOutline = false
        coverView.outlineProvider = ViewOutlineProvider.BACKGROUND
    }

    private fun ensureQueueList(list: RecyclerView) {
        val existing = queueAdapter
        if (existing != null) {
            existing.swapDataSet(MusicPlayerRemote.playingQueue, MusicPlayerRemote.position)
            return
        }
        val guard = RecyclerViewTouchActionGuardManager()
        val dragDrop = RecyclerViewDragDropManager()
        val swipe = RecyclerViewSwipeManager()
        val songAdapter = PlayingQueueAdapter(
            requireActivity(),
            MusicPlayerRemote.playingQueue.toMutableList(),
            MusicPlayerRemote.position,
            R.layout.item_queue
        )
        var wrapped: RecyclerView.Adapter<*>? = dragDrop.createWrappedAdapter(songAdapter)
        wrapped = wrapped?.let { swipe.createWrappedAdapter(it) }

        queueAdapter = songAdapter
        queueWrappedAdapter = wrapped
        queueTouchGuardManager = guard
        queueDragDropManager = dragDrop
        queueSwipeManager = swipe

        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = wrapped
        list.itemAnimator = DraggableItemAnimator()
        guard.attachRecyclerView(list)
        dragDrop.attachRecyclerView(list)
        swipe.attachRecyclerView(list)
        // The queue row layout follows the app theme; the player is always white-on-cover
        list.addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(view: View) {
                view.background = null
                styleQueueRow(view)
            }

            override fun onChildViewDetachedFromWindow(view: View) {}
        })
    }

    private fun styleQueueRow(v: View) {
        val name = if (v.id != View.NO_ID) {
            try {
                resources.getResourceEntryName(v.id)
            } catch (e: Exception) {
                ""
            }
        } else ""
        val soft = Color.argb(153, 255, 255, 255)
        if (name.contains("dummy", ignoreCase = true)) v.background = null
        when {
            v is TextView -> v.setTextColor(if (name == "title") Color.WHITE else soft)
            v is ImageView && (name == "menu" || name.startsWith("drag")) ->
                v.imageTintList = ColorStateList.valueOf(soft)
        }
        // Drag handle moves to the right, where the three dots were; the dots are hidden
        if (v is ImageView && name.startsWith("drag")) v.translationX = handleShiftPx()
        if (HIDE_ROW_MENU && name.contains("menu", ignoreCase = true)) v.visibility = View.INVISIBLE
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) styleQueueRow(v.getChildAt(i))
        }
    }

    private fun handleShiftPx(): Float {
        val d = resources.displayMetrics.density
        val w = _binding?.queueRecyclerView?.width?.takeIf { it > 0 }
            ?: resources.displayMetrics.widthPixels
        // handle center (list coords) = screen x + ROW_SHIFT; screen x = width - ROW_HANDLE_RIGHT
        return w - (ROW_HANDLE_RIGHT_DP - ROW_SHIFT_DP) * d - ROW_HANDLE_CENTER_DP * d
    }

    private fun releaseQueueList() {
        stopPinning()
        coverClipAnimator?.cancel()
        coverClipAnimator = null
        queueDragDropManager?.cancelDrag()
        queueDragDropManager?.release()
        queueDragDropManager = null
        queueSwipeManager?.release()
        queueSwipeManager = null
        queueTouchGuardManager = null
        queueWrappedAdapter?.let { WrapperAdapterUtils.releaseAll(it) }
        queueWrappedAdapter = null
        queueAdapter = null
        queueGeometry = null
        queueMode = false
    }

    override fun onQueueChanged() {
        super.onQueueChanged()
        if (queueMode && MusicPlayerRemote.playingQueue.isEmpty()) {
            setQueueMode(false)
            return
        }
        queueAdapter?.swapDataSet(MusicPlayerRemote.playingQueue, MusicPlayerRemote.position)
    }

    override fun onFavoriteToggled() {
        toggleFavorite(MusicPlayerRemote.currentSong)
    }

    // The user swiped to another cover: start its video (or drop the current one) now, instead of
    // waiting for the service's song-changed callback, so the square cover is not left on screen
    override fun onPageChosen(position: Int) {
        MusicPlayerRemote.playingQueue.getOrNull(position)?.let { fullCover?.onSongChanged(it) }
    }

    override fun onColorChanged(color: MediaNotificationProcessor) {
        playbackControlsFragment.setColor(color)
        lastColor = color.backgroundColor
        libraryViewModel.updateColor(color.backgroundColor)
        ToolbarContentTintHelper.colorizeToolbar(binding.playerToolbar, iconColor, activity)
    }

    override fun toggleFavorite(song: Song) {
        super.toggleFavorite(song)
        if (song.id == MusicPlayerRemote.currentSong.id) {
            updateIsFavorite()
        }
    }

    override fun onShow() {
    }

    override fun onHide() {
    }

    override fun toolbarIconColor(): Int {
        return iconColor
    }

    // Closed-caption icon for the lyrics button, as in the design
    override fun lyricsIconRes(isShowing: Boolean): Int = R.drawable.ic_lyrics_blur

    override val paletteColor: Int
        get() = lastColor

    private fun updateLiquid() {
        val song = MusicPlayerRemote.currentSong
        if (song.id == liquidSongId) return
        liquidTarget?.let { Glide.with(this).clear(it) }
        val target = object : CustomTarget<Bitmap>(256, 256) {
            override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                liquidSongId = song.id
                if (liquidPosterAlbumId == song.albumId) return // the video frame already colors it
                _binding?.liquidBackground?.setArtwork(resource)
            }

            override fun onLoadFailed(errorDrawable: Drawable?) {
                liquidSongId = -1L
                _binding?.liquidBackground?.setArtwork(null)
            }

            override fun onLoadCleared(placeholder: Drawable?) {}
        }
        liquidTarget = target
        Glide.with(this)
            .asBitmap()
            .load(RetroGlideExtension.getSongModel(song))
            .into(target)
    }

    private fun updateBlur() {
        if (binding.liquidBackground != null) {
            updateLiquid()
            return
        }
        // https://github.com/bumptech/glide/issues/527#issuecomment-148840717
        Glide.with(this)
            .load(RetroGlideExtension.getSongModel(MusicPlayerRemote.currentSong))
            .simpleSongCoverOptions(MusicPlayerRemote.currentSong)
            .override(64, 64)
            .transform(
                BlurTransformation.Builder(requireContext()).blurRadius(25f)
                    .build()
            ).thumbnail(lastRequest)
            .error(Glide.with(this).load(ColorDrawable(Color.DKGRAY)).fitCenter())
            .also {
                lastRequest = it.clone()
                it.crossfadeListener()
                    .into(binding.colorBackground)
            }
    }

    override fun onServiceConnected() {
        updateIsFavorite()
        updateBlur()
        fullCover?.onSongChanged(MusicPlayerRemote.currentSong)
    }

    override fun onPlayingMetaChanged() {
        updateIsFavorite()
        updateBlur()
        fullCover?.onSongChanged(MusicPlayerRemote.currentSong)
        queueAdapter?.setCurrent(MusicPlayerRemote.position)
        if (_binding != null) {
            val cover: PlayerAlbumCoverFragment = whichFragment(R.id.playerAlbumCoverFragment)
            cover.refreshPageCovers()
        }
        if (queueMode && panel == Panel.QUEUE) {
            (_binding?.queueRecyclerView?.layoutManager as? LinearLayoutManager)
                ?.scrollToPositionWithOffset(MusicPlayerRemote.position + 1, 0)
        }
        if (queueMode && panel == Panel.LYRICS) loadLyrics() else lyricsLoadedSongId = -1L
    }

    private fun startBackgroundMotion() {
        stopBackgroundMotion()
        if (binding.liquidBackground != null) return // Liquid view animates itself
        val bg = binding.colorBackground
        bg.post {
            if (_binding == null || bg.width == 0 || bg.height == 0) return@post
            val w = bg.width.toFloat()
            val h = bg.height.toFloat()
            val minScale = 1.35f // just enough to hide edges while moving, so the cover keeps all its colors

            fun motion(prop: String, from: Float, to: Float, ms: Long) =
                ObjectAnimator.ofFloat(bg, prop, from, to).apply {
                    duration = ms
                    repeatCount = ValueAnimator.INFINITE
                    repeatMode = ValueAnimator.REVERSE
                    interpolator = AccelerateDecelerateInterpolator()
                }

            bg.scaleX = minScale
            bg.scaleY = minScale
            bgAnimators = listOf(
                motion("rotation", -5f, 5f, 17_000),
                motion("translationX", -w * 0.04f, w * 0.04f, 23_000),
                motion("translationY", -h * 0.025f, h * 0.025f, 29_000),
                motion("scaleX", minScale, minScale + 0.15f, 19_000),
                motion("scaleY", minScale, minScale + 0.15f, 19_000)
            )
            bgAnimators.forEach { it.start() }
            updateMotionState()
        }
    }

    private fun stopBackgroundMotion() {
        bgAnimators.forEach { it.cancel() }
        bgAnimators = emptyList()
    }

    // Move while playing, freeze in place when paused
    private fun updateMotionState() {
        bgAnimators.forEach {
            if (MusicPlayerRemote.isPlaying) {
                if (it.isPaused) it.resume()
            } else if (it.isRunning) {
                it.pause()
            }
        }
    }

    override fun onPlayStateChanged() {
        super.onPlayStateChanged()
        updateMotionState()
        _binding?.liquidBackground?.setPlaying(MusicPlayerRemote.isPlaying)
        fullCover?.setPlaying(MusicPlayerRemote.isPlaying)
    }

    override fun onPause() {
        super.onPause()
        queueDragDropManager?.cancelDrag()
        lastRequest = null
        stopBackgroundMotion()
        _binding?.liquidBackground?.setPlaying(false)
        fullCover?.onPause()
    }

    override fun onResume() {
        super.onResume()
        PreferenceManager.getDefaultSharedPreferences(requireContext())
            .registerOnSharedPreferenceChangeListener(this)
        startBackgroundMotion()
        _binding?.liquidBackground?.setPlaying(MusicPlayerRemote.isPlaying)
        fullCover?.onResume(MusicPlayerRemote.isPlaying)
    }

    override fun onDestroyView() {
        lastMiniCard = null
        lyricsLoadJob?.cancel()
        lyricsLoadJob = null
        lyricsConfigured = false
        lyricsLoadedSongId = -1L
        releaseQueueList()
        stopBackgroundMotion()
        liquidTarget?.let { Glide.with(this).clear(it) }
        liquidTarget = null
        liquidSongId = -1L
        fullCover?.release()
        fullCover = null
        super.onDestroyView()
        PreferenceManager.getDefaultSharedPreferences(requireContext())
            .unregisterOnSharedPreferenceChangeListener(this)
        _binding = null
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (key == NEW_BLUR_AMOUNT) {
            updateBlur()
        }
        if (key == FullCoverController.PREF_KEY) {
            fullCover?.refresh(MusicPlayerRemote.currentSong)
        }
    }
}
