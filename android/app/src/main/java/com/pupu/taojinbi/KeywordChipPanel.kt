package com.pupu.taojinbi

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup

/**
 * 关键词 Chip 面板：勾选 = 启用；支持搜索、筛选、全选/清空、长按删除。
 */
class KeywordChipPanel(
    private val context: Context,
    private val chipGroup: ChipGroup,
    private val searchEdit: EditText,
    private val addEdit: EditText,
    private val btnAdd: Button,
    private val countView: TextView,
    private val btnModeAll: Button,
    private val btnModeSelected: Button,
    private val btnModeUnselected: Button,
    private val btnSelectAll: Button,
    private val btnClear: Button,
    private val isAllowPanel: Boolean,
) {
    private val pool = linkedSetOf<String>()
    private var filter = ""
    private var showMode = ShowMode.ALL

    private enum class ShowMode { ALL, SELECTED, UNSELECTED }

    private val checkedBg = if (isAllowPanel) {
        ContextCompat.getColor(context, R.color.allow_primary)
    } else {
        ContextCompat.getColor(context, R.color.skip_primary)
    }
    private val uncheckedBg = ContextCompat.getColor(context, R.color.vault_surface_raised)
    private val chipBg = ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
        intArrayOf(checkedBg, uncheckedBg),
    )
    private val chipTextColors = ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
        intArrayOf(Color.WHITE, ContextCompat.getColor(context, R.color.text_secondary)),
    )

    init {
        chipGroup.isSingleSelection = false
        chipGroup.isSelectionRequired = false
        searchEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                filter = s?.toString()?.trim().orEmpty()
                rebuild(getSelected())
            }
        })
        btnAdd.setOnClickListener { addCustom() }
        btnModeAll.setOnClickListener { setShowMode(ShowMode.ALL) }
        btnModeSelected.setOnClickListener { setShowMode(ShowMode.SELECTED) }
        btnModeUnselected.setOnClickListener { setShowMode(ShowMode.UNSELECTED) }
        btnSelectAll.setOnClickListener { selectAllVisible() }
        btnClear.setOnClickListener { clearVisible() }
    }

    fun load(poolKeywords: List<String>, selected: Set<String>) {
        pool.clear()
        pool.addAll(poolKeywords.map { it.trim() }.filter { it.isNotEmpty() })
        pool.addAll(selected)
        rebuild(selected)
    }

    fun getSelected(): Set<String> {
        val out = linkedSetOf<String>()
        for (i in 0 until chipGroup.childCount) {
            val chip = chipGroup.getChildAt(i) as? Chip ?: continue
            if (chip.isChecked) out.add(chip.text.toString())
        }
        return out
    }

    fun getPool(): List<String> = pool.toList()

    private fun setShowMode(mode: ShowMode) {
        showMode = mode
        highlightModeButtons()
        rebuild(getSelected())
    }

    private fun highlightModeButtons() {
        fun Button.paint(activeMode: ShowMode) {
            val active = showMode == activeMode
            setBackgroundResource(
                if (active) R.drawable.bg_filter_chip_selected else R.drawable.bg_filter_chip,
            )
            setTextColor(
                ContextCompat.getColor(
                    context,
                    if (active) R.color.gold_primary else R.color.text_muted,
                ),
            )
        }
        btnModeAll.paint(ShowMode.ALL)
        btnModeSelected.paint(ShowMode.SELECTED)
        btnModeUnselected.paint(ShowMode.UNSELECTED)
    }

    private fun addCustom() {
        val word = addEdit.text?.toString()?.trim().orEmpty()
        if (word.length < 2) {
            Toast.makeText(context, "关键词至少 2 个字", Toast.LENGTH_SHORT).show()
            return
        }
        pool.add(word)
        addEdit.text?.clear()
        val selected = getSelected().toMutableSet()
        selected.add(word)
        rebuild(selected)
    }

    private fun selectAllVisible() {
        val selected = getSelected().toMutableSet()
        visibleKeywords(selected).forEach { selected.add(it) }
        rebuild(selected)
    }

    private fun clearVisible() {
        val selected = getSelected().toMutableSet()
        visibleKeywords(selected).forEach { selected.remove(it) }
        rebuild(selected)
    }

    private fun confirmDelete(keyword: String) {
        val activity = context as? Activity
        if (activity == null || activity.isFinishing) {
            Toast.makeText(context, "页面已关闭，无法删除", Toast.LENGTH_SHORT).show()
            return
        }
        val label = if (isAllowPanel) "放行词" else "跳过词"
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_confirm_delete, null)
        val dialog = Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar)
        dialog.setContentView(view)
        dialog.setCancelable(true)
        dialog.setCanceledOnTouchOutside(true)
        view.findViewById<TextView>(R.id.dialogTitle).text = "删除$label"
        view.findViewById<TextView>(R.id.dialogMessage).text =
            "确定删除「$keyword」？\n删除后需点「保存设置」才会永久生效。"
        view.findViewById<Button>(R.id.dialogCancel).setOnClickListener { dialog.dismiss() }
        view.findViewById<Button>(R.id.dialogConfirm).setOnClickListener {
            dialog.dismiss()
            deleteKeyword(keyword)
        }
        // 居中卡片
        dialog.window?.setLayout(
            (activity.resources.displayMetrics.widthPixels * 0.86f).toInt(),
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        dialog.show()
    }

    private fun deleteKeyword(keyword: String) {
        pool.remove(keyword)
        val selected = getSelected().toMutableSet()
        selected.remove(keyword)
        rebuild(selected)
        Toast.makeText(context, "已删除，记得保存设置", Toast.LENGTH_SHORT).show()
    }

    private fun visibleKeywords(selected: Set<String>): List<String> =
        pool
            .filter { matchesFilter(it) }
            .filter { matchesMode(it, selected) }
            .sorted()

    private fun matchesFilter(kw: String): Boolean =
        filter.isEmpty() || kw.contains(filter, ignoreCase = true)

    private fun matchesMode(kw: String, selected: Set<String>): Boolean = when (showMode) {
        ShowMode.ALL -> true
        ShowMode.SELECTED -> kw in selected
        ShowMode.UNSELECTED -> kw !in selected
    }

    private fun rebuild(selected: Set<String>) {
        val visible = visibleKeywords(selected)
        chipGroup.removeAllViews()
        visible.forEach { kw ->
            chipGroup.addView(
                Chip(context).apply {
                    text = kw
                    isCheckable = true
                    isChecked = kw in selected
                    isCheckedIconVisible = true
                    chipBackgroundColor = chipBg
                    setTextColor(chipTextColors)
                    chipStrokeWidth = 0f
                    textSize = 13f
                    chipMinHeight = context.resources.displayMetrics.density * 34
                    chipCornerRadius = context.resources.displayMetrics.density * 17
                    setOnLongClickListener {
                        confirmDelete(kw)
                        true
                    }
                },
            )
        }
        val totalSelected = selected.size
        val label = if (isAllowPanel) "已放行" else "已跳过"
        countView.text = if (visible.size < pool.size) {
            "$label $totalSelected 条 · 显示 ${visible.size}/${pool.size}"
        } else {
            "$label $totalSelected / ${pool.size} 条"
        }
        if (visible.isEmpty()) {
            chipGroup.addView(
                TextView(context).apply {
                    text = if (showMode == ShowMode.SELECTED) {
                        "暂无已选词，点「全部」或搜索后勾选"
                    } else {
                        "无匹配词，换个搜索或添加自定义"
                    }
                    setTextColor(ContextCompat.getColor(context, R.color.text_muted))
                    textSize = 13f
                    setPadding(0, 8, 0, 8)
                },
            )
        }
        highlightModeButtons()
    }
}
