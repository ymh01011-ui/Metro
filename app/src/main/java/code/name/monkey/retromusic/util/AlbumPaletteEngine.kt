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
    // بنصغّر الصورة لمربع بالحجم ده قبل ما نحسب الألوان، بدل ما نتعامل مع
    // حجمها الأصلي (ممكن يوصل 2048×2048). ده هو السبب الحقيقي اللي كان
    // لسه بيبوّظ سرعة الفتح حتى بعد استخدام getPixels() دفعة واحدة: كنا
    // بنعمل allocate لـ array بـ4 مليون خانة (16 ميجا) وننسخها كل مرة
    // بيتفتح فيها ألبوم جديد - أتقل بكتير من العينة الصغيرة اللي بتاخدها
    // صفحة الفنان من جزء من الصورة بس. التصغير نفسه عملية رسم واحدة سريعة
    // ومحسّنة من النظام (مش loop بتاعنا)، وبعده بنمسح الصورة المصغّرة كلها.
    private const val THUMBNAIL_SIZE = 120

    /**
     * لازم تتنادى من غير الـ Main thread (بتعمل تصغير ومسح بكسلات).
     */
    fun findMostFrequentColor(bitmap: Bitmap): Int {
        val width = bitmap.width
        val height = bitmap.height
        if (width == 0 || height == 0) return Color.BLACK

        val thumbnail = try {
            Bitmap.createScaledBitmap(bitmap, THUMBNAIL_SIZE, THUMBNAIL_SIZE, true)
        } catch (e: Exception) {
            return Color.BLACK
        }

        val thumbWidth = thumbnail.width
        val thumbHeight = thumbnail.height
        val pixels = IntArray(thumbWidth * thumbHeight)
        thumbnail.getPixels(pixels, 0, thumbWidth, 0, 0, thumbWidth, thumbHeight)
        if (thumbnail !== bitmap) {
            thumbnail.recycle()
        }

        val counts = HashMap<Int, Int>()
        var sampledPixels = 0

        for (pixel in pixels) {
            // بنتجاهل البكسلات الشفافة اللي ممكن تكون على حواف صورة PNG
            // عشان ما تلخبطش الإحصاء بلون "فاضي".
            if (Color.alpha(pixel) >= 200) {
                val bucket = quantize(pixel)
                counts[bucket] = (counts[bucket] ?: 0) + 1
                sampledPixels++
            }
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
