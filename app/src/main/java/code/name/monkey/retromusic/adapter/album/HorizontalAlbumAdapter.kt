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
package code.name.monkey.retromusic.adapter.album

import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.FragmentActivity
import code.name.monkey.retromusic.glide.RetroGlideExtension
import code.name.monkey.retromusic.glide.RetroGlideExtension.albumCoverOptions
import code.name.monkey.retromusic.glide.RetroGlideExtension.asBitmapPalette
import code.name.monkey.retromusic.glide.RetroMusicColoredTarget
import code.name.monkey.retromusic.helper.HorizontalAdapterHelper
import code.name.monkey.retromusic.interfaces.IAlbumClickListener
import code.name.monkey.retromusic.model.Album
import code.name.monkey.retromusic.util.color.MediaNotificationProcessor
import com.bumptech.glide.Glide

class HorizontalAlbumAdapter(
    activity: FragmentActivity,
    dataSet: List<Album>,
    albumClickListener: IAlbumClickListener
) : AlbumAdapter(
    activity, dataSet, HorizontalAdapterHelper.LAYOUT_RES, albumClickListener
) {

    private var dynamicPrimaryTextColor: Int? = null
    private var dynamicSecondaryTextColor: Int? = null

    /**
     * Called by the artist page whenever its dynamic background color changes.
     * Unlike before, this color is now stored on the adapter itself and reapplied
     * on every bind (see onBindViewHolder/setColors below) — so items scrolled
     * into view later, which go through Android's normal ViewHolder recycling,
     * no longer fall back to the item layout's static default text color.
     */
    fun setDynamicTextColors(primaryColor: Int, secondaryColor: Int) {
        dynamicPrimaryTextColor = primaryColor
        dynamicSecondaryTextColor = secondaryColor
        notifyDataSetChanged()
    }

    override fun createViewHolder(view: View, viewType: Int): ViewHolder {
        val params = view.layoutParams as ViewGroup.MarginLayoutParams
        HorizontalAdapterHelper.applyMarginToLayoutParams(activity, params, viewType)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        super.onBindViewHolder(holder, position)
        // Apply immediately on bind so the color is correct even before the
        // album cover's async palette callback (setColors below) has fired.
        applyDynamicTextColors(holder)
    }

    override fun setColors(color: MediaNotificationProcessor, holder: ViewHolder) {
        // The artist page's dynamic background color always wins over the
        // album's own per-cover palette color, so every row stays consistent
        // with the page regardless of when this async callback lands.
        applyDynamicTextColors(holder)
    }

    private fun applyDynamicTextColors(holder: ViewHolder) {
        val primary = dynamicPrimaryTextColor ?: return
        val secondary = dynamicSecondaryTextColor ?: return
        holder.title?.setTextColor(primary)
        holder.text?.setTextColor(secondary)
    }

    override fun loadAlbumCover(album: Album, holder: ViewHolder) {
        if (holder.image == null) return
        Glide.with(activity)
            .asBitmapPalette()
            .albumCoverOptions(album.safeGetFirstSong())
            .load(RetroGlideExtension.getSongModel(album.safeGetFirstSong()))
            .into(object : RetroMusicColoredTarget(holder.image!!) {
                override fun onColorReady(colors: MediaNotificationProcessor) {
                    setColors(colors, holder)
                }
            })
    }

    override fun getItemViewType(position: Int): Int {
        return HorizontalAdapterHelper.getItemViewType(position, itemCount)
    }

    override fun getItemCount(): Int {
        return dataSet.size
    }

    companion object {
        val TAG: String = AlbumAdapter::class.java.simpleName
    }
}
