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
import androidx.core.view.doOnPreDraw
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
import com.google.android.material.transition.MaterialContainerTransform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Full song list for an artist, opened from the "See all" control on
 * [AbsArtistDetailsFragment] once there are more than that screen's preview
 * count of songs.
 *
 * Uses a Container Transform shared element (headerContainer) so the artist
 * header image visually morphs from the full-size banner on the details
 * screen into this screen's Collapsing Toolbar, instead of a plain slide
 * transition. See AbsArtistDetailsFragment's seeAllSongs click listener for
 * the FragmentNavigatorExtras that starts the transform. The transitionName
 * itself is passed explicitly through the bundle (EXTRA_TRANSITION_NAME)
 * rather than recomputed here, so it's guaranteed to match the source
 * screen's (artistId ?: artistName) value exactly.
 *
 * The song list is passed directly through the navigation Bundle (EXTRA_SONGS)
 * instead of being re-fetched, since the caller already has it sorted in
 * memory. The Artist itself (EXTRA_ARTIST) is passed too, so this screen can
 * reload the same header image and re-derive the same dominant color as the
 * details screen.
 *
 * Edge-to-edge + color matching AbsArtistDetailsFragment:
 * - decorFitsSystemWindows is turned off and only the Toolbar (not the whole
 *   header) is padded for the status bar inset, so the artist image reaches
 *   the very top of the screen instead of stopping short with a gap.
 * - The same ArtistPaletteEngine sampling used on the details screen is
 *   reused here so the collapsed toolbar scrim, page background, and
 *   title/icon colors match that screen exactly rather than falling back to
 *   the static ?attr/colorSurface.
 */
class ArtistAllSongsFragment : AbsMainActivityFragment(R.layout.fragment_artist_all_songs) {

    private var _binding: FragmentArtistAllSongsBinding? = null
    private val binding get() = _binding!!

    private lateinit var songAdapter: SimpleSongAdapter
    private var dominantBackgroundColor: Int = Color.BLACK

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sharedElementEnterTransition = MaterialContainerTransform().apply {
            drawingViewId = R.id.fragment_container
            scrimColor = Color.TRANSPARENT
            setAllContainerColors(Color.TRANSPARENT)
            setElevationShadowEnabled(false)
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

        val transitionName = arguments?.getString(EXTRA_TRANSITION_NAME).orEmpty()
        binding.headerContainer.transitionName = transitionName

        postponeEnterTransition()
        view.doOnPreDraw { startPostponedEnterTransition() }

        // Edge-to-edge: let content draw behind the status bar so the artist
        // image reaches the very top of the screen. Only the Toolbar gets
        // padded down by the status bar inset (not the whole AppBarLayout),
        // same approach as AbsArtistDetailsFragment.
        WindowCompat.setDecorFitsSystemWindows(requireActivity().window, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.toolbar) { v, insets ->
            val statusBarInsets = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            v.updatePadding(top = statusBarInsets.top)
            insets
        }

        binding.toolbar.setNavigationOnClickListener {
            findNavController().navigateUp()
        }

        if (artist != null) {
            binding.collapsingToolbar.title = artist.name
            loadArtistImage(artist)
        }

        songAdapter = SimpleSongAdapter(requireActivity(), songs, R.layout.item_song)
        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(context)
            itemAnimator = DefaultItemAnimator()
            adapter = songAdapter
        }
    }

    private fun loadArtistImage(artist: Artist) {
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
                    if (_binding != null) {
                        binding.image.setImageBitmap(resource)
                    }
                    extractAndApplyDominantColor(resource)
                }

                override fun onLoadCleared(placeholder: Drawable?) {
                    if (_binding != null) {
                        binding.image.setImageDrawable(placeholder)
                    }
                }
            })
    }

    /**
     * Samples the same region of the artist image that AbsArtistDetailsFragment
     * uses, so this screen's scrim, page background, and collapsed
     * title/icon colors match the details screen exactly instead of using
     * the static ?attr/colorSurface fallback declared in the layout.
     */
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
        binding.collapsingToolbar.setContentScrimColor(backgroundColor)
        binding.collapsingToolbar.setStatusBarScrimColor(backgroundColor)

        val isLightBackground = ColorUtils.calculateLuminance(backgroundColor) > 0.45f
        val foregroundColor = if (isLightBackground) Color.BLACK else Color.WHITE

        // Collapsed title sits on top of the solid scrim color, so it needs
        // the dynamic contrast color. Expanded title sits over the image's
        // own gradient overlay (always dark at that edge), so it stays
        // white — same reasoning as artistTitle in fragment_artist_details.xml.
        binding.collapsingToolbar.setCollapsedTitleTextColor(foregroundColor)
        binding.collapsingToolbar.setExpandedTitleColor(Color.WHITE)

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
