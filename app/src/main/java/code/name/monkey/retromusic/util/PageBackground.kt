package code.name.monkey.retromusic.util

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import androidx.fragment.app.Fragment
import code.name.monkey.retromusic.R
import com.google.android.material.color.MaterialColors

/**
 * The colour the app's pages are really drawn on: the first opaque colour background found
 * going up from the fragment container (the same lookup the search bar glass uses for its base
 * colour), then the theme's surface colour. It is NOT android:colorBackground, which in this
 * theme is a different (greyer) colour and looked washed out.
 */
fun Fragment.pageBackgroundColor(): Int {
    var v: View? = activity?.findViewById(R.id.fragment_container)
    while (v != null) {
        val bg = v.background
        if (bg is ColorDrawable && Color.alpha(bg.color) == 255) return bg.color
        v = v.parent as? View
    }
    val anchor = view ?: activity?.window?.decorView ?: return Color.BLACK
    return MaterialColors.getColor(anchor, com.google.android.material.R.attr.colorSurface, Color.BLACK)
}
