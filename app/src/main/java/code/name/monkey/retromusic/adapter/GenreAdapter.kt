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
import androidx.core.graphics.ColorUtils
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.RecyclerView
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.databinding.ItemGenreBinding
import code.name.monkey.retromusic.glide.RetroGlideExtension
import code.name.monkey.retromusic.glide.RetroGlideExtension.asBitmapPalette
import code.name.monkey.retromusic.glide.RetroGlideExtension.songCoverOptions
import code.name.monkey.retromusic.glide.RetroMusicColoredTarget
import code.name.monkey.retromusic.interfaces.IGenreClickListener
import android.util.LruCache
import code.name.monkey.retromusic.model.Genre
import code.name.monkey.retromusic.model.Song
import code.name.monkey.retromusic.util.MusicUtil
import code.name.monkey.retromusic.util.color.MediaNotificationProcessor
import com.bumptech.glide.Glide
import java.util.*

/**
 * @author Hemanth S (h4h13).
 */

class GenreAdapter(
    private val activity: FragmentActivity,
    var dataSet: List<Genre>,
    private val listener: IGenreClickListener
) : RecyclerView.Adapter<GenreAdapter.ViewHolder>() {

    init {
        this.setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long {
        return dataSet[position].id
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return ViewHolder(ItemGenreBinding.inflate(LayoutInflater.from(activity), parent, false))
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val genre = dataSet[position]
        holder.binding.title.text = genre.name
        holder.binding.text.text = String.format(
            Locale.getDefault(),
            "%d %s",
            genre.songCount,
            if (genre.songCount > 1) activity.getString(R.string.songs) else activity.getString(R.string.song)
        )
        loadGenreImage(genre, holder)
    }

    private fun loadGenreImage(genre: Genre, holder: GenreAdapter.ViewHolder) {
        // كان بيعمل query على الـ main thread لكل كرت مع كل bind (وأنت راجع من التفاصيل كمان).
        // دلوقتي بيتحسب مرة وبيتخزّن، ومفتاحه بيتغير لو عدد أغاني الـ genre اتغير.
        val cacheKey = 31 * genre.id.hashCode() + genre.songCount
        val genreSong: Song = coverSongCache.get(cacheKey)
            ?: MusicUtil.songByGenre(genre.id).also { coverSongCache.put(cacheKey, it) }
        Glide.with(activity)
            .asBitmapPalette()
            .songCoverOptions(genreSong)
            .load(RetroGlideExtension.getSongModel(genreSong))
            .into(object : RetroMusicColoredTarget(holder.binding.image) {
                override fun onColorReady(colors: MediaNotificationProcessor) {
                    setColors(holder, colors)
                }
            })
    }

    private fun setColors(holder: ViewHolder, color: MediaNotificationProcessor) {
        // Oto-style: dark muted tint of the cover color, light text on top
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(color.backgroundColor, hsl)
        hsl[1] = (hsl[1] * 0.4f).coerceAtMost(0.35f)
        hsl[2] = 0.2f
        holder.binding.imageContainerCard.setCardBackgroundColor(ColorUtils.HSLToColor(hsl))
        holder.binding.title.setTextColor(0xFFE6E1E5.toInt())
        holder.binding.text.setTextColor(0xB3E6E1E5.toInt())
    }

    override fun getItemCount(): Int {
        return dataSet.size
    }

    @SuppressLint("NotifyDataSetChanged")
    fun swapDataSet(list: List<Genre>) {
        dataSet = list
        notifyDataSetChanged()
    }

    companion object {
        private val coverSongCache = LruCache<Int, Song>(512)
    }

    inner class ViewHolder(val binding: ItemGenreBinding) : RecyclerView.ViewHolder(binding.root),
        View.OnClickListener {
        override fun onClick(v: View?) {
            listener.onClickGenre(dataSet[layoutPosition], itemView)
        }

        init {
            itemView.setOnClickListener(this)
            binding.image.shapeAppearanceModel = binding.image.shapeAppearanceModel
                .toBuilder()
                .setAllCornerSizes(8f * activity.resources.displayMetrics.density)
                .build()
        }
    }
}
