package com.aeromock.gps.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.graphics.*
import android.os.Build
import android.os.IBinder
import android.view.*
import com.aeromock.gps.data.SpoofStateHolder
import kotlin.math.hypot
import kotlin.math.min

class JoystickOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private lateinit var params: WindowManager.LayoutParams

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val settings = SpoofStateHolder.settings.value
        val density = resources.displayMetrics.density
        val sizePx = (settings.joystickSizeDp * density).toInt()

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        params = WindowManager.LayoutParams(
            sizePx,
            sizePx + (32 * density).toInt(),
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 60
            y = 400
        }

        val view = JoystickCanvasView(this, settings.joystickAlpha) { dx, dy ->
            SpoofStateHolder.setJoystickVector(dx, dy)
        }

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var draggingWindow = false

        view.setOnTouchListener { _, event ->
            val headerHeight = 32 * density
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (event.y <= headerHeight) {
                        draggingWindow = true
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        true
                    } else {
                        draggingWindow = false
                        view.handleJoystickTouch(event)
                        true
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (draggingWindow) {
                        params.x = initialX + (event.rawX - initialTouchX).toInt()
                        params.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager.updateViewLayout(view, params)
                    } else {
                        view.handleJoystickTouch(event)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!draggingWindow) {
                        view.resetKnob()
                    }
                    draggingWindow = false
                    true
                }
                else -> false
            }
        }

        overlayView = view
        windowManager.addView(view, params)
    }

    override fun onDestroy() {
        overlayView?.let { runCatching { windowManager.removeView(it) } }
        SpoofStateHolder.setJoystickVector(0f, 0f)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private class JoystickCanvasView(
        context: android.content.Context,
        alphaValue: Float,
        private val onVectorChanged: (Float, Float) -> Unit
    ) : View(context) {
        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb((alphaValue * 190).toInt(), 22, 27, 34)
            style = Paint.Style.FILL
        }
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(180, 88, 166, 255)
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }
        private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(235, 88, 166, 255)
            style = Paint.Style.FILL
        }
        private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(210, 48, 54, 61)
            style = Paint.Style.FILL
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 26f
            textAlign = Paint.Align.CENTER
        }

        private var knobX = 0f
        private var knobY = 0f
        private var centerX = 0f
        private var centerY = 0f
        private var maxRadius = 1f

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            val headerOffset = 32f * resources.displayMetrics.density
            centerX = w / 2f
            centerY = headerOffset + (h - headerOffset) / 2f
            maxRadius = min(w / 2f, (h - headerOffset) / 2f) * 0.72f
            knobX = centerX
            knobY = centerY
        }

        override fun onDraw(canvas: Canvas) {
            val density = resources.displayMetrics.density
            val headerH = 28f * density
            canvas.drawRoundRect(0f, 0f, width.toFloat(), headerH, 24f, 24f, handlePaint)
            canvas.drawText("≡ JOYSTICK ≡", width / 2f, headerH * 0.68f, textPaint)

            canvas.drawCircle(centerX, centerY, maxRadius * 1.15f, bgPaint)
            canvas.drawCircle(centerX, centerY, maxRadius * 1.15f, ringPaint)
            canvas.drawCircle(knobX, knobY, maxRadius * 0.36f, knobPaint)
        }

        fun handleJoystickTouch(event: MotionEvent) {
            val dx = event.x - centerX
            val dy = event.y - centerY
            val dist = hypot(dx, dy)
            if (dist > maxRadius) {
                knobX = centerX + (dx / dist) * maxRadius
                knobY = centerY + (dy / dist) * maxRadius
            } else {
                knobX = event.x
                knobY = event.y
            }
            val normX = ((knobX - centerX) / maxRadius).coerceIn(-1f, 1f)
            val normY = ((knobY - centerY) / maxRadius).coerceIn(-1f, 1f)
            onVectorChanged(normX, normY)
            invalidate()
        }

        fun resetKnob() {
            knobX = centerX
            knobY = centerY
            onVectorChanged(0f, 0f)
            invalidate()
        }
    }
}
