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
 * طبقة "زجاج" خفيفة ومحسنة
 * تعتمد على شفافية بسيطة (10%) وحواف رفيعة جداً لتعطي إحساساً بالأناقة بدون استهلاك الموارد.
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
        // تقليل سمك الحافة لتكون أرفع وأكثر أناقة بناءً على طلبك
        strokeWidth = 0.8f * resources.displayMetrics.density 
    }

    fun setBackdropColor(color: Int) {
        this.backdropColor = color
        setupPaints()
        invalidate()
    }

    private fun setupPaints() {
        if (width == 0 || height == 0) return

        val isLight = ColorUtils.calculateLuminance(backdropColor) > 0.45f
        val glassBaseColor = if (isLight) Color.BLACK else Color.WHITE

        // الشفافية 10% (القيمة 25 من أصل 255) ليكون اللون هادئ جداً
        fillPaint.color = ColorUtils.setAlphaComponent(glassBaseColor, 20) 

        // تدرج الإطار (اللمعة الجانبية)
        val topBorderColor = ColorUtils.setAlphaComponent(glassBaseColor, 65) // لمعة علوية أنعم
        val bottomBorderColor = ColorUtils.setAlphaComponent(glassBaseColor, 10) // خفوت سفلي
        
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
        
        // رسم جسم الزجاج
        canvas.drawRoundRect(rect, radius, radius, fillPaint)
        
        // رسم الإطار اللامع الرفيع
        val inset = borderPaint.strokeWidth / 2f
        val borderRect = RectF(inset, inset, width - inset, height - inset)
        val borderRadius = radius - inset
        
        canvas.drawRoundRect(borderRect, borderRadius, borderRadius, borderPaint)
    }
}
