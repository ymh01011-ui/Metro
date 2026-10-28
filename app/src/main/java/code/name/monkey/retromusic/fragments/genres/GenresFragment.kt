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
package code.name.monkey.retromusic.fragments.genres

import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import androidx.core.os.bundleOf
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import code.name.monkey.retromusic.EXTRA_GENRE
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.adapter.GenreAdapter
import code.name.monkey.retromusic.fragments.ReloadType
import code.name.monkey.retromusic.fragments.base.AbsRecyclerViewFragment
import code.name.monkey.retromusic.interfaces.IGenreClickListener
import code.name.monkey.retromusic.model.Genre
import code.name.monkey.retromusic.util.RetroUtil

class
GenresFragment : AbsRecyclerViewFragment<GenreAdapter, LinearLayoutManager>(),
    IGenreClickListener {

    // true من ساعة ما بنفتح تفاصيل genre لحد ما نرجع لهنا: بنفرّق بيه بين "رجوع من التفاصيل"
    // (لازم انيميشن الرجوع) وبين فتح الصفحة عادي (زي صفحة الـ Playlists بالظبط).
    private var openedGenreDetails = false

    // الرجوع من التفاصيل مبيعملش reload للأنواع (كان بيبني القايمة من الأول كل مرة)
    private var returningFromDetails = false

    override fun onCreateAnimation(transit: Int, enter: Boolean, nextAnim: Int): Animation? {
        if (openedGenreDetails) {
            return if (enter) {
                // رجعنا من التفاصيل
                openedGenreDetails = false
                AnimationUtils.loadAnimation(requireContext(), R.anim.nav_slide_in_left)
            } else {
                // بنفتح التفاصيل: الصفحة دي بتطلع لليسار
                AnimationUtils.loadAnimation(requireContext(), R.anim.nav_slide_out_left)
            }
        }
        return super.onCreateAnimation(transit, enter, nextAnim)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // الـ base fragment بيحط transition بعد ما الـ view يتبني، وده بيتنفذ بدل أنيميشن الرجوع
        if (openedGenreDetails) {
            reenterTransition = null
        }
        val pad = (8 * resources.displayMetrics.density).toInt()
        recyclerView.setPadding(pad, recyclerView.paddingTop, pad, recyclerView.paddingBottom)
        libraryViewModel.getGenre().observe(viewLifecycleOwner) {
            if (it.isNotEmpty())
                adapter?.swapDataSet(it)
            else
                adapter?.swapDataSet(listOf())
        }
    }

    override fun createLayoutManager(): LinearLayoutManager {
        return if (RetroUtil.isLandscape) {
            GridLayoutManager(activity, 4)
        } else {
            GridLayoutManager(activity, 2)
        }
    }

    override fun createAdapter(): GenreAdapter {
        val dataSet = if (adapter == null) ArrayList() else adapter!!.dataSet
        return GenreAdapter(requireActivity(), dataSet, this)
    }

    override fun onCreateMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateMenu(menu, inflater)
        menu.removeItem(R.id.action_grid_size)
        menu.removeItem(R.id.action_layout_type)
        menu.removeItem(R.id.action_sort_order)
        menu.findItem(R.id.action_settings).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
    }

    override fun onResume() {
        super.onResume()
        if (returningFromDetails) {
            returningFromDetails = false
        } else {
            libraryViewModel.forceReload(ReloadType.Genres)
        }
    }



    override val titleRes: Int
        get() = R.string.genres

    override val emptyMessage: Int
        get() = R.string.no_genres

    override val isShuffleVisible: Boolean
        get() = false

    companion object {
        @JvmField
        val TAG: String = GenresFragment::class.java.simpleName

        @JvmStatic
        fun newInstance(): GenresFragment {
            return GenresFragment()
        }
    }

    override fun onClickGenre(genre: Genre, view: View) {
        // نفس أنيميشن صفحة الـ Playlist (nav_slide_*)
        val navOptions = NavOptions.Builder()
            .setEnterAnim(R.anim.nav_slide_in_right)
            .setExitAnim(R.anim.nav_slide_out_left)
            .setPopEnterAnim(R.anim.nav_slide_in_left)
            .setPopExitAnim(R.anim.nav_slide_out_right)
            .build()
        openedGenreDetails = true
        returningFromDetails = true
        findNavController().navigate(
            R.id.genreDetailsFragment,
            bundleOf(EXTRA_GENRE to genre),
            navOptions
        )
    }
}
