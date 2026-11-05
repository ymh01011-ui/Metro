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
package code.name.monkey.retromusic.activities.base

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.core.animation.doOnEnd
import androidx.core.view.*
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.commit
import code.name.monkey.appthemehelper.util.VersionUtils
import code.name.monkey.retromusic.ADAPTIVE_COLOR_APP
import code.name.monkey.retromusic.ALBUM_COVER_STYLE
import code.name.monkey.retromusic.ALBUM_COVER_TRANSFORM
import code.name.monkey.retromusic.CAROUSEL_EFFECT
import code.name.monkey.retromusic.CIRCLE_PLAY_BUTTON
import code.name.monkey.retromusic.EXTRA_SONG_INFO
import code.name.monkey.retromusic.KEEP_SCREEN_ON
import code.name.monkey.retromusic.LIBRARY_CATEGORIES
import code.name.monkey.retromusic.NOW_PLAYING_SCREEN_ID
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.SCREEN_ON_LYRICS
import code.name.monkey.retromusic.SWIPE_ANYWHERE_NOW_PLAYING
import code.name.monkey.retromusic.SWIPE_DOWN_DISMISS
import code.name.monkey.retromusic.TAB_TEXT_MODE
import code.name.monkey.retromusic.TOGGLE_ADD_CONTROLS
import code.name.monkey.retromusic.TOGGLE_FULL_SCREEN
import code.name.monkey.retromusic.TOGGLE_VOLUME
import code.name.monkey.retromusic.activities.PermissionActivity
import code.name.monkey.retromusic.databinding.SlidingMusicPanelLayoutBinding
import code.name.monkey.retromusic.extensions.*
import code.name.monkey.retromusic.fragments.LibraryViewModel
import code.name.monkey.retromusic.fragments.NowPlayingScreen
import code.name.monkey.retromusic.fragments.NowPlayingScreen.*
import code.name.monkey.retromusic.fragments.base.AbsPlayerFragment
import code.name.monkey.retromusic.fragments.other.MiniPlayerFragment
import code.name.monkey.retromusic.fragments.player.adaptive.AdaptiveFragment
import code.name.monkey.retromusic.fragments.player.blur.BlurPlayerFragment
import code.name.monkey.retromusic.fragments.player.card.CardFragment
import code.name.monkey.retromusic.fragments.player.cardblur.CardBlurFragment
import code.name.monkey.retromusic.fragments.player.circle.CirclePlayerFragment
import code.name.monkey.retromusic.fragments.player.classic.ClassicPlayerFragment
import code.name.monkey.retromusic.fragments.player.color.ColorFragment
import code.name.monkey.retromusic.fragments.player.fit.FitFragment
import code.name.monkey.retromusic.fragments.player.flat.FlatPlayerFragment
import code.name.monkey.retromusic.fragments.player.full.FullPlayerFragment
import code.name.monkey.retromusic.fragments.player.gradient.GradientPlayerFragment
import code.name.monkey.retromusic.fragments.player.material.MaterialFragment
import code.name.monkey.retromusic.fragments.player.md3.MD3PlayerFragment
import code.name.monkey.retromusic.fragments.player.normal.PlayerFragment
import code.name.monkey.retromusic.fragments.player.peek.PeekPlayerFragment
import code.name.monkey.retromusic.fragments.player.plain.PlainPlayerFragment
import code.name.monkey.retromusic.fragments.player.simple.SimplePlayerFragment
import code.name.monkey.retromusic.fragments.player.tiny.TinyPlayerFragment
import code.name.monkey.retromusic.fragments.queue.PlayingQueueFragment
import code.name.monkey.retromusic.helper.MusicPlayerRemote
import code.name.monkey.retromusic.model.CategoryInfo
import code.name.monkey.retromusic.util.PreferenceUtil
import code.name.monkey.retromusic.util.ViewUtil
import code.name.monkey.retromusic.util.logD
import code.name.monkey.retromusic.views.GlassHelper
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetBehavior.BottomSheetCallback
import com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_COLLAPSED
import com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_DRAGGING
import com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
import com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_HIDDEN
import com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_SETTLING
import com.google.android.material.bottomsheet.BottomSheetBehavior.from
import org.koin.androidx.viewmodel.ext.android.viewModel


abstract class AbsSlidingMusicPanelActivity : AbsMusicServiceActivity(),
    SharedPreferences.OnSharedPreferenceChangeListener {
    companion object {
        val TAG: String = AbsSlidingMusicPanelActivity::class.java.simpleName
    }

    var fromNotification = false
    private var windowInsets: WindowInsetsCompat? = null
    protected val libraryViewModel by viewModel<LibraryViewModel>()
    private lateinit var bottomSheetBehavior: BottomSheetBehavior<FrameLayout>
    private lateinit var playerFragment: AbsPlayerFragment
    private var miniPlayerFragment: MiniPlayerFragment? = null
    private var nowPlayingScreen: NowPlayingScreen? = null
    private var taskColor: Int = 0
    private var paletteColor: Int = Color.WHITE
    private var navigationBarColor = 0
    private var panelGlass: View? = null
    private var glassCompact = false
    /** true while a detail page (album, artist, playlist...) is open and the bar is folded */
    private var glassDetailMode = false
    private var compactProgress = 0f
    private var compactShiftY = 0f
    private var compactAnimator: ValueAnimator? = null
    private val scrollWatched: MutableSet<View> =
        java.util.Collections.newSetFromMap(java.util.WeakHashMap<View, Boolean>())

    private val glassScrollListener = object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
        override fun onScrolled(recyclerView: androidx.recyclerview.widget.RecyclerView, dx: Int, dy: Int) {
            // ignore horizontal lists
            if (recyclerView.layoutManager?.canScrollVertically() == true) {
                onGlassScroll(dy, !recyclerView.canScrollVertically(-1))
            }
        }
    }

    private val glassFragmentCallbacks = object : androidx.fragment.app.FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentViewCreated(
            fm: androidx.fragment.app.FragmentManager,
            f: androidx.fragment.app.Fragment,
            v: View,
            savedInstanceState: Bundle?
        ) {
            watchScrolling(v)
        }
    }

    private val panelState: Int
        get() = bottomSheetBehavior.state
    private lateinit var binding: SlidingMusicPanelLayoutBinding
    private var isInOneTabMode = false

    private var navigationBarColorAnimator: ValueAnimator? = null
    private val argbEvaluator: ArgbEvaluator = ArgbEvaluator()

    private val onBackPressedCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            println("Handle back press ${bottomSheetBehavior.state}")
            if (!handleBackPress()) {
                remove()
                onBackPressedDispatcher.onBackPressed()
            }
        }
    }

    // Closes the side drawer on back press while it is open
    private val drawerBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            closeDrawer()
        }
    }

    private val bottomSheetCallbackList by lazy {
        object : BottomSheetCallback() {

            override fun onSlide(bottomSheet: View, slideOffset: Float) {
                setMiniPlayerAlphaProgress(slideOffset)
                navigationBarColorAnimator?.cancel()
                setNavigationBarColorPreOreo(
                    argbEvaluator.evaluate(
                        slideOffset,
                        surfaceColor(),
                        navigationBarColor
                    ) as Int
                )
            }

            override fun onStateChanged(bottomSheet: View, newState: Int) {
                onBackPressedCallback.isEnabled = newState == STATE_EXPANDED
                // the player is opening: unfold; back to the mini player: fold again on detail pages
                setGlassCompact(newState == STATE_COLLAPSED && glassDetailMode)
                if (newState == STATE_EXPANDED) resetGlassCompact()
                if (PreferenceUtil.glassEnabled) {
                    if (newState == STATE_EXPANDED) {
                        GlassHelper.setHostBackgroundAlpha(binding.slidingPanel, 255)
                    } else if (newState == STATE_COLLAPSED) {
                        GlassHelper.setHostBackgroundAlpha(binding.slidingPanel, 0)
                    }
                }
                when (newState) {
                    STATE_EXPANDED -> {
                        onPanelExpanded()
                        if (PreferenceUtil.lyricsScreenOn && PreferenceUtil.showLyrics) {
                            keepScreenOn(true)
                        }
                    }

                    STATE_COLLAPSED -> {
                        onPanelCollapsed()
                        if ((PreferenceUtil.lyricsScreenOn && PreferenceUtil.showLyrics) || !PreferenceUtil.isScreenOnEnabled) {
                            keepScreenOn(false)
                        }
                    }

                    STATE_SETTLING, STATE_DRAGGING -> {
                        if (fromNotification) {
                            binding.navigationView.bringToFront()
                            fromNotification = false
                        }
                    }

                    STATE_HIDDEN -> {
                        MusicPlayerRemote.clearQueue()
                    }

                    else -> {
                        logD("Do a flip")
                    }
                }
            }
        }
    }

    fun getBottomSheetBehavior() = bottomSheetBehavior

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!hasPermissions()) {
            startActivity(Intent(this, PermissionActivity::class.java))
            finish()
        }
        binding = SlidingMusicPanelLayoutBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.setOnApplyWindowInsetsListener { _, insets ->
            windowInsets = WindowInsetsCompat.toWindowInsetsCompat(insets)
            insets
        }
        chooseFragmentForTheme()
        setupSlidingUpPanel()
        setupBottomSheet()
        updateColor()
        applyGlass()
        if (!PreferenceUtil.materialYou) {
            binding.slidingPanel.backgroundTintList = ColorStateList.valueOf(darkAccentColor())
            navigationView.backgroundTintList = ColorStateList.valueOf(darkAccentColor())
        }

        navigationBarColor = surfaceColor()

        onBackPressedDispatcher.addCallback(onBackPressedCallback)

        setupDrawerLock()
        onBackPressedDispatcher.addCallback(drawerBackCallback)
    }

    private fun setupBottomSheet() {
        bottomSheetBehavior = from(binding.slidingPanel)
        bottomSheetBehavior.addBottomSheetCallback(bottomSheetCallbackList)
        bottomSheetBehavior.isHideable = PreferenceUtil.swipeDownToDismiss
        bottomSheetBehavior.significantVelocityThreshold = 300
        setMiniPlayerAlphaProgress(0F)
    }

    override fun onResume() {
        super.onResume()
        PreferenceUtil.registerOnSharedPreferenceChangedListener(this)
        applyGlass()
        if (nowPlayingScreen != PreferenceUtil.nowPlayingScreen) {
            postRecreate()
        }
        if (bottomSheetBehavior.state == STATE_EXPANDED) {
            setMiniPlayerAlphaProgress(1f)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        bottomSheetBehavior.removeBottomSheetCallback(bottomSheetCallbackList)
        compactAnimator?.cancel()
        PreferenceUtil.unregisterOnSharedPreferenceChangedListener(this)
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        when (key) {
            PreferenceUtil.GLASS_ENABLED, PreferenceUtil.GLASS_MATERIAL,
            PreferenceUtil.GLASS_COLOUR, PreferenceUtil.GLASS_FROST,
            PreferenceUtil.GLASS_FILM, PreferenceUtil.GLASS_TINT, PreferenceUtil.GLASS_COMPACT,
            -> {
                applyGlass()
            }

            SWIPE_DOWN_DISMISS -> {
                bottomSheetBehavior.isHideable = PreferenceUtil.swipeDownToDismiss
            }

            TOGGLE_ADD_CONTROLS -> {
                miniPlayerFragment?.setUpButtons()
            }

            NOW_PLAYING_SCREEN_ID -> {
                chooseFragmentForTheme()
                binding.slidingPanel.updateLayoutParams<ViewGroup.LayoutParams> {
                    height = if (nowPlayingScreen != Peek) {
                        ViewGroup.LayoutParams.MATCH_PARENT
                    } else {
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    }
                    onServiceConnected()
                }
            }

            ALBUM_COVER_TRANSFORM, CAROUSEL_EFFECT,
            ALBUM_COVER_STYLE, TOGGLE_VOLUME, EXTRA_SONG_INFO, CIRCLE_PLAY_BUTTON,
            -> {
                chooseFragmentForTheme()
                onServiceConnected()
            }

            SWIPE_ANYWHERE_NOW_PLAYING -> {
                playerFragment.addSwipeDetector()
            }

            ADAPTIVE_COLOR_APP -> {
                if (PreferenceUtil.nowPlayingScreen in listOf(Normal, Material, Flat)) {
                    chooseFragmentForTheme()
                    onServiceConnected()
                }
            }

            LIBRARY_CATEGORIES -> {
                updateTabs()
            }

            TAB_TEXT_MODE -> {
                navigationView.labelVisibilityMode = PreferenceUtil.tabTitleMode
            }

            TOGGLE_FULL_SCREEN -> {
                recreate()
            }

            SCREEN_ON_LYRICS -> {
                keepScreenOn(bottomSheetBehavior.state == STATE_EXPANDED && PreferenceUtil.lyricsScreenOn && PreferenceUtil.showLyrics || PreferenceUtil.isScreenOnEnabled)
            }

            KEEP_SCREEN_ON -> {
                maybeSetScreenOn()
            }
        }
    }

    /**
     * Adds / updates / removes the floating glass pills behind the bottom navigation and the
     * mini player, according to the "Glass" settings.
     */
    private fun applyGlass() {
        val enabled = PreferenceUtil.glassEnabled
        val blurRoot = binding.fragmentContainer
        val nav = binding.navigationView
        // The landscape navigation rail does not overlap the content, so only the bottom bar gets glass
        val glassBar = enabled && nav is BottomNavigationView
        val miniVertical = GlassHelper.dp(this, 2)

        // tapping the folded bar unfolds it (as in Square)
        GlassHelper.sync(
            nav,
            blurRoot,
            glassBar,
            GlassHelper.dp(this, GlassHelper.NAV_PILL_HEIGHT_DP),
            0,
            withIcon = true
        ) { onGlassCircleClicked() }
        GlassHelper.syncTabs(nav, glassBar)
        GlassHelper.setNavBackgroundHidden(nav, glassBar)

        panelGlass = GlassHelper.sync(
            binding.slidingPanel,
            blurRoot,
            enabled,
            dip(R.dimen.mini_player_height) - 2 * miniVertical,
            2
        )
        panelGlass?.alpha = if (panelState == STATE_EXPANDED) 0F else 1F
        GlassHelper.setHostBackgroundAlpha(
            binding.slidingPanel,
            if (!enabled || panelState == STATE_EXPANDED) 255 else 0
        )
        GlassHelper.setMiniInsets(
            miniPlayerFragment?.view,
            enabled,
            0
        )
        // the mini player's text and buttons follow the brightness of the page behind it
        val miniGlass = GlassHelper.layerOf(binding.slidingPanel)
        miniGlass?.onLightExtra = { onLight -> miniPlayerFragment?.applyGlassInk(onLight) }
        miniPlayerFragment?.applyGlassInk(
            if (miniGlass != null && miniGlass.sampled) miniGlass.onLight else null
        )

        if (!glassBar || !PreferenceUtil.glassCompact) {
            glassDetailMode = false
            resetGlassCompact()
        }
    }

    // ---- folded bar (circle + mini player pill) while scrolling down, like Square ----

    private var scrollAccum = 0

    private fun glassBarActive(): Boolean =
        PreferenceUtil.glassEnabled && PreferenceUtil.glassCompact && !isLandscape &&
            navigationView is BottomNavigationView && !isInOneTabMode

    /** Tapping the circle goes back to the page you came from. */
    private fun onGlassCircleClicked() {
        if (glassCompact) {
            onBackPressedDispatcher.onBackPressed()
        } else {
            setGlassCompact(false)
        }
    }

    private fun onGlassScroll(dy: Int, atTop: Boolean) {
        // folding on scroll was replaced by folding on detail pages
        if (true) return
        if (!PreferenceUtil.glassEnabled || !PreferenceUtil.glassCompact) return
        if (atTop) {
            // the bar comes back at the top of the page
            scrollAccum = 0
            setGlassCompact(false)
            return
        }
        // accumulate, and start over when the direction changes
        if ((scrollAccum > 0 && dy < 0) || (scrollAccum < 0 && dy > 0)) scrollAccum = 0
        scrollAccum += dy
        val threshold = GlassHelper.dp(this, 50)
        if (scrollAccum >= threshold) {
            scrollAccum = 0
            setGlassCompact(true)
        } else if (scrollAccum <= -threshold) {
            scrollAccum = 0
            setGlassCompact(false)
        }
    }

    private fun setGlassCompact(compact: Boolean) {
        if (compact == glassCompact) return
        if (compact) {
            if (isLandscape || !PreferenceUtil.glassEnabled || !PreferenceUtil.glassCompact) return
            if (panelState != STATE_COLLAPSED || !isBottomNavVisible) return
            val navLayer = GlassHelper.layerOf(binding.navigationView) ?: return
            val miniPill = panelGlass
            compactShiftY =
                if (miniPill != null && MusicPlayerRemote.playingQueue.isNotEmpty()) {
                    GlassHelper.centerDelta(navLayer, miniPill)
                } else 0F
            // the circle shows the tab you are on
            val menu = binding.navigationView.menu
            GlassHelper.setSelectedIcon(
                navLayer,
                menu.findItem(binding.navigationView.selectedItemId)?.icon
            )
        }
        glassCompact = compact
        compactAnimator?.cancel()
        compactAnimator = ValueAnimator.ofFloat(compactProgress, if (compact) 1F else 0F).apply {
            duration = 450
            interpolator = android.view.animation.PathInterpolator(0.2F, 0F, 0F, 1F)
            addUpdateListener {
                compactProgress = it.animatedValue as Float
                renderCompact()
            }
            start()
        }
    }

    private fun resetGlassCompact() {
        compactAnimator?.cancel()
        glassCompact = false
        compactProgress = 0F
        scrollAccum = 0
        renderCompact()
    }

    private fun renderCompact() {
        val circle = panelGlass?.layoutParams?.height?.takeIf { it > 0 }
            ?: (dip(R.dimen.mini_player_height) - 2 * GlassHelper.dp(this, 2))
        GlassHelper.applyCompact(
            binding.navigationView,
            binding.slidingPanel,
            miniPlayerFragment?.view,
            compactProgress,
            compactShiftY,
            circle
        )
    }

    /** Finds the scrolling lists of a fragment and reports their scroll direction. */
    private fun watchScrolling(view: View) {
        if (view is androidx.recyclerview.widget.RecyclerView) {
            if (scrollWatched.add(view)) view.addOnScrollListener(glassScrollListener)
        } else if (view is androidx.core.widget.NestedScrollView) {
            if (scrollWatched.add(view)) {
                var last = view.scrollY
                view.viewTreeObserver.addOnScrollChangedListener {
                    val y = view.scrollY
                    val dy = y - last
                    last = y
                    if (dy != 0 || y == 0) onGlassScroll(dy, y == 0)
                }
            }
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) watchScrolling(view.getChildAt(i))
        }
    }

    fun collapsePanel() {
        bottomSheetBehavior.state = STATE_COLLAPSED
    }

    fun expandPanel() {
        bottomSheetBehavior.state = STATE_EXPANDED
    }

    private fun setMiniPlayerAlphaProgress(progress: Float) {
        if (progress < 0) return
        val alpha = 1 - progress
        miniPlayerFragment?.view?.alpha = 1 - (progress / 0.2F)
        miniPlayerFragment?.view?.isGone = alpha == 0f
        if (!isLandscape) {
            binding.navigationView.translationY = progress * 500
            binding.navigationView.alpha = alpha
        }
        binding.playerFragmentContainer.alpha = (progress - 0.2F) / 0.2F
        panelGlass?.alpha = (1 - progress / 0.2F).coerceIn(0F, 1F)
        if (PreferenceUtil.glassEnabled) {
            GlassHelper.setHostBackgroundAlpha(
                binding.slidingPanel,
                ((progress / 0.2F).coerceIn(0F, 1F) * 255).toInt()
            )
        }
    }

    private fun animateNavigationBarColor(color: Int) {
        if (VersionUtils.hasOreo()) return
        navigationBarColorAnimator?.cancel()
        navigationBarColorAnimator = ValueAnimator
            .ofArgb(window.navigationBarColor, color).apply {
                duration = ViewUtil.RETRO_MUSIC_ANIM_TIME.toLong()
                interpolator = PathInterpolator(0.4f, 0f, 1f, 1f)
                addUpdateListener { animation: ValueAnimator ->
                    setNavigationBarColorPreOreo(
                        animation.animatedValue as Int
                    )
                }
                start()
            }
    }

    open fun onPanelCollapsed() {
        setMiniPlayerAlphaProgress(0F)
        // restore values
        animateNavigationBarColor(surfaceColor())
        setLightStatusBarAuto()
        setLightNavigationBarAuto()
        setTaskDescriptionColor(taskColor)
        //playerFragment?.onHide()
    }

    open fun onPanelExpanded() {
        setMiniPlayerAlphaProgress(1F)
        onPaletteColorChanged()
        //playerFragment?.onShow()
    }

    private fun setupSlidingUpPanel() {
        binding.slidingPanel.viewTreeObserver.addOnGlobalLayoutListener(object :
            ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                binding.slidingPanel.viewTreeObserver.removeOnGlobalLayoutListener(this)
                if (nowPlayingScreen != Peek) {
                    binding.slidingPanel.updateLayoutParams<ViewGroup.LayoutParams> {
                        height = ViewGroup.LayoutParams.MATCH_PARENT
                    }
                }
                when (panelState) {
                    STATE_EXPANDED -> onPanelExpanded()
                    STATE_COLLAPSED -> onPanelCollapsed()
                    else -> {
                        // playerFragment!!.onHide()
                    }
                }
            }
        })
    }

    val navigationView get() = binding.navigationView

    val slidingPanel get() = binding.slidingPanel

    val drawerNavigationView get() = binding.drawerNavigationView

    fun openDrawer() {
        // Unlock first: the drawer is locked closed so it can't be swiped open from the edge
        binding.drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED)
        binding.drawerLayout.openDrawer(GravityCompat.START)
    }

    fun closeDrawer() {
        binding.drawerLayout.closeDrawer(GravityCompat.START)
    }

    /**
     * The drawer is only opened from the hamburger in the home search bar, so edge-swipe
     * opening is disabled (it would also fight the system back gesture). While the drawer is
     * open it is unlocked so it can still be swiped closed.
     */
    private fun setupDrawerLock() {
        val drawer = binding.drawerLayout
        drawer.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
        drawer.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerOpened(drawerView: View) {
                drawerBackCallback.isEnabled = true
                drawer.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED)
            }

            override fun onDrawerClosed(drawerView: View) {
                drawerBackCallback.isEnabled = false
                if (drawer.getDrawerLockMode(GravityCompat.START) != DrawerLayout.LOCK_MODE_LOCKED_CLOSED) {
                    drawer.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
                }
            }
        })
    }

    val isBottomNavVisible get() = navigationView.isVisible && navigationView is BottomNavigationView

    override fun onServiceConnected() {
        super.onServiceConnected()
        hideBottomSheet(false)
    }

    override fun onQueueChanged() {
        super.onQueueChanged()
        // Mini player should be hidden in Playing Queue
        // it may pop up if hideBottomSheet is called
        if (currentFragment(R.id.fragment_container) !is PlayingQueueFragment) {
            hideBottomSheet(MusicPlayerRemote.playingQueue.isEmpty())
        }
    }

    private fun handleBackPress(): Boolean {
        if (panelState == STATE_EXPANDED) {
            collapsePanel()
            return true
        }
        return false
    }

    private fun onPaletteColorChanged() {
        if (panelState == STATE_EXPANDED) {
            navigationBarColor = surfaceColor()
            setTaskDescColor(paletteColor)
            val isColorLight = paletteColor.isColorLight
            if (PreferenceUtil.isAdaptiveColor && (nowPlayingScreen == Normal || nowPlayingScreen == Flat || nowPlayingScreen == Material)) {
                setLightNavigationBar(true)
                setLightStatusBar(isColorLight)
            } else if (nowPlayingScreen == Card || nowPlayingScreen == Blur || nowPlayingScreen == BlurCard) {
                animateNavigationBarColor(Color.BLACK)
                navigationBarColor = Color.BLACK
                setLightStatusBar(false)
                setLightNavigationBar(true)
            } else if (nowPlayingScreen == Color || nowPlayingScreen == Tiny || nowPlayingScreen == Gradient) {
                animateNavigationBarColor(paletteColor)
                navigationBarColor = paletteColor
                setLightNavigationBar(isColorLight)
                setLightStatusBar(isColorLight)
            } else if (nowPlayingScreen == Full) {
                animateNavigationBarColor(paletteColor)
                navigationBarColor = paletteColor
                setLightNavigationBar(isColorLight)
                setLightStatusBar(false)
            } else if (nowPlayingScreen == Classic) {
                setLightStatusBar(false)
            } else if (nowPlayingScreen == Fit) {
                setLightStatusBar(false)
            }
        }
    }

    private fun setTaskDescColor(color: Int) {
        taskColor = color
        if (panelState == STATE_COLLAPSED) {
            setTaskDescriptionColor(color)
        }
    }

    fun updateTabs() {
        binding.navigationView.menu.clear()
        val currentTabs: List<CategoryInfo> = PreferenceUtil.libraryCategory
        for (tab in currentTabs) {
            if (tab.visible) {
                val menu = tab.category
                binding.navigationView.menu.add(0, menu.id, 0, menu.stringRes)
                    .setIcon(menu.icon)
            }
        }
        if (binding.navigationView.menu.size() == 1) {
            isInOneTabMode = true
            binding.navigationView.isVisible = false
        } else {
            isInOneTabMode = false
        }
    }

    private fun updateColor() {
        libraryViewModel.paletteColor.observe(this) { color ->
            this.paletteColor = color
            onPaletteColorChanged()
        }
    }

    fun setBottomNavVisibility(
        visible: Boolean,
        animate: Boolean = false,
        hideBottomSheet: Boolean = MusicPlayerRemote.playingQueue.isEmpty(),
    ) {
        if (!ViewCompat.isLaidOut(navigationView)) {
            return
        }
        if (isInOneTabMode) {
            hideBottomSheet(
                hide = hideBottomSheet,
                animate = animate,
                isBottomNavVisible = false
            )
            return
        }
        if (glassBarActive()) {
            // Glass: the bar never disappears. On a detail page it folds into a circle (the
            // icon of the page you came from) next to the mini player.
            glassDetailMode = !visible
            binding.navigationView.isVisible = true
            binding.navigationView.translationY = 0F
            if (bottomSheetBehavior.state != STATE_EXPANDED) binding.navigationView.bringToFront()
            hideBottomSheet(
                hide = hideBottomSheet,
                animate = animate,
                isBottomNavVisible = true
            )
            setGlassCompact(glassDetailMode)
            return
        }
        if (visible xor navigationView.isVisible) {
            val mAnimate = animate && bottomSheetBehavior.state == STATE_COLLAPSED
            if (mAnimate) {
                if (visible) {
                    binding.navigationView.bringToFront()
                    binding.navigationView.show()
                } else {
                    binding.navigationView.hide()
                }
            } else {
                binding.navigationView.isVisible = visible
                if (visible && bottomSheetBehavior.state != STATE_EXPANDED) {
                    binding.navigationView.bringToFront()
                }
            }
        }
        hideBottomSheet(
            hide = hideBottomSheet,
            animate = animate,
            isBottomNavVisible = visible && navigationView is BottomNavigationView
        )
    }

    fun hideBottomSheet(
        hide: Boolean,
        animate: Boolean = false,
        isBottomNavVisible: Boolean = navigationView.isVisible && navigationView is BottomNavigationView,
    ) {
        val heightOfBar = windowInsets.getBottomInsets() + dip(R.dimen.mini_player_height)
        val heightOfBarWithTabs = heightOfBar + dip(R.dimen.bottom_nav_height)
        if (hide) {
            bottomSheetBehavior.peekHeight = -windowInsets.getBottomInsets()
            bottomSheetBehavior.state = STATE_COLLAPSED
            libraryViewModel.setFabMargin(
                this,
                if (isBottomNavVisible) dip(R.dimen.bottom_nav_height) else 0
            )
        } else {
            if (MusicPlayerRemote.playingQueue.isNotEmpty()) {
                binding.slidingPanel.elevation = 0F
                binding.navigationView.elevation = 5F
                if (isBottomNavVisible) {
                    logD("List")
                    if (animate) {
                        bottomSheetBehavior.peekHeightAnimate(heightOfBarWithTabs)
                    } else {
                        bottomSheetBehavior.peekHeight = heightOfBarWithTabs
                    }
                    libraryViewModel.setFabMargin(
                        this,
                        dip(R.dimen.bottom_nav_mini_player_height)
                    )
                } else {
                    logD("Details")
                    if (animate) {
                        bottomSheetBehavior.peekHeightAnimate(heightOfBar).doOnEnd {
                            binding.slidingPanel.bringToFront()
                        }
                    } else {
                        bottomSheetBehavior.peekHeight = heightOfBar
                        binding.slidingPanel.bringToFront()
                    }
                    libraryViewModel.setFabMargin(this, dip(R.dimen.mini_player_height))
                }
            }
        }
    }

    fun setAllowDragging(allowDragging: Boolean) {
        bottomSheetBehavior.isDraggable = allowDragging
        hideBottomSheet(false)
    }

    private fun chooseFragmentForTheme() {
        nowPlayingScreen = PreferenceUtil.nowPlayingScreen

        val fragment: AbsPlayerFragment = when (nowPlayingScreen) {
            Blur -> BlurPlayerFragment()
            Adaptive -> AdaptiveFragment()
            Normal -> PlayerFragment()
            Card -> CardFragment()
            BlurCard -> CardBlurFragment()
            Fit -> FitFragment()
            Flat -> FlatPlayerFragment()
            Full -> FullPlayerFragment()
            Plain -> PlainPlayerFragment()
            Simple -> SimplePlayerFragment()
            Material -> MaterialFragment()
            Color -> ColorFragment()
            Gradient -> GradientPlayerFragment()
            Tiny -> TinyPlayerFragment()
            Peek -> PeekPlayerFragment()
            Circle -> CirclePlayerFragment()
            Classic -> ClassicPlayerFragment()
            MD3 -> MD3PlayerFragment()
            else -> PlayerFragment()
        } // must extend AbsPlayerFragment
        supportFragmentManager.commit {
            replace(R.id.playerFragmentContainer, fragment)
        }
        supportFragmentManager.executePendingTransactions()
        playerFragment = whichFragment(R.id.playerFragmentContainer)
        miniPlayerFragment = whichFragment<MiniPlayerFragment>(R.id.miniPlayerFragment)
        miniPlayerFragment?.view?.setOnClickListener { expandPanel() }
    }
}
