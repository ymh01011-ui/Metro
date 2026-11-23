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
import android.animation.ValueAnimator
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.ActionMenuView
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.Toolbar
import androidx.core.view.children
import androidx.core.content.ContextCompat
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


class BlurPlayerFragment : AbsPlayerFragment(R.layout.fragment_blur),
    SharedPreferences.OnSharedPreferenceChangeListener {

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

    override fun onSheetProgress(progress: Float, miniCard: View?) {
        if (queueMode) {
            // While the queue is open the cover must not fly to the mini player (it would be drawn
            // over the list). Only when the player is fully collapsed: reset and let it run normally.
            if (progress > 0.01f) return
            setQueueMode(false, animate = false)
        }
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
            onFullModeChanged = { full -> cover.invisiblePager = full },
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
    // Everything is driven by one progress value (0 = normal player, 1 = queue) and re-applied on
    // every layout pass, so the small cover always follows the real position of the title row.
    // ---------------------------------------------------------------------------------------
    private var queueMode = false
    private var queueProgress = 0f
    private var queueAnimator: ValueAnimator? = null
    private var queueAdapter: PlayingQueueAdapter? = null
    private var queueWrappedAdapter: RecyclerView.Adapter<*>? = null
    private var queueDragDropManager: RecyclerViewDragDropManager? = null
    private var queueSwipeManager: RecyclerViewSwipeManager? = null
    private var queueTouchGuardManager: RecyclerViewTouchActionGuardManager? = null
    private var savedFullCoverAlpha = 0f
    private var savedInvisiblePager = false
    private var savedOutlineProvider: ViewOutlineProvider? = null

    // Measured when the queue opens (cover untouched): scale of the cover, where the artwork sits
    // inside the cover container, the artwork size and how far the controls slide down
    private var qScale = 0.15f
    private var qIx = 0f
    private var qIy = 0f
    private var qImgW = 0
    private var qImgH = 0
    private var qDy = 0f
    private var qGuideBegin = -1
    private var qRadius = 0f

    private val queueOutline = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(
                qIx.toInt(), qIy.toInt(), (qIx + qImgW).toInt(), (qIy + qImgH).toInt(), qRadius
            )
        }
    }

    private val queueLayoutListener =
        View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (queueMode && queueAnimator?.isRunning != true) applyQueueState(queueProgress)
        }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.now_playing -> {
                // Landscape layout has no inline queue: keep the old behaviour there
                if (_binding?.queueRecyclerView == null) return super.onMenuItemClick(item)
                setQueueMode(!queueMode)
                return true
            }
            // lyrics and queue use the same space
            R.id.action_toggle_lyrics -> if (queueMode) setQueueMode(false)
        }
        return super.onMenuItemClick(item)
    }

    private fun setQueueMode(show: Boolean, animate: Boolean = true) {
        if (queueMode == show || _binding == null) return
        val list = binding.queueRecyclerView ?: return
        val target = binding.queueCoverTarget ?: return
        val rootView = requireView()
        val coverView = rootView.findViewById<View>(R.id.playerAlbumCoverFragment) ?: return
        val controlsView = rootView.findViewById<View>(R.id.playbackControlsFragment) ?: return
        val cover: PlayerAlbumCoverFragment = whichFragment(R.id.playerAlbumCoverFragment)

        if (show) {
            // The queue needs at least one song
            if (MusicPlayerRemote.playingQueue.isEmpty()) return
            if (PreferenceUtil.showLyrics) {
                PreferenceUtil.showLyrics = false
                binding.playerToolbar.menu.findItem(R.id.action_toggle_lyrics)?.isChecked = false
            }
            ensureQueueList(list)

            if (queueProgress == 0f) {
                // Measure while everything is in its normal place
                measureQueueGeometry(coverView, controlsView, target, cover)
                savedFullCoverAlpha = binding.fullCoverContainer?.alpha ?: 0f
                savedOutlineProvider = coverView.outlineProvider
                qGuideBegin = -1
            }
            queueMode = true

            // The small cover is drawn by the cover pager, so it must be visible even when the
            // full-screen animated cover is active (that one is faded out by applyQueueState)
            savedInvisiblePager = cover.invisiblePager
            cover.invisiblePager = false

            playbackControlsFragment.setQueueMode(true, animate)
            // Swallow touches on the small cover so it cannot be swiped by accident
            target.visibility = View.VISIBLE
            target.isClickable = true
            (list.layoutManager as? LinearLayoutManager)
                ?.scrollToPositionWithOffset(MusicPlayerRemote.position + 1, 0)

            rootView.removeOnLayoutChangeListener(queueLayoutListener)
            rootView.addOnLayoutChangeListener(queueLayoutListener)
            animateQueueTo(1f, animate)
        } else {
            queueMode = false
            playbackControlsFragment.setQueueMode(false, animate)
            target.visibility = View.INVISIBLE
            target.isClickable = false
            cover.invisiblePager = savedInvisiblePager
            animateQueueTo(0f, animate) {
                if (!queueMode) {
                    list.visibility = View.GONE
                    rootView.removeOnLayoutChangeListener(queueLayoutListener)
                    coverView.clipToOutline = false
                    coverView.outlineProvider = savedOutlineProvider ?: ViewOutlineProvider.BACKGROUND
                }
            }
        }
    }

    private fun animateQueueTo(end: Float, animate: Boolean, onFinished: (() -> Unit)? = null) {
        queueAnimator?.cancel()
        if (!animate) {
            queueProgress = end
            applyQueueState(end)
            onFinished?.invoke()
            return
        }
        queueAnimator = ValueAnimator.ofFloat(queueProgress, end).apply {
            duration = 350
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener {
                queueProgress = it.animatedValue as Float
                applyQueueState(queueProgress)
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (!cancelled) onFinished?.invoke()
                }
            })
            start()
        }
    }

    private fun measureQueueGeometry(
        coverView: View,
        controlsView: View,
        target: View,
        cover: PlayerAlbumCoverFragment,
    ) {
        val d = resources.displayMetrics.density
        val playBtn = controlsView.findViewById<View>(R.id.playPauseButton)
        qDy = if (playBtn != null) {
            // free space under the transport row once it has moved 20dp up (see
            // BlurPlaybackControlsFragment.setQueueMode), keeping a 12dp gap above the toolbar
            val slack = controlsView.height - (playBtn.bottom - 20 * d)
            (slack - 12 * d).coerceIn(0f, 48 * d)
        } else {
            32 * d
        }
        val img = cover.currentCoverImage()
        if (img != null && img.width > 0 && target.width > 0) {
            val a = IntArray(2)
            val c = IntArray(2)
            img.getLocationInWindow(a)
            coverView.getLocationInWindow(c)
            qIx = (a[0] - c[0]).toFloat()
            qIy = (a[1] - c[1]).toFloat()
            qImgW = img.width
            qImgH = img.height
            qScale = target.width.toFloat() / img.width
        } else {
            qIx = 0f
            qIy = 0f
            qImgW = coverView.width
            qImgH = coverView.height
            qScale = if (coverView.width > 0) target.width.toFloat() / coverView.width else 0.15f
        }
    }

    /** Puts every moving part at its place for progress p (0 = normal, 1 = queue). */
    private fun applyQueueState(p: Float) {
        val b = _binding ?: return
        val list = b.queueRecyclerView ?: return
        val target = b.queueCoverTarget ?: return
        val guide = b.queueListBottomGuideline ?: return
        val rootView = view ?: return
        val coverView = rootView.findViewById<View>(R.id.playerAlbumCoverFragment) ?: return
        val controlsView = rootView.findViewById<View>(R.id.playbackControlsFragment) ?: return
        val d = resources.displayMetrics.density

        // Small cover: lands exactly on the target (left of the title), measured live
        val s = 1f + (qScale - 1f) * p
        coverView.pivotX = 0f
        coverView.pivotY = 0f
        coverView.scaleX = s
        coverView.scaleY = s
        coverView.translationX = p * (target.left - coverView.left - qIx * qScale)
        coverView.translationY = p * (target.top + qDy - coverView.top - qIy * qScale)

        // Rounded corners (10dp on screen at the end), only around the artwork
        if (p > 0f) {
            qRadius = 16f * d + (10f * d / qScale - 16f * d) * p
            coverView.outlineProvider = queueOutline
            coverView.clipToOutline = true
            coverView.invalidateOutline()
        }

        // Title block, favorite/more buttons and controls slide down together
        listOfNotNull<View>(controlsView, b.favoriteCircleButton, b.moreCircleButton, target)
            .forEach { it.translationY = p * qDy }

        // The list ends just above the small cover; the fade at its edge is part of the view
        if (p > 0f) {
            val gb = (target.top + qDy - 12 * d).toInt()
            if (gb != qGuideBegin) {
                guide.setGuidelineBegin(gb)
                qGuideBegin = gb
            }
            list.visibility = View.VISIBLE
        }
        list.alpha = ((p - 0.3f) / 0.7f).coerceIn(0f, 1f)
        b.fullCoverContainer?.alpha = savedFullCoverAlpha * (1f - p)
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
            R.layout.item_queue_blur
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
    }

    private fun releaseQueueList() {
        queueAnimator?.cancel()
        queueAnimator = null
        view?.removeOnLayoutChangeListener(queueLayoutListener)
        queueDragDropManager?.cancelDrag()
        queueDragDropManager?.release()
        queueDragDropManager = null
        queueSwipeManager?.release()
        queueSwipeManager = null
        queueTouchGuardManager = null
        queueWrappedAdapter?.let { WrapperAdapterUtils.releaseAll(it) }
        queueWrappedAdapter = null
        queueAdapter = null
        queueProgress = 0f
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
        if (queueMode) {
            (_binding?.queueRecyclerView?.layoutManager as? LinearLayoutManager)
                ?.scrollToPositionWithOffset(MusicPlayerRemote.position + 1, 0)
        }
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
