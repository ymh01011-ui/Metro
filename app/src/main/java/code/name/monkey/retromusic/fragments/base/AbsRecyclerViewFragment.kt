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

    // في صفحة تفاصيل الفنان اللي اشتغلت صح، استخدمنا أسلوب الـ sibling
    // overlay (تحطها بره التولبار خالص وتقيس مكانها بـ getLocationInWindow)
    // + إعطائها elevation أعلى من الـ AppBarLayout. الأفقي والـ z-order في
    // الأسلوب ده كانوا مظبوطين هنا برضه من غير أي تعديل - المشكلة كانت في
    // حساب الارتفاع بس: افترضنا إن مركز التولبار = toolbar.height / 2، وده
    // صحيح لتولبار صفحة التفاصيل بس غلط هنا - الـ toolbar بتاع TopAppBarLayout
    // ظاهر إن ارتفاعه المقاس (toolbar.height) أكبر من الشريط الظاهر فعليًا
    // (سبب مش عارفينه من غير كود TopAppBarLayout نفسه)، فكل تخمين مبني عليه
    // (height، أو actionBarSize، أو طرح padding) طلع غلط بنفس القد تقريبًا.
    //
    // بدل ما نفضل نخمن رقم صحيح، بنسيب الـ Toolbar نفسه يحسبه لينا: بنضيف
    // View شفافة (INVISIBLE، مش GONE عشان تاخد مكانها في الحساب) جواه
    // كـ "مسبار" بس، بنديها Gravity.CENTER_VERTICAL زي أي عنصر حقيقي، وبعد
    // كده بنقرا مكانها الفعلي (getLocationInWindow) - ده نفس نظام التمركز
    // اللي بيحط زرار البحث في مكانه الصح، فمضمون يطلع صح هنا كمان من غير
    // أي تخمين ارتفاعات.
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

        // المسبار: مش هيتشاف خالص (INVISIBLE)، غرضه الوحيد إننا نقرا مكانه
        // بعد ما الـ Toolbar يتمركزه رأسيًا بنفسه.
        val probe = View(requireContext()).apply {
            visibility = View.INVISIBLE
            layoutParams = Toolbar.LayoutParams(1, sizePx).apply {
                gravity = Gravity.CENTER_VERTICAL
            }
        }
        toolbar.addView(probe)

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
        // نفس سبب إعطاء الـ elevation اللي اشتغل في صفحة تفاصيل الفنان:
        // الـ AppBarLayout عنده elevation بيتغير مع السكرول، والرسم الحقيقي
        // (hardware acceleration) بيحترم الـ elevation مش ترتيب الشجرة.
        imageView.elevation = (16 * resources.displayMetrics.density) +
            binding.appBarLayout.elevation
        customOverflowIcon = imageView
        toolbar.overflowIcon = ColorDrawable(Color.TRANSPARENT)

        fun repositionOverImageView() {
            val toolbarLoc = IntArray(2)
            toolbar.getLocationInWindow(toolbarLoc)
            val probeLoc = IntArray(2)
            probe.getLocationInWindow(probeLoc)
            val rootLoc = IntArray(2)
            rootLayout.getLocationInWindow(rootLoc)
            imageView.translationX =
                (toolbarLoc[0] - rootLoc[0] + toolbar.width - sizePx).toFloat()
            imageView.translationY = (probeLoc[1] - rootLoc[1]).toFloat()
        }

        // التولبار هنا بيتحرك/يختفي مع السكرول (زي زرار البحث)، والحركة دي
        // بتحصل كل فريم من غير layout pass كامل - فبنستخدم OnPreDrawListener
        // عشان يعيد الحساب قبل كل فريم رسم.
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
