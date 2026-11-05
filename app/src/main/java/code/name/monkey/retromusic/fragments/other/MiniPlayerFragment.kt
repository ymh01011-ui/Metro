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

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.ColorUtils
import androidx.core.text.toSpannable
import androidx.core.view.isVisible
import code.name.monkey.appthemehelper.ThemeStore
import code.name.monkey.retromusic.views.GlassLayer
import com.google.android.material.color.MaterialColors
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.databinding.FragmentMiniPlayerBinding
import code.name.monkey.retromusic.extensions.accentColor
import code.name.monkey.retromusic.extensions.show
import code.name.monkey.retromusic.extensions.textColorPrimary
import code.name.monkey.retromusic.extensions.textColorSecondary
import code.name.monkey.retromusic.fragments.base.AbsMusicServiceFragment
import code.name.monkey.retromusic.glide.RetroGlideExtension
import code.name.monkey.retromusic.glide.RetroGlideExtension.songCoverOptions
import code.name.monkey.retromusic.helper.MusicPlayerRemote
import code.name.monkey.retromusic.helper.MusicProgressViewUpdateHelper
import code.name.monkey.retromusic.helper.PlayPauseButtonOnClickHandler
import code.name.monkey.retromusic.util.PreferenceUtil
import code.name.monkey.retromusic.util.RetroUtil
import com.bumptech.glide.Glide
import kotlin.math.abs

open class MiniPlayerFragment : AbsMusicServiceFragment(R.layout.fragment_mini_player),
    MusicProgressViewUpdateHelper.Callback, View.OnClickListener {

    private var _binding: FragmentMiniPlayerBinding? = null
    private val binding get() = _binding!!
    private lateinit var progressViewUpdateHelper: MusicProgressViewUpdateHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        progressViewUpdateHelper = MusicProgressViewUpdateHelper(this)
    }

    override fun onClick(view: View) {
        when (view.id) {
            R.id.actionNext -> MusicPlayerRemote.playNextSong()
            R.id.actionPrevious -> MusicPlayerRemote.back()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentMiniPlayerBinding.bind(view)
        view.setOnTouchListener(FlingPlayBackController(requireContext()))
        setUpMiniPlayer()
        setUpButtons()
    }

    fun setUpButtons() {
        if (RetroUtil.isTablet) {
            binding.actionNext.show()
            binding.actionPrevious.show()
        } else {
            binding.actionNext.isVisible = PreferenceUtil.isExtraControls
            binding.actionPrevious.isVisible = PreferenceUtil.isExtraControls
        }
        binding.actionNext.setOnClickListener(this)
        binding.actionPrevious.setOnClickListener(this)
    }

    /** Ink forced by the glass (dark over a bright page, white over a dark one); null = theme. */
    private var glassInk: Int? = null

    private fun setUpMiniPlayer() {
        setUpPlayPauseButton()
        binding.progressBar.setIndicatorColor(ThemeStore.accentColor(requireContext()))
        applyInk()
    }

    /** Called by the glass as the page behind the mini player glides between dark (0) and bright (1). */
    fun applyGlassInk(tone: Float?) {
        glassInk = tone?.let { ColorUtils.blendARGB(Color.WHITE, GlassLayer.DARK_INK, it) }
        if (_binding != null && isAdded) applyInk()
    }

    private fun applyInk() {
        val forced = glassInk
        val ink = forced ?: textColorPrimary()
        binding.miniPlayerTitle.setTextColor(ink)
        binding.miniPlayerArtist.setTextColor(
            if (forced != null) ColorUtils.setAlphaComponent(ink, 175) else textColorSecondary()
        )
        val tint = ColorStateList.valueOf(
            forced ?: MaterialColors.getColor(
                requireContext(), androidx.appcompat.R.attr.colorControlNormal, ink
            )
        )
        binding.actionPrevious.imageTintList = tint
        binding.actionNext.imageTintList = tint
        binding.miniPlayerPlayPauseButton.imageTintList = tint
        binding.progressBar.trackColor = ColorUtils.setAlphaComponent(ink, 46)
        // the play button gets a soft surface of its own, like Square's glass button
        val surface = GradientDrawable()
        surface.shape = GradientDrawable.OVAL
        surface.setColor(ColorUtils.setAlphaComponent(ink, 30))
        surface.setStroke(
            resources.displayMetrics.density.toInt().coerceAtLeast(1),
            ColorUtils.setAlphaComponent(ink, 50)
        )
        binding.miniPlayerPlayPauseButton.background = surface
    }

    private fun setUpPlayPauseButton() {
        binding.miniPlayerPlayPauseButton.setOnClickListener(PlayPauseButtonOnClickHandler())
    }

    private fun updateSongTitle() {
        val song = MusicPlayerRemote.currentSong
        // title on top, artist underneath
        binding.miniPlayerTitle.isSelected = true
        binding.miniPlayerTitle.text = song.title
        binding.miniPlayerArtist.text = song.artistName
        applyInk()
    }

    private fun updateSongCover() {
        val song = MusicPlayerRemote.currentSong
        Glide.with(requireContext())
            .load(RetroGlideExtension.getSongModel(song))
            .transition(RetroGlideExtension.getDefaultTransition())
            .songCoverOptions(song)
            .into(binding.image)
    }

    override fun onServiceConnected() {
        updateSongTitle()
        updateSongCover()
        updatePlayPauseDrawableState()
    }

    override fun onPlayingMetaChanged() {
        updateSongTitle()
        updateSongCover()
    }

    override fun onPlayStateChanged() {
        updatePlayPauseDrawableState()
    }

    override fun onUpdateProgressViews(progress: Int, total: Int) {
        binding.progressBar.max = total
        binding.progressBar.progress = progress
    }

    override fun onResume() {
        super.onResume()
        progressViewUpdateHelper.start()
    }

    override fun onPause() {
        super.onPause()
        progressViewUpdateHelper.stop()
    }

    protected fun updatePlayPauseDrawableState() {
        if (MusicPlayerRemote.isPlaying) {
            binding.miniPlayerPlayPauseButton.setImageResource(R.drawable.ic_pause)
        } else {
            binding.miniPlayerPlayPauseButton.setImageResource(R.drawable.ic_play_arrow)
        }
    }

    class FlingPlayBackController(context: Context) : View.OnTouchListener {

        private var flingPlayBackController = GestureDetector(context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onFling(
                    e1: MotionEvent,
                    e2: MotionEvent,
                    velocityX: Float,
                    velocityY: Float
                ): Boolean {
                    if (abs(velocityX) > abs(velocityY)) {
                        if (velocityX < 0) {
                            MusicPlayerRemote.playNextSong()
                            return true
                        } else if (velocityX > 0) {
                            MusicPlayerRemote.playPreviousSong()
                            return true
                        }
                    }
                    return false
                }
            })

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            return flingPlayBackController.onTouchEvent(event)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
