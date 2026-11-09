package code.name.monkey.retromusic.views

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.PathParser
import kotlin.math.min

private const val MAGNIFIER_PATH =
    "M15.5,14h-0.79l-0.28,-0.27C15.41,12.59 16,11.11 16,9.5 16,5.91 13.09,3 9.5,3S3,5.91 3,9.5 5.91,16 9.5,16c1.61,0 3.09,-0.59 4.23,-1.57l0.27,0.28v0.79l5,4.99L20.49,19l-4.99,-5zM9.5,14C7.01,14 5,11.99 5,9.5S7.01,5 9.5,5 14,7.01 14,9.5 11.99,14 9.5,14z"

/**
 * The magnifier shown inside the floating search bar (between the menu button and the text).
 * Added from code so every page that has the bar gets it, with no layout edits and no drawable
 * resource. Call [attach] once, then [tint] whenever the ink colour changes.
 */
object SearchBarIcon {
    private const val TAG = "metro_search_icon"

    /** Adds the magnifier right after [menuButton]. Safe to call again; does nothing if the layout differs. */
    fun attach(bar: ViewGroup, menuButton: View, searchText: View) {
        if (bar.findViewWithTag<ImageView>(TAG) != null) return
        val row = menuButton.parent as? LinearLayout ?: return
        if (row.orientation != LinearLayout.HORIZONTAL) return
        val density = bar.resources.displayMetrics.density

        val icon = ImageView(bar.context)
        icon.tag = TAG
        icon.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        icon.setImageDrawable(MagnifierDrawable())
        icon.layoutParams = LinearLayout.LayoutParams((24 * density).toInt(), (24 * density).toInt())
            .apply { marginStart = (2 * density).toInt() }
        row.addView(icon, row.indexOfChild(menuButton) + 1)

        // a little more room between the magnifier and the hint text
        (searchText.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
            it.marginStart = (10 * density).toInt()
            searchText.layoutParams = it
        }
    }

    /** Same ink as the bar's text (slightly see-through, like the text). */
    fun tint(bar: ViewGroup, color: Int) {
        (bar.findViewWithTag<ImageView>(TAG)?.drawable as? MagnifierDrawable)
            ?.setInk(ColorUtils.setAlphaComponent(color, 200))
    }
}

private class MagnifierDrawable : Drawable() {
    private val path = PathParser.createPathFromPathData(MAGNIFIER_PATH)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }

    fun setInk(color: Int) {
        if (paint.color != color) {
            paint.color = color
            invalidateSelf()
        }
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val scale = min(b.width(), b.height()) / 24f
        val save = canvas.save()
        canvas.translate(
            b.left + (b.width() - 24f * scale) / 2f,
            b.top + (b.height() - 24f * scale) / 2f
        )
        canvas.scale(scale, scale)
        canvas.drawPath(path, paint)
        canvas.restoreToCount(save)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
