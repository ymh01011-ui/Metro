package code.name.monkey.retromusic.util

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import com.bumptech.glide.util.Util.assertBackgroundThread

internal object MergedImageUtils {

    const val DEFAULT_SIZE = 1200

    // شبكة 5x5 بتتحط مايلة 45° وبتغطي الصورة بالكامل (من غير أركان فاضية)
    private const val GRID = 5
    private const val DEGREES = 45f

    // حجم الصورة الواحدة كنسبة من عرض الصورة النهائية
    private const val TILE_FRACTION = 0.30f

    // الفراغ بين الصور، ونصف قطر الحواف الدائرية، كنسبة من حجم الصورة الواحدة
    private const val GAP_RATIO = 0.12f
    private const val CORNER_RATIO = 0.15f

    // حجم الصورة الواحدة بالبكسل لحجم نهائي معين (بيستخدمه اللي بيجيب الأغلفة)
    fun tileSizeFor(outputSize: Int): Int =
        (outputSize.coerceIn(128, 1600) * TILE_FRACTION).toInt().coerceAtLeast(64)

    fun joinImages(list: List<Bitmap>, outputSize: Int = DEFAULT_SIZE): Bitmap {
        assertBackgroundThread()

        val size = outputSize.coerceIn(128, 1600)
        val result = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        if (list.isEmpty()) return result

        val canvas = Canvas(result)
        val tile = size * TILE_FRACTION
        val gap = tile * GAP_RATIO
        val step = tile + gap
        val gridExtent = GRID * tile + (GRID - 1) * gap
        val radius = tile * CORNER_RATIO

        canvas.translate(size / 2f, size / 2f)
        canvas.rotate(DEGREES)
        canvas.translate(-gridExtent / 2f, -gridExtent / 2f)

        // الصور المتجاورة (أفقيًا ورأسيًا) لازم تبقى مختلفة قدر الإمكان
        val n = list.size
        val rowShift = when {
            n <= 2 -> 1
            n < 5 -> 2
            else -> 3
        }

        val dst = RectF()
        val matrix = Matrix()
        for (row in 0 until GRID) {
            for (col in 0 until GRID) {
                val bitmap = list[(col + rowShift * row) % n]
                val left = step * col
                val top = step * row
                dst.set(left, top, left + tile, top + tile)

                // رسم بـ BitmapShader علشان الحواف الدائرية تطلع ناعمة (anti-aliased)
                val shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                matrix.reset()
                matrix.setScale(tile / bitmap.width, tile / bitmap.height)
                matrix.postTranslate(left, top)
                shader.setLocalMatrix(matrix)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                paint.shader = shader
                canvas.drawRoundRect(dst, radius, radius, paint)
            }
        }
        return result
    }
}
