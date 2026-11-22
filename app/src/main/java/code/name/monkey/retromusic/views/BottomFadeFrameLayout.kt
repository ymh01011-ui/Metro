package code.name.monkey.retromusic.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.util.AttributeSet
import android.widget.FrameLayout
import kotlin.math.min

/**
 * FrameLayout whose content fades out to transparent over its bottom [fadeHeightDp], so a
 * full-bleed cover (image or video) melts into whatever is drawn behind it.
 */
class BottomFadeFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    /** Height of the fade zone. Bigger = softer, longer blend. */
    var fadeHeightDp: Float = 200f
        set(value) {
            field = value
            rebuildMask()
            invalidate()
        }

    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildMask()
    }

    private fun rebuildMask() {
        val h = height.toFloat()
        if (h <= 0f) return
        val fade = min(fadeHeightDp * resources.displayMetrics.density, h)
        // Eased fade (not linear) so there is no visible "line" where it starts.
        maskPaint.shader = LinearGradient(
            0f,
            h - fade,
            0f,
            h,
            intArrayOf(
                0xFFFFFFFF.toInt(),
                0xEBFFFFFF.toInt(),
                0x96FFFFFF.toInt(),
                0x32FFFFFF.toInt(),
                0x00FFFFFF,
            ),
            floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f),
            Shader.TileMode.CLAMP,
        )
    }

    override fun dispatchDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val saved = canvas.saveLayer(0f, 0f, w, h, null)
        super.dispatchDraw(canvas)
        canvas.drawRect(0f, 0f, w, h, maskPaint)
        canvas.restoreToCount(saved)
    }
}
