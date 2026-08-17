package com.pupu.taojinbi

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import androidx.core.app.NotificationCompat
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class CoinA11yService : AccessibilityService() {
    val running = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor()
    private var engine: TaskEngine? = null
    private var overlay: LogOverlay? = null

    override fun onServiceConnected() {
        instance = this
        serviceInfo = serviceInfo.apply {
            flags = flags or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }
        overlay = LogOverlay(this)
        tryShowOverlay()
        emitLog("无障碍已连接。请先授权「悬浮窗」，再点开始。")
        startFg()
    }

    /** 悬浮窗授权后尽早显示控制条 */
    fun tryShowOverlay() {
        if (overlay?.canDraw() != true) return
        try {
            overlay?.ensureVisible { wantRun ->
                if (wantRun) startLoop() else stopLoop()
            }
        } catch (e: Exception) {
            emitLog("悬浮窗显示失败: ${e.message}")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() {
        running.set(false)
    }

    override fun onDestroy() {
        running.set(false)
        overlay?.hide()
        overlay = null
        instance = null
        super.onDestroy()
    }

    fun startLoop() {
        try {
            if (running.get()) {
                emitLog("已经在跑")
                return
            }
            if (!ensureOverlay()) {
                emitLog("悬浮窗未就绪，请确认已授权")
                return
            }
            running.set(true)
            overlay?.setRunning(true)
            emitLog("开始运行…")
            executor.execute {
                try {
                    val loaded = ConfigLoader.loadWithInfo(this@CoinA11yService)
                    emitLog(loaded.summary)
                    val driver = A11yDriver(this@CoinA11yService, loaded.config.packageName)
                    engine = TaskEngine(this@CoinA11yService, loaded.config, driver)
                    engine?.run()
                } catch (e: Exception) {
                    emitLog("运行异常: ${e.message ?: e.javaClass.simpleName}")
                } finally {
                    running.set(false)
                    onEngineStopped()
                }
            }
        } catch (e: Exception) {
            running.set(false)
            overlay?.setRunning(false)
            emitLog("启动失败: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    fun stopLoop() {
        if (!running.get()) return
        running.set(false)
        overlay?.setRunning(false)
        emitLog("已暂停，点「继续」恢复")
    }

    private fun ensureOverlay(): Boolean {
        if (overlay?.canDraw() != true) {
            emitLog("请先授权悬浮窗（显示在其他应用上层）")
            return false
        }
        overlay?.ensureVisible { wantRun ->
            if (wantRun) startLoop() else stopLoop()
        }
        return true
    }

    fun onEngineStopped() {
        overlay?.setRunning(false)
        emitLog("已停止，点「继续」恢复运行")
    }

    fun emitLog(msg: String) {
        overlay?.append(msg)
        logSink?.invoke(msg)
    }

    private fun startFg() {
        val ch = "taojinbi"
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(ch, "淘金助手", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(this, ch)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("淘金助手")
            .setContentText("无障碍已开启")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        try {
            startForeground(1, n)
        } catch (_: Exception) {
            nm.notify(1, n)
        }
    }

    companion object {
        @Volatile var instance: CoinA11yService? = null
        @Volatile var logSink: ((String) -> Unit)? = null
    }
}
