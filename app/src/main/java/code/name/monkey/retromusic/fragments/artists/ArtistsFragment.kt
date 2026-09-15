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
package code.name.monkey.retromusic.fragments.artists

import android.os.Bundle
import android.view.*
import androidx.core.os.bundleOf
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import code.name.monkey.retromusic.EXTRA_ARTIST_ID
import code.name.monkey.retromusic.EXTRA_ARTIST_NAME
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.adapter.artist.ArtistAdapter
import code.name.monkey.retromusic.fragments.GridStyle
import code.name.monkey.retromusic.fragments.ReloadType
import code.name.monkey.retromusic.fragments.base.AbsRecyclerViewCustomGridSizeFragment
import code.name.monkey.retromusic.helper.MusicPlayerRemote
import code.name.monkey.retromusic.helper.SortOrder.ArtistSortOrder
import code.name.monkey.retromusic.interfaces.IAlbumArtistClickListener
import code.name.monkey.retromusic.interfaces.IArtistClickListener
import code.name.monkey.retromusic.interfaces.IMultiArtistClickListener
import code.name.monkey.retromusic.service.MusicService
import code.name.monkey.retromusic.util.PreferenceUtil
import code.name.monkey.retromusic.util.RetroUtil

class ArtistsFragment : AbsRecyclerViewCustomGridSizeFragment<ArtistAdapter, GridLayoutManager>(),
    IArtistClickListener, IAlbumArtistClickListener, IMultiArtistClickListener {

    // بتتحط true قبل ما نفتح صفحة تفاصيل فنان (أي مسار من التلاتة). السبب:
    // onResume() كان بينده libraryViewModel.forceReload() في كل مرة الصفحة
    // بترجع تتفعّل - حتى لو كنا راجعين من صفحة فنان فتحناها إحنا بنفسنا،
    // واللي مفيش سبب يخلي بيانات الفنانين تتغير بسببها. إعادة التحميل دي
    // كانت بتحصل في نفس لحظة أنيميشن الرجوع (نص التاني تقريبًا) فبتاكل من
    // وقت الـ Main Thread وتسبب تقطيع محسوس، وبتبان كإن القائمة "بتتمسح
    // وتترجع من الأول". لما الفلاج ده true، بنتخطى الـ reload مرة واحدة بس.
    //
    // متخزنة في companion object مش property عادية - زي نفس الباترن
    // المستخدم في AbsArtistDetailsFragment (lastVisitedArtistId وغيرها) -
    // عشان لو الـ Navigation Component بيعمل تدمير كامل للـ Fragment
    // instance (مش بس الـ view) وقت الانتقال ويعيد بناء نسخة جديدة تمامًا
    // وقت الرجوع، الفلاج يفضل باقي وميترجعش false تلقائيًا.
    private var suppressNextReload: Boolean
        get() = pendingSuppressNextReload
        set(value) { pendingSuppressNextReload = value }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // الكلاس الأب (AbsRecyclerViewFragment) بيحط enterTransition/
        // reenterTransition = MaterialFadeThrough() من غير شرط في كل مرة
        // الـ view بتتبني - وده بيخلي FragmentManager يعتبر الصفحة دي
        // "شغالة بـ Transition Framework" ويتجاهل الـ View Animation بتاعة
        // NavOptions (nav_slide_in_right/out_left/...) خالص، حتى لو صفرنا
        // exitTransition/reenterTransition بعدين في onArtist(). لازم
        // نصفرهم هنا كمان - بعد الـ super مباشرة وفي كل مرة الـ view
        // بتتبني (يعني كمان لما نرجع من صفحة تفاصيل الفنان) - عشان
        // الأنيميشن بتاعنا يشتغل صح في الاتجاهين.
        enterTransition = null
        exitTransition = null
        reenterTransition = null
        returnTransition = null

        libraryViewModel.getArtists().observe(viewLifecycleOwner) {
            if (it.isNotEmpty())
                adapter?.swapDataSet(it)
            else
                adapter?.swapDataSet(listOf())
        }
    }

    override val titleRes: Int
        get() = R.string.artists

    override val emptyMessage: Int
        get() = R.string.no_artists

    override val isShuffleVisible: Boolean
        get() = true

    override fun onShuffleClicked() {
        libraryViewModel.getArtists().value?.let {
            MusicPlayerRemote.setShuffleMode(MusicService.SHUFFLE_MODE_NONE)
            MusicPlayerRemote.openQueue(
                queue = it.shuffled().flatMap { artist -> artist.songs },
                startPosition = 0,
                startPlaying = true
            )
        }
    }

    override fun setSortOrder(sortOrder: String) {
        libraryViewModel.forceReload(ReloadType.Artists)
    }

    override fun createLayoutManager(): GridLayoutManager {
        return GridLayoutManager(requireActivity(), getGridSize())
    }

    override fun createAdapter(): ArtistAdapter {
        val dataSet = if (adapter == null) ArrayList() else adapter!!.dataSet
        return ArtistAdapter(
            requireActivity(),
            dataSet,
            itemLayoutRes(),
            this,
            this,
            this
        )
    }

    override fun loadGridSize(): Int {
        return PreferenceUtil.artistGridSize
    }

    override fun saveGridSize(gridColumns: Int) {
        PreferenceUtil.artistGridSize = gridColumns
    }

    override fun loadGridSizeLand(): Int {
        return PreferenceUtil.artistGridSizeLand
    }

    override fun saveGridSizeLand(gridColumns: Int) {
        PreferenceUtil.artistGridSizeLand = gridColumns
    }

    override fun setGridSize(gridSize: Int) {
        layoutManager?.spanCount = gridSize
        adapter?.notifyDataSetChanged()
    }

    override fun loadSortOrder(): String {
        return PreferenceUtil.artistSortOrder
    }

    override fun saveSortOrder(sortOrder: String) {
        PreferenceUtil.artistSortOrder = sortOrder
    }

    override fun loadLayoutRes(): Int {
        return PreferenceUtil.artistGridStyle.layoutResId
    }

    override fun saveLayoutRes(layoutRes: Int) {
        PreferenceUtil.artistGridStyle = GridStyle.values().first { gridStyle ->
            gridStyle.layoutResId == layoutRes
        }
    }

    companion object {
        private var pendingSuppressNextReload: Boolean = false

        fun newInstance(): ArtistsFragment {
            return ArtistsFragment()
        }
    }

    override fun onArtist(artistId: Long, view: View) {
        // لازم نلغي أي Transition-Framework transition افتراضية هنا قبل
        // الـ navigate()، وإلا هتتعارض مع الـ View Animation بتاعة NavOptions
        // وتمنعها تبان خالص - نفس الباترن المستخدم في AbsArtistDetailsFragment
        // قبل ما ينادي على artistAllSongsFragment.
        exitTransition = null
        reenterTransition = null
        suppressNextReload = true

        // نفس الـ anim resources بتاعة صفحة "See All Songs" - أنيميشن سلايد
        // بسيط بدل الـ Container Transform (shared element) القديم.
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

    override fun onAlbumArtist(artistName: String, view: View) {
        // كانت مستخدمة FragmentNavigatorExtras/shared element من غير ما يكون
        // فيه sharedElementEnterTransition متظبطة في أي حتة (لا هنا ولا في
        // AlbumArtistDetailsFragment) - يعني الانتقال ده معندوش أنيميشن
        // خالص أصلاً. بنستخدم نفس الباترن بتاع onArtist() بدل كده، لأن
        // الوجهة (AlbumArtistDetailsFragment) بترث من AbsArtistDetailsFragment
        // اللي already متظبطة بالكامل للأنيميشن ده (postponeEnterTransition
        // وغيرها).
        exitTransition = null
        reenterTransition = null
        suppressNextReload = true

        val navOptions = NavOptions.Builder()
            .setEnterAnim(R.anim.nav_slide_in_right)
            .setExitAnim(R.anim.nav_slide_out_left)
            .setPopEnterAnim(R.anim.nav_slide_in_left)
            .setPopExitAnim(R.anim.nav_slide_out_right)
            .build()

        findNavController().navigate(
            R.id.albumArtistDetailsFragment,
            bundleOf(EXTRA_ARTIST_NAME to artistName),
            navOptions
        )
    }

    override fun onMultiArtist(artistName: String, view: View) {
        // نفس السبب بالظبط بتاع onAlbumArtist() فوق.
        exitTransition = null
        reenterTransition = null
        suppressNextReload = true

        val navOptions = NavOptions.Builder()
            .setEnterAnim(R.anim.nav_slide_in_right)
            .setExitAnim(R.anim.nav_slide_out_left)
            .setPopEnterAnim(R.anim.nav_slide_in_left)
            .setPopExitAnim(R.anim.nav_slide_out_right)
            .build()

        findNavController().navigate(
            R.id.multiArtistDetailsFragment,
            bundleOf(EXTRA_ARTIST_NAME to artistName),
            navOptions
        )
    }

    override fun onCreateMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateMenu(menu, inflater)
        val gridSizeItem: MenuItem = menu.findItem(R.id.action_grid_size)
        if (RetroUtil.isLandscape) {
            gridSizeItem.setTitle(R.string.action_grid_size_land)
        }
        setUpGridSizeMenu(gridSizeItem.subMenu!!)
        val layoutItem = menu.findItem(R.id.action_layout_type)
        setupLayoutMenu(layoutItem.subMenu!!)
        setUpSortOrderMenu(menu.findItem(R.id.action_sort_order).subMenu!!)
        setupAlbumArtistMenu(menu)
        setupMultiArtistMenu(menu)
    }

    private fun setupAlbumArtistMenu(menu: Menu) {
        menu.add(0, R.id.action_album_artist, 0, R.string.show_album_artists).apply {
            isCheckable = true
            isChecked = PreferenceUtil.albumArtistsOnly
        }
    }

    private fun setupMultiArtistMenu(menu: Menu) {
        menu.add(0, R.id.action_multi_artist, 0, "Split multiple artists").apply {
            isCheckable = true
            isChecked = PreferenceUtil.multiArtistsEnabled
        }
    }

    private fun setUpSortOrderMenu(
        sortOrderMenu: SubMenu
    ) {
        val currentSortOrder: String? = getSortOrder()
        sortOrderMenu.clear()
        sortOrderMenu.add(
            0,
            R.id.action_artist_sort_order_asc,
            0,
            R.string.sort_order_a_z
        ).isChecked = currentSortOrder.equals(ArtistSortOrder.ARTIST_A_Z)
        sortOrderMenu.add(
            0,
            R.id.action_artist_sort_order_desc,
            1,
            R.string.sort_order_z_a
        ).isChecked = currentSortOrder.equals(ArtistSortOrder.ARTIST_Z_A)
        sortOrderMenu.setGroupCheckable(0, true, true)
    }

    private fun setupLayoutMenu(
        subMenu: SubMenu
    ) {
        when (itemLayoutRes()) {
            R.layout.item_card -> subMenu.findItem(R.id.action_layout_card).isChecked = true
            R.layout.item_grid -> subMenu.findItem(R.id.action_layout_normal).isChecked = true
            R.layout.item_card_color -> subMenu.findItem(R.id.action_layout_colored_card).isChecked =
                true
            R.layout.item_grid_circle -> subMenu.findItem(R.id.action_layout_circular).isChecked =
                true
            R.layout.image -> subMenu.findItem(R.id.action_layout_image).isChecked = true
            R.layout.item_image_gradient -> subMenu.findItem(R.id.action_layout_gradient_image).isChecked =
                true
        }
    }

    private fun setUpGridSizeMenu(
        gridSizeMenu: SubMenu
    ) {
        when (getGridSize()) {
            1 -> gridSizeMenu.findItem(R.id.action_grid_size_1).isChecked =
                true
            2 -> gridSizeMenu.findItem(R.id.action_grid_size_2).isChecked = true
            3 -> gridSizeMenu.findItem(R.id.action_grid_size_3).isChecked = true
            4 -> gridSizeMenu.findItem(R.id.action_grid_size_4).isChecked = true
            5 -> gridSizeMenu.findItem(R.id.action_grid_size_5).isChecked = true
            6 -> gridSizeMenu.findItem(R.id.action_grid_size_6).isChecked = true
            7 -> gridSizeMenu.findItem(R.id.action_grid_size_7).isChecked = true
            8 -> gridSizeMenu.findItem(R.id.action_grid_size_8).isChecked = true
        }
        val gridSize: Int = maxGridSize
        if (gridSize < 8) {
            gridSizeMenu.findItem(R.id.action_grid_size_8).isVisible = false
        }
        if (gridSize < 7) {
            gridSizeMenu.findItem(R.id.action_grid_size_7).isVisible = false
        }
        if (gridSize < 6) {
            gridSizeMenu.findItem(R.id.action_grid_size_6).isVisible = false
        }
        if (gridSize < 5) {
            gridSizeMenu.findItem(R.id.action_grid_size_5).isVisible = false
        }
        if (gridSize < 4) {
            gridSizeMenu.findItem(R.id.action_grid_size_4).isVisible = false
        }
        if (gridSize < 3) {
            gridSizeMenu.findItem(R.id.action_grid_size_3).isVisible = false
        }
    }

    override fun onMenuItemSelected(item: MenuItem): Boolean {
        if (handleGridSizeMenuItem(item)) {
            return true
        }
        if (handleLayoutResType(item)) {
            return true
        }
        if (handleSortOrderMenuItem(item)) {
            return true
        }
        if (handleAlbumArtistMenu(item)) {
            return true
        }
        if (handleMultiArtistMenu(item)) {
            return true
        }
        return super.onMenuItemSelected(item)
    }

    private fun handleAlbumArtistMenu(item: MenuItem): Boolean {
        return if (item.itemId == R.id.action_album_artist) {
            // شلنا الاستبعاد المتبادل اللي كان هنا (تفعيل ده كان بيقفل
            // "Split multiple artists" تلقائيًا) - الخيارين المفروض يشتغلوا
            // مع بعض: تجميع حسب الـ Album Artist، ثم تقسيم أي اسم متعدد
            // جوه التجميع ده لو الخيار التاني مفعّل.
            val newValue = !item.isChecked
            PreferenceUtil.albumArtistsOnly = newValue
            item.isChecked = newValue
            libraryViewModel.forceReload(ReloadType.Artists)
            true
        } else {
            false
        }
    }

    private fun handleMultiArtistMenu(item: MenuItem): Boolean {
        return if (item.itemId == R.id.action_multi_artist) {
            val newValue = !item.isChecked
            PreferenceUtil.multiArtistsEnabled = newValue
            item.isChecked = newValue
            libraryViewModel.forceReload(ReloadType.Artists)
            true
        } else {
            false
        }
    }

    private fun handleSortOrderMenuItem(
        item: MenuItem
    ): Boolean {
        val sortOrder: String = when (item.itemId) {
            R.id.action_artist_sort_order_asc -> ArtistSortOrder.ARTIST_A_Z
            R.id.action_artist_sort_order_desc -> ArtistSortOrder.ARTIST_Z_A
            else -> PreferenceUtil.artistSortOrder
        }
        if (sortOrder != PreferenceUtil.artistSortOrder) {
            item.isChecked = true
            setAndSaveSortOrder(sortOrder)
            return true
        }
        return false
    }

    private fun handleLayoutResType(
        item: MenuItem
    ): Boolean {
        val layoutRes = when (item.itemId) {
            R.id.action_layout_normal -> R.layout.item_grid
            R.id.action_layout_card -> R.layout.item_card
            R.id.action_layout_colored_card -> R.layout.item_card_color
            R.id.action_layout_circular -> R.layout.item_grid_circle
            R.id.action_layout_image -> R.layout.image
            R.id.action_layout_gradient_image -> R.layout.item_image_gradient
            else -> PreferenceUtil.artistGridStyle.layoutResId
        }
        if (layoutRes != PreferenceUtil.artistGridStyle.layoutResId) {
            item.isChecked = true
            setAndSaveLayoutRes(layoutRes)
            return true
        }
        return false
    }

    private fun handleGridSizeMenuItem(
        item: MenuItem
    ): Boolean {
        val gridSize = when (item.itemId) {
            R.id.action_grid_size_1 -> 1
            R.id.action_grid_size_2 -> 2
            R.id.action_grid_size_3 -> 3
            R.id.action_grid_size_4 -> 4
            R.id.action_grid_size_5 -> 5
            R.id.action_grid_size_6 -> 6
            R.id.action_grid_size_7 -> 7
            R.id.action_grid_size_8 -> 8
            else -> 0
        }
        if (gridSize > 0) {
            item.isChecked = true
            setAndSaveGridSize(gridSize)
            return true
        }
        return false
    }

    override fun onResume() {
        super.onResume()
        if (suppressNextReload) {
            suppressNextReload = false
        } else {
            libraryViewModel.forceReload(ReloadType.Artists)
        }
    }
}
