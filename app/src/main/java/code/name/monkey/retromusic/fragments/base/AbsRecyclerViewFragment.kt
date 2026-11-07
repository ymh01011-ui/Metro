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
package code.name.monkey.retromusic.fragments.base

import code.name.monkey.retromusic.views.GlassPopupMenu
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.os.Parcelable
import android.view.*
import androidx.annotation.NonNull
import androidx.annotation.StringRes
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.Toolbar
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnPreDraw
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import code.name.monkey.appthemehelper.util.ColorUtil
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.adapter.base.AbsMultiSelectAdapter
import code.name.monkey.retromusic.databinding.FragmentMainRecyclerBinding
import code.name.monkey.retromusic.dialogs.CreatePlaylistDialog
import code.name.monkey.retromusic.dialogs.ImportPlaylistDialog
import code.name.monkey.retromusic.extensions.accentColor
import code.name.monkey.retromusic.extensions.dip
import code.name.monkey.retromusic.extensions.textColorSecondary
import code.name.monkey.retromusic.views.GlassHelper
import code.name.monkey.retromusic.views.GlassLayer
import code.name.monkey.retromusic.util.PreferenceUtil
import code.name.monkey.retromusic.interfaces.IScrollHelper
import code.name.monkey.retromusic.util.ThemedFastScroller.create
import com.google.android.material.color.MaterialColors
import com.google.android.material.transition.MaterialFadeThrough
import me.zhanghai.android.fastscroll.FastScroller
import me.zhanghai.android.fastscroll.FastScrollerBuilder

abstract class AbsRecyclerViewFragment<A : RecyclerView.Adapter<*>, LM : RecyclerView.LayoutManager> :
    AbsMainActivityFragment(R.layout.fragment_main_recycler), IScrollHelper {

    // قايمة (الترتيب، حجم الشبكة، إلخ) بتتفتح من زرار ⋯ في الصف اللي تحت شريط
    // البحث عن طريق PopupMenu، فمفيش toolbar حقيقي بيعرض menu. عشان كده نوقف نظام
    // الـ MenuProvider ونحن اللي بننده onCreateMenu/onMenuItemSelected يدوي على
    // منيو الـ PopupMenu (فـ ArtistsFragment ومشتقاتها مش محتاجين يتغيروا).
    override val registersMenuProvider: Boolean = false

    private var _binding: FragmentMainRecyclerBinding? = null
    private val binding get() = _binding!!
    protected var adapter: A? = null
    protected var layoutManager: LM? = null
    val shuffleButton get() = binding.shuffleButton
    abstract val isShuffleVisible: Boolean

    // كل مرة تدخل صفحة فنان وترجع، الـ Navigation Component بيدمر View الصفحة
    // ويبنيه تاني من الصفر، فبنخزن مكان السكرول هنا (على مستوى الـ Fragment نفسه)
    // وبنرجعه لما الـ View الجديد يتبني.
    private var savedRecyclerLayoutState: Parcelable? = null

    // ارتفاع الـ status bar (بيتحدد من الـ insets)
    private var statusBarTop = 0

    // الـ FastScroller بيتعمل أول ما المستخدم يسحب القايمة، فبنحتفظ بيه هنا
    // عشان نظبط له الـ padding (يبدأ من أول عنصر مش من أول الشاشة).
    private var fastScroller: FastScroller? = null

    // ---- أبعاد الهيدر (dp) ----
    // [status bar] 8 | شريط البحث 48 | 18 | الصف 40 | 11 | أول عنصر في القايمة
    private fun px(dp: Int) = (dp * resources.displayMetrics.density).toInt()
    private fun pillTop() = statusBarTop + px(8)
    private fun pillBottom() = pillTop() + px(48)
    private fun rowTop() = pillBottom() + px(18)
    private fun rowBottom() = rowTop() + px(40)
    private fun headerHeight() = rowBottom() + px(11)

    // أول صف من العناصر بياخد offset من فوق بمقدار الهيدر، فالقايمة بتبدأ تحت
    // شريط البحث والصف (اللي مرسومين فوقها). الـ padding مش بنلمسه عشان
    // InsetsRecyclerView بيتحكم فيه.
    private val headerDecoration = object : RecyclerView.ItemDecoration() {
        override fun getItemOffsets(
            outRect: Rect,
            view: View,
            parent: RecyclerView,
            state: RecyclerView.State,
        ) {
            if (isInFirstRow(parent, parent.getChildAdapterPosition(view))) {
                outRect.top = headerHeight()
            }
        }
    }

    private fun isInFirstRow(parent: RecyclerView, position: Int): Boolean {
        if (position == RecyclerView.NO_POSITION) return false
        return when (val lm = parent.layoutManager) {
            is GridLayoutManager -> lm.spanSizeLookup.getSpanGroupIndex(position, lm.spanCount) == 0
            is StaggeredGridLayoutManager -> position < lm.spanCount
            else -> position == 0
        }
    }

    // شريط البحث بيستخبى لما تسحب القايمة لفوق وبيرجع فورًا لما تسحب لتحت؛ ولما
    // تسيب إيدك بيكمل لأقرب وضع (مخفي/ظاهر).
    private fun hideRange() = pillBottom().toFloat()

    private val snapSearchBar = Runnable {
        val bar = _binding?.searchBar ?: return@Runnable
        val target = if (bar.translationY < -hideRange() / 2f) -hideRange() else 0f
        bar.animate().translationY(target).setDuration(150).start()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentMainRecyclerBinding.bind(view)
        postponeEnterTransition()
        view.doOnPreDraw { startPostponedEnterTransition() }
        enterTransition = MaterialFadeThrough().addTarget(binding.recyclerView)
        reenterTransition = MaterialFadeThrough().addTarget(binding.recyclerView)
        initLayoutManager()
        initAdapter()
        checkForMargins()
        setUpRecyclerView()
        setUpSearchBar()
        setUpHeaderRow()
        restoreScrollState()
        updateCount()
        binding.recyclerView.post { updateRowPosition() }
    }

    open fun onShuffleClicked() {
    }

    // placeholder مخفي، موجود بس عشان أي كود قديم بيشاور على toolbar يفضل يتبني
    val toolbar: Toolbar get() = binding.toolbar

    abstract val titleRes: Int

    private fun setUpSearchBar() {
        val bar = binding.searchBar
        val row = binding.listHeaderRow
        val list = binding.recyclerView

        // نفس عائلة لون أزرار الهوم، بس معتم عشان المحتوى اللي بيعدي تحته مايبانش
        val screenBg = MaterialColors.getColor(bar, com.google.android.material.R.attr.colorSurface)
        val pillColor = ColorUtils.compositeColors(
            ColorUtil.withAlpha(accentColor(), 0.12f),
            screenBg
        )
        searchPillColor = pillColor
        applySearchGlass()

        ViewCompat.setOnApplyWindowInsetsListener(requireView()) { _, insets ->
            statusBarTop = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            bar.updateLayoutParams<ViewGroup.MarginLayoutParams> { topMargin = pillTop() }
            row.updateLayoutParams<ViewGroup.MarginLayoutParams> { topMargin = rowTop() }
            list.invalidateItemDecorations()
            updateRowPosition()
            applyFastScrollerPadding()
            insets
        }
        ViewCompat.requestApplyInsets(requireView())

        // الضغط على الشريط -> البحث (نفس وجهة أيقونة البحث القديمة)
        bar.setOnClickListener {
            findNavController().navigate(R.id.action_search, null, navOptions)
        }

        // الهامبرغر يفتح الدراور
        binding.menuButton.setOnClickListener {
            mainActivity.openDrawer()
        }
    }

    private var searchPillColor = Color.TRANSPARENT

    /**
     * The search bar is a glass pill (same as the bottom bar and the mini player) that blurs the
     * list scrolling under it; with the glass setting off it keeps its plain coloured pill.
     */
    private fun applySearchGlass() {
        val b = _binding ?: return
        val layer = GlassHelper.syncFill(b.searchBar, b.recyclerView, PreferenceUtil.glassEnabled)
        b.searchBar.setCardBackgroundColor(if (layer != null) Color.TRANSPARENT else searchPillColor)
        layer?.onToneExtra = { tone -> applySearchInk(tone) }
        applySearchInk(if (layer != null && layer.sampled) layer.lightness else null)
    }

    /** Text and icon follow the brightness of the page behind the glass (null = theme colours). */
    private fun applySearchInk(tone: Float?) {
        val b = _binding ?: return
        if (!isAdded) return
        val ink = tone?.let { ColorUtils.blendARGB(Color.WHITE, GlassLayer.DARK_INK, it) }
        b.searchText.setTextColor(
            if (ink != null) ColorUtils.setAlphaComponent(ink, 200) else textColorSecondary()
        )
        b.menuButton.imageTintList = ink?.let { ColorStateList.valueOf(it) }
    }

    private fun setUpHeaderRow() {
        if (isShuffleVisible) {
            binding.shuffleButton.setOnClickListener {
                onShuffleClicked()
            }
        } else {
            binding.shuffleButton.isVisible = false
        }

        // ⋯ بيفتح PopupMenu متملي بنفس المنطق اللي كان بيملى toolbar.menu
        binding.moreButton.setOnClickListener { anchor ->
            val popup = GlassPopupMenu(requireContext(), anchor)
            onCreateMenu(popup.menu, popup.menuInflater)
            popup.setOnMenuItemClickListener { item -> onMenuItemSelected(item) }
            popup.show()
        }
    }

    // الصف بيتحرك مع القايمة: مكانه = أعلى القايمة - مقدار السكرول، فيطلع معاها
    private fun updateRowPosition() {
        val b = _binding ?: return
        val hideDistance = rowBottom().toFloat()
        val offset = b.recyclerView.computeVerticalScrollOffset().toFloat()
        b.listHeaderRow.translationY = -offset.coerceAtMost(hideDistance)
    }

    private fun updateCount() {
        val b = _binding ?: return
        val count = adapter?.itemCount ?: 0
        b.countText.text = "$count ${getString(titleRes).lowercase()}"
    }

    private fun setUpRecyclerView() {
        binding.recyclerView.apply {
            layoutManager = this@AbsRecyclerViewFragment.layoutManager
            adapter = this@AbsRecyclerViewFragment.adapter
            addItemDecoration(headerDecoration)
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    updateRowPosition()
                    val bar = _binding?.searchBar ?: return
                    bar.animate().cancel()
                    bar.translationY = if (recyclerView.computeVerticalScrollOffset() <= 0) {
                        0f
                    } else {
                        (bar.translationY - dy).coerceIn(-hideRange(), 0f)
                    }
                    bar.removeCallbacks(snapSearchBar)
                    bar.postDelayed(snapSearchBar, 120)
                }
            })
            // الـ FastScroller بيعتبر أي حدث layout/تحميل بيانات (حتى لو مفيش
            // سكرول حقيقي) إنه سكرول، فكان بيظهر لحظة مع فتح الصفحة أو
            // الرجوع من صفحة فنان. عشان كده مش بنعمله خالص لحد ما المستخدم
            // يلمس القايمة ويبدأ يسحبها فعليًا (SCROLL_STATE_DRAGGING) - وقتها
            // بس بنعمله. الـ listener بيشيل نفسه بعد أول مرة.
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                        recyclerView.removeOnScrollListener(this)
                        fastScroller = create(recyclerView)
                        applyFastScrollerPadding()
                    }
                }
            })
        }
    }

    // مسار الـ FastScroller يبدأ من أول عنصر (تحت شريط البحث والصف) بدل أعلى
    // الشاشة. باقي الأطراف بتفضل زي padding القايمة الأصلي.
    private fun applyFastScrollerPadding() {
        val list = _binding?.recyclerView ?: return
        fastScroller?.setPadding(
            list.paddingLeft,
            headerHeight(),
            list.paddingRight,
            list.paddingBottom
        )
    }

    protected open fun createFastScroller(recyclerView: RecyclerView): FastScroller {
        return FastScrollerBuilder(recyclerView).useMd2Style().build()
    }

    private fun initAdapter() {
        adapter = createAdapter()
        adapter?.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() {
                super.onChanged()
                checkIsEmpty()
                updateCount()
            }

            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                super.onItemRangeInserted(positionStart, itemCount)
                updateCount()
            }

            override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) {
                super.onItemRangeRemoved(positionStart, itemCount)
                updateCount()
            }
        })
    }

    protected open val emptyMessage: Int
        @StringRes get() = R.string.empty

    private fun getEmojiByUnicode(unicode: Int): String {
        return String(Character.toChars(unicode))
    }

    private fun checkIsEmpty() {
        binding.emptyText.setText(emptyMessage)
        binding.empty.isVisible = adapter!!.itemCount == 0
    }

    private var defaultBottomPadding = -1
    private var defaultClipToPadding = true

    private fun checkForMargins() {
        val list = binding.recyclerView
        if (defaultBottomPadding < 0) {
            defaultBottomPadding = list.paddingBottom
            defaultClipToPadding = list.clipToPadding
        }
        if (mainActivity.isBottomNavVisible) {
            if (PreferenceUtil.glassEnabled) {
                // Glass bar: the list keeps going under the floating bar and mini player (so there
                // is something to blur) instead of stopping above them. The padding lets the
                // last item scroll up clear of both.
                val inset = ViewCompat.getRootWindowInsets(list)
                    ?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0
                list.updateLayoutParams<ViewGroup.MarginLayoutParams> { bottomMargin = 0 }
                list.clipToPadding = false
                list.updatePadding(
                    bottom = defaultBottomPadding + dip(R.dimen.bottom_nav_mini_player_height) + inset
                )
            } else {
                list.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                    bottomMargin = dip(R.dimen.bottom_nav_height)
                }
                list.clipToPadding = defaultClipToPadding
                list.updatePadding(bottom = defaultBottomPadding)
            }
        }
    }

    private fun initLayoutManager() {
        layoutManager = createLayoutManager()
    }

    protected abstract fun createLayoutManager(): LM

    @NonNull
    protected abstract fun createAdapter(): A

    protected fun invalidateLayoutManager() {
        initLayoutManager()
        binding.recyclerView.layoutManager = layoutManager
    }

    protected fun invalidateAdapter() {
        initAdapter()
        checkIsEmpty()
        updateCount()
        binding.recyclerView.adapter = adapter
    }

    val recyclerView get() = binding.recyclerView

    val container get() = binding.root

    override fun scrollToTop() {
        recyclerView.scrollToPosition(0)
        binding.searchBar.animate().translationY(0f).setDuration(150).start()
        recyclerView.post { updateRowPosition() }
    }

    override fun onCreateMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_main, menu)
    }

    override fun onMenuItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_settings -> findNavController().navigate(
                R.id.settings_fragment,
                null,
                navOptions
            )
            R.id.action_import_playlist -> ImportPlaylistDialog().show(
                childFragmentManager,
                "ImportPlaylist"
            )
            R.id.action_add_to_playlist -> CreatePlaylistDialog.create(emptyList()).show(
                childFragmentManager,
                "ShowCreatePlaylistDialog"
            )
        }
        return false
    }

    override fun onResume() {
        super.onResume()
        checkForMargins()
        applySearchGlass()
    }

    override fun onDestroyView() {
        // بنسجل مكان السكرول قبل ما الـ View يتدمر، عشان نرجعه لما الـ View
        // الجديد يتبني (شوف restoreScrollState).
        savedRecyclerLayoutState = layoutManager?.onSaveInstanceState()
        _binding?.searchBar?.removeCallbacks(snapSearchBar)
        fastScroller = null
        super.onDestroyView()
        _binding = null
    }

    private fun restoreScrollState() {
        savedRecyclerLayoutState?.let { layoutManager?.onRestoreInstanceState(it) }
    }

    override fun onPause() {
        super.onPause()
        (adapter as? AbsMultiSelectAdapter<*, *>)?.actionMode?.finish()
    }
}
