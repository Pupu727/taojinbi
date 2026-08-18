package com.pupu.taojinbi

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.chip.ChipGroup

class SettingsFragment : Fragment() {
    private lateinit var allowPanel: KeywordChipPanel
    private lateinit var skipPanel: KeywordChipPanel
    private lateinit var editTarget: EditText
    private lateinit var saveHint: TextView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? = inflater.inflate(R.layout.fragment_settings, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        editTarget = view.findViewById(R.id.editTarget)
        saveHint = view.findViewById(R.id.saveHint)
        allowPanel = KeywordChipPanel(
            requireContext(),
            view.findViewById<ChipGroup>(R.id.chipsAllow),
            view.findViewById(R.id.searchAllow),
            view.findViewById(R.id.addAllow),
            view.findViewById(R.id.btnAddAllow),
            view.findViewById(R.id.countAllow),
            view.findViewById(R.id.btnModeAllAllow),
            view.findViewById(R.id.btnModeSelectedAllow),
            view.findViewById(R.id.btnModeUnselectedAllow),
            view.findViewById(R.id.btnSelectAllAllow),
            view.findViewById(R.id.btnClearAllow),
            isAllowPanel = true,
        )
        skipPanel = KeywordChipPanel(
            requireContext(),
            view.findViewById<ChipGroup>(R.id.chipsSkip),
            view.findViewById(R.id.searchSkip),
            view.findViewById(R.id.addSkip),
            view.findViewById(R.id.btnAddSkip),
            view.findViewById(R.id.countSkip),
            view.findViewById(R.id.btnModeAllSkip),
            view.findViewById(R.id.btnModeSelectedSkip),
            view.findViewById(R.id.btnModeUnselectedSkip),
            view.findViewById(R.id.btnSelectAllSkip),
            view.findViewById(R.id.btnClearSkip),
            isAllowPanel = false,
        )
        view.findViewById<Button>(R.id.btnSave).setOnClickListener { save() }
        loadIntoForm()
    }

    override fun onResume() {
        super.onResume()
        loadIntoForm()
    }

    private fun loadIntoForm() {
        if (!isAdded) return
        val ctx = requireContext()
        allowPanel.load(
            UserSettings.allowKeywordPool(ctx),
            UserSettings.getAllowKeywords(ctx).toSet(),
        )
        skipPanel.load(
            UserSettings.skipKeywordPool(ctx),
            UserSettings.getSkipKeywords(ctx).toSet(),
        )
        editTarget.setText(UserSettings.getTargetCount(ctx).toString())
    }

    private fun save() {
        val ctx = requireContext()
        val allows = allowPanel.getSelected().sorted()
        val skips = skipPanel.getSelected().sorted()
        val target = editTarget.text?.toString()?.trim()?.toIntOrNull()
        if (target == null || target < 1) {
            Toast.makeText(ctx, "目标数量请输入 1–999 的整数", Toast.LENGTH_SHORT).show()
            return
        }
        UserSettings.save(
            ctx,
            skipKeywords = skips,
            allowKeywords = allows,
            targetCount = target,
            skipPool = skipPanel.getPool().sorted(),
            allowPool = allowPanel.getPool().sorted(),
        )
        val exported = ConfigExporter.syncExternalConfig(
            ctx,
            allowKeywords = allows,
            skipKeywords = skips,
            allowPool = allowPanel.getPool().sorted(),
            skipPool = skipPanel.getPool().sorted(),
            targetCount = target,
        )
        val exportHint = exported?.let { "\n已同步到 ${it.absolutePath}" }.orEmpty()
        saveHint.text = "已保存：放行 ${allows.size} 条，跳过 ${skips.size} 条，目标 $target$exportHint"
        saveHint.visibility = View.VISIBLE
        Toast.makeText(ctx, "保存成功", Toast.LENGTH_SHORT).show()
        (activity as? MainActivity)?.refreshHomeStatus()
    }
}
