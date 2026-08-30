package code.name.monkey.retromusic.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.ColorUtils

/**
 * طبقة "زجاج حقيقي" محسنة (Apple Music iOS 26 Style)
 * تعتمد هذه الطريقة على الشفافية الدقيقة والإضاءة الجانبية المتدرجة (Rim Light) 
 * مع استخدام لون الصفحة الأساسي كخلفية للزجاج ليتماشى مع تصميم الواجهة.
 */
class LiquidGlassView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var cornerRadiusPx: Float = 0f
        set(value) {
            field = value
            invalidate()
        }

    private var backdropColor: Int = Color.DKGRAY
    private val rect = RectF()

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        // سمك الإطار الرفيع جداً يعطي إحساس انعكاس الضوء القوي على حرف الزجاج
        strokeWidth = 1.5f * resources.displayMetrics.density 
    }

    fun setBackdropColor(color: Int) {
        this.backdropColor = color
        setupPaints()
        invalidate()
    }

    private fun setupPaints() {
        if (width == 0 || height == 0) return

        // 1. استخدام لون الصفحة بالكامل (backdropColor) ليكون هو لون الزجاج
        // الشفافية 15% (القيمة 38 من أصل 255)
        fillPaint.color = ColorUtils.setAlphaComponent(backdropColor, 38) 

        // 2. تدرج الإطار (اللمعة الجانبية)
        // نستخدم الأبيض أو الأسود للإطار للحفاظ على انعكاس الضوء على حافة الزجاج
        val isLight = ColorUtils.calculateLuminance(backdropColor) > 0.45f
        val rimColor = if (isLight) Color.BLACK else Color.WHITE
        
        val topBorderColor = ColorUtils.setAlphaComponent(rimColor, 80) // لمعة واضحة من أعلى
        val bottomBorderColor = ColorUtils.setAlphaComponent(rimColor, 15) // خفوت من أسفل
        
        borderPaint.shader = LinearGradient(
            0f, 0f, 0f, height.toFloat(),
            topBorderColor, bottomBorderColor,
            Shader.TileMode.CLAMP
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rect.set(0f, 0f, w.toFloat(), h.toFloat())
        setupPaints() 
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        // إذا كانت القيمة 0، نجعله دائرياً بالكامل (للأزرار الثلاثة)، وإلا نستخدم القيمة المحددة (لمستطيل الـ Biography)
        val radius = if (cornerRadiusPx > 0f) cornerRadiusPx else height / 2f
        
        // 1. رسم جسم الزجاج الشفاف
        canvas.drawRoundRect(rect, radius, radius, fillPaint)
        
        // 2. رسم الإطار اللامع (نقوم بتصغيره قليلاً لكي لا يتم قص الإطار من خارج الحدود)
        val inset = borderPaint.strokeWidth / 2f
        val borderRect = RectF(inset, inset, width - inset, height - inset)
        val borderRadius = radius - inset
        
        canvas.drawRoundRect(borderRect, borderRadius, borderRadius, borderPaint)
    }
}
