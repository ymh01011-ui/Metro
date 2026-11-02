package code.name.monkey.retromusic.views

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.graphics.ColorUtils
import code.name.monkey.appthemehelper.ThemeStore
import code.name.monkey.retromusic.util.PreferenceUtil
import com.google.android.material.color.MaterialColors
import eightbitlab.com.blurview.BlurView
import eightbitlab.com.blurview.RenderScriptBlur

/**
 * Adds a "glass" layer (live backdrop blur + tint + film + edge sheen) as the first child of
 * a FrameLayout. Everything is driven by the Glass settings in [PreferenceUtil].
 *
 * The layer is a child of the host, so it follows the host's animations, alpha and visibility.
 */
object GlassHelper {
    private const val TAG_LAYER = "metro_glass_layer"
    private const val TAG_TINT = "metro_glass_tint"
    private const val TAG_EDGE = "metro_glass_edge"

    /**
     * Creates, updates or removes the glass layer of [host].
     * @param blurRoot the view whose content is blurred (the content behind [host])
     * @param layerHeight height of the layer inside [host], MATCH_PARENT by default
     * @return the layer, or null when [enabled] is false
     */
    fun sync(
        host: FrameLayout,
        blurRoot: ViewGroup,
        enabled: Boolean,
        layerHeight: Int = ViewGroup.LayoutParams.MATCH_PARENT,
    ): BlurView? {
        var layer = host.findViewWithTag<BlurView>(TAG_LAYER)
        if (!enabled) {
            layer?.let { (it.parent as? ViewGroup)?.removeView(it) }
            return null
        }
        if (layer == null) {
            layer = create(host, blurRoot, layerHeight)
        }
        style(layer)
        return layer
    }

    private fun create(host: FrameLayout, blurRoot: ViewGroup, layerHeight: Int): BlurView {
        val context = host.context
        val match = ViewGroup.LayoutParams.MATCH_PARENT

        val layer = BlurView(context)
        layer.tag = TAG_LAYER
        layer.layoutParams = FrameLayout.LayoutParams(match, layerHeight, Gravity.TOP)

        val tintView = View(context)
        tintView.tag = TAG_TINT
        tintView.layoutParams = FrameLayout.LayoutParams(match, match)

        val edgeHeight = context.resources.displayMetrics.density.toInt().coerceAtLeast(1)
        val edgeView = View(context)
        edgeView.tag = TAG_EDGE
        edgeView.layoutParams = FrameLayout.LayoutParams(match, edgeHeight, Gravity.TOP)

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
        layer.findViewWithTag<View>(TAG_EDGE)?.apply {
            visibility = if (material == "quality") View.VISIBLE else View.GONE
            setBackgroundColor(ColorUtils.setAlphaComponent(Color.WHITE, 90))
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
}
