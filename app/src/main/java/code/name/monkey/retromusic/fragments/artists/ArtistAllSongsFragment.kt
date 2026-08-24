package code.name.monkey.retromusic.fragments.artists

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.transition.Transition as PlatformTransition
import android.view.View
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.os.BundleCompat
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.LinearLayoutManager
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.adapter.song.SimpleSongAdapter
import code.name.monkey.retromusic.databinding.FragmentArtistAllSongsBinding
import code.name.monkey.retromusic.fragments.base.AbsMainActivityFragment
import code.name.monkey.retromusic.glide.RetroGlideExtension
import code.name.monkey.retromusic.glide.RetroGlideExtension.artistImageOptions
import code.name.monkey.retromusic.model.Artist
import code.name.monkey.retromusic.model.Song
import code.name.monkey.retromusic.util.ArtistPaletteEngine
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.google.android.material.transition.MaterialSharedAxis
import com.google.android.material.transition.SlideDistanceProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ArtistAllSongsFragment : AbsMainActivityFragment(R.layout.fragment_artist_all_songs) {

    private var _binding: FragmentArtistAllSongsBinding? = null
    private val binding get() = _binding!!

    private lateinit var songAdapter: SimpleSongAdapter
    private var dominantBackgroundColor: Int = Color.BLACK

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // اللون اللي جه من صفحة الفنان (نفس اللون بالظبط)
        val passedColor = arguments?.getInt(EXTRA_BACKGROUND_COLOR, Color.BLACK) ?: Color.BLACK
        dominantBackgroundColor = passedColor

        val container = requireActivity().findViewById<View>(R.id.fragment_container)

        // انتقال Shared Axis (محور Y): فيد + حركة رأسية، مع دخول للأمام (forward)
        val slideDistancePx = (resources.displayMetrics.density * 150).toInt()
        enterTransition = MaterialSharedAxis(MaterialSharedAxis.Y, true).apply {
            duration = 300L
            (primaryAnimatorProvider as? SlideDistanceProvider)?.slideDistance = slideDistancePx
            colorContainerDuringTransition(container) { dominantBackgroundColor }
        }
        // عند الرجوع: نفس المحور بس بإتجاه عكسي (forward = false)
        returnTransition = MaterialSharedAxis(MaterialSharedAxis.Y, false).apply {
            duration = 300L
            (primaryAnimatorProvider as? SlideDistanceProvider)?.slideDistance = slideDistancePx
            colorContainerDuringTransition(container) { dominantBackgroundColor }
        }
        // منع تراكب أنيميشن الدخول والخروج مع بعض (بيمنع الوميض الأسود اللحظي)
        allowEnterTransitionOverlap = false
        allowReturnTransitionOverlap = false
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

        // ضبط الـ Edge-to-Edge بدون تداخل
        WindowCompat.setDecorFitsSystemWindows(requireActivity().window, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.rootLayout) { _, insets ->
            val statusBarInsets = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBarInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars())

            binding.appBarLayout.updatePadding(top = statusBarInsets.top)
            binding.recyclerView.updatePadding(bottom = navBarInsets.bottom)

            insets
        }

        // نطبق نفس اللون كخلفية مبدئية للصفحة (قبل ما استخراج اللون الخاص بيها يخلص)
        binding.rootLayout.setBackgroundColor(dominantBackgroundColor)

        binding.toolbar.setNavigationOnClickListener {
            findNavController().navigateUp()
        }

        if (artist != null) {
            binding.toolbar.title = artist.name
            extractArtistColor(artist)
        }

        songAdapter = SimpleSongAdapter(requireActivity(), songs, R.layout.item_song)
        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(context)
            itemAnimator = DefaultItemAnimator()
            adapter = songAdapter
        }
    }

    private fun extractArtistColor(artist: Artist) {
        Glide.with(requireContext())
            .asBitmap()
            .artistImageOptions(artist)
            .load(RetroGlideExtension.getArtistModel(artist))
            .dontAnimate()
            .into(object : CustomTarget<Bitmap>() {
                override fun onResourceReady(
                    resource: Bitmap,
                    transition: Transition<in Bitmap>?
                ) {
                    extractAndApplyDominantColor(resource)
                }

                override fun onLoadCleared(placeholder: Drawable?) {}
            })
    }

    private fun extractAndApplyDominantColor(bitmap: Bitmap) {
        lifecycleScope.launch(Dispatchers.Default) {
            val dominantColor = ArtistPaletteEngine.findDominantColorAtSubtitleRegion(
                bitmap = bitmap,
                startRatio = 0.68f,
                endRatio = 0.78f
            )
            withContext(Dispatchers.Main) {
                if (_binding != null) {
                    applyDynamicColor(dominantColor)
                }
            }
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
            backgroundColor: Int
        ): Bundle =
            bundleOf(
                EXTRA_ARTIST to artist,
                EXTRA_SONGS to ArrayList(songs),
                EXTRA_TRANSITION_NAME to transitionName,
                EXTRA_BACKGROUND_COLOR to backgroundColor
            )
    }
}

/**
 * بيلوّن الـ Container المشترك (fragment_container) بس أثناء مدة الأنيميشن نفسه —
 * بيتلوّن لحظة بداية الترانزيشن (onTransitionStart) ويرجع لخلفيته الأصلية أول
 * ما الترانزيشن يخلص أو يتلغي (onTransitionEnd / onTransitionCancel)، عشان
 * التلوين ميفضلش عالق على باقي صفحات التطبيق.
 */
private fun PlatformTransition.colorContainerDuringTransition(
    container: View?,
    colorProvider: () -> Int
) {
    if (container == null) return
    addListener(object : PlatformTransition.TransitionListener {
        private var originalBackground: Drawable? = null

        override fun onTransitionStart(transition: PlatformTransition) {
            originalBackground = container.background
            container.setBackgroundColor(colorProvider())
        }

        override fun onTransitionEnd(transition: PlatformTransition) {
            container.background = originalBackground
        }

        override fun onTransitionCancel(transition: PlatformTransition) {
            container.background = originalBackground
        }

        override fun onTransitionPause(transition: PlatformTransition) {}
        override fun onTransitionResume(transition: PlatformTransition) {}
    })
}
