package code.name.monkey.retromusic.fragments.artists

import android.os.Bundle
import android.view.View
import androidx.core.os.bundleOf
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.LinearLayoutManager
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.adapter.song.SimpleSongAdapter
import code.name.monkey.retromusic.databinding.FragmentArtistAllSongsBinding
import code.name.monkey.retromusic.fragments.base.AbsMainActivityFragment
import code.name.monkey.retromusic.model.Song
import com.google.android.material.transition.MaterialSharedAxis

/**
 * Full song list for an artist, opened from the "See all" control on
 * [AbsArtistDetailsFragment] once there are more than [code.name.monkey.retromusic.fragments.artists.AbsArtistDetailsFragment]'s
 * preview count of songs.
 *
 * The song list is passed directly through the navigation Bundle (EXTRA_SONGS)
 * instead of being re-fetched from the repository, since the caller already has
 * it in memory. This assumes [Song] implements Parcelable (it needs a
 * `@Parcelize` — or equivalent manual implementation — for `getParcelableArrayList`
 * to work). If Song is not Parcelable in this project, swap EXTRA_SONGS for just
 * passing the artist id/name and fetching via the existing ArtistDetailsViewModel
 * pattern instead.
 */
class ArtistAllSongsFragment : AbsMainActivityFragment(R.layout.fragment_artist_all_songs) {

    private var _binding: FragmentArtistAllSongsBinding? = null
    private val binding get() = _binding!!

    private lateinit var songAdapter: SimpleSongAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // انتقال احترافي (Material Shared Axis) بدل ما الشاشة تظهر فجأة
        enterTransition = MaterialSharedAxis(MaterialSharedAxis.X, true)
        returnTransition = MaterialSharedAxis(MaterialSharedAxis.X, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentArtistAllSongsBinding.bind(view)

        val artistName = arguments?.getString(EXTRA_ARTIST_NAME).orEmpty()

        @Suppress("DEPRECATION")
        val songs: ArrayList<Song> =
            arguments?.getParcelableArrayList(EXTRA_SONGS) ?: arrayListOf()

        binding.toolbar.title = artistName
        binding.toolbar.setNavigationOnClickListener {
            findNavController().navigateUp()
        }

        songAdapter = SimpleSongAdapter(requireActivity(), songs, R.layout.item_song)
        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(context)
            itemAnimator = DefaultItemAnimator()
            adapter = songAdapter
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val EXTRA_ARTIST_NAME = "extra_artist_name"
        const val EXTRA_SONGS = "extra_songs"

        fun createBundle(artistName: String, songs: List<Song>): Bundle = bundleOf(
            EXTRA_ARTIST_NAME to artistName,
            EXTRA_SONGS to ArrayList(songs)
        )
    }
}
