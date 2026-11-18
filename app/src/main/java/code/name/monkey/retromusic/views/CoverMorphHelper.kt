package code.name.monkey.retromusic.views

import android.graphics.Outline
import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import com.google.android.material.card.MaterialCardView
import kotlin.math.pow

/**
 * Apple-Music-style morph: while the sliding panel is dragged, a "ghost" ImageView travels
 * from the mini player's artwork to the big artwork of the player (position, size, corner
 * radius and shadow all follow the sheet's slideOffset). Both real artworks are hidden while
 * the ghost is on screen, and swapped back the moment the sheet settles.
 */
class CoverMorphHelper(
    private val panel: FrameLayout,
    /** the small rounded card that holds the mini player artwork (R.id.imageTextContainer) */
    private val miniCard: () -> View?,
    /** the big artwork ImageView of the current page in the player */
    private val bigCover: () -> View?,
) {
    companion object {
        /** > 1 makes the artwork run slightly ahead of the sheet (iOS feel). 1 = locked to the sheet. */
        private const val LEAD = 1.2f
        private const val SHADOW_DP = 14f
    }

    private val density = panel.resources.displayMetrics.density
    private var outlineRadius = 0f

    private val ghost = ImageView(panel.context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        visibility = View.GONE
        pivotX = 0f
        pivotY = 0f
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, outlineRadius)
                outline.alpha = 1f // lets the elevation cast a shadow
            }
        }
    }

    private val from = Rect()
    private val to = Rect()
    private var fromRadius = 0f
    private var toRadius = 0f
    private var active = false
    private var hiddenMini: View? = null
    private var hiddenBig: View? = null

    /** Call from BottomSheetCallback.onSlide */
    fun onSlide(progress: Float) {
        if (progress <= 0f || progress >= 1f) {
            finish()
            return
        }
        if (!active) {
            if (!prepare()) return
            active = true
        }
        render(1f - (1f - progress).pow(LEAD))
    }

    /** Call when the sheet is EXPANDED / COLLAPSED / HIDDEN (and on resume if needed) */
    fun finish() {
        if (!active) return
        active = false
        ghost.visibility = View.GONE
        hiddenMini?.alpha = 1f
        hiddenBig?.alpha = 1f
        hiddenMini = null
        hiddenBig = null
    }

    private fun prepare(): Boolean {
        val card = miniCard() ?: return false
        val big = bigCover() ?: return false
        if (card.width == 0 || big.width == 0 || big.height == 0) return false

        from.set(0, 0, card.width, card.height)
        panel.offsetDescendantRectToMyCoords(card, from)
        to.set(0, 0, big.width, big.height)
        panel.offsetDescendantRectToMyCoords(big, to)

        fromRadius = (card as? MaterialCardView)?.radius ?: (9f * density)
        toRadius = (big.parent as? MaterialCardView)?.radius ?: 0f

        val miniImage = (card as? android.view.ViewGroup)?.getChildAt(0) as? ImageView
        val src = (big as? ImageView)?.drawable ?: miniImage?.drawable ?: return false
        ghost.setImageDrawable(src.constantState?.newDrawable(panel.resources)?.mutate() ?: src)

        val w = to.width()
        val h = to.height()
        val lp = (ghost.layoutParams as? FrameLayout.LayoutParams) ?: FrameLayout.LayoutParams(w, h)
        lp.width = w
        lp.height = h
        lp.gravity = Gravity.TOP or Gravity.START
        lp.setMargins(0, 0, 0, 0)
        if (ghost.parent == null) panel.addView(ghost, lp) else ghost.layoutParams = lp
        ghost.bringToFront() // above the mini player, the player and the glass layers
        ghost.visibility = View.VISIBLE
        ghost.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
        )
        ghost.layout(0, 0, w, h)

        hiddenMini = card.also { it.alpha = 0f }
        hiddenBig = big.also { it.alpha = 0f }
        return true
    }

    private fun render(e: Float) {
        val w = lerp(from.width().toFloat(), to.width().toFloat(), e)
        val h = lerp(from.height().toFloat(), to.height().toFloat(), e)
        val sx = w / to.width()
        val sy = h / to.height()
        ghost.scaleX = sx
        ghost.scaleY = sy
        ghost.translationX = lerp(from.left.toFloat(), to.left.toFloat(), e)
        ghost.translationY = lerp(from.top.toFloat(), to.top.toFloat(), e)
        // the outline is scaled together with the view, so divide to get the visual radius
        outlineRadius = lerp(fromRadius, toRadius, e) / sx
        ghost.invalidateOutline()
        ghost.elevation = lerp(0f, SHADOW_DP * density, e)
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
}
