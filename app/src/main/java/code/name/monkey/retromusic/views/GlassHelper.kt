package code.name.monkey.retromusic.views

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.graphics.ColorUtils
import code.name.monkey.appthemehelper.ThemeStore
import code.name.monkey.retromusic.util.PreferenceUtil
import com.google.android.material.color.MaterialColors
import com.google.android.material.navigation.NavigationBarView
import eightbitlab.com.blurview.BlurView
import eightbitlab.com.blurview.RenderScriptBlur
import java.util.WeakHashMap

/**
 * Floating "liquid glass" pills (Apple Music style): live backdrop blur + tint + film + rim light.
 * A pill is a BlurView added as the first child of a FrameLayout host, so it follows the host's
 * animations, alpha and visibility. Everything is driven by the Glass settings in [PreferenceUtil].
 */
object GlassHelper {
    const val TAG_LAYER = "metro_glass_layer"
    private const val TAG_TINT = "metro_glass_tint"
    private const val TAG_EDGE = "metro_glass_edge"
    private const val TAG_ICON = "metro_glass_icon"

    const val SIDE_MARGIN_DP = 16
    const val GAP_DP = 8
    const val NAV_PILL_HEIGHT_DP = 62
    const val TAB_INNER_DP = 4
    private const val TAG_PUCK = "metro_glass_puck"

    private val savedBackgrounds = WeakHashMap<View, Drawable?>()
    private val savedIndicator = WeakHashMap<View, Boolean>()
    private val tabWatchers = WeakHashMap<View, ViewTreeObserver.OnPreDrawListener>()
    private val lastPuckIndex = WeakHashMap<View, Int>()
    private val puckAnimating = WeakHashMap<View, Boolean>()

    fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    fun layerOf(host: ViewGroup): BlurView? = host.findViewWithTag<BlurView>(TAG_LAYER)

    // ------------------------------------------------------------------ pills

    /**
     * Creates, updates or removes the pill of [host].
     * @param layerHeight height of the pill in px, MATCH_PARENT by default
     * @return the pill, or null when [enabled] is false
     */
    fun sync(
        host: FrameLayout,
        blurRoot: ViewGroup,
        enabled: Boolean,
        layerHeight: Int = ViewGroup.LayoutParams.MATCH_PARENT,
        verticalMarginDp: Int = 6,
        withIcon: Boolean = false,
        onClick: (() -> Unit)? = null,
    ): BlurView? {
        var layer = layerOf(host)
        if (!enabled) {
            layer?.let { (it.parent as? ViewGroup)?.removeView(it) }
            return null
        }
        if (layer == null) {
            val context = host.context
            val side = dp(context, SIDE_MARGIN_DP)
            val vertical = dp(context, verticalMarginDp)
            val params = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                layerHeight,
                Gravity.START or (if (withIcon) Gravity.CENTER_VERTICAL else Gravity.TOP)
            )
            params.setMargins(side, vertical, side, vertical)
            params.marginStart = side
            params.marginEnd = side
            layer = createLayer(host, blurRoot, params, TAG_LAYER)
            if (withIcon) {
                val icon = ImageView(context)
                icon.tag = TAG_ICON
                icon.scaleType = ImageView.ScaleType.FIT_CENTER
                val pad = dp(context, 12)
                icon.setPadding(pad, pad, pad, pad)
                icon.layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                )
                icon.alpha = 0f
                layer.addView(icon)
            }
        }
        layer.setOnClickListener(if (onClick != null) View.OnClickListener { onClick() } else null)
        layer.isClickable = false
        style(layer)
        return layer
    }

    /** Puts [icon] (the selected tab) in the pill; it fades in while the bar is folded. */
    fun setSelectedIcon(layer: BlurView?, icon: Drawable?) {
        val view = layer?.findViewWithTag<ImageView>(TAG_ICON) ?: return
        view.setImageDrawable(icon?.constantState?.newDrawable()?.mutate() ?: icon)
        view.imageTintList = ColorStateList.valueOf(ThemeStore.accentColor(layer.context))
    }

    private fun createLayer(
        host: FrameLayout,
        blurRoot: ViewGroup,
        params: FrameLayout.LayoutParams,
        layerTag: String,
    ): BlurView {
        val context = host.context
        val match = ViewGroup.LayoutParams.MATCH_PARENT

        val layer = BlurView(context)
        layer.tag = layerTag
        layer.layoutParams = params
        layer.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(
                    0, 0, view.width, view.height, minOf(view.width, view.height) / 2f
                )
            }
        }
        layer.clipToOutline = true

        val tintView = View(context)
        tintView.tag = TAG_TINT
        tintView.layoutParams = FrameLayout.LayoutParams(match, match)

        val edgeView = View(context)
        edgeView.tag = TAG_EDGE
        edgeView.layoutParams = FrameLayout.LayoutParams(match, match)

        layer.addView(tintView)
        layer.addView(edgeView)
        host.addView(layer, 0)
        layer.setupWith(blurRoot, RenderScriptBlur(context)).setBlurRadius(8f)
        return layer
    }

    private fun style(layer: BlurView) {
        val context = layer.context
        val material = PreferenceUtil.glassMaterial
        val surface = MaterialColors.getColor(
            context, com.google.android.material.R.attr.colorSurface, Color.BLACK
        )
        val filmAlpha = PreferenceUtil.glassFilm.coerceIn(0, 100) * 2          // 0..200
        val tintAlpha = (PreferenceUtil.glassColour.coerceIn(0, 100) * 12) / 10 // 0..120
        val radius = PreferenceUtil.glassFrost.coerceIn(1, 25).toFloat()
        val tint = tintColor(context)

        layer.findViewWithTag<View>(TAG_TINT)?.setBackgroundColor(
            if (tint == 0) Color.TRANSPARENT else ColorUtils.setAlphaComponent(tint, tintAlpha)
        )

        // Rim light: a thin translucent outline around the pill
        layer.findViewWithTag<View>(TAG_EDGE)?.let { edge ->
            edge.visibility = if (material == "quality") View.VISIBLE else View.GONE
            val rim = GradientDrawable()
            rim.shape = GradientDrawable.RECTANGLE
            rim.cornerRadius = 1000f
            rim.setColor(Color.TRANSPARENT)
            rim.setStroke(dp(context, 1), ColorUtils.setAlphaComponent(Color.WHITE, 80))
            edge.background = rim
        }

        if (material == "fastest") {
            // No live blur: a flat translucent surface, the cheapest option
            layer.setBlurEnabled(false)
            layer.setBackgroundColor(
                ColorUtils.setAlphaComponent(surface, (150 + filmAlpha / 4).coerceAtMost(255))
            )
        } else {
            layer.setBackgroundColor(Color.TRANSPARENT)
            layer.setBlurEnabled(true)
            layer.setBlurRadius(radius)
            layer.setOverlayColor(ColorUtils.setAlphaComponent(surface, filmAlpha))
        }
        layer.invalidateOutline()
    }

    private fun tintColor(context: Context): Int = when (PreferenceUtil.glassTint) {
        "accent" -> ThemeStore.accentColor(context)
        "red" -> Color.parseColor("#FF3B30")
        "orange" -> Color.parseColor("#FF9500")
        "yellow" -> Color.parseColor("#FFD60A")
        "green" -> Color.parseColor("#30D158")
        "cyan" -> Color.parseColor("#32ADE6")
        "blue" -> Color.parseColor("#0A84FF")
        "purple" -> Color.parseColor("#BF5AF2")
        "pink" -> Color.parseColor("#FF2D95")
        else -> 0
    }

    // ------------------------------------------------------------------ tabs

    /**
     * Square-style tabs: the tabs sit inside the pill (4dp padding) and the selection is a
     * rounded "puck" that slides to the selected tab, instead of Material's small indicator.
     */
    fun syncTabs(nav: NavigationBarView, enabled: Boolean) {
        val context = nav.context
        val side = dp(context, SIDE_MARGIN_DP)
        val inner = dp(context, TAB_INNER_DP)
        val tabsHeight = dp(context, NAV_PILL_HEIGHT_DP) - 2 * inner

        var menuView: View? = null
        for (i in 0 until nav.childCount) {
            val child = nav.getChildAt(i)
            if (child.tag != TAG_LAYER && child.tag != TAG_PUCK) {
                menuView = child
                break
            }
        }
        val menuParams = menuView?.layoutParams as? FrameLayout.LayoutParams
        var puck = nav.findViewWithTag<View>(TAG_PUCK)

        if (!enabled) {
            savedIndicator.remove(nav)?.let { nav.isItemActiveIndicatorEnabled = it }
            if (menuView != null && menuParams != null) {
                menuParams.setMargins(0, 0, 0, 0)
                menuParams.marginStart = 0
                menuParams.marginEnd = 0
                menuParams.height = ViewGroup.LayoutParams.MATCH_PARENT
                menuParams.gravity = -1
                menuView.layoutParams = menuParams
            }
            puck?.let { nav.removeView(it) }
            tabWatchers.remove(nav)?.let {
                if (nav.viewTreeObserver.isAlive) nav.viewTreeObserver.removeOnPreDrawListener(it)
            }
            lastPuckIndex.remove(nav)
            puckAnimating.remove(nav)
            return
        }

        if (!savedIndicator.containsKey(nav)) savedIndicator[nav] = nav.isItemActiveIndicatorEnabled
        nav.isItemActiveIndicatorEnabled = false

        if (menuView != null && menuParams != null) {
            menuParams.setMargins(side + inner, 0, side + inner, 0)
            menuParams.marginStart = side + inner
            menuParams.marginEnd = side + inner
            menuParams.height = tabsHeight
            menuParams.gravity = Gravity.CENTER_VERTICAL
            menuView.layoutParams = menuParams
        }

        if (puck == null) {
            puck = View(context)
            puck.tag = TAG_PUCK
            val params = FrameLayout.LayoutParams(0, tabsHeight, Gravity.START or Gravity.CENTER_VERTICAL)
            params.marginStart = side + inner
            puck.layoutParams = params
            nav.addView(puck, minOf(1, nav.childCount))
        }
        val surface = MaterialColors.getColor(
            context, com.google.android.material.R.attr.colorSurface, Color.BLACK
        )
        val dark = ColorUtils.calculateLuminance(surface) < 0.5
        val shape = GradientDrawable()
        shape.shape = GradientDrawable.RECTANGLE
        shape.cornerRadius = 1000f
        shape.setColor(if (dark) Color.argb(90, 0, 0, 0) else Color.argb(150, 255, 255, 255))
        shape.setStroke(
            dp(context, 1),
            if (dark) Color.argb(50, 255, 255, 255) else Color.argb(30, 0, 0, 0)
        )
        puck.background = shape

        if (!tabWatchers.containsKey(nav)) {
            val listener = ViewTreeObserver.OnPreDrawListener {
                syncPuck(nav)
                true
            }
            nav.viewTreeObserver.addOnPreDrawListener(listener)
            tabWatchers[nav] = listener
        }
    }

    private fun syncPuck(nav: NavigationBarView) {
        val puck = nav.findViewWithTag<View>(TAG_PUCK) ?: return
        val menu = nav.menu
        val count = menu.size()
        if (count == 0 || nav.width == 0) return
        val context = nav.context
        val inset = dp(context, SIDE_MARGIN_DP) + dp(context, TAB_INNER_DP)
        val slot = (nav.width - nav.paddingLeft - nav.paddingRight - 2 * inset) / count
        if (slot <= 0) return

        var index = 0
        for (i in 0 until count) {
            if (menu.getItem(i).isChecked) {
                index = i
                break
            }
        }
        val params = puck.layoutParams as FrameLayout.LayoutParams
        if (params.width != slot) {
            params.width = slot
            puck.layoutParams = params
        }
        val target = (index * slot).toFloat()
        val last = lastPuckIndex[nav]
        when {
            last == null -> puck.translationX = target
            last != index -> {
                puckAnimating[nav] = true
                puck.animate().cancel()
                puck.animate()
                    .translationX(target)
                    .setDuration(320)
                    .setInterpolator(OvershootInterpolator(0.8f))
                    .withEndAction { puckAnimating[nav] = false }
                    .start()
            }
            puckAnimating[nav] != true && puck.translationX != target -> puck.translationX = target
        }
        lastPuckIndex[nav] = index
    }

    // ------------------------------------------------------------ host looks

    /** The floating bar must not have its own full-width background. */
    fun setNavBackgroundHidden(nav: View, hidden: Boolean) {
        if (hidden) {
            if (!savedBackgrounds.containsKey(nav)) savedBackgrounds[nav] = nav.background
            nav.background = null
        } else if (savedBackgrounds.containsKey(nav)) {
            nav.background = savedBackgrounds.remove(nav)
        }
    }

    /** 0 = fully transparent (collapsed mini player pill), 255 = original look. */
    fun setHostBackgroundAlpha(host: View, alpha: Int) {
        host.background?.mutate()?.alpha = alpha.coerceIn(0, 255)
    }

    /** Keeps the mini player's content inside its pill. */
    fun setMiniInsets(mini: View?, enabled: Boolean, startExtraPx: Int) {
        val view = mini ?: return
        val params = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        val side = dp(view.context, SIDE_MARGIN_DP)
        val start = if (enabled) startExtraPx + side - dp(view.context, 8) else 0
        val end = if (enabled) side + dp(view.context, 4) else 0
        if (params.marginStart != start || params.marginEnd != end) {
            params.marginStart = start
            params.marginEnd = end
            view.layoutParams = params
        }
        if (!enabled) view.translationY = 0f
    }

    // --------------------------------------------------------------- compact

    /** Vertical distance between the centers of [a] and [b] (ignoring [b]'s translation). */
    fun centerDelta(a: View, b: View): Float {
        val xy = IntArray(2)
        a.getLocationOnScreen(xy)
        val aCenter = xy[1] + a.height / 2f
        b.getLocationOnScreen(xy)
        val bCenter = xy[1] + b.height / 2f - b.translationY
        return aCenter - bCenter
    }

    private fun mix(a: Float, b: Float, p: Float) = a + (b - a) * p

    /**
     * Same idea as Square's fold: one value p drives everything.
     * p = 0: tab bar pill above the mini player pill.
     * p = 1: the tab bar pill has shrunk into a circle (selected tab) on the left and the mini
     *        player pill sits next to it, in the same row.
     */
    fun applyCompact(
        nav: ViewGroup,
        panel: ViewGroup,
        mini: View?,
        p: Float,
        shiftY: Float,
        circle: Int,
    ) {
        val context = nav.context
        val side = dp(context, SIDE_MARGIN_DP)
        val gap = dp(context, GAP_DP)

        // tabs fade out within the first third of the fold (as in Square)
        val tabsAlpha = (1f - p * 3f).coerceIn(0f, 1f)
        for (i in 0 until nav.childCount) {
            val child = nav.getChildAt(i)
            if (child.tag == TAG_LAYER) continue
            child.alpha = tabsAlpha
            child.visibility = if (tabsAlpha <= 0f) View.INVISIBLE else View.VISIBLE
        }

        // the tab bar pill shrinks into a circle
        layerOf(nav)?.let { layer ->
            val params = layer.layoutParams as FrameLayout.LayoutParams
            val fullWidth = nav.width - nav.paddingLeft - nav.paddingRight - 2 * side
            val fullHeight = dp(context, NAV_PILL_HEIGHT_DP)
            val width: Int
            val height: Int
            if (p <= 0f || fullWidth <= 0) {
                width = ViewGroup.LayoutParams.MATCH_PARENT
                height = fullHeight
            } else {
                width = mix(fullWidth.toFloat(), circle.toFloat(), p).toInt()
                height = mix(fullHeight.toFloat(), circle.toFloat(), p).toInt()
            }
            if (params.width != width || params.height != height) {
                params.width = width
                params.height = height
                layer.layoutParams = params
            }
            layer.findViewWithTag<View>(TAG_ICON)?.alpha = p
            layer.isClickable = p > 0.5f
            layer.invalidateOutline()
        }

        // the mini player pill slides into the row, next to the circle
        val extra = ((circle + gap) * p).toInt()
        layerOf(panel)?.let { layer ->
            layer.translationY = shiftY * p
            val params = layer.layoutParams as? ViewGroup.MarginLayoutParams
            if (params != null &&
                (params.marginStart != side + extra || params.marginEnd != side)
            ) {
                // always set BOTH ends: setting only the start resets the end margin to 0
                params.marginStart = side + extra
                params.marginEnd = side
                layer.layoutParams = params
            }
        }
        if (mini != null) {
            setMiniInsets(mini, true, extra)
            mini.translationY = shiftY * p
        }
    }
}
