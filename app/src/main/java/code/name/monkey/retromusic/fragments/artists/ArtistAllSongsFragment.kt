package code.name.monkey.retromusic.fragments.artists

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.View
import androidx.core.os.bundleOf
import androidx.core.view.doOnPreDraw
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
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.google.android.material.transition.MaterialContainerTransform

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
 * memory. The Artist itself (EXTRA_ARTIST) is passed too, purely so this
 * screen can reload the same header image — it assumes [Artist] implements
 * Parcelable, same assumption already made for [Song] on this screen.
 */
class ArtistAllSongsFragment : AbsMainActivityFragment(R.layout.fragment_artist_all_songs) {

    private var _binding: FragmentArtistAllSongsBinding? = null
    private val binding get() = _binding!!

    private lateinit var songAdapter: SimpleSongAdapter

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

        @Suppress("DEPRECATION")
        val artist: Artist? = arguments?.getParcelable(EXTRA_ARTIST) as? Artist

        @Suppress("DEPRECATION")
        val songs: ArrayList<Song> =
            arguments?.getParcelableArrayList(EXTRA_SONGS) ?: arrayListOf()

        val transitionName = arguments?.getString(EXTRA_TRANSITION_NAME).orEmpty()
        binding.headerContainer.transitionName = transitionName

        postponeEnterTransition()
        view.doOnPreDraw { startPostponedEnterTransition() }

        binding.toolbar.setNavigationOnClickListener {
            findNavController().navigateUp()
        }

        if (artist != null) {
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
                }

                override fun onLoadCleared(placeholder: Drawable?) {
                    if (_binding != null) {
                        binding.image.setImageDrawable(placeholder)
                    }
                }
            })
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
