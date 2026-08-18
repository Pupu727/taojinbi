package com.pupu.taojinbi

import android.content.Context
import android.os.Environment
import java.io.File

data class AppConfig(
    val packageName: String,
    val targetCount: Int,
    val skipKeywords: List<String>,
    val allowKeywords: List<String>,
    val quizKeywords: List<String>,
    val quickReturnKeywords: List<String>,
    val completionKeywords: List<String>,
    val checkinKeywords: List<String>,
    val entryKeywords: List<String>,
    val popupCloseKeywords: List<String>,
    val maxWaitDuration: Float,
    val swipeInterval: Float,
    val waitBetweenTasks: Float,
    val maxNoTaskCount: Int,
    val maxBackTimes: Int,
    val minBackSearch: Int,
    val minBackNormal: Int,
    val swipeDurMin: Float,
    val swipeDurMax: Float,
    val distMin: Float,
    val distMax: Float,
    val searchKeyword: String,
    val quickReturnSettle: Float,
    val waitOnlyKeywords: List<String>,
    val clickProductKeywords: List<String>,
    val minProductCoinReward: Int,
    val maxStaleProgressAttempts: Int,
)

data class ConfigLoadInfo(
    val config: AppConfig,
    /** 给用户看的配置来源说明 */
    val summary: String,
)

object ConfigLoader {
    private const val DIR_NAME = "taojinbi"
    private const val SKIP_FILE = "skip.txt"
    private const val CONFIG_FILE = "config.yaml"
    private const val PREFS = "taojinbi_user"
    private const val KEY_BUNDLED_CONFIG_HASH = "bundled_config_hash"

    /** 手机文件管理器可直接编辑：/sdcard/taojinbi/skip.txt */
    fun publicDir(): File = File(Environment.getExternalStorageDirectory(), DIR_NAME)

    fun publicSkipFile(): File = File(publicDir(), SKIP_FILE)

    fun publicConfigFile(): File = File(publicDir(), CONFIG_FILE)

    /** 无需存储权限的备用路径（USB 传到 Android/data/.../files/taojinbi/） */
    fun appSkipFile(context: Context): File =
        File(File(context.getExternalFilesDir(null), DIR_NAME), SKIP_FILE)

    fun appConfigFile(context: Context): File =
        File(File(context.getExternalFilesDir(null), DIR_NAME), CONFIG_FILE)

    /**
     * 包内 assets/config.yaml 有变更时（新装或覆盖安装），强制下发并覆盖：
     * 1) app 外置 config.yaml（及可写的共享目录）
     * 2) App 当前设置（SharedPreferences 白/黑名单与目标数）
     *
     * 用内容指纹判断，不依赖 versionCode；同一份 config 不会重复刷设置。
     */
    fun ensureBundledConfigInstalled(context: Context) {
        val assetsText = readBundledYaml(context)
        val hash = fingerprint(assetsText)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_BUNDLED_CONFIG_HASH, null) == hash) return

        val app = appConfigFile(context)
        app.parentFile?.mkdirs()
        app.writeText(assetsText, Charsets.UTF_8)
        runCatching {
            val pub = publicConfigFile()
            pub.parentFile?.mkdirs()
            pub.writeText(assetsText, Charsets.UTF_8)
        }

        val cfg = parseYaml(assetsText)
        UserSettings.save(
            context,
            skipKeywords = cfg.skipKeywords,
            allowKeywords = cfg.allowKeywords,
            targetCount = cfg.targetCount,
            skipPool = cfg.skipKeywords,
            allowPool = cfg.allowKeywords,
        )
        prefs.edit()
            .putString(KEY_BUNDLED_CONFIG_HASH, hash)
            .remove("bundled_config_installed")
            .apply()
    }

    fun load(context: Context): AppConfig = loadWithInfo(context).config

    fun defaultAllowKeywords(context: Context): List<String> =
        parseYaml(readBundledYaml(context)).allowKeywords

    fun defaultSkipKeywords(context: Context): List<String> =
        parseYaml(readBundledYaml(context)).skipKeywords

    fun defaultTargetCount(context: Context): Int =
        parseYaml(readBundledYaml(context)).targetCount

    fun loadWithInfo(context: Context): ConfigLoadInfo {
        ensureBundledConfigInstalled(context)
        val baseText = readBaseYaml(context)
        val baseFrom = when {
            appConfigFile(context).isReadableFile() -> appConfigFile(context).absolutePath
            publicConfigFile().isReadableFile() -> publicConfigFile().absolutePath
            else -> "assets/config.yaml"
        }
        var cfg = parseYaml(baseText)
        when {
            UserSettings.isSaved(context) -> {
                cfg = cfg.copy(
                    skipKeywords = UserSettings.getSkipKeywords(context),
                    allowKeywords = UserSettings.getAllowKeywords(context),
                    targetCount = UserSettings.getTargetCount(context),
                )
            }
            loadExternalSkip(context) != null -> {
                cfg = cfg.copy(skipKeywords = loadExternalSkip(context)!!)
            }
        }
        val summary = "配置: $baseFrom | 放行${cfg.allowKeywords.size} 跳过${cfg.skipKeywords.size} | 金币≤${cfg.minProductCoinReward} | 目标${cfg.targetCount}"
        return ConfigLoadInfo(cfg, summary)
    }

    /** @deprecated 请使用 App 内「任务设置」 */
    fun ensureSkipTemplate(context: Context): File {
        val target = when {
            publicDir().exists() || publicDir().mkdirs() -> publicSkipFile()
            else -> appSkipFile(context).also { it.parentFile?.mkdirs() }
        }
        if (!target.exists()) {
            target.writeText(
                """
                # 每行一个要跳过的任务关键词（任务名「包含」即跳过）
                # 改完保存后，点「继续」或重新「开始」即生效，无需重装 APK
                #
                # 示例（删掉行首 # 即生效）：
                # 拉好友
                # 下单
                # 看直播
                """.trimIndent() + "\n",
                Charsets.UTF_8,
            )
        }
        return target
    }

    /** 仅读 APK 包内 assets，作为权威默认源 */
    private fun readBundledYaml(context: Context): String =
        context.assets.open(CONFIG_FILE).bufferedReader(Charsets.UTF_8).use { it.readText() }

    /** 运行时基线：外置可解析则用外置，否则用包内 */
    private fun readBaseYaml(context: Context): String {
        val assetsText = readBundledYaml(context)
        val external = listOf(appConfigFile(context), publicConfigFile())
            .firstOrNull { it.isReadableFile() }
        if (external != null) {
            val text = runCatching { external.readText(Charsets.UTF_8) }.getOrNull()
            if (text != null) {
                val ok = runCatching { parseYaml(text); true }.getOrDefault(false)
                if (ok) return text
            }
        }
        return assetsText
    }

    private fun fingerprint(text: String): String =
        "${text.length}:${text.hashCode()}"

    private fun loadExternalSkip(context: Context): List<String>? {
        val file = listOf(publicSkipFile(), appSkipFile(context))
            .firstOrNull { it.isReadableFile() }
            ?: return null
        val lines = file.readLines(Charsets.UTF_8)
            .map { it.substringBefore("#").trim() }
            .filter { it.isNotEmpty() }
        return lines.ifEmpty { null }
    }

    private fun File.isReadableFile(): Boolean = isFile && canRead() && length() > 0L

    private fun parseYaml(text: String): AppConfig {
        val lists = parseYamlLists(text)
        val nums = parseYamlScalars(text)
        return AppConfig(
            packageName = nums["app.package_name"] ?: nums["package_name"] ?: "com.taobao.taobao",
            targetCount = nums["task.coin.target_count"]?.toIntOrNull()
                ?: nums["target_count"]?.toIntOrNull() ?: 30,
            skipKeywords = lists["skip_keywords"] ?: emptyList(),
            allowKeywords = lists["allow_keywords"] ?: emptyList(),
            quizKeywords = lists["quiz_keywords"] ?: emptyList(),
            quickReturnKeywords = lists["quick_return_keywords"] ?: emptyList(),
            completionKeywords = lists["completion_keywords"] ?: emptyList(),
            checkinKeywords = lists["checkin_keywords"] ?: emptyList(),
            entryKeywords = lists["coin_entry_keywords"] ?: listOf("赚更多金币"),
            popupCloseKeywords = (lists["popup_close_keywords"] ?: emptyList())
                .filter { it != "关闭" && it != "跳过" },
            maxWaitDuration = nums["max_wait_duration"]?.toFloatOrNull() ?: 35f,
            swipeInterval = nums["interval_seconds"]?.toFloatOrNull() ?: 0f,
            waitBetweenTasks = nums["wait_between_tasks"]?.toFloatOrNull() ?: 2f,
            maxNoTaskCount = nums["max_no_task_count"]?.toIntOrNull() ?: 2,
            maxBackTimes = nums["max_back_times"]?.toIntOrNull() ?: 10,
            minBackSearch = nums["min_back_times_search"]?.toIntOrNull() ?: 2,
            minBackNormal = nums["min_back_times_normal"]?.toIntOrNull() ?: 1,
            swipeDurMin = nums["duration_min"]?.toFloatOrNull() ?: 0.06f,
            swipeDurMax = nums["duration_max"]?.toFloatOrNull() ?: 0.14f,
            distMin = nums["distance_ratio_min"]?.toFloatOrNull() ?: 0.68f,
            distMax = nums["distance_ratio_max"]?.toFloatOrNull() ?: 0.88f,
            searchKeyword = nums["search_keyword"] ?: "笔记本电脑",
            quickReturnSettle = nums["quick_return_settle_seconds"]?.toFloatOrNull() ?: 3f,
            waitOnlyKeywords = lists["wait_only_keywords"] ?: defaultWaitOnlyKeywords(),
            clickProductKeywords = lists["click_product_keywords"] ?: defaultClickProductKeywords(),
            minProductCoinReward = nums["min_product_coin_reward"]?.toIntOrNull() ?: 10,
            maxStaleProgressAttempts = nums["max_stale_progress_attempts"]?.toIntOrNull() ?: 2,
        ).let { tuneSwipeForMobile(it) }
    }

    private fun defaultClickProductKeywords(): List<String> = listOf(
        "点击商品领优惠红包",
        "点击商品",
        "领优惠红包",
        "点3个商品",
        "点商品领",
    )

    /** 手机端滑动_defaults：比 PC 脚本更慢、幅度更小、间隔更长 */
    private fun tuneSwipeForMobile(cfg: AppConfig): AppConfig {
        var durMin = cfg.swipeDurMin
        var durMax = cfg.swipeDurMax
        var interval = cfg.swipeInterval
        var dMin = cfg.distMin
        var dMax = cfg.distMax
        if (durMin < 0.2f) durMin = 0.32f
        if (durMax < 0.25f) durMax = 0.52f
        if (durMax < durMin + 0.08f) durMax = durMin + 0.15f
        if (interval < 0.5f) interval = 1.4f
        if (dMin > 0.55f) dMin = 0.42f
        if (dMax > 0.65f) dMax = 0.58f
        return cfg.copy(
            swipeDurMin = durMin,
            swipeDurMax = durMax,
            swipeInterval = interval,
            distMin = dMin,
            distMax = dMax,
        )
    }

    private fun defaultWaitOnlyKeywords(): List<String> = listOf(
        "好物沉浸看",
        "沉浸看",
        "沉浸浏览",
        "精选好物沉浸",
    )

    private fun parseYamlLists(text: String): Map<String, List<String>> {
        val out = mutableMapOf<String, MutableList<String>>()
        var current: String? = null
        for (raw in text.lines()) {
            val line = raw.substringBefore("#").trimEnd()
            if (line.isBlank()) continue
            val mKey = Regex("^([A-Za-z0-9_]+):\\s*$").find(line.trim())
            if (mKey != null && !line.trim().startsWith("-")) {
                current = mKey.groupValues[1]
                out.getOrPut(current) { mutableListOf() }
                continue
            }
            val item = Regex("^-\\s*\"?(.*?)\"?\\s*$").find(line.trim()) ?: continue
            val key = current ?: continue
            var v = item.groupValues[1].trim()
            if (v.startsWith("\"") && v.endsWith("\"")) v = v.substring(1, v.length - 1)
            if (v.isNotEmpty()) out.getOrPut(key) { mutableListOf() }.add(v)
        }
        return out
    }

    private fun parseYamlScalars(text: String): Map<String, String> {
        val out = mutableMapOf<String, String>()
        for (raw in text.lines()) {
            val line = raw.substringBefore("#").trim()
            val m = Regex("^([A-Za-z0-9_.]+):\\s*(.+)$").find(line) ?: continue
            if (line.startsWith("-")) continue
            var v = m.groupValues[2].trim().trim('"')
            if (v.isEmpty()) continue
            out[m.groupValues[1]] = v
        }
        return out
    }
}

fun containsAny(text: String?, keys: List<String>): Boolean {
    if (text.isNullOrBlank()) return false
    return keys.any { it.isNotBlank() && text.contains(it) }
}

/** 白名单优先：命中放行词则不做黑名单跳过 */
fun shouldSkipTask(name: String, allowKeywords: List<String>, skipKeywords: List<String>): Boolean {
    if (containsAny(name, allowKeywords)) return false
    return containsAny(name, skipKeywords)
}
