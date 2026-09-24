package code.name.monkey.retromusic.views

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * View بترسم ظل ناعم حقيقي (blur فعلي عن طريق BlurMaskFilter) حوالين
 * مستطيل بحواف دائرية، بدل طبقات layer-list اللي بتبان كحلقات/درجات
 * واضحة (banding) خصوصًا فوق خلفية مصمتة زي الصفحة دي.
 *
 * لازم Software layer عشان BlurMaskFilter مش بيشتغل مع hardware
 * acceleration - العملية نفسها رخيصة (بتحصل مرة وقت الرسم، مش في لوب).
 */
class SoftShadowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var shadowColor: Int = DEFAULT_SHADOW_COLOR
        set(value) {
            field = value
            shadowPaint.color = value
            invalidate()
        }

    var cornerRadiusPx: Float = 0f
        set(value) {
            field = value
            invalidate()
        }

    var blurRadiusPx: Float = DEFAULT_BLUR_RADIUS_PX
        set(value) {
            field = value
            shadowPaint.maskFilter = if (value > 0f) {
                BlurMaskFilter(value, BlurMaskFilter.Blur.NORMAL)
            } else {
                null
            }
            invalidate()
        }

    // المسافة بين حواف الـ View وحواف المستطيل اللي بيترسم - لازم تكون
    // View أكبر من الشكل المرسوم بمقدار كافٍ عشان الـ blur يتنفس من غير
    // ما يتقص عند حدود الـ View نفسها.
    var shadowInsetPx: Float = 0f
        set(value) {
            field = value
            invalidate()
        }

    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = DEFAULT_SHADOW_COLOR
        maskFilter = BlurMaskFilter(DEFAULT_BLUR_RADIUS_PX, BlurMaskFilter.Blur.NORMAL)
    }
    private val shadowRect = RectF()

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, shadowPaint)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        shadowRect.set(
            shadowInsetPx,
            shadowInsetPx,
            width - shadowInsetPx,
            height - shadowInsetPx
        )
        canvas.drawRoundRect(shadowRect, cornerRadiusPx, cornerRadiusPx, shadowPaint)
    }

    companion object {
        private const val DEFAULT_SHADOW_COLOR = 0x33000000
        private const val DEFAULT_BLUR_RADIUS_PX = 24f
    }
}
