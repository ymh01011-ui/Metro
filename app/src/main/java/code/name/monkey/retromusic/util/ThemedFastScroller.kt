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

import android.view.ViewGroup
import code.name.monkey.appthemehelper.ThemeStore.Companion.accentColor
import code.name.monkey.appthemehelper.util.ColorUtil.isColorLight
import code.name.monkey.appthemehelper.util.MaterialValueHelper.getPrimaryTextColor
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.views.PopupBackground
import me.zhanghai.android.fastscroll.FastScroller
import me.zhanghai.android.fastscroll.FastScrollerBuilder
import me.zhanghai.android.fastscroll.PopupStyles
import androidx.core.content.ContextCompat

object ThemedFastScroller {
    fun create(view: ViewGroup): FastScroller {
        val context = view.context
        val color = accentColor(context)
        val textColor = getPrimaryTextColor(context, isColorLight(color))
        val fastScrollerBuilder = FastScrollerBuilder(view)
        // بدل ما نعتمد على شكل المكتبة الجاهز (MD2 أو الافتراضي) اللي مش
        // متأكدين منه فعليًا من غير معاينة، بنستخدم رسمة مخصصة بالكامل
        // (afs_custom_thumb.xml): نص دائرة بارزة من الحافة، فيها سهمين فوق
        // وتحت، من غير أي خط/track. الشكل والألوان متحطين صريح في ملف
        // الـ drawable نفسه، فسهل تتعدل من هناك لو الشكل محتاج ضبط.
        fastScrollerBuilder.setThumbDrawable(
            ContextCompat.getDrawable(context, R.drawable.afs_custom_thumb)!!
        )
        fastScrollerBuilder.setPopupStyle { popupText ->
            PopupStyles.MD2.accept(popupText)
            popupText.background = PopupBackground(context, color)
            popupText.setTextColor(textColor)
        }
        return fastScrollerBuilder.build()
    }
}
