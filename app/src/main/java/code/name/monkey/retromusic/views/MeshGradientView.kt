package code.name.monkey.retromusic.views

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Apple Music style animated mesh background.
 *
 * - [setColors]: 1..5 real colors from the cover. They are spread over the whole view
 *   (one base fill + soft blobs at different anchors), so every color is visible at once.
 * - Blobs drift on slow independent orbits. The motion only runs while music is playing
 *   ([setPlaying]) and freezes in place when paused.
 * - Switching songs cross-fades every color.
 */
class MeshGradientView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** Constant black scrim over the colors (Apple keeps the mesh a bit dimmed). 0 = off. */
    var scrimAlpha = 0.12f
        set(value) { field = value.coerceIn(0f, 0.8f); invalidate() }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val evaluator = ArgbEvaluator()

    private val slots = 5

    // Anchor (fraction of view) and orbit radius for each blob, plus orbit speeds (integers
    // => the 48s loop is seamless). Slot 0 is the base fill, slots 1..4 are the moving blobs.
    private val ax = floatArrayOf(0.50f, 0.22f, 0.80f, 0.25f, 0.78f)
    private val ay = floatArrayOf(0.50f, 0.20f, 0.30f, 0.80f, 0.72f)
    // Blob size (fraction of the longer side) and strength. Slots 1-2 hold the two most
    // important colors: big and strong. Slots 3-4 are shades / extra colors: smaller, softer.
    private val radius = floatArrayOf(0f, 0.85f, 0.70f, 0.60f, 0.55f)
    private val strength = floatArrayOf(0f, 0.95f, 0.95f, 0.50f, 0.50f)
    private val rx = floatArrayOf(0.00f, 0.20f, 0.18f, 0.22f, 0.16f)
    private val ry = floatArrayOf(0.00f, 0.12f, 0.16f, 0.14f, 0.18f)
    private val fx = intArrayOf(0, 1, 2, 1, 3)
    private val fy = intArrayOf(0, 2, 1, 3, 2)
    private val ph = floatArrayOf(0f, 0.0f, 1.9f, 3.6f, 5.0f)

    private var colors = IntArray(slots) { 0xFF1C1C1E.toInt() }
    private var shaders: Array<RadialGradient?> = arrayOfNulls(slots)
    private var angle = 0f
    private var playing = true
    private var colorAnim: ValueAnimator? = null

    private val loop = ValueAnimator.ofFloat(0f, (2 * PI).toFloat()).apply {
        duration = 48_000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            angle = it.animatedValue as Float
            invalidate()
        }
    }

    /** Pass 1..5 colors, most important first. Fades from the current colors. */
    fun setColors(newColors: IntArray) {
        if (newColors.isEmpty()) return
        val target = IntArray(slots) { newColors[it % newColors.size] }
        val from = colors.copyOf()
        colorAnim?.cancel()
        colorAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1100
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val f = it.animatedFraction
                colors = IntArray(slots) { i -> evaluator.evaluate(f, from[i], target[i]) as Int }
                rebuildShaders()
            }
            start()
        }
    }

    /** Drift while playing, freeze while paused. */
    fun setPlaying(isPlaying: Boolean) {
        playing = isPlaying
        applyPlayState()
    }

    private fun applyPlayState() {
        if (!isAttachedToWindow) return
        if (playing) {
            if (!loop.isStarted) loop.start() else if (loop.isPaused) loop.resume()
        } else {
            if (loop.isStarted && loop.isRunning) loop.pause()
        }
    }

    private fun rebuildShaders() {
        if (width == 0 || height == 0) return
        val side = max(width, height)
        // alpha falloff ~ (1 - d)^0.8 : strong core, soft edge
        val pos = floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f)
        val fall = floatArrayOf(1f, 0.794f, 0.574f, 0.330f, 0f)
        for (i in 1 until slots) {
            val c = colors[i]
            shaders[i] = RadialGradient(
                0f, 0f, side * radius[i],
                IntArray(5) { withAlpha(c, (fall[it] * strength[i] * 255).toInt()) },
                pos,
                Shader.TileMode.CLAMP
            )
        }
        invalidate()
    }

    private fun withAlpha(c: Int, a: Int) = (c and 0x00FFFFFF) or (a shl 24)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildShaders()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        loop.start()
        if (!playing) loop.pause()
    }

    override fun onDetachedFromWindow() {
        loop.cancel()
        colorAnim?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(colors[0])
        val w = width.toFloat()
        val h = height.toFloat()
        val side = max(w, h)
        // Draw the shade blobs first, the main colors last so they stay on top and visible
        for (i in slots - 1 downTo 1) {
            val shader = shaders[i] ?: continue
            val cx = w * (ax[i] + rx[i] * cos(angle * fx[i] + ph[i]))
            val cy = h * (ay[i] + ry[i] * sin(angle * fy[i] + ph[i] * 1.3f))
            paint.shader = shader
            canvas.save()
            canvas.translate(cx, cy)
            canvas.drawCircle(0f, 0f, side * radius[i], paint)
            canvas.restore()
        }
        if (scrimAlpha > 0f) {
            canvas.drawColor(Color.argb((scrimAlpha * 255).toInt(), 0, 0, 0))
        }
    }
}
