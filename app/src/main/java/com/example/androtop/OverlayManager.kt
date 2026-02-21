package com.example.androtop

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

class OverlayManager(private val context: Context) {

    private var windowManager: WindowManager? = null
    private var overlayView: TextView? = null
    private var isShowing = false

    fun canDrawOverlays(): Boolean {
        return Settings.canDrawOverlays(context)
    }

    fun show() {
        if (isShowing || !canDrawOverlays()) return

        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        overlayView = TextView(context).apply {
            text = context.getString(R.string.waiting_for_data)
            setTextColor(context.getColor(R.color.overlay_text))
            setBackgroundColor(context.getColor(R.color.overlay_background))
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(dpToPx(8), dpToPx(2), dpToPx(8), dpToPx(2))
            isSingleLine = true
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }

        try {
            windowManager?.addView(overlayView, params)
            isShowing = true
        } catch (e: Exception) {
            // Permission may have been revoked
            isShowing = false
        }
    }

    fun updateText(text: String) {
        overlayView?.post {
            overlayView?.text = text
        }
    }

    fun hide() {
        if (!isShowing) return
        try {
            overlayView?.let { windowManager?.removeView(it) }
        } catch (e: Exception) {
            // View may already be removed
        }
        overlayView = null
        isShowing = false
    }

    fun destroy() {
        hide()
        windowManager = null
    }

    fun isVisible(): Boolean = isShowing

    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            context.resources.displayMetrics
        ).toInt()
    }
}
