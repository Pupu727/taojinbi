package com.pupu.taojinbi

import android.content.Context

/** App 内用户设置（SharedPreferences），优先于外置文件与 assets。 */
object UserSettings {
    private const val PREFS = "taojinbi_user"
    private const val KEY_SKIP = "skip_keywords"
    private const val KEY_ALLOW = "allow_keywords"
    private const val KEY_SKIP_POOL = "skip_keyword_pool"
    private const val KEY_ALLOW_POOL = "allow_keyword_pool"
    private const val KEY_TARGET = "target_count"
    private const val KEY_SAVED = "settings_saved"

    fun isSaved(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SAVED, false)

    fun getSkipKeywords(context: Context): List<String> {
        val raw = prefs(context).getString(KEY_SKIP, null)
        if (raw != null) return parseLines(raw)
        return ConfigLoader.defaultSkipKeywords(context)
    }

    fun getAllowKeywords(context: Context): List<String> {
        val raw = prefs(context).getString(KEY_ALLOW, null)
        if (raw != null) return parseLines(raw)
        return ConfigLoader.defaultAllowKeywords(context)
    }

    fun getTargetCount(context: Context): Int {
        if (!isSaved(context)) return ConfigLoader.defaultTargetCount(context)
        return prefs(context).getInt(KEY_TARGET, ConfigLoader.defaultTargetCount(context))
    }

    fun skipKeywordPool(context: Context): List<String> {
        val raw = prefs(context).getString(KEY_SKIP_POOL, null)
        if (raw != null) return parseLines(raw)
        return (ConfigLoader.defaultSkipKeywords(context) + getSkipKeywords(context))
            .distinct()
            .sorted()
    }

    fun allowKeywordPool(context: Context): List<String> {
        val raw = prefs(context).getString(KEY_ALLOW_POOL, null)
        if (raw != null) return parseLines(raw)
        return (ConfigLoader.defaultAllowKeywords(context) + getAllowKeywords(context))
            .distinct()
            .sorted()
    }

    fun save(
        context: Context,
        skipKeywords: List<String>,
        allowKeywords: List<String>,
        targetCount: Int,
        skipPool: List<String> = skipKeywords,
        allowPool: List<String> = allowKeywords,
    ) {
        val target = targetCount.coerceIn(1, 999)
        prefs(context).edit()
            .putString(KEY_SKIP, skipKeywords.joinToString("\n"))
            .putString(KEY_ALLOW, allowKeywords.joinToString("\n"))
            .putString(KEY_SKIP_POOL, skipPool.joinToString("\n"))
            .putString(KEY_ALLOW_POOL, allowPool.joinToString("\n"))
            .putInt(KEY_TARGET, target)
            .putBoolean(KEY_SAVED, true)
            .apply()
    }

    /** 恢复为 APK 包内 assets/config.yaml 的默认白/黑名单与目标数 */
    fun restoreDefaults(context: Context) {
        val skip = ConfigLoader.defaultSkipKeywords(context)
        val allow = ConfigLoader.defaultAllowKeywords(context)
        val target = ConfigLoader.defaultTargetCount(context)
        save(
            context,
            skipKeywords = skip,
            allowKeywords = allow,
            targetCount = target,
            skipPool = skip,
            allowPool = allow,
        )
    }

    fun parseSkipText(text: String): List<String> = parseLines(text)

    private fun parseLines(raw: String): List<String> =
        raw.lines()
            .map { it.substringBefore("#").trim() }
            .filter { it.isNotEmpty() }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
