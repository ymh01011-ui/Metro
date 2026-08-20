package code.name.monkey.retromusic.fragments.artists

import android.os.Bundle
import android.view.View
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.LinearLayoutManager
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.adapter.song.SimpleSongAdapter
import code.name.monkey.retromusic.databinding.FragmentArtistAllSongsBinding
import code.name.monkey.retromusic.fragments.base.AbsMainActivityFragment
import code.name.monkey.retromusic.model.Song

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
 *
 * No custom Fragment transition is set here on purpose — a custom Slide/shared-axis
 * transition caused the previous screen's background to render underneath/overlap
 * during the pop animation. Navigation uses the library's own default enter/exit/
 * pop anim resources instead (see AbsArtistDetailsFragment's navigate() call),
 * the same simple animation family used for ordinary destination navigation
 * elsewhere in the app (e.g. Home ↔ Songs).
 *
 * The toolbar draws edge-to-edge (extends behind the status bar) to match the
 * artist details screen above it, instead of leaving a separate dark status-bar
 * strip above a lighter toolbar.
 */
class ArtistAllSongsFragment : AbsMainActivityFragment(R.layout.fragment_artist_all_songs) {

    private var _binding: FragmentArtistAllSongsBinding? = null
    private val binding get() = _binding!!

    private lateinit var songAdapter: SimpleSongAdapter

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentArtistAllSongsBinding.bind(view)

        // اجعل الشاشة تمتد خلف الـ status bar، والـ toolbar نفسه ياخد
        // padding يساوي ارتفاع الـ status bar بدل ما يفضل شريط منفصل فوقه
        WindowCompat.setDecorFitsSystemWindows(requireActivity().window, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.toolbar) { v, insets ->
            val statusBarInsets = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            v.updatePadding(top = statusBarInsets.top)
            insets
        }

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
