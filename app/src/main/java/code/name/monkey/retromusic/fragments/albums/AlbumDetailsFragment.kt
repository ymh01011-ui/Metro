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
package code.name.monkey.retromusic.fragments.albums

import android.app.ActivityOptions
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.*
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import androidx.core.os.bundleOf
import androidx.core.view.doOnPreDraw
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.FragmentNavigatorExtras
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import code.name.monkey.appthemehelper.common.ATHToolbarActivity.getToolbarBackgroundColor
import code.name.monkey.appthemehelper.util.ToolbarContentTintHelper
import code.name.monkey.retromusic.EXTRA_ALBUM_ID
import code.name.monkey.retromusic.EXTRA_ARTIST_ID
import code.name.monkey.retromusic.EXTRA_ARTIST_NAME
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.activities.tageditor.AbsTagEditorActivity
import code.name.monkey.retromusic.activities.tageditor.AlbumTagEditorActivity
import code.name.monkey.retromusic.adapter.ArtistPickerAdapter
import code.name.monkey.retromusic.adapter.album.HorizontalAlbumAdapter
import code.name.monkey.retromusic.adapter.song.SimpleSongAdapter
import code.name.monkey.retromusic.databinding.FragmentAlbumDetailsBinding
import code.name.monkey.retromusic.dialogs.AddToPlaylistDialog
import code.name.monkey.retromusic.dialogs.DeleteSongsDialog
import code.name.monkey.retromusic.extensions.*
import code.name.monkey.retromusic.fragments.base.AbsMainActivityFragment
import code.name.monkey.retromusic.glide.RetroGlideExtension
import code.name.monkey.retromusic.glide.RetroGlideExtension.albumCoverOptions
import code.name.monkey.retromusic.glide.RetroGlideExtension.asBitmapPalette
import code.name.monkey.retromusic.glide.SingleColorTarget
import code.name.monkey.retromusic.helper.MusicPlayerRemote
import code.name.monkey.retromusic.helper.SortOrder.AlbumSongSortOrder.Companion.SONG_A_Z
import code.name.monkey.retromusic.helper.SortOrder.AlbumSongSortOrder.Companion.SONG_DURATION
import code.name.monkey.retromusic.helper.SortOrder.AlbumSongSortOrder.Companion.SONG_TRACK_LIST
import code.name.monkey.retromusic.helper.SortOrder.AlbumSongSortOrder.Companion.SONG_Z_A
import code.name.monkey.retromusic.interfaces.IAlbumClickListener
import code.name.monkey.retromusic.model.Album
import code.name.monkey.retromusic.model.Artist
import code.name.monkey.retromusic.repository.RealRepository
import code.name.monkey.retromusic.util.*
import com.bumptech.glide.Glide
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.transition.MaterialArcMotion
import com.google.android.material.transition.MaterialContainerTransform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.get
import org.koin.androidx.viewmodel.ext.android.viewModel
import org.koin.core.parameter.parametersOf
import java.text.Collator

class AlbumDetailsFragment : AbsMainActivityFragment(R.layout.fragment_album_details),
    IAlbumClickListener {

    private var _binding: FragmentAlbumDetailsBinding? = null
    private val binding get() = _binding!!

    private val arguments by navArgs<AlbumDetailsFragmentArgs>()
    private val detailsViewModel by viewModel<AlbumDetailsViewModel> {
        parametersOf(arguments.extraAlbumId)
    }

    private lateinit var simpleSongAdapter: SimpleSongAdapter
    private lateinit var album: Album
    private var albumArtistExists = false

    private val savedSortOrder: String
        get() = PreferenceUtil.albumDetailSongSortOrder

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sharedElementEnterTransition = MaterialContainerTransform().apply {
            drawingViewId = R.id.fragment_container
            scrimColor = Color.TRANSPARENT
            setAllContainerColors(surfaceColor())
            setPathMotion(MaterialArcMotion())
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentAlbumDetailsBinding.bind(view)
        mainActivity.addMusicServiceEventListener(detailsViewModel)
        mainActivity.setSupportActionBar(binding.toolbar)

        binding.toolbar.title = " "
        binding.albumCoverContainer.transitionName = arguments.extraAlbumId.toString()
        postponeEnterTransition()

        // لو الألبوم ده في الكاش، لونه جاهز - بنطبقه فورًا قبل ما الصورة نفسها
        // تتحمل، عشان الأزرار والخلفية ما تبانش بلونها الافتراضي لحظة وبعدين
        // تتغير.
        AlbumDetailsCache.getColor(arguments.extraAlbumId)?.let { cachedColor ->
            setColors(cachedColor)
        }

        detailsViewModel.getAlbum().observe(viewLifecycleOwner) { album ->
            view.doOnPreDraw {
                startPostponedEnterTransition()
            }
            albumArtistExists = !album.albumArtist.isNullOrEmpty()
            showAlbum(album)
        }

        setupRecyclerView()
        binding.albumText.setOnClickListener {
            // If this album's artist tag is actually a combined tag (e.g.
            // "Alan Walker, Au/Ra, Tomine Harket") and multi-artist mode is
            // on, let the user pick which one to open - same behavior as the
            // Now Playing screen - instead of always opening the raw combined
            // entry or guessing a single one.
            if (PreferenceUtil.multiArtistsEnabled) {
                val nameToSplit = if (albumArtistExists) album.albumArtist!! else album.artistName
                val splitNames = ArtistTagUtil.splitArtistNames(nameToSplit)
                when {
                    splitNames.size > 1 -> {
                        lifecycleScope.launch(Dispatchers.IO) {
                            val repository = get<RealRepository>()
                            val artists = splitNames.map { repository.multiArtistByName(it) }
                            withContext(Dispatchers.Main) {
                                showArtistPickerDialog(artists)
                            }
                        }
                    }

                    splitNames.size == 1 -> goToMultiArtistFromAlbum(splitNames[0])
                    else -> goToMultiArtistFromAlbum(album.artistName)
                }
                return@setOnClickListener
            }

            if (albumArtistExists) {
                findActivityNavController(R.id.fragment_container)
                    .navigate(
                        R.id.albumArtistDetailsFragment,
                        bundleOf(EXTRA_ARTIST_NAME to album.albumArtist)
                    )
            } else {
                findActivityNavController(R.id.fragment_container)
                    .navigate(
                        R.id.artistDetailsFragment,
                        bundleOf(EXTRA_ARTIST_ID to album.artistId)
                    )
            }
        }
        binding.fragmentAlbumContent.playAction.setOnClickListener {
            MusicPlayerRemote.openQueue(album.songs, 0, true)
        }
        binding.fragmentAlbumContent.shuffleAction.setOnClickListener {
            MusicPlayerRemote.openAndShuffleQueue(
                album.songs,
                true
            )
        }
        binding.fragmentAlbumContent.addAction.setOnClickListener {
            addAlbumSongsToPlaylist()
        }

        binding.appBarLayout?.statusBarForeground =
            MaterialShapeDrawable.createWithElevationOverlay(requireContext())
    }

    private fun showArtistPickerDialog(artists: List<Artist>) {
        val adapter = ArtistPickerAdapter(requireActivity() as AppCompatActivity, artists)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.action_go_to_artist)
            .setAdapter(adapter) { _, which ->
                findActivityNavController(R.id.fragment_container).navigate(
                    R.id.multiArtistDetailsFragment,
                    bundleOf(EXTRA_ARTIST_NAME to artists[which].name)
                )
            }
            .show()
    }

    private fun goToMultiArtistFromAlbum(artistName: String) {
        findActivityNavController(R.id.fragment_container)
            .navigate(
                R.id.multiArtistDetailsFragment,
                bundleOf(EXTRA_ARTIST_NAME to artistName)
            )
    }

    private fun addAlbumSongsToPlaylist() {
        lifecycleScope.launch(Dispatchers.IO) {
            val playlists = get<RealRepository>().fetchPlaylists()
            withContext(Dispatchers.Main) {
                AddToPlaylistDialog.create(playlists, album.songs)
                    .show(childFragmentManager, "ADD_PLAYLIST")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceActivity?.removeMusicServiceEventListener(detailsViewModel)
    }

    private fun setupRecyclerView() {
        simpleSongAdapter = SimpleSongAdapter(
            requireActivity() as AppCompatActivity,
            ArrayList(),
            // ليها layout خاص بيها (item_song_album_details) بدل item_song
            // العام: رقم المسار بدل صورة الأغنية زي Apple Music، وده كمان
            // بيلغي تحميل Glide لكل صف فالقايمة أخف بكتير وقت أنيميشن الفتح.
            R.layout.item_song_album_details
        )
        binding.fragmentAlbumContent.recyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            itemAnimator = DefaultItemAnimator()
            isNestedScrollingEnabled = false
            adapter = simpleSongAdapter
        }
    }

    private fun showAlbum(album: Album) {
        if (album.songs.isEmpty()) {
            findNavController().navigateUp()
            return
        }
        this.album = album

        binding.albumTitle.text = album.title
        // albumText بيتحول لنوع View عام في الـ ViewBinding مش TextView، على
        // الأغلب لأن فيه نسخة تانية من fragment_album_details.xml (land أو
        // sw600dp) بتعرّف نفس الـ id بنوع مختلف أو من غيره خالص. الـ cast
        // الآمن ده بيتخطى المشكلة من غير ما نلمس الملف اللي مش موجود عندي.
        (binding.albumText as? TextView)?.text =
            if (albumArtistExists) album.albumArtist else album.artistName

        val songText = resources.getQuantityString(
            R.plurals.albumSongs,
            album.songCount,
            album.songCount
        )
        binding.fragmentAlbumContent.songTitle.text = songText
        if (MusicUtil.getYearString(album.year) == "-") {
            binding.fragmentAlbumContent.albumFooterText.text = String.format(
                "%s • %s",
                if (albumArtistExists) album.albumArtist else album.artistName,
                MusicUtil.getReadableDurationString(MusicUtil.getTotalDuration(album.songs))
            )
        } else {
            binding.fragmentAlbumContent.albumFooterText.text = String.format(
                "%s • %s • %s",
                if (albumArtistExists) album.albumArtist else album.artistName,
                MusicUtil.getYearString(album.year),
                MusicUtil.getReadableDurationString(MusicUtil.getTotalDuration(album.songs))
            )
        }
        loadAlbumCover(album)
        simpleSongAdapter.swapDataSet(album.songs)
        if (albumArtistExists) {
            detailsViewModel.getAlbumArtist(album.albumArtist.toString())
                .observe(viewLifecycleOwner) {
                    loadMoreAlbums(it)
                }
        } else {
            detailsViewModel.getArtist(album.artistId).observe(viewLifecycleOwner) {
                loadMoreAlbums(it)
            }
        }
    }

    private fun moreAlbums(albums: List<Album>) {
        binding.fragmentAlbumContent.moreTitle.show()
        binding.fragmentAlbumContent.moreRecyclerView.show()
        binding.fragmentAlbumContent.moreTitle.text =
            String.format(getString(R.string.label_more_from), album.artistName)

        val albumAdapter =
            HorizontalAlbumAdapter(requireActivity() as AppCompatActivity, albums, this)
        binding.fragmentAlbumContent.moreRecyclerView.layoutManager = GridLayoutManager(
            requireContext(),
            1,
            GridLayoutManager.HORIZONTAL,
            false
        )
        binding.fragmentAlbumContent.moreRecyclerView.adapter = albumAdapter
    }

    // كانت الدالة دي بتحمّل صورة الفنان الدائرية اللي كانت جنب العنوان، لكن
    // التصميم الجديد (زي Apple Music) مفيهوش الصورة دي، فبقت مسؤوليتها
    // الوحيدة إنها تجيب "More by Artist" - الاسم اتغير عشان يوضح كده.
    private fun loadMoreAlbums(artist: Artist) {
        detailsViewModel.getMoreAlbums(artist).observe(viewLifecycleOwner) {
            moreAlbums(it)
        }
    }

    private fun loadAlbumCover(album: Album) {
        // كان Glide بيفك صورة الغلاف بحجمها الأصلي. بنحدد سقف للحجم (1.5× عرض
        // الشاشة، بحد أقصى 2048) من غير قص ولا تغيير في النسبة، فالفك أسرع
        // بكتير من غير ما شكل الغلاف ولا اللون المستخرج منه يتغيروا.
        val maxImageSide = minOf((resources.displayMetrics.widthPixels * 3) / 2, 2048)
        val albumId = album.id
        Glide.with(requireContext())
            .asBitmapPalette()
            .albumCoverOptions(album.safeGetFirstSong())
            //.checkIgnoreMediaStore()
            .load(RetroGlideExtension.getSongModel(album.safeGetFirstSong()))
            .override(maxImageSide)
            .into(object : SingleColorTarget(binding.image) {
                override fun onColorReady(color: Int) {
                    setColors(color)
                    AlbumDetailsCache.putColor(albumId, color)
                }
            })
    }

    private fun setColors(color: Int) {
        _binding?.apply {
            // خلفية الصفحة: تدرّج من لون الغلاف (شفافية جزئية) لحد لون
            // الخلفية العادي، زي خلفية صفحة الألبوم في Apple Music.
            val gradient = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(ColorUtils.setAlphaComponent(color, 130), surfaceColor())
            )
            // contentRoot نفس القصة: nullable لأنه على الأغلب مش معرّف في نسخة
            // تانية من الـ layout.
            contentRoot?.background = gradient

            val onColor = if (ColorUtils.calculateLuminance(color) > 0.5) {
                Color.BLACK
            } else {
                Color.WHITE
            }
            fragmentAlbumContent.playAction.apply {
                backgroundTintList = ColorStateList.valueOf(color)
                setTextColor(onColor)
                iconTint = ColorStateList.valueOf(onColor)
            }
            fragmentAlbumContent.shuffleAction.imageTintList = ColorStateList.valueOf(color)
            fragmentAlbumContent.addAction.imageTintList = ColorStateList.valueOf(color)
            (albumText as? TextView)?.setTextColor(color)
        }
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

    override fun onCreateMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_album_detail, menu)
        val sortOrder = menu.findItem(R.id.action_sort_order)
        setUpSortOrderMenu(sortOrder.subMenu!!)
        ToolbarContentTintHelper.handleOnCreateOptionsMenu(
            requireContext(),
            binding.toolbar,
            menu,
            getToolbarBackgroundColor(binding.toolbar)
        )
    }

    override fun onMenuItemSelected(item: MenuItem): Boolean {
        return handleSortOrderMenuItem(item)
    }

    private fun handleSortOrderMenuItem(item: MenuItem): Boolean {
        var sortOrder: String? = null
        val songs = simpleSongAdapter.dataSet
        when (item.itemId) {
            android.R.id.home -> findNavController().navigateUp()
            R.id.action_play_next -> {
                MusicPlayerRemote.playNext(songs)
                return true
            }

            R.id.action_add_to_current_playing -> {
                MusicPlayerRemote.enqueue(songs)
                return true
            }

            R.id.action_add_to_playlist -> {
                addAlbumSongsToPlaylist()
                return true
            }

            R.id.action_delete_from_device -> {
                DeleteSongsDialog.create(songs).show(childFragmentManager, "DELETE_SONGS")
                return true
            }

            R.id.action_tag_editor -> {
                val intent = Intent(requireContext(), AlbumTagEditorActivity::class.java)
                intent.putExtra(AbsTagEditorActivity.EXTRA_ID, album.id)
                val options = ActivityOptions.makeSceneTransitionAnimation(
                    requireActivity(),
                    binding.albumCoverContainer,
                    "${getString(R.string.transition_album_art)}_${album.id}"
                )
                startActivity(
                    intent, options.toBundle()
                )
                return true
            }

            R.id.action_sort_order_title -> sortOrder = SONG_A_Z
            R.id.action_sort_order_title_desc -> sortOrder = SONG_Z_A
            R.id.action_sort_order_track_list -> sortOrder = SONG_TRACK_LIST
            R.id.action_sort_order_artist_song_duration -> sortOrder = SONG_DURATION
        }
        if (sortOrder != null) {
            item.isChecked = true
            setSaveSortOrder(sortOrder)
        }
        return true
    }

    private fun setUpSortOrderMenu(sortOrder: SubMenu) {
        when (savedSortOrder) {
            SONG_A_Z -> sortOrder.findItem(R.id.action_sort_order_title).isChecked = true
            SONG_Z_A -> sortOrder.findItem(R.id.action_sort_order_title_desc).isChecked = true
            SONG_TRACK_LIST ->
                sortOrder.findItem(R.id.action_sort_order_track_list).isChecked = true

            SONG_DURATION ->
                sortOrder.findItem(R.id.action_sort_order_artist_song_duration).isChecked = true
        }
    }

    private fun setSaveSortOrder(sortOrder: String) {
        PreferenceUtil.albumDetailSongSortOrder = sortOrder
        val songs = when (sortOrder) {
            SONG_TRACK_LIST -> album.songs.sortedWith { o1, o2 ->
                o1.trackNumber.compareTo(
                    o2.trackNumber
                )
            }

            SONG_A_Z -> {
                val collator = Collator.getInstance()
                album.songs.sortedWith { o1, o2 -> collator.compare(o1.title, o2.title) }
            }

            SONG_Z_A -> {
                val collator = Collator.getInstance()
                album.songs.sortedWith { o1, o2 -> collator.compare(o2.title, o1.title) }
            }

            SONG_DURATION -> album.songs.sortedWith { o1, o2 ->
                o1.duration.compareTo(
                    o2.duration
                )
            }

            else -> throw IllegalArgumentException("invalid $sortOrder")
        }
        album = album.copy(songs = songs)
        simpleSongAdapter.swapDataSet(album.songs)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
