package com.pupu.taojinbi

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

data class UiNode(
    val text: String,
    val desc: String,
    val cls: String,
    val bounds: Rect,
    val clickable: Boolean,
) {
    val label: String get() = text.ifBlank { desc }
    val cx: Int get() = bounds.centerX()
    val cy: Int get() = bounds.centerY()
}

/** H5 任务列表一行：标题 + 右侧点击区 */
data class TaskRow(
    val name: String,
    val titleBounds: Rect,
    val clickBounds: Rect,
    val progressCur: Int? = null,
    val progressTarget: Int? = null,
)

class A11yDriver(
    private val service: CoinA11yService,
    private val targetPkg: String,
) {
    companion object {
        @Volatile private var lastDualPickAt = 0L
        private const val DUAL_PICK_COOLDOWN_MS = 3500L
    }

    private val main = Handler(Looper.getMainLooper())
    private val selfPkg = service.packageName
    val goTexts = listOf("去完成", "去逛逛", "逛一逛", "去浏览", "去看看")
    val claimTexts = listOf("领取奖励", "去领取")
    private val actionTexts = goTexts + claimTexts

    fun isDirectClaimLabel(label: String): Boolean {
        val t = label.trim()
        return claimTexts.any { t == it || (t.contains(it) && t.length <= it.length + 8) }
    }

    fun log(msg: String) = service.emitLog(msg)

    fun sleep(seconds: Float) {
        if (seconds <= 0f) return
        val end = SystemClock.uptimeMillis() + (seconds * 1000).toLong()
        while (SystemClock.uptimeMillis() < end) {
            if (!service.running.get()) return
            service.blockWhilePaused()
            if (!service.running.get()) return
            Thread.sleep(min(200L, end - SystemClock.uptimeMillis()).coerceAtLeast(1L))
        }
    }

    fun screenW(): Int = service.resources.displayMetrics.widthPixels
    fun screenH(): Int = service.resources.displayMetrics.heightPixels

    fun currentPkg(): String = onMain {
        readTargetRoot()?.packageName?.toString()
            ?: service.rootInActiveWindow?.packageName?.toString()
            ?: ""
    }

    fun isTaobaoForeground(): Boolean = currentPkg() == targetPkg

    fun snapshot(): List<UiNode> = onMain {
        val root = readTargetRoot() ?: service.rootInActiveWindow ?: return@onMain emptyList()
        val out = ArrayList<UiNode>(256)
        walk(root, out)
        out
    }

    fun pageText(): String = snapshot().joinToString("\n") { it.label }

    /** 会员等级等淘宝内子页也有「去领取」，不能当任务列表 */
    fun isMembershipLevelPage(text: String = pageText()): Boolean {
        if (text.contains("会员等级") || text.contains("我的会员")) return true
        val levels = listOf("青铜", "白银", "黄金", "铂金", "钻石", "黑钻")
        if (text.contains("淘气值") && levels.any { text.contains(it) }) return true
        if (text.contains("精选福利") && text.contains("每天领红包")) return true
        return false
    }

    private fun looksLikeTaskListChrome(text: String): Boolean =
        text.contains("任务面板") || text.contains("每日来任务") ||
            text.contains("完成进度") || text.contains("赚金币抵钱")

    fun listOpen(nodes: List<UiNode> = snapshot()): Boolean {
        val text = nodes.joinToString("\n") { it.label }
        if (isClickProductTaskPage(text)) return false
        if (isMembershipLevelPage(text)) return false
        if (findTaskRows(nodes).isNotEmpty()) return true
        val gos = findGoButtons(nodes)
        if (gos.any { btn -> goTexts.any { g -> btn.label.contains(g) } }) return true
        // 仅「去领取/领取奖励」时须像任务列表（有面板特征），否则是会员页等
        if (gos.any { isDirectClaimLabel(it.label) } && looksLikeTaskListChrome(text)) {
            return true
        }
        return false
    }

    /** 用系统按文案搜索，H5 任务列表里比 walk 更可靠 */
    fun findNodesByText(vararg texts: String): List<UiNode> {
        val root = readTargetRoot() ?: service.rootInActiveWindow ?: return emptyList()
        val out = ArrayList<UiNode>()
        val seen = HashSet<String>()
        for (t in texts) {
            val hits = root.findAccessibilityNodeInfosByText(t) ?: continue
            for (n in hits) {
                if (!n.isVisibleToUser) continue
                val label = n.text?.toString()?.trim().orEmpty()
                    .ifBlank { n.contentDescription?.toString()?.trim().orEmpty() }
                if (label.isEmpty()) continue
                val b = Rect()
                n.getBoundsInScreen(b)
                if (b.width() <= 0 || b.height() <= 0) continue
                val key = "${b.left},${b.top},$label"
                if (!seen.add(key)) continue
                out.add(
                    UiNode(
                        text = label,
                        desc = n.contentDescription?.toString()?.trim().orEmpty(),
                        cls = n.className?.toString().orEmpty(),
                        bounds = Rect(b),
                        clickable = n.isClickable,
                    ),
                )
            }
        }
        return out
    }

    fun clickText(text: String, nodes: List<UiNode> = snapshot()): Boolean {
        val hit = nodes.firstOrNull { it.label.contains(text) } ?: return false
        return clickBounds(hit.bounds)
    }

    fun clickBounds(r: Rect, durationMs: Long = 120): Boolean {
        val pad = 8
        val x1 = r.left + pad
        val x2 = r.right - pad
        val y1 = r.top + pad
        val y2 = r.bottom - pad
        val x = if (x2 <= x1) r.centerX() else Random.nextInt(x1, x2 + 1)
        val y = if (y2 <= y1) r.centerY() else Random.nextInt(y1, y2 + 1)
        return clickXy(x, y, durationMs)
    }

    fun clickXy(x: Int, y: Int, durationMs: Long = 120, forgiving: Boolean = false): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        return when (gestureOutcome(path, durationMs)) {
            GestureOutcome.COMPLETED -> true
            GestureOutcome.CANCELLED -> {
                if (!forgiving) {
                    log("手势被取消 ($x,$y) ${durationMs}ms（界面切换时常见，不一定未点到）")
                }
                forgiving
            }
            GestureOutcome.DISPATCH_FAILED -> {
                log("手势派发失败 ($x,$y) ${durationMs}ms")
                false
            }
            GestureOutcome.TIMEOUT -> {
                log("手势超时 ($x,$y) ${durationMs}ms")
                false
            }
        }
    }

    /**
     * 点商品页「点我得淘金币」：只点按钮文字附近小区域，禁止扩大到整页/WebView。
     */
    fun clickProductCoinButton(btn: UiNode): Boolean {
        val safe = sanitizeProductButtonBounds(btn.bounds)
        if (safe == null) {
            log("按钮区域无效 top=${btn.bounds.top} ${btn.bounds.width()}x${btn.bounds.height()}")
            return false
        }
        val before = pageText()
        val beforeProgress = parseProductClickProgress(before)
        log("商品按钮区 ${safe.left},${safe.top}-${safe.right},${safe.bottom}")

        if (clickAccessibilityLabel("点我得淘金币", safe)) {
            log("节点点击")
            if (waitProductClickEffect(before, beforeProgress)) return true
        }

        for ((dx, dy) in productTapOffsets) {
            val x = (safe.centerX() + dx).coerceIn(safe.left + 4, safe.right - 4)
            val y = (safe.centerY() + dy).coerceIn(safe.top + 4, safe.bottom - 4)
            log("精确点击 ($x,$y)")
            if (clickXy(x, y, durationMs = 160)) {
                if (waitProductClickEffect(before, beforeProgress)) return true
            }
        }
        log("点我得淘金币 未生效")
        return false
    }

    private val productTapOffsets = listOf(
        0 to 0,
        0 to 6,
        0 to -6,
        -8 to 0,
        8 to 0,
    )

    private fun waitProductClickEffect(
        before: String,
        beforeProgress: Triple<Int, Int, Int>?,
    ): Boolean {
        val beforeCur = beforeProgress?.first
        repeat(5) {
            sleep(0.45f)
            val now = pageText()
            val prog = parseProductClickProgress(now)
            if (beforeCur != null && prog != null && prog.first > beforeCur) {
                log("进度 ${beforeCur}->${prog.first}")
                return true
            }
            if (isClickProductTaskPage(before) && !isClickProductTaskPage(now)) return true
            if (now.contains("已点击商品") && !before.contains("已点击商品")) return true
        }
        return false
    }

    /** 商品按钮合法区域：排除顶栏 Tab，限制宽高，过大则收束到中心 */
    private fun sanitizeProductButtonBounds(r: Rect): Rect? {
        val w = screenW()
        val h = screenH()
        val minTop = (h * 0.24f).toInt()
        if (r.bottom <= minTop) return null
        var out = Rect(r)
        if (out.top < minTop) out.top = minTop
        if (out.height() > 88) {
            val cy = r.centerY().coerceAtLeast(minTop + 20)
            out.top = cy - 36
            out.bottom = cy + 36
        }
        if (out.width() > (w * 0.52f).toInt()) {
            val cx = r.centerX()
            out.left = cx - 90
            out.right = cx + 90
        }
        if (out.width() < 72 || out.height() < 28) {
            out = expandClickBounds(out, minW = 88, minH = 36, maxW = (w * 0.48f).toInt(), maxH = 72)
        }
        out = clampToScreen(out)
        if (out.top < minTop || out.height() !in 24..96 || out.width() !in 60..(w * 0.55f).toInt()) {
            return null
        }
        return out
    }

    private fun clampToScreen(r: Rect): Rect {
        val w = screenW()
        val h = screenH()
        return Rect(
            r.left.coerceIn(0, w - 1),
            r.top.coerceIn(0, h - 1),
            r.right.coerceIn(1, w),
            r.bottom.coerceIn(1, h),
        )
    }

    /** 按文案找节点，优先 performAction(CLICK)，必要时向上找可点击父节点 */
    private fun clickAccessibilityLabel(text: String, near: Rect? = null): Boolean {
        val root = readTargetRoot() ?: service.rootInActiveWindow ?: return false
        val hits = root.findAccessibilityNodeInfosByText(text) ?: return false
        try {
            for (raw in hits) {
                try {
                    if (!raw.isVisibleToUser) continue
                    val label = raw.text?.toString().orEmpty()
                        .ifBlank { raw.contentDescription?.toString().orEmpty() }
                    if (label.contains("已点击")) continue
                    val b = Rect()
                    raw.getBoundsInScreen(b)
                    if (near != null && !boundsNear(b, near, slack = 48)) continue
                    if (!isReasonableProductHit(b)) continue
                    val clickNode = findClickableAncestor(raw) ?: raw
                    val cb = Rect()
                    clickNode.getBoundsInScreen(cb)
                    if (!isReasonableProductHit(cb)) {
                        if (performNodeClick(raw)) return true
                        continue
                    }
                    if (performNodeClick(clickNode)) return true
                } finally {
                    raw.recycle()
                }
            }
        } catch (_: Exception) {
        }
        return false
    }

    private fun boundsNear(a: Rect, b: Rect, slack: Int): Boolean {
        val expand = Rect(b)
        expand.inset(-slack, -slack)
        return Rect.intersects(expand, a) ||
            kotlin.math.abs(a.centerX() - b.centerX()) < slack + max(a.width(), b.width())
    }

    private fun findClickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = node
        var best: AccessibilityNodeInfo? = null
        repeat(6) {
            val n = cur ?: return best
            val b = Rect()
            n.getBoundsInScreen(b)
            if (!isReasonableProductHit(b)) return best
            if (n.isClickable && n.isEnabled) best = n
            cur = n.parent
        }
        return best
    }

    private fun isReasonableProductHit(b: Rect): Boolean {
        val w = screenW()
        val h = screenH()
        val minTop = (h * 0.24f).toInt()
        if (b.bottom <= minTop) return false
        if (b.height() > (h * 0.12f).toInt()) return false
        if (b.width() > (w * 0.55f).toInt()) return false
        return true
    }

    private fun expandClickBounds(
        r: Rect,
        minW: Int,
        minH: Int,
        maxW: Int = screenW() / 2,
        maxH: Int = 80,
    ): Rect {
        val out = Rect(r)
        if (out.height() < minH) {
            val pad = (minH - out.height()) / 2 + 2
            out.top -= pad
            out.bottom += pad
        }
        if (out.width() < minW) {
            val pad = (minW - out.width()) / 2 + 4
            out.left -= pad
            out.right += pad
        }
        if (out.height() > maxH) {
            val cy = out.centerY()
            out.top = cy - maxH / 2
            out.bottom = cy + maxH / 2
        }
        if (out.width() > maxW) {
            val cx = out.centerX()
            out.left = cx - maxW / 2
            out.right = cx + maxW / 2
        }
        return clampToScreen(out)
    }

    private fun performNodeClick(node: AccessibilityNodeInfo): Boolean = onMain {
        try {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } catch (_: Exception) {
            false
        }
    }

    fun findGoButtons(nodes: List<UiNode> = snapshot()): List<UiNode> {
        val w = screenW()
        val merged = LinkedHashMap<String, UiNode>()

        fun isGoBtn(n: UiNode): Boolean {
            val label = n.label
            return actionTexts.any { g -> label == g || (label.contains(g) && label.length <= g.length + 10) }
        }

        fun accept(n: UiNode): Boolean {
            if (!isGoBtn(n)) return false
            if (n.bounds.right <= w * 0.38f) return false
            if (n.bounds.height() !in 14..260) return false
            return true
        }

        fun add(n: UiNode) {
            if (!accept(n)) return
            val k = "${n.bounds.top / 15}_${n.bounds.left / 15}_${n.bounds.right / 15}"
            merged[k] = n
        }

        nodes.filter { accept(it) }.forEach(::add)
        findNodesByText(*actionTexts.toTypedArray()).forEach(::add)
        // H5 列表里按钮 class 常为 Button
        nodes.filter { it.cls.contains("Button", true) && isGoBtn(it) }.forEach(::add)

        return merged.values.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
    }

    private val progressRe = Regex("[(（]\\s*(\\d+)\\s*/\\s*(\\d+)\\s*[)）]")
    private val progressAtEndRe = Regex("""[(（]\s*(\d+)\s*/\s*(\d+)\s*[)）]\s*$""")

    /** 列表任务进度，如 淘金币趣味课堂(1/1)；进度须在标题末尾 */
    fun parseListTaskProgress(text: String, requireAtEnd: Boolean = false): Pair<Int, Int>? {
        val re = if (requireAtEnd) progressAtEndRe else progressRe
        val m = re.find(text.trim()) ?: return null
        val cur = m.groupValues[1].toIntOrNull() ?: return null
        val target = m.groupValues[2].toIntOrNull() ?: return null
        if (target <= 0 || cur < 0 || cur > target) return null
        return cur to target
    }

    /** 商品详情/URL/乱码，不是任务行 */
    fun looksLikeProductOrGarbage(text: String): Boolean {
        val t = text.trim()
        if (t.length > 52) return true
        if (t.contains("http", true) || t.contains("alicdn", true) ||
            t.contains(".jpg", true) || t.contains(".png", true) || t.contains("item.", true)
        ) {
            return true
        }
        if (t.contains("¥") || t.contains("已抵") || t.contains("人已抢") ||
            t.contains("折后") || t.contains("券后")
        ) {
            return true
        }
        if (Regex("[A-Za-z0-9+/=_-]{18,}").containsMatchIn(t)) return true
        val ascii = t.count { it.code in 32..126 }
        if (ascii > t.length / 2 && t.length > 16) return true
        val chinese = t.count { it in '\u4e00'..'\u9fff' }
        if (chinese < 3 && t.length > 8) return true
        return false
    }

    private val doneBtnTexts = setOf("已完成", "已领取", "已做完", "已完成任务", "领取完成")

    /** 列表里该行是否已完成（进度满或右侧「已完成」） */
    fun isTaskRowCompleted(row: TaskRow, nodes: List<UiNode> = snapshot()): Boolean {
        val cur = row.progressCur
        val target = row.progressTarget
        if (cur != null && target != null && target > 0 && cur >= target) return true
        return isRowDoneButtonVisible(row.titleBounds.centerY(), nodes)
    }

    /** 「去完成」按钮所在行是否已完成 */
    fun isGoRowCompleted(btn: UiNode, nodes: List<UiNode> = snapshot()): Boolean {
        val label = btn.label.trim()
        if (label in doneBtnTexts || label == "已完成") return true
        val cy = btn.cy
        if (isRowDoneButtonVisible(cy, nodes)) return true
        val combined = rowTextNearY(nodes, cy)
        parseListTaskProgress(combined)?.let { (c, t) ->
            if (t > 0 && c >= t) return true
        }
        return false
    }

    private fun rowTextNearY(nodes: List<UiNode>, cy: Int): String {
        val w = screenW()
        return nodes
            .filter { kotlin.math.abs(it.cy - cy) <= 55 && it.bounds.left < w * 0.75f }
            .sortedBy { it.bounds.left }
            .joinToString("") { it.label.trim() }
    }

    private fun resolveRowProgress(nodes: List<UiNode>, anchor: Rect): Pair<Int, Int>? =
        parseListTaskProgress(rowTextNearY(nodes, anchor.centerY()), requireAtEnd = true)

    private fun isRowDoneButtonVisible(cy: Int, nodes: List<UiNode>): Boolean {
        val minLeft = (screenW() * 0.52f).toInt()
        for (n in nodes) {
            if (kotlin.math.abs(n.cy - cy) > 58) continue
            if (n.bounds.left < minLeft) continue
            val t = n.label.trim()
            if (t in doneBtnTexts) return true
            if (t == "已完成" || (t.contains("已完成") && t.length <= 6)) return true
        }
        return findNodesByText("已完成").any { n ->
            kotlin.math.abs(n.cy - cy) <= 58 && n.bounds.left >= minLeft
        }
    }

    /**
     * H5 列表常只暴露少量「去完成」节点，但每行标题带 (0/1) 进度。
     * 按任务行识别 + 点右侧按钮区，比单纯搜按钮更全。
     */
    fun findTaskRows(nodes: List<UiNode> = snapshot()): List<TaskRow> {
        val w = screenW()
        val h = screenH()
        val minTop = (h * 0.16f).toInt()
        val skipExact = actionTexts.toSet() + setOf("立即领取", "赚更多金币", "签到领金币", "关闭")

        val raw = nodes.filter { n ->
            val t = n.label.trim()
            if (t.length !in 6..52) return@filter false
            if (!progressAtEndRe.containsMatchIn(t)) return@filter false
            if (looksLikeProductOrGarbage(t)) return@filter false
            if (productProgressRe.containsMatchIn(t)) return@filter false
            if (t.contains("点我得淘金币") || t.contains("补贴商品")) return@filter false
            if (t in skipExact) return@filter false
            if (n.bounds.top < minTop) return@filter false
            if (n.bounds.left > w * 0.72f) return@filter false
            if (n.bounds.height() > 200) return@filter false
            val name = progressAtEndRe.replace(t, "").trim()
            if (name.length !in 4..36 || looksLikeProductOrGarbage(name)) return@filter false
            true
        }.sortedBy { it.bounds.top }

        val deduped = ArrayList<UiNode>()
        for (n in raw) {
            val last = deduped.lastOrNull()
            if (last != null && kotlin.math.abs(n.bounds.top - last.bounds.top) < 40) {
                if (n.label.length > last.label.length) deduped[deduped.lastIndex] = n
            } else {
                deduped.add(n)
            }
        }

        // 标题与 (0/1) 拆成两个节点时，按行合并
        if (deduped.size < 4) {
            val byRow = nodes
                .filter { it.bounds.top >= minTop && it.label.length in 2..40 }
                .groupBy { it.bounds.centerY() / 45 }
            for ((_, group) in byRow) {
                val combined = group.joinToString("") { it.label.trim() }
                if (combined.length !in 8..52) continue
                if (!progressAtEndRe.containsMatchIn(combined)) continue
                if (looksLikeProductOrGarbage(combined)) continue
                if (skipExact.any { combined == it }) continue
                val anchor = group.maxByOrNull { it.bounds.width() * it.bounds.height() } ?: continue
                if (deduped.any { kotlin.math.abs(it.bounds.centerY() - anchor.bounds.centerY()) < 40 }) continue
                deduped.add(
                    UiNode(
                        text = combined,
                        desc = "",
                        cls = anchor.cls,
                        bounds = Rect(anchor.bounds),
                        clickable = anchor.clickable,
                    ),
                )
            }
            deduped.sortBy { it.bounds.top }
        }

        return deduped.mapNotNull { n ->
            val full = n.label.trim()
            val progress = parseListTaskProgress(full, requireAtEnd = true)
                ?: resolveRowProgress(nodes, n.bounds)?.takeIf { (_, t) -> t > 0 }
            if (progress == null) return@mapNotNull null
            val (pCur, pTarget) = progress
            var name = progressAtEndRe.replace(full, "").trim()
            if (name.isEmpty()) name = progressRe.replace(full, "").trim()
            if (name.isEmpty() || !isValidTaskName(name) || looksLikeProductOrGarbage(name)) {
                return@mapNotNull null
            }
            val top = n.bounds.top + 6
            val bottom = n.bounds.bottom - 6
            val click = Rect(
                (w * 0.70f).toInt(),
                top,
                w - 12,
                bottom,
            )
            TaskRow(name, Rect(n.bounds), click, pCur, pTarget)
        }
    }

    fun isValidTaskName(name: String): Boolean {
        if (name.startsWith("未命名@")) return false
        val t = name.trim()
        if (t.length !in 4..36) return false
        if (t.all { it.isDigit() }) return false
        if (t.contains("奖励更多")) return false
        if (t == "500" || t == "金币") return false
        if (isListChromeText(t)) return false
        if (looksLikeProductOrGarbage(t)) return false
        return true
    }

    fun isUnnamedTask(name: String): Boolean = name.startsWith("未命名@")

    /** 弹层标题/背景页文案，不能当任务名 */
    fun isListChromeText(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return true
        val chrome = listOf(
            "淘金币首页", "赚金币抵钱", "完成进度", "已领取", "提醒我来领淘金币",
            "每日来任务面板", "任务面板", "赚金币", "抵钱", "已帮你自动签到",
            "签到领金币", "赚更多金币", "立即领取", "关闭",
        )
        if (chrome.any { t == it || (t.length <= 12 && t.contains(it)) }) return true
        if (t == "首页" || t.endsWith("首页") && t.length <= 8) return true
        if (Regex("完成进度\\s*\\d").containsMatchIn(t)) return true
        return false
    }

    /** 「去完成」按钮对应哪一行任务（按纵坐标） */
    fun matchTaskRowForGo(btn: UiNode, rows: List<TaskRow>): TaskRow? =
        rows.minByOrNull { kotlin.math.abs(it.titleBounds.centerY() - btn.cy) }
            ?.takeIf { kotlin.math.abs(it.titleBounds.centerY() - btn.cy) <= 55 }

    fun resolveTaskName(btn: UiNode, nodes: List<UiNode> = snapshot(), rows: List<TaskRow>? = null): String {
        rows?.let { matchTaskRowForGo(btn, it)?.name }?.let { matched ->
            if (isValidTaskName(matched)) return matched
        }
        val combined = rowTextNearY(nodes, btn.cy)
        parseListTaskProgress(combined)?.let {
            var name = progressRe.replace(combined, "").trim()
            if (name.isEmpty()) name = combined
            if (isValidTaskName(name)) return name
        }
        val desc = btn.desc.trim()
        if (desc.isNotBlank() && actionTexts.none { desc == it || (desc.length <= 20 && desc.contains(it)) }) {
            if (isValidTaskName(desc)) return desc
        }
        nameNear(nodes, btn.bounds)?.let { if (isValidTaskName(it)) return it }
        val cy = btn.cy
        val row = nodes
            .filter { n ->
                val t = n.label.trim()
                if (isListChromeText(t)) return@filter false
                t.length in 4..36 && actionTexts.none { t == it } &&
                    !t.contains("¥") && !t.contains("已抵") && !t.contains("人已抢") &&
                    kotlin.math.abs(n.cy - cy) <= 55 && n.bounds.right <= btn.bounds.left + 20 &&
                    n.bounds.top >= (screenH() * 0.12f).toInt()
            }
            .maxByOrNull { it.label.length }
        if (row != null && isValidTaskName(row.label)) return row.label.trim()
        return "未命名@${btn.bounds.top},${btn.bounds.left}"
    }

    private fun nameNear(nodes: List<UiNode>, btn: Rect): String? {
        val cy = btn.centerY()
        val minTop = (screenH() * 0.12f).toInt()
        val skip = actionTexts.toSet() + setOf("立即领取", "赚更多金币", "签到领金币")
        return nodes
            .filter { n ->
                val t = n.label.trim()
                if (isListChromeText(t)) return@filter false
                t.length in 2..36 && t !in skip && !t.contains("¥") && !t.contains("已抵") &&
                    !t.contains("人已抢") && n.bounds.top >= minTop
            }
            .filter { n -> kotlin.math.abs(n.cy - cy) <= 55 && n.bounds.left < btn.left }
            .minByOrNull { n ->
                kotlin.math.abs(n.cy - cy) + max(0, btn.left - n.bounds.right) - n.label.length / 2
            }
            ?.label?.trim()
    }

    fun taskClickKey(name: String, bounds: Rect): String =
        taskProgressKey(name, null, null, bounds)

    /** 带进度的点击记录；(0/N) 视为新一轮，不沿用旧记录 */
    fun taskProgressKey(
        name: String,
        cur: Int?,
        target: Int?,
        bounds: Rect,
    ): String {
        if (name.startsWith("未命名")) {
            return "b:${bounds.top},${bounds.left},${bounds.right},${bounds.bottom}"
        }
        if (cur != null && target != null) return "n:$name:$cur/$target"
        return "n:$name"
    }

    fun shouldSkipAsClicked(
        name: String,
        cur: Int?,
        target: Int?,
        bounds: Rect,
        clicked: Set<String>,
    ): Boolean {
        if (cur == 0 && target != null && target > 0) return false
        return taskProgressKey(name, cur, target, bounds) in clicked
    }

    private val productProgressRe = Regex(
        "点\\s*(\\d+)\\s*个商品\\s*[，,]?\\s*得\\s*(\\d+)\\s*淘金币\\s*[(（]\\s*(\\d+)\\s*/\\s*(\\d+)\\s*[)）]",
    )
    private val productClickedRe = Regex(
        "已点击商品\\s*[(（]\\s*(\\d+)\\s*/\\s*(\\d+)\\s*[)）]",
    )

    /** 解析「点3个商品，得30淘金币(1/3)」→ (当前, 目标, 金币数) */
    fun parseProductClickProgress(text: String): Triple<Int, Int, Int>? {
        productProgressRe.find(text)?.let { m ->
            val target = m.groupValues[1].toIntOrNull() ?: return@let null
            val coins = m.groupValues[2].toIntOrNull() ?: return@let null
            val cur = m.groupValues[3].toIntOrNull() ?: return@let null
            val total = m.groupValues[4].toIntOrNull() ?: return@let null
            return Triple(cur, total, coins)
        }
        productClickedRe.find(text)?.let { m ->
            val cur = m.groupValues[1].toIntOrNull() ?: return@let null
            val total = m.groupValues[2].toIntOrNull() ?: return@let null
            return Triple(cur, total, 0)
        }
        return null
    }

    fun isClickProductTaskPage(text: String = pageText()): Boolean =
        parseProductClickProgress(text) != null ||
            text.contains("点我得淘金币") ||
            text.contains("补贴商品") ||
            text.contains("已点击商品")

    /** 可点的「点我得淘金币」按钮（跳过已点击、顶栏、过大节点） */
    fun findClickProductButtons(nodes: List<UiNode> = snapshot()): List<UiNode> {
        fun qualify(n: UiNode): UiNode? {
            val t = n.label.trim()
            if (!t.contains("点我得淘金币") && t != "点我得淘金币" && !t.contains("点我得")) return null
            if (t.contains("已点击")) return null
            val safe = sanitizeProductButtonBounds(n.bounds) ?: return null
            return n.copy(bounds = safe)
        }

        val fromSnap = nodes.mapNotNull { qualify(it) }
        if (fromSnap.isNotEmpty()) {
            return fromSnap
                .distinctBy { "${it.bounds.centerY() / 20}_${it.bounds.centerX() / 30}" }
                .sortedBy { it.bounds.top }
        }
        return findNodesByText("点我得淘金币")
            .mapNotNull { qualify(it) }
            .distinctBy { "${it.bounds.centerY() / 20}_${it.bounds.centerX() / 30}" }
            .sortedBy { it.bounds.top }
    }

    private val plusCoinRe = Regex("""\+\s*(\d+)\s*金币""")
    private val plusCoinLooseRe = Regex("""\+\s*(\d+)""")

    private val taskCoinRes = listOf(
        Regex("得\\s*(\\d+)\\s*淘金币"),
        Regex("得\\s*(\\d+)\\s*金币"),
        Regex("领\\s*(\\d+)\\s*淘金币"),
        Regex("领\\s*(\\d+)\\s*金币"),
        Regex("\\+\\s*(\\d+)\\s*金币"),
    )

    /** 从任务标题/行内文案解析奖励金币数，如「得30淘金币」「+15金币」「+10~30」 */
    fun parseTaskCoinReward(text: String): Int? {
        val t = text.trim()
        if (t.isEmpty()) return null
        for (re in taskCoinRes) {
            re.find(t)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        }
        plusCoinRe.find(t)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        if (t.contains("+") && t.length <= 24) {
            plusCoinLooseRe.find(t)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        }
        return null
    }

    /** 任务列表一行上的金币数：先看标题，再看同行节点 */
    fun coinRewardOnTaskRow(row: TaskRow, nodes: List<UiNode> = snapshot()): Int? {
        parseTaskCoinReward(row.name)?.let { return it }
        val top = row.titleBounds.top - 24
        val bottom = row.titleBounds.bottom + 36
        val left = row.titleBounds.left - 8
        val right = (screenW() * 0.78f).toInt()
        for (n in nodes) {
            if (n.bounds.bottom < top || n.bounds.top > bottom) continue
            if (n.bounds.right < left || n.bounds.left > right) continue
            parseTaskCoinReward(n.label)?.let { return it }
            plusCoinRe.find(n.label)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        }
        return null
    }

    fun coinRewardNearGoButton(btn: UiNode, nodes: List<UiNode> = snapshot()): Int? {
        val name = resolveTaskName(btn, nodes)
        parseTaskCoinReward(name)?.let { return it }
        val top = btn.bounds.top - 80
        val bottom = btn.bounds.bottom + 24
        for (n in nodes) {
            if (n.bounds.bottom < top || n.bounds.top > bottom) continue
            if (n.bounds.right > btn.bounds.left + 40) continue
            parseTaskCoinReward(n.label)?.let { return it }
            plusCoinRe.find(n.label)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        }
        return null
    }

    /** 奖励 ≤ minCoins 则跳过；读不到金币数时不拦 */
    fun isLowCoinReward(reward: Int?, minCoins: Int): Boolean =
        reward != null && reward <= minCoins

    /** 卡片底部 +XX金币；排除任务顶栏「得XX淘金币」 */
    fun coinRewardOnProductCard(btn: UiNode, nodes: List<UiNode>): Int? {
        val cardTop = btn.bounds.top - 260
        val cardBottom = btn.bounds.bottom + 16
        var bestCoins: Int? = null
        var bestScore = Int.MAX_VALUE
        for (n in nodes) {
            if (n.bounds.bottom < cardTop || n.bounds.top > cardBottom) continue
            val t = n.label.trim()
            if (!plusCoinRe.containsMatchIn(t)) continue
            if (t.contains("得") && t.contains("淘金币") && t.contains("个商品")) continue
            val coins = plusCoinRe.find(t)?.groupValues?.get(1)?.toIntOrNull() ?: continue
            val score = kotlin.math.abs(n.cy - btn.cy) +
                if (n.bounds.bottom >= btn.bounds.top - 40) 0 else 30
            if (score < bestScore) {
                bestScore = score
                bestCoins = coins
            }
        }
        return bestCoins
    }

    /** 选一个可点商品；+金币 ≤ minCoins 的自动跳过 */
    fun pickClickProductButton(minCoins: Int, nodes: List<UiNode> = snapshot()): UiNode? {
        val buttons = findClickProductButtons(nodes)
        if (buttons.isEmpty()) {
            log("未找到合法「点我得淘金币」按钮")
            return null
        }
        for (btn in buttons) {
            val reward = coinRewardOnProductCard(btn, nodes)
            when {
                reward == null -> {
                    log("商品@${btn.bounds.centerY()} 未读到+金币，仍尝试")
                    return btn
                }
                reward <= minCoins -> log("跳过 +${reward}金币(≤$minCoins) @${btn.bounds.centerY()}")
                else -> {
                    log("选中 +${reward}金币 @${btn.bounds.centerY()}")
                    return btn
                }
            }
        }
        return null
    }

    fun swipeUp(distMin: Float, distMax: Float, durMin: Float, durMax: Float) {
        val w = screenW()
        val h = screenH()
        val margin = max(40, w / 10)
        val startX = Random.nextInt(margin, w - margin)
        val startY = Random.nextInt((h * 0.78f).toInt(), (h * 0.94f).toInt())
        val dist = (h * Random.nextFloat().let { distMin + it * (distMax - distMin) }).toInt()
        val endY = max((h * 0.06f).toInt(), startY - dist)
        val endX = (startX + Random.nextInt(-(w * 0.12f).toInt(), (w * 0.12f).toInt() + 1))
            .coerceIn(margin, w - margin)
        val dur = ((durMin + Random.nextFloat() * (durMax - durMin)) * 1000)
            .toLong().coerceIn(280L, 900L)
        val path = Path().apply {
            moveTo(startX.toFloat(), startY.toFloat())
            quadTo(
                ((startX + endX) / 2 + Random.nextInt(-40, 41)).toFloat(),
                ((startY + endY) / 2).toFloat(),
                endX.toFloat(),
                endY.toFloat(),
            )
        }
        log("模拟滑动 ($startX,$startY)->($endX,$endY) ${startY - endY}px")
        gesture(path, dur)
    }

    /** 列表内下滑 + 可滚动容器转发 */
    fun swipeListDown() {
        val w = screenW()
        val h = screenH()
        val x = w / 2 + Random.nextInt(-30, 31)
        val y1 = (h * 0.72f).toInt()
        val y2 = (h * 0.28f).toInt()
        val path = Path().apply {
            moveTo(x.toFloat(), y1.toFloat())
            lineTo(x.toFloat(), y2.toFloat())
        }
        log("列表内下滑")
        gesture(path, 360)
        scrollListForward()
    }

    fun swipeListDownHard() {
        val w = screenW()
        val h = screenH()
        val x = w / 2
        val path = Path().apply {
            moveTo(x.toFloat(), (h * 0.82f))
            lineTo(x.toFloat(), (h * 0.18f))
        }
        log("列表大幅下滑")
        gesture(path, 420)
        repeat(2) { scrollListForward() }
    }

    fun scrollListForward(): Boolean = onMain {
        val root = readTargetRoot() ?: return@onMain false
        var ok = false
        walkScrollable(root) { node ->
            if (node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
                ok = true
                return@walkScrollable
            }
        }
        ok
    }

    private fun walkScrollable(node: AccessibilityNodeInfo, block: (AccessibilityNodeInfo) -> Unit) {
        if (node.isScrollable) block(node)
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            walkScrollable(c, block)
        }
    }

    fun listFingerprint(): String =
        findTaskRows().joinToString("|") { "${it.name}@${it.titleBounds.top}" }

    fun back(): Boolean =
        onMain { service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) }

    /**
     * 直接 Intent 拉起淘宝，不先回桌面（避免 HOME 闪一下，也减少部分机型误弹授权框的触发路径）。
     */
    fun launchTaobao(): Boolean {
        val pm = service.packageManager
        val intents = buildList {
            pm.getLaunchIntentForPackage(targetPkg)?.let { add(it) }
            val probe = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                setPackage(targetPkg)
            }
            pm.queryIntentActivities(probe, 0).forEach { ri ->
                add(
                    Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_LAUNCHER)
                        component = ComponentName(ri.activityInfo.packageName, ri.activityInfo.name)
                    },
                )
            }
            add(Intent(Intent.ACTION_VIEW, Uri.parse("taobao://")))
            add(Intent(Intent.ACTION_VIEW, Uri.parse("https://m.taobao.com")))
        }
        if (intents.isEmpty()) {
            log("找不到淘宝启动入口，请手动打开淘宝")
            return false
        }
        for (intent in intents.distinctBy { it.component?.flattenToString() ?: it.dataString }) {
            intent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED,
            )
            val ok = onMain {
                try {
                    service.startActivity(intent)
                    log("已拉起淘宝 (${intent.component?.className ?: intent.dataString})")
                    true
                } catch (e: Exception) {
                    try {
                        service.applicationContext.startActivity(intent)
                        log("已拉起淘宝(备用) (${intent.component?.className ?: intent.dataString})")
                        true
                    } catch (e2: Exception) {
                        log("拉起失败: ${e2.message}")
                        false
                    }
                }
            }
            if (ok) return true
        }
        return false
    }

    /** 系统双开选择器（OPPO/小米等）弹出中 */
    fun isDualAppPickerShowing(): Boolean {
        val pkg = currentPkg()
        if (pkg.contains("multiapp", true) || pkg.contains("dual", true) ||
            pkg.contains("clone", true) || pkg.contains("parallel", true)
        ) {
            return true
        }
        val text = pageText()
        return text.contains("选择打开的应用") || text.contains("选择要使用的应用") ||
            text.contains("选择应用") || (text.contains("淘宝") && text.contains("分身") && text.contains("取消"))
    }

    private fun tryClickDualAppByText(mode: Int): Boolean {
        if (!isDualAppPickerShowing()) return false
        val nodes = findNodesByText("淘宝", "淘宝(分身)", "取消")
        val hit = when (mode) {
            UserSettings.DUAL_CLONE -> nodes.firstOrNull { n ->
                val t = n.label
                t.contains("分身") || t.contains("双开") || t.contains("克隆")
            }
            else -> nodes.firstOrNull { n ->
                val t = n.label
                t.contains("淘宝") && !t.contains("分身") && !t.contains("双开")
            } ?: nodes.firstOrNull { it.label.trim() == "淘宝" }
        } ?: return false
        log("双开选择: 点文案「${hit.label}」")
        return clickBounds(hit.bounds, durationMs = 200)
    }

    /** 双开选择器：优先点文案，否则按用户坐标；防抖避免重复点击。 */
    fun handleDualAppPicker(): Boolean {
        val ctx = service.applicationContext
        val mode = UserSettings.getDualAppMode(ctx)
        if (mode == UserSettings.DUAL_OFF) return false
        if (isTaobaoForeground()) return false
        val now = System.currentTimeMillis()
        if (now - lastDualPickAt < DUAL_PICK_COOLDOWN_MS) return false

        var sawPicker = isDualAppPickerShowing()
        if (!sawPicker) {
            repeat(8) {
                sleep(0.25f)
                if (isDualAppPickerShowing()) {
                    sawPicker = true
                    return@repeat
                }
                if (isTaobaoForeground()) return false
            }
        }
        if (!sawPicker && !isDualAppPickerShowing()) return false

        lastDualPickAt = now
        val modeLabel = UserSettings.dualAppModeLabel(ctx)
        val beforePkg = currentPkg()

        if (tryClickDualAppByText(mode)) {
            sleep(1f)
            if (isTaobaoForeground() || !isDualAppPickerShowing()) {
                log("双开选择: 文案点击成功 [$modeLabel]")
                return true
            }
        }

        val (x, y) = when (mode) {
            UserSettings.DUAL_MAIN -> UserSettings.getDualCoord1(ctx)
            UserSettings.DUAL_CLONE -> UserSettings.getDualCoord2(ctx)
            else -> return false
        }
        if (x < 0 || y < 0) {
            log("双开已开启但未设置坐标，请到「任务设置」里选手动坐标")
            return false
        }
        val cx = x.coerceIn(2, screenW() - 3)
        val cy = y.coerceIn(2, screenH() - 3)
        log("双开选择: 坐标点击 ($cx,$cy) [$modeLabel]")
        clickXy(cx, cy, durationMs = 200, forgiving = true)
        sleep(1.2f)
        return when {
            isTaobaoForeground() -> {
                log("双开选择: 已进入淘宝")
                true
            }
            currentPkg() != beforePkg && !isDualAppPickerShowing() -> {
                log("双开选择: 选择器已关闭")
                true
            }
            else -> false
        }
    }

    fun ensureTaobao(): Boolean {
        if (isTaobaoForeground()) {
            log("当前已在淘宝")
            return true
        }
        if (isDualAppPickerShowing()) {
            log("检测到双开选择器 pkg=${currentPkg()}")
            handleDualAppPicker()
            repeat(5) { i ->
                sleep(if (i == 0) 2f else 1.5f)
                if (isTaobaoForeground()) {
                    log("淘宝已在前台")
                    return true
                }
            }
        }
        log("当前包名=${currentPkg()}，直接拉起淘宝（不回桌面）…")
        if (!launchTaobao()) return false
        sleep(0.8f)
        handleDualAppPicker()
        repeat(3) { i ->
            sleep(if (i == 0) 3.5f else 2f)
            if (isTaobaoForeground()) {
                log("淘宝已在前台")
                return true
            }
        }
        log("仍未进入淘宝，请手动切到淘宝后再点开始")
        return false
    }

    fun clickTextSearch(text: String): Boolean {
        val hits = findNodesByText(text)
        val hit = hits.firstOrNull { it.label.contains(text) } ?: return false
        log("点「${hit.label}」")
        return clickBounds(hit.bounds)
    }

    private fun readTargetRoot(): AccessibilityNodeInfo? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            service.windows?.forEach { win ->
                if (win.type != AccessibilityWindowInfo.TYPE_APPLICATION) return@forEach
                val root = win.root ?: return@forEach
                val pkg = root.packageName?.toString() ?: return@forEach
                if (pkg == targetPkg) return root
            }
            service.windows?.forEach { win ->
                if (win.type != AccessibilityWindowInfo.TYPE_APPLICATION) return@forEach
                val root = win.root ?: return@forEach
                val pkg = root.packageName?.toString() ?: return@forEach
                if (pkg != selfPkg && !pkg.contains("systemui", true)) return root
            }
        }
        val active = service.rootInActiveWindow ?: return null
        val pkg = active.packageName?.toString() ?: ""
        return if (pkg == selfPkg) null else active
    }

    private enum class GestureOutcome { COMPLETED, CANCELLED, DISPATCH_FAILED, TIMEOUT }

    private fun gestureOutcome(path: Path, durationMs: Long): GestureOutcome {
        val stroke = android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, durationMs)
        val desc = android.accessibilityservice.GestureDescription.Builder().addStroke(stroke).build()
        val completed = AtomicReference(false)
        val cancelled = AtomicReference(false)
        val dispatched = AtomicReference(false)
        val latch = CountDownLatch(1)
        main.post {
            val ok = service.dispatchGesture(
                desc,
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: android.accessibilityservice.GestureDescription?) {
                        completed.set(true)
                        latch.countDown()
                    }

                    override fun onCancelled(gestureDescription: android.accessibilityservice.GestureDescription?) {
                        cancelled.set(true)
                        latch.countDown()
                    }
                },
                null,
            )
            dispatched.set(ok)
            if (!ok) latch.countDown()
        }
        val waited = latch.await(3, TimeUnit.SECONDS)
        return when {
            completed.get() -> GestureOutcome.COMPLETED
            cancelled.get() -> GestureOutcome.CANCELLED
            !dispatched.get() -> GestureOutcome.DISPATCH_FAILED
            !waited -> GestureOutcome.TIMEOUT
            else -> GestureOutcome.TIMEOUT
        }
    }

    private fun gesture(path: Path, durationMs: Long): Boolean =
        gestureOutcome(path, durationMs) == GestureOutcome.COMPLETED

    private fun walk(node: AccessibilityNodeInfo, out: MutableList<UiNode>) {
        val b = Rect()
        node.getBoundsInScreen(b)
        if (b.width() <= 0 || b.height() <= 0) {
            // skip invalid
        } else {
            val t = node.text?.toString()?.trim().orEmpty()
            val d = node.contentDescription?.toString()?.trim().orEmpty()
            if (t.isNotEmpty() || d.isNotEmpty() || node.isClickable) {
                out.add(
                    UiNode(
                        text = t,
                        desc = d,
                        cls = node.className?.toString().orEmpty(),
                        bounds = Rect(b),
                        clickable = node.isClickable,
                    ),
                )
            }
        }
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            walk(c, out)
        }
    }

    private fun <T> onMain(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val box = AtomicReference<T>()
        val latch = CountDownLatch(1)
        main.post {
            try {
                box.set(block())
            } finally {
                latch.countDown()
            }
        }
        latch.await(8, TimeUnit.SECONDS)
        @Suppress("UNCHECKED_CAST")
        return box.get() as T
    }
}
