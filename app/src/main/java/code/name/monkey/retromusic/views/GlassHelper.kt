package code.name.monkey.retromusic.views

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Outline
import android.graphics.drawable.Drawable
import android.graphics.Typeface
import android.os.Build
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.util.TypedValue
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import code.name.monkey.appthemehelper.ThemeStore
import code.name.monkey.retromusic.util.PreferenceUtil
import com.google.android.material.color.MaterialColors
import com.google.android.material.navigation.NavigationBarView
import java.util.WeakHashMap

/**
 * Floating "liquid glass" pills (Apple Music style): live backdrop blur + tint + film + rim light.
 * A pill is a [GlassLayer] added as the first child of a FrameLayout host, so it follows the host's
 * animations, alpha and visibility. Everything is driven by the Glass settings in [PreferenceUtil].
 */
object GlassHelper {
    const val TAG_LAYER = "metro_glass_layer"
    private const val TAG_TINT = "metro_glass_tint"
    private const val TAG_EDGE = "metro_glass_edge"
    private const val TAG_ICON = "metro_glass_icon"

    private const val SEARCH_FILM_FACTOR = 0.5f

    const val SIDE_MARGIN_DP = 16
    const val GAP_DP = 8
    const val NAV_PILL_HEIGHT_DP = 62
    const val TAB_INNER_DP = 4
    private const val TAG_PUCK = "metro_glass_puck"
    private const val TAG_TABS = "metro_glass_tabs"

    private val CHECKED_STATE = intArrayOf(
        android.R.attr.state_checked, android.R.attr.state_selected, android.R.attr.state_enabled
    )
    private val UNCHECKED_STATE = intArrayOf(android.R.attr.state_enabled)

    private val savedBackgrounds = WeakHashMap<View, Drawable?>()
    private val savedIndicator = WeakHashMap<View, Boolean>()
    private val tabWatchers = WeakHashMap<View, ViewTreeObserver.OnPreDrawListener>()
    private val lastPuckIndex = WeakHashMap<View, Int>()
    private val puckAnimating = WeakHashMap<View, Boolean>()
    private val tabIds = WeakHashMap<View, IntArray>()
    private val styledIndex = WeakHashMap<View, Int>()
    private val styledInk = WeakHashMap<View, Int>()

    fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    fun layerOf(host: ViewGroup): GlassLayer? = host.findViewWithTag<GlassLayer>(TAG_LAYER)

    /** Re-reads the settings for the pill of [host]. */
    fun restyle(host: ViewGroup) {
        layerOf(host)?.let { style(it) }
    }

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
    ): GlassLayer? {
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
                iconViews[icon] = true
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

    /**
     * A glass pill that fills [host] completely (used for the floating search bar, a card that has
     * its own size and margins). [backdrop] must NOT contain [host]: pass a sibling of it
     * (the list / scroll view under the bar), never one of its parents.
     * @return the pill, or null when [enabled] is false
     */
    fun syncFill(host: FrameLayout, backdrop: ViewGroup, enabled: Boolean): GlassLayer? {
        var layer = layerOf(host)
        if (!enabled) {
            layer?.let { (it.parent as? ViewGroup)?.removeView(it) }
            for (i in 0 until host.childCount) host.getChildAt(i).elevation = 0f
            return null
        }
        if (layer == null) {
            val match = ViewGroup.LayoutParams.MATCH_PARENT
            layer = createLayer(host, backdrop, FrameLayout.LayoutParams(match, match), TAG_LAYER)
        }
        layer.backdrop = backdrop
        // the search bar sits over a plain page, so the white veil shows much more than on the
        // bottom bar: use a lighter one (raise towards 1f for more, lower for less)
        layer.filmFactor = SEARCH_FILM_FACTOR
        // the list under the bar has no background of its own: give the blur an opaque page
        layer.baseColor = MaterialColors.getColor(
            host.context, android.R.attr.colorBackground, Color.BLACK
        )
        // the pill has an elevation, so the bar's own content must sit above it
        val lift = dp(host.context, 9).toFloat()
        for (i in 0 until host.childCount) {
            val child = host.getChildAt(i)
            if (child !== layer) child.elevation = lift
        }
        layer.isClickable = false
        style(layer)
        return layer
    }

    /**
     * Like [syncFill] but with rounded corners of [cornerDp] instead of a full pill: for cards
     * (the "About" box). Same blur, film, tint, rim light and shadow as the pills.
     */
    fun syncRounded(host: FrameLayout, backdrop: ViewGroup, enabled: Boolean, cornerDp: Int): GlassLayer? {
        val layer = syncFill(host, backdrop, enabled) ?: return null
        val context = host.context
        val corner = dp(context, cornerDp).toFloat()
        layer.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(
                    0, 0, view.width, view.height,
                    minOf(corner, minOf(view.width, view.height) / 2f)
                )
                outline.alpha = 1f
            }
        }
        layer.findViewWithTag<View>(TAG_EDGE)?.background =
            RimDrawable(0.75f * context.resources.displayMetrics.density, corner)
        style(layer) // re-applies the rim tone to the new rounded rim
        return layer
    }

    /**
     * A free-standing glass panel with rounded corners (not a pill), for popup menus. It lives in
     * its own window, so it blurs [backdrop] (the activity's decor view) using screen positions.
     * Same settings and same look (blur, colour, film, tint, rim light) as the bars.
     */
    fun createPopupLayer(context: Context, backdrop: ViewGroup, cornerDp: Int): GlassLayer {
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        val density = context.resources.displayMetrics.density
        val corner = dp(context, cornerDp).toFloat()

        val layer = GlassLayer(context)
        layer.tag = TAG_LAYER
        layer.screenCoords = true
        layer.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, corner)
                outline.alpha = 1f
            }
        }
        layer.clipToOutline = true
        layer.elevation = dp(context, 6).toFloat()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            layer.outlineSpotShadowColor = Color.argb(70, 0, 0, 0)
            layer.outlineAmbientShadowColor = Color.argb(30, 0, 0, 0)
        }

        val tintView = View(context)
        tintView.tag = TAG_TINT
        tintView.layoutParams = FrameLayout.LayoutParams(match, match)
        val edgeView = View(context)
        edgeView.tag = TAG_EDGE
        edgeView.layoutParams = FrameLayout.LayoutParams(match, match)
        edgeView.background = RimDrawable(0.75f * density, corner)
        layer.addView(tintView)
        layer.addView(edgeView)

        layer.backdrop = backdrop
        layer.baseColor = MaterialColors.getColor(context, android.R.attr.colorBackground, Color.BLACK)
        layer.onToneChanged = { applyTone(layer) }
        style(layer)
        return layer
    }

    /** Puts [icon] (the selected tab) in the pill; it fades in while the bar is folded. */
    fun setSelectedIcon(layer: GlassLayer?, icon: Drawable?) {
        val view = layer?.findViewWithTag<ImageView>(TAG_ICON) ?: return
        view.setImageDrawable(icon?.constantState?.newDrawable()?.mutate() ?: icon)
        applyTone(layer)
    }

    private fun createLayer(
        host: FrameLayout,
        blurRoot: ViewGroup,
        params: FrameLayout.LayoutParams,
        layerTag: String,
    ): GlassLayer {
        val context = host.context
        val match = ViewGroup.LayoutParams.MATCH_PARENT

        val layer = GlassLayer(context)
        layer.tag = layerTag
        layer.layoutParams = params
        layer.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(
                    0, 0, view.width, view.height, minOf(view.width, view.height) / 2f
                )
                outline.alpha = 1f
            }
        }
        layer.clipToOutline = true
        // depth: a soft shadow around the pill (clipped away under the glass itself)
        layer.elevation = dp(context, 3).toFloat()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            layer.outlineSpotShadowColor = Color.argb(55, 0, 0, 0)
            layer.outlineAmbientShadowColor = Color.argb(25, 0, 0, 0)
        }
        host.clipChildren = false
        host.clipToPadding = false

        val tintView = View(context)
        tintView.tag = TAG_TINT
        tintView.layoutParams = FrameLayout.LayoutParams(match, match)

        val edgeView = View(context)
        edgeView.tag = TAG_EDGE
        edgeView.layoutParams = FrameLayout.LayoutParams(match, match)

        layer.addView(tintView)
        layer.addView(edgeView)
        host.addView(layer, 0)
        layer.backdrop = blurRoot
        layer.onToneChanged = { applyTone(layer) }
        return layer
    }

    private fun style(layer: GlassLayer) {
        val context = layer.context
        val density = context.resources.displayMetrics.density
        val material = PreferenceUtil.glassMaterial
        val surface = MaterialColors.getColor(
            context, com.google.android.material.R.attr.colorSurface, Color.BLACK
        )
        val dark = ColorUtils.calculateLuminance(surface) < 0.5
        val film = PreferenceUtil.glassFilm.coerceIn(0, 100)
        val tint = tintColor(context)

        // Dark mode: a light veil (Square's glass is brighter than the page, not darker).
        // Light mode: the surface colour.
        val filmColor = if (dark) Color.WHITE else surface
        val filmAlpha = if (dark) film / 2 else film * 2 // dark 0..50, light 0..200

        layer.findViewWithTag<View>(TAG_TINT)?.setBackgroundColor(
            if (tint == 0) Color.TRANSPARENT else ColorUtils.setAlphaComponent(tint, 90)
        )

        // Rim light: a thin translucent outline around the pill
        layer.findViewWithTag<View>(TAG_EDGE)?.let { edge ->
            edge.visibility = if (material == "quality") View.VISIBLE else View.GONE
            if (edge.background !is RimDrawable) edge.background = RimDrawable(0.75f * density)
        }

        val liveBlur = material != "fastest" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        layer.live = liveBlur
        if (liveBlur) {
            layer.setBackgroundColor(Color.TRANSPARENT)
            // Frost is in dp of blur (sigma), Colour boosts saturation: 0% = 1.0x, 100% = 2.0x
            layer.blurSigmaPx = (PreferenceUtil.glassFrost * density * 0.6f).coerceAtLeast(0.5f)
            layer.saturation = 1f + PreferenceUtil.glassColour.coerceIn(0, 100) / 100f
            layer.filmColor = ColorUtils.setAlphaComponent(
                filmColor, (filmAlpha * layer.filmFactor).toInt().coerceIn(0, 255)
            )
        } else {
            // No blur (Fastest, or below Android 12): a flat translucent surface
            layer.filmColor = Color.TRANSPARENT
            layer.setBackgroundColor(ColorUtils.setAlphaComponent(surface, (150 + film).coerceAtMost(255)))
        }
        applyTone(layer)
        layer.invalidateOutline()
        layer.invalidate()
    }

    /** The rim follows the page behind the pill: dark variant on dark, light variant on bright. */
    private fun applyTone(layer: GlassLayer) {
        val surface = MaterialColors.getColor(
            layer.context, com.google.android.material.R.attr.colorSurface, Color.BLACK
        )
        val themeLight = ColorUtils.calculateLuminance(surface) >= 0.5
        val tone = if (layer.sampled) layer.lightness else if (themeLight) 1f else 0f
        (layer.findViewWithTag<View>(TAG_EDGE)?.background as? RimDrawable)?.setTone(tone)
        // the icon shown in the folded pill: the same ink as the text of the mini player
        layer.findViewWithTag<ImageView>(TAG_ICON)?.imageTintList = ColorStateList.valueOf(
            iconInk ?: ColorUtils.blendARGB(Color.WHITE, GlassLayer.DARK_INK, tone)
        )
    }

    private var iconInk: Int? = null
    private val iconViews = java.util.WeakHashMap<ImageView, Boolean>()

    /** The mini player tells the icon in the folded pill which ink its own text has right now. */
    fun setIconInk(color: Int?) {
        iconInk = color
        if (color == null) return
        for (view in iconViews.keys.toList()) view.imageTintList = ColorStateList.valueOf(color)
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
     * Square-style tabs. Material's own tab views are hidden and replaced by a row we control
     * (24dp icon, 2dp gap, 11sp label, so nothing can overlap), plus a rounded "puck" that
     * slides to the selected tab. Tapping a tab still goes through the real navigation view.
     */
    fun syncTabs(nav: NavigationBarView, enabled: Boolean) {
        val context = nav.context
        val side = dp(context, SIDE_MARGIN_DP)
        val inner = dp(context, TAB_INNER_DP)
        val tabsHeight = dp(context, NAV_PILL_HEIGHT_DP) - 2 * inner

        var materialMenu: View? = null
        for (i in 0 until nav.childCount) {
            val child = nav.getChildAt(i)
            val tag = child.tag
            if (tag != TAG_LAYER && tag != TAG_PUCK && tag != TAG_TABS) {
                materialMenu = child
                break
            }
        }
        val row = nav.findViewWithTag<LinearLayout>(TAG_TABS)
        var puck = nav.findViewWithTag<View>(TAG_PUCK)

        if (!enabled) {
            materialMenu?.visibility = View.VISIBLE
            row?.let { nav.removeView(it) }
            puck?.let { nav.removeView(it) }
            tabWatchers.remove(nav)?.let {
                if (nav.viewTreeObserver.isAlive) nav.viewTreeObserver.removeOnPreDrawListener(it)
            }
            lastPuckIndex.remove(nav)
            puckAnimating.remove(nav)
            tabIds.remove(nav)
            styledIndex.remove(nav)
            styledInk.remove(nav)
            return
        }

        materialMenu?.visibility = View.GONE

        if (puck == null) {
            puck = View(context)
            puck.tag = TAG_PUCK
            val params =
                FrameLayout.LayoutParams(0, tabsHeight, Gravity.START or Gravity.CENTER_VERTICAL)
            params.marginStart = side + inner
            puck.layoutParams = params
            nav.addView(puck, minOf(1, nav.childCount))
        }
        puck.elevation = dp(context, 9).toFloat()
        puck.outlineProvider = null // the puck must not cast a shadow
        stylePuck(nav)

        if (row == null) {
            val newRow = LinearLayout(context)
            newRow.tag = TAG_TABS
            newRow.orientation = LinearLayout.HORIZONTAL
            val params = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, tabsHeight, Gravity.START or Gravity.CENTER_VERTICAL
            )
            params.setMargins(side + inner, 0, side + inner, 0)
            params.marginStart = side + inner
            params.marginEnd = side + inner
            newRow.layoutParams = params
            newRow.elevation = dp(context, 10).toFloat()
            nav.addView(newRow, minOf(2, nav.childCount))
        }
        // force a rebuild + restyle (theme / accent may have changed)
        tabIds.remove(nav)
        styledIndex.remove(nav)
        syncRow(nav)

        if (!tabWatchers.containsKey(nav)) {
            val listener = ViewTreeObserver.OnPreDrawListener {
                syncRow(nav)
                syncPuck(nav)
                true
            }
            nav.viewTreeObserver.addOnPreDrawListener(listener)
            tabWatchers[nav] = listener
        }
    }

    private fun stylePuck(nav: NavigationBarView) {
        val puck = nav.findViewWithTag<View>(TAG_PUCK) ?: return
        val context = nav.context
        val surface = MaterialColors.getColor(
            context, com.google.android.material.R.attr.colorSurface, Color.BLACK
        )
        val glass = layerOf(nav)
        val tone = if (glass != null && glass.sampled) {
            glass.lightness
        } else if (ColorUtils.calculateLuminance(surface) >= 0.5) {
            1f
        } else {
            0f
        }
        val fill = ColorUtils.blendARGB(Color.argb(90, 0, 0, 0), Color.argb(32, 0, 0, 0), tone)
        val stroke = ColorUtils.blendARGB(Color.argb(60, 255, 255, 255), Color.argb(45, 0, 0, 0), tone)
        val shape = (puck.background as? GradientDrawable) ?: GradientDrawable().also {
            it.shape = GradientDrawable.RECTANGLE
            it.cornerRadius = 1000f
            puck.background = it
        }
        shape.setColor(fill)
        shape.setStroke(dp(context, 1), stroke)
    }

    private fun syncRow(nav: NavigationBarView) {
        val row = nav.findViewWithTag<LinearLayout>(TAG_TABS) ?: return
        val menu = nav.menu
        val count = menu.size()

        val known = tabIds[nav]
        var changed = known == null || known.size != count
        if (!changed && known != null) {
            for (i in 0 until count) {
                if (known[i] != menu.getItem(i).itemId) {
                    changed = true
                    break
                }
            }
        }
        var selected = 0
        for (i in 0 until count) {
            if (menu.getItem(i).isChecked) {
                selected = i
                break
            }
        }

        if (changed) {
            row.removeAllViews()
            val context = nav.context
            for (i in 0 until count) {
                val item = menu.getItem(i)
                val itemId = item.itemId

                val tab = LinearLayout(context)
                tab.orientation = LinearLayout.VERTICAL
                tab.gravity = Gravity.CENTER
                tab.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                tab.setOnClickListener { nav.selectedItemId = itemId }

                val icon = ImageView(context)
                icon.scaleType = ImageView.ScaleType.FIT_CENTER
                icon.layoutParams = LinearLayout.LayoutParams(dp(context, 24), dp(context, 24))
                icon.setImageDrawable(item.icon?.constantState?.newDrawable()?.mutate() ?: item.icon)

                val label = TextView(context)
                label.text = item.title
                label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                label.maxLines = 1
                label.ellipsize = android.text.TextUtils.TruncateAt.END
                label.gravity = Gravity.CENTER
                val labelParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
                labelParams.topMargin = dp(context, 2)
                label.layoutParams = labelParams

                tab.addView(icon)
                tab.addView(label)
                row.addView(tab)
            }
            tabIds[nav] = IntArray(count) { menu.getItem(it).itemId }
            styledIndex.remove(nav)
        }

        val glass = layerOf(nav)
        val glassInk = glass?.inkColor
        val inkKey = glassInk ?: 0
        if (styledIndex[nav] != selected || styledInk[nav] != inkKey) {
            // animate only when the selection really changes, not when the tabs are (re)built
            val animate = styledIndex[nav] != null && styledIndex[nav] != selected
            val context = nav.context
            // over a bright page: dark ink and a deeper accent, like Apple's glass flipping to dark
            val baseAccent = ThemeStore.accentColor(context)
            val accent = if (glass != null && glass.sampled) {
                ColorUtils.blendARGB(
                    baseAccent, ColorUtils.blendARGB(baseAccent, Color.BLACK, 0.35f), glass.lightness
                )
            } else {
                baseAccent
            }
            val ink = glassInk ?: MaterialColors.getColor(
                context, com.google.android.material.R.attr.colorOnSurface, Color.WHITE
            )
            val dim = ColorUtils.setAlphaComponent(ink, 180)
            for (i in 0 until row.childCount) {
                val tab = row.getChildAt(i) as? LinearLayout ?: continue
                val chosen = i == selected
                val color = if (chosen) accent else dim
                (tab.getChildAt(0) as? ImageView)?.let { icon ->
                    icon.imageTintList = ColorStateList.valueOf(color)
                    // animated icon selectors (the little notes around the songs icon...) react to
                    // the checked state, exactly like Material's own tab did
                    icon.isSelected = chosen
                    icon.setImageState(if (chosen) CHECKED_STATE else UNCHECKED_STATE, false)
                    if (!animate) icon.drawable?.jumpToCurrentState()
                }
                (tab.getChildAt(1) as? TextView)?.let {
                    it.setTextColor(color)
                    it.setTypeface(Typeface.DEFAULT, if (chosen) Typeface.BOLD else Typeface.NORMAL)
                }
            }
            styledIndex[nav] = selected
            styledInk[nav] = inkKey
            stylePuck(nav)
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
        // same horizontal bounds as the pill: the layout's own 10dp padding then puts the
        // artwork 10dp inside the pill, as in Square
        val start = if (enabled) startExtraPx + side else 0
        val end = if (enabled) side else 0
        if (params.marginStart != start || params.marginEnd != end) {
            params.marginStart = start
            params.marginEnd = end
            view.layoutParams = params
        }
        view.elevation = if (enabled) dp(view.context, 9).toFloat() else 0f
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
            if (child.tag != TAG_TABS && child.tag != TAG_PUCK) continue
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

/**
 * The edge of a glass pill: a faint vertical sheen over the whole pill, a soft inner glow and a
 * thin rim that is bright on top, dim in the middle and lit again at the bottom (light catching
 * the glass). [setTone] glides it between the variant for dark pages (0) and bright pages (1).
 */
/** overall opacity of the rim */
private const val RIM_OPACITY = 0.75f

private class RimDrawable(
    private val strokePx: Float,
    private val cornerPx: Float = -1f,
) : Drawable() {
    private var tone = 0f
    private val sheen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokePx * 1.25f
    }
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokePx
    }
    private val rect = RectF()

    fun setTone(value: Float) {
        if (value == tone) return
        tone = value
        rebuild()
        invalidateSelf()
    }

    private fun mix(dark: Int, light: Int) = ColorUtils.blendARGB(dark, light, tone)

    /** a bit see-through, so the rim never looks like a hard white or black line */
    private fun a(color: Int, alpha: Int) =
        ColorUtils.setAlphaComponent(color, (alpha * RIM_OPACITY).toInt())

    private fun rebuild() {
        val b = bounds
        if (b.isEmpty) return
        val top = b.top.toFloat()
        val bottom = b.bottom.toFloat()
        val white = Color.WHITE
        val black = Color.BLACK
        sheen.shader = LinearGradient(
            0f, top, 0f, bottom,
            intArrayOf(
                mix(a(white, 20), a(white, 45)),
                a(white, 0),
                mix(a(black, 26), a(black, 10))
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        val middle = mix(a(white, 28), a(black, 20))
        rim.shader = LinearGradient(
            0f, top, 0f, bottom,
            intArrayOf(
                mix(a(white, 150), a(white, 220)),
                middle,
                middle,
                mix(a(white, 60), a(black, 45))
            ),
            floatArrayOf(0f, 0.4f, 0.6f, 1f),
            Shader.TileMode.CLAMP
        )
        glow.shader = LinearGradient(
            0f, top, 0f, bottom,
            intArrayOf(
                mix(a(white, 24), a(white, 50)),
                a(white, 0),
                mix(a(white, 12), a(white, 0))
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    override fun onBoundsChange(bounds: Rect) {
        rebuild()
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val radius = if (cornerPx > 0f) minOf(cornerPx, minOf(b.width(), b.height()) / 2f)
        else minOf(b.width(), b.height()) / 2f

        rect.set(b)
        canvas.drawRoundRect(rect, radius, radius, sheen)

        val glowInset = strokePx * 1.5f
        rect.set(b)
        rect.inset(glowInset, glowInset)
        canvas.drawRoundRect(rect, radius - glowInset, radius - glowInset, glow)

        val rimInset = strokePx / 2f
        rect.set(b)
        rect.inset(rimInset, rimInset)
        canvas.drawRoundRect(rect, radius - rimInset, radius - rimInset, rim)
    }

    override fun setAlpha(alpha: Int) {
        sheen.alpha = alpha
        glow.alpha = alpha
        rim.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        sheen.colorFilter = colorFilter
        glow.colorFilter = colorFilter
        rim.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
