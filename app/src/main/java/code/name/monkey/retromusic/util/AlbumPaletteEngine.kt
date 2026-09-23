package code.name.monkey.retromusic.util

import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.graphics.ColorUtils

/**
 * محرك لون خاص بصفحة تفاصيل الألبوم بس (منفصل تمامًا عن ArtistPaletteEngine
 * المستخدم في صفحة الفنان، عشان أي تعديل هنا محدش يكسر حاجة هناك).
 *
 * بيمسح الصورة كلها (مش عينة من جزء واحد زي findDominantColorAtSubtitleRegion)
 * ويلاقي أكتر لون متكرر فعليًا (mode color)، مع استثناء واحد: لو أكتر لون
 * متكرر طلع أسود/قريب من الأسود بس نسبته من إجمالي البكسلات أقل من 35%،
 * بيتجاهله ويرجع تاني أكتر لون متكرر بدله.
 */
object AlbumPaletteEngine {

    private const val BLACK_MIN_SHARE = 0.35f
    private const val BLACK_LUMINANCE_THRESHOLD = 0.05
    // لو حواف الصورة (فوق/تحت/يمين/شمال) لونها متكرر بنسبة أعلى من كده،
    // بنستخدم لون الحواف ده كخلفية للصفحة بدل أكتر لون متكرر في الصورة
    // كلها - غالبًا معناها إن الصورة أصلاً عليها خلفية مصمتة واحدة.
    private const val EDGE_DOMINANT_MIN_SHARE = 0.55f
    // بنجمع البكسلات المتقاربة في نفس "الحتة اللونية" بدل ما نقارن كل بكسل
    // بقيمته بالظبط - غير كده أي ضغط/تدرج بسيط هيخلي كل بكسل تقريبًا قيمة
    // مختلفة شعرة عن التاني وميبقاش في حاجة "متكررة" أصلاً.
    private const val QUANTIZE_STEP = 16
    // حد أقصى لعدد النقط اللي بنعدّها - أسرع بكتير من مسح كل بكسل في صورة
    // كبيرة من غير ما يأثر على النتيجة عمليًا.
    private const val TARGET_SAMPLES = 150_000

    /**
     * أول حاجة بيشوفها: لو حواف الصورة الأربعة (فوق/تحت/يمين/شمال) كلها
     * أو أغلبها (55%+) نفس اللون، بيرجع لون الحواف ده على طول. لو لأ،
     * بيرجع أكتر لون متكرر في الصورة كلها (findMostFrequentColor).
     *
     * لازم تتنادى من غير الـ Main thread (بتمسح بكسلات الـ Bitmap).
     */
    fun findBackgroundColor(bitmap: Bitmap): Int {
        return findDominantEdgeColor(bitmap) ?: findMostFrequentColor(bitmap)
    }

    /**
     * لازم تتنادى من غير الـ Main thread (بتمسح بكسلات الـ Bitmap).
     */
    fun findMostFrequentColor(bitmap: Bitmap): Int {
        val width = bitmap.width
        val height = bitmap.height
        if (width == 0 || height == 0) return Color.BLACK

        val totalPixels = width.toLong() * height.toLong()
        val stride = maxOf(1, Math.sqrt(totalPixels.toDouble() / TARGET_SAMPLES).toInt())

        val counts = HashMap<Int, Int>()
        var sampledPixels = 0

        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                val pixel = bitmap.getPixel(x, y)
                // بنتجاهل البكسلات الشفافة اللي ممكن تكون على حواف صورة PNG
                // عشان ما تلخبطش الإحصاء بلون "فاضي".
                if (Color.alpha(pixel) >= 200) {
                    val bucket = quantize(pixel)
                    counts[bucket] = (counts[bucket] ?: 0) + 1
                    sampledPixels++
                }
                x += stride
            }
            y += stride
        }

        if (sampledPixels == 0 || counts.isEmpty()) return Color.BLACK

        val sortedByFrequency = counts.entries.sortedByDescending { it.value }
        val top = sortedByFrequency[0]
        val topColor = dequantize(top.key)
        val topShare = top.value.toFloat() / sampledPixels

        val shouldSkipBlack = isBlackish(topColor) &&
            topShare < BLACK_MIN_SHARE &&
            sortedByFrequency.size > 1

        return if (shouldSkipBlack) dequantize(sortedByFrequency[1].key) else topColor
    }

    private fun findDominantEdgeColor(bitmap: Bitmap): Int? {
        val width = bitmap.width
        val height = bitmap.height
        if (width < 2 || height < 2) return null

        val counts = HashMap<Int, Int>()
        var edgeSamples = 0

        fun sample(x: Int, y: Int) {
            val pixel = bitmap.getPixel(x, y)
            if (Color.alpha(pixel) >= 200) {
                val bucket = quantize(pixel)
                counts[bucket] = (counts[bucket] ?: 0) + 1
                edgeSamples++
            }
        }

        val strideX = maxOf(1, width / 100)
        val strideY = maxOf(1, height / 100)

        var x = 0
        while (x < width) {
            sample(x, 0)
            sample(x, height - 1)
            x += strideX
        }
        var y = 0
        while (y < height) {
            sample(0, y)
            sample(width - 1, y)
            y += strideY
        }

        if (edgeSamples == 0 || counts.isEmpty()) return null

        val top = counts.entries.maxByOrNull { it.value } ?: return null
        val topShare = top.value.toFloat() / edgeSamples

        return if (topShare >= EDGE_DOMINANT_MIN_SHARE) dequantize(top.key) else null
    }

    private fun isBlackish(color: Int): Boolean =
        ColorUtils.calculateLuminance(color) < BLACK_LUMINANCE_THRESHOLD

    private fun quantize(pixel: Int): Int {
        val r = (Color.red(pixel) / QUANTIZE_STEP) * QUANTIZE_STEP
        val g = (Color.green(pixel) / QUANTIZE_STEP) * QUANTIZE_STEP
        val b = (Color.blue(pixel) / QUANTIZE_STEP) * QUANTIZE_STEP
        return Color.rgb(r, g, b)
    }

    private fun dequantize(bucket: Int): Int {
        val half = QUANTIZE_STEP / 2
        val r = (Color.red(bucket) + half).coerceAtMost(255)
        val g = (Color.green(bucket) + half).coerceAtMost(255)
        val b = (Color.blue(bucket) + half).coerceAtMost(255)
        return Color.rgb(r, g, b)
    }
}
