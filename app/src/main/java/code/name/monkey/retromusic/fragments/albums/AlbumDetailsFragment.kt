/*
 * Copyright (c) 2020 Hemanth Savarla.
 *
 * Licensed under the GNU General Public License v3
 */
package code.name.monkey.retromusic.fragments.albums

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.LruCache
import android.util.TypedValue
import android.view.*
import android.view.animation.Animation
import android.view.animation.AnimationUtils
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
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.VideoSize
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.offline.HlsDownloader
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
import code.name.monkey.retromusic.model.Song
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
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.Collator

private const val TOOLBAR_ICON_ALPHA = 0xCC

// ارتفاع تدرج اللون فوق الفيديو كنسبة من ارتفاع إطار الفيديو (كل ما تكبر الرقم يغطي الفيديو أكتر)
private const val VIDEO_GRADIENT_HEIGHT_FRACTION = 0.5f

// عدد صفوف الأغاني اللي بتتبند فورًا وقت فتح الصفحة (الباقي بيتبند بعد ما الأنيميشن يخلص)
private const val VISIBLE_SONGS_IMMEDIATE = 4

// لو أنيميشن الدخول مخلصش (أو مفيش أنيميشن) نعتبر الصفحة استقرت بعد المدة دي من بداية الانتقال
private const val ENTER_SETTLE_FALLBACK_MS = 700L

// نتيجة فحص الـ Motion Artwork لألبوم واحد، بتتخزن بشكل دائم:
// url         = رابط الفيديو (null = اتفحص وتأكدنا إن معندوش فيديو)
// ratio       = نسبة ارتفاع/عرض إطار الفيديو المقاسة من الفيديو نفسه
// posterColor = لون الخلفية المستخرج من أول فريم محفوظ من الفيديو (مش من الغلاف الأصلي)
// ready       = الفيديو اتنزل كامل على الجهاز وبيشتغل أوفلاين
// checkedAt   = وقت الفحص (بنعيد فحص الألبومات اللي معندهاش فيديو بعد فترة)
internal data class AnimatedEntry(
    val url: String?,
    val ratio: Float? = null,
    val posterColor: Int? = null,
    val ready: Boolean = false,
    val checkedAt: Long = 0L,
)

private sealed class FetchResult {
    data class Found(val url: String) : FetchResult()
    object NoVideo : FetchResult()
    object Failed : FetchResult() // مفيش نت / خطأ مؤقت: مبنخزنش حاجة عشان نجرب تاني
}

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
    // الصفحة "استقرت" لما أنيميشن الدخول يخلص. الشغل التقيل (تشغيل الفيديو، ربط باقي الأغاني،
    // ألبومات الفنان) بيتأجل لحد كده عشان مايزاحمش الأنيميشن زي صفحة الفنان.
    private var enterSettled = false
    private val settledCallbacks = mutableListOf<() -> Unit>()
    private var currentVideoUrl: String? = null
    private var videoVisibleOnScreen = true
    private var moreAlbumsRequested = false
    private var statusBarRegistered = false
    private var lastStatusBarLight: Boolean? = null
    private var navEntry: NavBackStackEntry? = null

    // Video Player Properties
    private var exoPlayer: ExoPlayer? = null
    private var isVideoAlbum = false

    // مكان السكرول اللي كنت واقف عليه قبل ما تفتح ألبوم تاني (بيتحفظ في onDestroyView)
    private var savedScrollY = 0

    // true = الصفحة اتفتحت بأول فريم محفوظ من الفيديو (من غير تحميل الغلاف الأصلي خالص)
    private var posterShown = false

    // نسبة ارتفاع/عرض إطار الفيديو الحالية (اتقاست من الفيديو نفسه)
    private var currentFrameRatio: Float? = null

    companion object {
        // خليها false في الإصدار النهائي؛ لو حبيت تشوف رسائل التشخيص وقت التطوير رجّعها true
        private const val DEBUG_ANIMATED_ARTWORK = false

        // Shared cache instance for ExoPlayer to avoid locking issues
        private var simpleCache: SimpleCache? = null

        // حالة شريط الحالة (ستاتس بار) الأصلية، مشتركة بين كل نسخ صفحة الألبوم. بنحفظها مرة
        // واحدة لما أول صفحة ألبوم تفتح، ومبنرجّعها غير لما آخر صفحة ألبوم تتقفل. كده فتح ألبوم
        // من جوه ألبوم (أو العكس) مبيخليش اللون المتغير يتحفظ على إنه "الأصلي" ويفضل معلق.
        private var baseStatusBarLight: Boolean? = null
        private var albumPagesAlive = 0

        // آخر صور أول فريم (poster) اتفكت من الملف، في الذاكرة، عشان الفتح التاني لنفس الألبوم
        // مايفكش الصورة من القرص على الـ Main Thread تاني
        private val posterMemoryCache = LruCache<Long, Bitmap>(2)

        // آخر غلاف ألبوم اتفتحت صفحته (سلوت واحد)، زي كاش صفحة الفنان: لو رجعت لنفس الألبوم
        // قبل ما تفتح غيره، الغلاف بيظهر فورًا من غير تحميل تاني
        private var lastVisitedCoverAlbumId: Long = -1L
        private var lastVisitedCoverBitmap: Bitmap? = null

        // Anonymous (no-login) Apple Music web token, shared across instances
        private var cachedAnonymousToken: String? = null
        private var cachedTokenExpiry: Long = 0L

        private const val ANIMATED_ARTWORK_PREFS = "animated_artwork_prefs"
        private const val ANIMATED_ARTWORK_PREFS_KEY = "cache_json_v2"

        // نسخة في الذاكرة من الكاش المحفوظ على القرص، بتتحمل مرة واحدة بس لكل عملية تشغيل
        // للتطبيق، وبعدين بتتحدث في الذاكرة والقرص مع كل تغيير. albumId -> AnimatedEntry
        // (ConcurrentHashMap لأن التنزيل في الخلفية بيعدّل عليها من thread تاني)
        private var animatedArtworkCache: ConcurrentHashMap<Long, AnimatedEntry>? = null

        // الألبومات اللي اتأكدنا إنها معندهاش فيديو بنعيد فحصها بعد الفترة دي
        private const val NO_VIDEO_RECHECK_MS = 7L * 24 * 60 * 60 * 1000

        private val downloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val downloadsInFlight: MutableSet<Long> = java.util.Collections.synchronizedSet(HashSet())

        @Synchronized
        private fun loadAnimatedArtworkCache(context: Context): ConcurrentHashMap<Long, AnimatedEntry> {
            animatedArtworkCache?.let { return it }
            val prefs = context.applicationContext
                .getSharedPreferences(ANIMATED_ARTWORK_PREFS, Context.MODE_PRIVATE)
            val map = ConcurrentHashMap<Long, AnimatedEntry>()
            prefs.getString(ANIMATED_ARTWORK_PREFS_KEY, null)?.let { json ->
                try {
                    val obj = JSONObject(json)
                    obj.keys().forEach { key ->
                        val albumId = key.toLongOrNull() ?: return@forEach
                        map[albumId] = when (val v = if (obj.isNull(key)) null else obj.get(key)) {
                            null -> AnimatedEntry(null) // صيغة قديمة: checkedAt=0 يعني هيتفحص تاني
                            is String -> AnimatedEntry(v) // صيغة قديمة: رابط بس
                            is JSONObject -> AnimatedEntry(
                                url = v.optString("url").takeIf { it.isNotBlank() },
                                ratio = if (v.has("ratio")) v.optDouble("ratio").toFloat() else null,
                                posterColor = if (v.has("pcolor")) v.optInt("pcolor") else null,
                                ready = v.optBoolean("ready", false),
                                checkedAt = v.optLong("checkedAt", 0L),
                            )
                            else -> AnimatedEntry(null)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            animatedArtworkCache = map
            return map
        }

        @Synchronized
        private fun persistAnimatedArtworkCache(context: Context) {
            val map = animatedArtworkCache ?: return
            val obj = JSONObject()
            map.forEach { (albumId, entry) ->
                val e = JSONObject()
                entry.url?.let { e.put("url", it) }
                entry.ratio?.let { e.put("ratio", it.toDouble()) }
                entry.posterColor?.let { e.put("pcolor", it) }
                if (entry.ready) e.put("ready", true)
                if (entry.checkedAt > 0) e.put("checkedAt", entry.checkedAt)
                obj.put(albumId.toString(), e)
            }
            context.applicationContext
                .getSharedPreferences(ANIMATED_ARTWORK_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(ANIMATED_ARTWORK_PREFS_KEY, obj.toString())
                .apply()
        }

        // أول فريم من الفيديو متخزن كصورة دائمة (في filesDir مش cacheDir عشان النظام ميمسحهاش)
        private fun posterFile(context: Context, albumId: Long): File =
            File(File(context.applicationContext.filesDir, "animated_posters"), "$albumId.jpg")

        // كاش ExoPlayer بقى في filesDir (مش cacheDir) عشان "مسح الكاش" من إعدادات النظام
        // ميمسحش الفيديوهات المتنزلة، ولازم يكون واحد بس لكل التطبيق.
        @Synchronized
        private fun cacheDataSourceFactory(context: Context): CacheDataSource.Factory {
            if (simpleCache == null) {
                val app = context.applicationContext
                val cacheDir = File(app.filesDir, "animated_covers_cache_v2")
                simpleCache = SimpleCache(cacheDir, NoOpCacheEvictor(), StandaloneDatabaseProvider(app))
            }
            return CacheDataSource.Factory()
                .setCache(simpleCache!!)
                .setUpstreamDataSourceFactory(DefaultHttpDataSource.Factory())
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        }

        private fun fetchText(url: String): String? {
            val c = URL(url).openConnection() as HttpURLConnection
            return try {
                c.connectTimeout = 8000
                c.readTimeout = 8000
                if (c.responseCode != 200) null else c.inputStream.bufferedReader().use { it.readText() }
            } finally {
                c.disconnect()
            }
        }

        // الرابط اللي بيرجع من Apple هو playlist رئيسي فيه كذا جودة، وExoPlayer بيبدّل بينهم حسب
        // النت، فمرة يتخزن جودة ومرة تانية — وأوفلاين بيطلب جودة مش متخزنة. عشان كده بنختار جودة
        // واحدة (H.264 / SDR / أقل عرض >= عرض الشاشة) ونشغّل وننزّل playlist بتاعها هي بس.
        private fun resolveBestVariantUrl(masterUrl: String, targetWidthPx: Int): String {
            try {
                val text = fetchText(masterUrl) ?: return masterUrl
                class V(val bw: Int, val w: Int, val avc: Boolean, val sdr: Boolean, val uri: String)
                val lines = text.lines()
                val variants = mutableListOf<V>()
                var i = 0
                while (i < lines.size) {
                    val l = lines[i].trim()
                    if (l.startsWith("#EXT-X-STREAM-INF:")) {
                        val attrs = l.substringAfter(':')
                        val bw = Regex("""(?:^|,)BANDWIDTH=(\d+)""").find(attrs)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                        val w = Regex("""RESOLUTION=(\d+)x\d+""").find(attrs)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                        val codecs = Regex("CODECS=\"([^\"]*)\"").find(attrs)?.groupValues?.get(1) ?: ""
                        val range = Regex("""VIDEO-RANGE=(\w+)""").find(attrs)?.groupValues?.get(1) ?: "SDR"
                        var j = i + 1
                        while (j < lines.size && (lines[j].isBlank() || lines[j].startsWith("#"))) j++
                        if (j < lines.size) {
                            variants += V(
                                bw, w,
                                codecs.isEmpty() || codecs.contains("avc1"),
                                range.equals("SDR", true),
                                URL(URL(masterUrl), lines[j].trim()).toString(),
                            )
                        }
                        i = j
                    }
                    i++
                }
                if (variants.isEmpty()) return masterUrl
                val pool = variants.filter { it.avc && it.sdr }
                    .ifEmpty { variants.filter { it.sdr } }
                    .ifEmpty { variants }
                val enough = pool.filter { it.w >= targetWidthPx }
                val best = if (enough.isNotEmpty()) {
                    enough.minWithOrNull(compareBy<V>({ it.w }, { it.bw }))
                } else {
                    pool.maxWithOrNull(compareBy<V>({ it.w }, { it.bw }))
                }
                return best?.uri ?: masterUrl
            } catch (e: Exception) {
                return masterUrl
            }
        }

        // بيقفل الفيديو كامل (playlist + كل الـ segments) جوه الكاش الدائم، في الخلفية وعلى مستوى
        // التطبيق كله (مش مربوط بالصفحة) فمبيتوقفش لو خرجت من الألبوم قبل ما يخلص.
        private fun ensureVideoDownloaded(context: Context, albumId: Long, url: String) {
            val app = context.applicationContext
            if (!downloadsInFlight.add(albumId)) return
            downloadScope.launch {
                try {
                    val variantUrl = resolveBestVariantUrl(url, app.resources.displayMetrics.widthPixels)
                    HlsDownloader(MediaItem.fromUri(variantUrl), cacheDataSourceFactory(app)).download(null)
                    val cache = loadAnimatedArtworkCache(app)
                    cache[albumId]?.let { e ->
                        if (e.url != null) {
                            cache[albumId] = e.copy(url = variantUrl, ready = true)
                            persistAnimatedArtworkCache(app)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace() // غالبًا مفيش نت؛ هنجرب تاني في المرة الجاية
                } finally {
                    downloadsInFlight.remove(albumId)
                }
            }
        }
    }

    private val savedSortOrder: String
        get() = PreferenceUtil.albumDetailSongSortOrder

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        postponeEnterTransition()
        enterSettled = false
        settledCallbacks.clear()
        videoVisibleOnScreen = true
        moreAlbumsRequested = false
        _binding = FragmentAlbumDetailsBinding.bind(view)
        mainActivity.addMusicServiceEventListener(detailsViewModel)

        binding.imageShadow?.apply {
            val density = resources.displayMetrics.density
            cornerRadiusPx = 6f * density
            blurRadiusPx = 18f * density
            shadowInsetPx = 16f * density
            shadowColor = 0x3D000000
        }

        registerStatusBar()
        navEntry = runCatching { findNavController().currentBackStackEntry }.getOrNull()

        // ارتفاع التدرج فوق الفيديو بيتحسب من ارتفاع إطار الفيديو الفعلي
        binding.videoFrame?.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateVideoGradientHeight()
        }

        // ألبوم ليه فيديو متخزن + أول فريم محفوظ: نفتح الصفحة بالصورة دي مباشرة (بدل الغلاف
        // الأصلي) وبنفس نسبة الفيديو ولون الخلفية المحفوظ، فمفيش أي تبديل صور وقت الفتح.
        applyCachedVideoHeaderIfPossible()

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
            // الأيقونة اتعملت دلوقتي بس، فنطبق عليها اللون الحالي (وإلا هتفضل بيضاء بعد الرجوع)
            reapplyColorsIfReady()
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
            // الفيديو مش ظاهر لما نسكرول بعيد عنه، فنوقفه عشان نوفر المعالجة والبطارية
            if (isVideoAlbum) {
                val frameHeight = binding.videoFrame?.height ?: 0
                val visibleNow = frameHeight <= 0 || scrollY < frameHeight
                if (visibleNow != videoVisibleOnScreen) {
                    videoVisibleOnScreen = visibleNow
                    exoPlayer?.playWhenReady = visibleNow && isResumed
                }
            }

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
        // الأدابتر اتعمل دلوقتي، فنعيد تطبيق الألوان عشان الأغاني تاخد اللون الصح
        reapplyColorsIfReady()
        restoreScrollPosition()
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
        val app = requireContext().applicationContext
        val cache = loadAnimatedArtworkCache(app)
        val entry = cache[albumId]

        // "معندوش فيديو" بنصدّقها لمدة محدودة بس (وأي نتيجة قديمة/فاشلة بتتفحص تاني)
        val noVideoStillValid = entry != null && entry.url == null &&
            System.currentTimeMillis() - entry.checkedAt < NO_VIDEO_RECHECK_MS

        if (entry != null && (entry.url != null || noVideoStillValid)) {
            val cachedUrl = entry.url
            isVideoAlbum = cachedUrl != null
            showAlbum(albumData)
            if (cachedUrl != null) {
                runWhenEnterSettled {
                    setupVideoPlayer(cachedUrl, albumId)
                    if (!entry.ready) ensureVideoDownloaded(app, albumId, cachedUrl)
                }
            }
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val result = try {
                fetchAnimatedArtworkUrl(albumData)
            } catch (e: Exception) {
                e.printStackTrace()
                FetchResult.Failed
            }
            val videoUrl = (result as? FetchResult.Found)?.url
            when (result) {
                is FetchResult.Found -> {
                    cache[albumId] = AnimatedEntry(result.url, checkedAt = System.currentTimeMillis())
                    persistAnimatedArtworkCache(app)
                    ensureVideoDownloaded(app, albumId, result.url)
                }
                FetchResult.NoVideo -> {
                    cache[albumId] = AnimatedEntry(null, checkedAt = System.currentTimeMillis())
                    persistAnimatedArtworkCache(app)
                }
                FetchResult.Failed -> Unit // مفيش نت: منخزنش "معندوش فيديو" غلط
            }

            withContext(Dispatchers.Main) {
                if (_binding == null) return@withContext
                isVideoAlbum = videoUrl != null
                showAlbum(albumData)
                if (videoUrl != null) runWhenEnterSettled { setupVideoPlayer(videoUrl, albumId) }
            }
        }
    }

    // بيمسح نتيجة الفحص المحفوظة لألبوم معيّن (والصورة المحفوظة بتاعته) عشان يجرب يجيبها تاني من الأول
    private fun forceRecheckAnimatedArtwork(albumData: Album) {
        val cache = loadAnimatedArtworkCache(requireContext())
        cache.remove(albumData.id)
        persistAnimatedArtworkCache(requireContext())
        posterFile(requireContext(), albumData.id).delete()
        posterMemoryCache.remove(albumData.id)
        checkAndFetchAnimatedArtwork(albumData)
    }

    // لو الألبوم ليه فيديو متخزن + أول فريم محفوظ + النسبة واللون: بنجهز واجهة الفيديو بالكامل
    // فورًا (قبل ما بيانات الألبوم توصل)، والصورة بتتفك من الملف مباشرة عشان الـ transition
    // يبدأ وفيه صورة جاهزة. الغلاف الأصلي مبيتحملش خالص في الحالة دي.
    private fun applyCachedVideoHeaderIfPossible() {
        if (binding.videoHeaderContainer == null || binding.videoImage == null) return
        val albumId = arguments.extraAlbumId
        val entry = loadAnimatedArtworkCache(requireContext())[albumId] ?: return
        val ratio = entry.ratio ?: return
        val color = entry.posterColor ?: return
        if (entry.url == null) return
        val file = posterFile(requireContext(), albumId)
        if (!file.exists()) return
        val poster = loadPosterBitmap(albumId, file) ?: return

        isVideoAlbum = true
        posterShown = true
        applyVideoFrameRatio(ratio)
        binding.videoImage?.setImageBitmap(poster)
        binding.videoImage?.transitionName = "${getString(R.string.transition_album_art)}_$albumId"
        binding.headerContainer.visibility = View.GONE
        binding.fragmentAlbumContent.originalButtonsContainer?.visibility = View.GONE
        binding.videoHeaderContainer?.visibility = View.VISIBLE

        hasExtractedColors = true
        dominantBackgroundColor = color
        AlbumDetailsCache.putColor(albumId, color)
        setColors(color)

        view?.post { releaseEnterTransition() }
    }

    // بيفك صورة أول فريم بحجم مناسب للشاشة (مش أكبر من 1.5x عرض الشاشة) ويحتفظ بيها في الذاكرة
    private fun loadPosterBitmap(albumId: Long, file: File): Bitmap? {
        posterMemoryCache.get(albumId)?.let { return it }
        val maxSide = minOf((resources.displayMetrics.widthPixels * 3) / 2, 2048)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        posterMemoryCache.put(albumId, bitmap)
        return bitmap
    }

    // بيضبط نسبة إطار الفيديو على نسبة الفيديو الحقيقية عشان مفيش قص. بنحصرها بين
    // 0.9 و 1.6 عشان فيديو طويل جدًا ميخليش الهيدر ياخد الشاشة كلها.
    private fun applyVideoFrameRatio(heightOverWidth: Float) {
        val frame = binding.videoFrame ?: return
        val ratio = heightOverWidth.coerceIn(0.9f, 1.6f)
        currentFrameRatio = ratio
        val lp = frame.layoutParams as? ConstraintLayout.LayoutParams ?: return
        val newRatio = String.format(Locale.US, "1:%.4f", ratio)
        if (lp.dimensionRatio != newRatio) {
            albumTitleBottomInScrollContent = -1
            lp.dimensionRatio = newRatio
            frame.layoutParams = lp
        }
    }

    // ---------- أدوات مساعدة ----------

    private fun normalizeName(s: String?): String =
        (s ?: "").lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    // "Dreamland (+ Bonus Levels)" -> "Dreamland" ، "Foo - Single" -> "Foo"
    private fun baseTitle(s: String): String =
        s.replace(Regex("""\s*[\(\[][^)\]]*[)\]]"""), "")
            .replace(Regex("""\s+-\s+(single|ep)\s*$""", RegexOption.IGNORE_CASE), "")
            .trim()

    // GET على amp-api بنفس الهيدرز القديمة. null = 404، وأي خطأ تاني بيرمي IOException (يعني Failed مؤقت)
    private fun ampGetJson(urlStr: String, token: String): JSONObject? {
        val connection = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Origin", "https://beta.music.apple.com")
            setRequestProperty("Accept", "*/*")
            setRequestProperty("x-apple-client-version", "2638.7.0-external")
            setRequestProperty(
                "sec-ch-ua",
                "\"Android WebView\";v=\"153\", \"Not_A Brand\";v=\"8\", \"Chromium\";v=\"153\"",
            )
            setRequestProperty("sec-ch-ua-mobile", "?1")
            setRequestProperty("sec-ch-ua-platform", "\"Android\"")
            connectTimeout = 8000
            readTimeout = 8000
        }
        try {
            val code = connection.responseCode
            if (code == 404) return null
            if (code == 401 || code == 403) {
                cachedAnonymousToken = null // التوكن باظ، نجيب واحد جديد المرة الجاية
                throw java.io.IOException("amp-api HTTP $code")
            }
            if (code != 200) throw java.io.IOException("amp-api HTTP $code")
            return JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    // ---------- 1) البحث في كتالوج Apple Music نفسه (نفس اللي تبويب Albums بيستخدمه) ----------

    // بيرجع أكتر من نسخة مرشحة للألبوم (الأصلي، Deluxe، Bonus...) مرتبة من الأقرب للأبعد
    private fun searchAlbumCandidates(albumData: Album, country: String, token: String): List<String> {
        val artist = (if (albumArtistExists) albumData.albumArtist else albumData.artistName).orEmpty()
        val title = baseTitle(albumData.title.orEmpty())
        val wantedTitle = normalizeName(title)
        val wantedArtist = normalizeName(artist)
            .takeIf { it.isNotBlank() && it != "various artists" }

        // جرّب "فنان + عنوان" وبعدين العنوان لوحده (لو اسم الفنان في ملفاتك مكتوب بشكل مختلف)
        val queries = listOf("$artist $title".trim(), title).filter { it.isNotBlank() }.distinct()

        val scored = LinkedHashMap<String, Int>()
        for (q in queries) {
            val url = "https://amp-api.music.apple.com/v1/catalog/$country/search" +
                "?term=${URLEncoder.encode(q, "UTF-8")}&types=albums&limit=10&l=en-US&platform=web"
            val albums = ampGetJson(url, token)
                ?.optJSONObject("results")?.optJSONObject("albums")?.optJSONArray("data") ?: continue

            for (i in 0 until albums.length()) {
                val item = albums.getJSONObject(i)
                val id = item.optString("id")
                val attrs = item.optJSONObject("attributes") ?: continue
                val rawName = attrs.optString("name")
                val name = normalizeName(rawName)
                val baseName = normalizeName(baseTitle(rawName))
                val itemArtist = normalizeName(attrs.optString("artistName"))

                val artistOk = wantedArtist == null ||
                    itemArtist.contains(wantedArtist) || wantedArtist.contains(itemArtist)
                if (!artistOk) continue

                val rank = when {
                    name == wantedTitle -> 0
                    baseName == wantedTitle -> 1
                    else -> continue
                }
                if (id.isNotBlank() && id !in scored) scored[id] = rank * 1000 + rawName.length
            }
            if (scored.isNotEmpty()) break // لقينا مرشحين من أول استعلام، كفاية
        }
        return scored.entries.sortedBy { it.value }.map { it.key }.take(4)
    }

    // ---------- 2) جلب رابط الفيديو لنسخة معيّنة ----------

    private fun fetchMotionUrl(appleMusicId: String, country: String, token: String): FetchResult {
        val extendParams = "editorialArtwork,editorialVideo,extendedAssetUrls,offers,seoDescription,seoTitle"
        val json = ampGetJson(
            "https://amp-api.music.apple.com/v1/catalog/$country/albums/$appleMusicId" +
                "?extend=$extendParams&l=en-US&platform=web",
            token,
        ) ?: return FetchResult.NoVideo

        val data = json.optJSONArray("data")
        if (data == null || data.length() == 0) return FetchResult.NoVideo
        val attributes = data.getJSONObject(0).optJSONObject("attributes") ?: return FetchResult.NoVideo
        val editorialVideo = attributes.optJSONObject("editorialVideo") ?: return FetchResult.NoVideo

        // المفاتيح المعروفة أولاً، وبعدين أي مفتاح تاني فيه "video" (أحياناً الاسم بيختلف)
        val motion = editorialVideo.optJSONObject("motionDetailTall")
            ?: editorialVideo.optJSONObject("motionSquareVideo1x1")
            ?: editorialVideo.keys().asSequence()
                .mapNotNull { editorialVideo.optJSONObject(it) }
                .firstOrNull { it.optString("video").isNotBlank() }
            ?: return FetchResult.NoVideo

        val masterUrl = motion.optString("video").takeIf { it.isNotBlank() } ?: return FetchResult.NoVideo
        return FetchResult.Found(resolveBestVariantUrl(masterUrl, resources.displayMetrics.widthPixels))
    }

    // ---------- 3) الدالة الرئيسية ----------

    private fun fetchAnimatedArtworkUrl(albumData: Album): FetchResult {
        val token = getAnonymousAppleMusicToken() ?: return FetchResult.Failed

        // us الأول، وبعدين بلد الجهاز، وبعدين in/gb. بننتقل للبلد اللي بعده بس لو الألبوم
        // مش موجود خالص في البلد الحالية (مش لو موجود ومعندوش فيديو)
        val storefronts = listOf("us", java.util.Locale.getDefault().country.lowercase(), "in", "gb")
            .filter { it.length == 2 }.distinct()

        for (country in storefronts) {
            val ids = searchAlbumCandidates(albumData, country, token)
            if (ids.isEmpty()) continue
            for (id in ids) {
                val r = fetchMotionUrl(id, country, token)
                if (r is FetchResult.Found) return r
            }
            return FetchResult.NoVideo // الألبوم موجود بس مفيش نسخة منه ليها فيديو
        }
        return FetchResult.NoVideo
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

        debugToast("طول صفحة الـ HTML: ${mainHtml.length} حرف")

        // بندور في كل ملفات JS المذكورة في الصفحة (src أو data-src)، بس بنفحص
        // الملفات اللي في اسمها "legacy" الأول - دي غالبًا اللي فيها التوكن حسب
        // نفس الطريقة المستخدمة في مشاريع reverse-engineering مشابهة لـ Apple Music.
        val jsPaths =
            Regex("""/assets/[^"'\s]+\.js""")
                .findAll(mainHtml)
                .map { it.value }
                .distinct()
                .toList()

        if (jsPaths.isEmpty()) {
            debugToast("مفيش ولا ملف JS واحد في الصفحة")
            return null
        }

        val orderedPaths = jsPaths.sortedByDescending { it.contains("legacy", ignoreCase = true) }

        for (path in orderedPaths) {
            val jsContent = httpGetText("https://beta.music.apple.com$path")
            if (jsContent == null) {
                debugToast("فشل تحميل $path")
                continue
            }
            debugToast("فحصت $path (${jsContent.length} حرف)، فيه eyJ: ${jsContent.contains("eyJ")}")
            val token = extractToken(jsContent)
            if (token != null) {
                cachedAnonymousToken = token
                cachedTokenExpiry = decodeJwtExpiry(token) ?: (System.currentTimeMillis() + 15 * 60 * 1000)
                return token
            }
        }

        debugToast("دورنا في ${orderedPaths.size} ملف (${orderedPaths.joinToString()}) ومنلقيناش توكن صحيح")
        return null
    }

    // بيدور على كل الأنماط اللي شكلها JWT كامل (header.payload.signature) في النص، مش بس
    // اللي بتبدأ بـ eyJh تحديدًا - لأن أول 4 حروف بتتغير حسب ترتيب مفاتيح الـ header
    // (مثلاً "typ" قبل "alg" هتدي eyJ0 مش eyJh). وبيتأكد إن كل واحد فيهم فعلاً JWT حقيقي
    // (مش أي base64 string اتفق إنه يبدأ بنفس الحروف زي توكنات التتبع/الأناليتكس) عن طريق
    // فك أول جزء (header) بتاعه والتأكد إنه JSON فيه مفتاح "alg".
    private fun extractToken(text: String): String? =
        Regex("""eyJ[A-Za-z0-9_\-]{5,}\.[A-Za-z0-9_\-]{10,}\.[A-Za-z0-9_\-]{10,}""")
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

    private fun getCacheDataSourceFactory(): CacheDataSource.Factory =
        cacheDataSourceFactory(requireContext())

    private fun setupVideoPlayer(videoUrl: String, albumId: Long) {
        if (_binding == null || binding.videoPlayerView == null) return

        // نفس الفيديو شغال بالفعل (الـ observer ممكن يتنده أكتر من مرة): منعيدش بناء المشغل
        if (exoPlayer != null && currentVideoUrl == videoUrl) return
        currentVideoUrl = videoUrl

        exoPlayer?.release()
        // بافر صغير: الفيديو قصير وبيتكرر ومكتوم، فمش محتاجين نحمّل ونحتفظ بأكتر من كده في الذاكرة
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(2_000, 10_000, 500, 1_000)
            .build()
        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels
        exoPlayer = ExoPlayer.Builder(requireContext()).setLoadControl(loadControl).build().apply {
            repeatMode = Player.REPEAT_MODE_ONE // تكرار لا نهائي
            volume = 0f // كتم الصوت
            // مفيش داعي نفك فيديو أكبر من شاشة الجهاز
            trackSelectionParameters = trackSelectionParameters.buildUpon()
                .setMaxVideoSize(screenW, screenH)
                .build()
        }

        binding.videoPlayerView?.player = exoPlayer

        val mediaItem = MediaItem.fromUri(videoUrl)
        val mediaSource = androidx.media3.exoplayer.hls.HlsMediaSource.Factory(getCacheDataSourceFactory())
            .createMediaSource(mediaItem)

        exoPlayer?.setMediaSource(mediaSource)
        exoPlayer?.prepare()
        exoPlayer?.playWhenReady = videoVisibleOnScreen && isResumed

        exoPlayer?.addListener(object : Player.Listener {
            // انتقال سلس: عند جاهزية الفيديو، بنظهره فوق الصورة (اللي هي أول فريم منه)
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    binding.videoPlayerView?.animate()?.alpha(1f)?.setDuration(500)?.start()
                }
            }

            // نقيس أبعاد الفيديو الحقيقية ونظبط إطار الصفحة عليها، فمفيش قص
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (_binding == null || videoSize.width == 0 || videoSize.height == 0) return
                var w = videoSize.width * videoSize.pixelWidthHeightRatio
                var h = videoSize.height.toFloat()
                if (videoSize.unappliedRotationDegrees == 90 || videoSize.unappliedRotationDegrees == 270) {
                    val t = w; w = h; h = t
                }
                applyVideoFrameRatio(h / w)
            }

            // أول فريم اتعرض: نحفظه كصورة دائمة (مرة واحدة بس لكل ألبوم)
            override fun onRenderedFirstFrame() {
                capturePosterIfNeeded(albumId)
            }

            // لو رابط الفيديو المخزن مبقاش شغال (404/403) نمسحه عشان يتجاب من جديد المرة الجاية.
            // أخطاء الشبكة/الأوفلاين مبنمسحش بيها حاجة.
            override fun onPlayerError(error: PlaybackException) {
                if (error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS) {
                    context?.applicationContext?.let { ctx ->
                        loadAnimatedArtworkCache(ctx).remove(albumId)
                        persistAnimatedArtworkCache(ctx)
                        posterFile(ctx, albumId).delete()
                        posterMemoryCache.remove(albumId)
                    }
                }
            }
        })
    }

    // بيحفظ أول فريم كصورة دائمة + بيستخرج لون الخلفية منه هو (مش من الغلاف الأصلي)،
    // ويخزن اللون والنسبة مع الألبوم عشان الفتحات الجاية تبدأ بيهم على طول.
    private fun capturePosterIfNeeded(albumId: Long) {
        val ctx = context?.applicationContext ?: return
        val entry = loadAnimatedArtworkCache(ctx)[albumId] ?: return
        if (entry.url == null) return
        val file = posterFile(ctx, albumId)
        if (file.exists() && entry.posterColor != null && entry.ratio != null) return
        val textureView = binding.videoPlayerView?.videoSurfaceView as? TextureView ?: return

        textureView.post {
            if (_binding == null || textureView.width == 0 || textureView.height == 0) return@post
            val frameRatio = currentFrameRatio ?: return@post
            val bitmap = try { textureView.bitmap } catch (e: Exception) { null } ?: return@post
            if (isMostlyBlack(bitmap)) return@post

            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val color = AlbumPaletteEngine.findMostFrequentColor(bitmap)
                    file.parentFile?.mkdirs()
                    val tmp = File(file.parentFile, "$albumId.tmp")
                    FileOutputStream(tmp).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                    if (!tmp.renameTo(file)) { tmp.delete(); return@launch }
                    posterMemoryCache.remove(albumId)

                    val cache = loadAnimatedArtworkCache(ctx)
                    cache[albumId]?.let { e ->
                        if (e.url != null) {
                            cache[albumId] = e.copy(ratio = frameRatio, posterColor = color)
                            persistAnimatedArtworkCache(ctx)
                        }
                    }

                    withContext(Dispatchers.Main) {
                        if (_binding == null) return@withContext
                        AlbumDetailsCache.putColor(albumId, color)
                        if (dominantBackgroundColor != color || !hasExtractedColors) {
                            hasExtractedColors = true
                            dominantBackgroundColor = color
                            setColors(color)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    file.delete()
                }
            }
        }
    }

    // حماية من إننا نحفظ فريم أسود (لو الـ TextureView لسه مرسمش) كصورة دائمة
    private fun isMostlyBlack(bitmap: Bitmap): Boolean {
        val stepX = maxOf(bitmap.width / 12, 1)
        val stepY = maxOf(bitmap.height / 12, 1)
        var sum = 0L
        var count = 0
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val c = bitmap.getPixel(x, y)
                sum += (Color.red(c) + Color.green(c) + Color.blue(c)) / 3
                count++
                x += stepX
            }
            y += stepY
        }
        return count == 0 || sum / count < 8
    }

    override fun onResume() {
        super.onResume()
        exoPlayer?.playWhenReady = videoVisibleOnScreen
        // أي شاشة تانية أو حوار أو الـ Activity ممكن يكون غيّر ألوان الستاتس بار، فبنعيد تطبيق لون الصفحة
        lastStatusBarLight?.let { applyStatusBarAppearance(it) }
    }

    override fun onPause() {
        super.onPause()
        exoPlayer?.playWhenReady = false
    }

    private fun releaseEnterTransition() {
        if (transitionStarted) return
        transitionStarted = true
        startPostponedEnterTransition()
        // بعد ما الانتقال يبدأ، ساعات الـ Activity بيرجّع لون الستاتس بار بتاعه، فنثبّت لون الصفحة
        view?.post { lastStatusBarLight?.let { applyStatusBarAppearance(it) } }
        // شبكة أمان: لو أنيميشن الدخول مبلّغش إنه خلص (مفيش أنيميشن مثلًا)، نعتبر الصفحة استقرت
        view?.postDelayed({ markEnterSettled() }, ENTER_SETTLE_FALLBACK_MS)
    }

    // ينفّذ الشغل التقيل بعد ما أنيميشن الدخول يخلص (أو فورًا لو خلص بالفعل)
    private fun runWhenEnterSettled(block: () -> Unit) {
        if (_binding == null) return
        if (enterSettled) block() else settledCallbacks.add(block)
    }

    // بينفّذ الشغل المؤجل، كل حاجة على فريم لوحدها عشان مفيش فريم واحد تقيل يوقف الحركة
    private fun markEnterSettled() {
        if (enterSettled || _binding == null) return
        enterSettled = true
        val pending = settledCallbacks.toList()
        settledCallbacks.clear()
        fun runNext(index: Int) {
            val root = view ?: return
            if (_binding == null || index >= pending.size) return
            pending[index]()
            root.postOnAnimation { runNext(index + 1) }
        }
        view?.postOnAnimation { runNext(0) }
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
            itemAnimator = null
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

        // لو الصفحة اتفتحت بأول فريم محفوظ من الفيديو، مبنحملش الغلاف الأصلي خالص
        if (!posterShown) loadAlbumCover(album)
        bindSongsStaggered(album.songs)

        // ألبومات الفنان بتتحمل فورًا من غير تأجيل (ونسجّل الـ observer مرة واحدة بس)
        if (!moreAlbumsRequested) {
            moreAlbumsRequested = true
            if (albumArtistExists) {
                detailsViewModel.getAlbumArtist(album.albumArtist.toString()).observe(viewLifecycleOwner) { loadMoreAlbums(it) }
            } else {
                detailsViewModel.getArtist(album.artistId).observe(viewLifecycleOwner) { loadMoreAlbums(it) }
            }
        }
    }

    // أول صفوف بس (اللي ظاهرة قبل السكرول) بتتبند فورًا، والباقي بعد ما الأنيميشن يخلص.
    // القايمة جوه NestedScrollView فبتتقاس كلها مرة واحدة، فكل صف زيادة بيتقل على الأنيميشن.
    private fun bindSongsStaggered(songs: List<Song>) {
        val immediate = minOf(VISIBLE_SONGS_IMMEDIATE, songs.size)
        if (enterSettled || songs.size <= immediate) {
            simpleSongAdapter.swapDataSet(songs)
            return
        }
        simpleSongAdapter.swapDataSet(songs.subList(0, immediate))
        runWhenEnterSettled {
            if (::simpleSongAdapter.isInitialized) simpleSongAdapter.swapDataSet(songs)
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

        // نفس آخر ألبوم اتفتح: الغلاف واللون جاهزين، من غير Glide ولا حساب ألوان
        if (lastVisitedCoverAlbumId == album.id) {
            val bmp = lastVisitedCoverBitmap
            val color = AlbumDetailsCache.getColor(album.id)
            if (bmp != null && color != null) {
                cachedBitmap = bmp
                dominantBackgroundColor = color
                hasExtractedColors = true
                binding.image.setImageBitmap(bmp)
                binding.videoImage?.setImageBitmap(bmp)
                setColors(color)
                view?.post { releaseEnterTransition() }
                return
            }
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
                lastVisitedCoverAlbumId = albumId
                lastVisitedCoverBitmap = bitmap

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
            // الگراديانت بقى شريط ارتفاعه ثابت (في الـ XML) ملازق للحافة السفلية بس، فالتغميق
            // مبيطلعش لفوق خالص وبيغطي بس مكان العنوان واسم الفنان والكابشن.
            // التدرج بقى أطول ومتدرج على مراحل أكتر، فلون الصفحة بينزل على الفيديو ويغطيه أكتر
            val gradient = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(
                    Color.TRANSPARENT,
                    ColorUtils.setAlphaComponent(backgroundColor, 45),
                    ColorUtils.setAlphaComponent(backgroundColor, 120),
                    ColorUtils.setAlphaComponent(backgroundColor, 195),
                    ColorUtils.setAlphaComponent(backgroundColor, 240),
                    backgroundColor
                )
            )
            binding.videoGradient?.background = gradient
            updateVideoGradientHeight()
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

    private fun reapplyColorsIfReady() {
        if (_binding != null && hasExtractedColors) setColors(dominantBackgroundColor)
    }

    // بيرجّعك لنفس مكان السكرول بعد الرجوع من ألبوم تاني. المحتوى (الأغاني والألبومات
    // المقترحة) بيتحمل تدريجيًا، فبنستنى لحد ما الصفحة تبقى طويلة كفاية ونسكرول مرة واحدة.
    private fun restoreScrollPosition() {
        val target = savedScrollY
        if (target <= 0) return
        val scroll = binding.content
        val listener = object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                val child = scroll.getChildAt(0) ?: return
                if (child.height - scroll.height >= target) {
                    scroll.scrollTo(0, target)
                    if (scroll.viewTreeObserver.isAlive) scroll.viewTreeObserver.removeOnGlobalLayoutListener(this)
                }
            }
        }
        scroll.viewTreeObserver.addOnGlobalLayoutListener(listener)
        scroll.postDelayed({
            if (scroll.viewTreeObserver.isAlive) scroll.viewTreeObserver.removeOnGlobalLayoutListener(listener)
        }, 2000L)
    }

    private fun updateVideoGradientHeight() {
        if (_binding == null) return
        val gradientView = binding.videoGradient ?: return
        val frame = binding.videoFrame ?: return
        if (frame.height <= 0) return
        val target = (frame.height * VIDEO_GRADIENT_HEIGHT_FRACTION).toInt()
        val lp = gradientView.layoutParams ?: return
        if (lp.height != target) {
            lp.height = target
            gradientView.layoutParams = lp
        }
    }

    // ---------- ستاتس بار: حفظ الحالة الأصلية مرة واحدة وإرجاعها لما آخر صفحة ألبوم تتقفل ----------

    private fun readStatusBarLight(): Boolean? {
        val window = activity?.window ?: return null
        return androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars
    }

    private fun registerStatusBar() {
        if (statusBarRegistered) return
        if (albumPagesAlive <= 0 || baseStatusBarLight == null) {
            baseStatusBarLight = readStatusBarLight()
        }
        albumPagesAlive++
        statusBarRegistered = true
    }

    private fun releaseStatusBar() {
        if (!statusBarRegistered) return
        statusBarRegistered = false
        albumPagesAlive--
        if (albumPagesAlive <= 0) {
            albumPagesAlive = 0
            val base = baseStatusBarLight
            baseStatusBarLight = null
            if (base != null) {
                activity?.window?.let { window ->
                    androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = base
                }
            }
        }
    }

    // ---------- انيميشن الخروج ----------

    // true = الصفحة دي بتتقفل (رجوع) ورايحة لشاشة مش صفحة ألبوم (زي قايمة الألبومات).
    // في الفتح (push) الصفحة بتفضل في الـ back stack، وفي الرجوع (pop) بتتشال منه.
    private fun isPoppedToNonAlbumPage(): Boolean {
        val entry = navEntry ?: return false
        val nav = runCatching { findNavController() }.getOrNull() ?: return false
        val stillInStack = entry == nav.currentBackStackEntry || entry == nav.previousBackStackEntry
        if (stillInStack) return false
        return nav.currentDestination?.id != R.id.albumDetailsFragment
    }

    override fun onCreateAnimation(transit: Int, enter: Boolean, nextAnim: Int): Animation? {
        if (!enter && isPoppedToNonAlbumPage()) {
            // رجوع لقايمة الألبومات: ستاتس بار يرجع فورًا + انيميشن خروج خاص بالرجوع
            // (مبقاش بيستعمل انيميشن الفتح). الدخول لأي صفحة تانية مبيتغيرش.
            releaseStatusBar()
            return AnimationUtils.loadAnimation(requireContext(), R.anim.nav_slide_out_right)
        }
        val anim = super.onCreateAnimation(transit, enter, nextAnim)
            ?: if (enter && nextAnim != 0 &&
                runCatching { resources.getResourceTypeName(nextAnim) == "anim" }.getOrDefault(false)
            ) {
                // نفس اللي الـ framework كان هيعمله بالظبط، بس بنمسك منه لحظة انتهاء الأنيميشن
                runCatching { AnimationUtils.loadAnimation(requireContext(), nextAnim) }.getOrNull()
            } else null
        if (enter && anim != null) {
            anim.setAnimationListener(object : Animation.AnimationListener {
                override fun onAnimationStart(animation: Animation?) {}
                override fun onAnimationRepeat(animation: Animation?) {}
                override fun onAnimationEnd(animation: Animation?) {
                    markEnterSettled()
                }
            })
        }
        return anim
    }

    private fun applyStatusBarAppearance(isLightBackground: Boolean) {
        if (!statusBarRegistered) return // الصفحة بدأت تتقفل: مننفضش على الستاتس بار تاني
        lastStatusBarLight = isLightBackground
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
        savedScrollY = _binding?.content?.scrollY ?: 0
        super.onDestroyView()
        exoPlayer?.release()
        exoPlayer = null
        currentVideoUrl = null
        settledCallbacks.clear()
        enterSettled = false
        customOverflowIcon = null
        transitionStarted = false
        posterShown = false
        currentFrameRatio = null
        albumTitleBottomInScrollContent = -1
        releaseStatusBar()
        navEntry = null
        lastStatusBarLight = null
        _binding = null
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceActivity?.removeMusicServiceEventListener(detailsViewModel)
    }
}
