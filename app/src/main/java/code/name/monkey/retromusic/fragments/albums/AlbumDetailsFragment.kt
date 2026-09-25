/*
 * Copyright (c) 2020 Hemanth Savarla.
 *
 * Licensed under the GNU General Public License v3
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
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.database.StandaloneDatabaseProvider
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
import org.json.JSONObject
import org.koin.android.ext.android.get
import org.koin.androidx.viewmodel.ext.android.viewModel
import org.koin.core.parameter.parametersOf
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.Collator

private const val TOOLBAR_ICON_ALPHA = 0xCC

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

    override val registersMenuProvider: Boolean = false
    private var customOverflowIcon: ImageView? = null
    private var dominantBackgroundColor: Int = Color.BLACK
    private var cachedBitmap: Bitmap? = null
    private var hasExtractedColors: Boolean = false
    private var transitionStarted: Boolean = false
    private var albumTitleBottomInScrollContent: Int = -1
    private var foregroundColor: Int = Color.WHITE
    private var secondaryForegroundColor: Int = Color.WHITE
    private var originalStatusBarLight: Boolean? = null

    // Video Player Properties
    private var exoPlayer: ExoPlayer? = null
    private var isVideoAlbum = false

    companion object {
        // اعرضها toast لحد ما تتأكد إن الميزة شغالة، بعدين خليها false
        private const val DEBUG_ANIMATED_ARTWORK = true

        // Shared cache instance for ExoPlayer to avoid locking issues
        private var simpleCache: SimpleCache? = null

        // Anonymous (no-login) Apple Music web token, shared across instances
        private var cachedAnonymousToken: String? = null
        private var cachedTokenExpiry: Long = 0L

        // albumId -> motion artwork URL (null = checked, confirmed no video)
        private val animatedArtworkCache = HashMap<Long, String?>()
    }

    private val savedSortOrder: String
        get() = PreferenceUtil.albumDetailSongSortOrder

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        postponeEnterTransition()
        _binding = FragmentAlbumDetailsBinding.bind(view)
        mainActivity.addMusicServiceEventListener(detailsViewModel)

        binding.imageShadow?.apply {
            val density = resources.displayMetrics.density
            cornerRadiusPx = 6f * density
            blurRadiusPx = 18f * density
            shadowInsetPx = 16f * density
            shadowColor = 0x3D000000
        }

        if (originalStatusBarLight == null) {
            originalStatusBarLight = (requireActivity().window.decorView.systemUiVisibility and
                android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR) != 0
        }

        AlbumDetailsCache.getColor(arguments.extraAlbumId)?.let { cachedColor ->
            dominantBackgroundColor = cachedColor
            hasExtractedColors = true
            setColors(cachedColor)
        }
        val initialColor = if (hasExtractedColors) dominantBackgroundColor else neutralFallbackColor()
        binding.rootLayout.setBackgroundColor(initialColor)

        val toolbar = binding.toolbar as TintableToolbar
        toolbar.title = null
        toolbar.contentInsetStartWithNavigation = 0
        toolbar.setTitleMarginStart(0)
        toolbar.contentInsetEndWithActions = 0
        toolbar.setPadding(toolbar.paddingLeft, toolbar.paddingTop, 0, toolbar.paddingBottom)
        toolbar.setNavigationOnClickListener { findNavController().navigateUp() }

        view.post {
            if (_binding == null) return@post
            toolbar.inflateMenu(R.menu.menu_album_detail)
            setUpSortOrderMenu(toolbar.menu.findItem(R.id.action_sort_order).subMenu!!)
            toolbar.setOnMenuItemClickListener { item -> handleSortOrderMenuItem(item) }
            setUpCustomOverflowIcon(toolbar)
        }

        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(requireActivity().window, false)

        binding.appBarLayout?.let { appBar ->
            ViewCompat.setOnApplyWindowInsetsListener(appBar) { v, insets ->
                val statusBarInsets = insets.getInsets(WindowInsetsCompat.Type.statusBars())
                v.updatePadding(top = statusBarInsets.top)
                
                binding.headerContainer.updatePadding(
                    top = statusBarInsets.top + actionBarSizePx() + (24 * resources.displayMetrics.density).toInt()
                )
                insets
            }
        }

        binding.fragmentAlbumContent.bottomSpacer.layoutParams.height = resources.displayMetrics.heightPixels / 4
        binding.fragmentAlbumContent.bottomSpacer.requestLayout()

        binding.appBarLayout?.alpha = 1f
        binding.toolbar.isClickable = true
        binding.content.isVerticalScrollBarEnabled = false

        binding.content.setOnScrollChangeListener(androidx.core.widget.NestedScrollView.OnScrollChangeListener { _, _, scrollY, _, _ ->
            val activeTitleView: View = if (isVideoAlbum && binding.videoHeaderContainer?.visibility == View.VISIBLE && binding.videoAlbumTitle != null) {
                binding.videoAlbumTitle!!
            } else {
                binding.albumTitle
            }

            if (albumTitleBottomInScrollContent <= 0) {
                val titleLocation = IntArray(2)
                activeTitleView.getLocationOnScreen(titleLocation)
                val contentLocation = IntArray(2)
                binding.content.getLocationOnScreen(contentLocation)
                val titleBottomOnScreen = titleLocation[1] + activeTitleView.height
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
                        toolbar.title = if (activeTitleView == binding.videoAlbumTitle) binding.videoAlbumTitle?.text else binding.albumTitle.text
                    }
                } else {
                    toolbar.title = null
                }
            }
        })

        setupRecyclerView()
        detailsViewModel.getAlbum().observe(viewLifecycleOwner) { album ->
            albumArtistExists = !album.albumArtist.isNullOrEmpty()
            checkAndFetchAnimatedArtwork(album)
        }

        val artistClickListener = View.OnClickListener {
            if (PreferenceUtil.multiArtistsEnabled) {
                val nameToSplit = if (albumArtistExists) album.albumArtist!! else album.artistName
                val splitNames = ArtistTagUtil.splitArtistNames(nameToSplit)
                when {
                    splitNames.size > 1 -> {
                        lifecycleScope.launch(Dispatchers.IO) {
                            val repository = get<RealRepository>()
                            val artists = splitNames.map { repository.multiArtistByName(it) }
                            withContext(Dispatchers.Main) { showArtistPickerDialog(artists) }
                        }
                    }
                    splitNames.size == 1 -> goToMultiArtistFromAlbum(splitNames[0])
                    else -> goToMultiArtistFromAlbum(album.artistName)
                }
            } else {
                if (albumArtistExists) {
                    findActivityNavController(R.id.fragment_container).navigate(
                        R.id.albumArtistDetailsFragment, bundleOf(EXTRA_ARTIST_NAME to album.albumArtist)
                    )
                } else {
                    findActivityNavController(R.id.fragment_container).navigate(
                        R.id.artistDetailsFragment, bundleOf(EXTRA_ARTIST_ID to album.artistId)
                    )
                }
            }
        }
        
        binding.albumText.setOnClickListener(artistClickListener)
        binding.videoAlbumText?.setOnClickListener(artistClickListener)

        binding.fragmentAlbumContent.playAction.setOnClickListener {
            if (::album.isInitialized) MusicPlayerRemote.openQueue(album.songs, 0, true)
        }
        binding.videoPlayAction?.setOnClickListener {
            if (::album.isInitialized) MusicPlayerRemote.openQueue(album.songs, 0, true)
        }

        binding.fragmentAlbumContent.shuffleAction.setOnClickListener {
            if (::album.isInitialized) MusicPlayerRemote.openAndShuffleQueue(album.songs, true)
        }
        binding.videoShuffleAction?.setOnClickListener {
            if (::album.isInitialized) MusicPlayerRemote.openAndShuffleQueue(album.songs, true)
        }

        binding.fragmentAlbumContent.addAction.setOnClickListener {
            if (::album.isInitialized) addAlbumSongsToPlaylist()
        }
        binding.videoAddAction?.setOnClickListener {
            if (::album.isInitialized) addAlbumSongsToPlaylist()
        }

        binding.appBarLayout?.statusBarForeground = MaterialShapeDrawable.createWithElevationOverlay(requireContext())

        view.postDelayed({ releaseEnterTransition() }, 400L)
    }

    // يتأكد إذا كان الألبوم له صورة متحركة (Motion Artwork) على Apple Music
    // ويجيبها من غير أي تسجيل دخول أو حساب مدفوع، عن طريق:
    // 1) iTunes Search API العام لمعرفة الـ ID الصحيح لنفس الألبوم
    // 2) توكن مجهول الهوية (anonymous) زي اللي بيستخدمه أي زائر عادي لموقع music.apple.com
    private fun checkAndFetchAnimatedArtwork(albumData: Album) {
        val albumId = albumData.id

        if (animatedArtworkCache.containsKey(albumId)) {
            val cachedUrl = animatedArtworkCache[albumId]
            isVideoAlbum = cachedUrl != null
            showAlbum(albumData)
            if (cachedUrl != null) setupVideoPlayer(cachedUrl)
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val videoUrl = try {
                fetchAnimatedArtworkUrl(albumData)
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
            animatedArtworkCache[albumId] = videoUrl

            withContext(Dispatchers.Main) {
                if (_binding == null) return@withContext
                isVideoAlbum = videoUrl != null
                showAlbum(albumData)
                if (videoUrl != null) setupVideoPlayer(videoUrl)
            }
        }
    }

    private fun fetchAnimatedArtworkUrl(albumData: Album): String? {
        val country = "us"
        val appleMusicId = findAppleMusicAlbumId(albumData, country)
        if (appleMusicId == null) {
            debugToast("منلقيتش الألبوم على Apple Music (iTunes search)")
            return null
        }
        val token = getAnonymousAppleMusicToken()
        if (token == null) {
            debugToast("منلقيتش توكن مجهول الهوية")
            return null
        }

        val url = URL("https://amp-api.music.apple.com/v1/catalog/$country/albums/$appleMusicId?extend=editorialVideo")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Origin", "https://music.apple.com")
            connectTimeout = 8000
            readTimeout = 8000
        }
        try {
            if (connection.responseCode != 200) {
                debugToast("amp-api رجع ${connection.responseCode}")
                return null
            }
            val response = connection.inputStream.bufferedReader().use { it.readText() }
            val data = JSONObject(response).optJSONArray("data")
            if (data == null || data.length() == 0) {
                debugToast("amp-api رجع من غير بيانات للألبوم")
                return null
            }
            val attributes = data.getJSONObject(0).optJSONObject("attributes") ?: return null
            val editorialVideo = attributes.optJSONObject("editorialVideo")
            if (editorialVideo == null) {
                debugToast("الألبوم دا معندوش Motion Artwork على Apple Music")
                return null
            }
            val motion = editorialVideo.optJSONObject("motionDetailTall")
                ?: editorialVideo.optJSONObject("motionSquareVideo1x1")
                ?: return null
            return motion.optString("video").takeIf { it.isNotBlank() }
        } finally {
            connection.disconnect()
        }
    }

    // بيدور على نفس الألبوم بالاسم + اسم الفنان عبر iTunes Search API (عام، من غير توكن ولا تسجيل دخول)
    // عشان يجيب الـ ID الصحيح بتاعه على Apple Music بدل رقم ثابت
    private fun findAppleMusicAlbumId(albumData: Album, country: String): String? {
        val artist = if (albumArtistExists) albumData.albumArtist else albumData.artistName
        val term = URLEncoder.encode("${artist ?: ""} ${albumData.title}".trim(), "UTF-8")
        val url = URL("https://itunes.apple.com/search?term=$term&entity=album&limit=1&country=$country")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8000
            readTimeout = 8000
        }
        try {
            if (connection.responseCode != 200) {
                debugToast("iTunes search رجع ${connection.responseCode}")
                return null
            }
            val response = connection.inputStream.bufferedReader().use { it.readText() }
            val results = JSONObject(response).optJSONArray("results")
            if (results == null || results.length() == 0) {
                debugToast("iTunes search مفيهوش نتايج لـ ${albumData.title}")
                return null
            }
            val collectionId = results.getJSONObject(0).optLong("collectionId", -1)
            return if (collectionId > 0) collectionId.toString() else null
        } finally {
            connection.disconnect()
        }
    }

    // توكن مجهول الهوية، نفس اللي بيستخدمه أي زائر عادي لموقع Apple Music من غير حساب أو اشتراك.
    // الطريقة القديمة (meta tag اسمه desktop-music-app/config/environment في music.apple.com)
    // بقت باطلة - Apple نقلت الواجهة لدومين beta.music.apple.com كـ SPA بالكامل. الصفحة دي
    // بتحمّل عادةً أكتر من ملف JS بنمط /assets/index...js (نسخة ES module حديثة + نسخة
    // "legacy" للمتصفحات القديمة)، والتوكن ممكن يكون في أي واحد فيهم - مش بالضرورة الـ legacy
    // بس زي ما كان مفترض قبل كده. فبندور في كل ملف منهم لحد ما نلاقي JWT حقيقي.
    private fun getAnonymousAppleMusicToken(): String? {
        cachedAnonymousToken?.let { token ->
            if (System.currentTimeMillis() < cachedTokenExpiry) return token
        }

        val mainHtml = httpGetText("https://beta.music.apple.com")
        if (mainHtml == null) {
            debugToast("فشل تحميل beta.music.apple.com")
            return null
        }

        val jsPaths =
            Regex("""/assets/index[^"'\s]*\.js""")
                .findAll(mainHtml)
                .map { it.value }
                .distinct()
                .toList()

        if (jsPaths.isEmpty()) {
            debugToast("مفيش ولا ملف /assets/index*.js في الصفحة (طولها ${mainHtml.length} حرف)")
            return null
        }

        for (path in jsPaths) {
            val jsContent = httpGetText("https://beta.music.apple.com$path") ?: continue
            val token = extractToken(jsContent)
            if (token != null) {
                cachedAnonymousToken = token
                cachedTokenExpiry = decodeJwtExpiry(token) ?: (System.currentTimeMillis() + 15 * 60 * 1000)
                return token
            }
        }

        debugToast("دورنا في ${jsPaths.size} ملف (${jsPaths.joinToString()}) ومنلقيناش توكن صحيح")
        return null
    }

    // بيدور على كل الأنماط اللي شكلها JWT (بتبدأ بـ eyJh) في النص، وبيتأكد إن كل واحد فيهم
    // فعلاً JWT حقيقي (مش أي base64 string اتفق إنه يبدأ بنفس الحروف زي توكنات التتبع/الأناليتكس)
    // عن طريق فك أول جزء (header) بتاعه والتأكد إنه JSON فيه مفتاح "alg" - ده بالظبط اللي بيميز
    // JWT عن أي base64 عادي.
    private fun extractToken(text: String): String? =
        Regex("""eyJh[A-Za-z0-9_\-.]{40,}""")
            .findAll(text)
            .map { it.value }
            .firstOrNull { isLikelyJwt(it) }

    private fun isLikelyJwt(token: String): Boolean =
        try {
            val header = token.substringBefore(".")
            val decoded =
                String(
                    android.util.Base64.decode(
                        header,
                        android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING,
                    ),
                )
            JSONObject(decoded).has("alg")
        } catch (e: Exception) {
            false
        }


    private fun httpGetText(urlStr: String): String? {
        val connection = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15"
            )
            connectTimeout = 8000
            readTimeout = 8000
        }
        return try {
            if (connection.responseCode != 200) {
                debugToast("HTTP ${connection.responseCode} من $urlStr")
                null
            } else {
                connection.inputStream.bufferedReader().use { it.readText() }
            }
        } catch (e: Exception) {
            debugToast("إكسبشن: ${e.message}")
            null
        } finally {
            connection.disconnect()
        }
    }

    // Toast بسيط بيوضح بالظبط فين بقى الفشل، عشان تقدر تشخّص المشكلة من غير كمبيوتر أو logcat.
    // خليها DEBUG_ANIMATED_ARTWORK = false لما تتأكد إن كل حاجة شغالة تمام.
    private fun debugToast(message: String) {
        if (!DEBUG_ANIMATED_ARTWORK) return
        activity?.runOnUiThread {
            if (isAdded && _binding != null) {
                android.widget.Toast.makeText(requireContext(), "🎬 $message", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun decodeJwtExpiry(token: String): Long? {
        return try {
            val payload = token.split(".").getOrNull(1) ?: return null
            val decoded = String(
                android.util.Base64.decode(
                    payload,
                    android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
                )
            )
            val exp = JSONObject(decoded).optLong("exp", -1)
            if (exp <= 0) null else exp * 1000L - 60_000L
        } catch (e: Exception) {
            null
        }
    }

    private fun getCacheDataSourceFactory(): CacheDataSource.Factory {
        if (simpleCache == null) {
            val cacheDir = File(requireContext().cacheDir, "animated_covers_cache")
            val databaseProvider = StandaloneDatabaseProvider(requireContext())
            simpleCache = SimpleCache(cacheDir, NoOpCacheEvictor(), databaseProvider)
        }
        return CacheDataSource.Factory()
            .setCache(simpleCache!!)
            .setUpstreamDataSourceFactory(DefaultHttpDataSource.Factory())
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    private fun setupVideoPlayer(videoUrl: String) {
        if (_binding == null || binding.videoPlayerView == null) return

        exoPlayer = ExoPlayer.Builder(requireContext()).build().apply {
            repeatMode = Player.REPEAT_MODE_ONE // تكرار لا نهائي
            volume = 0f // كتم الصوت
        }

        binding.videoPlayerView?.player = exoPlayer

        val mediaItem = MediaItem.fromUri(videoUrl)
        val mediaSource = androidx.media3.exoplayer.hls.HlsMediaSource.Factory(getCacheDataSourceFactory())
            .createMediaSource(mediaItem)

        exoPlayer?.setMediaSource(mediaSource)
        exoPlayer?.prepare()
        exoPlayer?.playWhenReady = true

        // انتقال سلس: عند جاهزية الفيديو، نقوم بإخفاء الصورة الأساسية تدريجياً
        exoPlayer?.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    binding.videoPlayerView?.animate()?.alpha(1f)?.setDuration(500)?.start()
                }
            }
        })
    }

    override fun onResume() {
        super.onResume()
        exoPlayer?.playWhenReady = true
    }

    override fun onPause() {
        super.onPause()
        exoPlayer?.playWhenReady = false
    }

    private fun releaseEnterTransition() {
        if (transitionStarted) return
        transitionStarted = true
        startPostponedEnterTransition()
    }

    private fun showArtistPickerDialog(artists: List<Artist>) {
        val adapter = ArtistPickerAdapter(requireActivity() as AppCompatActivity, artists)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.action_go_to_artist)
            .setAdapter(adapter) { _, which ->
                findActivityNavController(R.id.fragment_container).navigate(
                    R.id.multiArtistDetailsFragment, bundleOf(EXTRA_ARTIST_NAME to artists[which].name)
                )
            }
            .show()
    }

    private fun goToMultiArtistFromAlbum(artistName: String) {
        findActivityNavController(R.id.fragment_container)
            .navigate(R.id.multiArtistDetailsFragment, bundleOf(EXTRA_ARTIST_NAME to artistName))
    }

    private fun addAlbumSongsToPlaylist() {
        lifecycleScope.launch(Dispatchers.IO) {
            val playlists = get<RealRepository>().fetchPlaylists()
            withContext(Dispatchers.Main) {
                if (_binding == null) return@withContext
                AddToPlaylistDialog.create(playlists, album.songs).show(childFragmentManager, "ADD_PLAYLIST")
            }
        }
    }

    private fun setupRecyclerView() {
        simpleSongAdapter = SimpleSongAdapter(
            requireActivity() as AppCompatActivity, ArrayList(), R.layout.item_song_album_details
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
        if (!::simpleSongAdapter.isInitialized) {
            setupRecyclerView()
        }
        this.album = album
        
        val songCountText = if (album.songCount == 1) "1 song" else "${album.songCount} songs"
        val albumYear = MusicUtil.getYearString(album.year)
        val metaTextString = if (albumYear == "-") songCountText else "$albumYear • $songCountText"
        val artistString = if (albumArtistExists) album.albumArtist else album.artistName

        // تبديل الواجهة حسب وجود الفيديو
        if (isVideoAlbum && binding.videoHeaderContainer != null) {
            binding.headerContainer.visibility = View.GONE
            binding.fragmentAlbumContent.originalButtonsContainer?.visibility = View.GONE
            binding.videoHeaderContainer?.visibility = View.VISIBLE

            binding.videoAlbumTitle?.text = album.title
            binding.videoAlbumText?.text = artistString
            binding.videoAlbumMetaText?.text = metaTextString
        } else {
            binding.videoHeaderContainer?.visibility = View.GONE
            binding.headerContainer.visibility = View.VISIBLE
            binding.fragmentAlbumContent.originalButtonsContainer?.visibility = View.VISIBLE

            binding.albumTitle.text = album.title
            binding.albumText.text = artistString
            binding.albumMetaText.text = metaTextString
        }

        binding.fragmentAlbumContent.songTitle.text = songCountText
        binding.image.transitionName = "${getString(R.string.transition_album_art)}_${album.id}"
        binding.videoImage?.transitionName = "${getString(R.string.transition_album_art)}_${album.id}"

        // يتم تحميل الصورة في كلا الواجهتين (لتكون فريم مبدئي للفيديو)
        loadAlbumCover(album)
        simpleSongAdapter.swapDataSet(album.songs)
        
        if (albumArtistExists) {
            detailsViewModel.getAlbumArtist(album.albumArtist.toString()).observe(viewLifecycleOwner) { loadMoreAlbums(it) }
        } else {
            detailsViewModel.getArtist(album.artistId).observe(viewLifecycleOwner) { loadMoreAlbums(it) }
        }
    }

    private fun moreAlbums(albums: List<Album>) {
        binding.fragmentAlbumContent.moreTitle.show()
        binding.fragmentAlbumContent.moreRecyclerView.show()
        binding.fragmentAlbumContent.moreTitle.text = String.format(getString(R.string.label_more_from), album.artistName)

        val albumAdapter = HorizontalAlbumAdapter(requireActivity() as AppCompatActivity, albums, this)
        moreAlbumAdapter = albumAdapter
        binding.fragmentAlbumContent.moreRecyclerView.layoutManager = GridLayoutManager(requireContext(), 1, GridLayoutManager.HORIZONTAL, false)
        binding.fragmentAlbumContent.moreRecyclerView.adapter = albumAdapter
        if (hasExtractedColors) albumAdapter.setDynamicTextColors(foregroundColor, secondaryForegroundColor)
    }

    private fun loadMoreAlbums(artist: Artist) {
        detailsViewModel.getMoreAlbums(artist).observe(viewLifecycleOwner) { moreAlbums(it) }
    }

    private fun loadAlbumCover(album: Album) {
        if (cachedBitmap != null && hasExtractedColors) {
            binding.image.setImageBitmap(cachedBitmap)
            binding.videoImage?.setImageBitmap(cachedBitmap)
            setColors(dominantBackgroundColor)
            view?.post { releaseEnterTransition() }
            return
        }

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
                override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                    cachedBitmap = resource
                    extractColorAndApplyBackground(albumId, resource)
                }

                override fun onLoadFailed(errorDrawable: Drawable?) {
                    if (_binding != null && errorDrawable != null) {
                        binding.image.setImageDrawable(errorDrawable)
                        binding.videoImage?.setImageDrawable(errorDrawable)
                    }
                    releaseEnterTransition()
                }

                override fun onLoadCleared(placeholder: Drawable?) {
                    if (_binding != null) {
                        binding.image.setImageDrawable(placeholder)
                        binding.videoImage?.setImageDrawable(placeholder)
                    }
                }
            })
    }

    private fun extractColorAndApplyBackground(albumId: Long, bitmap: Bitmap) {
        lifecycleScope.launch(Dispatchers.Default) {
            val mostFrequentColor = AlbumPaletteEngine.findMostFrequentColor(bitmap)
            withContext(Dispatchers.Main) {
                hasExtractedColors = true
                dominantBackgroundColor = mostFrequentColor
                AlbumDetailsCache.putColor(albumId, mostFrequentColor)

                if (_binding != null) {
                    binding.image.setImageBitmap(bitmap)
                    binding.videoImage?.setImageBitmap(bitmap)
                    setColors(mostFrequentColor)
                }
                releaseEnterTransition()
            }
        }
    }

    private fun setColors(backgroundColor: Int) {
        if (_binding == null) return
        binding.rootLayout.setBackgroundColor(backgroundColor)
        binding.appBarLayout?.setBackgroundColor(ColorUtils.setAlphaComponent(backgroundColor, 0))
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
        binding.albumMetaText.setTextColor(secondaryFgColor)

        binding.videoAlbumTitle?.setTextColor(fgColor)
        binding.videoAlbumText?.setTextColor(secondaryFgColor)
        binding.videoAlbumMetaText?.setTextColor(secondaryFgColor)

        if (isVideoAlbum && binding.videoHeaderContainer?.visibility == View.VISIBLE) {
            val gradient = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.TRANSPARENT, ColorUtils.setAlphaComponent(backgroundColor, 150), backgroundColor)
            )
            binding.videoGradient?.background = gradient
        }

        val toolbar = binding.toolbar
        val iconColor = ColorUtils.setAlphaComponent(fgColor, TOOLBAR_ICON_ALPHA)
        if (toolbar is TintableToolbar) {
            toolbar.navigationIcon?.let { DrawableCompat.setTint(it, iconColor) }
            customOverflowIcon?.drawable?.let { DrawableCompat.setTint(it.mutate(), iconColor) }
            toolbar.setTitleTextColor(fgColor)
        }

        binding.fragmentAlbumContent.songTitle.setTextColor(fgColor)
        binding.fragmentAlbumContent.moreTitle.setTextColor(fgColor)
        applyStatusBarAppearance(isLightBackground)

        binding.fragmentAlbumContent.shuffleActionGlass.setBackdropColor(backgroundColor)
        binding.fragmentAlbumContent.addActionGlass.setBackdropColor(backgroundColor)
        binding.fragmentAlbumContent.shuffleAction.iconTint = ColorStateList.valueOf(fgColor)
        binding.fragmentAlbumContent.addAction.iconTint = ColorStateList.valueOf(fgColor)
        
        binding.videoShuffleActionGlass?.setBackdropColor(backgroundColor)
        binding.videoAddActionGlass?.setBackdropColor(backgroundColor)
        binding.videoShuffleAction?.iconTint = ColorStateList.valueOf(fgColor)
        binding.videoAddAction?.iconTint = ColorStateList.valueOf(fgColor)

        val playButtonBackground = if (isLightBackground) Color.BLACK else Color.WHITE
        val playButtonForeground = if (isLightBackground) Color.WHITE else Color.BLACK
        
        binding.fragmentAlbumContent.playAction.apply {
            backgroundTintList = ColorStateList.valueOf(playButtonBackground)
            setTextColor(playButtonForeground)
            iconTint = ColorStateList.valueOf(playButtonForeground)
        }
        
        binding.videoPlayAction?.apply {
            backgroundTintList = ColorStateList.valueOf(playButtonBackground)
            setTextColor(playButtonForeground)
            iconTint = ColorStateList.valueOf(playButtonForeground)
        }

        if (::simpleSongAdapter.isInitialized) simpleSongAdapter.setDynamicTextColors(fgColor, secondaryFgColor)
        moreAlbumAdapter?.setDynamicTextColors(fgColor, secondaryFgColor)
    }

    private fun applyStatusBarAppearance(isLightBackground: Boolean) {
        activity?.window?.let { window ->
            androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = isLightBackground
        }
    }

    private fun neutralFallbackColor(): Int {
        val typedValue = TypedValue()
        val resolved = requireContext().theme.resolveAttribute(com.google.android.material.R.attr.colorSurface, typedValue, true)
        return if (resolved) typedValue.data else Color.BLACK
    }

    private fun actionBarSizePx(): Int {
        val typedValue = TypedValue()
        val resolved = requireContext().theme.resolveAttribute(androidx.appcompat.R.attr.actionBarSize, typedValue, true)
        return if (resolved) TypedValue.complexToDimensionPixelSize(typedValue.data, resources.displayMetrics) else (56 * resources.displayMetrics.density).toInt()
    }

    private fun setUpCustomOverflowIcon(toolbar: TintableToolbar) {
        val icon = toolbar.overflowIcon?.mutate() ?: return
        toolbar.overflowIcon = ColorDrawable(Color.TRANSPARENT)

        val sizePx = (48 * resources.displayMetrics.density).toInt()
        val iconPaddingPx = (12 * resources.displayMetrics.density).toInt()
        val backgroundTypedValue = TypedValue()
        requireContext().theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, backgroundTypedValue, true)

        val imageView = ImageView(requireContext()).apply {
            setImageDrawable(icon)
            setPadding(iconPaddingPx, iconPaddingPx, iconPaddingPx, iconPaddingPx)
            isClickable = true
            isFocusable = true
            if (backgroundTypedValue.resourceId != 0) background = ContextCompat.getDrawable(requireContext(), backgroundTypedValue.resourceId)
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
            imageView.translationX = (toolbarLoc[0] - rootLoc[0] + toolbar.width - sizePx).toFloat()
            imageView.translationY = (toolbarLoc[1] - rootLoc[1] + (toolbar.height - sizePx) / 2).toFloat()
        }
        toolbar.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> repositionOverImageView() }
        toolbar.post { repositionOverImageView() }
    }

    override fun onAlbumClick(albumId: Long, view: View) {
        val navOptions = NavOptions.Builder()
            .setEnterAnim(R.anim.nav_slide_in_right)
            .setExitAnim(R.anim.nav_slide_out_left)
            .setPopEnterAnim(R.anim.nav_slide_in_left)
            .setPopExitAnim(R.anim.nav_slide_out_right)
            .build()
        findNavController().navigate(R.id.albumDetailsFragment, bundleOf(EXTRA_ALBUM_ID to albumId), navOptions)
    }

    override fun onCreateMenu(menu: Menu, inflater: MenuInflater) {}

    override fun onMenuItemSelected(item: MenuItem): Boolean {
        return handleSortOrderMenuItem(item)
    }

    private fun handleSortOrderMenuItem(item: MenuItem): Boolean {
        var sortOrder: String? = null
        val songs = simpleSongAdapter.dataSet
        when (item.itemId) {
            R.id.action_play_next -> { MusicPlayerRemote.playNext(songs); return true }
            R.id.action_add_to_current_playing -> { MusicPlayerRemote.enqueue(songs); return true }
            R.id.action_add_to_playlist -> { addAlbumSongsToPlaylist(); return true }
            R.id.action_delete_from_device -> { DeleteSongsDialog.create(songs).show(childFragmentManager, "DELETE_SONGS"); return true }
            R.id.action_tag_editor -> {
                val intent = Intent(requireContext(), AlbumTagEditorActivity::class.java)
                intent.putExtra(AbsTagEditorActivity.EXTRA_ID, album.id)
                val options = ActivityOptions.makeSceneTransitionAnimation(requireActivity(), binding.image, "${getString(R.string.transition_album_art)}_${album.id}")
                startActivity(intent, options.toBundle())
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
            SONG_TRACK_LIST -> sortOrder.findItem(R.id.action_sort_order_track_list).isChecked = true
            SONG_DURATION -> sortOrder.findItem(R.id.action_sort_order_artist_song_duration).isChecked = true
        }
    }

    private fun setSaveSortOrder(sortOrder: String) {
        PreferenceUtil.albumDetailSongSortOrder = sortOrder
        val songs = when (sortOrder) {
            SONG_TRACK_LIST -> album.songs.sortedWith { o1, o2 -> o1.trackNumber.compareTo(o2.trackNumber) }
            SONG_A_Z -> { val collator = Collator.getInstance(); album.songs.sortedWith { o1, o2 -> collator.compare(o1.title, o2.title) } }
            SONG_Z_A -> { val collator = Collator.getInstance(); album.songs.sortedWith { o1, o2 -> collator.compare(o2.title, o1.title) } }
            SONG_DURATION -> album.songs.sortedWith { o1, o2 -> o1.duration.compareTo(o2.duration) }
            else -> throw IllegalArgumentException("invalid $sortOrder")
        }
        album = album.copy(songs = songs)
        simpleSongAdapter.swapDataSet(album.songs)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        exoPlayer?.release()
        exoPlayer = null
        customOverflowIcon = null
        transitionStarted = false
        originalStatusBarLight?.let { wasLight ->
            activity?.window?.let { window ->
                androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = wasLight
            }
        }
        _binding = null
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceActivity?.removeMusicServiceEventListener(detailsViewModel)
    }
}
