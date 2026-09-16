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

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.*
import android.widget.ImageView
import androidx.annotation.NonNull
import androidx.annotation.StringRes
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.view.doOnPreDraw
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.RecyclerView
import code.name.monkey.appthemehelper.common.ATHToolbarActivity
import code.name.monkey.appthemehelper.util.ToolbarContentTintHelper
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
import com.google.android.material.transition.MaterialFadeThrough
import me.zhanghai.android.fastscroll.FastScroller
import me.zhanghai.android.fastscroll.FastScrollerBuilder

abstract class AbsRecyclerViewFragment<A : RecyclerView.Adapter<*>, LM : RecyclerView.LayoutManager> :
    AbsMainActivityFragment(R.layout.fragment_main_recycler), IScrollHelper {

    private var _binding: FragmentMainRecyclerBinding? = null
    private val binding get() = _binding!!
    protected var adapter: A? = null
    protected var layoutManager: LM? = null
    val shuffleButton get() = binding.shuffleButton
    abstract val isShuffleVisible: Boolean

    // النقط الحقيقية (overflow icon) بتاعة الـ Options Menu بتتشال فورًا لحظة
    // ما view الفراجمنت يتدمر (لأنها مربوطة بحالة الـ MenuProvider، اللي
    // مرتبطة بالـ Lifecycle.State.STARTED) - قبل ما أنيميشن الخروج
    // (View Animation) حتى يبدأ يتحرك، فبتبان بتختفي فجأة. الـ ImageView دي
    // إحنا حاطينها كـ child عادي جوه التولبار نفسه (مش مربوطة بالـ MenuProvider
    // خالص)، فبتفضل ظاهرة طول ما الـ View نفسه لسه موجود على الشاشة - يعني
    // طول مدة الأنيميشن كاملة، وبتختفي بس لما الـ View فعليًا يتشال.
    private var customOverflowIcon: ImageView? = null

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
            create(this)
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

    override fun onPrepareMenu(menu: Menu) {
        ToolbarContentTintHelper.handleOnPrepareOptionsMenu(requireActivity(), toolbar)
        setUpCustomOverflowIcon()
    }

    // نفس المشكلة اللي في صفحة تفاصيل الفنان بالظبط: الـ Toolbar بيحجز مساحة
    // لزرار الـ overflow الحقيقي (ActionMenuView) بناءً على وجود العناصر
    // نفسها، مش على شفافية الأيقونة - فأي View بنضيفها جوه التولبار بتتحط
    // *قبل* المساحة دي، مش في نهايته فعليًا. فبنحط أيقونتنا كـ sibling
    // للـ appBarLayout جوه الـ CoordinatorLayout (مش جوه التولبار خالص)،
    // ونحسب مكانها بالقياس المباشر (getLocationInWindow) من التولبار الحقيقي.
    // وبما إنها فوقه في الـ z-order، أي ضغطة في مكانها هتوصلها هي الأول -
    // فمش محتاجين "نلاقي" الزرار الحقيقي ونعطله يدوي، بيبقى تلقائيًا مقفول
    // عمليًا تحتها.
    //
    // بننسخ الـ Drawable الحقيقي بعد ما الـ ToolbarContentTintHelper يخلص
    // تلوينه (constantState.newDrawable()) - كده مش محتاجين نعرف إزاي
    // المكتبة لونته بالظبط. النسخة دي بتتاخد مرة واحدة بس؛ لو الثيم اتغير
    // بعد كده (نهاري/ليلي) هي مش هتتحدث تلقائي، بس ده تنازل مقبول.
    private fun setUpCustomOverflowIcon() {
        if (customOverflowIcon != null) return
        val realIcon = toolbar.overflowIcon ?: return
        val clonedIcon = realIcon.constantState?.newDrawable(resources)?.mutate() ?: return

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
            setOnClickListener { toolbar.showOverflowMenu() }
        }

        val rootLayout = binding.root
        rootLayout.addView(imageView, sizePx, sizePx)
        imageView.bringToFront()
        // bringToFront() بترتب مكانها في شجرة الـ views بس، لكن الـ AppBarLayout
        // عنده elevation (بيتغير مع السكرول)، والـ Z-ordering الحقيقي وقت
        // الرسم (hardware acceleration) بيحترم الـ elevation مش ترتيب الشجرة -
        // فكان بيرسم فوق أيقونتنا برغم bringToFront(). بنديها elevation أعلى
        // بشكل واضح عشان تفضل هي الأعلى مهما الـ AppBarLayout اتغير.
        imageView.elevation = (16 * resources.displayMetrics.density) +
            (binding.appBarLayout.elevation)
        customOverflowIcon = imageView
        toolbar.overflowIcon = ColorDrawable(Color.TRANSPARENT)

        fun repositionOverImageView() {
            val toolbarLoc = IntArray(2)
            toolbar.getLocationInWindow(toolbarLoc)
            val rootLoc = IntArray(2)
            rootLayout.getLocationInWindow(rootLoc)
            // بنتمركز في نص "منطقة المحتوى الحقيقية" بتاعة التولبار (من غير
            // أي padding علوي/سفلي - زي padding الـ status bar لو متحط على
            // التولبار نفسه مش على الـ appBarLayout)، مش نص ارتفاعه الكامل -
            // عشان كده كانت الأيقونة نازلة شوية عن مكانها الطبيعي.
            val contentTop = toolbar.paddingTop
            val contentHeight = toolbar.height - toolbar.paddingTop - toolbar.paddingBottom
            imageView.translationX =
                (toolbarLoc[0] - rootLoc[0] + toolbar.width - sizePx).toFloat()
            imageView.translationY =
                (toolbarLoc[1] - rootLoc[1] + contentTop + (contentHeight - sizePx) / 2).toFloat()
        }

        // على عكس صفحة تفاصيل الفنان (اللي التولبار بتاعها عايم ثابت مكانه
        // طول الوقت)، التولبار هنا بيتحرك/يختفي مع السكرول (زي زرار البحث
        // بالظبط، اللي هو جزء أصلي من نفس التولبار). التحريك ده بيحصل عن
        // طريق الـ AppBarLayout.Behavior وهو بيغير مكانه كل فريم من غير ما
        // يعمل layout pass كامل - يعني addOnLayoutChangeListener لوحده مش
        // هيمسك كل حركة. فبنستخدم OnPreDrawListener عشان يعيد حساب المكان
        // *قبل كل فريم رسم*، فيتحرك مع التولبار بالظبط لحظة بلحظة.
        val preDrawListener = ViewTreeObserver.OnPreDrawListener {
            repositionOverImageView()
            true
        }
        rootLayout.viewTreeObserver.addOnPreDrawListener(preDrawListener)
        imageView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) {
                rootLayout.viewTreeObserver.removeOnPreDrawListener(preDrawListener)
            }
        })
        toolbar.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> repositionOverImageView() }
        toolbar.post { repositionOverImageView() }
    }

    override fun onCreateMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_main, menu)
        ToolbarContentTintHelper.handleOnCreateOptionsMenu(
            requireContext(),
            toolbar,
            menu,
            ATHToolbarActivity.getToolbarBackgroundColor(toolbar)
        )
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
        super.onDestroyView()
        customOverflowIcon = null
        _binding = null
    }

    override fun onPause() {
        super.onPause()
        (adapter as? AbsMultiSelectAdapter<*, *>)?.actionMode?.finish()
    }
}
