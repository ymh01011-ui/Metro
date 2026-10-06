package code.name.monkey.retromusic.util

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

object AppleBackgroundColor {

    private const val SAMPLE_W = 60
    private const val SAMPLE_H = 100
    private const val TOP_ROWS = 7          // ~7% من أعلى الصورة
    private const val SCALE = 1.16f         // تقريب مبني على قياساتنا
    private const val OFFSET = -31f
    private const val TARGET_L = 65f        // n في دالة Apple
    private const val MIN_CONTRAST = 3.0    // i في دالة Apple

    /** الدالة الرئيسية: Bitmap الغلاف -> لون الخلفية (ARGB) */
    fun fromArtwork(src: Bitmap): Int {
        val (r, g, b) = topStripAverage(src)
        val approx = intArrayOf(
            (r * SCALE + OFFSET).roundToInt().coerceIn(0, 255),
            (g * SCALE + OFFSET).roundToInt().coerceIn(0, 255),
            (b * SCALE + OFFSET).roundToInt().coerceIn(0, 255)
        )
        return adjustColorForWhiteText(approx[0], approx[1], approx[2])
    }

    /** متوسط RGB لشريط الأعلى بعد تصغير الصورة لـ 60x100 */
    private fun topStripAverage(src: Bitmap): Triple<Float, Float, Float> {
        val small = Bitmap.createScaledBitmap(src, SAMPLE_W, SAMPLE_H, true)
        val px = IntArray(SAMPLE_W * TOP_ROWS)
        small.getPixels(px, 0, SAMPLE_W, 0, 0, SAMPLE_W, TOP_ROWS)
        if (small !== src) small.recycle()
        var r = 0L; var g = 0L; var b = 0L
        for (p in px) { r += Color.red(p); g += Color.green(p); b += Color.blue(p) }
        val n = px.size.toFloat()
        return Triple(r / n, g / n, b / n)
    }

    /** نسخة من adjustColorForWhiteText بتاعة Apple (n=65, i=3) */
    private fun adjustColorForWhiteText(r: Int, g: Int, b: Int): Int {
        val lum = relativeLuminance(r, g, b)
        val contrast = 1.05 / (lum + 0.05)       // الأبيض luminance = 1
        if (contrast >= MIN_CONTRAST) return Color.rgb(r, g, b)

        val lab = rgbToLab(r, g, b)
        val c = hypot(lab[1], lab[2])
        val h = atan2(lab[2], lab[1])
        val newC = c * (TARGET_L / lab[0].coerceAtLeast(0.0001))
        val lab2 = doubleArrayOf(TARGET_L.toDouble(), newC * cos(h), newC * sin(h))
        val rgb = labToRgb(lab2[0], lab2[1], lab2[2])

        val hsl = rgbToHsl(rgb[0], rgb[1], rgb[2])
        hsl[1] = min(hsl[1] * 1.5, 1.0)
        val out = hslToRgb(hsl[0], hsl[1], hsl[2])
        return Color.rgb(out[0], out[1], out[2])
    }

    private fun relativeLuminance(r: Int, g: Int, b: Int): Double {
        fun f(v: Int): Double {
            val a = v / 255.0
            return if (a <= 0.03928) a / 12.92 else ((a + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * f(r) + 0.7152 * f(g) + 0.0722 * f(b)
    }

    // ---- sRGB <-> Lab (D65: Xn=.95047, Yn=1, Zn=1.08883) ----
    private const val XN = 0.95047
    private const val YN = 1.0
    private const val ZN = 1.08883

    private fun lin(v: Int): Double {
        val a = v / 255.0
        return if (a <= 0.04045) a / 12.92 else ((a + 0.055) / 1.055).pow(2.4)
    }

    private fun gamma(v: Double): Int {
        val c = if (v <= 0.0031308) v * 12.92 else 1.055 * v.pow(1 / 2.4) - 0.055
        return (c * 255).roundToInt().coerceIn(0, 255)
    }

    private fun rgbToLab(r: Int, g: Int, b: Int): DoubleArray {
        val rl = lin(r); val gl = lin(g); val bl = lin(b)
        val x = (0.4124564 * rl + 0.3575761 * gl + 0.1804375 * bl) / XN
        val y = (0.2126729 * rl + 0.7151522 * gl + 0.0721750 * bl) / YN
        val z = (0.0193339 * rl + 0.1191920 * gl + 0.9503041 * bl) / ZN
        fun f(t: Double) = if (t > 0.008856) cbrt(t) else 7.787 * t + 16.0 / 116
        val fx = f(x); val fy = f(y); val fz = f(z)
        return doubleArrayOf(116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz))
    }

    private fun labToRgb(l: Double, a: Double, b: Double): IntArray {
        val fy = (l + 16) / 116
        val fx = fy + a / 500
        val fz = fy - b / 200
        fun inv(t: Double): Double {
            val t3 = t * t * t
            return if (t3 > 0.008856) t3 else (t - 16.0 / 116) / 7.787
        }
        val x = inv(fx) * XN
        val y = inv(fy) * YN
        val z = inv(fz) * ZN
        val rl = 3.2404542 * x - 1.5371385 * y - 0.4985314 * z
        val gl = -0.9692660 * x + 1.8760108 * y + 0.0415560 * z
        val bl = 0.0556434 * x - 0.2040259 * y + 1.0572252 * z
        return intArrayOf(gamma(rl), gamma(gl), gamma(bl))
    }

    // ---- RGB <-> HSL (h: 0..360, s/l: 0..1) ----
    private fun rgbToHsl(r: Int, g: Int, b: Int): DoubleArray {
        val rf = r / 255.0; val gf = g / 255.0; val bf = b / 255.0
        val mx = max(rf, max(gf, bf)); val mn = min(rf, min(gf, bf))
        val l = (mx + mn) / 2
        val d = mx - mn
        if (d == 0.0) return doubleArrayOf(0.0, 0.0, l)
        val s = d / (1 - abs(2 * l - 1))
        var h = when (mx) {
            rf -> 60 * (((gf - bf) / d) % 6)
            gf -> 60 * ((bf - rf) / d + 2)
            else -> 60 * ((rf - gf) / d + 4)
        }
        if (h < 0) h += 360
        return doubleArrayOf(h, s, l)
    }

    private fun hslToRgb(h: Double, s: Double, l: Double): IntArray {
        val c = (1 - abs(2 * l - 1)) * s
        val x = c * (1 - abs((h / 60) % 2 - 1))
        val m = l - c / 2
        val (r1, g1, b1) = when {
            h < 60 -> Triple(c, x, 0.0)
            h < 120 -> Triple(x, c, 0.0)
            h < 180 -> Triple(0.0, c, x)
            h < 240 -> Triple(0.0, x, c)
            h < 300 -> Triple(x, 0.0, c)
            else -> Triple(c, 0.0, x)
        }
        return intArrayOf(
            ((r1 + m) * 255).roundToInt().coerceIn(0, 255),
            ((g1 + m) * 255).roundToInt().coerceIn(0, 255),
            ((b1 + m) * 255).roundToInt().coerceIn(0, 255)
        )
    }
}
