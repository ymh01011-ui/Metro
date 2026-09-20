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

import android.os.Bundle
import android.os.Parcelable
import android.util.TypedValue
import android.view.*
import android.widget.ImageView
import androidx.annotation.NonNull
import androidx.annotation.StringRes
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.Toolbar
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.content.ContextCompat
import androidx.core.view.doOnPreDraw
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.RecyclerView
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.adapter.base.AbsMultiSelectAdapter
import code.name.monkey.retromusic.databinding.FragmentMainRecyclerBinding
import code.name.monkey.retromusic.dialogs.CreatePlaylistDialog
import code.name.monkey.retromusic.dialogs.ImportPlaylistDialog
import code.name.monkey.retromusic.extensions.accentColor
import code.name.monkey.retromusic.extensions.dip
import code.name.monkey.retromusic.interfaces.IScrollHelper
import code.name.monkey.retromusic.util.PreferenceUtil
import code.name.monkey.retromusic.util.ThemedFastScroller.create
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.transition.MaterialFadeThrough
import me.zhanghai.android.fastscroll.FastScroller
import me.zhanghai.android.fastscroll.FastScrollerBuilder

abstract class AbsRecyclerViewFragment<A : RecyclerView.Adapter<*>, LM : RecyclerView.LayoutManager> :
    AbsMainActivityFragment(R.layout.fragment_main_recycler), IScrollHelper {

    // *الحل الجذري*: المشكلة الحقيقية طول الوقت كانت إن toolbar.menu فيه
    // عناصر حقيقية (Sort, Grid, إلخ) بـ showAsAction="never" - وده اللي
    // بيخلي الـ ActionMenuView تحجز مساحة زرار overflow حقيقي، بغض النظر عن
    // شفافية أيقونته. أي حل كان بيحاول "يتحايل" حوالين المساحة المحجوزة دي
    // (margin سالب، قياس يدوي، sibling overlay) هيفضل عرضة لأي تغيير بسيط.
    //
    // الحل الصح: نوقف نظام الـ MenuProvider الحقيقي خالص (registersMenuProvider
    // = false تحت)، ونستخدم PopupMenu منفصل تمامًا بدل toolbar.menu. كده
    // toolbar.menu بيفضل فاضي طول الوقت - الـ ActionMenuView عمرها ما هتحجز
    // أي مساحة، وأيقونتنا بتاخد مكانها الصح تلقائيًا من نظام التمركز العادي
    // بتاع الـ Toolbar (بالظبط زي زرار البحث) من غير أي حسابات يدوية.
    // onCreateMenu/onMenuItemSelected لسه نفسهم (فـ ArtistsFragment ومشتقاتها
    // مش محتاجين يتغيروا خالص) - بس بننده عليهم إحنا يدوي على منيو الـ
    // PopupMenu بدل ما الإطار ينده عليهم على toolbar.menu.
    override val registersMenuProvider: Boolean = false

    private var _binding: FragmentMainRecyclerBinding? = null
    private val binding get() = _binding!!
    protected var adapter: A? = null
    protected var layoutManager: LM? = null
    val shuffleButton get() = binding.shuffleButton
    abstract val isShuffleVisible: Boolean

    private var customOverflowIcon: ImageView? = null

    // كل مرة تدخل صفحة فنان وترجع، الـ Navigation Component بيدمر View
    // الصفحة دي بالكامل ويبنيه تاني من الصفر (نفس نسخة الـ Fragment
    // نفسها بس View جديد) - يعني الـ LayoutManager والـ AppBarLayout
    // بيرجعوا لحالتهم الافتراضية (سكرول = صفر، بار مفتوح بالكامل)، حتى لو
    // كنت لسه نازل تحت قبل ما تفتح صفحة الفنان. بنخزن مكان السكرول وحالة
    // البار هنا (خصائص على مستوى الـ Fragment نفسه، فبتفضل موجودة حتى لو
    // الـ View اتدمر) وبنرجعهم تاني لما الـ View الجديد يتبني.
    private var savedRecyclerLayoutState: Parcelable? = null
    private var savedAppBarOffset: Int? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentMainRecyclerBinding.bind(view)
        postponeEnterTransition()
        view.doOnPreDraw { startPostponedEnterTransition() }
        enterTransition = MaterialFadeThrough().addTarget(binding.recyclerView)
        reenterTransition = MaterialFadeThrough().addTarget(binding.recyclerView)
        mainActivity.setSupportActionBar(toolbar)
        mainActivity.supportActionBar?.title = null
        initLayoutManager()
        initAdapter()
        checkForMargins()
        setUpRecyclerView()
        setupToolbar()
        setUpCustomOverflowIcon()
        restoreScrollAndAppBarState()
        binding.shuffleButton.fitsSystemWindows = PreferenceUtil.isFullScreenMode
        // Add listeners when shuffle is visible
        if (isShuffleVisible) {
            binding.recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    super.onScrolled(recyclerView, dx, dy)
                    if (dy > 0) {
                        binding.shuffleButton.hide()
                    } else if (dy < 0) {
                        binding.shuffleButton.show()
                    }

                }
            })
            binding.shuffleButton.apply {
                setOnClickListener {
                    onShuffleClicked()
                }
                accentColor()
            }
        } else {
            binding.shuffleButton.isVisible = false
        }
        libraryViewModel.getFabMargin().observe(viewLifecycleOwner) {
            binding.shuffleButton.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = it
            }
        }
    }

    open fun onShuffleClicked() {
    }

    val toolbar: Toolbar get() = binding.appBarLayout.toolbar

    private fun setupToolbar() {
        toolbar.setNavigationOnClickListener {
            findNavController().navigate(
                R.id.action_search,
                null,
                navOptions
            )
        }
        val appName = resources.getString(titleRes)
        binding.appBarLayout.title = appName
    }

    abstract val titleRes: Int

    private fun setUpRecyclerView() {
        binding.recyclerView.apply {
            layoutManager = this@AbsRecyclerViewFragment.layoutManager
            adapter = this@AbsRecyclerViewFragment.adapter
            // الـ FastScroller بيعتبر أي حدث layout/تحميل بيانات (حتى لو مفيش
            // سكرول حقيقي) إنه سكرول، فكان بيظهر لحظة مع فتح الصفحة أو
            // الرجوع من صفحة فنان (لأن الـ View بيتبني من الصفر وبيانات
            // الفنانين بتتحمل وبيترجع مكان السكرول). عشان كده مش بنعمله
            // خالص لحد ما المستخدم يلمس القايمة ويبدأ يسحبها فعليًا
            // (SCROLL_STATE_DRAGGING) - وقتها بس بنعمله، فبيظهر مع السكرول
            // الحقيقي بس. الـ listener بيشيل نفسه بعد أول مرة.
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                        recyclerView.removeOnScrollListener(this)
                        create(recyclerView)
                    }
                }
            })
        }
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

    private fun checkForMargins() {
        if (mainActivity.isBottomNavVisible) {
            binding.recyclerView.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = dip(R.dimen.bottom_nav_height)
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
        binding.recyclerView.adapter = adapter
    }

    val recyclerView get() = binding.recyclerView

    val container get() = binding.root

    override fun scrollToTop() {
        recyclerView.scrollToPosition(0)
        binding.appBarLayout.setExpanded(true, true)
    }

    // *الحل الجذري*: toolbar.menu دلوقتي بيفضل فاضي طول الوقت (شوف الشرح
    // فوق الكلاس)، يعني الـ ActionMenuView مش هتحجز أي مساحة overflow
    // خالص. فمجرد ما نضيف الأيقونة كـ child عادي بـ Gravity.END مع
    // CENTER_VERTICAL، الـ Toolbar بيحطها في مكانها الصح تلقائيًا - أفقيًا
    // ورأسيًا - بنفس نظام التمركز اللي بيحط زرار البحث في مكانه، من غير أي
    // قياس يدوي، margin سالب، أو sibling overlay. وبما إنها جزء حقيقي من
    // نفس الـ View، بتتحرك مع أي سكرول/إخفاء تلقائيًا.
    //
    // الضغط عليها بيفتح PopupMenu منفصل (مش toolbar.showOverflowMenu())،
    // وبنملاه بنفس المنطق اللي كان بيملى toolbar.menu بالظبط
    // (onCreateMenu/onMenuItemSelected) - يعني ArtistsFragment ومشتقاتها
    // مش محتاجين أي تعديل خالص، أوامرهم بتتنفذ زي ما هي.
    //
    // عشان ناخد شكل أيقونة الـ overflow الافتراضية بتاعة الثيم (من غير ما
    // نخمن اسم resource)، بنضيف عنصر وهمي فاضي مؤقت لحد ما نلاقي شكلها،
    // وبعدين بنمسحه فورًا - toolbar.menu بيرجع فاضي زي ما كان.
    private fun setUpCustomOverflowIcon() {
        if (customOverflowIcon != null) return

        val probeMenu = toolbar.menu
        probeMenu.add(0, View.NO_ID, 0, "").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        val clonedIcon = toolbar.overflowIcon?.constantState?.newDrawable(resources)?.mutate()
        probeMenu.clear()
        if (clonedIcon == null) return

        val sizePx = (48 * resources.displayMetrics.density).toInt()
        val paddingPx = (12 * resources.displayMetrics.density).toInt()
        val backgroundTypedValue = TypedValue()
        requireContext().theme.resolveAttribute(
            android.R.attr.selectableItemBackgroundBorderless, backgroundTypedValue, true
        )

        val imageView = ImageView(requireContext()).apply {
            setImageDrawable(clonedIcon)
            setPadding(paddingPx, paddingPx, paddingPx, paddingPx)
            isClickable = true
            isFocusable = true
            if (backgroundTypedValue.resourceId != 0) {
                background = ContextCompat.getDrawable(requireContext(), backgroundTypedValue.resourceId)
            }
            layoutParams = Toolbar.LayoutParams(sizePx, sizePx).apply {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
            }
            setOnClickListener {
                val popup = PopupMenu(requireContext(), this)
                onCreateMenu(popup.menu, popup.menuInflater)
                popup.setOnMenuItemClickListener { item -> onMenuItemSelected(item) }
                popup.show()
            }
        }
        toolbar.addView(imageView)
        customOverflowIcon = imageView
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
    }

    override fun onDestroyView() {
        // بنسجل مكان السكرول وحالة البار قبل ما الـ View يتدمر خالص، عشان
        // نرجعهم لما الـ View الجديد يتبني (شوف restoreScrollAndAppBarState).
        savedRecyclerLayoutState = layoutManager?.onSaveInstanceState()
        savedAppBarOffset = ((binding.appBarLayout.layoutParams as? CoordinatorLayout.LayoutParams)
            ?.behavior as? AppBarLayout.Behavior)?.topAndBottomOffset
        super.onDestroyView()
        customOverflowIcon = null
        _binding = null
    }

    private fun restoreScrollAndAppBarState() {
        savedRecyclerLayoutState?.let { layoutManager?.onRestoreInstanceState(it) }
        val offset = savedAppBarOffset ?: return
        // لازم نستنى الـ Behavior يتربط بالـ View الجديد الأول (بيحصل في
        // الـ layout pass)، فبنأجلها لحد بعد أول رسم.
        binding.appBarLayout.doOnPreDraw {
            val params = binding.appBarLayout.layoutParams as? CoordinatorLayout.LayoutParams
            (params?.behavior as? AppBarLayout.Behavior)?.topAndBottomOffset = offset
        }
    }

    override fun onPause() {
        super.onPause()
        (adapter as? AbsMultiSelectAdapter<*, *>)?.actionMode?.finish()
    }
}
