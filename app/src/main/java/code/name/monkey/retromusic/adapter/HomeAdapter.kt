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
package code.name.monkey.retromusic.adapter

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.os.bundleOf
import androidx.core.view.updatePadding
import androidx.fragment.app.findFragment
import androidx.navigation.NavOptions
import androidx.navigation.findNavController
import androidx.navigation.fragment.FragmentNavigatorExtras
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import code.name.monkey.retromusic.*
import code.name.monkey.retromusic.adapter.album.AlbumAdapter
import code.name.monkey.retromusic.adapter.artist.ArtistAdapter
import code.name.monkey.retromusic.adapter.song.SongAdapter
import code.name.monkey.retromusic.fragments.home.HomeFragment
import code.name.monkey.retromusic.interfaces.IAlbumClickListener
import code.name.monkey.retromusic.interfaces.IArtistClickListener
import code.name.monkey.retromusic.interfaces.IMultiArtistClickListener
import code.name.monkey.retromusic.model.Album
import code.name.monkey.retromusic.model.Artist
import code.name.monkey.retromusic.model.Home
import code.name.monkey.retromusic.model.Song
import code.name.monkey.retromusic.util.PreferenceUtil

class HomeAdapter(private val activity: AppCompatActivity) :
    RecyclerView.Adapter<RecyclerView.ViewHolder>(), IArtistClickListener, IAlbumClickListener,
    IMultiArtistClickListener {

    private var list = listOf<Home>()

    override fun getItemViewType(position: Int): Int {
        return list[position].homeSection
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val layout =
            LayoutInflater.from(activity).inflate(R.layout.section_recycler_view, parent, false)
        return when (viewType) {
            RECENT_ARTISTS, TOP_ARTISTS -> ArtistViewHolder(layout)
            FAVOURITES -> PlaylistViewHolder(layout)
            TOP_ALBUMS, RECENT_ALBUMS -> AlbumViewHolder(layout)
            else -> {
                ArtistViewHolder(layout)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val home = list[position]
        when (getItemViewType(position)) {
            RECENT_ALBUMS -> {
                val viewHolder = holder as AlbumViewHolder
                viewHolder.bindView(home)
                viewHolder.clickableArea.setOnClickListener {
                    it.findFragment<HomeFragment>().slideTo(
                        R.id.detailListFragment,
                        bundleOf("type" to RECENT_ALBUMS, EXTRA_SLIDE_NAV to true)
                    )
                }
            }
            TOP_ALBUMS -> {
                val viewHolder = holder as AlbumViewHolder
                viewHolder.bindView(home)
                viewHolder.clickableArea.setOnClickListener {
                    it.findFragment<HomeFragment>().slideTo(
                        R.id.detailListFragment,
                        bundleOf("type" to TOP_ALBUMS, EXTRA_SLIDE_NAV to true)
                    )
                }
            }
            RECENT_ARTISTS -> {
                val viewHolder = holder as ArtistViewHolder
                viewHolder.bindView(home)
                viewHolder.clickableArea.setOnClickListener {
                    it.findFragment<HomeFragment>().slideTo(
                        R.id.detailListFragment,
                        bundleOf("type" to RECENT_ARTISTS, EXTRA_SLIDE_NAV to true)
                    )
                }
            }
            TOP_ARTISTS -> {
                val viewHolder = holder as ArtistViewHolder
                viewHolder.bindView(home)
                viewHolder.clickableArea.setOnClickListener {
                    it.findFragment<HomeFragment>().slideTo(
                        R.id.detailListFragment,
                        bundleOf("type" to TOP_ARTISTS, EXTRA_SLIDE_NAV to true)
                    )
                }
            }
            FAVOURITES -> {
                val viewHolder = holder as PlaylistViewHolder
                viewHolder.bindView(home)
                viewHolder.clickableArea.setOnClickListener {
                    it.findFragment<HomeFragment>().slideTo(
                        R.id.detailListFragment,
                        bundleOf("type" to FAVOURITES, EXTRA_SLIDE_NAV to true)
                    )
                }
            }
        }
    }

    override fun getItemCount(): Int {
        return list.size
    }

    @SuppressLint("NotifyDataSetChanged")
    fun swapData(sections: List<Home>) {
        // same sections again (e.g. a reload when coming back to the page): nothing to redraw
        if (sections == list) return
        list = sections
        notifyDataSetChanged()
    }

    @Suppress("UNCHECKED_CAST")
    private inner class AlbumViewHolder(view: View) : AbsHomeViewItem(view) {
        fun bindView(home: Home) {
            title.setText(home.titleRes)
            recyclerView.apply {
                adapter = albumAdapter(home.arrayList as List<Album>)
                layoutManager = gridLayoutManager()
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private inner class ArtistViewHolder(view: View) : AbsHomeViewItem(view) {
        fun bindView(home: Home) {
            title.setText(home.titleRes)
            recyclerView.apply {
                layoutManager = linearLayoutManager()
                adapter = artistsAdapter(home.arrayList as List<Artist>)
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private inner class PlaylistViewHolder(view: View) : AbsHomeViewItem(view) {
        fun bindView(home: Home) {
            title.setText(home.titleRes)
            recyclerView.apply {
                val songAdapter = SongAdapter(
                    activity,
                    home.arrayList as MutableList<Song>,
                    R.layout.item_favourite_card
                )
                layoutManager = linearLayoutManager()
                adapter = songAdapter
            }
        }
    }

    open class AbsHomeViewItem(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val recyclerView: RecyclerView = itemView.findViewById(R.id.recyclerView)
        val title: AppCompatTextView = itemView.findViewById(R.id.title)
        val clickableArea: ViewGroup = itemView.findViewById(R.id.clickable_area)

        init {
            // In section_recycler_view the cards start 8dp to the left of the title. Shift them so
            // the cards, the titles and the search bar all share the same left edge.
            recyclerView.clipToPadding = false
            recyclerView.updatePadding(
                left = recyclerView.paddingLeft + (8 * itemView.resources.displayMetrics.density).toInt()
            )
        }
    }

    private fun artistsAdapter(artists: List<Artist>) =
        ArtistAdapter(activity, artists, PreferenceUtil.homeArtistGridStyle, this, null, this)

    private fun albumAdapter(albums: List<Album>) =
        AlbumAdapter(activity, albums, PreferenceUtil.homeAlbumGridStyle, this)

    private fun gridLayoutManager() =
        GridLayoutManager(activity, 1, GridLayoutManager.HORIZONTAL, false)

    private fun linearLayoutManager() =
        LinearLayoutManager(activity, LinearLayoutManager.HORIZONTAL, false)

    // HomeAdapter isn't a Fragment, so we reach the host (HomeFragment) through the view.
    // HomeFragment.slideTo() clears its transitions and opens the page with the slide NavOptions.
    override fun onArtist(artistId: Long, view: View) {
        view.findFragment<HomeFragment>()
            .slideTo(R.id.artistDetailsFragment, bundleOf(EXTRA_ARTIST_ID to artistId))
    }

    override fun onMultiArtist(artistName: String, view: View) {
        view.findFragment<HomeFragment>()
            .slideTo(R.id.multiArtistDetailsFragment, bundleOf(EXTRA_ARTIST_NAME to artistName))
    }

    override fun onAlbumClick(albumId: Long, view: View) {
        view.findFragment<HomeFragment>()
            .slideTo(R.id.albumDetailsFragment, bundleOf(EXTRA_ALBUM_ID to albumId))
    }
}
