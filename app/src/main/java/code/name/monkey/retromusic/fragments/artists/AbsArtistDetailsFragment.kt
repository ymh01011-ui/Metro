package code.name.monkey.retromusic.fragments.artists

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.PopupMenu
import androidx.core.graphics.ColorUtils
import androidx.core.os.bundleOf
import androidx.core.view.doOnPreDraw
import androidx.core.view.isVisible
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
import code.name.monkey.retromusic.repository.RealRepository
import code.name.monkey.retromusic.util.*
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

    private val savedSongSortOrder: String
        get() = PreferenceUtil.artistDetailSongSortOrder

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sharedElementEnterTransition = MaterialContainerTransform().apply {
            drawingViewId = R.id.fragment_container
            scrimColor = Color.TRANSPARENT
            setAllContainerColors(surfaceColor())
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentArtistDetailsBinding.bind(view)
        mainActivity.addMusicServiceEventListener(detailsViewModel)
        mainActivity.setSupportActionBar(binding.toolbar)
        binding.toolbar.title = null
        
        binding.image.transitionName = (artistId ?: artistName).toString()
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

        setupSongSortButton()
        // appBarLayout is nullable in the generated binding; use a safe call to avoid compilation errors
        binding.appBarLayout?.statusBarForeground =
            MaterialShapeDrawable.createWithElevationOverlay(requireContext())
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

        songAdapter.swapDataSet(artist.sortedSongs)

        val (albums, singles, appearsOn) = categorizeAlbums(artist)

        val albumText = resources.getQuantityString(
            R.plurals.albums, albums.size, albums.size
        )
        binding.fragmentArtistContent.albumTitle.text = albumText
        albumAdapter.swapDataSet(albums)
        binding.fragmentArtistContent.albumTitle.isVisible = albums.isNotEmpty()
        binding.fragmentArtistContent.albumRecyclerView.isVisible = albums.isNotEmpty()

        // الكلمة مكنتش بتظهر لأنه مفيش نص متحدد وقت التشغيل (tools:text بتاعة الـ XML بتشتغل بس وقت المعاينة
        // في Android Studio، مش وقت تشغيل التطبيق فعليًا). النص الحقيقي دلوقتي متحدد في الـ XML نفسه.
        singlesAdapter.swapDataSet(singles)
        binding.fragmentArtistContent.singlesTitle.isVisible = singles.isNotEmpty()
        binding.fragmentArtistContent.singlesRecyclerView.isVisible = singles.isNotEmpty()

        appearsOnAdapter.swapDataSet(appearsOn)
        binding.fragmentArtistContent.appearsOnTitle.isVisible = appearsOn.isNotEmpty()
        binding.fragmentArtistContent.appearsOnRecyclerView.isVisible = appearsOn.isNotEmpty()
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
                    extractColorsAndApplyGradient(resource)
                }

                override fun onLoadCleared(placeholder: Drawable?) {
                    if (_binding != null) {
                        binding.image.setImageDrawable(placeholder)
                    }
                }
            })
    }

    // بدل ما ناخد "أكتر لون متكرر" من الصورة كلها (اللي ممكن يمسك هدوم الفنان في النص)،
    // بناخد متوسط اللون الفعلي من حواف الصورة بس (يمين وشمال) في الجزء السفلي منها،
    // عشان نمسك لون خلفية الصفحة الثابتة (اللي بتظهر بعد ما الصورة تختفي مع السكرول)
    //
    // وبالتوازي، بنستخرج Palette كامل من الصورة (Dominant / Vibrant / Dark Vibrant / Dark Muted)
    // مش لون واحد بس، عشان نبني منه تدرّج سينمائي متعدد الطبقات زي أبل ميوزك بالظبط
    private fun extractColorsAndApplyGradient(bitmap: Bitmap) {
        lifecycleScope.launch(Dispatchers.Default) {
            val flatBackgroundColor = try {
                averageEdgeColor(bitmap)
            } catch (e: Exception) {
                surfaceColor()
            }

            val palette = try {
                RetroColorUtil.generatePalette(bitmap)
            } catch (e: Exception) {
                null
            }

            val gradientColors = RetroColorUtil.getArtistGradientColors(palette, flatBackgroundColor)

            withContext(Dispatchers.Main) {
                setColors(flatBackgroundColor, gradientColors)
            }
        }
    }

    private fun averageEdgeColor(original: Bitmap): Int {
        // بنصغّر الصورة الأول عشان العملية تبقى سريعة (مش محتاجين دقة عالية لمتوسط لون)
        val targetWidth = 100
        val scale = targetWidth.toFloat() / original.width
        val targetHeight = (original.height * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(original, targetWidth, targetHeight, true)

        // الجزء السفلي بس (٢٥٪ من ارتفاع الصورة)
        val stripHeightRatio = 0.25f
        val stripHeight = (scaled.height * stripHeightRatio).toInt().coerceAtLeast(1)
        val startY = (scaled.height - stripHeight).coerceAtLeast(0)

        // حواف الصورة بس (١٨٪ من الشمال + ١٨٪ من اليمين)، مش النص عشان نتجنب جسم/هدوم الفنان
        val edgeWidthRatio = 0.18f
        val edgeWidth = (scaled.width * edgeWidthRatio).toInt().coerceAtLeast(1)

        var rSum = 0L
        var gSum = 0L
        var bSum = 0L
        var count = 0L

        for (y in startY until scaled.height) {
            for (x in 0 until edgeWidth) {
                val px = scaled.getPixel(x, y)
                rSum += Color.red(px)
                gSum += Color.green(px)
                bSum += Color.blue(px)
                count++
            }
            for (x in (scaled.width - edgeWidth) until scaled.width) {
                val px = scaled.getPixel(x, y)
                rSum += Color.red(px)
                gSum += Color.green(px)
                bSum += Color.blue(px)
                count++
            }
        }

        if (scaled !== original) {
            scaled.recycle()
        }

        return if (count == 0L) {
            surfaceColor()
        } else {
            Color.rgb((rSum / count).toInt(), (gSum / count).toInt(), (bSum / count).toInt())
        }
    }

    // بيبني تدرّج سينمائي متعدد الطبقات (Cinematic Gradient) على طريقة أبل ميوزك:
    // Transparent → Dominant → Vibrant → Dark Vibrant → Dark Muted → لون خلفية الصفحة،
    // مع مزج ناعم (Blend) بين كل طبقة واللي بعدها عشان مفيش حدود واضحة بين الألوان.
    //
    // الـ Fade بيبدأ بدري من حوالي 12% بس من ارتفاع الصورة (مش من النص)، فالصورة تفضل
    // واضحة زي ما هي في الجزء العلوي، وبعدين التدرّج بيتكثّف تدريجيًا لحد ما يوصل
    // للون خلفية الصفحة في الآخر من غير أي قطع مفاجئ.
    private fun setColors(flatBackgroundColor: Int, gradient: RetroColorUtil.ArtistGradientColors) {
        if (_binding == null) return

        // خلفية الصفحة الثابتة (اللي بتفضل ظاهرة بعد ما الصورة تختفي مع السكرول)
        binding.rootLayout.setBackgroundColor(flatBackgroundColor)
        // appBarLayout and collapsingToolbar are nullable in the generated binding; use safe calls
        binding.appBarLayout?.setBackgroundColor(flatBackgroundColor)
        binding.collapsingToolbar?.setContentScrimColor(flatBackgroundColor)

        // آخر طبقة في التدرّج متبقاش سودا صافية لو الصورة أصلاً غامقة (Dark Artwork)،
        // فبنمزجها مع لون خلفية الصفحة بدل الاعتماد على Color.BLACK
        val endColor = RetroColorUtil.blendColors(gradient.darkMuted, flatBackgroundColor, 0.6f)

        data class GradientKeyframe(val t: Float, val color: Int, val alpha: Int)

        val fadeStartT = 0.12f // 10-15% من أعلى الصورة، الصورة تفضل واضحة زيها زي Apple Music
        val keyframes = listOf(
            GradientKeyframe(0.00f, gradient.dominant, 0),
            GradientKeyframe(fadeStartT, gradient.dominant, 0),
            GradientKeyframe(0.32f, gradient.dominant, 65),
            GradientKeyframe(0.50f, gradient.vibrant, 135),
            GradientKeyframe(0.68f, gradient.darkVibrant, 200),
            GradientKeyframe(0.85f, gradient.darkMuted, 240),
            GradientKeyframe(1.00f, endColor, 255)
        )

        val stopCount = 24
        val colors = IntArray(stopCount) { i ->
            val t = i / (stopCount - 1f)
            val (from, to) = keyframes.zipWithNext().firstOrNull { (a, b) -> t in a.t..b.t }
                ?: (keyframes.last() to keyframes.last())

            val span = (to.t - from.t).takeIf { it > 0f } ?: 1f
            val localProgress = ((t - from.t) / span).coerceIn(0f, 1f)
            // Smoothstep بدل الانتقال الخطي المباشر، عشان الدمج بين كل لون واللي بعده
            // يبقى ناعم من غير حدود واضحة (زي ما طلب)
            val eased = localProgress * localProgress * (3f - 2f * localProgress)

            val blendedRgb = RetroColorUtil.blendColors(to.color, from.color, eased)
            val alpha = (from.alpha + (to.alpha - from.alpha) * eased).toInt().coerceIn(0, 255)
            ColorUtils.setAlphaComponent(blendedRgb, alpha)
        }

        val gradientDrawable = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
            colors
        )
        binding.headerGradient?.let { it.background = gradientDrawable }

        applyContrastingForegroundColor(flatBackgroundColor)
    }

    // لو اللون المستخرج فاتح (زي خلفية بيضا)، الكلام والأيقونات تتحول لأسود عشان تفضل واضحة،
    // ولو غامق تفضل بيضا زي ما كانت. بنحسب "سطوع" اللون (luminance) ونقارنه بحد وسط.
    private fun applyContrastingForegroundColor(backgroundColor: Int) {
        val isLightBackground = ColorUtils.calculateLuminance(backgroundColor) > 0.5
        val foregroundColor = if (isLightBackground) Color.BLACK else Color.WHITE
        val secondaryForegroundColor = ColorUtils.setAlphaComponent(foregroundColor, 0xB3)
        val subtleOverlay = ColorUtils.setAlphaComponent(foregroundColor, 0x33)
        val subtleStroke = ColorUtils.setAlphaComponent(foregroundColor, 0x1A)

        binding.artistTitle.setTextColor(foregroundColor)
        binding.text.setTextColor(secondaryForegroundColor)

        binding.toolbar.navigationIcon = tintedDrawable(binding.toolbar.navigationIcon, foregroundColor)
        binding.toolbar.overflowIcon = tintedDrawable(binding.toolbar.overflowIcon, foregroundColor)

        binding.fragmentArtistContent.songTitle.setTextColor(foregroundColor)
        binding.fragmentArtistContent.albumTitle.setTextColor(foregroundColor)
        binding.fragmentArtistContent.singlesTitle.setTextColor(foregroundColor)
        binding.fragmentArtistContent.appearsOnTitle.setTextColor(foregroundColor)

        androidx.core.widget.ImageViewCompat.setImageTintList(
            binding.fragmentArtistContent.songSortOrder,
            android.content.res.ColorStateList.valueOf(foregroundColor)
        )

        binding.fragmentArtistContent.infoAction.iconTint =
            android.content.res.ColorStateList.valueOf(foregroundColor)
        binding.fragmentArtistContent.infoAction.backgroundTintList =
            android.content.res.ColorStateList.valueOf(subtleOverlay)
        binding.fragmentArtistContent.infoAction.strokeColor =
            android.content.res.ColorStateList.valueOf(subtleStroke)

        binding.fragmentArtistContent.shuffleAction.iconTint =
            android.content.res.ColorStateList.valueOf(foregroundColor)
        binding.fragmentArtistContent.shuffleAction.backgroundTintList =
            android.content.res.ColorStateList.valueOf(subtleOverlay)
        binding.fragmentArtistContent.shuffleAction.strokeColor =
            android.content.res.ColorStateList.valueOf(subtleStroke)
    }

    private fun tintedDrawable(drawable: Drawable?, color: Int): Drawable? {
        val source = drawable ?: return null
        val wrapped = androidx.core.graphics.drawable.DrawableCompat.wrap(source.mutate())
        androidx.core.graphics.drawable.DrawableCompat.setTint(wrapped, color)
        return wrapped
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

    override fun onMenuItemSelected(item: MenuItem): Boolean {
        return handleSortOrderMenuItem(item)
    }

    private fun handleSortOrderMenuItem(item: MenuItem): Boolean {
        val songs = artist.songs
        when (item.itemId) {
            android.R.id.home -> findNavController().navigateUp()
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
        }
        return true
    }

    private fun setupSongSortButton() {
        binding.fragmentArtistContent.songSortOrder.setOnClickListener {
            PopupMenu(requireContext(), binding.fragmentArtistContent.songSortOrder).apply {
                inflate(R.menu.menu_artist_song_sort_order)
                setUpSortOrderMenu(menu)
                setOnMenuItemClickListener { item ->
                    val sortOrder = when (item.itemId) {
                        R.id.action_sort_order_title -> SortOrder.ArtistSongSortOrder.SONG_A_Z
                        R.id.action_sort_order_title_desc -> SortOrder.ArtistSongSortOrder.SONG_Z_A
                        R.id.action_sort_order_album -> SortOrder.ArtistSongSortOrder.SONG_ALBUM
                        R.id.action_sort_order_year -> SortOrder.ArtistSongSortOrder.SONG_YEAR
                        R.id.action_sort_order_song_duration -> SortOrder.ArtistSongSortOrder.SONG_DURATION
                        else -> {
                            throw IllegalArgumentException("invalid ${item.title}")
                        }
                    }
                    item.isChecked = true
                    setSaveSortOrder(sortOrder)
                    return@setOnMenuItemClickListener true
                }
                show()
            }
        }
    }

    private fun setSaveSortOrder(sortOrder: String) {
        PreferenceUtil.artistDetailSongSortOrder = sortOrder
        songAdapter.swapDataSet(artist.sortedSongs)
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

    override fun onCreateMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_artist_detail, menu)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
