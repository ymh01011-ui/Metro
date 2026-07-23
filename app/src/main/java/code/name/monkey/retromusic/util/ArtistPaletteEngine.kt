/*
 * Copyright (c) 2019 Hemanth Savarala.
 *
 * Licensed under the GNU General Public License v3
 *
 * This is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by
 *  the Free Software Foundation either version 3 of the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 */
package code.name.monkey.retromusic.util

import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.graphics.ColorUtils

/**
 * محرك استخراج الألوان والتدرّج المنسجم لصفحة الفنان (Apple Music Style).
 */
object ArtistPaletteEngine {

    /**
     * بيستخرج "أكثر لون متكرر" (Dominant Color) بدون أي خلط، 
     * تحديداً من منطقة سطر معلومات الأغاني والألبومات (من 68% إلى 78% من ارتفاع الصورة).
     */
    fun findDominantColorAtSubtitleRegion(
        bitmap: Bitmap,
        startRatio: Float = 0.68f,
        endRatio: Float = 0.78f
    ): Int {
        val startY = (bitmap.height * startRatio).toInt().coerceIn(0, bitmap.height - 1)
        val endY = (bitmap.height * endRatio).toInt().coerceIn(startY + 1, bitmap.height)

        val colorCountMap = HashMap<Int, Int>()

        // تسريع الفحص بدون التأثير على الدقة
        val stepX = maxOf(1, bitmap.width / 100)
        val stepY = maxOf(1, (endY - startY) / 20)

        for (y in startY until endY step stepY) {
            for (x in 0 until bitmap.width step stepX) {
                val pixel = bitmap.getPixel(x, y)
                
                // تجميع الألوان المتقاربة جداً لضمان دقة العد
                val quantizedColor = Color.rgb(
                    (Color.red(pixel) / 8) * 8,
                    (Color.green(pixel) / 8) * 8,
                    (Color.blue(pixel) / 8) * 8
                )

                val count = colorCountMap.getOrDefault(quantizedColor, 0) + 1
                colorCountMap[quantizedColor] = count
            }
        }

        // أخذ اللون صاحب أعلى تكرار إحصائي (Mode)
        val dominantQuantized = colorCountMap.maxByOrNull { it.value }?.key ?: Color.BLACK

        return dominantQuantized
    }

    /**
     * يبني تدرجًا لونيًا متدرج الشفافية (Alpha) ناعماً جداً لمنع وجود أي خطوط حادة.
     */
    fun buildSeamlessGradient(
        blendColor: Int,
        fadeStart: Float = 0.42f,
        stopCount: Int = 30
    ): IntArray {
        return IntArray(stopCount) { i ->
            val progress = i / (stopCount - 1f)

            val alphaProgress = if (progress < fadeStart) {
                0f
            } else {
                val localProgress = (progress - fadeStart) / (1f - fadeStart)
                localProgress * localProgress * (3f - 2f * localProgress) // Smoothstep
            }

            val alphaInt = (alphaProgress * 255).toInt().coerceIn(0, 255)
            ColorUtils.setAlphaComponent(blendColor, alphaInt)
        }
    }
}
