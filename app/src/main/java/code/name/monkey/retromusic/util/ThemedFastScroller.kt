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
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.ViewGroup
import androidx.core.graphics.ColorUtils
import code.name.monkey.appthemehelper.ThemeStore.Companion.accentColor
import code.name.monkey.appthemehelper.util.ColorUtil.isColorLight
import code.name.monkey.appthemehelper.util.MaterialValueHelper.getPrimaryTextColor
import code.name.monkey.retromusic.views.PopupBackground
import me.zhanghai.android.fastscroll.FastScroller
import me.zhanghai.android.fastscroll.FastScrollerBuilder
import me.zhanghai.android.fastscroll.PopupStyles

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
        val bodyColor = ColorUtils.blendARGB(background, color, 0.18f)
        val arrowColor = ensureContrast(color, bodyColor)

        val fastScrollerBuilder = FastScrollerBuilder(view)
        // الشكل: نص دائرة لاصق في حافة الشاشة اليمين فيه سهمين (الرسم في
        // ThumbDrawable تحت). ألوانه بتتحسب من الثيم وقت الإنشاء - عشان كده
        // اتعمل بالكود بدل ملف XML ثابت (afs_custom_thumb.xml بقى مش مستخدم
        // وتقدر تمسحه).
        fastScrollerBuilder.setThumbDrawable(ThumbDrawable(context, bodyColor, arrowColor))
        // الخط الأبيض الشفاف اللي كان ظاهر تحت الشكل هو الـ track الافتراضي
        // للمكتبة، فبنستبدله بـ drawable شفاف تمامًا.
        fastScrollerBuilder.setTrackDrawable(ColorDrawable(Color.TRANSPARENT))
        fastScrollerBuilder.setPopupStyle { popupText ->
            PopupStyles.MD2.accept(popupText)
            popupText.background = PopupBackground(context, color)
            popupText.setTextColor(textColor)
        }
        return fastScrollerBuilder.build()
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

    /**
     * نص دائرة (الجزء المدوّر ناحية الشمال، والحافة اليمين مستقيمة) فيه سهمين
     * فوق وتحت. المقاسات: 34dp عرض × 56dp ارتفاع.
     */
    private class ThumbDrawable(
        context: Context,
        bodyColor: Int,
        arrowColor: Int
    ) : Drawable() {

        private val density = context.resources.displayMetrics.density
        private val widthPx = (34 * density).toInt()
        private val heightPx = (56 * density).toInt()

        private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = bodyColor
        }
        private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = arrowColor
        }
        private val bodyPath = Path()
        private val arrowPath = Path()
        private val arcRect = RectF()

        override fun getIntrinsicWidth() = widthPx
        override fun getIntrinsicHeight() = heightPx

        override fun draw(canvas: Canvas) {
            val left = bounds.left.toFloat()
            val top = bounds.top.toFloat()
            val right = bounds.right.toFloat()
            val bottom = bounds.bottom.toFloat()
            val radius = (bottom - top) / 2f

            // الجسم: من الحافة اليمين المستقيمة، والجزء الشمال نص دائرة.
            arcRect.set(left, top, left + radius * 2f, bottom)
            bodyPath.reset()
            bodyPath.moveTo(right, top)
            bodyPath.lineTo(left + radius, top)
            bodyPath.arcTo(arcRect, 270f, -180f)
            bodyPath.lineTo(right, bottom)
            bodyPath.close()
            canvas.drawPath(bodyPath, bodyPaint)

            // السهمين: عرض 11dp وارتفاع 5.5dp، والمسافة بينهم 7dp.
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
        }

        override fun setAlpha(alpha: Int) {
            bodyPaint.alpha = alpha
            arrowPaint.alpha = alpha
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
