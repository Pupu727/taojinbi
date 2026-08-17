package com.pupu.taojinbi

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private lateinit var statusA11y: TextView
    private lateinit var statusOverlay: TextView
    private lateinit var statusAllowCount: TextView
    private lateinit var statusSkipCount: TextView
    private lateinit var statusTargetCount: TextView
    private lateinit var pillA11y: LinearLayout
    private lateinit var pillOverlay: LinearLayout
    private lateinit var logView: TextView
    private val logs = ArrayDeque<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ConfigLoader.ensureBundledConfigInstalled(this)
        setContentView(R.layout.activity_main)
        statusA11y = findViewById(R.id.statusA11y)
        statusOverlay = findViewById(R.id.statusOverlay)
        statusAllowCount = findViewById(R.id.statusAllowCount)
        statusSkipCount = findViewById(R.id.statusSkipCount)
        statusTargetCount = findViewById(R.id.statusTargetCount)
        pillA11y = findViewById(R.id.pillA11y)
        pillOverlay = findViewById(R.id.pillOverlay)
        logView = findViewById(R.id.log)
        findViewById<Button>(R.id.btnA11y).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.btnOverlay).setOnClickListener { askOverlay() }
        findViewById<Button>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<Button>(R.id.btnStart).setOnClickListener { startRun() }
        findViewById<Button>(R.id.btnStop).setOnClickListener {
            CoinA11yService.instance?.stopLoop() ?: append("服务未连接")
        }
        CoinA11yService.logSink = { msg -> runOnUiThread { append(msg) } }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        if (a11yOn() && CoinA11yService.instance == null) {
            append("无障碍已开但服务未连接，请关闭再开「淘金助手」")
        }
    }

    override fun onDestroy() {
        if (CoinA11yService.logSink != null) CoinA11yService.logSink = null
        super.onDestroy()
    }

    private fun startRun() {
        append("── 点击开始 ──")
        if (!a11yOn()) {
            append("请先开无障碍")
            toast("请先开启无障碍")
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            append("请先授权悬浮窗")
            toast("请先授权悬浮窗")
            askOverlay()
            return
        }
        val svc = CoinA11yService.instance
        if (svc == null) {
            append("无障碍已开但服务未连上")
            append("请到无障碍设置里关掉再开「淘金助手」")
            toast("服务未连接，请重开无障碍")
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        try {
            openTaobaoFirst()
            append("开始（顶部悬浮条可展开日志）")
            toast("已开始，请看顶部悬浮条")
            svc.startLoop()
        } catch (e: Exception) {
            append("启动失败: ${e.message}")
            toast("启动失败: ${e.message}")
        }
    }

    /** 先从 Activity 拉起淘宝（比无障碍服务后台拉起更可靠） */
    private fun openTaobaoFirst() {
        val pkg = "com.taobao.taobao"
        val launch = packageManager.getLaunchIntentForPackage(pkg) ?: return
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(launch) }
            .onFailure {
                runCatching {
                    startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("taobao://")).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        },
                    )
                }
            }
    }

    private fun askOverlay() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
            append("悬浮窗已授权")
            CoinA11yService.instance?.tryShowOverlay()
            return
        }
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName"),
            ),
        )
    }

    private fun refreshStatus() {
        val a11yOk = a11yOn()
        val overlayOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)
        paintPill(pillA11y, statusA11y, a11yOk, "已开启", "未开启")
        paintPill(pillOverlay, statusOverlay, overlayOk, "已授权", "未授权")
        if (overlayOk) CoinA11yService.instance?.tryShowOverlay()
        val allowN = UserSettings.getAllowKeywords(this).size
        val skipN = UserSettings.getSkipKeywords(this).size
        val target = UserSettings.getTargetCount(this)
        statusAllowCount.text = allowN.toString()
        statusSkipCount.text = skipN.toString()
        statusTargetCount.text = target.toString()
    }

    private fun paintPill(pill: LinearLayout, label: TextView, ok: Boolean, okText: String, warnText: String) {
        label.text = if (ok) okText else warnText
        label.setTextColor(
            ContextCompat.getColor(this, if (ok) R.color.success else R.color.warning),
        )
        pill.setBackgroundResource(
            if (ok) R.drawable.bg_status_pill_ok else R.drawable.bg_status_pill_warn,
        )
    }

    private fun a11yOn(): Boolean {
        val expected = ComponentName(this, CoinA11yService::class.java).flattenToString()
        if (Settings.Secure.getInt(contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) != 1) return false
        val raw = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return raw.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    private fun append(msg: String) {
        logs.addLast(msg)
        while (logs.size > 80) logs.removeFirst()
        logView.text = logs.joinToString("\n")
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
