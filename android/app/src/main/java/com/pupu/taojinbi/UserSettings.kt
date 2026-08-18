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
    private const val KEY_DUAL_MODE = "dual_app_mode"
    private const val KEY_COORD1_X = "dual_coord1_x"
    private const val KEY_COORD1_Y = "dual_coord1_y"
    private const val KEY_COORD2_X = "dual_coord2_x"
    private const val KEY_COORD2_Y = "dual_coord2_y"

    const val DUAL_OFF = 0
    const val DUAL_MAIN = 1
    const val DUAL_CLONE = 2

    /** Spinner 里「选手势坐标」的虚拟项，不会写入 mode */
    const val SPINNER_PICK_COORD1 = 3
    const val SPINNER_PICK_COORD2 = 4

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

    fun getDualAppMode(context: Context): Int =
        prefs(context).getInt(KEY_DUAL_MODE, DUAL_OFF)

    fun getDualCoord1(context: Context): Pair<Int, Int> {
        val p = prefs(context)
        return p.getInt(KEY_COORD1_X, -1) to p.getInt(KEY_COORD1_Y, -1)
    }

    fun getDualCoord2(context: Context): Pair<Int, Int> {
        val p = prefs(context)
        return p.getInt(KEY_COORD2_X, -1) to p.getInt(KEY_COORD2_Y, -1)
    }

    fun dualAppModeLabel(context: Context): String = when (getDualAppMode(context)) {
        DUAL_MAIN -> "主应用"
        DUAL_CLONE -> "双开应用"
        else -> "不开启该功能"
    }

    fun formatCoord(context: Context, slot: Int): String {
        val (x, y) = if (slot == 2) getDualCoord2(context) else getDualCoord1(context)
        return if (x >= 0 && y >= 0) "($x, $y)" else "未设置"
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
        dualAppMode: Int? = null,
        coord1: Pair<Int, Int>? = null,
        coord2: Pair<Int, Int>? = null,
    ) {
        val target = targetCount.coerceIn(1, 999)
        val editor = prefs(context).edit()
            .putString(KEY_SKIP, skipKeywords.joinToString("\n"))
            .putString(KEY_ALLOW, allowKeywords.joinToString("\n"))
            .putString(KEY_SKIP_POOL, skipPool.joinToString("\n"))
            .putString(KEY_ALLOW_POOL, allowPool.joinToString("\n"))
            .putInt(KEY_TARGET, target)
            .putBoolean(KEY_SAVED, true)
        if (dualAppMode != null) {
            editor.putInt(KEY_DUAL_MODE, dualAppMode.coerceIn(DUAL_OFF, DUAL_CLONE))
        }
        coord1?.let { (x, y) ->
            editor.putInt(KEY_COORD1_X, x).putInt(KEY_COORD1_Y, y)
        }
        coord2?.let { (x, y) ->
            editor.putInt(KEY_COORD2_X, x).putInt(KEY_COORD2_Y, y)
        }
        editor.apply()
    }

    fun saveDualCoord(context: Context, slot: Int, x: Int, y: Int) {
        val editor = prefs(context).edit()
        if (slot == 2) {
            editor.putInt(KEY_COORD2_X, x).putInt(KEY_COORD2_Y, y)
        } else {
            editor.putInt(KEY_COORD1_X, x).putInt(KEY_COORD1_Y, y)
        }
        editor.apply()
    }

    fun saveDualAppMode(context: Context, mode: Int) {
        prefs(context).edit()
            .putInt(KEY_DUAL_MODE, mode.coerceIn(DUAL_OFF, DUAL_CLONE))
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
