/*
 * Copyright (c) 2020 Hemanth Savarala.
 *
 * Licensed under the GNU General Public License v3
 *
 * This is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by
 *  the Free Software Foundation either version 3 of the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 */
package code.name.monkey.retromusic.util

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import androidx.annotation.RequiresApi
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.RecyclerView
import code.name.monkey.appthemehelper.ThemeStore.Companion.accentColor
import code.name.monkey.appthemehelper.util.ColorUtil.isColorLight
import code.name.monkey.appthemehelper.util.MaterialValueHelper.getPrimaryTextColor
import code.name.monkey.retromusic.views.PopupBackground
import me.zhanghai.android.fastscroll.FastScroller
import me.zhanghai.android.fastscroll.FastScrollerBuilder
import me.zhanghai.android.fastscroll.PopupStyles
import java.util.WeakHashMap
import kotlin.math.ceil

object ThemedFastScroller {
    fun create(view: ViewGroup): FastScroller {
        val context = view.context
        val color = accentColor(context)
        val textColor = getPrimaryTextColor(context, isColorLight(color))

        // لون جسم الشكل: لون خلفية الثيم ممزوج بنسبة بسيطة من الـ accent
        // (معتم تمامًا، عشان مايظهرش اللي تحته). لون السهمين: الـ accent
        // نفسه، ولو كان غامق/فاتح أوي على الجسم (تباين ضعيف) بنفتحه أو
        // بنغمّقه تدريجيًا لحد ما يبقى واضح.
        val background = ColorUtils.setAlphaComponent(
            resolveThemeColor(context, android.R.attr.colorBackground, Color.BLACK), 255
        )
        // وضع Just Black: لون سطح الثيم (colorSurface) بيبقى أسود تمامًا. وقتها الشكل بياخد لون غامق
        // جدًا (أسود فيه لمسة بسيطة من الـ accent) قريب من لون البار اللي فوق، بدل الخلطة الأفتح.
        // باقي الثيمات زي ما هي.
        val surface = resolveThemeColor(
            context, com.google.android.material.R.attr.colorSurface, background
        )
        val isJustBlack = ColorUtils.calculateLuminance(surface) < 0.002
        val blended = if (isJustBlack) {
            ColorUtils.blendARGB(Color.BLACK, color, 0.09f)
        } else {
            ColorUtils.blendARGB(background, color, 0.18f)
        }
        // لازم يبقى معتم تمامًا: لو الـ accent فيه شفافية بسيطة الخلطة بتطلع شفافة شوية،
        // و ColorUtils.calculateContrast بترمي استثناء مع أي خلفية شفافة (ده كان سبب الكراش).
        val bodyColor = ColorUtils.setAlphaComponent(blended, 255)
        val arrowColor = ensureContrast(color, bodyColor)

        absorbSideMargins(view)
        val fastScrollerBuilder = FastScrollerBuilder(view)
        // بعض الشاشات (زي شبكة الفنانين الدايرية) بتحط padding جانبي على الـ
        // RecyclerView، والمكتبة كانت هتزق الشكل لجوه بنفس المقدار. بنثبت
        // الجوانب على صفر عشان الشكل يفضل لاصق في حافة الشاشة، ونحافظ على
        // الـ padding الرأسي زي ما هو.
        fastScrollerBuilder.setPadding(0, view.paddingTop, 0, view.paddingBottom)
        // الشكل: نص دائرة لاصق في حافة الشاشة اليمين فيه سهمين (الرسم في
        // ThumbDrawable تحت). ألوانه بتتحسب من الثيم وقت الإنشاء - عشان كده
        // اتعمل بالكود بدل ملف XML ثابت (afs_custom_thumb.xml بقى مش مستخدم
        // وتقدر تمسحه).
        val thumbDrawable = ThumbDrawable(context, view, background, bodyColor, arrowColor)
        fastScrollerBuilder.setThumbDrawable(thumbDrawable)
        hookScroll(view, thumbDrawable)
        // الخط الأبيض الشفاف اللي كان ظاهر تحت الشكل هو الـ track الافتراضي
        // للمكتبة، فبنستبدله بـ drawable شفاف تمامًا. لازم مقاسه يبقى 0 مش
        // سالب (ColorDrawable بيرجع -1 والمكتبة بترمي IllegalArgumentException).
        fastScrollerBuilder.setTrackDrawable(EmptyTrackDrawable())
        fastScrollerBuilder.setPopupStyle { popupText ->
            PopupStyles.MD2.accept(popupText)
            popupText.background = PopupBackground(context, color)
            popupText.setTextColor(textColor)
        }
        return fastScrollerBuilder.build()
    }

    /**
     * الشكل بيتحط جوه الـ overlay بتاع الـ RecyclerView، فبيلزق في حافة الـ RecyclerView نفسه
     * مش حافة الشاشة. لو الـ RecyclerView عليه margin جانبي (زي شبكات الفنانين والألبومات
     * والبلاي ليست) الشكل بيفضل بعيد عن الحافة بنفس المقدار، ومينفعش يطلع برا حدود الـ
     * RecyclerView (بيتقص). الحل: نحوّل الـ margin الجانبي لـ padding، فالـ RecyclerView يبقى
     * بعرض الشاشة والمحتوى مكانه بالظبط (واللي فوق/تحت ما اتغيّرش).
     */
    private fun absorbSideMargins(view: ViewGroup) {
        val lp = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        val left = lp.leftMargin
        val right = lp.rightMargin
        if (left == 0 && right == 0) return
        view.setPadding(
            view.paddingLeft + left, view.paddingTop,
            view.paddingRight + right, view.paddingBottom
        )
        lp.leftMargin = 0
        lp.rightMargin = 0
        view.layoutParams = lp
    }

    private val scrollHooks = WeakHashMap<RecyclerView, RecyclerView.OnScrollListener>()

    /**
     * الزجاج بيترسم تاني كل فريم طول ما الشكل ظاهر (alpha > 0)، بس الحلقة دي بتقف لما الشكل يختفي،
     * ولما يظهر تاني (بحركة بتتنفذ على الـ RenderThread) الـ view مابيتعملهوش invalidate، فالشكل
     * كان بيفضل بصورة بلور قديمة متجمدة. فبنربط الرسم بسكرول الـ RecyclerView: أي سكرول يرسم الشكل
     * من جديد، فالبلور دايماً بيمشي مع المحتوى.
     */
    private fun hookScroll(view: ViewGroup, drawable: ThumbDrawable) {
        val recycler = view as? RecyclerView ?: return
        scrollHooks.remove(recycler)?.let { recycler.removeOnScrollListener(it) }
        val listener = object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                drawable.refresh()
            }
        }
        recycler.addOnScrollListener(listener)
        scrollHooks[recycler] = listener
    }

    private fun resolveThemeColor(context: Context, attr: Int, fallback: Int): Int {
        val typedValue = TypedValue()
        val resolved = context.theme.resolveAttribute(attr, typedValue, true)
        return if (resolved &&
            typedValue.type >= TypedValue.TYPE_FIRST_COLOR_INT &&
            typedValue.type <= TypedValue.TYPE_LAST_COLOR_INT
        ) typedValue.data else fallback
    }

    private fun ensureContrast(foreground: Int, background: Int): Int {
        val target = if (ColorUtils.calculateLuminance(background) > 0.5) Color.BLACK else Color.WHITE
        var result = ColorUtils.setAlphaComponent(foreground, 255)
        var steps = 0
        while (ColorUtils.calculateContrast(result, background) < 3.0 && steps < 10) {
            result = ColorUtils.blendARGB(result, target, 0.15f)
            steps++
        }
        return result
    }

    /** track شفاف تمامًا ومقاسه 0 (المكتبة بترفض المقاس السالب). */
    private class EmptyTrackDrawable : Drawable() {
        override fun draw(canvas: Canvas) {}
        override fun getIntrinsicWidth() = 0
        override fun getIntrinsicHeight() = 0
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}

        @Suppress("OVERRIDE_DEPRECATION")
        override fun getOpacity() = PixelFormat.TRANSPARENT
    }

    /**
     * نص دائرة زجاجي (الجزء المدوّر ناحية الشمال، والحافة اليمين مستقيمة) فيه سهمين.
     * المقاسات: 34dp عرض × 56dp ارتفاع.
     *
     * الزجاج: بنرسم أبناء الـ [backdrop] (الأغاني اللي بتعدّي تحت الشكل) جوه RenderNode
     * ونبلّرها بـ RenderEffect (Android 12+)، وفوقها film + لمعة + حافة (rim light). بنرسم
     * الأبناء بس مش الـ backdrop نفسه، لأن الشكل جوه الـ overlay بتاعه (يعني كان هيرسم نفسه).
     * تحت Android 12 أو مع خامة "fastest" بيبقى جسم شبه شفاف عادي.
     */
    private class ThumbDrawable(
        context: Context,
        private val backdrop: ViewGroup,
        private val themeBackground: Int,
        bodyColor: Int,
        arrowColor: Int
    ) : Drawable() {

        private val density = context.resources.displayMetrics.density
        private val widthPx = (34 * density).toInt()
        private val heightPx = (56 * density).toInt()

        private val live = PreferenceUtil.glassMaterial != "fastest" &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        private val sigma = (PreferenceUtil.glassFrost * density * 0.6f).coerceAtLeast(0.5f)
        private val saturation = 1f + PreferenceUtil.glassColour.coerceIn(0, 100) / 100f
        private val film = PreferenceUtil.glassFilm.coerceIn(0, 100)
        private val margin = ceil(sigma * 3f).toInt()
        private val strokePx = 0.75f * density

        private var alphaValue = 255
        private var node: RenderNode? = null
        private var effect: RenderEffect? = null

        private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = ColorUtils.setAlphaComponent(bodyColor, 215)
        }
        private val filmPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = strokePx
        }
        private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = arrowColor
        }
        private val bodyPath = Path()
        private val rimPath = Path()
        private val arrowPath = Path()
        private val arcRect = RectF()

        override fun getIntrinsicWidth() = widthPx
        override fun getIntrinsicHeight() = heightPx

        /** يرسم الشكل تاني (البلور بيتحسب من المحتوى الحالي). */
        fun refresh() = invalidateSelf()

        private fun buildPath(path: Path, inset: Float) {
            val left = bounds.left + inset
            val top = bounds.top + inset
            val right = bounds.right.toFloat()
            val bottom = bounds.bottom - inset
            val radius = (bottom - top) / 2f
            // الجسم: من الحافة اليمين المستقيمة، والجزء الشمال نص دائرة.
            arcRect.set(left, top, left + radius * 2f, bottom)
            path.reset()
            path.moveTo(right, top)
            path.lineTo(left + radius, top)
            path.arcTo(arcRect, 270f, -180f)
            path.lineTo(right, bottom)
            path.close()
        }

        override fun onBoundsChange(bounds: android.graphics.Rect) {
            if (bounds.isEmpty) return
            buildPath(bodyPath, 0f)
            buildPath(rimPath, strokePx / 2f)
            val top = bounds.top.toFloat()
            val bottom = bounds.bottom.toFloat()
            sheenPaint.shader = LinearGradient(
                0f, top, 0f, bottom,
                intArrayOf(Color.argb(40, 255, 255, 255), Color.argb(0, 255, 255, 255), Color.argb(18, 0, 0, 0)),
                floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
            )
        }

        /** لون الصفحة الحقيقي: أقرب خلفية لونية (ColorDrawable) فوق الـ backdrop، وإلا لون الثيم. */
        private fun pageColor(): Int {
            var v: View? = backdrop
            while (v != null) {
                val bg = v.background
                if (bg is ColorDrawable && Color.alpha(bg.color) == 255) return bg.color
                v = v.parent as? View
            }
            return themeBackground
        }

        override fun draw(canvas: Canvas) {
            if (bounds.isEmpty) return
            val page = pageColor()
            val dark = ColorUtils.calculateLuminance(page) < 0.5

            val rootSave = if (alphaValue < 255) {
                canvas.saveLayerAlpha(
                    bounds.left.toFloat(), bounds.top.toFloat(),
                    bounds.right.toFloat(), bounds.bottom.toFloat(), alphaValue
                )
            } else canvas.save()

            canvas.save()
            canvas.clipPath(bodyPath)
            var blurred = false
            if (live && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && canvas.isHardwareAccelerated) {
                blurred = drawBlur(canvas, page)
            }
            if (blurred) {
                filmPaint.color = ColorUtils.setAlphaComponent(
                    if (dark) Color.WHITE else page,
                    (if (dark) film / 2 else film * 2).coerceIn(0, 255)
                )
                canvas.drawPath(bodyPath, filmPaint)
            } else {
                canvas.drawPath(bodyPath, bodyPaint)
            }
            canvas.drawPath(bodyPath, sheenPaint)
            canvas.restore()

            // الحافة: منورة من فوق، خافتة في النص، ومنورة تاني من تحت (ضوء بيمسك الزجاج)
            val top = bounds.top.toFloat()
            val bottom = bounds.bottom.toFloat()
            val mid = if (dark) Color.argb(21, 255, 255, 255) else Color.argb(15, 0, 0, 0)
            rimPaint.shader = LinearGradient(
                0f, top, 0f, bottom,
                intArrayOf(
                    if (dark) Color.argb(112, 255, 255, 255) else Color.argb(165, 255, 255, 255),
                    mid, mid,
                    if (dark) Color.argb(45, 255, 255, 255) else Color.argb(34, 0, 0, 0)
                ),
                floatArrayOf(0f, 0.4f, 0.6f, 1f), Shader.TileMode.CLAMP
            )
            canvas.drawPath(rimPath, rimPaint)

            // السهمين: عرض 11dp وارتفاع 5.5dp، والمسافة بينهم 7dp.
            val left = bounds.left.toFloat()
            val right = bounds.right.toFloat()
            val centerX = left + (right - left) * 0.68f
            val centerY = (top + bottom) / 2f
            val halfWidth = 5.5f * density
            val arrowHeight = 5.5f * density
            val halfGap = 3.5f * density

            arrowPath.reset()
            // سهم لفوق
            arrowPath.moveTo(centerX - halfWidth, centerY - halfGap)
            arrowPath.lineTo(centerX, centerY - halfGap - arrowHeight)
            arrowPath.lineTo(centerX + halfWidth, centerY - halfGap)
            arrowPath.close()
            // سهم لتحت
            arrowPath.moveTo(centerX - halfWidth, centerY + halfGap)
            arrowPath.lineTo(centerX, centerY + halfGap + arrowHeight)
            arrowPath.lineTo(centerX + halfWidth, centerY + halfGap)
            arrowPath.close()
            canvas.drawPath(arrowPath, arrowPaint)

            canvas.restoreToCount(rootSave)
        }

        @RequiresApi(Build.VERSION_CODES.S)
        private fun drawBlur(canvas: Canvas, page: Int): Boolean {
            val thumb = callback as? View ?: return false
            if (!backdrop.isAttachedToWindow) return false

            val w = bounds.width() + 2 * margin
            val h = bounds.height() + 2 * margin
            val n = node ?: RenderNode("glass-thumb").also { node = it }
            n.setPosition(0, 0, w, h)
            val fx = effect ?: run {
                val blur = RenderEffect.createBlurEffect(sigma, sigma, Shader.TileMode.CLAMP)
                val colours = ColorMatrix().apply { setSaturation(saturation) }
                RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(colours), blur)
            }.also { effect = it }
            n.setRenderEffect(fx)

            // مكان أول بكسل في الـ node جوه الـ backdrop. الشكل جوه الـ overlay بتاع الـ
            // backdrop، فإحداثياته (left/top + translation) هي إحداثيات الـ backdrop نفسها.
            // translationX مش بنقراه: المكتبة بتحرّك الشكل أفقياً بس عشان تخفيه/تظهره، وبحركة
            // بتتنفذ على الـ RenderThread، فالقيمة اللي بنقراها وقت الحركة بتبقى قديمة وبتخلّي
            // البلور ياخد من مكان غلط. الشكل بيتبلّر دايماً من مكانه الأصلي (الحافة اليمين).
            val originX = (thumb.left + bounds.left - margin).toFloat()
            val originY = thumb.top + thumb.translationY + bounds.top - margin

            val rec = n.beginRecording(w, h)
            try {
                rec.drawColor(page)
                rec.translate(-originX, -originY)
                for (i in 0 until backdrop.childCount) {
                    val c = backdrop.getChildAt(i)
                    if (c.visibility != View.VISIBLE) continue
                    val cx = c.left + c.translationX - backdrop.scrollX
                    val cy = c.top + c.translationY - backdrop.scrollY
                    // اللي مش تحت الشكل مالوش لازمة
                    if (cy + c.height < originY || cy > originY + h) continue
                    if (cx + c.width < originX || cx > originX + w) continue
                    rec.save()
                    rec.translate(cx, cy)
                    c.draw(rec)
                    rec.restore()
                }
            } finally {
                n.endRecording()
            }

            canvas.save()
            canvas.translate(bounds.left - margin.toFloat(), bounds.top - margin.toFloat())
            canvas.drawRenderNode(n)
            canvas.restore()

            // المحتوى اللي تحت بيتحرك: ارسم تاني الفريم الجاي طول ما الشكل ظاهر
            if (thumb.visibility == View.VISIBLE && thumb.alpha > 0.02f && backdrop.isShown) {
                thumb.postInvalidateOnAnimation()
            }
            return true
        }

        override fun setAlpha(alpha: Int) {
            alphaValue = alpha
            invalidateSelf()
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            bodyPaint.colorFilter = colorFilter
            arrowPaint.colorFilter = colorFilter
            invalidateSelf()
        }

        @Suppress("OVERRIDE_DEPRECATION")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}
