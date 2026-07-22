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
 *
 * ليه محرك منفصل بدل استخدام Palette API زي ما هو؟
 * لأن تصنيفات Palette الجاهزة (Dominant / Vibrant / Muted...) بترجع null كتير على صور
 * حقيقية (خصوصًا صور استوديو فيها خلفية رمادية كبيرة زي الكونكريت)، والاعتماد على
 * fallback من تصنيف لتصنيف بيخلي كل الألوان تنهار على نفس اللون الرمادي الكبير،
 * فالنتيجة النهائية تبقى خلفية رمادية مسطحة بدون أي بصمة حقيقية من الصورة.
 *
 * هنا بدل التصنيفات الجاهزة، بنمشي يدويًا على كل الـ Swatches الخام (لحد 32 لون)،
 * وبنحسب لكل واحد فيهم "Vibrancy Score" = التشبع × جذر نسبة ظهوره في الصورة. كده لون
 * زي جاكيت أحمر صغير المساحة لسه بيتلقط كـ Accent، من غير ما الخلفية الرمادية الكبيرة
 * تطغى عليه — وده أقرب لسلوك Apple Music من الاعتماد المباشر على Palette الخام.
 */
object ArtistPaletteEngine {

    /** الألوان النهائية اللي بيتبنى منها التدرّج السينمائي. */
    data class ArtistColors(
        @ColorInt val base: Int,   // اللون المحايد الأساسي (رمادي بميل أزرق لو الأصل رمادي فعلاً، وإلا نفس لون الصورة الحقيقي)
        @ColorInt val accent: Int, // أقوى لون "حي" في الصورة حتى لو مساحته صغيرة
        @ColorInt val deep: Int    // الطبقة الأخيرة الغامقة (كحلي لو الأصل رمادي، وإلا نفس الـ Hue بس أغمق)
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

        // بنحدد الأول: اللون المسيطر في الصورة "محايد فعلاً" (رمادي/بني فاتح باهت زي الحيطة
        // في صورة NSYNC) ولا "لون حقيقي قوي" (زي الأحمر في صورة Doja Cat)؟ التلوين الأزرق
        // البارد لازم يتطبق بس على الحالة المحايدة — لو طبقناه على لون قوي أصلاً، هيسحبه
        // ناحية الموف/البنفسجي ويبوّظ اللون الحقيقي بدل ما يحافظ عليه
        val baseSaturation = saturationOf(baseSwatch.rgb)
        val isNeutralBase = baseSaturation < 0.14f

        // اللون المحايد: لو الأصل رمادي فعلاً، بنديله ميل أزرق بارد خفيف. لو الأصل لون
        // قوي أصلاً (زي الأحمر)، بنسيبه زي ما هو من غير أي تلوين إضافي
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

        // الطبقة الأخيرة: لو الأصل رمادي، بتميل للكحلي. لو الأصل لون قوي أصلاً، بتفضل
        // بنفس الـ Hue وبس بتغمق (Value يقل)، عشان الأحمر يفضل أحمر وهو بيغمق مش بيتحول كحلي
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
     * Transparent → Base (رمادي مزرق) → لمسة Accent في النص → Deep (كحلي غامق) → لون خلفية الصفحة.
     *
     * الـ Fade بيبدأ من [fadeStart] بس (افتراضيًا 12% من ارتفاع الصورة) عشان الصورة تفضل
     * واضحة، وكل نقطة بتتحسب بـ Blend ناعم (Smoothstep) بين الكي-فريمات فمفيش حدود واضحة.
     */
    fun buildGradientStops(
        colors: ArtistColors,
        flatBackgroundColor: Int,
        fadeStart: Float = 0.12f,
        stopCount: Int = 26
    ): IntArray {
        val endColor = blend(colors.deep, flatBackgroundColor, 0.55f)

        data class Keyframe(val t: Float, val color: Int, val alpha: Int)

        val keyframes = listOf(
            Keyframe(0.00f, colors.base, 0),
            Keyframe(fadeStart, colors.base, 0),
            Keyframe(0.30f, colors.base, 55),
            Keyframe(0.45f, blend(colors.base, colors.accent, 0.35f), 120), // لمسة الـ Accent هنا
            Keyframe(0.62f, blend(colors.base, colors.deep, 0.55f), 185),
            Keyframe(0.82f, colors.deep, 235),
            Keyframe(1.00f, endColor, 255)
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
    // Color science helpers (كل حاجة هنا محلية للملف ده بس)
    // ---------------------------------------------------------------------

    private fun vibrancyScore(swatch: Palette.Swatch, totalPopulation: Int): Double {
        val hsv = FloatArray(3)
        Color.colorToHSV(swatch.rgb, hsv)
        val saturation = hsv[1].toDouble()
        val value = hsv[2].toDouble()
        val populationRatio = swatch.population.toDouble() / totalPopulation
        // بنقلّل وزن الألوان القريبة جدًا من الأبيض/الأسود الخام عشان متتحسبش Accent
        val extremeValuePenalty = if (value < 0.15 || value > 0.95) 0.35 else 1.0
        return saturation * sqrt(populationRatio) * extremeValuePenalty
    }

    private fun saturationOf(@ColorInt color: Int): Float {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        return hsv[1]
    }

    /** بيقرّب اللون شوية من Hue مستهدف (Cool blue أو Navy) من غير ما يمسح لونه الأصلي بالكامل. */
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

    /** blend(A, B, ratio): ratio = 0 يرجّع A بالكامل، ratio = 1 يرجّع B بالكامل. */
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
