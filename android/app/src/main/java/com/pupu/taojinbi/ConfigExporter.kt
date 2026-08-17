package com.pupu.taojinbi

import android.content.Context
import java.io.File

/** 把 App 内当前白/黑名单同步写入外置 config.yaml（与 PC 脚本共用目录）。 */
object ConfigExporter {
    /**
     * 将当前手机端配置写入 app 外置目录，并尽量同步到共享目录。
     * 保存设置后调用；可将该文件拷回项目 `assets/config.yaml` 作为新默认。
     */
    fun syncExternalConfig(
        context: Context,
        allowKeywords: List<String>,
        skipKeywords: List<String>,
        allowPool: List<String>,
        skipPool: List<String>,
        targetCount: Int,
    ): File? {
        val base = readBaseYaml(context) ?: return null
        var out = base
        out = replaceYamlList(out, "allow_keywords", allowPool.ifEmpty { allowKeywords })
        out = replaceYamlList(out, "skip_keywords", skipPool.ifEmpty { skipKeywords })
        out = replaceScalar(out, "target_count", targetCount.coerceIn(1, 999).toString())
        val app = ConfigLoader.appConfigFile(context)
        app.parentFile?.mkdirs()
        app.writeText(out, Charsets.UTF_8)
        val pub = ConfigLoader.publicConfigFile()
        runCatching {
            pub.parentFile?.mkdirs()
            pub.writeText(out, Charsets.UTF_8)
        }
        return app
    }

    private fun readBaseYaml(context: Context): String? = runCatching {
        val external = listOf(ConfigLoader.appConfigFile(context), ConfigLoader.publicConfigFile())
            .firstOrNull { it.isFile && it.canRead() && it.length() > 0L }
        if (external != null) return external.readText(Charsets.UTF_8)
        context.assets.open("config.yaml").bufferedReader(Charsets.UTF_8).use { it.readText() }
    }.getOrNull()

    /** 替换 `key:` 列表块（如 allow_keywords / skip_keywords） */
    private fun replaceYamlList(yaml: String, key: String, items: List<String>): String {
        val lines = yaml.lines().toMutableList()
        val keyRe = Regex("^\\s*$key:\\s*$")
        val start = lines.indexOfFirst { keyRe.matches(it.trimEnd()) }
        if (start < 0) return yaml
        var end = start + 1
        while (end < lines.size) {
            val t = lines[end].trim()
            if (t.isEmpty()) {
                end++
                continue
            }
            if (t.startsWith("-")) {
                end++
                continue
            }
            if (!t.startsWith("#") && t.contains(":") && !t.startsWith("-")) break
            if (t.startsWith("#")) {
                end++
                continue
            }
            break
        }
        val indent = lines[start].substringBefore(key).let { if (it.isEmpty()) "    " else it }
        val itemIndent = "$indent  "
        val block = buildList {
            add(lines[start])
            items.distinct().sorted().forEach { add("$itemIndent- \"$it\"") }
        }
        lines.subList(start, end).clear()
        lines.addAll(start, block)
        return lines.joinToString("\n")
    }

    private fun replaceScalar(yaml: String, key: String, value: String): String {
        val re = Regex("^(\\s*$key:\\s*).+$")
        return yaml.lines().joinToString("\n") { line ->
            re.find(line)?.let { "${it.groupValues[1]}$value  # 本轮目标（App 同步）" } ?: line
        }
    }
}
