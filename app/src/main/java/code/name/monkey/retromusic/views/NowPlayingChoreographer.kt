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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The ONE place that decides how the mini player turns into the full player (and back).
 *
 * Input : a single number, progress p (0 = mini player, 1 = full player), fed by the bottom sheet.
 * Output: every visual property that depends on it, written from here and nowhere else.
 *
 * Glass mode (the important one):
 *   The full player is NOT a wide sheet that rises from the bottom. It lives inside ONE body
 *   whose shape at p = 0 is exactly the mini player pill (same size, same position, same
 *   corners). From the first pixel of the drag:
 *     - the body's top follows the finger (it is the sheet's top),
 *     - its bottom edge stretches downwards to the bottom of the screen,
 *     - its sides and corners open up to the full screen,
 *     - the tab bar is pushed down by the bottom edge, at the same speed, until it is off
 *       screen, so the two never touch,
 *     - the glass slowly turns into the player: the player and the panel colour fade in over
 *       it with a very soft curve, and the glass is dropped only when it is fully covered.
 *   The whole panel (background, glass, mini player, player, artwork) is clipped to that body,
 *   so nothing can ever show outside the pill shape.
 *
 * Plain mode (glass off) keeps the old behaviour: opaque panel, tab bar fades and slides away.
 *
 * Everything is a pure function of p: dragging, settling, reversing and interrupting all look
 * the same.
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

        /** the glass pill of the tab bar, null when there is none */
        val navGlass: View?

        /** true when the panel's own background has to fade in with p (glass mode) */
        val fadesPanelBackground: Boolean

        fun setPanelBackgroundAlpha(alpha: Int)
    }

    // ------------------------------------------------------------------ timeline

    private enum class Ease { LINEAR, SMOOTH, SMOOTHER }

    private class Span(
        private val from: Float,
        private val to: Float,
        private val ease: Ease = Ease.SMOOTH,
    ) {
        /** 0 before [from], 1 after [to], eased in between */
        fun at(p: Float): Float {
            val t = ((p - from) / (to - from)).coerceIn(0f, 1f)
            return when (ease) {
                Ease.LINEAR -> t
                Ease.SMOOTH -> t * t * (3f - 2f * t)
                // zero speed AND zero acceleration at both ends: the softest curve
                Ease.SMOOTHER -> t * t * t * (t * (t * 6f - 15f) + 10f)
            }
        }
    }

    private class Times(
        val miniOut: Span,
        val playerIn: Span,
        val controlsIn: Span,
        val toolbarIn: Span,
        val navFade: Span,
        val navSlide: Span,
        val backdropIn: Span,
        val cornerIn: Span,
        val cornerOut: Span,
    )

    /**
     * The sheet travels about 1200px, so p = 0.1 is only ~120px of finger movement.
     *
     * Glass mode, one continuous motion (all numbers are progress, not pixels):
     *   0 .. 0.60 : sides, top and corners of the body open up to the screen
     *   0 .. 0.22 : the bottom edge stretches down to the screen bottom (the tab bar is pushed
     *               down with it, 1:1)
     *   0.02 .. 0.34 : the player and the panel colour fade in over the glass (very soft curve)
     *   0.28 .. 0.42 : the glass is dropped, once it is completely covered
     */
    private val glassTimes = Times(
        miniOut = Span(0f, 0.09f),
        playerIn = Span(0.02f, 0.34f, Ease.SMOOTHER),
        controlsIn = Span(0.12f, 0.42f),
        toolbarIn = Span(0.18f, 0.44f),
        navFade = Span(0f, 0.05f), // only used when the tab bar is folded to a circle
        navSlide = Span(0f, 1f), // unused in glass mode (the tab bar is pushed by the body)
        backdropIn = Span(0.02f, 0.34f, Ease.SMOOTHER),
        cornerIn = Span(0f, 0.01f), // unused in glass mode (the body owns the corners)
        cornerOut = Span(0.80f, 1f),
    )

    /** glass off: opaque panel, everything is done a bit later */
    private val plainArrive = 0.20f

    private val plainTimes = Times(
        miniOut = Span(0f, 0.10f),
        playerIn = Span(0.02f, plainArrive),
        controlsIn = Span(0.06f, plainArrive),
        toolbarIn = Span(0.10f, plainArrive),
        navFade = Span(0f, plainArrive),
        navSlide = Span(plainArrive, 0.40f),
        backdropIn = Span(0f, plainArrive),
        cornerIn = Span(0f, 0.10f),
        cornerOut = Span(0.60f, 1f),
    )

    // the glass body
    private val bodyGrow = Span(0f, 0.60f) // sides, top and corners open up
    private val bodyStretch = Span(0f, 0.22f) // bottom edge goes down to the screen bottom
    private val bodyOut = Span(0.28f, 0.42f) // glass is dropped once the player covers it

    private val density = panel.resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    // ------------------------------------------------------------------ panel shape

    // plain mode: round top corners of the whole panel
    private var panelCorner = 0f

    // glass mode: the panel is clipped to the body (panel coordinates)
    private var bodyShape = false
    private var shapeLeft = 0
    private var shapeTop = 0
    private var shapeRight = 0
    private var shapeBottom = 0
    private var shapeRadius = 0f

    init {
        panel.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                if (bodyShape) {
                    val r = min(shapeRadius, min(shapeRight - shapeLeft, shapeBottom - shapeTop) / 2f)
                    if (r <= 0.5f) {
                        outline.setRect(shapeLeft, shapeTop, shapeRight, shapeBottom)
                    } else {
                        outline.setRoundRect(shapeLeft, shapeTop, shapeRight, shapeBottom, r)
                    }
                    return
                }
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
        if (!bodyShape) panel.clipToOutline = radius > 0.5f
        panel.invalidateOutline()
    }

    private fun applyBodyShape(left: Int, top: Int, right: Int, bottom: Int, radius: Float) {
        if (bodyShape && left == shapeLeft && top == shapeTop && right == shapeRight &&
            bottom == shapeBottom && abs(radius - shapeRadius) < 0.1f
        ) return
        shapeLeft = left
        shapeTop = top
        shapeRight = right
        shapeBottom = bottom
        shapeRadius = radius
        bodyShape = true
        panel.clipToOutline = true
        panel.invalidateOutline()
    }

    private fun clearBodyShape() {
        if (!bodyShape) return
        bodyShape = false
        panel.clipToOutline = panelCorner > 0.5f
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
        val glass = host.panelGlass != null
        val t = if (glass) glassTimes else plainTimes

        // mini player content leaves first
        host.mini?.let {
            val a = 1f - t.miniOut.at(p)
            it.alpha = a
            it.isVisible = a > 0f
        }

        // glass body: dissolves only once the player and the panel colour are on top of it
        host.panelGlass?.alpha = 1f - bodyOut.at(p)
        if (host.fadesPanelBackground) {
            host.setPanelBackgroundAlpha((t.backdropIn.at(p) * 255f).toInt())
        }

        // full player: backdrop first, then controls, then toolbar
        host.player.alpha = t.playerIn.at(p)
        resolvePlayerViews()
        controls?.let {
            val c = t.controlsIn.at(p)
            it.alpha = c
            it.translationY = (1f - c) * dp(28f)
        }
        toolbar?.alpha = t.toolbarIn.at(p)

        if (glass) {
            setPanelCorner(0f)
            val bodyBottom = morphGlass(p)
            pushNavGlass(p, bodyBottom)
        } else {
            restoreGlass()
            clearBodyShape()
            // tab bar: dissolves, then moves out of the way (and out of touch)
            host.nav?.let {
                it.alpha = 1f - t.navFade.at(p)
                it.translationY = t.navSlide.at(p) * (it.height + dp(24f))
            }
            // panel corners: square at both ends, round while the sheet travels
            setPanelCorner(dp(PANEL_CORNER_DP) * t.cornerIn.at(p) * (1f - t.cornerOut.at(p)))
        }

        morphArtwork(p)
    }

    /** Gives the real artworks back (call when the activity goes away). */
    fun release() {
        restoreGlass()
        clearBodyShape()
        releaseArtwork()
    }

    private fun releaseArtwork() {
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
        controls = root.findViewById<View>(R.id.playbackControlsFragment)
        toolbar = root.findViewById<View>(R.id.playerToolbar)
    }

    // ------------------------------------------------------------------ glass body

    private var bodyLayer: View? = null
    private var baseStart = 0
    private var baseEnd = 0
    private var baseTop = 0
    private var baseHeight = 0
    private var baseRadius = 0f

    /** where the bottom edge of the mini player pill is when the sheet is closed (screen y) */
    private var restBottomScreen = 0f
    private val xy = IntArray(2)

    private fun screenY(view: View): Int {
        view.getLocationOnScreen(xy)
        return xy[1]
    }

    /**
     * The mini player pill becomes the sheet.
     *  - top: follows the panel (the finger)
     *  - bottom: starts at the pill's bottom and stretches down to the bottom of the screen
     *  - sides / corners: open up from the pill to the full screen
     * The panel is clipped to the same shape, so the player is revealed inside the body.
     *
     * @return the screen y of the body's bottom edge, or null when there is no body
     */
    private fun morphGlass(p: Float): Float? {
        val layer = host.panelGlass
        if (layer == null || p <= 0f) {
            restoreGlass()
            clearBodyShape()
            return null
        }
        val lp = layer.layoutParams as? FrameLayout.LayoutParams ?: return null
        if (panel.width == 0) return null
        if (bodyLayer !== layer) {
            restoreGlass()
            bodyLayer = layer
            baseStart = lp.marginStart
            baseEnd = lp.marginEnd
            baseTop = lp.topMargin
            baseHeight = if (lp.height > 0) lp.height else layer.height
            baseRadius = baseHeight / 2f
        }

        val grow = bodyGrow.at(p)
        val stretch = bodyStretch.at(p)

        val parent = panel.parent as? View
        val parentTop = if (parent != null) screenY(parent) else 0
        val panelTop = screenY(panel)
        val screenBottom = panel.rootView.height.toFloat()

        // The sheet's top is (parentTop + restOffset * (1 - p)), so the closed position of the
        // pill can be recovered from the current one without remembering anything.
        val travelLeft = (1f - p).coerceAtLeast(0.1f)
        val restPanelTop = parentTop + (panelTop - parentTop) / travelLeft
        restBottomScreen = restPanelTop + baseTop + baseHeight

        // the bottom edge goes past the screen bottom a little, so its round corners are
        // never visible once it has arrived
        val bottomScreen = lerp(
            restBottomScreen,
            screenBottom + dp(BOTTOM_OVERSHOOT_DP),
            stretch
        )
        val bottomRel = bottomScreen - panelTop

        val top = lerp(baseTop.toFloat(), 0f, grow)
        val height = max(baseHeight.toFloat(), bottomRel - top).toInt()
        val start = lerp(baseStart.toFloat(), 0f, grow).toInt()
        val end = lerp(baseEnd.toFloat(), 0f, grow).toInt()
        val topPx = top.toInt()

        if (lp.marginStart != start || lp.marginEnd != end ||
            lp.topMargin != topPx || lp.height != height
        ) {
            lp.marginStart = start
            lp.marginEnd = end
            lp.topMargin = topPx
            lp.height = height
            layer.layoutParams = lp
        }

        // pill radius -> the sheet's radius -> square at the very end
        val radius = lerp(baseRadius, dp(PANEL_CORNER_DP), grow) * (1f - glassTimes.cornerOut.at(p))
        GlassHelper.setCornerOverride(layer, radius)

        if (p >= 1f) {
            clearBodyShape()
        } else {
            applyBodyShape(start, topPx, panel.width - end, topPx + height, radius)
        }
        return bottomScreen
    }

    /**
     * The tab bar is pushed down by the bottom edge of the body: same speed, never closer than
     * the gap the layout gives it (at most [NAV_GAP_DP]), until it has left the screen.
     */
    private fun pushNavGlass(p: Float, bodyBottom: Float?) {
        val nav = host.nav ?: return
        if (bodyBottom == null || p <= 0f) {
            nav.translationY = 0f
            nav.alpha = 1f
            return
        }
        val ref = host.navGlass ?: nav
        // where the bar would be without the push
        val navTop = screenY(ref) - nav.translationY
        val layoutGap = navTop - restBottomScreen
        val gap = min(layoutGap, dp(NAV_GAP_DP))
        nav.translationY = (bodyBottom + gap - navTop).coerceAtLeast(0f)

        // folded into a circle, the bar sits in the same row as the pill: it just fades
        val folded = nav.width > 0 && ref.width < nav.width / 2
        nav.alpha = if (folded) 1f - glassTimes.navFade.at(p) else 1f
    }

    /** Back to the pill the layout gave it (and to applyCompact's control). */
    private fun restoreGlass() {
        val layer = bodyLayer ?: return
        bodyLayer = null
        GlassHelper.setCornerOverride(layer, null)
        val lp = layer.layoutParams as? FrameLayout.LayoutParams ?: return
        lp.marginStart = baseStart
        lp.marginEnd = baseEnd
        lp.topMargin = baseTop
        lp.height = baseHeight
        layer.layoutParams = lp
    }

    private fun morphArtwork(p: Float) {
        if (p <= 0f || p >= 1f) {
            releaseArtwork()
            return
        }
        val card = host.miniCard
        val image = host.bigArtwork
        val big = (image?.parent as? MaterialCardView) ?: image
        if (card == null || image == null || big == null ||
            card.width == 0 || big.width == 0 || big.height == 0
        ) {
            releaseArtwork()
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
                releaseArtwork()
                return
            }
        }

        // the artwork grows steadily with the sheet and lands exactly when the sheet does
        val t = (p / ART_END).coerceIn(0f, 1f)
        val e = 1f - (1f - t).pow(ART_EASE)
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
        /** progress at which the artwork has reached the big artwork's place (1 = with the sheet) */
        const val ART_END = 1f

        /** 1 = constant speed; a little more = starts a bit quicker, settles gently */
        const val ART_EASE = 1.25f

        /** must stay above the 9dp that GlassHelper.setMiniInsets gives the mini player */
        const val BASE_Z_DP = 14f

        /** corner radius of the open sheet (before it goes square at p = 1) */
        const val PANEL_CORNER_DP = 32f

        /** how far the body's bottom edge goes below the screen (must be >= the corner radius) */
        const val BOTTOM_OVERSHOOT_DP = 40f

        /** the closest the tab bar pill may get to the body while it is pushed away */
        const val NAV_GAP_DP = 6f
    }
}
