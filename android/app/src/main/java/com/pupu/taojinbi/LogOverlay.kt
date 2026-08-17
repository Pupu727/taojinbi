package com.pupu.taojinbi

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * 紧凑悬浮胶囊：可拖、展开日志、停止/继续；日志区 [FLAG_NOT_TOUCHABLE] 穿透。
 */
class LogOverlay(private val context: Context) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var barView: View? = null
    private var logView: View? = null
    private var logText: TextView? = null
    private var toggleBtn: TextView? = null
    private var expandBtn: TextView? = null
    private var scroll: ScrollView? = null
    private val lines = ArrayDeque<String>()
    private var barParams: WindowManager.LayoutParams? = null
    private var logParams: WindowManager.LayoutParams? = null
    private var dragX = 0f
    private var dragY = 0f
    private var winX = 0
    private var winY = 0
    private var uiRunning = false
    private var logExpanded = false
    private var onToggle: ((Boolean) -> Unit)? = null
    private val barHeightPx = dp(BAR_HEIGHT_DP)
    private val logWidthPx = dp(LOG_WIDTH_DP)
    private val screenW: Int get() = context.resources.displayMetrics.widthPixels
    private val maxY: Int get() = (context.resources.displayMetrics.heightPixels * 0.7f).toInt()

    fun canDraw(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

    private fun inflater() =
        LayoutInflater.from(ContextThemeWrapper(context, R.style.Theme_Taojinbi))

    @SuppressLint("ClickableViewAccessibility")
    fun ensureVisible(onToggle: (Boolean) -> Unit) {
        this.onToggle = onToggle
        if (barView != null) return
        if (!canDraw()) return
        try {
            val type = overlayType()
            val startY = dp(START_Y_DP)

            val bar = inflater().inflate(R.layout.overlay_log_bar, null)
            toggleBtn = bar.findViewById(R.id.btnOverlayToggle)
            expandBtn = bar.findViewById(R.id.btnOverlayExpand)
            toggleBtn?.setOnClickListener { onToggle?.invoke(!uiRunning) }
            expandBtn?.setOnClickListener { setLogExpanded(!logExpanded) }
            barParams = baseParams(
                type,
                touchable = true,
                width = WindowManager.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 0
                y = startY
            }
            bar.setOnTouchListener { _, e ->
                if (e.actionMasked == MotionEvent.ACTION_DOWN && isTouchOnButton(e, toggleBtn, expandBtn)) {
                    return@setOnTouchListener false
                }
                onDrag(e)
            }
            wm.addView(bar, barParams)
            barView = bar
            setRunning(false)

            val panel = inflater().inflate(R.layout.overlay_log_panel, null)
            logText = panel.findViewById(R.id.overlayLog)
            scroll = panel.findViewById(R.id.logScroll)
            logParams = baseParams(type, touchable = false, width = logWidthPx).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 0
                y = startY + barHeightPx + dp(4)
            }
            wm.addView(panel, logParams)
            logView = panel
            setLogExpanded(false)
            // 测宽后放到顶部正中
            bar.post { moveToTopCenter() }
            flush()
        } catch (e: Exception) {
            throw IllegalStateException("悬浮窗显示失败: ${e.message}", e)
        }
    }

    fun setRunning(running: Boolean) {
        uiRunning = running
        toggleBtn?.post {
            toggleBtn?.text = if (running) "停止" else "继续"
            val color = if (running) R.color.skip_primary else R.color.success
            toggleBtn?.setTextColor(ContextCompat.getColor(context, color))
        }
        // 开始跑任务时回到屏幕顶部中间，避免被拖到角落后不好找
        if (running) {
            barView?.post { moveToTopCenter() }
        }
    }

    fun setLogExpanded(expanded: Boolean) {
        logExpanded = expanded
        logView?.post {
            logView?.visibility = if (expanded) View.VISIBLE else View.GONE
            expandBtn?.text = if (expanded) "收起" else "日志"
        }
    }

    fun hide() {
        listOf(logView, barView).forEach { v ->
            v?.let {
                try {
                    wm.removeView(it)
                } catch (_: Exception) {
                }
            }
        }
        logView = null
        barView = null
        logText = null
        toggleBtn = null
        expandBtn = null
        scroll = null
        barParams = null
        logParams = null
        uiRunning = false
        logExpanded = false
        onToggle = null
    }

    fun append(msg: String) {
        lines.addLast(msg)
        while (lines.size > 60) lines.removeFirst()
        flush()
    }

    private fun isTouchOnButton(e: MotionEvent, vararg buttons: TextView?): Boolean {
        for (btn in buttons) {
            val b = btn ?: continue
            val loc = IntArray(2)
            b.getLocationOnScreen(loc)
            if (e.rawX >= loc[0] && e.rawX <= loc[0] + b.width &&
                e.rawY >= loc[1] && e.rawY <= loc[1] + b.height
            ) {
                return true
            }
        }
        return false
    }

    private fun onDrag(e: MotionEvent): Boolean {
        val bp = barParams ?: return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragX = e.rawX
                dragY = e.rawY
                winX = bp.x
                winY = bp.y
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val maxX = (screenW - dp(40)).coerceAtLeast(0)
                val nx = (winX + (e.rawX - dragX).toInt()).coerceIn(0, maxX)
                val ny = (winY + (e.rawY - dragY).toInt()).coerceIn(0, maxY)
                applyPosition(nx, ny)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> return true
        }
        return false
    }

    private fun applyPosition(x: Int, y: Int) {
        barParams?.let {
            it.x = x
            it.y = y
            wm.updateViewLayout(barView, it)
        }
        logParams?.let {
            val barW = barView?.width?.takeIf { w -> w > 0 } ?: dp(ESTIMATED_BAR_WIDTH_DP)
            val maxLogX = (screenW - logWidthPx).coerceAtLeast(0)
            // 日志面板相对悬浮条水平居中
            it.x = (x + barW / 2 - logWidthPx / 2).coerceIn(0, maxLogX)
            it.y = y + barHeightPx + dp(4)
            wm.updateViewLayout(logView, it)
        }
    }

    /** 水平居中、贴屏幕顶（略避开状态栏） */
    private fun moveToTopCenter() {
        val bar = barView ?: return
        val w = if (bar.width > 0) bar.width else dp(ESTIMATED_BAR_WIDTH_DP)
        val x = ((screenW - w) / 2).coerceAtLeast(0)
        val y = dp(START_Y_DP).coerceIn(0, maxY)
        applyPosition(x, y)
    }

    private fun flush() {
        val tv = logText ?: return
        tv.post {
            tv.text = lines.joinToString("\n")
            scroll?.post { scroll?.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    private fun baseParams(type: Int, touchable: Boolean, width: Int): WindowManager.LayoutParams {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        flags = if (touchable) {
            flags or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        } else {
            flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        return WindowManager.LayoutParams(
            width,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            flags,
            PixelFormat.TRANSLUCENT,
        )
    }

    private fun dp(v: Int): Int =
        (v * context.resources.displayMetrics.density).toInt()

    companion object {
        private const val BAR_HEIGHT_DP = 36
        private const val LOG_WIDTH_DP = 280
        private const val ESTIMATED_BAR_WIDTH_DP = 168
        /** 顶边距：略低于状态栏，仍在屏幕正上方 */
        private const val START_Y_DP = 28
    }
}
