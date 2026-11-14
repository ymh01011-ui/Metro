package code.name.monkey.retromusic.views

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.graphics.Rect
import android.graphics.RenderNode
import android.graphics.Shader
import android.os.Build
import android.os.SystemClock
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors
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

    /**
     * Painted under the page before it is blurred. Needed when the backdrop view itself has no
     * background (a bare list): its gaps are transparent, so the blurred copy would be partly
     * transparent and the sharp page would show through the glass.
     */
    var baseColor = Color.TRANSPARENT

    /** 1 = full film strength; below 1 = less veil (the search bar uses a lighter one) */
    var filmFactor = 1f

    /**
     * true = the blur reads only the area right under the pill and mirrors its edges, so nothing
     * from the page just above or below the pill leaks into it (used by the search bar).
     * false = a border of 3x the blur strength around the pill is read as well, so the pill's own
     * edges have real neighbours (bottom bar, mini player, popups).
     */
    var contained = false

    /**
     * true for glass that lives in its own window (popup menus): the backdrop is then in another
     * window, so positions are compared on the screen instead of inside the window.
     */
    var screenCoords = false

    /**
     * true while this layer is faded in and out by its own alpha (popup menus). It then skips the
     * offscreen copy a fade of a blurred view normally needs, and waits until it is fully shown
     * before reading the brightness behind it, so neither costs a frame during the animation.
     */
    var cheapFade = false

    override fun hasOverlappingRendering(): Boolean = !cheapFade

    /**
     * null = tone follows the app theme (light mode -> dark ink, dark mode -> light ink).
     * a colour = the page behind this pill is painted with it (artist / album pages), so the tone
     * is decided from that colour directly, up front. Nothing is measured from the screen.
     */
    var pageColor: Int? = null
        set(value) {
            if (field == value) return
            field = value
            syncTone(force = true)
            invalidate()
        }

    /**
     * true = when [pageColor] is not set, this layer takes the colour of the page that is
     * currently open (see [setPage]) if that page is painted from an image (artist / album).
     * Used by the shared pieces that sit over every page: bottom bar, mini player, popup menus.
     */
    var followsPage = false
        set(value) {
            if (field == value) return
            field = value
            syncTone(force = true)
            invalidate()
        }

    private var seenPageVersion = -1

    /** true once the tone (light or dark ink) has been decided at least once */
    var sampled = false
        private set

    /** true when the pill sits over a bright page: its content should then be dark */
    var onLight = false
        private set

    /**
     * 0 = over a dark page (light ink), 1 = over a bright page (dark ink). It glides between the
     * two instead of jumping, so the content never flashes from one colour to the other.
     */
    var lightness = 0f
        private set

    /** the ink to use over this glass right now, or null until the first measurement */
    val inkColor: Int?
        get() = if (!sampled) null else ColorUtils.blendARGB(Color.WHITE, DARK_INK, lightness)

    /** called on every step of the glide (used by GlassHelper) */
    var onToneChanged: ((Float) -> Unit)? = null

    /** called on every step of the glide (used by the activity for the mini player) */
    var onToneExtra: ((Float) -> Unit)? = null

    private var toneAnimator: ValueAnimator? = null

    private var lastSample = 0L

    private var node: RenderNode? = null
    private var effect: RenderEffect? = null
    private var effectSigma = -1f
    private var effectSaturation = -1f
    private var effectMode: Shader.TileMode? = null
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
        syncTone(force = false)
    }

    /**
     * Decides whether the content over the pill is dark or light: from [pageColor] when the page
     * has its own colour, otherwise from the app theme. Nothing is read back from the screen.
     */
    private fun syncTone(force: Boolean) {
        val now = SystemClock.uptimeMillis()
        val pageChanged = followsPage && seenPageVersion != pageVersion
        if (!force && !pageChanged && sampled && now - lastSample < 1000) return
        lastSample = now
        seenPageVersion = pageVersion
        val page = pageColor ?: if (followsPage) currentPageColor else null
        val light = if (page != null) {
            ColorUtils.calculateLuminance(ColorUtils.setAlphaComponent(page, 255)) > PAGE_LIGHT_THRESHOLD
        } else {
            val surface = MaterialColors.getColor(
                context, com.google.android.material.R.attr.colorSurface, Color.BLACK
            )
            ColorUtils.calculateLuminance(surface) >= 0.5
        }
        val first = !sampled
        sampled = true
        if (first || light != onLight) {
            onLight = light
            glideTo(if (light) 1f else 0f, animate = !first)
        }
    }

    /**
     * Sets the tone before this layer is shown, so a popup opens already in the right tone.
     * [area] is kept only so existing callers still compile; [done] is called right away.
     */
    fun prime(area: Rect, done: () -> Unit) {
        syncTone(force = true)
        done()
    }

    private fun glideTo(target: Float, animate: Boolean) {
        toneAnimator?.cancel()
        if (!animate) {
            setTone(target)
            return
        }
        toneAnimator = ValueAnimator.ofFloat(lightness, target).apply {
            duration = 450
            addUpdateListener { setTone(it.animatedValue as Float) }
            start()
        }
    }

    private fun setTone(value: Float) {
        lightness = value
        onToneChanged?.invoke(value)
        onToneExtra?.invoke(value)
    }

    override fun onDetachedFromWindow() {
        toneAnimator?.cancel()
        super.onDetachedFromWindow()
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.S)
    private fun drawGlass(canvas: Canvas) {
        val root = backdrop
        if (live && root != null && root.isAttachedToWindow && canvas.isHardwareAccelerated &&
            width > 0 && height > 0
        ) {
            // extra border so the blur has real neighbours at the pill's edges
            val margin = if (contained) 0 else ceil(blurSigmaPx * 3f).toInt()
            val w = width + 2 * margin
            val h = height + 2 * margin

            val n = node ?: RenderNode("metro-glass").also { node = it }
            n.setPosition(0, 0, w, h)
            n.setRenderEffect(currentEffect())

            if (screenCoords) {
                getLocationOnScreen(here)
                root.getLocationOnScreen(there)
            } else {
                getLocationInWindow(here)
                root.getLocationInWindow(there)
            }
            val recording = n.beginRecording(w, h)
            try {
                if (baseColor != Color.TRANSPARENT) recording.drawColor(baseColor)
                // root.draw() does not apply root's own scroll (the parent does that), so a
                // scrolling backdrop such as a NestedScrollView needs it added back here
                recording.translate(
                    (margin + there[0] - here[0]).toFloat() - root.scrollX,
                    (margin + there[1] - here[1]).toFloat() - root.scrollY
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
        // over a bright page the glass gets a touch darker so it stays readable; it follows
        // `lightness`, so it fades in and out together with the ink colour
        if (live && lightness > 0f) {
            canvas.drawColor(Color.argb((lightness * LIGHT_VEIL_ALPHA).toInt(), 0, 0, 0))
        }

        // the page behind keeps moving: draw again on the next frame
        if (live) postInvalidateOnAnimation()
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.S)
    private fun currentEffect(): RenderEffect {
        val sigma = blurSigmaPx.coerceAtLeast(0.5f)
        var current = effect
        val mode = if (contained) Shader.TileMode.MIRROR else Shader.TileMode.CLAMP
        if (current == null || effectSigma != sigma || effectSaturation != saturation || effectMode != mode) {
            val blur = RenderEffect.createBlurEffect(sigma, sigma, mode)
            val colours = ColorMatrix()
            colours.setSaturation(saturation)
            current = RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(colours), blur)
            effect = current
            effectSigma = sigma
            effectSaturation = saturation
            effectMode = mode
        }
        return current
    }

    companion object {
        /** colour of the open page when it is painted from an image (artist / album), else null */
        @Volatile
        var currentPageColor: Int? = null
            private set

        /** goes up on every change, so layers that follow the page notice it on their next draw */
        @Volatile
        private var pageVersion = 0
        private var pageOwner: java.lang.ref.WeakReference<Any>? = null

        /** An image-coloured page (artist / album) announces its colour while it is on screen. */
        fun setPage(owner: Any, color: Int) {
            pageOwner = java.lang.ref.WeakReference(owner)
            if (currentPageColor != color) {
                currentPageColor = color
                pageVersion++
            }
        }

        /** Only the page that announced the colour can clear it (so page -> page can't clash). */
        fun clearPage(owner: Any) {
            if (pageOwner?.get() !== owner) return
            pageOwner = null
            if (currentPageColor != null) {
                currentPageColor = null
                pageVersion++
            }
        }

        val DARK_INK: Int = Color.parseColor("#1C1B1F")
        /** strength (0..255) of the dark veil over a bright page */
        private const val LIGHT_VEIL_ALPHA = 16
        /** same threshold the artist / album pages use to pick black or white text */
        private const val PAGE_LIGHT_THRESHOLD = 0.45
    }
}
