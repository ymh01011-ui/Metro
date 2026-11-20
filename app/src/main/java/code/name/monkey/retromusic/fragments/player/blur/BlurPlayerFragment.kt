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

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import code.name.monkey.appthemehelper.util.ToolbarContentTintHelper
import code.name.monkey.retromusic.NEW_BLUR_AMOUNT
import code.name.monkey.retromusic.R
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
import code.name.monkey.retromusic.util.PreferenceUtil.blurAmount
import code.name.monkey.retromusic.util.color.MediaNotificationProcessor
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestBuilder


class BlurPlayerFragment : AbsPlayerFragment(R.layout.fragment_blur),
    SharedPreferences.OnSharedPreferenceChangeListener {

    private var lastRequest: RequestBuilder<Drawable>? = null

    override fun playerToolbar(): Toolbar {
        return binding.playerToolbar
    }

    private lateinit var playbackControlsFragment: BlurPlaybackControlsFragment

    private var lastColor: Int = 0

    // Light gray used for the bottom icon row (heart, queue, ...), like the design
    private val iconColor = Color.rgb(214, 214, 214)

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
            inflateMenu(R.menu.menu_player_blur)
            // (no back arrow in portrait; kept for the landscape layout which still has one)
            setNavigationOnClickListener { requireActivity().onBackPressedDispatcher.onBackPressed() }
            overflowIcon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_more_horiz)
            ToolbarContentTintHelper.colorizeToolbar(this, iconColor, activity)
        }.setOnMenuItemClickListener(this)
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
    override fun lyricsIconRes(isShowing: Boolean): Int = R.drawable.ic_closed_caption

    override val paletteColor: Int
        get() = lastColor

    private fun updateBlur() {
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
    }

    override fun onPlayingMetaChanged() {
        updateIsFavorite()
        updateBlur()
    }

    private fun startBackgroundMotion() {
        stopBackgroundMotion()
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
    }

    override fun onPause() {
        super.onPause()
        lastRequest = null
        stopBackgroundMotion()
    }

    override fun onResume() {
        super.onResume()
        PreferenceManager.getDefaultSharedPreferences(requireContext())
            .registerOnSharedPreferenceChangeListener(this)
        startBackgroundMotion()
    }

    override fun onDestroyView() {
        stopBackgroundMotion()
        super.onDestroyView()
        PreferenceManager.getDefaultSharedPreferences(requireContext())
            .unregisterOnSharedPreferenceChangeListener(this)
        _binding = null
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (key == NEW_BLUR_AMOUNT) {
            updateBlur()
        }
    }
}
