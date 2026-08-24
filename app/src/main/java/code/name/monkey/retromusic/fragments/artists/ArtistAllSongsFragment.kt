package code.name.monkey.retromusic.fragments.artists

import android.graphics.Color
import android.os.Bundle
import android.view.View
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.os.BundleCompat
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnPreDraw
import androidx.core.view.updatePadding
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.adapter.song.SimpleSongAdapter
import code.name.monkey.retromusic.databinding.FragmentArtistAllSongsBinding
import code.name.monkey.retromusic.fragments.base.AbsMainActivityFragment
import code.name.monkey.retromusic.model.Artist
import code.name.monkey.retromusic.model.Song
import com.google.android.material.transition.MaterialSharedAxis
import com.google.android.material.transition.SlideDistanceProvider

class ArtistAllSongsFragment : AbsMainActivityFragment(R.layout.fragment_artist_all_songs) {

    private var _binding: FragmentArtistAllSongsBinding? = null
    private val binding get() = _binding!!

    private lateinit var songAdapter: SimpleSongAdapter
    private var dominantBackgroundColor: Int = Color.BLACK

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // الحل الجذري: استخدام ارتفاع الشاشة بالكامل كمسافة للحركة بدلاً من 150dp
        val slideDistancePx = resources.displayMetrics.heightPixels

        enterTransition = MaterialSharedAxis(MaterialSharedAxis.Y, true).apply {
            duration = 400L // رفع المدة لـ 400 عشان تناسب المسافة الجديدة
            secondaryAnimatorProvider = null
            (primaryAnimatorProvider as? SlideDistanceProvider)?.slideDistance = slideDistancePx
        }
        returnTransition = MaterialSharedAxis(MaterialSharedAxis.Y, false).apply {
            duration = 400L
            secondaryAnimatorProvider = null
            (primaryAnimatorProvider as? SlideDistanceProvider)?.slideDistance = slideDistancePx
        }
        allowEnterTransitionOverlap = true
        allowReturnTransitionOverlap = true

        postponeEnterTransition()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentArtistAllSongsBinding.bind(view)

        val artist: Artist? = arguments?.let {
            BundleCompat.getParcelable(it, EXTRA_ARTIST, Artist::class.java)
        }

        @Suppress("DEPRECATION")
        val songs: ArrayList<Song> =
            arguments?.getParcelableArrayList(EXTRA_SONGS) ?: arrayListOf()

        // استلام اللون الممرر من الصفحة الرئيسية مباشرة
        dominantBackgroundColor = arguments?.getInt(EXTRA_BACKGROUND_COLOR, Color.BLACK) ?: Color.BLACK

        WindowCompat.setDecorFitsSystemWindows(requireActivity().window, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.rootLayout) { _, insets ->
            val statusBarInsets = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBarInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars())

            binding.appBarLayout.updatePadding(top = statusBarInsets.top)
            binding.recyclerView.updatePadding(bottom = navBarInsets.bottom)

            insets
        }

        binding.toolbar.setNavigationOnClickListener {
            findNavController().navigateUp()
        }

        songAdapter = SimpleSongAdapter(requireActivity(), songs, R.layout.item_song)
        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(context)
            itemAnimator = null // تعطيل ItemAnimator لمنع التقطيع أثناء الحركة
            adapter = songAdapter
        }

        if (artist != null) {
            binding.toolbar.title = artist.name
            applyDynamicColor(dominantBackgroundColor)
        }

        view.doOnPreDraw {
            startPostponedEnterTransition()
        }
    }

    private fun applyDynamicColor(backgroundColor: Int) {
        dominantBackgroundColor = backgroundColor
        binding.rootLayout.setBackgroundColor(backgroundColor)

        val isLightBackground = ColorUtils.calculateLuminance(backgroundColor) > 0.45f
        val foregroundColor = if (isLightBackground) Color.BLACK else Color.WHITE

        binding.toolbar.setTitleTextColor(foregroundColor)
        binding.toolbar.navigationIcon?.let { DrawableCompat.setTint(it, foregroundColor) }

        songAdapter.setDynamicTextColors(
            foregroundColor,
            ColorUtils.setAlphaComponent(foregroundColor, 0xCC)
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val EXTRA_ARTIST = "extra_artist"
        const val EXTRA_SONGS = "extra_songs"
        const val EXTRA_TRANSITION_NAME = "extra_transition_name"
        const val EXTRA_BACKGROUND_COLOR = "extra_background_color"

        fun createBundle(
            artist: Artist,
            songs: List<Song>,
            transitionName: String,
            backgroundColor: Int = Color.BLACK
        ): Bundle = bundleOf(
            EXTRA_ARTIST to artist,
            EXTRA_SONGS to ArrayList(songs),
            EXTRA_TRANSITION_NAME to transitionName,
            EXTRA_BACKGROUND_COLOR to backgroundColor
        )
    }
}
