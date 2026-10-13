package code.name.monkey.retromusic.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.bumptech.glide.util.Util.assertBackgroundThread


internal object MergedImageUtils {

    // حجم الصورة الناتجة (مربعة، والـ ImageView بيقصها لشكل الهيدر)
    private const val OUTPUT_SIZE = 1200
    private const val PARTS = 3
    private const val DEGREES = 45f

    // الفراغ بين الصور كنسبة من حجم الصورة الواحدة (زي الأصل: 50 على 533)
    private const val GAP_RATIO = 50f / 533f

    fun joinImages(list: List<Bitmap>): Bitmap {
        assertBackgroundThread()

        val arranged = arrangeBitmaps(list)

        // الشبكة 3x3 (بنفس الفراغات اللي بين الصور) بتتحط مايلة 45° ومتكبّرة بحيث الصورة
        // النهائية تتملي بالكامل (من غير أركان فاضية بعد الميل)
        val gridSize = OUTPUT_SIZE * Math.sqrt(2.0).toFloat()
        val tile = gridSize / (PARTS + GAP_RATIO * (PARTS - 1))
        val step = tile * (1f + GAP_RATIO)

        val result = Bitmap.createBitmap(OUTPUT_SIZE, OUTPUT_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        canvas.translate(OUTPUT_SIZE / 2f, OUTPUT_SIZE / 2f)
        canvas.rotate(DEGREES)
        canvas.translate(-gridSize / 2f, -gridSize / 2f)

        val dst = RectF()
        arranged.forEachIndexed { i, bitmap ->
            val left = step * (i % PARTS)
            val top = step * (i / PARTS)
            dst.set(left, top, left + tile, top + tile)
            canvas.drawBitmap(bitmap, null, dst, paint)
        }
        return result
    }

    private fun arrangeBitmaps(list: List<Bitmap>): List<Bitmap> {
        return when {
            list.size == 1 -> {
                val item = list[0]
                listOf(item, item, item, item, item, item, item, item, item)
            }
            list.size == 2 -> {
                val item1 = list[0]
                val item2 = list[1]
                listOf(item1, item2, item1, item2, item1, item2, item1, item2, item1)
            }
            list.size == 3 -> {
                val item1 = list[0]
                val item2 = list[1]
                val item3 = list[2]
                listOf(item1, item2, item3, item3, item1, item2, item2, item3, item1)
            }
            list.size == 4 -> {
                val item1 = list[0]
                val item2 = list[1]
                val item3 = list[2]
                val item4 = list[3]
                listOf(item1, item2, item3, item4, item1, item2, item3, item4, item1)
            }
            list.size < 9 -> { // 5 to 8
                val item1 = list[0]
                val item2 = list[1]
                val item3 = list[2]
                val item4 = list[3]
                val item5 = list[4]
                listOf(item1, item2, item3, item4, item5, item2, item3, item4, item1)
            }
            else -> list // case 9
        }
    }
}
