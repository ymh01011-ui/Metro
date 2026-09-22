/*
 * Copyright (c) 2020 Hemanth Savarla.
 *
 * Licensed under the GNU General Public License v3
 *
 * This is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 */
package code.name.monkey.retromusic.fragments.albums

import android.app.ActivityOptions
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.*
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnPreDraw
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import code.name.monkey.retromusic.EXTRA_ALBUM_ID
import code.name.monkey.retromusic.EXTRA_ARTIST_ID
import code.name.monkey.retromusic.EXTRA_ARTIST_NAME
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.activities.tageditor.AbsTagEditorActivity
import code.name.monkey.retromusic.activities.tageditor.AlbumTagEditorActivity
import code.name.monkey.retromusic.adapter.ArtistPickerAdapter
import code.name.monkey.retromusic.adapter.album.HorizontalAlbumAdapter
import code.name.monkey.retromusic.adapter.song.SimpleSongAdapter
import code.name.monkey.retromusic.databinding.FragmentAlbumDetailsBinding
import code.name.monkey.retromusic.dialogs.AddToPlaylistDialog
import code.name.monkey.retromusic.dialogs.DeleteSongsDialog
import code.name.monkey.retromusic.extensions.*
import code.name.monkey.retromusic.fragments.base.AbsMainActivityFragment
import code.name.monkey.retromusic.glide.RetroGlideExtension
import code.name.monkey.retromusic.glide.RetroGlideExtension.albumCoverOptions
import code.name.monkey.retromusic.helper.MusicPlayerRemote
import code.name.monkey.retromusic.helper.SortOrder.AlbumSongSortOrder.Companion.SONG_A_Z
import code.name.monkey.retromusic.helper.SortOrder.AlbumSongSortOrder.Companion.SONG_DURATION
import code.name.monkey.retromusic.helper.SortOrder.AlbumSongSortOrder.Companion.SONG_TRACK_LIST
import code.name.monkey.retromusic.helper.SortOrder.AlbumSongSortOrder.Companion.SONG_Z_A
import code.name.monkey.retromusic.interfaces.IAlbumClickListener
import code.name.monkey.retromusic.model.Album
import code.name.monkey.retromusic.model.Artist
import code.name.monkey.retromusic.repository.RealRepository
import code.name.monkey.retromusic.util.*
import code.name.monkey.retromusic.views.TintableToolbar
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.DownsampleStrategy
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.shape.MaterialShapeDrawable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.get
import org.koin.androidx.viewmodel.ext.android.viewModel
import org.koin.core.parameter.parametersOf
import java.text.Collator

private const val FADE_START_FRACTION = 0.42f
private const val TOOLBAR_ICON_ALPHA = 0xCC // ~80%، بنفس فلسفة صفحة الفنان بالظبط

class AlbumDetailsFragment : AbsMainActivityFragment(R.layout.fragment_album_details),
    IAlbumClickListener {

    private var _binding: FragmentAlbumDetailsBinding? = null
    private val binding get() = _binding!!

    private val arguments by navArgs<AlbumDetailsFragmentArgs>()
    private val detailsViewModel by viewModel<AlbumDetailsViewModel> {
        parametersOf(arguments.extraAlbumId)
    }

    private lateinit var simpleSongAdapter: SimpleSongAdapter
    private var moreAlbumAdapter: HorizontalAlbumAdapter? = null
    private lateinit var album: Album
    private var albumArtistExists = false

    // نفس فلسفة صفحة الفنان بالظبط: بندير التولبار والمنيو بتاعتنا بنفسنا،
    // مش عن طريق الـ MenuHost المشترك بتاع الـ Activity - لازمة عشان نقدر
    // نتحكم في لون أيقونة السهم/النقط بنفسنا حسب لون الغلاف المستخرج.
    override val registersMenuProvider: Boolean = false
    private var customOverflowIcon: ImageView? = null
    private var dominantBackgroundColor: Int = Color.BLACK
    private var cachedBitmap: Bitmap? = null
    private var cachedGradientStops: IntArray? = null
    private var hasExtractedColors: Boolean = false
    private var albumTitleBottomInScrollContent: Int = -1
    private var foregroundColor: Int = Color.WHITE
    private var secondaryForegroundColor: Int = Color.WHITE

    private val savedSortOrder: String
        get() = PreferenceUtil.albumDetailSongSortOrder

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // الأنيميشن بقى متحكم فيه من الـ NavOptions بتاعة الـ navigate() اللي
        // بيفتح الصفحة دي (نفس anim resources بتاعة صفحة الفنان: nav_slide_in_right/
        // out_left/in_left/out_right) - مش من Fragment Transition framework. فمفيش
        // داعي لـ MaterialContainerTransform هنا، خصوصًا إن الهيدر بقى edge-to-edge
        // مش كارت تربيعي زي الأول (فمفيش حاجة تتعمل عليها Container Transform أصلاً).
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        postponeEnterTransition()

        _binding = FragmentAlbumDetailsBinding.bind(view)
        mainActivity.addMusicServiceEventListener(detailsViewModel)

        // لو الألبوم ده في الكاش، لونه جاهز - بنطبقه فورًا قبل ما الصورة نفسها
        // تتحمل، عشان الخلفية والأزرار ما تبانش بلون محايد لحظة وبعدين تتغير.
        AlbumDetailsCache.getColor(arguments.extraAlbumId)?.let { cachedColor ->
            dominantBackgroundColor = cachedColor
            hasExtractedColors = true
            setColors(cachedColor, ArtistPaletteEngine.buildSeamlessGradient(cachedColor, FADE_START_FRACTION))
        }
        val initialColor = if (hasExtractedColors) dominantBackgroundColor else neutralFallbackColor()
        binding.rootLayout.setBackgroundColor(initialColor)

        val toolbar = binding.toolbar as TintableToolbar
        toolbar.title = null
        toolbar.contentInsetStartWithNavigation = 0
        toolbar.setTitleMarginStart(0)
        toolbar.contentInsetEndWithActions = 0
        toolbar.setPadding(toolbar.paddingLeft, toolbar.paddingTop, 0, toolbar.paddingBottom)
        toolbar.setNavigationOnClickListener {
            findNavController().navigateUp()
        }

        view.post {
            if (_binding == null) return@post
            toolbar.inflateMenu(R.menu.menu_album_detail)
            setUpSortOrderMenu(toolbar.menu.findItem(R.id.action_sort_order).subMenu!!)
            toolbar.setOnMenuItemClickListener { item ->
                handleSortOrderMenuItem(item)
            }
            setUpCustomOverflowIcon(toolbar)
        }

        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(requireActivity().window, false)

        binding.appBarLayout?.let { appBar ->
            ViewCompat.setOnApplyWindowInsetsListener(appBar) { v, insets ->
                val statusBarInsets = insets.getInsets(WindowInsetsCompat.Type.statusBars())
                v.updatePadding(top = statusBarInsets.top)
                insets
            }
        }

        binding.fragmentAlbumContent.bottomSpacer.layoutParams.height =
            resources.displayMetrics.heightPixels / 4
        binding.fragmentAlbumContent.bottomSpacer.requestLayout()

        binding.appBarLayout?.alpha = 1f
        binding.toolbar.isClickable = true
        binding.content.isVerticalScrollBarEnabled = false

        binding.content.setOnScrollChangeListener(androidx.core.widget.NestedScrollView.OnScrollChangeListener { _, _, scrollY, _, _ ->
            if (albumTitleBottomInScrollContent <= 0) {
                val titleLocation = IntArray(2)
                binding.albumTitle.getLocationOnScreen(titleLocation)
                val contentLocation = IntArray(2)
                binding.content.getLocationOnScreen(contentLocation)
                val titleBottomOnScreen = titleLocation[1] + binding.albumTitle.height
                albumTitleBottomInScrollContent = (titleBottomOnScreen - contentLocation[1]) + scrollY
            }

            val appBarHeight = binding.appBarLayout?.height ?: 0
            val titleBottom = albumTitleBottomInScrollContent - appBarHeight
            if (titleBottom > 0) {
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

                if (alphaProgress >= 0.9f) {
                    if (toolbar.title.isNullOrEmpty()) {
                        toolbar.title = binding.albumTitle.text
                    }
                } else {
                    toolbar.title = null
                }
            }
        })

        detailsViewModel.getAlbum().observe(viewLifecycleOwner) { album ->
            albumArtistExists = !album.albumArtist.isNullOrEmpty()
            showAlbum(album)
        }

        setupRecyclerView()
        binding.albumText.setOnClickListener {
            // If this album's artist tag is actually a combined tag (e.g.
            // "Alan Walker, Au/Ra, Tomine Harket") and multi-artist mode is
            // on, let the user pick which one to open - same behavior as the
            // Now Playing screen - instead of always opening the raw combined
            // entry or guessing a single one.
            if (PreferenceUtil.multiArtistsEnabled) {
                val nameToSplit = if (albumArtistExists) album.albumArtist!! else album.artistName
                val splitNames = ArtistTagUtil.splitArtistNames(nameToSplit)
                when {
                    splitNames.size > 1 -> {
                        lifecycleScope.launch(Dispatchers.IO) {
                            val repository = get<RealRepository>()
                            val artists = splitNames.map { repository.multiArtistByName(it) }
                            withContext(Dispatchers.Main) {
                                showArtistPickerDialog(artists)
                            }
                        }
                    }

                    splitNames.size == 1 -> goToMultiArtistFromAlbum(splitNames[0])
                    else -> goToMultiArtistFromAlbum(album.artistName)
                }
                return@setOnClickListener
            }

            if (albumArtistExists) {
                findActivityNavController(R.id.fragment_container)
                    .navigate(
                        R.id.albumArtistDetailsFragment,
                        bundleOf(EXTRA_ARTIST_NAME to album.albumArtist)
                    )
            } else {
                findActivityNavController(R.id.fragment_container)
                    .navigate(
                        R.id.artistDetailsFragment,
                        bundleOf(EXTRA_ARTIST_ID to album.artistId)
                    )
            }
        }
        binding.fragmentAlbumContent.playAction.setOnClickListener {
            if (::album.isInitialized) MusicPlayerRemote.openQueue(album.songs, 0, true)
        }
        binding.fragmentAlbumContent.shuffleAction.setOnClickListener {
            if (::album.isInitialized) MusicPlayerRemote.openAndShuffleQueue(album.songs, true)
        }
        binding.fragmentAlbumContent.addAction.setOnClickListener {
            if (::album.isInitialized) addAlbumSongsToPlaylist()
        }

        binding.appBarLayout?.statusBarForeground =
            MaterialShapeDrawable.createWithElevationOverlay(requireContext())

        view.doOnPreDraw {
            startPostponedEnterTransition()
        }
    }

    private fun showArtistPickerDialog(artists: List<Artist>) {
        val adapter = ArtistPickerAdapter(requireActivity() as AppCompatActivity, artists)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.action_go_to_artist)
            .setAdapter(adapter) { _, which ->
                findActivityNavController(R.id.fragment_container).navigate(
                    R.id.multiArtistDetailsFragment,
                    bundleOf(EXTRA_ARTIST_NAME to artists[which].name)
                )
            }
            .show()
    }

    private fun goToMultiArtistFromAlbum(artistName: String) {
        findActivityNavController(R.id.fragment_container)
            .navigate(
                R.id.multiArtistDetailsFragment,
                bundleOf(EXTRA_ARTIST_NAME to artistName)
            )
    }

    private fun addAlbumSongsToPlaylist() {
        lifecycleScope.launch(Dispatchers.IO) {
            val playlists = get<RealRepository>().fetchPlaylists()
            withContext(Dispatchers.Main) {
                if (_binding == null) return@withContext
                AddToPlaylistDialog.create(playlists, album.songs)
                    .show(childFragmentManager, "ADD_PLAYLIST")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceActivity?.removeMusicServiceEventListener(detailsViewModel)
    }

    private fun setupRecyclerView() {
        simpleSongAdapter = SimpleSongAdapter(
            requireActivity() as AppCompatActivity,
            ArrayList(),
            // ليها layout خاص بيها (item_song_album_details) بدل item_song
            // العام: رقم المسار بدل صورة الأغنية زي Apple Music.
            R.layout.item_song_album_details
        )
        binding.fragmentAlbumContent.recyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            itemAnimator = DefaultItemAnimator()
            isNestedScrollingEnabled = false
            adapter = simpleSongAdapter
        }
    }

    private fun showAlbum(album: Album) {
        if (album.songs.isEmpty()) {
            findNavController().navigateUp()
            return
        }
        this.album = album

        binding.albumTitle.text = album.title
        binding.albumText.text =
            if (albumArtistExists) album.albumArtist else album.artistName

        // بيفضل نفس الـ transitionName القديم عشان أنيميشن الانتقال لـ
        // AlbumTagEditorActivity (Activity منفصلة، مش جوه الـ Nav Graph)
        // يفضل شغال زي الأول، بس دلوقتي من صورة الغلاف نفسها بما إن الكارت
        // اتشال.
        binding.image.transitionName = "${getString(R.string.transition_album_art)}_${album.id}"

        val songText = resources.getQuantityString(
            R.plurals.albumSongs,
            album.songCount,
            album.songCount
        )
        binding.fragmentAlbumContent.songTitle.text = songText
        if (MusicUtil.getYearString(album.year) == "-") {
            binding.fragmentAlbumContent.albumFooterText.text = String.format(
                "%s • %s",
                if (albumArtistExists) album.albumArtist else album.artistName,
                MusicUtil.getReadableDurationString(MusicUtil.getTotalDuration(album.songs))
            )
        } else {
            binding.fragmentAlbumContent.albumFooterText.text = String.format(
                "%s • %s • %s",
                if (albumArtistExists) album.albumArtist else album.artistName,
                MusicUtil.getYearString(album.year),
                MusicUtil.getReadableDurationString(MusicUtil.getTotalDuration(album.songs))
            )
        }
        loadAlbumCover(album)
        simpleSongAdapter.swapDataSet(album.songs)
        if (albumArtistExists) {
            detailsViewModel.getAlbumArtist(album.albumArtist.toString())
                .observe(viewLifecycleOwner) {
                    loadMoreAlbums(it)
                }
        } else {
            detailsViewModel.getArtist(album.artistId).observe(viewLifecycleOwner) {
                loadMoreAlbums(it)
            }
        }
    }

    private fun moreAlbums(albums: List<Album>) {
        binding.fragmentAlbumContent.moreTitle.show()
        binding.fragmentAlbumContent.moreRecyclerView.show()
        binding.fragmentAlbumContent.moreTitle.text =
            String.format(getString(R.string.label_more_from), album.artistName)

        val albumAdapter =
            HorizontalAlbumAdapter(requireActivity() as AppCompatActivity, albums, this)
        moreAlbumAdapter = albumAdapter
        binding.fragmentAlbumContent.moreRecyclerView.layoutManager = GridLayoutManager(
            requireContext(),
            1,
            GridLayoutManager.HORIZONTAL,
            false
        )
        binding.fragmentAlbumContent.moreRecyclerView.adapter = albumAdapter
        // لو الألوان اتحسبت خلاص قبل ما القائمة دي توصل، نطبقها فورًا -
        // وإلا هتتطبق مع أول extractColorsAndApplyGradient لما الصورة توصل.
        if (hasExtractedColors) {
            albumAdapter.setDynamicTextColors(foregroundColor, secondaryForegroundColor)
        }
    }

    // كانت الدالة دي بتحمّل صورة الفنان الدائرية اللي كانت جنب العنوان، لكن
    // التصميم الجديد (زي Apple Music) مفيهوش الصورة دي، فبقت مسؤوليتها
    // الوحيدة إنها تجيب "More by Artist".
    private fun loadMoreAlbums(artist: Artist) {
        detailsViewModel.getMoreAlbums(artist).observe(viewLifecycleOwner) {
            moreAlbums(it)
        }
    }

    private fun loadAlbumCover(album: Album) {
        if (cachedBitmap != null && hasExtractedColors && cachedGradientStops != null) {
            binding.image.setImageBitmap(cachedBitmap)
            setColors(dominantBackgroundColor, cachedGradientStops!!)
            return
        }

        // نفس سقف الحجم بالظبط اللي في صفحة الفنان (1.5× عرض الشاشة، بحد أقصى
        // 2048px) عشان الفك يبقى أسرع من غير ما شكل الغلاف ولا اللون المستخرج
        // منه يتغيروا.
        val maxImageSide = minOf((resources.displayMetrics.widthPixels * 3) / 2, 2048)
        val albumId = album.id
        Glide.with(requireContext())
            .asBitmap()
            .albumCoverOptions(album.safeGetFirstSong())
            .load(RetroGlideExtension.getSongModel(album.safeGetFirstSong()))
            .override(maxImageSide)
            .downsample(DownsampleStrategy.CENTER_INSIDE)
            .dontAnimate()
            .into(object : CustomTarget<Bitmap>() {
                override fun onResourceReady(
                    resource: Bitmap,
                    transition: Transition<in Bitmap>?
                ) {
                    cachedBitmap = resource
                    extractColorsAndApplyGradient(albumId, resource)
                }

                override fun onLoadCleared(placeholder: Drawable?) {
                    if (_binding != null) {
                        binding.image.setImageDrawable(placeholder)
                    }
                }
            })
    }

    private fun extractColorsAndApplyGradient(albumId: Long, bitmap: Bitmap) {
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
                AlbumDetailsCache.putColor(albumId, dominantColor)

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

        val gradientDrawable = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            gradientStops
        )
        binding.headerGradient?.let { it.background = gradientDrawable }

        applyContrastingForegroundColor(backgroundColor)
    }

    private fun applyContrastingForegroundColor(backgroundColor: Int) {
        val isLightBackground = ColorUtils.calculateLuminance(backgroundColor) > 0.45f
        val fgColor = if (isLightBackground) Color.BLACK else Color.WHITE
        val secondaryFgColor = ColorUtils.setAlphaComponent(fgColor, 0xCC)
        foregroundColor = fgColor
        secondaryForegroundColor = secondaryFgColor

        binding.albumTitle.setTextColor(fgColor)
        binding.albumText.setTextColor(secondaryFgColor)

        val toolbar = binding.toolbar
        val iconColor = ColorUtils.setAlphaComponent(fgColor, TOOLBAR_ICON_ALPHA)
        if (toolbar is TintableToolbar) {
            toolbar.navigationIcon?.let { DrawableCompat.setTint(it, iconColor) }
            customOverflowIcon?.drawable?.let { DrawableCompat.setTint(it.mutate(), iconColor) }
            toolbar.setTitleTextColor(fgColor)
        }

        binding.fragmentAlbumContent.songTitle.setTextColor(fgColor)
        binding.fragmentAlbumContent.albumFooterText.setTextColor(secondaryFgColor)
        binding.fragmentAlbumContent.moreTitle.setTextColor(fgColor)

        applyStatusBarAppearance(isLightBackground)

        binding.fragmentAlbumContent.shuffleActionGlass.setBackdropColor(backgroundColor)
        binding.fragmentAlbumContent.playActionGlass.setBackdropColor(backgroundColor)
        binding.fragmentAlbumContent.addActionGlass.setBackdropColor(backgroundColor)

        binding.fragmentAlbumContent.shuffleAction.iconTint = ColorStateList.valueOf(fgColor)
        binding.fragmentAlbumContent.addAction.iconTint = ColorStateList.valueOf(fgColor)
        binding.fragmentAlbumContent.playAction.imageTintList = ColorStateList.valueOf(fgColor)

        simpleSongAdapter.setDynamicTextColors(fgColor, secondaryFgColor)
        moreAlbumAdapter?.setDynamicTextColors(fgColor, secondaryFgColor)
    }

    private fun applyStatusBarAppearance(isLightBackground: Boolean) {
        activity?.window?.let { window ->
            androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)
                .isAppearanceLightStatusBars = isLightBackground
        }
    }

    private fun neutralFallbackColor(): Int {
        val typedValue = TypedValue()
        val resolved = requireContext().theme.resolveAttribute(
            com.google.android.material.R.attr.colorSurface, typedValue, true
        )
        return if (resolved) typedValue.data else Color.BLACK
    }

    // نفس الحيلة بالظبط اللي في صفحة الفنان: أيقونة النقط الحقيقية بتاعة
    // الـ Toolbar بنخفيها ونحط ImageView إحنا عليها بدالها كـ sibling
    // للـ appBarLayout جوه rootLayout، عشان نقدر نتحكم في لونها بنفس طريقة
    // سهم الرجوع بالظبط.
    private fun setUpCustomOverflowIcon(toolbar: TintableToolbar) {
        val icon = toolbar.overflowIcon?.mutate() ?: return
        toolbar.overflowIcon = ColorDrawable(Color.TRANSPARENT)

        val sizePx = (48 * resources.displayMetrics.density).toInt()
        val iconPaddingPx = (12 * resources.displayMetrics.density).toInt()
        val backgroundTypedValue = TypedValue()
        requireContext().theme.resolveAttribute(
            android.R.attr.selectableItemBackgroundBorderless, backgroundTypedValue, true
        )

        val imageView = ImageView(requireContext()).apply {
            setImageDrawable(icon)
            setPadding(iconPaddingPx, iconPaddingPx, iconPaddingPx, iconPaddingPx)
            isClickable = true
            isFocusable = true
            if (backgroundTypedValue.resourceId != 0) {
                background = ContextCompat.getDrawable(requireContext(), backgroundTypedValue.resourceId)
            }
            setOnClickListener { toolbar.showOverflowMenu() }
        }

        val rootLayout = binding.rootLayout as ViewGroup
        rootLayout.addView(imageView, sizePx, sizePx)
        imageView.bringToFront()
        customOverflowIcon = imageView

        fun repositionOverImageView() {
            val toolbarLoc = IntArray(2)
            toolbar.getLocationInWindow(toolbarLoc)
            val rootLoc = IntArray(2)
            rootLayout.getLocationInWindow(rootLoc)
            imageView.translationX =
                (toolbarLoc[0] - rootLoc[0] + toolbar.width - sizePx).toFloat()
            imageView.translationY =
                (toolbarLoc[1] - rootLoc[1] + (toolbar.height - sizePx) / 2).toFloat()
        }
        toolbar.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> repositionOverImageView() }
        toolbar.post { repositionOverImageView() }
    }

    // نفس أنيميشن الدخول/الخروج بتاع صفحة الفنان بالظبط (nav_slide_*)، بدل
    // الـ Hold()/Shared Element القديمة اللي مبقتش منطقية أصلاً بعد ما بقى
    // الهيدر edge-to-edge (مفيش كارت تربيعي نقل منه/له).
    override fun onAlbumClick(albumId: Long, view: View) {
        val navOptions = NavOptions.Builder()
            .setEnterAnim(R.anim.nav_slide_in_right)
            .setExitAnim(R.anim.nav_slide_out_left)
            .setPopEnterAnim(R.anim.nav_slide_in_left)
            .setPopExitAnim(R.anim.nav_slide_out_right)
            .build()

        findNavController().navigate(
            R.id.albumDetailsFragment,
            bundleOf(EXTRA_ALBUM_ID to albumId),
            navOptions
        )
    }

    override fun onCreateMenu(menu: Menu, inflater: MenuInflater) {
        // مش محتاجين حاجة هنا: الصفحة بتدير المنيو بتاعتها بنفسها بالكامل
        // (شوف setUpCustomOverflowIcon و onViewCreated) - registersMenuProvider
        // متظبطة false فوق عشان الـ MenuHost المشترك أصلاً ميندهش على الدالة دي.
    }

    override fun onMenuItemSelected(item: MenuItem): Boolean {
        return handleSortOrderMenuItem(item)
    }

    private fun handleSortOrderMenuItem(item: MenuItem): Boolean {
        var sortOrder: String? = null
        val songs = simpleSongAdapter.dataSet
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
                addAlbumSongsToPlaylist()
                return true
            }

            R.id.action_delete_from_device -> {
                DeleteSongsDialog.create(songs).show(childFragmentManager, "DELETE_SONGS")
                return true
            }

            R.id.action_tag_editor -> {
                val intent = Intent(requireContext(), AlbumTagEditorActivity::class.java)
                intent.putExtra(AbsTagEditorActivity.EXTRA_ID, album.id)
                val options = ActivityOptions.makeSceneTransitionAnimation(
                    requireActivity(),
                    binding.image,
                    "${getString(R.string.transition_album_art)}_${album.id}"
                )
                startActivity(
                    intent, options.toBundle()
                )
                return true
            }

            R.id.action_sort_order_title -> sortOrder = SONG_A_Z
            R.id.action_sort_order_title_desc -> sortOrder = SONG_Z_A
            R.id.action_sort_order_track_list -> sortOrder = SONG_TRACK_LIST
            R.id.action_sort_order_artist_song_duration -> sortOrder = SONG_DURATION
        }
        if (sortOrder != null) {
            item.isChecked = true
            setSaveSortOrder(sortOrder)
        }
        return true
    }

    private fun setUpSortOrderMenu(sortOrder: SubMenu) {
        when (savedSortOrder) {
            SONG_A_Z -> sortOrder.findItem(R.id.action_sort_order_title).isChecked = true
            SONG_Z_A -> sortOrder.findItem(R.id.action_sort_order_title_desc).isChecked = true
            SONG_TRACK_LIST ->
                sortOrder.findItem(R.id.action_sort_order_track_list).isChecked = true

            SONG_DURATION ->
                sortOrder.findItem(R.id.action_sort_order_artist_song_duration).isChecked = true
        }
    }

    private fun setSaveSortOrder(sortOrder: String) {
        PreferenceUtil.albumDetailSongSortOrder = sortOrder
        val songs = when (sortOrder) {
            SONG_TRACK_LIST -> album.songs.sortedWith { o1, o2 ->
                o1.trackNumber.compareTo(
                    o2.trackNumber
                )
            }

            SONG_A_Z -> {
                val collator = Collator.getInstance()
                album.songs.sortedWith { o1, o2 -> collator.compare(o1.title, o2.title) }
            }

            SONG_Z_A -> {
                val collator = Collator.getInstance()
                album.songs.sortedWith { o1, o2 -> collator.compare(o2.title, o1.title) }
            }

            SONG_DURATION -> album.songs.sortedWith { o1, o2 ->
                o1.duration.compareTo(
                    o2.duration
                )
            }

            else -> throw IllegalArgumentException("invalid $sortOrder")
        }
        album = album.copy(songs = songs)
        simpleSongAdapter.swapDataSet(album.songs)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        customOverflowIcon = null
        _binding = null
    }
}
