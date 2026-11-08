package code.name.monkey.retromusic.fragments.home

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import code.name.monkey.retromusic.R

/**
 * Look of one square tile on the home screen.
 * [gradient] runs top-left to bottom-right (light to dark), [ink] is the colour of the icon,
 * of the thin outline and of the soft glow behind the icon.
 */
class HomeTileStyle(
    @StringRes val title: Int,
    @DrawableRes val icon: Int,
    val gradient: IntArray,
    val ink: Int
)

object HomeTileStyles {

    // bright cyan -> deep teal-blue: the past, calm
    val HISTORY = HomeTileStyle(
        R.string.tile_history, R.drawable.ic_tile_history,
        intArrayOf(0xFF27B5D6.toInt(), 0xFF1584AE.toInt(), 0xFF0B5A80.toInt()),
        0xFFC9F4FF.toInt()
    )

    // vivid coral -> burnt orange: heat, energy
    val MOST_PLAYED = HomeTileStyle(
        R.string.tile_most_played, R.drawable.ic_tile_flame,
        intArrayOf(0xFFFF7A56.toInt(), 0xFFE84F2B.toInt(), 0xFFA83418.toInt()),
        0xFFFFD2BF.toInt()
    )

    // electric blue -> violet -> magenta (taken from the reference artwork)
    val FAVORITE = HomeTileStyle(
        R.string.tile_favorite, R.drawable.ic_tile_heart,
        intArrayOf(0xFF2F6BFF.toInt(), 0xFF7C3FE8.toInt(), 0xFFC12BD0.toInt()),
        0xFFF6D3FF.toInt()
    )

    // fresh emerald: "right now"
    val FOR_YOU_NOW = HomeTileStyle(
        R.string.tile_for_you_now, R.drawable.ic_tile_radar,
        intArrayOf(0xFF25CD90.toInt(), 0xFF129B69.toInt(), 0xFF0A6A49.toInt()),
        0xFFC8FFE3.toInt()
    )

    // rose -> plum, like the vinyl card in the reference
    val REDISCOVER = HomeTileStyle(
        R.string.tile_rediscover, R.drawable.ic_tile_vinyl,
        intArrayOf(0xFFE0618F.toInt(), 0xFFAF3F6E.toInt(), 0xFF70264A.toInt()),
        0xFFFFC9DC.toInt()
    )

    // golden amber
    val STATS = HomeTileStyle(
        R.string.tile_listening_stats, R.drawable.ic_tile_bars,
        intArrayOf(0xFFFFB327.toInt(), 0xFFDB8A07.toInt(), 0xFF9A5A04.toInt()),
        0xFFFFEBB0.toInt()
    )
}
