package code.name.monkey.retromusic.fragments.artists

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnPreDraw
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.FragmentNavigatorExtras
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.transition.TransitionValues
import androidx.transition.Visibility
import code.name.monkey.retromusic.EXTRA_ALBUM_ID
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.adapter.album.HorizontalAlbumAdapter
import code.name.monkey.retromusic.adapter.song.SimpleSongAdapter
import code.name.monkey.retromusic.databinding.FragmentArtistDetailsBinding
import code.name.monkey.retromusic.dialogs.AddToPlaylistDialog
import code.name.monkey.retromusic.extensions.*
import code.name.monkey.retromusic.fragments.base.AbsMainActivityFragment
import code.name.monkey.retromusic.glide.RetroGlideExtension
import code.name.monkey.retromusic.glide.RetroGlideExtension.artistImageOptions
import code.name.monkey.retromusic.helper.MusicPlayerRemote
import code.name.monkey.retromusic.helper.SortOrder
import code.name.monkey.retromusic.interfaces.IAlbumClickListener
import code.name.monkey.retromusic.model.Album
import code.name.monkey.retromusic.model.Artist
import code.name.monkey.retromusic.model.Song
import code.name.monkey.retromusic.repository.RealRepository
import code.name.monkey.retromusic.util.*
import code.name.monkey.retromusic.views.TintableToolbar
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.transition.MaterialContainerTransform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.get
import java.util.*

private const val FADE_START_FRACTION = 0.42f
private const val GLASS_CIRCLE_ALPHA = 0x4D // ~30%
private const val SEE_ALL_ALPHA = 0x99 // ~60%
private const val SONGS_PREVIEW_COUNT = 6

abstract class AbsArtistDetailsFragment : AbsMainActivityFragment(R.layout.fragment_artist_details),
    IAlbumClickListener {
    private var _binding: FragmentArtistDetailsBinding? = null
    private val binding get() = _binding!!

    abstract val detailsViewModel: ArtistDetailsViewModel
    abstract val artistId: Long?
    abstract val artistName: String?
    private lateinit var artist: Artist
    private lateinit var songAdapter: SimpleSongAdapter
    private lateinit var albumAdapter: HorizontalAlbumAdapter
    private lateinit var singlesAdapter: HorizontalAlbumAdapter
    private lateinit var appearsOnAdapter: HorizontalAlbumAdapter
    private var forceDownload: Boolean = false
    
    private var dominantBackgroundColor: Int = Color.BLACK
    private var cachedBitmap: Bitmap? = null
    private var cachedGradientStops: IntArray? = null
    private var hasExtractedColors: Boolean = false

    private val savedSongSortOrder: String
        get() = PreferenceUtil.artistDetailSongSortOrder

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // الأنيميشن الخاص بالعنصر المشترك (Shared Element)
        sharedElementEnterTransition = MaterialContainerTransform().apply {
            drawingViewId = R.id.fragment_container
            scrimColor = Color.TRANSPARENT
            setAllContainerColors(Color.TRANSPARENT)
            setElevationShadowEnabled(false)
        }

        // الأنيميشن المخصص لظهور باقي الشاشة من آخر 10% إلى 100%
        val slide10PercentTransition = object : Visibility() {
            override fun onAppear(
                sceneRoot: ViewGroup,
                view: View,
                startValues: TransitionValues?,
                endValues: TransitionValues?
            ): Animator {
                val startY = view.resources.displayMetrics.heightPixels * 0.10f
                view.translationY = startY
                view.alpha = 0f
                
                val moveAnim = ObjectAnimator.ofFloat(view, View.TRANSLATION_Y, startY, 0f)
                val alphaAnim = ObjectAnimator.ofFloat(view, View.ALPHA, 0f, 1f)
                
                return AnimatorSet().apply {
                    playTogether(moveAnim, alphaAnim)
                    interpolator = DecelerateInterpolator()
                }
            }

            override fun onDisappear(
                sceneRoot: ViewGroup,
                view: View,
                startValues: TransitionValues?,
                endValues: TransitionValues?
            ): Animator {
                val endY = view.resources.displayMetrics.heightPixels * 0.10f
                
                val moveAnim = ObjectAnimator.ofFloat(view, View.TRANSLATION_Y, 0f, endY)
                val alphaAnim = ObjectAnimator.ofFloat(view, View.ALPHA, 1f, 0f)
                
                return AnimatorSet().apply {
                    playTogether(moveAnim, alphaAnim)
                    interpolator = AccelerateInterpolator()
                }
            }
        }

        enterTransition = slide10PercentTransition.apply { duration = 350 }
        returnTransition = slide10PercentTransition.apply { duration = 350 }
        reenterTransition = slide10PercentTransition.apply { duration = 350 }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentArtistDetailsBinding.bind(view)

        mainActivity.addMusicServiceEventListener(detailsViewModel)

        val toolbar = binding.toolbar as TintableToolbar
        toolbar.title = null
        toolbar.inflateMenu(R.menu.menu_artist_detail)
        setUpSortOrderMenu(toolbar.menu)
        toolbar.setNavigationOnClickListener {
            findNavController().navigateUp()
        }
        toolbar.setOnMenuItemClickListener { item ->
            handleSortOrderMenuItem(item)
        }

        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(requireActivity().window, false)

        binding.appBarLayout?.let { appBar ->
            ViewCompat.setOnApplyWindowInsetsListener(appBar) { v, insets ->
                val statusBarInsets = insets.getInsets(WindowInsetsCompat.Type.statusBars())
                v.updatePadding(top = statusBarInsets.top)
                insets
            }
        }

        binding.appBarLayout?.alpha = 1f
        binding.toolbar.isClickable = true

        binding.content.setOnScrollChangeListener(androidx.core.widget.NestedScrollView.OnScrollChangeListener { _, _, scrollY, _, _ ->
            val imageHeight = binding.image.height
            if (imageHeight > 0) {
                val startFade = imageHeight - 220
                val endFade = imageHeight - 60

                val alphaProgress = when {
                    scrollY <= startFade -> 0f
                    scrollY >= endFade -> 1f
                    else -> (scrollY - startFade).toFloat() / (endFade - startFade)
                }

                val dynamicColor = ColorUtils.setAlphaComponent(dominantBackgroundColor, (alphaProgress * 255).toInt())
                binding.appBarLayout?.setBackgroundColor(dynamicColor)
            }
        })

        binding.headerContainer?.transitionName = (artistId ?: artistName).toString()

        postponeEnterTransition()

        detailsViewModel.getArtist().observe(viewLifecycleOwner) {
            showArtist(it)
            view.doOnPreDraw {
                startPostponedEnterTransition()
            }
        }
        setupRecyclerView()

        binding.fragmentArtistContent.playAction.setOnClickListener {
            if (::artist.isInitialized) {
                MusicPlayerRemote.openQueue(artist.sortedSongs, 0, true)
            }
        }
        binding.fragmentArtistContent.shuffleAction.setOnClickListener {
            if (::artist.isInitialized) {
                MusicPlayerRemote.openAndShuffleQueue(artist.songs, true)
            }
        }

        binding.fragmentArtistContent.infoAction.setOnClickListener {
            if (::artist.isInitialized) {
                addArtistSongsToPlaylist()
            }
        }

        binding.fragmentArtistContent.seeAllSongs.setOnClickListener {
            if (::artist.isInitialized) {
                findNavController().navigate(
                    R.id.artistAllSongsFragment,
                    ArtistAllSongsFragment.createBundle(artist, artist.sortedSongs, (artistId ?: artistName).toString())
                )
                
<?xml version="1.0" encoding="utf-8"?>
<set xmlns:android="[http://schemas.android.com/apk/res/android](http://schemas.android.com/apk/res/android)">
    <translate
        android:duration="300"
        android:fromYDelta="10%p"
        android:toYDelta="0%p"
        android:interpolator="@android:anim/decelerate_interpolator" />
</set>
