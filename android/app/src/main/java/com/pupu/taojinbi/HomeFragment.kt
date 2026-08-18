package com.pupu.taojinbi

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment

class HomeFragment : Fragment() {
    private lateinit var statusA11y: TextView
    private lateinit var statusOverlay: TextView
    private lateinit var statusAllowCount: TextView
    private lateinit var statusSkipCount: TextView
    private lateinit var statusTargetCount: TextView
    private lateinit var pillA11y: LinearLayout
    private lateinit var pillOverlay: LinearLayout
    private lateinit var logView: TextView
    private val logs = ArrayDeque<String>()
    private var dualAppController: DualAppSettingsController? = null

    private val pickCoordLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val ctrl = dualAppController ?: return@registerForActivityResult
        if (result.resultCode != android.app.Activity.RESULT_OK) {
            ctrl.onPickCancelled()
            return@registerForActivityResult
        }
        val data = result.data ?: run {
            ctrl.onPickCancelled()
            return@registerForActivityResult
        }
        val slot = data.getIntExtra(CoordinatePickActivity.EXTRA_SLOT, CoordinatePickActivity.SLOT_1)
        val x = data.getIntExtra(CoordinatePickActivity.EXTRA_X, -1)
        val y = data.getIntExtra(CoordinatePickActivity.EXTRA_Y, -1)
        if (x < 0 || y < 0) {
            ctrl.onPickCancelled()
            return@registerForActivityResult
        }
        ctrl.onCoordPicked(slot, x, y)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? = inflater.inflate(R.layout.fragment_home, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        statusA11y = view.findViewById(R.id.statusA11y)
        statusOverlay = view.findViewById(R.id.statusOverlay)
        statusAllowCount = view.findViewById(R.id.statusAllowCount)
        statusSkipCount = view.findViewById(R.id.statusSkipCount)
        statusTargetCount = view.findViewById(R.id.statusTargetCount)
        pillA11y = view.findViewById(R.id.pillA11y)
        pillOverlay = view.findViewById(R.id.pillOverlay)
        logView = view.findViewById(R.id.log)
        setupLogCopy(view.findViewById(R.id.logPanel))

        dualAppController = DualAppSettingsController(this, view, pickCoordLauncher)
        dualAppController?.load()

        view.findViewById<Button>(R.id.btnA11y).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        view.findViewById<Button>(R.id.btnOverlay).setOnClickListener { askOverlay() }
        view.findViewById<Button>(R.id.btnStart).setOnClickListener { startRun() }
        view.findViewById<Button>(R.id.btnStop).setOnClickListener {
            CoinA11yService.instance?.stopLoop() ?: append("服务未连接")
        }
    }

    override fun onResume() {
        super.onResume()
        CoinA11yService.logSink = { msg -> activity?.runOnUiThread { append(msg) } }
        refreshStatus()
        dualAppController?.load()
        if (a11yOn() && CoinA11yService.instance == null) {
            append("无障碍已开但服务未连接，请关闭再开「淘金助手」")
        }
    }

    override fun onDestroyView() {
        if (CoinA11yService.logSink != null) {
            CoinA11yService.logSink = null
        }
        dualAppController = null
        super.onDestroyView()
    }

    fun append(msg: String) {
        if (!::logView.isInitialized) return
        logs.addLast(msg)
        while (logs.size > 80) logs.removeFirst()
        logView.text = logs.joinToString("\n")
    }

    private fun setupLogCopy(logPanel: View) {
        val copyAction = View.OnLongClickListener {
            copyLogsToClipboard(it)
            true
        }
        logPanel.setOnLongClickListener(copyAction)
        logView.setOnLongClickListener(copyAction)
    }

    private fun copyLogsToClipboard(source: View) {
        val text = if (logs.isEmpty()) {
            logView.text?.toString().orEmpty().trim()
        } else {
            logs.joinToString("\n")
        }
        if (text.isEmpty() || text == "等待开始…") {
            toast("暂无日志可复制")
            return
        }
        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("淘金助手运行日志", text))
        source.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        toast("已复制 ${text.lines().size} 行日志")
    }

    private fun startRun() {
        append("── 点击开始 ──")
        if (!a11yOn()) {
            append("请先开无障碍")
            toast("请先开启无障碍")
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !Settings.canDrawOverlays(requireContext())
        ) {
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
            append("开始（已尝试拉起淘宝；顶部悬浮条可展开日志）")
            toast("已开始，请看顶部悬浮条")
            svc.startLoop()
        } catch (e: Exception) {
            append("启动失败: ${e.message}")
            toast("启动失败: ${e.message}")
        }
    }

    private fun openTaobaoFirst() {
        val pkg = "com.taobao.taobao"
        val launch = requireContext().packageManager.getLaunchIntentForPackage(pkg) ?: return
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
        if (Settings.canDrawOverlays(requireContext())) {
            append("悬浮窗已授权")
            CoinA11yService.instance?.tryShowOverlay()
            return
        }
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${requireContext().packageName}"),
            ),
        )
    }

    fun refreshStatus() {
        if (!isAdded) return
        val ctx = requireContext()
        val a11yOk = a11yOn()
        val overlayOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            Settings.canDrawOverlays(ctx)
        paintPill(pillA11y, statusA11y, a11yOk, "已开启", "未开启")
        paintPill(pillOverlay, statusOverlay, overlayOk, "已授权", "未授权")
        if (overlayOk) CoinA11yService.instance?.tryShowOverlay()
        statusAllowCount.text = UserSettings.getAllowKeywords(ctx).size.toString()
        statusSkipCount.text = UserSettings.getSkipKeywords(ctx).size.toString()
        statusTargetCount.text = UserSettings.getTargetCount(ctx).toString()
    }

    private fun paintPill(pill: LinearLayout, label: TextView, ok: Boolean, okText: String, warnText: String) {
        label.text = if (ok) okText else warnText
        label.setTextColor(
            ContextCompat.getColor(requireContext(), if (ok) R.color.success else R.color.warning),
        )
        pill.setBackgroundResource(
            if (ok) R.drawable.bg_status_pill_ok else R.drawable.bg_status_pill_warn,
        )
    }

    private fun a11yOn(): Boolean {
        val ctx = requireContext()
        val expected = ComponentName(ctx, CoinA11yService::class.java).flattenToString()
        if (Settings.Secure.getInt(ctx.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) != 1) {
            return false
        }
        val raw = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        return raw.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    private fun toast(msg: String) {
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
    }
}
