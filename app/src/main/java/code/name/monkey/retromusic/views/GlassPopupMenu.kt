package code.name.monkey.retromusic.views

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.MenuRes
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.Toolbar
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import code.name.monkey.retromusic.util.PreferenceUtil
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.color.MaterialColors
import kotlin.math.max
import kotlin.math.min

/**
 * Drop-in replacement for [PopupMenu] whose panel is real glass: the page behind is blurred live
 * (same [GlassLayer] and same Glass settings as the bottom bar and the mini player).
 *
 * Same API as the parts of PopupMenu the app uses ([menu], [menuInflater], [inflate],
 * [setOnMenuItemClickListener], [show]). With the Glass effect switched off, or when there is no
 * activity to blur, it simply shows the normal [PopupMenu].
 */
class GlassPopupMenu(context: Context, private val anchor: View) {

    private val holder = PopupMenu(context, anchor)

    val menu: Menu get() = holder.menu

    val menuInflater: MenuInflater get() = holder.menuInflater

    fun inflate(@MenuRes menuRes: Int) = holder.inflate(menuRes)

    fun setOnMenuItemClickListener(listener: PopupMenu.OnMenuItemClickListener?) {
        holder.setOnMenuItemClickListener(listener)
    }

    fun setOnDismissListener(listener: PopupMenu.OnDismissListener?) {
        dismissListener = listener
        holder.setOnDismissListener(listener)
    }

    private var dismissListener: PopupMenu.OnDismissListener? = null

    fun show() {
        if (!PreferenceUtil.glassEnabled || !GlassMenuWindow(
                anchor, holder.menu, emptySet()
            ) { dismissListener?.onDismiss(holder) }.show()
        ) {
            holder.show()
        }
    }

    companion object {
        /**
         * Shows the overflow menu of [toolbar] as glass, anchored on [anchor] (the custom three-dot
         * button). [excludeIds] are items the toolbar already shows as icons.
         */
        @JvmStatic
        fun showToolbarOverflow(toolbar: Toolbar, anchor: View, vararg excludeIds: Int) {
            if (!PreferenceUtil.glassEnabled ||
                !GlassMenuWindow(anchor, toolbar.menu, excludeIds.toSet(), null).show()
            ) {
                toolbar.showOverflowMenu()
            }
        }
    }
}

/** The glass window itself: a list of rows (with sub menus) inside a [GlassLayer]. */
private class GlassMenuWindow(
    private val anchor: View,
    private val rootMenu: Menu,
    private val exclude: Set<Int>,
    private val onDismiss: (() -> Unit)?,
) {
    private val context: Context = anchor.context
    private val density = context.resources.displayMetrics.density
    private val inkViews = ArrayList<TextView>()
    private val tickViews = ArrayList<MaterialCheckBox>()
    private lateinit var layer: GlassLayer
    private lateinit var column: LinearLayout
    private var popup: PopupWindow? = null

    private fun dp(value: Int) = (value * density).toInt()

    private fun findActivity(): Activity? {
        var c: Context? = context
        while (c != null) {
            if (c is Activity) return c
            c = (c as? ContextWrapper)?.baseContext
        }
        return null
    }

    /** @return false when the glass menu could not be built (caller falls back to a normal one) */
    fun show(): Boolean {
        val activity = findActivity() ?: return false
        val decor = activity.window?.decorView as? ViewGroup ?: return false
        if (!anchor.isAttachedToWindow || rootMenu.size() == 0) return false

        layer = GlassHelper.createPopupLayer(context, decor, 24)
        column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, dp(10))
        }
        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(column, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        layer.addView(
            scroll,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        layer.onToneExtra = { applyInk() }

        render(rootMenu, null)

        // ---- size ----
        val metrics = context.resources.displayMetrics
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels
        val margin = dp(8)
        val pad = dp(12) // room around the glass for its shadow
        val maxW = min(dp(320), screenW - 2 * margin)
        column.measure(
            View.MeasureSpec.makeMeasureSpec(maxW, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val glassW = max(min(column.measuredWidth, maxW), min(dp(200), maxW))
        val naturalH = column.measuredHeight

        val insets = ViewCompat.getRootWindowInsets(decor)
            ?.getInsets(WindowInsetsCompat.Type.systemBars())
        val topLimit = (insets?.top ?: 0) + margin
        val bottomLimit = screenH - (insets?.bottom ?: 0) - margin

        val loc = IntArray(2)
        anchor.getLocationInWindow(loc)
        val anchorTop = loc[1]
        val anchorBottom = loc[1] + anchor.height
        val below = bottomLimit - anchorBottom
        val above = anchorTop - topLimit
        val placeBelow = naturalH <= below || below >= above
        val glassH = min(naturalH, max(if (placeBelow) below else above, dp(120)))
        val y = if (placeBelow) anchorBottom else anchorTop - glassH

        val anchorEnd = loc[0] + anchor.width
        val x = (anchorEnd - glassW).coerceIn(margin, max(margin, screenW - glassW - margin))

        val container = FrameLayout(context).apply {
            clipChildren = false
            clipToPadding = false
            setPadding(pad, pad, pad, pad)
            addView(layer, FrameLayout.LayoutParams(glassW, glassH))
        }
        val window = PopupWindow(container, glassW + 2 * pad, glassH + 2 * pad, true).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true
            isClippingEnabled = false
            elevation = 0f
            setOnDismissListener { onDismiss?.invoke() }
        }
        popup = window

        // grows out of the corner next to the button
        layer.cheapFade = true
        layer.alpha = 0f
        layer.scaleX = 0.92f
        layer.scaleY = 0.92f
        layer.pivotX = glassW.toFloat()
        layer.pivotY = if (placeBelow) 0f else glassH.toFloat()

        val reveal = {
            // The first frame of a live-blur layer is the expensive one (the whole page is
            // recorded and blurred). Let it be drawn while the panel is still invisible, and
            // only then start the animation, so the animation itself never contains that hitch.
            layer.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    if (layer.viewTreeObserver.isAlive) layer.viewTreeObserver.removeOnPreDrawListener(this)
                    layer.post {
                        layer.animate()
                            .alpha(1f).scaleX(1f).scaleY(1f)
                            .setDuration(200)
                            .setInterpolator(DecelerateInterpolator(1.5f))
                            .start()
                    }
                    return true
                }
            })
            try {
                window.showAtLocation(decor, Gravity.NO_GRAVITY, x - pad, y - pad)
            } catch (e: Exception) {
                onDismiss?.invoke()
            }
        }

        // Measure the page where the panel will open BEFORE showing it, so it opens already in
        // the right tone (ink, rim, dark veil) instead of jumping once the first regular
        // measurement arrives. This takes a few milliseconds and has a short timeout.
        layer.prime(Rect(x, y, x + glassW, y + glassH)) {
            if (anchor.isAttachedToWindow) reveal() else onDismiss?.invoke()
        }
        return true
    }

    private fun inkColor(): Int =
        layer.inkColor ?: MaterialColors.getColor(
            context, com.google.android.material.R.attr.colorOnSurface, Color.WHITE
        )

    private fun applyInk() {
        val ink = inkColor()
        for (view in inkViews) {
            val alpha = if (view.isEnabled) 255 else 90
            view.setTextColor(ColorUtils.setAlphaComponent(ink, alpha))
        }
        for (box in tickViews) {
            box.buttonTintList = ColorStateList.valueOf(ink)
        }
    }

    /** Fills the column with [menu]'s items; [title] is set for a sub menu (shows a back row). */
    private fun render(menu: Menu, title: CharSequence?) {
        column.removeAllViews()
        inkViews.clear()
        tickViews.clear()

        if (title != null) {
            column.addView(
                row("‹   $title", enabled = true, trailing = null, bold = true) { render(rootMenu, null) }
            )
        }
        for (i in 0 until menu.size()) {
            val item = menu.getItem(i)
            val itemTitle = item.title
            if (!item.isVisible || item.itemId == 0 || item.itemId in exclude ||
                itemTitle.isNullOrEmpty()
            ) continue
            val inSub = title != null
            val trailing: View? = when {
                item.hasSubMenu() -> chevron()
                item.isCheckable && !inSub -> checkbox(item.isChecked)
                item.isCheckable && item.isChecked -> tick()
                else -> null
            }
            column.addView(row(itemTitle, item.isEnabled, trailing, bold = false) { choose(item) })
        }
        applyInk()
    }

    private fun choose(item: MenuItem) {
        if (item.hasSubMenu()) {
            render(item.subMenu!!, item.title)
            return
        }
        popup?.dismiss()
        // goes through the menu's own callback, exactly as a tap on a normal menu would
        if (item.itemId != 0) rootMenu.performIdentifierAction(item.itemId, 0)
    }

    private fun row(
        text: CharSequence,
        enabled: Boolean,
        trailing: View?,
        bold: Boolean,
        onClick: () -> Unit,
    ): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
            setPadding(dp(20), 0, dp(16), 0)
            val outValue = TypedValue()
            if (context.theme.resolveAttribute(
                    android.R.attr.selectableItemBackground, outValue, true
                )
            ) {
                setBackgroundResource(outValue.resourceId)
            }
            isEnabled = enabled
            isClickable = enabled
            if (enabled) setOnClickListener { onClick() }
        }
        val label = TextView(context).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            isEnabled = enabled
        }
        inkViews.add(label)
        row.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (trailing != null) {
            row.addView(
                trailing,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = dp(16) }
            )
        }
        return row
    }

    private fun chevron(): View = TextView(context).apply {
        text = "›"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
        inkViews.add(this)
    }

    private fun tick(): View = TextView(context).apply {
        text = "✓"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        inkViews.add(this)
    }

    private fun checkbox(checked: Boolean): View = MaterialCheckBox(context).apply {
        isChecked = checked
        isClickable = false
        isFocusable = false
        tickViews.add(this)
    }
}
