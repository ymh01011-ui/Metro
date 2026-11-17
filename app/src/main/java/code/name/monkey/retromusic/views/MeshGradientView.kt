package code.name.monkey.retromusic.views

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.graphics.ColorUtils
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Animated "mesh" gradient background (Apple Music style).
 * Call [setColors] with 2-4 colors; blobs drift slowly and colors cross-fade on change.
 */
class MeshGradientView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val evaluator = ArgbEvaluator()

    private var colors = intArrayOf(
        0xFF202020.toInt(), 0xFF303030.toInt(), 0xFF252525.toInt(), 0xFF181818.toInt()
    )
    private var shaders: List<RadialGradient> = emptyList()
    private var t = 0f
    private var colorAnim: ValueAnimator? = null

    private val loop = ValueAnimator.ofFloat(0f, (2 * PI).toFloat()).apply {
        duration = 25_000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            t = it.animatedValue as Float
            invalidate()
        }
    }

    fun setColors(newColors: IntArray) {
        if (newColors.isEmpty()) return
        val target = IntArray(newColors.size) { fixColor(newColors[it]) }
        val from = IntArray(target.size) { colors.getOrElse(it) { _ -> target[it] } }
        colorAnim?.cancel()
        colorAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 700
            addUpdateListener {
                val f = it.animatedFraction
                colors = IntArray(target.size) { i ->
                    evaluator.evaluate(f, from[i], target[i]) as Int
                }
                rebuildShaders()
            }
            start()
        }
    }

    // Darken light colors so white text/icons stay readable
    private fun fixColor(c: Int): Int {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(c, hsl)
        hsl[1] = hsl[1].coerceIn(0.35f, 0.9f)
        hsl[2] = hsl[2].coerceIn(0.15f, 0.40f)
        return ColorUtils.HSLToColor(hsl)
    }

    private fun rebuildShaders() {
        if (width == 0 || height == 0) return
        val r = max(width, height) * 0.75f
        shaders = colors.map {
            RadialGradient(0f, 0f, r, it, it and 0x00FFFFFF, Shader.TileMode.CLAMP)
        }
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildShaders()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        loop.start()
    }

    override fun onDetachedFromWindow() {
        loop.cancel()
        colorAnim?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(colors[0])
        val r = max(width, height) * 0.75f
        shaders.forEachIndexed { i, shader ->
            if (i == 0) return@forEachIndexed
            val p = t + i * 1.7f
            val cx = width * (0.5f + 0.4f * cos(p * (1f + i * 0.15f)))
            val cy = height * (0.5f + 0.4f * sin(p * (1f + i * 0.10f)))
            paint.shader = shader
            canvas.save()
            canvas.translate(cx, cy)
            canvas.drawCircle(0f, 0f, r, paint)
            canvas.restore()
        }
    }
}
