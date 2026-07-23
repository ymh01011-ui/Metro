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
import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import kotlin.math.sqrt

/**
 * محرك ألوان مستقل بالكامل، مخصص بس لشاشة تفاصيل الفنان (Artist Details).
 * مش بيلمس ولا بيتشارك أي كود مع [RetroColorUtil] أو أي شاشة تانية في التطبيق،
 * فتعديله أو حتى كسره ميأثرش على أي حتة تانية.
 */
object ArtistPaletteEngine {

    /** الألوان النهائية اللي بيتبنى منها التدرّج السينمائي. */
    data class ArtistColors(
        @ColorInt val base: Int,   // اللون المحايد الأساسي
        @ColorInt val accent: Int, // أقوى لون "حي" في الصورة حتى لو مساحته صغيرة
        @ColorInt val deep: Int    // الطبقة الأخيرة الغامقة
    )

    private const val COOL_HUE = 215f // ميل أزرق بارد للّون المحايد
    private const val NAVY_HUE = 225f // ميل كحلي للطبقة الأخيرة

    /** بيستخرج Palette بأكبر عدد ألوان ممكن (32) عشان الـ Accent الصغير زي الجاكيت ميضيعش. */
    fun generatePalette(bitmap: Bitmap): Palette =
        Palette.Builder(bitmap)
            .clearFilters()
            .maximumColorCount(32)
            .generate()

    fun extractColors(palette: Palette?, fallback: Int): ArtistColors {
        val swatches = palette?.swatches.orEmpty()
        if (swatches.isEmpty()) {
            return ArtistColors(fallback, fallback, fallback)
        }

        val totalPopulation = swatches.sumOf { it.population }.coerceAtLeast(1)

        // أكبر مساحة في الصورة (غالبًا الخلفية) بتاخد دور اللون المحايد الأساسي
        val baseSwatch = swatches.maxByOrNull { it.population } ?: swatches.first()

        // أقوى لون "حي" في الصورة حتى لو مساحته صغيرة (زي الجاكيت الأحمر)
        val accentSwatch = swatches.maxByOrNull { vibrancyScore(it, totalPopulation) } ?: baseSwatch
        val accentSaturation = saturationOf(accentSwatch.rgb)

        // لو الصورة فعلاً شبه أحادية اللون (مفيش أي لون حي حقيقي)، منفتعلش لون مش موجود
        val hasRealAccent = accentSaturation > 0.16f

        val baseSaturation = saturationOf(baseSwatch.rgb)
        val isNeutralBase = baseSaturation < 0.14f

        val base = if (isNeutralBase) {
            tintTowardHue(baseSwatch.rgb, COOL_HUE, hueShiftRatio = 0.22f, minSaturation = 0.10f)
        } else {
            baseSwatch.rgb
        }

        val accent = if (hasRealAccent) {
            boostSaturation(accentSwatch.rgb, targetMinSaturation = 0.55f)
        } else {
            base
        }

        val deepBase = if (isNeutralBase) {
            tintTowardHue(baseSwatch.rgb, NAVY_HUE, hueShiftRatio = 0.35f, minSaturation = 0.14f)
        } else {
            baseSwatch.rgb
        }
        val deepWithAccent = if (hasRealAccent) blend(deepBase, accent, 0.15f) else deepBase
        val deep = darken(deepWithAccent, targetValue = 0.16f)

        return ArtistColors(base = base, accent = accent, deep = deep)
    }

    /**
     * بيبني مصفوفة الألوان الجاهزة لـ GradientDrawable (Orientation TOP_BOTTOM):
     * بيبدأ شفاف تمامًا فوق، وبيمتزج بنعومة لحد ما ينتهي عند الأسفل بلون خلفية الصفحة
     * الخالص (Alpha = 255) لضمان اختفاء أي خط فاصل نهائيًا.
     */
    fun buildGradientStops(
        colors: ArtistColors,
        flatBackgroundColor: Int,
        fadeStart: Float = 0.15f,
        stopCount: Int = 26
    ): IntArray {
        val targetEndColor = flatBackgroundColor

        data class Keyframe(val t: Float, val color: Int, val alpha: Int)

        val keyframes = listOf(
            Keyframe(0.00f, colors.base, 0),
            Keyframe(fadeStart, colors.base, 0),
            Keyframe(0.35f, colors.accent, 80),
            Keyframe(0.65f, blend(colors.accent, targetEndColor, 0.5f), 170),
            Keyframe(0.85f, targetEndColor, 230),
            Keyframe(1.00f, targetEndColor, 255)
        )

        return IntArray(stopCount) { i ->
            val t = i / (stopCount - 1f)
            val (from, to) = keyframes.zipWithNext().firstOrNull { (a, b) -> t in a.t..b.t }
                ?: (keyframes.last() to keyframes.last())

            val span = (to.t - from.t).takeIf { it > 0f } ?: 1f
            val local = ((t - from.t) / span).coerceIn(0f, 1f)
            val eased = local * local * (3f - 2f * local) // Smoothstep

            val rgb = blend(from.color, to.color, eased)
            val alpha = (from.alpha + (to.alpha - from.alpha) * eased).toInt().coerceIn(0, 255)
            ColorUtils.setAlphaComponent(rgb, alpha)
        }
    }

    // ---------------------------------------------------------------------
    // Color science helpers
    // ---------------------------------------------------------------------

    private fun vibrancyScore(swatch: Palette.Swatch, totalPopulation: Int): Double {
        val hsv = FloatArray(3)
        Color.colorToHSV(swatch.rgb, hsv)
        val saturation = hsv[1].toDouble()
        val value = hsv[2].toDouble()
        val populationRatio = swatch.population.toDouble() / totalPopulation
        val extremeValuePenalty = if (value < 0.15 || value > 0.95) 0.35 else 1.0
        return saturation * sqrt(populationRatio) * extremeValuePenalty
    }

    private fun saturationOf(@ColorInt color: Int): Float {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        return hsv[1]
    }

    @ColorInt
    private fun tintTowardHue(
        @ColorInt color: Int,
        targetHueDegrees: Float,
        hueShiftRatio: Float,
        minSaturation: Float
    ): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        val hueDiff = ((targetHueDegrees - hsv[0] + 540f) % 360f) - 180f
        hsv[0] = (hsv[0] + hueDiff * hueShiftRatio + 360f) % 360f
        hsv[1] = maxOf(hsv[1], minSaturation)
        return Color.HSVToColor(hsv)
    }

    @ColorInt
    private fun boostSaturation(@ColorInt color: Int, targetMinSaturation: Float): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        if (hsv[1] < targetMinSaturation) {
            hsv[1] = targetMinSaturation
        }
        return Color.HSVToColor(hsv)
    }

    @ColorInt
    private fun darken(@ColorInt color: Int, targetValue: Float): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        if (hsv[2] > targetValue) {
            hsv[2] = targetValue
        }
        return Color.HSVToColor(hsv)
    }

    @ColorInt
    private fun blend(@ColorInt colorA: Int, @ColorInt colorB: Int, ratio: Float): Int {
        val r = ratio.coerceIn(0f, 1f)
        val inv = 1f - r
        val red = Color.red(colorA) * inv + Color.red(colorB) * r
        val green = Color.green(colorA) * inv + Color.green(colorB) * r
        val blue = Color.blue(colorA) * inv + Color.blue(colorB) * r
        return Color.rgb(red.toInt(), green.toInt(), blue.toInt())
    }
}
