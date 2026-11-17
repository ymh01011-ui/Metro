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

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.View
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import code.name.monkey.appthemehelper.util.ToolbarContentTintHelper
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.databinding.FragmentBlurBinding
import code.name.monkey.retromusic.extensions.drawAboveSystemBars
import code.name.monkey.retromusic.extensions.whichFragment
import code.name.monkey.retromusic.fragments.base.AbsPlayerFragment
import code.name.monkey.retromusic.fragments.player.PlayerAlbumCoverFragment
import code.name.monkey.retromusic.glide.RetroGlideExtension
import code.name.monkey.retromusic.helper.MusicPlayerRemote
import code.name.monkey.retromusic.model.Song
import code.name.monkey.retromusic.util.color.CoverColorExtractor
import code.name.monkey.retromusic.util.color.MediaNotificationProcessor
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


class BlurPlayerFragment : AbsPlayerFragment(R.layout.fragment_blur) {

    override fun playerToolbar(): Toolbar {
        return binding.playerToolbar
    }

    private lateinit var playbackControlsFragment: BlurPlaybackControlsFragment

    private var lastColor: Int = 0

    private var _binding: FragmentBlurBinding? = null
    private val binding get() = _binding!!


    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentBlurBinding.bind(view)
        setUpSubFragments()
        setUpPlayerToolbar()
        binding.playerToolbar.drawAboveSystemBars()
    }

    private fun setUpSubFragments() {
        playbackControlsFragment = whichFragment(R.id.playbackControlsFragment)
        val playerAlbumCoverFragment: PlayerAlbumCoverFragment =
            whichFragment(R.id.playerAlbumCoverFragment)
        playerAlbumCoverFragment.setCallbacks(this)
    }

    private fun setUpPlayerToolbar() {
        binding.playerToolbar.apply {
            inflateMenu(R.menu.menu_player)
            setNavigationOnClickListener { requireActivity().onBackPressedDispatcher.onBackPressed() }
            ToolbarContentTintHelper.colorizeToolbar(this, Color.WHITE, activity)
        }.setOnMenuItemClickListener(this)
    }

    override fun onFavoriteToggled() {
        toggleFavorite(MusicPlayerRemote.currentSong)
    }

    override fun onColorChanged(color: MediaNotificationProcessor) {
        playbackControlsFragment.setColor(color)
        lastColor = color.backgroundColor
        libraryViewModel.updateColor(color.backgroundColor)
        ToolbarContentTintHelper.colorizeToolbar(binding.playerToolbar, Color.WHITE, activity)

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
        return Color.WHITE
    }

    override val paletteColor: Int
        get() = lastColor

    private var coverTarget: CustomTarget<Bitmap>? = null

    // Real colors from the cover (k-means, distinct hues) -> mesh background
    private fun updateBackgroundColors() {
        coverTarget?.let { Glide.with(this).clear(it) }
        val target = object : CustomTarget<Bitmap>(96, 96) {
            override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                viewLifecycleOwner.lifecycleScope.launch {
                    val colors = withContext(Dispatchers.Default) {
                        CoverColorExtractor.extract(resource, 5)
                    }
                    if (colors.isNotEmpty() && _binding != null) {
                        binding.colorBackground.setColors(colors)
                    }
                }
            }

            override fun onLoadCleared(placeholder: android.graphics.drawable.Drawable?) {}
        }
        coverTarget = target
        Glide.with(this)
            .asBitmap()
            .load(RetroGlideExtension.getSongModel(MusicPlayerRemote.currentSong))
            .into(target)
    }

    private fun updatePlayingState() {
        if (_binding != null) binding.colorBackground.setPlaying(MusicPlayerRemote.isPlaying)
    }

    override fun onServiceConnected() {
        updateIsFavorite()
        updateBackgroundColors()
        updatePlayingState()
    }

    override fun onPlayingMetaChanged() {
        updateIsFavorite()
        updateBackgroundColors()
        updatePlayingState()
    }

    override fun onPlayStateChanged() {
        super.onPlayStateChanged()
        updatePlayingState()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        coverTarget?.let { Glide.with(this).clear(it) }
        coverTarget = null
        _binding = null
    }
}
