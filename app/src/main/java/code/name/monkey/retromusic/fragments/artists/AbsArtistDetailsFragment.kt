package code.name.monkey.retromusic.fragments.artists

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
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

// شفافية دوائر infoAction/shuffleAction لما تاخد لون الخلفية الديناميكي (تأثير زجاجي)
private const val GLASS_CIRCLE_ALPHA = 0x4D // ~30%

// شفافية نص "See all" — أعلى من شفافية النصوص التانية عمدًا
private const val SEE_ALL_ALPHA = 0x99 // ~60%

// عدد الأغاني اللي بتتعرض قبل ما يظهر زرار "See all"
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

    private val savedSongSortOrder: String
        get() = PreferenceUtil.artistDetailSongSortOrder

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
        _binding = FragmentArtistDetailsBinding.bind(view)

        mainActivity.addMusicServiceEventListener(detailsViewModel)
        
        // ربط الـ Toolbar وجعله مستقل تماماً
        val toolbar = binding.toolbar as TintableToolbar
        toolbar.title = null
        toolbar.inflateMenu(R.menu.menu_artist_detail)
        // يحدد أي عنصر ترتيب مُختار حاليًا جوه sub-menu "Sort by"
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
            view.doOnPreDraw {
                startPostponedEnterTransition()
            }
            showArtist(it)
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

        // infoAction بقى زرار "إضافة كل أغاني الفنان للـ playlist" (علامة +)
        binding.fragmentArtistContent.infoAction.setOnClickListener {
            if (::artist.isInitialized) {
                addArtistSongsToPlaylist()
            }
        }

        // See all: بيفتح صفحة كاملة فيها كل أغاني الفنان
        // Container Transform: ربط الترانزيت بزرار seeAllSongs مباشرة لتتمدد الشاشة منه بسلاسة
        binding.fragmentArtistContent.seeAllSongs.setOnClickListener {
            if (::artist.isInitialized) {
                val transitionName = "see_all_transform_${artistId ?: artistName}"
                binding.fragmentArtistContent.seeAllSongs.transitionName = transitionName
                val extras = FragmentNavigatorExtras(
                    binding.fragmentArtistContent.seeAllSongs to transitionName
                )
                findNavController().navigate(
                    R.id.artistAllSongsFragment,
                    ArtistAllSongsFragment.createBundle(artist, artist.sortedSongs, transitionName),
                    null,
                    extras
                )
            }
        }

        binding.appBarLayout?.statusBarForeground =
            MaterialShapeDrawable.createWithElevationOverlay(requireContext())
    }

    private fun addArtistSongsToPlaylist() {
        lifecycleScope.launch(Dispatchers.IO) {
            val playlists = get<RealRepository>().fetchPlaylists()
            withContext(Dispatchers.Main) {
                if (_binding == null) return@withContext
                AddToPlaylistDialog.create(playlists, artist.songs)
                    .show(childFragmentManager, "ADD_PLAYLIST")
            }
        }
    }

    private fun setupRecyclerView() {
        albumAdapter = HorizontalAlbumAdapter(requireActivity(), ArrayList(), this)
        binding.fragmentArtistContent.albumRecyclerView.apply {
            itemAnimator = DefaultItemAnimator()
            layoutManager = GridLayoutManager(this.context, 1, GridLayoutManager.HORIZONTAL, false)
            adapter = albumAdapter
        }

        singlesAdapter = HorizontalAlbumAdapter(requireActivity(), ArrayList(), this)
        binding.fragmentArtistContent.singlesRecyclerView.apply {
            itemAnimator = DefaultItemAnimator()
            layoutManager = GridLayoutManager(this.context, 1, GridLayoutManager.HORIZONTAL, false)
            adapter = singlesAdapter
        }

        appearsOnAdapter = HorizontalAlbumAdapter(requireActivity(), ArrayList(), this)
        binding.fragmentArtistContent.appearsOnRecyclerView.apply {
            itemAnimator = DefaultItemAnimator()
            layoutManager = GridLayoutManager(this.context, 1, GridLayoutManager.HORIZONTAL, false)
            adapter = appearsOnAdapter
        }

        songAdapter = SimpleSongAdapter(requireActivity(), ArrayList(), R.layout.item_song)
        binding.fragmentArtistContent.recyclerView.apply {
            itemAnimator = DefaultItemAnimator()
            layoutManager = LinearLayoutManager(this.context)
            adapter = songAdapter
        }
    }

    private fun categorizeAlbums(artist: Artist): Triple<List<Album>, List<Album>, List<Album>> {
        val albums = mutableListOf<Album>()
        val singles = mutableListOf<Album>()
        val appearsOn = mutableListOf<Album>()
        for (album in artist.albums) {
            val albumArtistName = album.albumArtist
            val albumArtistNames = ArtistTagUtil.splitArtistNames(albumArtistName)
            val isPrimaryArtist = albumArtistNames.isEmpty() ||
                    albumArtistNames.any { it.equals(artist.name, ignoreCase = true) }
            when {
                !isPrimaryArtist -> appearsOn.add(album)
                album.songCount <= 1 -> singles.add(album)
                else -> albums.add(album)
            }
        }
        return Triple(albums, singles, appearsOn)
    }

    private fun showArtist(artist: Artist) {
        if (artist.songCount == 0) {
            findNavController().navigateUp()
            return
        }
        this.artist = artist
        loadArtistImage(artist)

        binding.artistTitle.text = artist.name
        binding.text.text = String.format(
            "%s • %s",
            MusicUtil.getArtistInfoString(requireContext(), artist),
            MusicUtil.getReadableDurationString(MusicUtil.getTotalDuration(artist.songs))
        )

        applySongsPreview(artist.sortedSongs)

        val (albums, singles, appearsOn) = categorizeAlbums(artist)

        val albumText = resources.getQuantityString(
            R.plurals.albums, albums.size, albums.size
        )
        binding.fragmentArtistContent.albumTitle.text = albumText
        albumAdapter.swapDataSet(albums)
        binding.fragmentArtistContent.albumTitle.isVisible = albums.isNotEmpty()
        binding.fragmentArtistContent.albumRecyclerView.isVisible = albums.isNotEmpty()

        singlesAdapter.swapDataSet(singles)
        binding.fragmentArtistContent.singlesTitle.isVisible = singles.isNotEmpty()
        binding.fragmentArtistContent.singlesRecyclerView.isVisible = singles.isNotEmpty()

        appearsOnAdapter.swapDataSet(appearsOn)
        binding.fragmentArtistContent.appearsOnTitle.isVisible = appearsOn.isNotEmpty()
        binding.fragmentArtistContent.appearsOnRecyclerView.isVisible = appearsOn.isNotEmpty()
    }

    /**
     * Shows only the first [SONGS_PREVIEW_COUNT] songs and toggles the "See all"
     * control's visibility accordingly. Used on initial load and whenever the
     * sort order changes (so the list collapses back to a preview each time).
     */
    private fun applySongsPreview(sortedSongs: List<Song>) {
        val hasMoreSongs = sortedSongs.size > SONGS_PREVIEW_COUNT
        songAdapter.swapDataSet(
            if (hasMoreSongs) sortedSongs.take(SONGS_PREVIEW_COUNT) else sortedSongs
        )
        binding.fragmentArtistContent.seeAllSongs.isVisible = hasMoreSongs
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
                    extractColorsAndApplyGradient(resource)
                }

                override fun onLoadCleared(placeholder: Drawable?) {
                    if (_binding != null) {
                        binding.image.setImageDrawable(placeholder)
                    }
                }
            })
    }

    private fun extractColorsAndApplyGradient(bitmap: Bitmap) {
        lifecycleScope.launch(Dispatchers.Default) {
            val dominantColor = ArtistPaletteEngine.findDominantColorAtSubtitleRegion(
                bitmap = bitmap,
                startRatio = 0.68f,
                endRatio = 0.78f
            )

            val gradientStops = ArtistPaletteEngine.buildSeamlessGradient(
                blendColor = dominantColor,
                fadeStart = FADE_START_FRACTION
            )

            withContext(Dispatchers.Main) {
                if (_binding != null) {
                    binding.image.setImageBitmap(bitmap)
                    setColors(dominantColor, gradientStops)
                }
            }
        }
    }

    private fun setColors(backgroundColor: Int, gradientStops: IntArray) {
        if (_binding == null) return

        dominantBackgroundColor = backgroundColor
        binding.rootLayout.setBackgroundColor(backgroundColor)
        
        binding.appBarLayout?.setBackgroundColor(ColorUtils.setAlphaComponent(backgroundColor, 0))

        val gradientDrawable = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
            gradientStops
        )
        binding.headerGradient?.let { it.background = gradientDrawable }

        applyContrastingForegroundColor(backgroundColor)
    }

    private fun applyContrastingForegroundColor(backgroundColor: Int) {
        val isLightBackground = ColorUtils.calculateLuminance(backgroundColor) > 0.45f
        val foregroundColor = if (isLightBackground) Color.BLACK else Color.WHITE
        val secondaryForegroundColor = ColorUtils.setAlphaComponent(foregroundColor, 0xCC) 

        binding.artistTitle.setTextColor(foregroundColor)
        binding.text.setTextColor(secondaryForegroundColor)

        // tint toolbar icons: navigation and overflow
        val toolbar = binding.toolbar
        if (toolbar is TintableToolbar) {
            toolbar.navigationIcon?.let { DrawableCompat.setTint(it, foregroundColor) }
            toolbar.setOverflowIconTint(foregroundColor)
        } else if (toolbar is androidx.appcompat.widget.Toolbar) {
            toolbar.navigationIcon?.let { DrawableCompat.setTint(it, foregroundColor) }
            toolbar.overflowIcon?.let { DrawableCompat.setTint(it, foregroundColor) }
        }

        binding.fragmentArtistContent.songTitle.setTextColor(foregroundColor)
        binding.fragmentArtistContent.seeAllSongs.setTextColor(
            ColorUtils.setAlphaComponent(foregroundColor, SEE_ALL_ALPHA)
        )
        binding.fragmentArtistContent.albumTitle.setTextColor(foregroundColor)
        binding.fragmentArtistContent.singlesTitle.setTextColor(foregroundColor)
        binding.fragmentArtistContent.appearsOnTitle.setTextColor(foregroundColor)

        binding.fragmentArtistContent.playAction.elevation = 0f
        if (binding.fragmentArtistContent.playAction is com.google.android.material.button.MaterialButton) {
            (binding.fragmentArtistContent.playAction as com.google.android.material.button.MaterialButton).strokeWidth = 0
        }

        // infoAction (+) و shuffleAction: بدل الدايرة البيضاء الشفافة الثابتة،
        // خدي نفس لون خلفية الصفحة (backgroundColor) بشفافية، مع الحفاظ على
        // إطار الدايرة الأبيض (strokeColor) زي ما هو عشان يفضل الإحساس الزجاجي
        val glassCircleTint = ColorUtils.setAlphaComponent(backgroundColor, GLASS_CIRCLE_ALPHA)
        binding.fragmentArtistContent.infoAction.backgroundTintList =
            android.content.res.ColorStateList.valueOf(glassCircleTint)
        binding.fragmentArtistContent.shuffleAction.backgroundTintList =
            android.content.res.ColorStateList.valueOf(glassCircleTint)

        binding.fragmentArtistContent.infoAction.iconTint =
            android.content.res.ColorStateList.valueOf(foregroundColor)

        binding.fragmentArtistContent.shuffleAction.iconTint =
            android.content.res.ColorStateList.valueOf(foregroundColor)

        // بدل ما نلف على الـ views الظاهرة دلوقتي بس (كان بيسيب أي عنصر يتلف
        // عليه بعدين، زي الألبومات وانت بتسحب، من غير لون)، اللون بقى متخزن
        // جوه كل adapter وبيتطبق تلقائي في onBindViewHolder لأي عنصر جديد.
        songAdapter.setDynamicTextColors(foregroundColor, secondaryForegroundColor)
        albumAdapter.setDynamicTextColors(foregroundColor, secondaryForegroundColor)
        singlesAdapter.setDynamicTextColors(foregroundColor, secondaryForegroundColor)
        appearsOnAdapter.setDynamicTextColors(foregroundColor, secondaryForegroundColor)
    }

    override fun onAlbumClick(albumId: Long, view: View) {
        findNavController().navigate(
            R.id.albumDetailsFragment,
            bundleOf(EXTRA_ALBUM_ID to albumId),
            null,
            FragmentNavigatorExtras(
                view to albumId.toString()
            )
        )
    }

    private fun handleSortOrderMenuItem(item: MenuItem): Boolean {
        val songs = artist.songs
        when (item.itemId) {
            R.id.action_play_next -> {
                MusicPlayerRemote.playNext(songs)
                return true
            }

            R.id.action_add_to_current_playing -> {
                MusicPlayerRemote.enqueue(songs)
                return true
            }

            R.id.action_add_to_playlist -> {
                lifecycleScope.launch(Dispatchers.IO) {
                    val playlists = get<RealRepository>().fetchPlaylists()
                    withContext(Dispatchers.Main) {
                        AddToPlaylistDialog.create(playlists, songs)
                            .show(childFragmentManager, "ADD_PLAYLIST")
                    }
                }
                return true
            }

            R.id.action_set_artist_image -> {
                selectImageLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
                return true
            }

            R.id.action_reset_artist_image -> {
                showToast(resources.getString(R.string.updating))
                lifecycleScope.launch {
                    CustomArtistImageUtil.getInstance(requireContext())
                        .resetCustomArtistImage(artist)
                }
                forceDownload = true
                return true
            }

            // خيارات الترتيب، منقولة من الـ PopupMenu القديم لجوه sub-menu
            // "Sort by" في قايمة الثلاث نقاط
            R.id.action_sort_order_title -> {
                item.isChecked = true
                setSaveSortOrder(SortOrder.ArtistSongSortOrder.SONG_A_Z)
                return true
            }

            R.id.action_sort_order_title_desc -> {
                item.isChecked = true
                setSaveSortOrder(SortOrder.ArtistSongSortOrder.SONG_Z_A)
                return true
            }

            R.id.action_sort_order_album -> {
                item.isChecked = true
                setSaveSortOrder(SortOrder.ArtistSongSortOrder.SONG_ALBUM)
                return true
            }

            R.id.action_sort_order_year -> {
                item.isChecked = true
                setSaveSortOrder(SortOrder.ArtistSongSortOrder.SONG_YEAR)
                return true
            }

            R.id.action_sort_order_song_duration -> {
                item.isChecked = true
                setSaveSortOrder(SortOrder.ArtistSongSortOrder.SONG_DURATION)
                return true
            }
        }
        return true
    }

    private fun setSaveSortOrder(sortOrder: String) {
        PreferenceUtil.artistDetailSongSortOrder = sortOrder
        // بعد تغيير الترتيب، اللستة بترجع تتقفل على أول 6 أغاني تاني
        // (زي أول تحميل للصفحة)، وSee all بتظهر لو لسه فيه أكتر من 6.
        applySongsPreview(artist.sortedSongs)
    }

    private fun setUpSortOrderMenu(sortOrder: Menu) {
        when (savedSongSortOrder) {
            SortOrder.ArtistSongSortOrder.SONG_A_Z -> sortOrder.findItem(R.id.action_sort_order_title).isChecked =
                true

            SortOrder.ArtistSongSortOrder.SONG_Z_A -> sortOrder.findItem(R.id.action_sort_order_title_desc).isChecked =
                true

            SortOrder.ArtistSongSortOrder.SONG_ALBUM -> sortOrder.findItem(R.id.action_sort_order_album).isChecked =
                true

            SortOrder.ArtistSongSortOrder.SONG_YEAR -> sortOrder.findItem(R.id.action_sort_order_year).isChecked =
                true

            SortOrder.ArtistSongSortOrder.SONG_DURATION -> sortOrder.findItem(R.id.action_sort_order_song_duration).isChecked =
                true

            else -> {
                throw IllegalArgumentException("invalid $savedSongSortOrder")
            }
        }
    }

    private val selectImageLauncher =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            lifecycleScope.launch {
                if (uri != null) {
                    CustomArtistImageUtil.getInstance(requireContext())
                        .setCustomArtistImage(artist, uri)
                }
            }
        }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
