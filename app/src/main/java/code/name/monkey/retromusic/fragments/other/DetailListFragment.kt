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
package code.name.monkey.retromusic.fragments.other

import android.graphics.Color
import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import androidx.core.os.bundleOf
import androidx.core.view.doOnPreDraw
import androidx.core.view.isVisible
import androidx.lifecycle.LiveData
import androidx.navigation.NavOptions
import androidx.navigation.fragment.FragmentNavigatorExtras
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import code.name.monkey.retromusic.*
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.adapter.album.AlbumAdapter
import code.name.monkey.retromusic.adapter.artist.ArtistAdapter
import code.name.monkey.retromusic.adapter.song.ShuffleButtonSongAdapter
import code.name.monkey.retromusic.adapter.song.SongAdapter
import code.name.monkey.retromusic.databinding.FragmentPlaylistDetailBinding
import code.name.monkey.retromusic.db.toSong
import code.name.monkey.retromusic.fragments.base.AbsMainActivityFragment
import code.name.monkey.retromusic.interfaces.IAlbumClickListener
import code.name.monkey.retromusic.interfaces.IArtistClickListener
import code.name.monkey.retromusic.model.Album
import code.name.monkey.retromusic.model.Artist
import code.name.monkey.retromusic.util.LastPageCache
import code.name.monkey.retromusic.util.pageBackgroundColor
import code.name.monkey.retromusic.util.RetroUtil
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.transition.MaterialSharedAxis


class DetailListFragment : AbsMainActivityFragment(R.layout.fragment_playlist_detail),
    IArtistClickListener, IAlbumClickListener {
    private val args by navArgs<DetailListFragmentArgs>()
    private var _binding: FragmentPlaylistDetailBinding? = null
    private val binding get() = _binding!!
    private var showClearHistoryOption = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (arguments?.getBoolean(EXTRA_SLIDE_NAV, false) == true) {
            // opened from the home page with the slide animation (NavOptions): a transition here
            // would make the framework skip that animation, so this page adds none of its own
            enterTransition = null
            returnTransition = null
            return
        }
        when (args.type) {
            TOP_ARTISTS,
            RECENT_ARTISTS,
            TOP_ALBUMS,
            RECENT_ALBUMS,
            FAVOURITES,
            -> {
                enterTransition = MaterialSharedAxis(MaterialSharedAxis.X, true)
                returnTransition = MaterialSharedAxis(MaterialSharedAxis.X, false)
            }
            else -> {
                enterTransition = MaterialSharedAxis(MaterialSharedAxis.Y, true)
                returnTransition = MaterialSharedAxis(MaterialSharedAxis.Y, false)
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // The page stays postponed until the FIRST SCREEN of the list is loaded and laid out, then
        // the enter animation starts (see startEnter). The rest of the list is added only after
        // the enter animation has finished.
        val slide = arguments?.getBoolean(EXTRA_SLIDE_NAV, false) == true
        enterSettled = false
        enterStarted = false
        settledCallbacks.clear()
        _binding = FragmentPlaylistDetailBinding.bind(view)
        if (slide) {
            // this page slides in over the home page: it gets the page colour the rest of the app
            // draws on, so the home page can't show through it
            if (view.background == null) view.setBackgroundColor(pageBackgroundColor())
        }
        postponeEnterTransition()
        // safety net: never keep the page waiting forever if the data is slow or never arrives
        view.postDelayed({ startEnter() }, ENTER_START_FALLBACK_MS)
        mainActivity.setSupportActionBar(binding.toolbar)
        binding.progressIndicator.hide()
        when (args.type) {
            TOP_ARTISTS -> loadArtists(R.string.top_artists, TOP_ARTISTS)
            RECENT_ARTISTS -> loadArtists(R.string.recent_artists, RECENT_ARTISTS)
            TOP_ALBUMS -> loadAlbums(R.string.top_albums, TOP_ALBUMS)
            RECENT_ALBUMS -> loadAlbums(R.string.recent_albums, RECENT_ALBUMS)
            FAVOURITES -> loadFavorite()
            HISTORY_PLAYLIST -> {
                loadHistory()
                showClearHistoryOption = true // Reference to onCreateOptionsMenu
            }
            LAST_ADDED_PLAYLIST -> lastAddedSongs()
            TOP_PLAYED_PLAYLIST -> topPlayed()
            FOR_YOU_NOW_PLAYLIST -> forYouNow()
            REDISCOVER_PLAYLIST -> rediscover()
        }

        binding.appBarLayout.statusBarForeground =
            MaterialShapeDrawable.createWithElevationOverlay(requireContext())
    }

    private fun lastAddedSongs() {
        binding.toolbar.setTitle(R.string.last_added)
        val songAdapter = ShuffleButtonSongAdapter(
            requireActivity(),
            mutableListOf(),
            R.layout.item_list
        )
        binding.recyclerView.apply {
            adapter = songAdapter
            layoutManager = linearLayoutManager()
        }
        libraryViewModel.latestAddedSongs().observeWhenSettled { songs ->
            songAdapter.swapDataSet(songs)
        }
    }

    private fun topPlayed() {
        binding.toolbar.setTitle(R.string.my_top_tracks)
        val songAdapter = ShuffleButtonSongAdapter(
            requireActivity(),
            mutableListOf(),
            R.layout.item_list
        )
        binding.recyclerView.apply {
            adapter = songAdapter
            layoutManager = linearLayoutManager()
        }
        libraryViewModel.playCountSongs().observeWhenSettled { songs ->
            songAdapter.swapDataSet(songs)
        }
    }

    private fun forYouNow() {
        binding.toolbar.setTitle(R.string.tile_for_you_now)
        val songAdapter = ShuffleButtonSongAdapter(
            requireActivity(),
            mutableListOf(),
            R.layout.item_list
        )
        binding.recyclerView.apply {
            adapter = songAdapter
            layoutManager = linearLayoutManager()
        }
        // computed once per visit: re-opening the same page keeps its list (no reshuffle)
        libraryViewModel.forYouNowSongs().observeWhenSettled(refresh = false) { songs ->
            songAdapter.swapDataSet(songs)
            binding.empty.isVisible = songs.isEmpty()
        }
    }

    private fun rediscover() {
        binding.toolbar.setTitle(R.string.tile_rediscover)
        val songAdapter = ShuffleButtonSongAdapter(
            requireActivity(),
            mutableListOf(),
            R.layout.item_list
        )
        binding.recyclerView.apply {
            adapter = songAdapter
            layoutManager = linearLayoutManager()
        }
        libraryViewModel.rediscoverSongs().observeWhenSettled(refresh = false) { songs ->
            songAdapter.swapDataSet(songs)
            binding.empty.isVisible = songs.isEmpty()
        }
    }

    private fun loadHistory() {
        binding.toolbar.setTitle(R.string.history)

        val songAdapter = ShuffleButtonSongAdapter(
            requireActivity(),
            mutableListOf(),
            R.layout.item_list
        )
        binding.recyclerView.apply {
            adapter = songAdapter
            layoutManager = linearLayoutManager()
        }

        libraryViewModel.observableHistorySongs().observeWhenSettled {
            songAdapter.swapDataSet(it)
            binding.empty.isVisible = it.isEmpty()
        }

    }

    private fun loadFavorite() {
        binding.toolbar.setTitle(R.string.favorites)
        val songAdapter = SongAdapter(
            requireActivity(),
            mutableListOf(),
            R.layout.item_list
        )
        binding.recyclerView.apply {
            adapter = songAdapter
            layoutManager = linearLayoutManager()
        }
        libraryViewModel.favorites().observeWhenSettled { songEntities ->
            val songs = songEntities.map { songEntity -> songEntity.toSong() }
            songAdapter.swapDataSet(songs)
        }
    }


    private fun loadArtists(title: Int, type: Int) {
        binding.toolbar.setTitle(title)
        val artistAdapter = artistAdapter(listOf())
        binding.recyclerView.apply {
            adapter = artistAdapter
            layoutManager = gridLayoutManager()
        }
        libraryViewModel.artists(type).observeWhenSettled { artists ->
            artistAdapter.swapDataSet(artists)
        }
    }

    private fun loadAlbums(title: Int, type: Int) {
        binding.toolbar.setTitle(title)
        val albumAdapter = albumAdapter(listOf())
        binding.recyclerView.apply {
            adapter = albumAdapter
            layoutManager = gridLayoutManager()
        }
        libraryViewModel.albums(type).observeWhenSettled { albums ->
            albumAdapter.swapDataSet(albums)
        }
    }

    private fun artistAdapter(artists: List<Artist>): ArtistAdapter = ArtistAdapter(
        requireActivity(),
        artists,
        R.layout.item_grid_circle,
        this
    )

    private fun albumAdapter(albums: List<Album>): AlbumAdapter = AlbumAdapter(
        requireActivity(),
        albums,
        R.layout.item_grid,
        this
    )

    private fun linearLayoutManager(): LinearLayoutManager =
        LinearLayoutManager(requireContext(), LinearLayoutManager.VERTICAL, false)

    private fun gridLayoutManager(): GridLayoutManager =
        GridLayoutManager(requireContext(), gridCount(), GridLayoutManager.VERTICAL, false)

    private fun gridCount(): Int {
        if (RetroUtil.isTablet) {
            return if (RetroUtil.isLandscape) 6 else 4
        }
        return if (RetroUtil.isLandscape) 4 else 2
    }

    // estimates how many items fill one screen on this device (phone / tablet / landscape)
    private fun firstScreenCount(): Int {
        val dm = resources.displayMetrics
        val lm = _binding?.recyclerView?.layoutManager
        return if (lm is GridLayoutManager) {
            val span = lm.spanCount
            val cellHeight = dm.widthPixels / span + 56 * dm.density
            (kotlin.math.ceil(dm.heightPixels / cellHeight).toInt() + 1) * span
        } else {
            kotlin.math.ceil(dm.heightPixels / (56 * dm.density)).toInt() + 2
        }
    }


    override fun onArtist(artistId: Long, view: View) {
        exitTransition = null
        reenterTransition = null

        val navOptions = NavOptions.Builder()
            .setEnterAnim(R.anim.nav_slide_in_right)
            .setExitAnim(R.anim.nav_slide_out_left)
            .setPopEnterAnim(R.anim.nav_slide_in_left)
            .setPopExitAnim(R.anim.nav_slide_out_right)
            .build()

        findNavController().navigate(
            R.id.artistDetailsFragment,
            bundleOf(EXTRA_ARTIST_ID to artistId),
            navOptions
        )
    }

    override fun onAlbumClick(albumId: Long, view: View) {
        findNavController().navigate(
            R.id.albumDetailsFragment,
            bundleOf(EXTRA_ALBUM_ID to albumId),
            null,
            FragmentNavigatorExtras(
                view to albumId.toString()
            )
        )
    }

    // ---- enter animation bookkeeping (same pattern as the artist page) --------------------------

    // true once the enter animation has finished (the rest of the list is added only then)
    private var enterSettled = false
    // true once the enter animation was allowed to start (first screen of the list is ready)
    private var enterStarted = false
    private val settledCallbacks = mutableListOf<() -> Unit>()

    // starts the postponed enter animation; safe to call more than once
    private fun startEnter() {
        if (enterStarted || _binding == null) return
        enterStarted = true
        startPostponedEnterTransition()
        // safety net in case the animation never reports that it ended
        view?.postDelayed({ markEnterSettled() }, ENTER_SETTLE_FALLBACK_MS)
    }

    private fun runWhenEnterSettled(block: () -> Unit) {
        if (_binding == null) return
        if (enterSettled) block() else settledCallbacks.add(block)
    }

    // runs the waiting work, one step per frame so no single frame is heavy
    private fun markEnterSettled() {
        if (enterSettled || _binding == null) return
        enterSettled = true
        val pending = settledCallbacks.toList()
        settledCallbacks.clear()
        fun runNext(index: Int) {
            val root = view ?: return
            if (_binding == null || index >= pending.size) return
            pending[index]()
            root.postOnAnimation { runNext(index + 1) }
        }
        view?.postOnAnimation { runNext(0) }
    }

    /**
     * Observes like [observe], but
     *  - if this is the page that was opened last time, its previous data is shown at once (the
     *    page does not load from scratch again); with [refresh] = false that data is kept as is;
     *  - the enter animation starts only once the first screen of the list is in place; only that
     *    first screen is loaded before the animation, the rest comes after it has finished.
     */
    private fun <E> LiveData<List<E>>.observeWhenSettled(
        refresh: Boolean = true,
        block: (List<E>) -> Unit
    ) {
        val key = "list:" + args.type
        val cached: List<E>? = LastPageCache.get(key)
        var shown: List<E>? = null

        fun show(list: List<E>) {
            if (_binding == null) return
            // page already open and settled (e.g. a later refresh): just apply the data
            if (enterSettled) {
                block(list)
                startEnter()
                return
            }
            // 1) only what fits on the first screen goes in now
            val n = firstScreenCount()
            block(if (list.size <= n) list else list.take(n))
            // 2) the enter animation starts once that first screen has been laid out
            _binding?.recyclerView?.doOnPreDraw { startEnter() }
            // 3) the rest of the list is added after the enter animation has finished
            if (list.size > n) runWhenEnterSettled { block(list) }
        }

        if (cached != null) {
            shown = cached
            show(cached)
            if (!refresh) return
        }
        observe(viewLifecycleOwner) { value ->
            LastPageCache.put(key, value)
            if (value != shown) {
                shown = value
                show(value)
            }
        }
    }

    // we hold the end of the enter animation (the one from NavOptions) without changing it
    override fun onCreateAnimation(transit: Int, enter: Boolean, nextAnim: Int): Animation? {
        val anim = super.onCreateAnimation(transit, enter, nextAnim)
            ?: if (enter && nextAnim != 0 &&
                runCatching { resources.getResourceTypeName(nextAnim) == "anim" }.getOrDefault(false)
            ) {
                runCatching { AnimationUtils.loadAnimation(requireContext(), nextAnim) }.getOrNull()
            } else null
        if (enter && anim != null && !enterSettled) {
            anim.setAnimationListener(object : Animation.AnimationListener {
                override fun onAnimationStart(animation: Animation?) {}
                override fun onAnimationRepeat(animation: Animation?) {}
                override fun onAnimationEnd(animation: Animation?) {
                    markEnterSettled()
                }
            })
        }
        return anim
    }

    override fun onDestroyView() {
        super.onDestroyView()
        settledCallbacks.clear()
        _binding = null
    }

    override fun onCreateMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_clear_history, menu)
        if (showClearHistoryOption) {
            menu.findItem(R.id.action_clear_history).isVisible = true // Show Clear History option
        }
    }

    override fun onMenuItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_clear_history -> {
                if (binding.recyclerView.adapter?.itemCount!! > 0) {
                    libraryViewModel.clearHistory()

                    val snackBar =
                        Snackbar.make(
                            binding.container,
                            getString(R.string.history_cleared),
                            Snackbar.LENGTH_LONG
                        )
                            .setAction(getString(R.string.history_undo_button)) {
                                libraryViewModel.restoreHistory()
                            }
                            .setActionTextColor(Color.YELLOW)
                    val snackBarView = snackBar.view
                    snackBarView.translationY =
                        -(resources.getDimension(R.dimen.mini_player_height))
                    snackBar.show()
                }
            }
        }
        return false
    }
}

private const val ENTER_SETTLE_FALLBACK_MS = 700L
private const val ENTER_START_FALLBACK_MS = 800L
