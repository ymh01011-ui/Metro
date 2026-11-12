package code.name.monkey.retromusic.fragments.other

import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.view.ViewOutlineProvider
import androidx.core.graphics.ColorUtils
import androidx.core.view.doOnPreDraw
import androidx.core.view.isVisible
import androidx.lifecycle.LiveData
import androidx.navigation.fragment.findNavController
import code.name.monkey.appthemehelper.util.ColorUtil
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.databinding.FragmentListeningStatsBinding
import code.name.monkey.retromusic.databinding.ItemStatCardBinding
import code.name.monkey.retromusic.extensions.accentColor
import code.name.monkey.retromusic.fragments.base.AbsMainActivityFragment
import code.name.monkey.retromusic.fragments.home.HomeTileStyles
import code.name.monkey.retromusic.model.ListeningStats
import code.name.monkey.retromusic.util.LastPageCache
import code.name.monkey.retromusic.util.pageBackgroundColor
import com.google.android.material.color.MaterialColors
import com.google.android.material.transition.MaterialSharedAxis

/** The "Listening stats" screen opened from the amber tile on the home page. */
class ListeningStatsFragment : AbsMainActivityFragment(R.layout.fragment_listening_stats) {

    private var _binding: FragmentListeningStatsBinding? = null
    private val binding get() = _binding!!

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        enterSettled = false
        settledCallbacks.clear()
        _binding = FragmentListeningStatsBinding.bind(view)
        // slides in over the home page: give it the page colour the rest of the app draws on
        if (view.background == null) view.setBackgroundColor(pageBackgroundColor())
        view.postDelayed({ markEnterSettled() }, ENTER_SETTLE_FALLBACK_MS)
        postponeEnterTransition()
        view.doOnPreDraw { startPostponedEnterTransition() }

        mainActivity.setSupportActionBar(binding.toolbar)
        binding.toolbar.setTitle(R.string.tile_listening_stats)
        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }

        styleCards()
        // Same page as last time: its numbers are shown at once (no loading from scratch). Otherwise
        // the numbers are computed in the background and fade in after the slide-in.
        val cachedStats = LastPageCache.get<ListeningStats>(CACHE_KEY)
        var shown: ListeningStats? = cachedStats
        if (cachedStats != null) bindStats(cachedStats) else binding.statsScroll.alpha = 0f
        libraryViewModel.listeningStats().observe(viewLifecycleOwner) { stats ->
            LastPageCache.put(CACHE_KEY, stats)
            if (stats != shown) {
                shown = stats
                runWhenEnterSettled {
                    bindStats(stats)
                    _binding?.statsScroll?.animate()?.alpha(1f)?.setDuration(180)?.start()
                }
            }
        }
    }

    private fun styleCards() {
        val density = resources.displayMetrics.density
        val radius = 24f * density

        // hero card uses the same gradient (and light / dark look) as the home tile
        val dark = HomeTileStyles.isDark(binding.root)
        val style = HomeTileStyles.STATS
        binding.heroCard.background =
            GradientDrawable(GradientDrawable.Orientation.TL_BR, style.gradientFor(dark))
                .apply { setCornerRadius(radius) }
        val textColor = if (dark) style.inkFor(true) else Color.WHITE
        binding.heroValue.setTextColor(textColor)
        binding.heroLabel.setTextColor(ColorUtils.setAlphaComponent(textColor, 204))
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

    // ---- enter animation bookkeeping (same pattern as the artist page) --------------------------

    private var enterSettled = false
    private val settledCallbacks = mutableListOf<() -> Unit>()

    private fun runWhenEnterSettled(block: () -> Unit) {
        if (_binding == null) return
        if (enterSettled) block() else settledCallbacks.add(block)
    }

    private fun markEnterSettled() {
        if (enterSettled || _binding == null) return
        enterSettled = true
        val pending = settledCallbacks.toList()
        settledCallbacks.clear()
        fun runNext(index: Int) {
            val root = view ?: return
            if (_binding == null || index >= pending.size) return
            pending[index]()
            root.postOnAnimation { runNext(index + 1) }
        }
        view?.postOnAnimation { runNext(0) }
    }

    private fun <T> LiveData<T>.observeWhenSettled(block: (T) -> Unit) {
        observe(viewLifecycleOwner) { value -> runWhenEnterSettled { block(value) } }
    }

    override fun onCreateAnimation(transit: Int, enter: Boolean, nextAnim: Int): Animation? {
        val anim = super.onCreateAnimation(transit, enter, nextAnim)
            ?: if (enter && nextAnim != 0 &&
                runCatching { resources.getResourceTypeName(nextAnim) == "anim" }.getOrDefault(false)
            ) {
                runCatching { AnimationUtils.loadAnimation(requireContext(), nextAnim) }.getOrNull()
            } else null
        if (enter && anim != null && !enterSettled) {
            anim.setAnimationListener(object : Animation.AnimationListener {
                override fun onAnimationStart(animation: Animation?) {}
                override fun onAnimationRepeat(animation: Animation?) {}
                override fun onAnimationEnd(animation: Animation?) {
                    markEnterSettled()
                }
            })
        }
        return anim
    }

    override fun onDestroyView() {
        super.onDestroyView()
        settledCallbacks.clear()
        _binding = null
    }

    private companion object {
        const val DASH = "—"
    }
}

private const val ENTER_SETTLE_FALLBACK_MS = 700L
private const val CACHE_KEY = "stats"
