package com.facu.geminibrowser

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.animation.DecelerateInterpolator
import android.webkit.WebView
import kotlin.math.abs
import kotlin.math.sign

/** WebView con rebote elástico estilo iOS al llegar al borde de la página (se apaga en Ajustes). */
class BounceWebView(context: Context) : WebView(context) {
    var bounce = true
    private var lastY = 0f
    private var raw = 0f
    private var side = 0
    private var over = false

    init { overScrollMode = OVER_SCROLL_NEVER }

    private fun rubber(x: Float): Float {
        val d = height * 0.45f
        return sign(x) * (1f - 1f / (abs(x) * 0.55f / d + 1f)) * d
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!bounce) return super.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                animate().cancel()
                translationY = 0f
                lastY = e.rawY
                over = false
                return super.onTouchEvent(e)
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = e.rawY - lastY
                lastY = e.rawY
                if (!over) {
                    val top = !canScrollVertically(-1)
                    val bottom = !canScrollVertically(1)
                    if ((dy > 0 && top) || (dy < 0 && bottom)) {
                        over = true
                        raw = 0f
                        side = if (dy > 0) 1 else -1
                        val c = MotionEvent.obtain(e)
                        c.action = MotionEvent.ACTION_CANCEL
                        super.onTouchEvent(c)
                        c.recycle()
                    } else {
                        return super.onTouchEvent(e)
                    }
                }
                raw += dy
                if (raw * side <= 0f) {
                    over = false
                    translationY = 0f
                    val d = MotionEvent.obtain(e)
                    d.action = MotionEvent.ACTION_DOWN
                    super.onTouchEvent(d)
                    d.recycle()
                    return true
                }
                translationY = rubber(raw)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (over) {
                    over = false
                    animate().translationY(0f).setDuration(480).setInterpolator(DecelerateInterpolator(2.4f)).start()
                    return true
                }
                return super.onTouchEvent(e)
            }
        }
        return super.onTouchEvent(e)
    }
}
