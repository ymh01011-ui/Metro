package code.name.monkey.retromusic.fragments.other

import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewOutlineProvider
import androidx.core.graphics.ColorUtils
import androidx.core.view.doOnPreDraw
import androidx.core.view.isVisible
import androidx.navigation.fragment.findNavController
import code.name.monkey.appthemehelper.util.ColorUtil
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.databinding.FragmentListeningStatsBinding
import code.name.monkey.retromusic.databinding.ItemStatCardBinding
import code.name.monkey.retromusic.extensions.accentColor
import code.name.monkey.retromusic.fragments.base.AbsMainActivityFragment
import code.name.monkey.retromusic.fragments.home.HomeTileStyles
import code.name.monkey.retromusic.model.ListeningStats
import com.google.android.material.color.MaterialColors
import com.google.android.material.transition.MaterialSharedAxis

/** The "Listening stats" screen opened from the amber tile on the home page. */
class ListeningStatsFragment : AbsMainActivityFragment(R.layout.fragment_listening_stats) {

    private var _binding: FragmentListeningStatsBinding? = null
    private val binding get() = _binding!!

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterTransition = MaterialSharedAxis(MaterialSharedAxis.Y, true)
        returnTransition = MaterialSharedAxis(MaterialSharedAxis.Y, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentListeningStatsBinding.bind(view)
        postponeEnterTransition()
        view.doOnPreDraw { startPostponedEnterTransition() }

        mainActivity.setSupportActionBar(binding.toolbar)
        binding.toolbar.setTitle(R.string.tile_listening_stats)
        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }

        styleCards()
        libraryViewModel.listeningStats().observe(viewLifecycleOwner) { bindStats(it) }
    }

    private fun styleCards() {
        val density = resources.displayMetrics.density
        val radius = 24f * density

        // hero card uses the same gradient as the home tile
        binding.heroCard.background =
            GradientDrawable(GradientDrawable.Orientation.TL_BR, HomeTileStyles.STATS.gradient)
                .apply { cornerRadius = radius }
        binding.heroCard.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
        binding.heroCard.clipToOutline = true

        val screenBg =
            MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorSurface)
        val cardColor =
            ColorUtils.compositeColors(ColorUtil.withAlpha(accentColor(), 0.12f), screenBg)
        listOf(
            binding.statPlays, binding.statSongs,
            binding.statTopSong, binding.statTopArtist, binding.statTopAlbum
        ).forEach { it.root.setCardBackgroundColor(cardColor) }
    }

    private fun bindStats(stats: ListeningStats) {
        val b = _binding ?: return
        b.empty.isVisible = stats.totalPlays == 0
        b.heroValue.text = formatDuration(stats.totalListeningMillis)

        fill(b.statPlays, R.string.stats_total_plays, stats.totalPlays.toString(), "")
        fill(
            b.statSongs, R.string.stats_songs_played, stats.songsPlayed.toString(),
            getString(R.string.stats_of_library, stats.librarySongs)
        )
        fill(
            b.statTopSong, R.string.stats_top_song, stats.topSongTitle ?: DASH,
            listOfNotNull(stats.topSongArtist, plays(stats.topSongPlays)).joinToString("  •  ")
                .takeIf { stats.topSongTitle != null }.orEmpty()
        )
        fill(
            b.statTopArtist, R.string.stats_top_artist, stats.topArtistName ?: DASH,
            if (stats.topArtistName != null) plays(stats.topArtistPlays) else ""
        )
        fill(
            b.statTopAlbum, R.string.stats_top_album, stats.topAlbumName ?: DASH,
            if (stats.topAlbumName != null) plays(stats.topAlbumPlays) else ""
        )
    }

    private fun fill(card: ItemStatCardBinding, label: Int, value: String, sub: String) {
        card.statLabel.setText(label)
        card.statValue.text = value
        card.statSub.text = sub
        card.statSub.isVisible = sub.isNotEmpty()
    }

    private fun plays(count: Int) = resources.getQuantityString(R.plurals.stats_plays, count, count)

    private fun formatDuration(millis: Long): String {
        val totalMinutes = millis / 60_000
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return if (hours > 0) getString(R.string.stats_duration_hm, hours, minutes)
        else getString(R.string.stats_duration_m, minutes)
    }

    override fun onCreateMenu(menu: Menu, inflater: MenuInflater) {
    }

    override fun onMenuItemSelected(item: MenuItem): Boolean {
        return false
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private companion object {
        const val DASH = "—"
    }
}
