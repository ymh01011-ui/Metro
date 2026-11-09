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
package code.name.monkey.retromusic.fragments.home

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.SystemClock
import android.view.*
import android.widget.TextView
import code.name.monkey.retromusic.views.GlassHelper
import code.name.monkey.retromusic.views.SearchBarIcon
import code.name.monkey.retromusic.views.GlassLayer
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.graphics.ColorUtils
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.core.view.doOnPreDraw
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.core.widget.NestedScrollView
import com.google.android.material.color.MaterialColors
import androidx.navigation.fragment.FragmentNavigatorExtras
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import code.name.monkey.appthemehelper.util.ColorUtil
import code.name.monkey.retromusic.*
import code.name.monkey.retromusic.adapter.HomeAdapter
import code.name.monkey.retromusic.databinding.FragmentHomeBinding
import code.name.monkey.retromusic.extensions.accentColor
import code.name.monkey.retromusic.extensions.dip
import code.name.monkey.retromusic.extensions.elevatedAccentColor
import code.name.monkey.retromusic.fragments.ReloadType
import code.name.monkey.retromusic.fragments.base.AbsMainActivityFragment
import code.name.monkey.retromusic.glide.RetroGlideExtension
import code.name.monkey.retromusic.glide.RetroGlideExtension.profileBannerOptions
import code.name.monkey.retromusic.glide.RetroGlideExtension.songCoverOptions
import code.name.monkey.retromusic.glide.RetroGlideExtension.userProfileOptions
import code.name.monkey.retromusic.helper.MusicPlayerRemote
import code.name.monkey.retromusic.interfaces.IScrollHelper
import code.name.monkey.retromusic.model.Song
import code.name.monkey.retromusic.util.PreferenceUtil
import code.name.monkey.retromusic.util.PreferenceUtil.userName
import com.bumptech.glide.Glide
import com.google.android.material.transition.MaterialFadeThrough
import com.google.android.material.transition.MaterialSharedAxis
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import androidx.recyclerview.widget.GridLayoutManager
import code.name.monkey.retromusic.adapter.HomePinnedAdapter
import code.name.monkey.retromusic.adapter.HomeRecentSongsAdapter
import code.name.monkey.retromusic.databinding.ItemHomeTileBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Tile corner radius as a share of the tile width (matches the mock-up). */
private const val TILE_CORNER_RATIO = 0.17f

class HomeFragment :
    AbsMainActivityFragment(R.layout.fragment_home), IScrollHelper {

    private var _binding: HomeBinding? = null
    private val binding get() = _binding!!

    private var recentAdapter: HomeRecentSongsAdapter? = null
    private var pinnedAdapter: HomePinnedAdapter? = null

    // these live on the fragment, which stays on the back stack while another screen is open
    private var savedScrollY = 0
    private var lastHomeReload = 0L

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val homeBinding = FragmentHomeBinding.bind(view)
        _binding = HomeBinding(homeBinding)

        enterTransition = MaterialFadeThrough().addTarget(binding.contentContainer)
        reenterTransition = MaterialFadeThrough().addTarget(binding.contentContainer)

        checkForMargins()

        setupTiles()
        setupRecentlyAdded()
        setupPinned()

        val homeAdapter = HomeAdapter(mainActivity)
        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(mainActivity)
            adapter = homeAdapter
        }
        libraryViewModel.getHome().observe(viewLifecycleOwner) {
            homeAdapter.swapData(it)
            restoreScroll()
        }

        setupSearchBar()
        postponeEnterTransition()
        view.doOnPreDraw { startPostponedEnterTransition() }
    }

    // ---------------------------------------------------------------- tiles

    private fun setupTiles() {
        bindTile(binding.tileHistory, HomeTileStyles.HISTORY) { openDetail(HISTORY_PLAYLIST) }
        bindTile(binding.tileMostPlayed, HomeTileStyles.MOST_PLAYED) { openDetail(TOP_PLAYED_PLAYLIST) }
        bindTile(binding.tileFavorite, HomeTileStyles.FAVORITE) { openDetail(FAVOURITES, xAxis = true) }
        bindTile(binding.tileForYouNow, HomeTileStyles.FOR_YOU_NOW) { openDetail(FOR_YOU_NOW_PLAYLIST) }
        bindTile(binding.tileRediscover, HomeTileStyles.REDISCOVER) { openDetail(REDISCOVER_PLAYLIST) }
        bindTile(binding.tileStats, HomeTileStyles.STATS) {
            findNavController().navigate(R.id.listeningStatsFragment)
            setSharedAxisYTransitions()
        }
    }

    /** Paints one square tile: gradient, rounded clip, thin outline, soft glow, icon and title. */
    private fun bindTile(tile: ItemHomeTileBinding, style: HomeTileStyle, onClick: () -> Unit) {
        val density = resources.displayMetrics.density
        val dark = HomeTileStyles.isDark(tile.root)
        val ink = style.inkFor(dark)

        val fill = GradientDrawable(GradientDrawable.Orientation.TL_BR, style.gradientFor(dark))
        val strokeShape = GradientDrawable()
        strokeShape.setShape(GradientDrawable.RECTANGLE)
        strokeShape.setColor(Color.TRANSPARENT)
        strokeShape.setStroke(
            (1.2f * density).toInt().coerceAtLeast(1),
            ColorUtils.setAlphaComponent(ink, if (dark) 75 else 110)
        )
        tile.root.background = fill
        tile.tileStroke.background = strokeShape

        // soft, light glow behind the icon (kept subtle on purpose)
        val glow = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(
                ColorUtils.setAlphaComponent(ink, if (dark) 38 else 105),
                ColorUtils.setAlphaComponent(ink, 0)
            )
        )
        glow.setGradientType(GradientDrawable.RADIAL_GRADIENT)
        glow.setGradientRadius(62f * density)
        glow.setGradientCenter(0.5f, 0.5f)
        tile.tileGlow.background = glow

        // corner radius is a fixed share of the tile width, so it matches the mock on any screen
        tile.root.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, view.width * TILE_CORNER_RATIO)
            }
        }
        tile.root.clipToOutline = true
        tile.root.addOnLayoutChangeListener { v, left, _, right, _, _, _, _, _ ->
            val radius = (right - left) * TILE_CORNER_RATIO
            fill.setCornerRadius(radius)
            strokeShape.setCornerRadius(radius)
            v.invalidateOutline()
        }

        tile.tileIcon.setImageResource(style.icon)
        tile.tileIcon.imageTintList = ColorStateList.valueOf(ink)
        tile.tileTitle.setText(style.title)
        tile.tileTitle.setTextColor(if (dark) ink else Color.WHITE)
        tile.root.contentDescription = getString(style.title)
        tile.root.setOnClickListener { onClick() }
    }

    private fun openDetail(type: Int, xAxis: Boolean = false) {
        findNavController().navigate(
            R.id.detailListFragment,
            bundleOf(EXTRA_PLAYLIST_TYPE to type)
        )
        if (xAxis) setSharedAxisXTransitions() else setSharedAxisYTransitions()
    }

    // ------------------------------------------------------- recently added

    private fun setupRecentlyAdded() {
        val adapter = HomeRecentSongsAdapter(mainActivity)
        recentAdapter = adapter
        binding.recentRecyclerView.apply {
            layoutManager =
                GridLayoutManager(requireContext(), 3, GridLayoutManager.HORIZONTAL, false)
            this.adapter = adapter
        }
        binding.recentHeader.setOnClickListener { openDetail(LAST_ADDED_PLAYLIST) }
        libraryViewModel.getRecentlyAdded().observe(viewLifecycleOwner) { songs ->
            binding.recentSection.isVisible = songs.isNotEmpty()
            adapter.swapData(songs)
            restoreScroll()
        }
    }

    // --------------------------------------------------------------- pinned

    private fun setupPinned() {
        val screenBg =
            MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorSurface)
        val addTileColor =
            ColorUtils.compositeColors(ColorUtil.withAlpha(accentColor(), 0.12f), screenBg)

        val adapter = HomePinnedAdapter(
            addTileColor = addTileColor,
            addIconColor = accentColor(),
            onClick = { openPinned(it) },
            onLongClick = { confirmUnpin(it) }
        )
        pinnedAdapter = adapter
        binding.pinnedRecyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
            this.adapter = adapter
        }
        // re-draw once the albums / artists are loaded (and whenever they reload)
        libraryViewModel.getAlbums().observe(viewLifecycleOwner) { renderPinned() }
        libraryViewModel.getArtists().observe(viewLifecycleOwner) { renderPinned() }
        renderPinned()
    }

    private fun renderPinned() {
        val ctx = context ?: return
        val albums = libraryViewModel.getAlbums().value.orEmpty().associateBy { it.id }
        val artists = libraryViewModel.getArtists().value.orEmpty().associateBy { it.id }
        val items = PinnedStore.get(ctx).mapNotNull { pin ->
            when (pin.type) {
                PinnedStore.Type.ALBUM -> albums[pin.id]?.let { HomePinnedAdapter.Item.AlbumItem(it) }
                PinnedStore.Type.ARTIST -> artists[pin.id]?.let { HomePinnedAdapter.Item.ArtistItem(it) }
            }
        }
        pinnedAdapter?.swapData(items)
    }

    private fun openPinned(item: HomePinnedAdapter.Item) {
        when (item) {
            is HomePinnedAdapter.Item.AlbumItem -> findNavController().navigate(
                R.id.albumDetailsFragment,
                bundleOf(EXTRA_ALBUM_ID to item.album.id)
            )
            is HomePinnedAdapter.Item.ArtistItem -> {
                exitTransition = null
                reenterTransition = null
                findNavController().navigate(
                    R.id.artistDetailsFragment,
                    bundleOf(EXTRA_ARTIST_ID to item.artist.id),
                    navOptions
                )
            }
            HomePinnedAdapter.Item.Add -> showAddPinDialog()
        }
    }

    private fun confirmUnpin(item: HomePinnedAdapter.Item) {
        val ctx = requireContext()
        val (type, id) = when (item) {
            is HomePinnedAdapter.Item.AlbumItem -> PinnedStore.Type.ALBUM to item.album.id
            is HomePinnedAdapter.Item.ArtistItem -> PinnedStore.Type.ARTIST to item.artist.id
            HomePinnedAdapter.Item.Add -> return
        }
        MaterialAlertDialogBuilder(ctx)
            .setMessage(R.string.home_unpin_message)
            .setPositiveButton(R.string.home_unpin) { _, _ ->
                PinnedStore.remove(ctx, type, id)
                renderPinned()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showAddPinDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.home_pin_add)
            .setItems(
                arrayOf(getString(R.string.home_pin_albums), getString(R.string.home_pin_artists))
            ) { _, which -> if (which == 0) pickAlbumToPin() else pickArtistToPin() }
            .show()
    }

    private fun pickAlbumToPin() {
        val ctx = requireContext()
        val albums = libraryViewModel.getAlbums().value.orEmpty()
            .filterNot { PinnedStore.contains(ctx, PinnedStore.Type.ALBUM, it.id) }
            .sortedBy { it.title.lowercase() }
        if (albums.isEmpty()) return
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.home_pin_albums)
            .setItems(albums.map { it.title }.toTypedArray()) { _, i ->
                PinnedStore.add(ctx, PinnedStore.Type.ALBUM, albums[i].id)
                renderPinned()
            }
            .show()
    }

    private fun pickArtistToPin() {
        val ctx = requireContext()
        val artists = libraryViewModel.getArtists().value.orEmpty()
            .filterNot { PinnedStore.contains(ctx, PinnedStore.Type.ARTIST, it.id) }
            .sortedBy { it.name.lowercase() }
        if (artists.isEmpty()) return
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.home_pin_artists)
            .setItems(artists.map { it.name }.toTypedArray()) { _, i ->
                PinnedStore.add(ctx, PinnedStore.Type.ARTIST, artists[i].id)
                renderPinned()
            }
            .show()
    }

    // ------------------------------------------------- search bar (unchanged)
    private fun setupSearchBar() {
        val bar = binding.searchBar
        val scroll = binding.container
        val density = resources.displayMetrics.density
        val barMargin = (8 * density).toInt()
        val barHeight = (48 * density).toInt()
        var statusBarTop = 0

        // The pill floats over the content: put the status bar height on the pill's top margin
        // and push the content down by the same amount (plus the pill itself).
        ViewCompat.setOnApplyWindowInsetsListener(requireView()) { _, insets ->
            statusBarTop = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            bar.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = statusBarTop + barMargin
            }
            scroll.updatePadding(top = statusBarTop + barMargin * 2 + barHeight)
            insets
        }
        ViewCompat.requestApplyInsets(requireView())

        // Hide on scroll up, show again as soon as you scroll down (from anywhere).
        fun hideRange() = (statusBarTop + barMargin + barHeight).toFloat()
        val snap = Runnable {
            val target = if (bar.translationY < -hideRange() / 2f) -hideRange() else 0f
            // the (empty) update listener keeps this on the UI thread, so the glass sees the bar's real position
            bar.animate().translationY(target).setDuration(150).setUpdateListener { }.start()
        }
        scroll.setOnScrollChangeListener(
            NestedScrollView.OnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
                bar.animate().cancel()
                bar.translationY = if (scrollY <= 0) 0f
                else (bar.translationY - (scrollY - oldScrollY)).coerceIn(-hideRange(), 0f)
                bar.removeCallbacks(snap)
                bar.postDelayed(snap, 120)
            }
        )

        // Same tint family as the History / Last added buttons. Made opaque so the content
        // scrolling underneath the pill doesn't show through it.
        val screenBg = MaterialColors.getColor(bar, com.google.android.material.R.attr.colorSurface)
        searchPillColor =
            ColorUtils.compositeColors(ColorUtil.withAlpha(accentColor(), 0.12f), screenBg)
        SearchBarIcon.attach(bar, binding.menuButton, bar.findViewById(R.id.searchText))
        applySearchGlass()

        // Tap anywhere on the pill -> search (same destination the old search icon used)
        binding.searchBar.setOnClickListener {
            findNavController().navigate(R.id.action_search, null, navOptions)
        }

        // Hamburger opens the side drawer
        binding.menuButton.setOnClickListener {
            mainActivity.openDrawer()
        }
    }

    private var searchPillColor = Color.TRANSPARENT

    /**
     * The search bar is a glass pill (same as the bottom bar and the mini player) that blurs the
     * page scrolling under it; with the glass setting off it keeps its plain coloured pill.
     */
    private fun applySearchGlass() {
        val b = _binding ?: return
        val layer = GlassHelper.syncFill(b.searchBar, b.container, PreferenceUtil.glassEnabled)
        // paint the blur over the colour the page really has, so the bar is not tinted
        layer?.baseColor = pageColor(b.searchBar)
        // blur only what is under the bar (nothing from the cards just above it leaks in)
        layer?.contained = true
        b.searchBar.setCardBackgroundColor(if (layer != null) Color.TRANSPARENT else searchPillColor)
        layer?.onToneExtra = { tone -> applySearchInk(tone) }
        applySearchInk(if (layer != null && layer.sampled) layer.lightness else null)
    }

    /** The first opaque background colour above [from] (the page as the user actually sees it). */
    private fun pageColor(from: View): Int {
        var v: View? = from.parent as? View
        while (v != null) {
            val bg = v.background
            if (bg is ColorDrawable && Color.alpha(bg.color) == 255) return bg.color
            v = v.parent as? View
        }
        return MaterialColors.getColor(from, android.R.attr.colorBackground, Color.BLACK)
    }

    /** Text and icon follow the brightness of the page behind the glass (null = theme colours). */
    private fun applySearchInk(tone: Float?) {
        val b = _binding ?: return
        if (!isAdded) return
        val ink = tone?.let { ColorUtils.blendARGB(Color.WHITE, GlassLayer.DARK_INK, it) }
        b.searchBar.findViewById<TextView>(R.id.searchText)?.setTextColor(
            if (ink != null) ColorUtils.setAlphaComponent(ink, 200)
            else MaterialColors.getColor(
                b.searchBar, android.R.attr.textColorSecondary, Color.GRAY
            )
        )
        b.menuButton.imageTintList = ink?.let { ColorStateList.valueOf(it) }
        // the magnifier follows the same ink; without glass it uses the secondary text colour
        SearchBarIcon.tint(
            b.searchBar,
            ink ?: MaterialColors.getColor(b.searchBar, android.R.attr.textColorSecondary, Color.GRAY)
        )
    }

    private fun checkForMargins() {
        if (mainActivity.isBottomNavVisible) {
            binding.recyclerView.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = dip(R.dimen.bottom_nav_height)
            }
        }
    }

    // The home screen no longer has a toolbar menu: search lives in the search bar
    // and settings will live in the side drawer.
    override fun onCreateMenu(menu: Menu, inflater: MenuInflater) {
    }

    override fun onMenuItemSelected(item: MenuItem): Boolean {
        return false
    }

    override fun scrollToTop() {
        binding.container.scrollTo(0, 0)
        binding.searchBar.animate().translationY(0f).setDuration(150).setUpdateListener { }.start()
    }

    fun setSharedAxisXTransitions() {
        exitTransition =
            MaterialSharedAxis(MaterialSharedAxis.X, true).addTarget(CoordinatorLayout::class.java)
        reenterTransition = MaterialSharedAxis(MaterialSharedAxis.X, false)
    }

    private fun setSharedAxisYTransitions() {
        exitTransition =
            MaterialSharedAxis(MaterialSharedAxis.Y, true).addTarget(CoordinatorLayout::class.java)
        reenterTransition = MaterialSharedAxis(MaterialSharedAxis.Y, false)
    }

    companion object {

        const val TAG: String = "BannerHomeFragment"

        /** Minimum time between two library reloads triggered by coming back to the page. */
        private const val HOME_RELOAD_INTERVAL = 30_000L

        @JvmStatic
        fun newInstance(): HomeFragment {
            return HomeFragment()
        }
    }

    /** After coming back from another screen: scroll to where the page was, once the content is tall enough again. */
    private fun restoreScroll() {
        val target = savedScrollY
        if (target <= 0) return
        val b = _binding ?: return
        b.container.post {
            val current = _binding ?: return@post
            val content = current.container.getChildAt(0) ?: return@post
            if (content.height - current.container.height >= target) {
                current.container.scrollTo(0, target)
                current.searchBar.translationY = 0f
                savedScrollY = 0
            }
        }
    }

    override fun onResume() {
        super.onResume()
        checkForMargins()
        applySearchGlass()
        // the sections only change when something is played or added, so don't query the library
        // again every time the page comes back from another screen (that made returning slow)
        val now = SystemClock.elapsedRealtime()
        if (now - lastHomeReload > HOME_RELOAD_INTERVAL) {
            lastHomeReload = now
            libraryViewModel.forceReload(ReloadType.HomeSections)
        }
        renderPinned()
        exitTransition = null
    }

    override fun onDestroyView() {
        savedScrollY = _binding?.container?.scrollY ?: 0
        super.onDestroyView()
        recentAdapter = null
        pinnedAdapter = null
        _binding = null
    }
}
