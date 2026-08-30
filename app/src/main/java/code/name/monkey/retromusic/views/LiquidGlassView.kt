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
        strokeWidth = 1.5f * resources.displayMetrics.density 
    }

    fun setBackdropColor(color: Int) {
        this.backdropColor = color
        setupPaints()
        invalidate()
    }

    private fun setupPaints() {
        if (width == 0 || height == 0) return

        // 1. تحديد ما إذا كانت الخلفية فاتحة أم غامقة
        val isLight = ColorUtils.calculateLuminance(backdropColor) > 0.45f
        
        // السر هنا: استخدام الأبيض أو الأسود الشفاف لخلق تباين الزجاج، وليس لون الخلفية نفسه
        val glassBaseColor = if (isLight) Color.BLACK else Color.WHITE

        // 2. لون التعبئة (جسم الزجاج): أبيض/أسود بشفافية 15% (القيمة 38 من 255)
        // هذا ما يجعل الزر يبرز كطبقة زجاجية مضيئة فوق الخلفية
        fillPaint.color = ColorUtils.setAlphaComponent(glassBaseColor, 38) 

        // 3. تدرج الإطار (اللمعة الجانبية)
        val topBorderColor = ColorUtils.setAlphaComponent(glassBaseColor, 80)
        val bottomBorderColor = ColorUtils.setAlphaComponent(glassBaseColor, 15) 
        
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
        
        val radius = if (cornerRadiusPx > 0f) cornerRadiusPx else height / 2f
        
        canvas.drawRoundRect(rect, radius, radius, fillPaint)
        
        val inset = borderPaint.strokeWidth / 2f
        val borderRect = RectF(inset, inset, width - inset, height - inset)
        val borderRadius = radius - inset
        
        canvas.drawRoundRect(borderRect, borderRadius, borderRadius, borderPaint)
    }
}
