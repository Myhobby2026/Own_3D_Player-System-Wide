package com.neonplayer.systemwide.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Rounded neon knob control used for mix levels (Virtual 3D, Surround 7.1) and
 * other effect strengths. Value range: 0..100.
 */
class NeonKnobView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    interface OnValueChangeListener {
        fun onValueChanged(value: Int, fromUser: Boolean)
    }

    var value: Int = 50
        set(v) {
            val clamped = v.coerceIn(0, 100)
            if (field != clamped) {
                field = clamped
                invalidate()
            }
        }

    var label: String = ""
        set(v) { field = v; invalidate() }

    var accentColor: Int = Color.parseColor("#00F0FF")
        set(v) { field = v; buildPaints(); invalidate() }

    var onValueChangeListener: OnValueChangeListener? = null

    private val arcRect = RectF()
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#1A2F45")
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E6F7FF")
        textAlign = Paint.Align.CENTER
        fakeBoldText = true
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8AA4BF")
        textAlign = Paint.Align.CENTER
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val startAngle = 135f
    private val sweepMax = 270f

    init {
        isClickable = true
        isFocusable = true
        buildPaints()
    }

    private fun buildPaints() {
        valuePaint.color = accentColor
        glowPaint.color = Color.argb(70, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor))
        dotPaint.color = accentColor
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desired = dp(132)
        val w = resolveSize(desired, widthMeasureSpec)
        val h = resolveSize(desired + dp(24), heightMeasureSpec)
        val size = min(w, h - dp(20))
        setMeasuredDimension(size + dp(16), size + dp(28))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val radius = min(width, height - dp(20)) / 2f - dp(10)
        val cy = radius + dp(10)
        val stroke = radius * 0.14f

        arcRect.set(cx - radius, cy - radius, cx + radius, cy + radius)
        trackPaint.strokeWidth = stroke
        valuePaint.strokeWidth = stroke
        glowPaint.strokeWidth = stroke * 2.2f

        // Track
        canvas.drawArc(arcRect, startAngle, sweepMax, false, trackPaint)

        // Value arc
        val sweep = sweepMax * (value / 100f)
        if (sweep > 0f) {
            canvas.drawArc(arcRect, startAngle, sweep, false, glowPaint)
            canvas.drawArc(arcRect, startAngle, sweep, false, valuePaint)
        }

        // Indicator dot
        val angle = Math.toRadians((startAngle + sweep).toDouble())
        val dotX = cx + cos(angle).toFloat() * radius
        val dotY = cy + sin(angle).toFloat() * radius
        dotPaint.setShadowLayer(dp(6).toFloat(), 0f, 0f, accentColor)
        setLayerType(LAYER_TYPE_SOFTWARE, dotPaint)
        canvas.drawCircle(dotX, dotY, stroke * 0.55f, dotPaint)

        // Center value
        textPaint.textSize = radius * 0.42f
        canvas.drawText(value.toString(), cx, cy + textPaint.textSize * 0.35f, textPaint)

        // Label
        if (label.isNotEmpty()) {
            labelPaint.textSize = dp(11).toFloat()
            labelPaint.color = Color.parseColor("#8AA4BF")
            canvas.drawText(label, cx, height - dp(4).toFloat(), labelPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                updateFromTouch(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_UP -> {
                updateFromTouch(event.x, event.y)
                performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun updateFromTouch(x: Float, y: Float) {
        val cx = width / 2f
        val radius = min(width, height - dp(20)) / 2f - dp(10)
        val cy = radius + dp(10)
        val dx = x - cx
        val dy = y - cy
        var deg = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
        // Normalize to 0..360 starting at startAngle
        var rel = deg - startAngle
        while (rel < 0) rel += 360f
        rel %= 360f
        if (rel > sweepMax) {
            // Snap to nearest end
            val distToEnd = rel - sweepMax
            val distToStart = 360f - rel
            rel = if (distToEnd < distToStart) sweepMax else 0f
        }
        val newValue = ((rel / sweepMax) * 100f).roundToInt().coerceIn(0, 100)
        if (newValue != value) {
            value = newValue
            onValueChangeListener?.onValueChanged(value, true)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()
}
