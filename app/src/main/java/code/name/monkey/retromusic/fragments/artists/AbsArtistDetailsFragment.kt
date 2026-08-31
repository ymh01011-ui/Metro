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
import androidx.navigation.NavOptions
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
import com.google.android.material.transition.Hold
import com.google.android.material.transition.MaterialContainerTransform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.get
import java.util.*

private const val FADE_START_FRACTION = 0.42f
private const val SEE_ALL_ALPHA = 0x99 // ~60%
private const val TOOLBAR_ICON_ALPHA = 0xE6 // ~90% - شفافية بسيطة للسهم ونقط المنيو
private const val BIOGRAPHY_GLASS_CORNER_DP = 12f
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
    private var originalAppearanceLightStatusBars: Boolean? = null
    private var hasExtractedColors: Boolean = false
    // موضع أسفل اسم الفنان بالنسبة لأعلى الـ NestedScrollView كله (مش بالنسبة
    // لأبوه المباشر بس)، محسوب مرة واحدة ومتخزن هنا. ده اللي كان ناقص: كنا
    // بنستخدم artistTitle.bottom اللي بيرجع قيمة بالنسبة لأبوه المباشر (اللي هو
    // LinearLayout صغير لف حوالين النص بس)، مش بالنسبة للسكرول كله، فكانت
    // القيمة صغيرة جدًا وبالتالي البار كان بيظهر بمجرد أول سحب بسيط
    private var artistTitleBottomInScrollContent: Int = -1

    private val savedSongSortOrder: String
        get() = PreferenceUtil.artistDetailSongSortOrder

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        sharedElementEnterTransition = MaterialContainerTransform().apply {
            drawingViewId = R.id.fragment_container
            scrimColor = Color.TRANSPARENT
            setAllContainerColors(Color.TRANSPARENT)
            setElevationShadowEnabled(false)
            // FADE_MODE_THROUGH بيقفل محتوى الكارت الأول قبل ما يفتح محتوى الشاشة الجديدة
            // بدل ما الاتنين يترسموا فوق بعض في نفس الوقت (overdraw) - ده بيقلل التقل
            // خصوصًا في الشاشات اللي فيها عناصر/صور كتير.
            fadeMode = MaterialContainerTransform.FADE_MODE_THROUGH
        }
        // بنحددها صراحة (بدل الاعتماد على القيمة الافتراضية) عشان نضمن إن
        // الصورة هترجع تصغر تاني بنفس الأنيميشن بالظبط لما نرجع للـ Artists list
        sharedElementReturnTransition = MaterialContainerTransform().apply {
            drawingViewId = R.id.fragment_container
            scrimColor = Color.TRANSPARENT
            setAllContainerColors(Color.TRANSPARENT)
            setElevationShadowEnabled(false)
            fadeMode = MaterialContainerTransform.FADE_MODE_THROUGH
        }

        // العنصر المشترك دلوقتي بقى الشاشة كلها (rootLayout) مش هيدر جزئي بس،
        // فالـ MaterialContainerTransform هيحول الكارت الصغير في القائمة للشاشة
        // بالكامل بمحتواها كلها مع بعض، فمحتاجينش أي enterTransition/returnTransition
        // إضافية لباقي المحتوى.

        // استخدام Hold للحفاظ على الشاشة الحالية صلبة وتحت الشاشة الجديدة تماماً
        exitTransition = Hold().apply {
            duration = 350L
        }
        reenterTransition = Hold().apply {
            duration = 350L
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentArtistDetailsBinding.bind(view)

        val initialColor = if (hasExtractedColors) dominantBackgroundColor else neutralFallbackColor()
        binding.rootLayout.setBackgroundColor(initialColor)

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

        // مساحة فاضية تحت المحتوى كله بارتفاع ربع الشاشة تقريبًا
        binding.fragmentArtistContent.bottomSpacer.layoutParams.height =
            resources.displayMetrics.heightPixels / 4
        binding.fragmentArtistContent.bottomSpacer.requestLayout()

        binding.appBarLayout?.alpha = 1f
        binding.toolbar.isClickable = true

        binding.content.setOnScrollChangeListener(androidx.core.widget.NestedScrollView.OnScrollChangeListener { _, _, scrollY, _, _ ->
            // artistTitle.bottom بيرجع الموضع بالنسبة لأبوه المباشر بس (اللي هو
            // LinearLayout صغير لف حوالين النص)، مش بالنسبة لبداية محتوى السكرول
            // كله - فكانت القيمة دايمًا صغيرة وبيبان البار بمجرد أول سحب. بنحسبها
            // هنا صح مرة واحدة بس (أول سكرول) ونخزنها، لأن الـ layout مش هيتغير.
            if (artistTitleBottomInScrollContent <= 0) {
                val titleLocation = IntArray(2)
                binding.artistTitle.getLocationOnScreen(titleLocation)
                val contentLocation = IntArray(2)
                binding.content.getLocationOnScreen(contentLocation)
                val titleBottomOnScreen = titleLocation[1] + binding.artistTitle.height
                artistTitleBottomInScrollContent = (titleBottomOnScreen - contentLocation[1]) + scrollY
            }

            // ارتفاع الـ appBar نفسه (padding الـ status bar + التولبار) - ده
            // المكان اللي اسم الفنان بيختفي وراه فعليًا وهو بيسكرول لفوق، مش
            // لما يوصل لأعلى الشاشة تمامًا (0). من غير طرح الارتفاع ده كان فيه
            // تأخير: البار كان بيستنى لحد ما الاسم يبقى مختفي بمسافة كمان
            // (بارتفاع الـ appBar) قبل ما يبدأ يظهر.
            val appBarHeight = binding.appBarLayout?.height ?: 0
            val titleBottom = artistTitleBottomInScrollContent - appBarHeight
            if (titleBottom > 0) {
                // نطاق ظهور صغير (32dp محولة صح بالـ density، مش بكسلات خام زي الأول)
                // عشان البار يفضل مخفي تمامًا لحد ما اسم الفنان يوشك يختفي وراه بالظبط،
                // وبعدين يظهر بسرعة بدل ما يتلون بالتدريج طول السحب.
                val fadeWindowPx = (32 * resources.displayMetrics.density).toInt()
                val startFade = titleBottom - fadeWindowPx / 2
                val endFade = titleBottom + fadeWindowPx / 2

                val alphaProgress = when {
                    scrollY <= startFade -> 0f
                    scrollY >= endFade -> 1f
                    else -> (scrollY - startFade).toFloat() / (endFade - startFade)
                }

                val dynamicColor = ColorUtils.setAlphaComponent(dominantBackgroundColor, (alphaProgress * 255).toInt())
                binding.appBarLayout?.setBackgroundColor(dynamicColor)

                // اسم الفنان بيظهر جوه الـ toolbar بس لما البار يكون قرب يبان بالكامل
                if (alphaProgress >= 0.9f) {
                    if (toolbar.title.isNullOrEmpty()) {
                        toolbar.title = binding.artistTitle.text
                    }
                } else {
                    toolbar.title = null
                }
            }
        })

        // الـ transitionName بقى على rootLayout (الشاشة كلها) مش headerContainer،
        // عشان يتطابق مع الكارت الكامل في القائمة (itemView) بدل ما يتطابق مع صورة بس.
        binding.rootLayout.transitionName = (artistId ?: artistName).toString()

        postponeEnterTransition()

        detailsViewModel.getArtist().observe(viewLifecycleOwner) {
            showArtist(it)
            view.doOnPreDraw {
                startPostponedEnterTransition()
            }
        }

        detailsViewModel.getBiography().observe(viewLifecycleOwner) { bio ->
            if (bio.isNullOrBlank()) {
                binding.fragmentArtistContent.biographyTitle.visibility = View.GONE
                binding.fragmentArtistContent.biographyCardWrapper.visibility = View.GONE
            } else {
                binding.fragmentArtistContent.biographyTitle.visibility = View.VISIBLE
                binding.fragmentArtistContent.biographyCardWrapper.visibility = View.VISIBLE
                binding.fragmentArtistContent.biographyText.text = bio
                // نرجّعها تتقفل (3 أسطر) كل ما فنان جديد يتحمل
                binding.fragmentArtistContent.biographyText.maxLines = 3
                binding.fragmentArtistContent.biographyMore.text = "More"
            }
        }

        binding.fragmentArtistContent.biographyMore.setOnClickListener {
            val bioText = binding.fragmentArtistContent.biographyText
            val isExpanded = bioText.maxLines > 3
            if (isExpanded) {
                bioText.maxLines = 3
                binding.fragmentArtistContent.biographyMore.text = "More"
            } else {
                bioText.maxLines = Int.MAX_VALUE
                binding.fragmentArtistContent.biographyMore.text = "Less"
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
                // انتقال iOS-style: بنشيل الـ Transition Framework خالص هنا ونستخدم
                // Animation resources كلاسيكية تحاكي push/pop بتاعة UINavigationController:
                // الشاشة الجايه بتغطي الشاشة كلها من اليمين، والشاشة الحالية بتعمل
                // parallax بسيط لليسار وتضلم شوية، والعكس بالظبط وأنت راجع.
                exitTransition = null
                reenterTransition = null

                val navOptions = NavOptions.Builder()
                    .setEnterAnim(R.anim.nav_slide_in_right)
                    .setExitAnim(R.anim.nav_slide_out_left)
                    .setPopEnterAnim(R.anim.nav_slide_in_left)
                    .setPopExitAnim(R.anim.nav_slide_out_right)
                    .build()

                findNavController().navigate(
                    R.id.artistAllSongsFragment,
                    ArtistAllSongsFragment.createBundle(
                        artist, 
                        artist.sortedSongs, 
                        (artistId ?: artistName).toString(),
                        dominantBackgroundColor
                    ),
                    navOptions
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
        // بنعمل كل RecyclerView "transition group" عشان الـ Container Transform ياخد
        // لقطة (snapshot) من الليست ككل بدل ما يحاول يتعامل مع كل عنصر جوه القائمة
        // لوحده وقت التحويل - ده اللي بيسبب التقل في الفنانين اللي عندهم عناصر كتير.
        // وبنشيل itemAnimator وقت الإعداد الأول عشان ميتعارضش مع أنيميشن الدخول.

        albumAdapter = HorizontalAlbumAdapter(requireActivity(), ArrayList(), this)
        binding.fragmentArtistContent.albumRecyclerView.apply {
            androidx.core.view.ViewGroupCompat.setTransitionGroup(this, true)
            itemAnimator = null
            layoutManager = GridLayoutManager(this.context, 1, GridLayoutManager.HORIZONTAL, false)
            adapter = albumAdapter
        }

        singlesAdapter = HorizontalAlbumAdapter(requireActivity(), ArrayList(), this)
        binding.fragmentArtistContent.singlesRecyclerView.apply {
            androidx.core.view.ViewGroupCompat.setTransitionGroup(this, true)
            itemAnimator = null
            layoutManager = GridLayoutManager(this.context, 1, GridLayoutManager.HORIZONTAL, false)
            adapter = singlesAdapter
        }

        appearsOnAdapter = HorizontalAlbumAdapter(requireActivity(), ArrayList(), this)
        binding.fragmentArtistContent.appearsOnRecyclerView.apply {
            androidx.core.view.ViewGroupCompat.setTransitionGroup(this, true)
            itemAnimator = null
            layoutManager = GridLayoutManager(this.context, 1, GridLayoutManager.HORIZONTAL, false)
            adapter = appearsOnAdapter
        }

        songAdapter = SimpleSongAdapter(requireActivity(), ArrayList(), R.layout.item_song)
        binding.fragmentArtistContent.recyclerView.apply {
            androidx.core.view.ViewGroupCompat.setTransitionGroup(this, true)
            itemAnimator = null
            layoutManager = LinearLayoutManager(this.context)
            adapter = songAdapter
        }

        // نرجع الـ itemAnimator العادي بعد ما أنيميشن الدخول يخلص، عشان تحديثات الليست
        // بعد كده (زي تغيير الترتيب) تفضل بتتحرك بشكل طبيعي.
        view?.doOnPreDraw {
            binding.fragmentArtistContent.albumRecyclerView.itemAnimator = DefaultItemAnimator()
            binding.fragmentArtistContent.singlesRecyclerView.itemAnimator = DefaultItemAnimator()
            binding.fragmentArtistContent.appearsOnRecyclerView.itemAnimator = DefaultItemAnimator()
            binding.fragmentArtistContent.recyclerView.itemAnimator = DefaultItemAnimator()
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

    private fun applySongsPreview(sortedSongs: List<Song>) {
        val hasMoreSongs = sortedSongs.size > SONGS_PREVIEW_COUNT
        songAdapter.swapDataSet(
            if (hasMoreSongs) sortedSongs.take(SONGS_PREVIEW_COUNT) else sortedSongs
        )
        binding.fragmentArtistContent.seeAllSongs.isVisible = hasMoreSongs
    }

    private fun loadArtistImage(artist: Artist) {
        if (cachedBitmap != null && hasExtractedColors && cachedGradientStops != null) {
            binding.image.setImageBitmap(cachedBitmap)
            setColors(dominantBackgroundColor, cachedGradientStops!!)
            return
        }

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
                    cachedBitmap = resource
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
                hasExtractedColors = true
                cachedGradientStops = gradientStops
                dominantBackgroundColor = dominantColor

                if (_binding != null) {
                    binding.image.setImageBitmap(bitmap)
                    setColors(dominantColor, gradientStops)
                }
            }
        }
    }

    private fun setColors(backgroundColor: Int, gradientStops: IntArray) {
        if (_binding == null) return

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

        val toolbar = binding.toolbar
        val iconColor = ColorUtils.setAlphaComponent(foregroundColor, TOOLBAR_ICON_ALPHA)
        if (toolbar is TintableToolbar) {
            toolbar.navigationIcon?.let { DrawableCompat.setTint(it, iconColor) }
            toolbar.setOverflowIconTint(iconColor)
            toolbar.setTitleTextColor(foregroundColor)
        } else if (toolbar is androidx.appcompat.widget.Toolbar) {
            toolbar.navigationIcon?.let { DrawableCompat.setTint(it, iconColor) }
            toolbar.overflowIcon?.let { DrawableCompat.setTint(it, iconColor) }
            toolbar.setTitleTextColor(foregroundColor)
        }

        binding.fragmentArtistContent.songTitle.setTextColor(foregroundColor)
        binding.fragmentArtistContent.seeAllSongs.setTextColor(
            ColorUtils.setAlphaComponent(foregroundColor, SEE_ALL_ALPHA)
        )
        binding.fragmentArtistContent.albumTitle.setTextColor(foregroundColor)
        binding.fragmentArtistContent.singlesTitle.setTextColor(foregroundColor)
        binding.fragmentArtistContent.appearsOnTitle.setTextColor(foregroundColor)

        binding.fragmentArtistContent.biographyTitle.setTextColor(foregroundColor)
        binding.fragmentArtistContent.biographyText.setTextColor(secondaryForegroundColor)
        binding.fragmentArtistContent.biographyMore.setTextColor(foregroundColor)

        // شريط الحالة (الساعة/البطارية فوق) يتحول لأيقونات غامقة لما الخلفية فاتحة،
        // وأيقونات بيضة لما الخلفية غامقة، بنفس منطق isLightBackground المستخدم هنا.
        // بنسجل الحالة الأصلية أول مرة بس، عشان نرجعها زي ما كانت لما نخرج من الشاشة.
        activity?.window?.let { window ->
            val controller = androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)
            if (originalAppearanceLightStatusBars == null) {
                originalAppearanceLightStatusBars = controller.isAppearanceLightStatusBars
            }
            controller.isAppearanceLightStatusBars = isLightBackground
        }

        binding.fragmentArtistContent.playAction.elevation = 0f

        // التلات دواير بقوا زجاج حقيقي (بلور GPU + انكسار/تشتت لوني) بدل
        // الشفافية الثابتة القديمة - بنمرر لون خلفية الصفحة الحقيقي، وهو اللي
        // بيتعمله البلور والشيدر فوقه جوه LiquidGlassView
        binding.fragmentArtistContent.infoActionGlass.setBackdropColor(backgroundColor)
        binding.fragmentArtistContent.playActionGlass.setBackdropColor(backgroundColor)
        binding.fragmentArtistContent.shuffleActionGlass.setBackdropColor(backgroundColor)

        binding.fragmentArtistContent.infoAction.iconTint =
            android.content.res.ColorStateList.valueOf(foregroundColor)
        binding.fragmentArtistContent.shuffleAction.iconTint =
            android.content.res.ColorStateList.valueOf(foregroundColor)
        binding.fragmentArtistContent.playAction.imageTintList =
            android.content.res.ColorStateList.valueOf(foregroundColor)

        // نفس الزجاج الحقيقي على مستطيل الـ Biography، بنفس حواف الكارت الدائرية
        binding.fragmentArtistContent.biographyCardGlass.cornerRadiusPx =
            BIOGRAPHY_GLASS_CORNER_DP * resources.displayMetrics.density
        binding.fragmentArtistContent.biographyCardGlass.setBackdropColor(backgroundColor)

        songAdapter.setDynamicTextColors(foregroundColor, secondaryForegroundColor)
        albumAdapter.setDynamicTextColors(foregroundColor, secondaryForegroundColor)
        singlesAdapter.setDynamicTextColors(foregroundColor, secondaryForegroundColor)
        appearsOnAdapter.setDynamicTextColors(foregroundColor, secondaryForegroundColor)
    }

    override fun onAlbumClick(albumId: Long, view: View) {
        // الانتقال ده فيه shared element حقيقي (transitionName بتاع الألبوم)
        // فبيعتمد على Hold() عشان الشاشة الحالية تفضل ثابتة ورا الـ MaterialContainerTransform
        // في الذهاب والرجوع
        exitTransition = Hold().apply {
            duration = 350L
        }
        reenterTransition = Hold().apply {
            duration = 350L
        }
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
                    clearImageCache()
                }
                forceDownload = true
                return true
            }

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
    
    private fun neutralFallbackColor(): Int {
        val typedValue = android.util.TypedValue()
        val resolved = requireContext().theme.resolveAttribute(
            com.google.android.material.R.attr.colorSurface, typedValue, true
        )
        return if (resolved) typedValue.data else Color.BLACK
    }

    private fun clearImageCache() {
        cachedBitmap = null
        cachedGradientStops = null
        hasExtractedColors = false
    }

    private fun setSaveSortOrder(sortOrder: String) {
        PreferenceUtil.artistDetailSongSortOrder = sortOrder
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
                    clearImageCache()
                }
            }
        }

    override fun onDestroyView() {
        // نرجّع شكل شريط الحالة (فاتح/غامق) زي ما كان قبل ما ندخل الشاشة دي
        originalAppearanceLightStatusBars?.let { original ->
            activity?.window?.let { window ->
                androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)
                    .isAppearanceLightStatusBars = original
            }
        }
        super.onDestroyView()
        _binding = null
    }
}
