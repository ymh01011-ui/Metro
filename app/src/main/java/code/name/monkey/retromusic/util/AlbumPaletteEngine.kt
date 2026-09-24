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
    // بنجمع البكسلات المتقاربة في نفس "الحتة اللونية" بدل ما نقارن كل بكسل
    // بقيمته بالظبط - غير كده أي ضغط/تدرج بسيط هيخلي كل بكسل تقريبًا قيمة
    // مختلفة شعرة عن التاني وميبقاش في حاجة "متكررة" أصلاً.
    private const val QUANTIZE_STEP = 16
    // حد أقصى لعدد النقط اللي بنعدّها - أسرع بكتير من مسح كل بكسل في صورة
    // كبيرة من غير ما يأثر على النتيجة عمليًا.
    private const val TARGET_SAMPLES = 150_000

    /**
     * لازم تتنادى من غير الـ Main thread (بتمسح بكسلات الـ Bitmap).
     */
    fun findMostFrequentColor(bitmap: Bitmap): Int {
        val width = bitmap.width
        val height = bitmap.height
        if (width == 0 || height == 0) return Color.BLACK

        // قراءة كل بكسلات الصورة دفعة واحدة (نسخة native واحدة) بدل ما ننده
        // getPixel() لكل بكسل لوحده - ده كان سبب البطء الحقيقي اللي زوّد وقت
        // فتح الصفحة: كل نداية getPixel() بتعدي حدود الـ JNI بمفردها، فـ 150
        // ألف نداية منفصلة كانت أبطأ بمراحل من نسخة واحدة للصورة كلها زي
        // اللي بتستخدمها صفحة الفنان (بتاخد عينة من منطقة صغيرة بس أصلاً).
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val totalPixels = width.toLong() * height.toLong()
        val stride = maxOf(1, Math.sqrt(totalPixels.toDouble() / TARGET_SAMPLES).toInt())

        val counts = HashMap<Int, Int>()
        var sampledPixels = 0

        var y = 0
        while (y < height) {
            val rowOffset = y * width
            var x = 0
            while (x < width) {
                val pixel = pixels[rowOffset + x]
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
