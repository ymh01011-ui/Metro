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
 *
 * Glass mode: the mini player pill does not fade away. It is pulled down over the tab bar
 * (the two glass pills fuse into one body), then it grows into the sheet and stays on screen
 * until the full player has faded in on top of it: the glass turns into the player.
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

    private class Span(
        private val from: Float,
        private val to: Float,
        /** true: constant speed, visible from the very first frame (no slow start) */
        private val linear: Boolean = false,
    ) {
        /** 0 before [from], 1 after [to], smooth (or linear) in between */
        fun at(p: Float): Float {
            val t = ((p - from) / (to - from)).coerceIn(0f, 1f)
            return if (linear) t else t * t * (3f - 2f * t)
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
     * Glass mode runs in two steps:
     *   1. 0 .. [glassExpand]: the glass pill is pulled over the tab bar (which dissolves in it) and
     *      grows, in width and in height, until it has exactly the size of the player behind it.
     *      The player is not visible yet.
     *   2. [glassExpand] .. [glassArrive]: the player appears on top, quickly. The glass is
     *      dropped once the player is opaque.
     */
    // Measured from the screenshot: at the point the player must be 100% there (and the tab bar
    // 100% gone) the sheet's top is at y = 1270 on a page whose rest position is y = 1329, and the
    // artwork has grown from 72px to 104px. Both give progress = 0.044 (about 58px of finger travel).
    private val glassArrive = 0.044f // player 100% here, tab bar 100% gone here
    private val glassExpand = 0.015f // the glass has taken the player's size, the player starts to show

    private val glassTimes = Times(
        miniOut = Span(0f, 0.012f),
        // linear: starts showing the instant the expansion is done and fades in evenly
        playerIn = Span(glassExpand, glassArrive, linear = true),
        controlsIn = Span(glassExpand + 0.008f, glassArrive),
        toolbarIn = Span(glassExpand + 0.015f, glassArrive),
        navFade = Span(0.008f, glassArrive), // gone exactly when the player is complete
        navSlide = Span(glassArrive, 0.20f), // moves away only once it is invisible
        backdropIn = Span(glassExpand, glassArrive, linear = true),
        cornerIn = Span(0f, glassExpand),
        cornerOut = Span(0.60f, 1f),
    )

    /** glass off: opaque panel */
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

    // the glass body (only needed until the player has taken over)
    private val bodyStretch = Span(0f, 0.012f) // pill is pulled down over the tab bar
    private val bodyGrow = Span(0f, glassExpand) // sides and top open up to the screen edges
    private val bodyFloor = Span(0.003f, glassExpand) // bottom goes down to the screen bottom
    private val bodyOut = Span(glassArrive - 0.01f, glassArrive) // gone under the player

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
        val t = if (host.panelGlass != null) glassTimes else plainTimes

        // mini player content leaves first
        host.mini?.let {
            val a = 1f - t.miniOut.at(p)
            it.alpha = a
            it.isVisible = a > 0f
        }

        // glass body: dissolves only once the player is on top of it
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

        // tab bar: dissolves into the glass body, then moves out of the way (and out of touch)
        host.nav?.let {
            it.alpha = 1f - t.navFade.at(p)
            it.translationY = t.navSlide.at(p) * (it.height + dp(24f))
        }

        // panel corners: square at both ends, round while the sheet travels
        setPanelCorner(dp(32f) * t.cornerIn.at(p) * (1f - t.cornerOut.at(p)))

        morphGlass(p)
        morphArtwork(p)
    }

    /** Gives the real artworks back (call when the activity goes away). */
    fun release() {
        restoreGlass()
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
        controls = root.findViewById(R.id.playbackControlsFragment)
        toolbar = root.findViewById(R.id.playerToolbar)
    }

    // ------------------------------------------------------------------ glass body

    private var bodyLayer: View? = null
    private var baseStart = 0
    private var baseEnd = 0
    private var baseTop = 0
    private var baseHeight = 0
    private var baseRadius = 0f
    private var navBottomScreen = 0f
    private val xy = IntArray(2)

    private fun screenY(view: View): Int {
        view.getLocationOnScreen(xy)
        return xy[1]
    }

    /**
     * The mini player pill becomes the sheet. Its top follows the panel (the finger); its bottom
     * is first pulled down onto the bottom edge of the tab bar pill, then slides to the bottom of
     * the screen; its sides open up to the screen edges. Because the pill and the tab bar pill
     * share the same width, the union of the two (once the gap is filled) looks like one body.
     */
    private fun morphGlass(p: Float) {
        val layer = host.panelGlass
        if (layer == null || p <= 0f) {
            restoreGlass()
            return
        }
        val lp = layer.layoutParams as? FrameLayout.LayoutParams ?: return
        if (bodyLayer !== layer) {
            restoreGlass()
            bodyLayer = layer
            baseStart = lp.marginStart
            baseEnd = lp.marginEnd
            baseTop = lp.topMargin
            baseHeight = if (lp.height > 0) lp.height else layer.height
            baseRadius = baseHeight / 2f
            val ref = host.navGlass ?: host.nav
            navBottomScreen = if (ref != null) (screenY(ref) + ref.height).toFloat()
            else panel.rootView.height.toFloat()
        }

        val grow = bodyGrow.at(p)
        val stretch = bodyStretch.at(p)
        val floor = bodyFloor.at(p)

        val panelTop = screenY(panel)
        val screenBottom = panel.rootView.height.toFloat()
        val bottomTarget = lerp(navBottomScreen, screenBottom, floor) - panelTop

        val top = lerp(baseTop.toFloat(), 0f, grow)
        val bottom = lerp((baseTop + baseHeight).toFloat(), bottomTarget, stretch)
        val height = max(baseHeight.toFloat(), bottom - top).toInt()
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
        // pill radius -> the sheet's radius, so the player that appears on top has the same corners
        GlassHelper.setCornerOverride(layer, lerp(baseRadius, dp(32f), grow))
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
    }
}
