package code.name.monkey.retromusic.views

import android.graphics.Outline
import android.graphics.Rect
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.view.isVisible
import code.name.monkey.retromusic.R
import com.google.android.material.card.MaterialCardView
import com.google.android.material.imageview.ShapeableImageView
import kotlin.math.abs
import kotlin.math.pow

/**
 * The ONE place that decides how the mini player turns into the full player (and back).
 *
 * Input : a single number, progress p (0 = mini player, 1 = full player), fed by the bottom sheet.
 * Output: every visual property that depends on it, written from here and nowhere else:
 *   - mini player content, glass pill, panel backdrop, full player, controls, toolbar, tab bar
 *   - panel corner radius
 *   - the artwork: one view that flies from the mini artwork to the big artwork
 *
 * Every element has its own time window ([Span]) on the same timeline, so the motion is
 * staggered the way Apple Music does it, and the whole thing is a pure function of p:
 * dragging, settling, reversing and interrupting all look the same.
 */
class NowPlayingChoreographer(
    private val panel: FrameLayout,
    private val host: Host,
) {

    interface Host {
        /** root view of the mini player (its text and buttons) */
        val mini: View?

        /** the rounded card that holds the mini player artwork */
        val miniCard: View?

        /** the container of the full player fragment */
        val player: View

        /** the ImageView of the big artwork of the page that is on screen */
        val bigArtwork: View?

        /** bottom tab bar, null when there is none (landscape rail) */
        val nav: View?

        /** the glass pill behind the mini player, null when glass is off */
        val panelGlass: View?

        /** true when the panel's own background has to fade in with p (glass mode) */
        val fadesPanelBackground: Boolean

        fun setPanelBackgroundAlpha(alpha: Int)
    }

    // ------------------------------------------------------------------ timeline

    private class Span(private val from: Float, private val to: Float) {
        /** 0 before [from], 1 after [to], smooth in between */
        fun at(p: Float): Float {
            val t = ((p - from) / (to - from)).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }
    }

    private val miniContentOut = Span(0f, 0.16f)
    private val pillOut = Span(0.04f, 0.30f)
    private val backdropIn = Span(0f, 0.22f)
    private val playerIn = Span(0.10f, 0.50f)
    private val controlsIn = Span(0.38f, 0.82f)
    private val toolbarIn = Span(0.55f, 0.95f)
    private val navFade = Span(0f, 0.35f)
    private val navSlide = Span(0f, 0.70f)
    private val cornerIn = Span(0f, 0.15f)
    private val cornerOut = Span(0.60f, 1f)

    private val density = panel.resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    // ------------------------------------------------------------------ panel shape

    private var panelCorner = 0f

    init {
        panel.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                val r = panelCorner
                if (r <= 0.5f) {
                    outline.setRect(0, 0, view.width, view.height)
                } else {
                    // extended downwards so only the top corners are round
                    outline.setRoundRect(0, 0, view.width, view.height + r.toInt() + 1, r)
                }
            }
        }
    }

    private fun setPanelCorner(radius: Float) {
        if (abs(radius - panelCorner) < 0.25f) return
        panelCorner = radius
        panel.clipToOutline = radius > 0.5f
        panel.invalidateOutline()
    }

    // ------------------------------------------------------------------ artwork

    private var outlineRadius = 0f
    private var outlineAlpha = 0f

    private val ghost = ImageView(panel.context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        visibility = View.GONE
        pivotX = 0f
        pivotY = 0f
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, outlineRadius)
                outline.alpha = outlineAlpha // strength of the shadow
            }
        }
    }

    private val from = Rect()
    private val to = Rect()
    private var fromRadius = 0f
    private var toRadius = 0f
    private var boundW = 0
    private var boundH = 0
    private var hiddenCard: View? = null
    private var hiddenBig: View? = null
    private var active = false

    // cached lookups inside the full player
    private var cachedPlayerRoot: View? = null
    private var controls: View? = null
    private var toolbar: View? = null

    // ------------------------------------------------------------------ public

    /** Writes the whole look for progress [raw] (values outside 0..1 are clamped). */
    fun render(raw: Float) {
        val p = raw.coerceIn(0f, 1f)

        // mini player content leaves first
        host.mini?.let {
            val a = 1f - miniContentOut.at(p)
            it.alpha = a
            it.isVisible = a > 0f
        }

        // glass pill dissolves while the panel backdrop takes over (they overlap: no see-through dip)
        host.panelGlass?.alpha = 1f - pillOut.at(p)
        if (host.fadesPanelBackground) {
            host.setPanelBackgroundAlpha((backdropIn.at(p) * 255f).toInt())
        }

        // full player: backdrop first, then controls, then toolbar
        host.player.alpha = playerIn.at(p)
        resolvePlayerViews()
        controls?.let {
            val c = controlsIn.at(p)
            it.alpha = c
            it.translationY = (1f - c) * dp(28f)
        }
        toolbar?.alpha = toolbarIn.at(p)

        // tab bar slides away and fades
        host.nav?.let {
            it.alpha = 1f - navFade.at(p)
            it.translationY = navSlide.at(p) * (it.height + dp(24f))
        }

        // panel corners: square at both ends, round while the sheet travels
        setPanelCorner(dp(32f) * cornerIn.at(p) * (1f - cornerOut.at(p)))

        morphArtwork(p)
    }

    /** Gives the real artworks back (call when the activity goes away). */
    fun release() {
        active = false
        ghost.visibility = View.GONE
        hiddenCard?.alpha = 1f
        hiddenBig?.alpha = 1f
        hiddenCard = null
        hiddenBig = null
    }

    // ------------------------------------------------------------------ internals

    private fun resolvePlayerViews() {
        val root = host.player
        if (root === cachedPlayerRoot && controls != null) return
        cachedPlayerRoot = root
        controls = root.findViewById(R.id.playbackControlsFragment)
        toolbar = root.findViewById(R.id.playerToolbar)
    }

    private fun morphArtwork(p: Float) {
        if (p <= 0f || p >= 1f) {
            release()
            return
        }
        val card = host.miniCard
        val image = host.bigArtwork
        val big = (image?.parent as? MaterialCardView) ?: image
        if (card == null || image == null || big == null ||
            card.width == 0 || big.width == 0 || big.height == 0
        ) {
            release()
            return
        }

        // measured live, every frame, in the panel's own coordinates: whatever else moves the
        // mini player or the page (glass fold, pager transforms) is already included
        from.set(0, 0, card.width, card.height)
        panel.offsetDescendantRectToMyCoords(card, from)
        to.set(0, 0, big.width, big.height)
        panel.offsetDescendantRectToMyCoords(big, to)

        if (!active || big !== hiddenBig || card !== hiddenCard ||
            to.width() != boundW || to.height() != boundH
        ) {
            if (!bind(card, image, big)) {
                release()
                return
            }
        }

        // the artwork runs slightly ahead of the sheet
        val e = 1f - (1f - p).pow(ART_LEAD)
        val w = lerp(from.width().toFloat(), to.width().toFloat(), e)
        val h = lerp(from.height().toFloat(), to.height().toFloat(), e)
        val sx = w / to.width()
        val sy = h / to.height()
        ghost.scaleX = sx
        ghost.scaleY = sy
        ghost.translationX = lerp(from.left.toFloat(), to.left.toFloat(), e)
        ghost.translationY = lerp(from.top.toFloat(), to.top.toFloat(), e)
        // the outline is scaled with the view: divide to get the radius that is actually seen
        outlineRadius = lerp(fromRadius, toRadius, e) / sx
        outlineAlpha = 0.55f * e
        ghost.invalidateOutline()
        // always above the mini player (GlassHelper gives it 9dp of elevation), shadow grows with e
        ghost.elevation = dp(BASE_Z_DP) + dp(8f) * e
    }

    private fun bind(card: View, image: View, big: View): Boolean {
        val miniImage = (card as? android.view.ViewGroup)?.getChildAt(0) as? ImageView
        val src = (image as? ImageView)?.drawable ?: miniImage?.drawable ?: return false

        // a different page / card than before: give the old ones back first
        if (hiddenBig !== big) hiddenBig?.alpha = 1f
        if (hiddenCard !== card) hiddenCard?.alpha = 1f

        fromRadius = cornerOf(card) ?: dp(9f)
        toRadius = cornerOf(big) ?: 0f
        boundW = to.width()
        boundH = to.height()

        ghost.setImageDrawable(src.constantState?.newDrawable(panel.resources)?.mutate() ?: src)
        val lp = (ghost.layoutParams as? FrameLayout.LayoutParams)
            ?: FrameLayout.LayoutParams(boundW, boundH)
        lp.width = boundW
        lp.height = boundH
        lp.gravity = Gravity.TOP or Gravity.START
        lp.setMargins(0, 0, 0, 0)
        if (ghost.parent == null) panel.addView(ghost, lp) else ghost.layoutParams = lp
        ghost.bringToFront()
        ghost.visibility = View.VISIBLE
        ghost.measure(
            View.MeasureSpec.makeMeasureSpec(boundW, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(boundH, View.MeasureSpec.EXACTLY)
        )
        ghost.layout(0, 0, boundW, boundH)

        card.alpha = 0f
        big.alpha = 0f
        hiddenCard = card
        hiddenBig = big
        active = true
        return true
    }

    private fun cornerOf(view: View): Float? = when (view) {
        is MaterialCardView -> view.radius
        is ShapeableImageView -> view.shapeAppearanceModel.topLeftCornerSize
            .getCornerSize(RectF(0f, 0f, view.width.toFloat(), view.height.toFloat()))
        else -> null
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    private companion object {
        /** > 1: the artwork leads the sheet a little. 1 = locked to it. */
        const val ART_LEAD = 1.2f

        /** must stay above the 9dp that GlassHelper.setMiniInsets gives the mini player */
        const val BASE_Z_DP = 14f
    }
}
