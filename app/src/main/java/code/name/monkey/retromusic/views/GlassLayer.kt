package code.name.monkey.retromusic.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.os.Build
import android.view.ViewGroup
import android.widget.FrameLayout
import kotlin.math.ceil

/**
 * A pill of real-time glass.
 *
 * Instead of copying the page into a bitmap (what made the old blur lag behind and show parts of
 * the previous page during transitions), the page behind is drawn straight into a GPU RenderNode,
 * blurred and colour-boosted by a RenderEffect, and drawn inside the pill. It is therefore always
 * exactly what is on screen in that very frame. Needs Android 12 (API 31); below that the pill is
 * a flat translucent surface.
 */
class GlassLayer(context: Context) : FrameLayout(context) {

    /** The view whose content shows through the glass (it must not contain this layer). */
    var backdrop: ViewGroup? = null

    /** false = no blur at all (flat pill), used by the "Fastest" material and below Android 12. */
    var live = true

    /** Blur strength in px (sigma). */
    var blurSigmaPx = 8f

    /** 1 = untouched colours, higher = more vivid (Square's "Colour"). */
    var saturation = 1.5f

    /** Veil painted over the blurred page (Square's "Film"). */
    var filmColor = Color.TRANSPARENT

    private var node: RenderNode? = null
    private var effect: RenderEffect? = null
    private var effectSigma = -1f
    private var effectSaturation = -1f
    private val here = IntArray(2)
    private val there = IntArray(2)

    init {
        setWillNotDraw(false)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            drawGlass(canvas)
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.S)
    private fun drawGlass(canvas: Canvas) {
        val root = backdrop
        if (live && root != null && root.isAttachedToWindow && canvas.isHardwareAccelerated &&
            width > 0 && height > 0
        ) {
            // extra border so the blur has real neighbours at the pill's edges
            val margin = ceil(blurSigmaPx * 3f).toInt()
            val w = width + 2 * margin
            val h = height + 2 * margin

            val n = node ?: RenderNode("metro-glass").also { node = it }
            n.setPosition(0, 0, w, h)
            n.setRenderEffect(currentEffect())

            getLocationInWindow(here)
            root.getLocationInWindow(there)
            val recording = n.beginRecording(w, h)
            try {
                recording.translate(
                    (margin + there[0] - here[0]).toFloat(),
                    (margin + there[1] - here[1]).toFloat()
                )
                root.draw(recording)
            } finally {
                n.endRecording()
            }

            canvas.save()
            canvas.translate(-margin.toFloat(), -margin.toFloat())
            canvas.drawRenderNode(n)
            canvas.restore()
        }
        if (live && filmColor != Color.TRANSPARENT) canvas.drawColor(filmColor)

        // the page behind keeps moving: draw again on the next frame
        if (live) postInvalidateOnAnimation()
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.S)
    private fun currentEffect(): RenderEffect {
        val sigma = blurSigmaPx.coerceAtLeast(0.5f)
        var current = effect
        if (current == null || effectSigma != sigma || effectSaturation != saturation) {
            val blur = RenderEffect.createBlurEffect(sigma, sigma, Shader.TileMode.CLAMP)
            val colours = ColorMatrix()
            colours.setSaturation(saturation)
            current = RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(colours), blur)
            effect = current
            effectSigma = sigma
            effectSaturation = saturation
        }
        return current
    }
}
