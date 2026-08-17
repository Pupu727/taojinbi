package com.pupu.taojinbi

import android.graphics.Rect
import kotlin.math.max

private data class PickResult(
    val btn: UiNode?,
    val clickRect: Rect?,
    val name: String?,
    val visibleCount: Int,
    val progressCur: Int? = null,
    val progressTarget: Int? = null,
)

class TaskEngine(
    private val svc: CoinA11yService,
    private val cfg: AppConfig,
    private val d: A11yDriver,
) {
    private val clicked = mutableSetOf<String>()
    private var homeEntryClicked = false
    private var savedEntryXy: Pair<Int, Int>? = null
    private var finishCount = 0
    private var noTaskCount = 0

    private var listScrollStuck = 0

    private enum class ScrollResult { MOVED, STUCK, AT_END }

    fun run() {
        d.log("目标 ${cfg.targetCount} | 放行${cfg.allowKeywords.size} 跳过${cfg.skipKeywords.size} | 金币≤${cfg.minProductCoinReward} | 无任务停${cfg.maxNoTaskCount}轮")
        try {
            if (!d.ensureTaobao()) {
                d.log("未进入淘宝，停止（请确认已安装淘宝后重试）")
                return
            }
            if (d.isClickProductTaskPage()) {
                d.log("当前在点商品页，跳过导航")
            } else {
                navigateToCoinTasks()
                enterTaskList()
            }
            while (svc.running.get()) {
                if (finishCount >= cfg.targetCount) {
                    d.log("✓ 完成 $finishCount/${cfg.targetCount}")
                    break
                }
                if (!d.isTaobaoForeground()) {
                    d.log("不在淘宝，重新拉起（不回桌面）")
                    d.ensureTaobao()
                    navigateToCoinTasks()
                    enterTaskList()
                    continue
                }
                d.log("查找任务 $finishCount/${cfg.targetCount} pkg=${d.currentPkg()}")
                if (recoverStrandedTask()) {
                    d.sleep(cfg.waitBetweenTasks)
                    continue
                }
                val nodes0 = d.snapshot()
                nodes0.firstOrNull { it.label == "立即领取" }?.let {
                    d.clickBounds(it.bounds)
                    d.sleep(2f)
                }
                var pick = pickNext()
                if (pick.btn == null && pick.clickRect == null && pick.visibleCount > 0) {
                    d.log("本屏 ${pick.visibleCount} 个均已屏蔽/已点，向下滚动")
                    if (scrollTaskList() == ScrollResult.AT_END && !handleFullListPass()) break
                    continue
                }
                if (pick.btn == null && pick.clickRect == null) {
                    val nodes = d.snapshot()
                    if (d.listOpen(nodes)) {
                        d.log("在列表但没可点任务，列表内下滑")
                        if (scrollTaskList() == ScrollResult.AT_END && !handleFullListPass()) break
                        pick = pickNext()
                        if (pick.btn == null && pick.clickRect == null && pick.visibleCount > 0) {
                            d.log("滚动后本屏 ${pick.visibleCount} 个仍不可点，继续滚")
                            if (scrollTaskList() == ScrollResult.AT_END && !handleFullListPass()) break
                            continue
                        }
                        if (pick.btn == null && pick.clickRect == null && pick.visibleCount == 0) {
                            if (scrollTaskList() == ScrollResult.AT_END && !handleFullListPass()) break
                            continue
                        }
                    } else {
                        if (d.isClickProductTaskPage()) {
                            recoverStrandedTask()
                            continue
                        }
                        val earn = nodes.any { n -> cfg.entryKeywords.any { n.label.contains(it) } }
                        if (earn) {
                            d.log("列表关了，重新点赚更多金币")
                            homeEntryClicked = false
                            savedEntryXy = null
                            enterTaskList()
                            continue
                        }
                        d.log("不在任务列表，回首页重进")
                        hardResetToTaskList()
                        continue
                    }
                }
                if (pick.btn != null || pick.clickRect != null) {
                    noTaskCount = 0
                    val name = pick.name ?: "未命名"
                    val rect = pick.clickRect ?: pick.btn!!.bounds
                    d.log("点击: $name")
                    clicked += d.taskProgressKey(
                        name, pick.progressCur, pick.progressTarget, rect,
                    )
                    d.clickBounds(rect)
                    d.sleep(1.5f)
                    dismissPopups(listSafe = false)
                    val clickProductsTask = isClickProductTaskName(name)
                    if (clickProductsTask) d.sleep(1.5f)
                    if (d.isClickProductTaskPage()) {
                        d.log("已进入点商品页")
                    } else if (d.listOpen()) {
                        d.log("点完仍在列表，跳过")
                        continue
                    }
                    val search = name.contains("搜一搜") || name.contains("搜索") ||
                        d.pageText().contains("搜索有福利")
                    if (search) doSearch()
                    val quiz = containsAny(name, cfg.quizKeywords)
                    val quick = !quiz && containsAny(name, cfg.quickReturnKeywords)
                    val waitOnly = !quiz && !quick && containsAny(name, cfg.waitOnlyKeywords)
                    val runClickProducts = clickProductsTask || d.isClickProductTaskPage()
                    if (quiz) d.log("趣味课堂")
                    if (quick) d.log("秒返")
                    if (waitOnly) d.log("沉浸看·只等待")
                    if (runClickProducts) d.log("点商品任务")
                    val ok = operateTask(search, quick, quiz, waitOnly, runClickProducts)
                    if (ok) {
                        finishCount += 1
                        d.log("✓ +1 → $finishCount/${cfg.targetCount}")
                    } else {
                        d.log("✗ 未回列表，仍 $finishCount/${cfg.targetCount}")
                    }
                    noTaskCount = 0
                } else {
                    d.log("未找到可执行任务")
                    if (d.isClickProductTaskPage()) {
                        recoverStrandedTask()
                        continue
                    }
                    if (d.listOpen()) {
                        if (scrollTaskList() == ScrollResult.AT_END && !handleFullListPass()) break
                    } else {
                        hardResetToTaskList()
                    }
                }
                d.sleep(cfg.waitBetweenTasks)
            }
        } catch (e: Exception) {
            d.log("异常: ${e.message}")
        }
        if (!svc.running.get()) {
            d.log("已暂停，共 $finishCount/${cfg.targetCount}")
        } else {
            d.log("结束，共 $finishCount/${cfg.targetCount}")
        }
        svc.running.set(false)
    }

    /** 列表已到底且全程无可做任务：计 1 轮，重开列表再扫，满 N 轮则停 */
    private fun handleFullListPass(): Boolean {
        noTaskCount += 1
        d.log("✓ 全列表无可做任务 第 $noTaskCount/${cfg.maxNoTaskCount} 轮")
        listScrollStuck = 0
        if (noTaskCount >= cfg.maxNoTaskCount) {
            pauseAtListEnd()
            return false
        }
        reopenTaskList()
        return true
    }

    /** 关掉当前列表弹层，再点「赚更多金币」从头打开 */
    private fun reopenTaskList() {
        d.log("重开任务列表（关闭后再进）")
        homeEntryClicked = false
        repeat(3) {
            if (!svc.running.get()) return
            if (!d.listOpen()) return@repeat
            d.back()
            d.sleep(0.8f)
        }
        d.sleep(1f)
        val entry = d.findNodesByText("赚更多金币").firstOrNull { n ->
            val t = n.label.trim()
            t.contains("赚更多金币") && t.length <= 14
        }
        if (entry != null) {
            d.log("点「赚更多金币」")
            savedEntryXy = entry.bounds.centerX() to entry.bounds.centerY()
            d.clickBounds(entry.bounds)
            homeEntryClicked = true
            d.sleep(4f)
            if (d.listOpen()) {
                d.log("✓ 列表已重开")
                return
            }
        }
        savedEntryXy?.let { (x, y) ->
            d.log("原位置再点赚更多金币")
            d.clickXy(x, y)
            homeEntryClicked = true
            d.sleep(4f)
            if (d.listOpen()) {
                d.log("✓ 列表已重开")
                return
            }
        }
        d.log("重开未果，走 enterTaskList")
        enterTaskList()
    }

    /** 列表已到底、连续 N 轮全跳过，自动暂停 */
    private fun pauseAtListEnd() {
        d.log("✓ ${cfg.maxNoTaskCount} 轮遍历均无新任务，停止")
        d.log("本轮完成 $finishCount/${cfg.targetCount}，点「继续」恢复")
        listScrollStuck = 0
        svc.stopLoop()
    }

    private fun scrollTaskList(): ScrollResult {
        val before = d.listFingerprint()
        d.swipeListDown()
        d.sleep(2f)
        var after = d.listFingerprint()
        if (before.isNotBlank() && before == after) {
            d.log("列表未动，加大滑动")
            d.swipeListDownHard()
            d.sleep(2f)
            after = d.listFingerprint()
        }
        if (before.isBlank() || before != after) {
            listScrollStuck = 0
            return ScrollResult.MOVED
        }
        listScrollStuck += 1
        d.log("滑动后仍无新任务 ($listScrollStuck/2)")
        if (listScrollStuck >= 2) {
            if (d.isClickProductTaskPage()) {
                d.log("滑动无进展，实为点商品页")
                listScrollStuck = 0
                return ScrollResult.STUCK
            }
            listScrollStuck = 0
            return ScrollResult.AT_END
        }
        return ScrollResult.STUCK
    }

    private fun pickNext(): PickResult {
        val nodes = d.snapshot()
        val rows = d.findTaskRows(nodes)
        val gos = d.findGoButtons(nodes)
        val total = max(rows.size, gos.size)

        if (total == 0) {
            d.log("未看到任务行/去完成")
            return PickResult(null, null, null, 0)
        }
        d.log("任务行 ${rows.size} 条 | 去完成 ${gos.size} 个")

        rows.forEachIndexed { i, row ->
            val name = row.name
            if (!d.isValidTaskName(name)) {
                d.log("  [行$i] $name 标题无效，跳过")
                return@forEachIndexed
            }
            if (shouldSkipTask(name, cfg.allowKeywords, cfg.skipKeywords)) {
                d.log("  [行$i] $name 屏蔽")
                return@forEachIndexed
            }
            if (containsAny(name, cfg.skipKeywords) && containsAny(name, cfg.allowKeywords)) {
                d.log("  [行$i] $name 白名单覆盖黑名单")
            }
            if (d.shouldSkipAsClicked(name, row.progressCur, row.progressTarget, row.clickBounds, clicked)) {
                val prog = row.progressCur?.let { c -> row.progressTarget?.let { t -> "($c/$t)" } } ?: ""
                d.log("  [行$i] $name$prog 本进度已点")
                return@forEachIndexed
            }
            if (d.isTaskRowCompleted(row, nodes)) {
                clicked += d.taskProgressKey(name, row.progressCur, row.progressTarget, row.clickBounds)
                val prog = row.progressCur?.let { c -> row.progressTarget?.let { t -> "($c/$t)" } } ?: ""
                d.log("  [行$i] $name$prog 已完成，跳过")
                return@forEachIndexed
            }
            val reward = d.coinRewardOnTaskRow(row, nodes)
            val allowForce = containsAny(name, cfg.allowKeywords)
            if (!allowForce && d.isLowCoinReward(reward, cfg.minProductCoinReward)) {
                d.log("  [行$i] $name 金币≤${cfg.minProductCoinReward}(${reward})，屏蔽")
                return@forEachIndexed
            }
            val go = gos.firstOrNull { kotlin.math.abs(it.cy - row.titleBounds.centerY()) < 55 }
            if (go == null) {
                d.log("  [行$i] $name 无去完成，跳过")
                return@forEachIndexed
            }
            d.log("  [行$i] 选中 $name${formatProg(row.progressCur, row.progressTarget)}")
            return PickResult(go, go.bounds, name, total, row.progressCur, row.progressTarget)
        }

        gos.forEachIndexed { i, btn ->
            val matchedRow = d.matchTaskRowForGo(btn, rows)
            if (matchedRow == null) {
                d.log("  [$i] 去完成无任务行匹配，跳过")
                return@forEachIndexed
            }
            val name = matchedRow.name
            val pCur = matchedRow.progressCur
            val pTarget = matchedRow.progressTarget
            if (d.isUnnamedTask(name)) {
                clicked += d.taskProgressKey(name, pCur, pTarget, btn.bounds)
                d.log("  [$i] 未识别任务，自动屏蔽")
                return@forEachIndexed
            }
            if (!d.isValidTaskName(name)) {
                d.log("  [$i] $name 无效/页面文案，跳过")
                return@forEachIndexed
            }
            if (shouldSkipTask(name, cfg.allowKeywords, cfg.skipKeywords)) {
                d.log("  [$i] $name 屏蔽")
                return@forEachIndexed
            }
            if (containsAny(name, cfg.skipKeywords) && containsAny(name, cfg.allowKeywords)) {
                d.log("  [$i] $name 白名单覆盖黑名单")
            }
            if (d.shouldSkipAsClicked(name, pCur, pTarget, btn.bounds, clicked)) {
                d.log("  [$i] $name${formatProg(pCur, pTarget)} 本进度已点")
                return@forEachIndexed
            }
            if (matchedRow != null && d.isTaskRowCompleted(matchedRow, nodes)) {
                clicked += d.taskProgressKey(name, pCur, pTarget, btn.bounds)
                d.log("  [$i] $name 已完成，跳过")
                return@forEachIndexed
            }
            if (d.isGoRowCompleted(btn, nodes)) {
                clicked += d.taskProgressKey(name, pCur, pTarget, btn.bounds)
                d.log("  [$i] $name 已完成，跳过")
                return@forEachIndexed
            }
            val reward = d.coinRewardOnTaskRow(matchedRow, nodes)
            val allowForce = containsAny(name, cfg.allowKeywords)
            if (!allowForce && d.isLowCoinReward(reward, cfg.minProductCoinReward)) {
                d.log("  [$i] $name 金币≤${cfg.minProductCoinReward}(${reward})，屏蔽")
                return@forEachIndexed
            }
            d.log("  [$i] 选中 $name${formatProg(pCur, pTarget)}")
            return PickResult(btn, btn.bounds, name, total, pCur, pTarget)
        }
        return PickResult(null, null, null, total)
    }

    private fun formatProg(cur: Int?, target: Int?): String =
        if (cur != null && target != null) "($cur/$target)" else ""

    /** 从淘宝任意页导航到淘金币任务列表 */
    private fun navigateToCoinTasks() {
        d.log("导航到淘金币任务列表…")
        homeEntryClicked = false
        savedEntryXy = null
        val maxAttempts = 5
        repeat(maxAttempts) { attempt ->
            if (!svc.running.get()) return
            if (d.listOpen()) {
                d.log("✓ 已在任务列表")
                homeEntryClicked = true
                return
            }
            val coinEntry = d.findNodesByText("领淘金币", "淘金币").firstOrNull { n ->
                val t = n.label
                (t.contains("领淘金币") || t == "淘金币") && t.length <= 12
            } ?: d.snapshot().firstOrNull {
                it.label.contains("领淘金币") || (it.desc.contains("淘金币") && it.label.length <= 8)
            }
            if (coinEntry != null) {
                d.log("点「${coinEntry.label.ifBlank { coinEntry.desc }}」进淘金币")
                d.clickBounds(coinEntry.bounds)
                d.sleep(4f)
                enterTaskList()
                if (d.listOpen()) {
                    d.log("✓ 导航成功")
                    return
                }
            }
            if (findHomeButton(cfg.checkinKeywords, ignore = listOf("领红包", "神器", "已签")) != null ||
                findHomeButton(cfg.entryKeywords, ignore = listOf("签到", "再赚")) != null
            ) {
                enterTaskList()
                if (d.listOpen()) {
                    d.log("✓ 已在淘金币首页并打开列表")
                    return
                }
            }
            d.log("导航重试 ${attempt + 1}/$maxAttempts")
            d.ensureTaobao()
            d.sleep(2f)
        }
        d.log("⚠ 导航未确认到任务列表，继续尝试")
    }

    private fun enterTaskList() {
        d.sleep(1f)
        if (d.listOpen()) {
            d.log("已在任务列表")
            homeEntryClicked = true
            return
        }
        if (homeEntryClicked) {
            d.log("已点过入口，等列表")
            d.sleep(2f)
            if (d.listOpen()) return
            homeEntryClicked = false
        }
        if (d.listOpen()) {
            homeEntryClicked = true
            return
        }
        val checkinHit = findHomeButton(cfg.checkinKeywords, ignore = listOf("领红包", "神器", "已签"))
        if (checkinHit != null) {
            val (node, label) = checkinHit
            d.log("签到: $label")
            savedEntryXy = node.bounds.centerX() to node.bounds.centerY()
            d.clickBounds(node.bounds)
            d.sleep(2.5f)
            val xy = savedEntryXy
            if (xy != null) {
                d.log("原位置再点（应是赚更多金币）")
                d.clickXy(xy.first, xy.second)
            }
            homeEntryClicked = true
            d.sleep(4f)
            if (d.listOpen()) return
        }
        val entryHit = findHomeButton(cfg.entryKeywords, ignore = listOf("签到", "再赚"))
        if (entryHit != null) {
            val (node, label) = entryHit
            d.log("点「$label」进列表")
            d.clickBounds(node.bounds)
            homeEntryClicked = true
            d.sleep(4f)
            if (d.listOpen()) return
        }
        d.log("未找到入口（pkg=${d.currentPkg()}）")
        val sample = d.snapshot().filter { it.label.length in 2..20 }.take(8).joinToString { it.label }
        if (sample.isNotBlank()) d.log("屏上文案: $sample")
        homeEntryClicked = false
    }

    /** 首页主按钮：优先按文案搜索，H5 比 walk 更准 */
    private fun findHomeButton(
        keywords: List<String>,
        ignore: List<String>,
    ): Pair<UiNode, String>? {
        for (k in keywords) {
            for (n in d.findNodesByText(k)) {
                val t = n.label
                if (t.length > 14) continue
                if (ignore.any { t.contains(it) }) continue
                if (t.contains(k) || t == k) return n to t
            }
        }
        val nodes = d.snapshot()
        return nodes.firstOrNull { n ->
            val t = n.label
            keywords.any { t.contains(it) } && t.length <= 14 &&
                ignore.none { t.contains(it) }
        }?.let { it to it.label }
    }

    private fun operateTask(
        search: Boolean,
        quick: Boolean,
        quiz: Boolean,
        waitOnly: Boolean,
        clickProducts: Boolean,
    ): Boolean {
        if (!d.isTaobaoForeground()) {
            d.log("任务页不在淘宝，放弃本次")
            return false
        }
        dismissPopups(listSafe = false)
        if (d.snapshot().any { it.label == "取消" }) {
            d.clickText("取消")
            d.sleep(1f)
            return false
        }
        if (quiz) {
            d.log("趣味课堂: 点选项 → 我选好了 → 等完成")
            clickQuizOption()
            clickQuizSubmit()
            dismissPopups(listSafe = false)
            waitForCompletion((cfg.maxWaitDuration * 1000).toLong())
            dismissPopups(listSafe = false)
            return returnToList(search = false, forceExternal = false)
        }
        if (quick) {
            val settle = 5f
            d.log("秒返等待 ${settle.toInt()}s 后返回")
            d.sleep(settle)
            dismissPopups(listSafe = false)
            return returnToList(search, forceExternal = true)
        }
        if (d.listOpen() && !d.isClickProductTaskPage()) {
            d.log("仍在列表，不浏览滑动")
            return false
        }
        if (clickProducts || d.isClickProductTaskPage()) {
            return operateClickProductsTask()
        }
        val timeout = (cfg.maxWaitDuration * 1000).toLong()
        if (waitOnly) {
            d.log("等待完成（不滑动）最多${cfg.maxWaitDuration}s")
            waitForCompletion(timeout)
            return returnToList(search, forceExternal = false)
        }
        val start = System.currentTimeMillis()
        val interval = cfg.swipeInterval.coerceAtLeast(0.8f)
        d.log("浏览滑动 间隔${interval}s 超时${cfg.maxWaitDuration}s")
        var round = 0
        while (svc.running.get()) {
            if (System.currentTimeMillis() - start > timeout) {
                d.log("超时返回")
                break
            }
            val (done, how) = detectDone()
            if (done) {
                d.log("完成: $how")
                break
            }
            if (round == 0) d.sleep(1.2f)
            d.swipeUp(cfg.distMin, cfg.distMax, cfg.swipeDurMin, cfg.swipeDurMax)
            round += 1
            val (done2, how2) = detectDone()
            if (done2) {
                d.log("完成: $how2")
                break
            }
            d.sleep(interval)
        }
        return returnToList(search, forceExternal = false)
    }

    /** 沉浸看等：倒计时自动完成，不需滑动 */
    private fun waitForCompletion(timeoutMs: Long) {
        val start = System.currentTimeMillis()
        while (svc.running.get()) {
            if (System.currentTimeMillis() - start > timeoutMs) {
                d.log("等待超时")
                break
            }
            val (done, how) = detectDone()
            if (done) {
                d.log("完成: $how")
                break
            }
            d.sleep(1.2f)
        }
    }

    /**
     * 点 N 个商品得金币：识别 点3个商品，得30淘金币(0/3)，
     * 循环点「点我得淘金币」→ 返回列表页，直到 (N/N)。
     */
    private fun operateClickProductsTask(): Boolean {
        d.log("点商品流程")
        val deadline = System.currentTimeMillis() + (cfg.maxWaitDuration * 1000).toLong()
        var noBtnRounds = 0
        while (svc.running.get() && System.currentTimeMillis() < deadline) {
            if (d.listOpen()) {
                d.log("意外回到任务列表")
                return false
            }
            val text = d.pageText()
            val progress = d.parseProductClickProgress(text)
            if (progress != null) {
                val (cur, target, coins) = progress
                d.log("点商品进度 $cur/$target (${coins}金币)")
                if (cur >= target) {
                    d.log("✓ 商品已点够")
                    break
                }
            }
            if (text.contains("已得") || detectDone().first) {
                d.log("✓ 检测到完成")
                break
            }
            val btn = d.pickClickProductButton(cfg.minProductCoinReward)
            if (btn == null) {
                noBtnRounds += 1
                if (noBtnRounds >= 4) {
                    d.log("未找到可点商品按钮")
                    break
                }
                d.log("下滑找商品 ($noBtnRounds)")
                d.swipeListDown()
                d.sleep(1.5f)
                continue
            }
            noBtnRounds = 0
            d.log("点: 点我得淘金币")
            if (!d.clickProductCoinButton(btn)) {
                d.log("点击无效，下滑换商品")
                d.swipeListDown()
                d.sleep(1.5f)
                continue
            }
            d.sleep(1.5f)
            dismissPopups(listSafe = false)
            backToProductListPage()
            d.sleep(1f)
        }
        return returnToList(search = false, forceExternal = false)
    }

    /** 从商品详情/back stack 回到补贴商品列表 */
    private fun backToProductListPage() {
        repeat(3) {
            if (!svc.running.get()) return
            val t = d.pageText()
            if (d.parseProductClickProgress(t) != null || t.contains("点我得淘金币") ||
                t.contains("补贴商品") || t.contains("已点击商品")
            ) {
                return
            }
            if (d.listOpen()) return
            d.back()
            d.sleep(0.9f)
        }
    }

    private fun detectDone(): Pair<Boolean, String?> {
        val text = d.pageText()
        if (looksCountdown(text) && !text.contains("已得") && !text.contains("已完成")) return false to null
        if (text.contains("已得")) return true to "已得"
        if (text.contains("已成功领取奖励")) return true to "已成功领取奖励"
        for (k in cfg.completionKeywords) {
            if (k.isNotBlank() && text.contains(k) && !looksCountdown(k)) return true to k
        }
        return false to null
    }

    private fun looksCountdown(text: String): Boolean {
        if (text.contains("已完成") || text.contains("已得")) return false
        return Regex("浏览\\s*[1-9]\\d*\\s*秒").containsMatchIn(text)
    }

    private fun returnToList(search: Boolean, forceExternal: Boolean): Boolean {
        d.log("返回列表")
        val minBack = when {
            forceExternal -> maxOf(2, cfg.minBackSearch)
            search -> cfg.minBackSearch
            else -> cfg.minBackNormal
        }
        var backCount = 0
        while (svc.running.get() && backCount < cfg.maxBackTimes) {
            if (d.listOpen()) {
                if (backCount == 0) {
                    d.log("已在列表(未进子页?)")
                    return false
                }
                d.log("✓ 回列表 $backCount 次")
                return true
            }
            val pkg = d.currentPkg()
            if (pkg.contains("launcher", true) || (pkg.contains("welcome", true) && backCount > 0)) {
                d.ensureTaobao()
                navigateToCoinTasks()
                enterTaskList()
                return d.listOpen()
            }
            dismissPopups(listSafe = true)
            if (d.listOpen() && backCount >= minBack) return true
            d.back()
            d.sleep(0.8f)
            backCount += 1
        }
        return d.listOpen()
    }

    private fun dismissPopups(listSafe: Boolean) {
        val nodes = d.snapshot()
        if (listSafe && d.listOpen(nodes)) return
        if (d.listOpen(nodes)) return
        for (k in cfg.popupCloseKeywords) {
            val hit = nodes.firstOrNull { it.label == k || it.label.contains(k) }
            if (hit != null) {
                d.log("弹窗: ${hit.label}")
                d.clickBounds(hit.bounds)
                d.sleep(0.4f)
                return
            }
        }
    }

    /** 已在点商品子页时直接续跑，避免被误判成任务列表 */
    private fun recoverStrandedTask(): Boolean {
        if (!d.isClickProductTaskPage()) return false
        d.log("检测到点商品任务页，继续执行")
        val ok = operateClickProductsTask()
        if (ok) {
            finishCount += 1
            d.log("✓ +1 → $finishCount/${cfg.targetCount}")
        } else {
            d.log("点商品未完成，尝试回列表")
            if (!d.listOpen()) hardResetToTaskList()
        }
        noTaskCount = 0
        listScrollStuck = 0
        return true
    }

    /** 连按返回离开异常页，再从淘宝首页重进淘金币列表 */
    private fun hardResetToTaskList() {
        d.log("异常状态，连按返回并重新进淘金币")
        homeEntryClicked = false
        savedEntryXy = null
        repeat(5) {
            if (!svc.running.get()) return
            if (d.listOpen() && !d.isClickProductTaskPage()) return
            d.back()
            d.sleep(0.7f)
        }
        d.ensureTaobao()
        d.sleep(1.5f)
        navigateToCoinTasks()
        enterTaskList()
    }

    private fun isClickProductTaskName(name: String): Boolean {
        if (containsAny(name, cfg.clickProductKeywords)) return true
        if (Regex("点\\d+个商品").containsMatchIn(name)) return true
        return name.contains("商品") && name.contains("淘金币")
    }

    private fun containsAny(text: String, keys: List<String>): Boolean =
        keys.any { text.contains(it) }

    private fun doSearch() {
        val nodes = d.snapshot()
        val edit = nodes.firstOrNull { it.cls.contains("EditText") } ?: return
        d.clickBounds(edit.bounds)
        d.sleep(0.8f)
        val tips = d.snapshot().filter { n ->
            val t = n.label
            t.length in 2..18 && !t.contains("搜索") && !t.contains("取消") &&
                n.bounds.top >= edit.bounds.bottom - 20
        }
        if (tips.isNotEmpty()) {
            val hit = tips.random()
            d.log("搜: ${hit.label}")
            d.clickBounds(hit.bounds)
            d.sleep(2f)
        }
    }

    private fun isQuizNoiseOption(text: String): Boolean {
        if (text.isBlank()) return true
        val noise = listOf(
            "返回", "关闭", "取消", "确定", "提交", "下一题", "上一题",
            "去完成", "去逛逛", "逛一逛", "立即领取", "搜索", "分享",
            "规则", "说明", "淘金币", "赚金币", "赚更多", "我选好了", "选好了",
        )
        return noise.any { text.contains(it) }
    }

    private fun clickQuizOption(): Boolean {
        d.log("趣味课堂: 等待题目")
        d.sleep(2.5f)
        val h = d.screenH()
        val nodes = d.snapshot()
        val candidates = mutableListOf<UiNode>()
        val optionRe = Regex("^[ABCDa-d]([.\\s、].*)?$")

        for (n in nodes) {
            val t = n.label.trim()
            if (isQuizNoiseOption(t)) continue
            val cy = n.cy
            if (cy <= h * 0.2 || cy >= h * 0.85) continue
            when {
                n.cls.contains("RadioButton", ignoreCase = true) -> candidates.add(n)
                n.cls.contains("Button", ignoreCase = true) &&
                    (optionRe.containsMatchIn(t) || t.length in 2..40) -> candidates.add(n)
                n.clickable && t.length in 1..60 &&
                    (t.startsWith("A") || t.startsWith("B") || t.startsWith("C") || t.startsWith("D") ||
                        Regex("^[ABCD][.、]").containsMatchIn(t)) -> candidates.add(n)
            }
        }
        if (candidates.isEmpty()) {
            for (letter in listOf("A", "B", "C", "D")) {
                nodes.filter {
                    val t = it.label.trim()
                    !isQuizNoiseOption(t) &&
                        (t.startsWith(letter) || Regex("^$letter[.、]").containsMatchIn(t))
                }.forEach { candidates.add(it) }
            }
        }
        val uniq = candidates.distinctBy { it.label.trim() }
        if (uniq.isEmpty()) {
            d.log("趣味课堂: 未识别到选项")
            return false
        }
        val pick = uniq.random()
        d.log("趣味课堂: 点选项「${pick.label}」")
        d.clickBounds(pick.bounds)
        d.sleep(1.2f)
        return true
    }

    /** 点底部「我选好了」提交答案 */
    private fun clickQuizSubmit(): Boolean {
        val h = d.screenH()
        val submitKeys = listOf("我选好了", "选好了", "确认提交", "提交答案")
        repeat(6) {
            val hit = d.snapshot()
                .filter { n ->
                    val t = n.label
                    submitKeys.any { k -> t.contains(k) } &&
                        n.bounds.top > h * 0.55 &&
                        n.bounds.height() > 16
                }
                .maxByOrNull { it.bounds.bottom }
            if (hit != null) {
                d.log("趣味课堂: 点「${hit.label}」")
                d.clickBounds(hit.bounds)
                d.sleep(1.5f)
                return true
            }
            d.sleep(0.6f)
        }
        if (d.clickText("我选好了")) {
            d.log("趣味课堂: 点我选好了")
            d.sleep(1.5f)
            return true
        }
        d.log("趣味课堂: 未找到「我选好了」")
        return false
    }
}
