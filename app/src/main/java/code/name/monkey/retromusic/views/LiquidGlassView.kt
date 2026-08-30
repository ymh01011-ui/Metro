package code.name.monkey.retromusic.views

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.util.AttributeSet
import androidx.annotation.RequiresApi
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.graphics.ColorUtils

/**
 * طبقة "زجاج حقيقي" (Liquid Glass) بالطريقة الصح، مش لون شفاف ثابت زي الطريقة
 * القديمة (backgroundTint بشفافية %30 بس). هنا بنعمل فعليًا:
 *
 * 1. بيتمان (bitmap) بلون خلفية الصفحة الحقيقي (dominantBackgroundColor) مع
 *    تدرج بسيط بيدّي إحساس عمق.
 * 2. Gaussian blur حقيقي بمعالجة الـ GPU عن طريق RenderEffect.createBlurEffect
 *    (Android 12+ / API 31+) - مش تمويه مرسوم يدويًا.
 * 3. في Android 13+ (API 33+) بنضيف فوق البلور شيدر AGSL حقيقي (RuntimeShader)
 *    بيحسب Signed Distance Field لشكل الزجاج (دايرة أو مستطيل بحواف دائرية)
 *    وبيستخدمها عشان: (أ) يعمل انكسار خفيف (refraction) قرب الحواف زي عدسة
 *    محدبة، (ب) يفرّق قنوات الألوان الحمراء/الزرقاء بمقدار بسيط قرب الحافة
 *    (chromatic dispersion)، (ج) يرسم خط لمعان (specular rim) زي انعكاس ضوء
 *    على حرف الزجاج.
 *
 * على الأجهزة الأقدم من Android 12 بترجع تلقائيًا لتأثير مبسط (تدرج + خط
 * لمعان بس من غير بلور GPU) بدل ما تكسر.
 *
 * الاستخدام: حطها كأول Child جوه FrameLayout بنفس حجم الزر/الكارت اللي فوقها،
 * وحط العنصر القابل للضغط (MaterialButton / FAB / CardView) فوقها بخلفية
 * شفافة تمامًا. بعدين نادي setBackdropColor(dominantBackgroundColor) بعد ما
 * تستخرج لون الصفحة.
 */
class LiquidGlassView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    /** 0 = دايرة كاملة (نص الارتفاع)، أكبر من 0 = مستطيل بحواف دائرية بنفس القيمة (بالبكسل) */
    var cornerRadiusPx: Float = 0f
        set(value) {
            field = value
            render()
        }

    /** قوة البلور بالبكسل - القيم من 18 لـ 36 بتدي إحساس "زجاج مصنفر" واقعي */
    var blurRadiusPx: Float = 30f

    /** قوة الانكسار عند الحواف، من 0 (بدون) لـ 1 (قوي) */
    var refractionStrength: Float = 0.6f

    private var backdropColor: Int = Color.DKGRAY

    init {
        scaleType = ScaleType.FIT_XY
    }

    fun setBackdropColor(color: Int) {
        backdropColor = color
        render()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        render()
    }

    private fun render() {
        if (width <= 0 || height <= 0) return

        setImageBitmap(buildBaseBitmap())

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            setRenderEffect(buildRenderEffect())
        }
    }

    /** بيتمان أساسي بلون خلفية الصفحة الحقيقي + تدرج خفيف + خط لمعان على الحافة،
     * وده اللي بعدين بيتعمله البلور والشيدر فوقه */
    private fun buildBaseBitmap(): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val radius = if (cornerRadiusPx > 0f) cornerRadiusPx else height / 2f
        val rect = RectF(0f, 0f, width.toFloat(), height.toFloat())

        val gradientPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, 0f, height.toFloat(),
                ColorUtils.blendARGB(backdropColor, Color.WHITE, 0.22f),
                ColorUtils.blendARGB(backdropColor, Color.BLACK, 0.14f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRoundRect(rect, radius, radius, gradientPaint)

        // طبقة تلوين بيضاء خفيفة فوق التدرج، عشان يدي إحساس "الزجاج" مش لون عادي
        val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ColorUtils.setAlphaComponent(Color.WHITE, 46)
        }
        canvas.drawRoundRect(rect, radius, radius, tintPaint)

        // خط لمعان رفيع على حرف الزجاج (specular rim)
        val strokeWidth = 1.5f * resources.displayMetrics.density
        val inset = strokeWidth / 2f
        val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            this.strokeWidth = strokeWidth
            shader = LinearGradient(
                0f, 0f, 0f, height.toFloat(),
                intArrayOf(
                    Color.argb(150, 255, 255, 255),
                    Color.argb(20, 255, 255, 255),
                    Color.argb(80, 255, 255, 255)
                ),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRoundRect(
            RectF(inset, inset, width - inset, height - inset),
            radius - inset, radius - inset, rimPaint
        )

        return bmp
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun buildRenderEffect(): RenderEffect {
        val blur = RenderEffect.createBlurEffect(blurRadiusPx, blurRadiusPx, Shader.TileMode.CLAMP)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return blur
        }

        val shader = RuntimeShader(LIQUID_GLASS_AGSL)
        shader.setFloatUniform("resolution", width.toFloat(), height.toFloat())
        shader.setFloatUniform(
            "cornerRadius",
            if (cornerRadiusPx > 0f) cornerRadiusPx else height / 2f
        )
        shader.setFloatUniform("refraction", refractionStrength)

        val shaderEffect = RenderEffect.createRuntimeShaderEffect(shader, "content")
        // الشيدر بيشتغل على ناتج البلور (بلور الأول، وبعدين الانكسار والتشتت اللوني فوقه)
        return RenderEffect.createChainEffect(shaderEffect, blur)
    }

    companion object {
        // AGSL: بيحسب Signed Distance Field (SDF) لمستطيل بحواف دائرية، وبيستخدمها
        // عشان: (1) يزوّغ عينة المحتوى المموّه قرب الحواف لإحساس عدسة محدبة،
        // (2) يفرّق قنوات R/G/B بمقدار بسيط قرب الحافة (تشتت لوني حقيقي)،
        // (3) يضيف خط لمعان بيتحرك مع شكل الحافة.
        private const val LIQUID_GLASS_AGSL = """
            uniform shader content;
            uniform float2 resolution;
            uniform float cornerRadius;
            uniform float refraction;

            float roundedBoxSDF(float2 p, float2 halfSize, float radius) {
                float2 q = abs(p) - halfSize + radius;
                return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - radius;
            }

            half4 main(float2 fragCoord) {
                float2 center = resolution * 0.5;
                float2 p = fragCoord - center;
                float dist = roundedBoxSDF(p, center, cornerRadius);

                float edgeMask = 1.0 - smoothstep(-28.0, 0.0, dist);
                float2 dir = p / (length(p) + 0.0001);
                float bend = edgeMask * refraction * 9.0;

                half4 colR = content.eval(fragCoord - dir * (bend + 1.3));
                half4 colG = content.eval(fragCoord - dir * bend);
                half4 colB = content.eval(fragCoord - dir * (bend - 1.3));

                half4 col = half4(colR.r, colG.g, colB.b, colG.a);

                float rim = 1.0 - smoothstep(0.0, 3.0, abs(dist));
                col.rgb += rim * 0.2;

                return col;
            }
        """
    }
}
