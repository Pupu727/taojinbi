package com.pupu.taojinbi

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.chip.ChipGroup

class SettingsActivity : AppCompatActivity() {
    private lateinit var allowPanel: KeywordChipPanel
    private lateinit var skipPanel: KeywordChipPanel
    private lateinit var editTarget: EditText
    private lateinit var saveHint: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        editTarget = findViewById(R.id.editTarget)
        saveHint = findViewById(R.id.saveHint)
        allowPanel = KeywordChipPanel(
            this,
            findViewById<ChipGroup>(R.id.chipsAllow),
            findViewById(R.id.searchAllow),
            findViewById(R.id.addAllow),
            findViewById(R.id.btnAddAllow),
            findViewById(R.id.countAllow),
            findViewById(R.id.btnModeAllAllow),
            findViewById(R.id.btnModeSelectedAllow),
            findViewById(R.id.btnModeUnselectedAllow),
            findViewById(R.id.btnSelectAllAllow),
            findViewById(R.id.btnClearAllow),
            isAllowPanel = true,
        )
        skipPanel = KeywordChipPanel(
            this,
            findViewById<ChipGroup>(R.id.chipsSkip),
            findViewById(R.id.searchSkip),
            findViewById(R.id.addSkip),
            findViewById(R.id.btnAddSkip),
            findViewById(R.id.countSkip),
            findViewById(R.id.btnModeAllSkip),
            findViewById(R.id.btnModeSelectedSkip),
            findViewById(R.id.btnModeUnselectedSkip),
            findViewById(R.id.btnSelectAllSkip),
            findViewById(R.id.btnClearSkip),
            isAllowPanel = false,
        )
        findViewById<Button>(R.id.btnSave).setOnClickListener { save() }
        findViewById<Button>(R.id.btnRestore).setOnClickListener { restore() }
        loadIntoForm()
    }

    private fun loadIntoForm() {
        allowPanel.load(
            UserSettings.allowKeywordPool(this),
            UserSettings.getAllowKeywords(this).toSet(),
        )
        skipPanel.load(
            UserSettings.skipKeywordPool(this),
            UserSettings.getSkipKeywords(this).toSet(),
        )
        editTarget.setText(UserSettings.getTargetCount(this).toString())
    }

    private fun save() {
        val allows = allowPanel.getSelected().sorted()
        val skips = skipPanel.getSelected().sorted()
        val target = editTarget.text?.toString()?.trim()?.toIntOrNull()
        if (target == null || target < 1) {
            Toast.makeText(this, "目标数量请输入 1–999 的整数", Toast.LENGTH_SHORT).show()
            return
        }
        UserSettings.save(
            this,
            skipKeywords = skips,
            allowKeywords = allows,
            targetCount = target,
            skipPool = skipPanel.getPool().sorted(),
            allowPool = allowPanel.getPool().sorted(),
        )
        val exported = ConfigExporter.syncExternalConfig(
            this,
            allowKeywords = allows,
            skipKeywords = skips,
            allowPool = allowPanel.getPool().sorted(),
            skipPool = skipPanel.getPool().sorted(),
            targetCount = target,
        )
        val exportHint = exported?.let { "\n已同步到 ${it.absolutePath}" }.orEmpty()
        saveHint.text = "已保存：放行 ${allows.size} 条，跳过 ${skips.size} 条，目标 $target$exportHint"
        saveHint.visibility = View.VISIBLE
        Toast.makeText(this, "保存成功", Toast.LENGTH_SHORT).show()
    }

    private fun restore() {
        UserSettings.restoreDefaults(this)
        loadIntoForm()
        saveHint.text = "已恢复为 config.yaml 默认白名单/黑名单"
        saveHint.visibility = View.VISIBLE
        Toast.makeText(this, "已恢复为 config 默认", Toast.LENGTH_SHORT).show()
    }
}
