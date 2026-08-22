package code.name.monkey.retromusic.fragments.artists

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Bundle
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
        // انتقال Shared Axis (محور Y): فيد + حركة خفيفة بإتجاه رأسي، مع دخول للأمام (forward)
        enterTransition = MaterialSharedAxis(MaterialSharedAxis.Y, true).apply {
            duration = 300L
        }
        // عند الرجوع: نفس المحور بس بإتجاه عكسي (forward = false)
        returnTransition = MaterialSharedAxis(MaterialSharedAxis.Y, false).apply {
            duration = 300L
        }
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

        fun createBundle(artist: Artist, songs: List<Song>, transitionName: String): Bundle =
            bundleOf(
                EXTRA_ARTIST to artist,
                EXTRA_SONGS to ArrayList(songs),
                EXTRA_TRANSITION_NAME to transitionName
            )
    }
}
