package code.name.monkey.retromusic.fragments.home

import android.view.View
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.graphics.ColorUtils
import code.name.monkey.retromusic.R
import com.google.android.material.color.MaterialColors

/**
 * Look of one square tile on the home screen.
 * Light theme: bright gradient ([gradient]) with white title and [ink] icon.
 * Dark theme: deep, muted gradient ([darkGradient]) with the title and icon in a light tint of
 * the same hue ([darkInk]), like the reference app.
 */
class HomeTileStyle(
    @StringRes val title: Int,
    @DrawableRes val icon: Int,
    val gradient: IntArray,
    val ink: Int,
    val darkGradient: IntArray,
    val darkInk: Int
) {

    fun gradientFor(dark: Boolean): IntArray = if (dark) darkGradient else gradient

    fun inkFor(dark: Boolean): Int = if (dark) darkInk else ink
}

object HomeTileStyles {

    /** True when the screen background is dark (follows the app theme, not only the system). */
    fun isDark(view: View): Boolean {
        val background = MaterialColors.getColor(view, com.google.android.material.R.attr.colorSurface)
        return ColorUtils.calculateLuminance(background) < 0.5
    }

    // cyan -> teal: the past, calm
    val HISTORY = HomeTileStyle(
        R.string.tile_history, R.drawable.ic_tile_history,
        intArrayOf(0xFF27B5D6.toInt(), 0xFF1584AE.toInt(), 0xFF0B5A80.toInt()),
        0xFFC9F4FF.toInt(),
        intArrayOf(0xFF1F6178.toInt(), 0xFF184B5F.toInt(), 0xFF11333F.toInt()),
        0xFFBDEBF5.toInt()
    )

    // coral -> burnt orange: heat, energy
    val MOST_PLAYED = HomeTileStyle(
        R.string.tile_most_played, R.drawable.ic_tile_flame,
        intArrayOf(0xFFFF7A56.toInt(), 0xFFE84F2B.toInt(), 0xFFA83418.toInt()),
        0xFFFFD2BF.toInt(),
        intArrayOf(0xFF7A3A28.toInt(), 0xFF5E2D1F.toInt(), 0xFF432016.toInt()),
        0xFFFFCDB8.toInt()
    )

    // blue -> violet -> magenta (from the reference artwork)
    val FAVORITE = HomeTileStyle(
        R.string.tile_favorite, R.drawable.ic_tile_heart,
        intArrayOf(0xFF2F6BFF.toInt(), 0xFF7C3FE8.toInt(), 0xFFC12BD0.toInt()),
        0xFFF6D3FF.toInt(),
        intArrayOf(0xFF2D4399.toInt(), 0xFF4E3194.toInt(), 0xFF6C2A86.toInt()),
        0xFFE9D2FF.toInt()
    )

    // emerald: "right now"
    val FOR_YOU_NOW = HomeTileStyle(
        R.string.tile_for_you_now, R.drawable.ic_tile_radar,
        intArrayOf(0xFF25CD90.toInt(), 0xFF129B69.toInt(), 0xFF0A6A49.toInt()),
        0xFFC8FFE3.toInt(),
        intArrayOf(0xFF1B6B48.toInt(), 0xFF145337.toInt(), 0xFF0D3A26.toInt()),
        0xFFC1F5D6.toInt()
    )

    // rose -> plum, like the vinyl card in the reference
    val REDISCOVER = HomeTileStyle(
        R.string.tile_rediscover, R.drawable.ic_tile_vinyl,
        intArrayOf(0xFFE0618F.toInt(), 0xFFAF3F6E.toInt(), 0xFF70264A.toInt()),
        0xFFFFC9DC.toInt(),
        intArrayOf(0xFF7A3650.toInt(), 0xFF5E2A3F.toInt(), 0xFF441E2D.toInt()),
        0xFFF7C2D4.toInt()
    )

    // golden amber
    val STATS = HomeTileStyle(
        R.string.tile_listening_stats, R.drawable.ic_tile_bars,
        intArrayOf(0xFFFFB327.toInt(), 0xFFDB8A07.toInt(), 0xFF9A5A04.toInt()),
        0xFFFFEBB0.toInt(),
        intArrayOf(0xFF7A5A14.toInt(), 0xFF614611.toInt(), 0xFF47320C.toInt()),
        0xFFF7DE9C.toInt()
    )
}
