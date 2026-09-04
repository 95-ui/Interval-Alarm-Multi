package com.example.intervalalarm.multi

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class CircularCountdownView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // Farben
    private val colorCyan = Color.parseColor("#00D9FF")
    private val colorYellow = Color.parseColor("#FFB800")
    private val colorRed = Color.parseColor("#FF3B5C")
    private val trackColor = Color.parseColor("#1a2744")
    private val bgColor = Color.parseColor("#16213E")

    // Paint Objekte
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 12f
        color = trackColor
    }

    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 12f
        strokeCap = Paint.Cap.ROUND
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 20f
        strokeCap = Paint.Cap.ROUND
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 28f
        color = Color.parseColor("#667788")
        textAlign = Paint.Align.CENTER
        letterSpacing = 0.15f
    }

    private val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 72f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.MONOSPACE
        isFakeBoldText = true
    }

    private val subLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 26f
        color = Color.parseColor("#667788")
        textAlign = Paint.Align.CENTER
    }

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = bgColor
    }

    // Zustand
    private var progress = 1f // 1.0 = voll, 0.0 = leer
    private var displayProgress = 1f
    private var timeText = "--:--"
    private var isActive = false
    private var currentColor = colorCyan
    private var animator: ValueAnimator? = null

    private val rect = RectF()

    fun setProgress(remaining: Long, total: Long) {
        if (total <= 0) {
            progress = 0f
            displayProgress = 0f
            timeText = "--:--"
            isActive = false
            invalidate()
            return
        }

        isActive = true
        val newProgress = (remaining.toFloat() / total.toFloat()).coerceIn(0f, 1f)

        // Sanfte Animation zum neuen Wert
        animator?.cancel()
        animator = ValueAnimator.ofFloat(displayProgress, newProgress).apply {
            duration = 900
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                displayProgress = anim.animatedValue as Float
                invalidate()
            }
            start()
        }

        progress = newProgress

        // Zeittext berechnen
        val hours = remaining / 3600000
        val mins = (remaining % 3600000) / 60000
        val secs = (remaining % 60000) / 1000
        timeText = if (hours > 0) {
            String.format("%02d:%02d:%02d", hours, mins, secs)
        } else {
            String.format("%02d:%02d", mins, secs)
        }

        // Farbe bestimmen
        currentColor = when {
            newProgress > 0.5f -> colorCyan
            newProgress > 0.2f -> colorYellow
            else -> colorRed
        }
    }

    fun reset() {
        animator?.cancel()
        progress = 0f
        displayProgress = 0f
        timeText = "--:--"
        isActive = false
        currentColor = colorCyan
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx = width / 2f
        val cy = height / 2f
        val radius = min(cx, cy) - 24f

        // Hintergrund-Kreis
        canvas.drawCircle(cx, cy, radius + 16f, bgPaint)

        // Track (Hintergrund-Ring)
        rect.set(cx - radius, cy - radius, cx + radius, cy + radius)
        canvas.drawArc(rect, 0f, 360f, false, trackPaint)

        // Tick-Markierungen (60 Striche wie eine Uhr)
        for (i in 0 until 60) {
            val angle = Math.toRadians((i * 6.0) - 90.0)
            val isMajor = i % 5 == 0
            val innerRadius = if (isMajor) radius - 28f else radius - 20f
            val outerRadius = radius - 14f

            tickPaint.strokeWidth = if (isMajor) 2f else 0.8f
            tickPaint.color = if (isMajor) Color.parseColor("#334466")
                              else Color.parseColor("#1e2d45")

            canvas.drawLine(
                cx + innerRadius * cos(angle).toFloat(),
                cy + innerRadius * sin(angle).toFloat(),
                cx + outerRadius * cos(angle).toFloat(),
                cy + outerRadius * sin(angle).toFloat(),
                tickPaint
            )
        }

        if (displayProgress > 0f) {
            val sweepAngle = 360f * displayProgress

            // Glow-Effekt (breiter, halbtransparent)
            glowPaint.color = Color.argb(40,
                Color.red(currentColor),
                Color.green(currentColor),
                Color.blue(currentColor)
            )
            canvas.drawArc(rect, -90f, sweepAngle, false, glowPaint)

            // Fortschritts-Ring
            progressPaint.color = currentColor
            progressPaint.shader = SweepGradient(
                cx, cy,
                intArrayOf(
                    Color.argb(80, Color.red(currentColor), Color.green(currentColor), Color.blue(currentColor)),
                    currentColor,
                    currentColor
                ),
                floatArrayOf(0f, 0.3f, 1f)
            )
            canvas.save()
            canvas.rotate(-90f, cx, cy)
            canvas.drawArc(rect, 0f, sweepAngle, false, progressPaint)
            canvas.restore()
            progressPaint.shader = null

            // Leuchtpunkt am Ende des Fortschritts
            if (isActive) {
                val dotAngle = Math.toRadians((sweepAngle - 90.0).toDouble())
                val dotX = cx + radius * cos(dotAngle).toFloat()
                val dotY = cy + radius * sin(dotAngle).toFloat()

                // Äußerer Glow
                dotPaint.color = Color.argb(60,
                    Color.red(currentColor),
                    Color.green(currentColor),
                    Color.blue(currentColor)
                )
                canvas.drawCircle(dotX, dotY, 14f, dotPaint)

                // Innerer Punkt
                dotPaint.color = currentColor
                canvas.drawCircle(dotX, dotY, 7f, dotPaint)

                // Weißer Kern
                dotPaint.color = Color.WHITE
                canvas.drawCircle(dotX, dotY, 3f, dotPaint)
            }
        }

        // Text: "NÄCHSTER ALARM"
        canvas.drawText("NÄCHSTER ALARM", cx, cy - 40f, labelPaint)

        // Zeit-Anzeige
        timePaint.color = if (isActive) currentColor else Color.parseColor("#445566")
        canvas.drawText(timeText, cx, cy + 30f, timePaint)

        // Sub-Label
        val subText = if (isActive) "läuft..." else "gestoppt"
        canvas.drawText(subText, cx, cy + 62f, subLabelPaint)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredSize = 600 // dp equivalent
        val width = resolveSize(desiredSize, widthMeasureSpec)
        val height = resolveSize(desiredSize, heightMeasureSpec)
        val size = min(width, height)
        setMeasuredDimension(size, size)
    }
}
