package code.name.monkey.retromusic.fragments.artists

import android.animation.Animator
import android.animation.ObjectAnimator
import android.transition.TransitionValues
import android.transition.Visibility
import android.view.View
import android.view.ViewGroup

/**
 * ترانزيشن مشتركة: حركة Translation بس (من غير أي Alpha/Fade) عشان نتجنب
 * مشكلة الوميض الأسود اللي بتحصل مع أي أنيميشن بيعمل animate على الشفافية.
 * مستخدمة في AbsArtistDetailsFragment و ArtistAllSongsFragment.
 */
internal class TranslateOnly(
    private val slideDistance: Int,
    private val forward: Boolean
) : Visibility() {

    override fun onAppear(
        sceneRoot: ViewGroup,
        view: View,
        startValues: TransitionValues?,
        endValues: TransitionValues?
    ): Animator {
        val offset = if (forward) slideDistance.toFloat() else -slideDistance.toFloat()
        view.translationY = offset
        return ObjectAnimator.ofFloat(view, View.TRANSLATION_Y, offset, 0f)
    }

    override fun onDisappear(
        sceneRoot: ViewGroup,
        view: View,
        startValues: TransitionValues?,
        endValues: TransitionValues?
    ): Animator {
        val offset = if (forward) -slideDistance.toFloat() else slideDistance.toFloat()
        return ObjectAnimator.ofFloat(view, View.TRANSLATION_Y, 0f, offset)
    }
}
