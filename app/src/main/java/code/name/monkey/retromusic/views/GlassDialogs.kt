package code.name.monkey.retromusic.views

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.InsetDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.graphics.ColorUtils
import androidx.fragment.app.DialogFragment
import code.name.monkey.appthemehelper.ThemeStore
import code.name.monkey.retromusic.util.PreferenceUtil

/**
 * Turns any dialog (AlertDialog / MaterialAlertDialog / DialogFragment) into glass,
 * using the same GlassLayer + settings (blur, frost, colour, film, rim) as the popup menus.
 */
object GlassDialogs {

    private const val DIALOG_CORNER_DP = 28
    private const val TAG = "metro_glass_dialog"


    private fun glassOn(): Boolean = PreferenceUtil.glassEnabled

    /**
     * Applies the glass as soon as the dialog's window is attached (i.e. when it is shown).
     * Works without touching setOnShowListener, so it never clashes with colorButtons().
     */
    fun applyWhenShown(dialog: Dialog) {
        val decor = dialog.window?.decorView ?: return
        decor.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                apply(dialog)
            }

            override fun onViewDetachedFromWindow(v: View) {}
        })
    }

    /** Call it after the dialog's content exists (after show(), or inside onShow / onStart). */
    fun apply(dialog: Dialog, activity: Activity? = null) {
        if (!glassOn()) return
        val window = dialog.window ?: return
        val host = activity ?: dialog.context.findActivity() ?: return
        val backdrop = host.window.decorView as? ViewGroup ?: return

        val decor = window.decorView
        val content = decor.findViewById<ViewGroup>(android.R.id.content) as? FrameLayout ?: return
        if (content.findViewWithTag<View>(TAG) != null) return

        // the dialog's real panel: AppCompat/Material AlertDialog root, else the first child
        val panel: View = content.findViewById(androidx.appcompat.R.id.parentPanel)
            ?: content.getChildAt(0) ?: return

        // 1) remove the opaque surface but keep the same insets (so the layout doesn't change)
        val rect = Rect()
        decor.background?.getPadding(rect)
        window.setBackgroundDrawable(
            InsetDrawable(ColorDrawable(Color.TRANSPARENT), rect.left, rect.top, rect.right, rect.bottom)
        )
        panel.background = null

        // 2) glass layer behind the panel, following the panel's bounds
        val layer = GlassHelper.createPopupLayer(dialog.context, backdrop, DIALOG_CORNER_DP)
        layer.tag = TAG
        // the glass has an elevation (shadow), so it would be drawn OVER the dialog's content
        // (that's why the text disappeared): lift the panel above it. No background = no shadow.
        panel.elevation = layer.elevation + GlassHelper.dp(dialog.context, 2)
        val lp = FrameLayout.LayoutParams(panel.width, panel.height, Gravity.TOP or Gravity.START)
        content.addView(layer, 0, lp)

        fun follow() {
            val p = layer.layoutParams as FrameLayout.LayoutParams
            if (p.width != panel.width || p.height != panel.height ||
                p.leftMargin != panel.left || p.topMargin != panel.top
            ) {
                p.width = panel.width
                p.height = panel.height
                p.leftMargin = panel.left
                p.topMargin = panel.top
                layer.layoutParams = p
            }
        }
        follow()
        panel.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> follow() }
        // 3) ink: same logic as the popup menus / mini player. Over a bright page (artist / album
        // pages painted from the cover) the text goes dark, over a dark page it goes light.
        layer.prime(Rect()) {}

        layer.onToneExtra = { tintContent(panel, layer) }
        panel.viewTreeObserver.addOnGlobalLayoutListener { tintContent(panel, layer) }
        tintContent(panel, layer)
    }

    private fun tintContent(panel: View, layer: GlassLayer) {
        val ink = layer.inkColor ?: return
        val base = ThemeStore.accentColor(panel.context)
        val accent = ColorUtils.blendARGB(
            base, ColorUtils.blendARGB(base, Color.BLACK, 0.35f), layer.lightness
        )
        tintTree(panel, ink, accent)
    }

    private fun tintTree(v: View, ink: Int, accent: Int) {
        when (v) {
            is Button -> if (v.currentTextColor != accent) v.setTextColor(accent)
            is EditText -> Unit
            is TextView -> if (v.currentTextColor != ink) v.setTextColor(ink)
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) tintTree(v.getChildAt(i), ink, accent)
        }
    }

    private fun Context.findActivity(): Activity? {
        var c: Context? = this
        while (c is ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return null
    }
}

/** Instead of dialog.show(): */
fun AlertDialog.showGlass(activity: Activity? = null): AlertDialog {
    show()
    GlassDialogs.apply(this, activity)
    return this
}

/** In a DialogFragment: override onStart() { super.onStart(); glassify() } */
fun DialogFragment.glassify() {
    dialog?.let { GlassDialogs.apply(it, activity) }
}
