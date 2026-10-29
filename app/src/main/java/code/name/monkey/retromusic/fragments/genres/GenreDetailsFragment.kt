package code.name.monkey.retromusic.fragments.genres

import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.LruCache
import android.util.TypedValue
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.widget.ImageView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavBackStackEntry
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.adapter.song.SongAdapter
import code.name.monkey.retromusic.databinding.FragmentPlaylistDetailNewBinding
import code.name.monkey.retromusic.dialogs.AddToPlaylistDialog
import code.name.monkey.retromusic.fragments.base.AbsMainActivityFragment
import code.name.monkey.retromusic.glide.RetroGlideExtension.playlistOptions
import code.name.monkey.retromusic.glide.playlistPreview.GenrePreview
import code.name.monkey.retromusic.helper.MusicPlayerRemote
import code.name.monkey.retromusic.helper.menu.GenreMenuHelper
import code.name.monkey.retromusic.model.Genre
import code.name.monkey.retromusic.model.Song
import code.name.monkey.retromusic.repository.RealRepository
import code.name.monkey.retromusic.util.MusicUtil
import code.name.monkey.retromusic.util.ThemedFastScroller
import code.name.monkey.retromusic.views.TintableToolbar
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.android.fastscroll.FastScroller
import org.koin.android.ext.android.get
import org.koin.androidx.viewmodel.ext.android.viewModel
import org.koin.core.parameter.parametersOf

// نفس قيم صفحة الـ Playlist بالظبط
private const val TOOLBAR_ICON_ALPHA = 0xCC
private const val GRADIENT_EXTRA = 1.6f
private const val GRADIENT_FALLBACK_FRACTION = 0.5f
private const val VISIBLE_SONGS_IMMEDIATE = 14
private const val ENTER_SETTLE_FALLBACK_MS = 700L

// أقصى مدة نستنى فيها (الهيدر + الصورة + أول دفعة أغاني) قبل ما الأنيميشن يبدأ.
private const val RELEASE_DEADLINE_MS = 350L
private const val COLLAGE_SIZE_PX = 1200

class GenreDetailsFragment : AbsMainActivityFragment(R.layout.fragment_playlist_detail_new) {
    private val arguments by navArgs<GenreDetailsFragmentArgs>()
    private val detailsViewModel: GenreDetailsViewModel by viewModel {
        parametersOf(arguments.extraGenre)
    }

    private var _binding: FragmentPlaylistDetailNewBinding? = null
    private val binding get() = _binding!!

    // القائمة بتتحكم في الـ toolbar بنفسها (زي صفحة الـ Playlist)
    override val registersMenuProvider: Boolean = false

    private val genre: Genre get() = arguments.extraGenre
    private var allSongs: List<Song> = emptyList()
    private lateinit var songAdapter: SongAdapter
    private var fastScroller: FastScroller? = null
    private var fastScrollerTop = -1
    private var preDrawListener: ViewTreeObserver.OnPreDrawListener? = null
    private var lastToolbarAlpha = -1

    // الأغاني اتربطت كلها في الـ adapter؟ (قبل كده بيبقى فيه أول دفعة بس)
    private var songsFullyBound = false
    private var appliedSongsSignature: Int? = null
    private var appliedCollageKey: Int? = null

    private var customOverflowIcon: ImageView? = null
    private var surfaceColor: Int = Color.BLACK
    private var foregroundColor: Int = Color.WHITE
    private var navEntry: NavBackStackEntry? = null
    private var transitionStarted = false

    // الأنيميشن بيبدأ لما التلاتة يجهزوا: الهيدر اتقاس، الصورة اتولدت، وأول دفعة أغاني اتربطت.
    private var headerReady = false
    private var imageReady = false
    private var contentReady = false

    // الصفحة "استقرت" لما أنيميشن الدخول يخلص: الشغل التقيل بيتأجل لحد كده
    private var enterSettled = false
    private val settledCallbacks = mutableListOf<() -> Unit>()

    // مكان القايمة قبل ما نفتح صفحة تانية (بيتحفظ في onDestroyView)
    private var savedFirstPosition = 0
    private var savedFirstOffset = 0

    companion object {
        // آخر genre اتفتح (خانة واحدة): لو رجعت لنفس الـ genre تتفتح فورًا من الذاكرة
        private var lastGenreId = -1L
        private var lastSongs: List<Song> = emptyList()

        // آخر صورتين اتولّدوا: الفتح التاني لأي genre منهم فوري.
        private val collageCache = LruCache<Int, Bitmap>(2)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        postponeEnterTransition()
        enterSettled = false
        settledCallbacks.clear()
        headerReady = false
        imageReady = false
        contentReady = false
        songsFullyBound = false
        appliedSongsSignature = null
        appliedCollageKey = null
        _binding = FragmentPlaylistDetailNewBinding.bind(view)
        navEntry = runCatching { findNavController().currentBackStackEntry }.getOrNull()
        mainActivity.addMusicServiceEventListener(detailsViewModel)

        // لون الصفحة من الثيم (مش من الصورة)
        surfaceColor = resolveSurfaceColor()
        binding.rootLayout.setBackgroundColor(surfaceColor)
        // الصورة مخفية لحد ما تتحمل
        binding.image.setImageDrawable(null)
        binding.image.alpha = 0f

        WindowCompat.setDecorFitsSystemWindows(requireActivity().window, false)

        val toolbar = binding.toolbar as TintableToolbar
        toolbar.title = null
        toolbar.contentInsetStartWithNavigation = 0
        toolbar.setTitleMarginStart(0)
        toolbar.contentInsetEndWithActions = 0
        toolbar.setPadding(toolbar.paddingLeft, toolbar.paddingTop, 0, toolbar.paddingBottom)
        toolbar.setNavigationOnClickListener { findNavController().navigateUp() }

        view.post {
            if (_binding == null) return@post
            toolbar.inflateMenu(R.menu.menu_genre_detail)
            toolbar.setOnMenuItemClickListener { item ->
                GenreMenuHelper.handleMenuClick(requireActivity(), genre, item)
            }
            setUpCustomOverflowIcon(toolbar)
            applyColors()
        }

        // الاسكرولير بيبدأ من تحت الـ toolbar
        binding.appBarLayout.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateFastScrollerPadding()
        }
        binding.appBarLayout.let { appBar ->
            ViewCompat.setOnApplyWindowInsetsListener(appBar) { v, insets ->
                val statusBarInsets = insets.getInsets(WindowInsetsCompat.Type.statusBars())
                v.updatePadding(top = statusBarInsets.top)
                insets
            }
        }

        setUpRecyclerView()
        setupButtons()

        // العنوان من الـ arguments فورًا
        binding.playlistTitle.text = genre.name

        // الهيدر لما يتقاس: نظبط مسافة القايمة والتدرج، ونبدأ الانتقال
        binding.headerContainer.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            onHeaderLaidOut()
        }
        applyColors()

        // آخر genre اتفتح: لو فتحته تاني بنعرض أغانيه والصورة فورًا من الذاكرة
        // من غير ما نستنى الـ ViewModel، وأي تغيير حقيقي بيتحدّث لوحده.
        if (lastGenreId == genre.id && lastSongs.isNotEmpty()) {
            songs(lastSongs)
        }

        detailsViewModel.getSongs().observe(viewLifecycleOwner) {
            lastGenreId = genre.id
            lastSongs = it
            songs(it)
        }

        // شبكة أمان: لو الهيدر اتأخر في القياس، الصفحة تفتح برضه
        view.postDelayed({ releaseEnterTransition() }, RELEASE_DEADLINE_MS)
    }

    // ---------- الصورة التلقائية (من صور الأغاني، بنفس طريقة الـ Playlist) ----------

    private fun loadCollage(songs: List<Song>, signature: Int) {
        val key = 31 * genre.id.hashCode() + signature
        if (key == appliedCollageKey) return
        appliedCollageKey = key
        // نفس الصورة اتعرضت قبل كده: بنحطها فورًا من الذاكرة من غير ما نولّدها تاني
        val cached = collageCache.get(key)
        if (cached != null && !cached.isRecycled) {
            binding.image.setImageBitmap(cached)
            binding.image.alpha = 1f
            imageReady = true
            tryRelease()
            return
        }
        // التوليد بيبدأ فورًا مش بعد الأنيميشن، والصفحة متأجلة لحد ما الصورة تجهز
        Glide.with(this)
            .load(GenrePreview(genre, songs))
            .playlistOptions()
            .override(COLLAGE_SIZE_PX)
            .placeholder(ColorDrawable(Color.TRANSPARENT))
            .listener(object : RequestListener<Drawable> {
                override fun onLoadFailed(
                    e: GlideException?, model: Any?, target: Target<Drawable>?,
                    isFirstResource: Boolean
                ): Boolean {
                    showImage()
                    return false
                }

                override fun onResourceReady(
                    resource: Drawable?, model: Any?, target: Target<Drawable>?,
                    dataSource: DataSource?, isFirstResource: Boolean
                ): Boolean {
                    showImage()
                    // نسخة في الذاكرة للفتح الجاي (مش نفس bitmap بتاع Glide عشان ميتعاد استخدامه)
                    val source = (resource as? BitmapDrawable)?.bitmap
                    if (source != null && !source.isRecycled) {
                        view?.post {
                            runCatching {
                                source.copy(Bitmap.Config.ARGB_8888, false)
                                    ?.let { collageCache.put(key, it) }
                            }
                        }
                    }
                    return false
                }
            })
            .into(binding.image)
    }

    // الصورة جاهزة: لو الأنيميشن لسه مابدأش بتظهر فورًا، وإلا بتظهر بـ fade.
    private fun showImage() {
        val b = _binding ?: return
        if (transitionStarted) fadeInImage() else b.image.alpha = 1f
        view?.post {
            if (_binding != null) {
                imageReady = true
                tryRelease()
            }
        }
    }

    private fun fadeInImage() {
        _binding?.image?.animate()?.alpha(1f)?.setDuration(160L)?.start()
    }

    // ---------- الأزرار ----------

    private fun setupButtons() {
        binding.playAction.setOnClickListener {
            if (allSongs.isNotEmpty()) MusicPlayerRemote.openQueue(allSongs, 0, true)
        }
        binding.shuffleAction.setOnClickListener {
            if (allSongs.isNotEmpty()) MusicPlayerRemote.openAndShuffleQueue(allSongs, true)
        }
        binding.addAction.setOnClickListener {
            val songs = allSongs
            if (songs.isEmpty()) return@setOnClickListener
            lifecycleScope.launch(Dispatchers.IO) {
                val playlists = get<RealRepository>().fetchPlaylists()
                withContext(Dispatchers.Main) {
                    if (_binding != null) {
                        AddToPlaylistDialog.create(playlists, songs)
                            .show(requireActivity().supportFragmentManager, "ADD_PLAYLIST")
                    }
                }
            }
        }
    }

    // ---------- القايمة ----------

    private fun setUpRecyclerView() {
        // نفس layout صفحة الأغاني (Songs) بالظبط
        songAdapter = SongAdapter(requireActivity(), ArrayList(), R.layout.item_list)

        val bottomPadding =
            resources.getDimensionPixelSize(R.dimen.mini_player_height) +
                (32 * resources.displayMetrics.density).toInt()

        binding.recyclerView.apply {
            adapter = songAdapter
            layoutManager = LinearLayoutManager(requireContext())
            itemAnimator = DefaultItemAnimator()
            updatePadding(bottom = bottomPadding)
            // الاسكرولير مش بيتعمل هنا: بنعمله أول ما المستخدم يبدأ يسحب القايمة (ensureFastScroller).
            // الهيدر لازم يمشي مع القايمة في كل فريم بيترسم، مهما كان سبب الحركة.
            val listener = ViewTreeObserver.OnPreDrawListener {
                if (_binding != null) syncHeaderWithList()
                true
            }
            preDrawListener = listener
            viewTreeObserver.addOnPreDrawListener(listener)
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    syncHeaderWithList()
                }

                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    if (newState == RecyclerView.SCROLL_STATE_DRAGGING) ensureFastScroller()
                    if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                        syncHeaderWithList()
                        updateFastScrollerPadding()
                    }
                }
            })
        }
        songAdapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() {
                checkIsEmpty()
                postSyncHeader()
            }

            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) = postSyncHeader()
            override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) = postSyncHeader()
            override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) =
                postSyncHeader()
        })
    }

    // بيتعمل أول مرة المستخدم يسحب القايمة بصباعه، مش وقت فتح الصفحة.
    private fun ensureFastScroller() {
        if (fastScroller != null || _binding == null) return
        fastScroller = ThemedFastScroller.create(binding.recyclerView)
        fastScrollerTop = -1
        updateFastScrollerPadding()
    }

    // بداية مسار الاسكرولير = أسفل الهيدر وهو ظاهر، ولما الهيدر يطلع لفوق بينزل معاه
    // لحد ما يقف تحت الـ toolbar.
    private fun updateFastScrollerPadding(headerOffset: Int = -1) {
        if (_binding == null) return
        val appBarHeight = binding.appBarLayout.height
        val headerHeight = binding.headerContainer.height
        if (appBarHeight <= 0 || headerHeight <= 0) return
        val offset = if (headerOffset >= 0) headerOffset else headerScrollOffset()
        applyScrollerTop(maxOf(appBarHeight, headerHeight - offset))
    }

    private fun applyScrollerTop(top: Int) {
        if (top == fastScrollerTop) return
        fastScrollerTop = top
        fastScroller?.setPadding(0, top, 0, binding.recyclerView.paddingBottom)
    }

    private fun postSyncHeader() {
        view?.post { if (_binding != null) syncHeaderWithList() }
    }

    fun songs(songs: List<Song>) {
        allSongs = songs
        if (songs.isEmpty()) {
            appliedSongsSignature = null
            songsFullyBound = true
            songAdapter.swapDataSet(emptyList())
            checkIsEmpty()
            return
        }
        // نفس القايمة جت تاني: منعيدش ربط كل الصفوف
        val signature = songs.fold(songs.size) { acc, s -> 31 * (31 * acc + s.id.hashCode()) + s.title.hashCode() }
        if (signature == appliedSongsSignature) return
        appliedSongsSignature = signature

        binding.playlistInfo.text = MusicUtil.getPlaylistInfoString(requireContext(), songs)
        loadCollage(songs, signature)

        val immediate = minOf(VISIBLE_SONGS_IMMEDIATE, songs.size)
        if (enterSettled || songs.size <= immediate) {
            songsFullyBound = true
            songAdapter.swapDataSet(songs)
            restoreListPosition()
        } else {
            // أول دفعة بس وقت الأنيميشن، والباقي بعد ما الأنيميشن يخلص.
            songsFullyBound = false
            songAdapter.swapDataSet(ArrayList(songs.subList(0, immediate)))
            runWhenEnterSettled { bindAllSongsIfNeeded() }
        }
        markContentReady()
    }

    // بيربط كل الأغاني (بعد أول دفعة). بيستخدم آخر قايمة وصلت، وآمن لو اتنادى أكتر من مرة.
    private fun bindAllSongsIfNeeded() {
        if (_binding == null || songsFullyBound || allSongs.isEmpty()) return
        songAdapter.swapDataSet(allSongs)
        songsFullyBound = true
        restoreListPosition()
    }

    private fun restoreListPosition() {
        val position = savedFirstPosition
        if (position <= 0) return
        val offset = savedFirstOffset
        savedFirstPosition = 0
        savedFirstOffset = 0
        binding.recyclerView.post {
            if (_binding == null) return@post
            (binding.recyclerView.layoutManager as? LinearLayoutManager)
                ?.scrollToPositionWithOffset(position, offset)
        }
    }

    private fun checkIsEmpty() {
        if (_binding == null) return
        val empty = songAdapter.itemCount == 0 && songsFullyBound
        binding.empty.isVisible = empty
        binding.emptyText.isVisible = empty
    }

    // ---------- الهيدر: بيتحرك مع القايمة ----------

    private fun onHeaderLaidOut() {
        if (_binding == null) return
        val header = binding.headerContainer
        val height = header.height
        if (height <= 0) return
        val rv = binding.recyclerView
        if (rv.paddingTop != height) {
            val oldPadding = rv.paddingTop
            val lm = rv.layoutManager as? LinearLayoutManager
            // القايمة كانت في أولها؟ (الصفوف اتربطت قبل ما الهيدر يتقاس، فكانت لازقة فوق)
            val wasAtTop = lm != null && lm.findFirstVisibleItemPosition() <= 0 &&
                (lm.findViewByPosition(0)?.top ?: oldPadding) >= oldPadding
            rv.updatePadding(top = height)
            // بعد ما المسافة العلوية تكبر، نرجّع أول صف تحت الهيدر
            if (wasAtTop) lm?.scrollToPositionWithOffset(0, 0)
        }
        updateGradientHeight()
        syncHeaderWithList()
        if (!headerReady) {
            view?.post { markHeaderReady() }
        }
    }

    // مقدار ما القايمة اتسكرولت بالنسبة للأعلى = مقدار ما الهيدر لازم يتحرك لفوق
    private fun headerScrollOffset(): Int {
        val rv = binding.recyclerView
        if (songAdapter.itemCount == 0) return 0
        val lm = rv.layoutManager as? LinearLayoutManager ?: return 0
        val first = lm.findViewByPosition(0)
        if (first != null) return (rv.paddingTop - first.top).coerceAtLeast(0)
        // لسه مفيش layout: الهيدر يفضل ظاهر. لو أول صف ظاهر مش رقم 0: اتسكرولنا بعيد عن الهيدر.
        return if (lm.findFirstVisibleItemPosition() > 0) binding.headerContainer.height else 0
    }

    private fun syncHeaderWithList() {
        if (_binding == null) return
        val offset = headerScrollOffset()
        val ty = -offset.toFloat()
        if (binding.headerContainer.translationY != ty) binding.headerContainer.translationY = ty
        updateFastScrollerPadding(offset)

        // الـ toolbar بياخد لون الصفحة تدريجيًا لما العنوان يطلع تحته
        val toolbar = binding.toolbar
        val titleBottom = binding.playlistTitle.bottom - binding.appBarLayout.height
        if (titleBottom > 0) {
            val fadeWindowPx = (32 * resources.displayMetrics.density).toInt()
            val startFade = titleBottom - fadeWindowPx / 2
            val endFade = titleBottom + fadeWindowPx / 2
            val progress = when {
                offset <= startFade -> 0f
                offset >= endFade -> 1f
                else -> (offset - startFade).toFloat() / (endFade - startFade)
            }
            val alpha = (progress * 255).toInt()
            if (alpha != lastToolbarAlpha) {
                lastToolbarAlpha = alpha
                binding.appBarLayout.setBackgroundColor(
                    ColorUtils.setAlphaComponent(surfaceColor, alpha)
                )
            }
            if (progress >= 0.9f) {
                if (toolbar.title.isNullOrEmpty()) toolbar.title = binding.playlistTitle.text
            } else if (!toolbar.title.isNullOrEmpty()) {
                toolbar.title = null
            }
        }
    }

    private fun updateGradientHeight() {
        if (_binding == null) return
        val frame = binding.imageFrame
        if (frame.height <= 0) return
        val title = binding.playlistTitle
        val target = if (title.height > 0 && title.top > 0) {
            val fromTitleTop = (frame.height - title.top).coerceAtLeast(0)
            (fromTitleTop * (1f + GRADIENT_EXTRA)).toInt().coerceAtMost(frame.height)
        } else (frame.height * GRADIENT_FALLBACK_FRACTION).toInt()
        val lp = binding.imageGradient.layoutParams ?: return
        if (target > 0 && lp.height != target) {
            lp.height = target
            binding.imageGradient.layoutParams = lp
        }
    }

    // ---------- الألوان (من الثيم، مش من الصورة) ----------

    private fun resolveSurfaceColor(): Int {
        val typedValue = TypedValue()
        val resolved = requireContext().theme.resolveAttribute(
            com.google.android.material.R.attr.colorSurface, typedValue, true
        )
        return if (resolved) typedValue.data else Color.BLACK
    }

    // منحنى تغميق ناعم (smootherstep) من شفاف للون الصفحة
    private fun smoothFade(color: Int): IntArray {
        val steps = 28
        return IntArray(steps + 1) { i ->
            val pos = i / steps.toFloat()
            val x = (pos / 0.97f).coerceIn(0f, 1f)
            val eased = x * x * x * (x * (x * 6f - 15f) + 10f)
            ColorUtils.setAlphaComponent(color, (eased * 255f).toInt().coerceIn(0, 255))
        }
    }

    private fun applyColors() {
        if (_binding == null) return
        val bg = surfaceColor
        val isLight = ColorUtils.calculateLuminance(bg) > 0.45f
        val fg = if (isLight) Color.BLACK else Color.WHITE
        val secondaryFg = ColorUtils.setAlphaComponent(fg, 0xCC)
        foregroundColor = fg

        binding.rootLayout.setBackgroundColor(bg)
        binding.playlistTitle.setTextColor(fg)
        binding.playlistInfo.setTextColor(secondaryFg)
        binding.imageGradient.background =
            GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, smoothFade(bg))

        val toolbar = binding.toolbar
        val iconColor = ColorUtils.setAlphaComponent(fg, TOOLBAR_ICON_ALPHA)
        if (toolbar is TintableToolbar) {
            toolbar.navigationIcon?.let { DrawableCompat.setTint(it, iconColor) }
            customOverflowIcon?.drawable?.let { DrawableCompat.setTint(it.mutate(), iconColor) }
            toolbar.setTitleTextColor(fg)
        }

        binding.shuffleActionGlass.setBackdropColor(bg)
        binding.addActionGlass.setBackdropColor(bg)
        binding.shuffleAction.iconTint = ColorStateList.valueOf(fg)
        binding.addAction.iconTint = ColorStateList.valueOf(fg)

        val playBg = if (isLight) Color.BLACK else Color.WHITE
        val playFg = if (isLight) Color.WHITE else Color.BLACK
        binding.playAction.apply {
            backgroundTintList = ColorStateList.valueOf(playBg)
            setTextColor(playFg)
            iconTint = ColorStateList.valueOf(playFg)
        }
    }

    // ---------- بدء الانتقال + تأجيل الشغل التقيل ----------

    private fun markHeaderReady() {
        if (_binding == null) return
        headerReady = true
        tryRelease()
    }

    private fun markContentReady() {
        contentReady = true
        tryRelease()
    }

    private fun tryRelease() {
        if (headerReady && imageReady && contentReady) releaseEnterTransition()
    }

    private fun releaseEnterTransition() {
        if (transitionStarted || _binding == null) return
        transitionStarted = true
        startPostponedEnterTransition()
        // شبكة أمان: لو أنيميشن الدخول مبلّغش إنه خلص، نعتبر الصفحة استقرت
        view?.postDelayed({ markEnterSettled() }, ENTER_SETTLE_FALLBACK_MS)
    }

    private fun runWhenEnterSettled(block: () -> Unit) {
        if (_binding == null) return
        if (enterSettled) block() else settledCallbacks.add(block)
    }

    // بينفّذ الشغل المؤجل، كل حاجة على فريم لوحدها
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

    // ---------- الأنيميشن (نفس صفحة الـ Playlist) ----------

    // true = الصفحة دي بتتقفل (رجوع) ورايحة لشاشة تانية غير صفحة genre.
    private fun isPoppedAway(): Boolean {
        val entry = navEntry ?: return false
        val nav = runCatching { findNavController() }.getOrNull() ?: return false
        val stillInStack = entry == nav.currentBackStackEntry || entry == nav.previousBackStackEntry
        if (stillInStack) return false
        return nav.currentDestination?.id != R.id.genreDetailsFragment
    }

    override fun onCreateAnimation(transit: Int, enter: Boolean, nextAnim: Int): Animation? {
        if (!enter && isPoppedAway()) {
            // رجوع لقايمة الـ genres: انيميشن خروج خاص بالرجوع
            return AnimationUtils.loadAnimation(requireContext(), R.anim.nav_slide_out_right)
        }
        val anim = super.onCreateAnimation(transit, enter, nextAnim)
            ?: if (enter && nextAnim != 0 &&
                runCatching { resources.getResourceTypeName(nextAnim) == "anim" }.getOrDefault(false)
            ) {
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

    // ---------- أيقونة الثلاث نقط (نفس صفحة الـ Playlist) ----------

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
            imageView.translationX = (toolbarLoc[0] - rootLoc[0] + toolbar.width - sizePx).toFloat()
            imageView.translationY = (toolbarLoc[1] - rootLoc[1] + (toolbar.height - sizePx) / 2).toFloat()
        }
        toolbar.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> repositionOverImageView() }
        toolbar.post { repositionOverImageView() }
    }

    override fun onCreateMenu(menu: Menu, inflater: MenuInflater) {}

    override fun onMenuItemSelected(item: MenuItem): Boolean = false

    override fun onDestroyView() {
        _binding?.let { b ->
            val first = (b.recyclerView.layoutManager as? LinearLayoutManager)
            val pos = first?.findFirstVisibleItemPosition() ?: 0
            savedFirstPosition = pos
            savedFirstOffset = first?.findViewByPosition(pos)?.top?.minus(b.recyclerView.paddingTop) ?: 0
        }
        _binding?.recyclerView?.let { rv ->
            preDrawListener?.let { l ->
                val vto = rv.viewTreeObserver
                if (vto.isAlive) vto.removeOnPreDrawListener(l)
            }
        }
        preDrawListener = null
        lastToolbarAlpha = -1
        runCatching { mainActivity.removeMusicServiceEventListener(detailsViewModel) }
        super.onDestroyView()
        fastScroller = null
        fastScrollerTop = -1
        settledCallbacks.clear()
        enterSettled = false
        transitionStarted = false
        headerReady = false
        imageReady = false
        contentReady = false
        customOverflowIcon = null
        navEntry = null
        _binding = null
    }
}
