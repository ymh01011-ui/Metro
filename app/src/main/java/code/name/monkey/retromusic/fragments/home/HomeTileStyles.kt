package code.name.monkey.retromusic.fragments.home

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import code.name.monkey.retromusic.R

/**
 * Look of one square tile on the home screen.
 * [gradient] runs top-left to bottom-right (light to dark), [ink] is the colour of the icon
 * and of the thin outline. Colours are kept muted so the white title always stays readable.
 */
class HomeTileStyle(
    @StringRes val title: Int,
    @DrawableRes val icon: Int,
    val gradient: IntArray,
    val ink: Int
)

object HomeTileStyles {

    // cool cyan -> teal: the past, calm
    val HISTORY = HomeTileStyle(
        R.string.tile_history, R.drawable.ic_tile_history,
        intArrayOf(0xFF2A7F9E.toInt(), 0xFF1B6B7E.toInt(), 0xFF12505C.toInt()),
        0xFFA8E6F2.toInt()
    )

    // coral -> burnt orange: heat, energy
    val MOST_PLAYED = HomeTileStyle(
        R.string.tile_most_played, R.drawable.ic_tile_flame,
        intArrayOf(0xFFB8483A.toInt(), 0xFFA5532A.toInt(), 0xFF7A3A1F.toInt()),
        0xFFFFC2A3.toInt()
    )

    // electric blue -> violet -> magenta (taken from the reference artwork)
    val FAVORITE = HomeTileStyle(
        R.string.tile_favorite, R.drawable.ic_tile_heart,
        intArrayOf(0xFF2B5BD7.toInt(), 0xFF6A36C9.toInt(), 0xFFA82BA8.toInt()),
        0xFFF3C8FF.toInt()
    )

    // emerald: "right now"
    val FOR_YOU_NOW = HomeTileStyle(
        R.string.tile_for_you_now, R.drawable.ic_tile_radar,
        intArrayOf(0xFF1F8A5B.toInt(), 0xFF19734D.toInt(), 0xFF0F5238.toInt()),
        0xFFB6F2D0.toInt()
    )

    // rose -> brown, like the vinyl card in the reference
    val REDISCOVER = HomeTileStyle(
        R.string.tile_rediscover, R.drawable.ic_tile_vinyl,
        intArrayOf(0xFF8C4A62.toInt(), 0xFF6E3A4B.toInt(), 0xFF4A2A2E.toInt()),
        0xFFF5B3C6.toInt()
    )

    // golden amber
    val STATS = HomeTileStyle(
        R.string.tile_listening_stats, R.drawable.ic_tile_bars,
        intArrayOf(0xFFB07A12.toInt(), 0xFF94650F.toInt(), 0xFF6B4609.toInt()),
        0xFFFFE29A.toInt()
    )
}
