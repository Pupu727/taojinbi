package com.pupu.taojinbi

import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.fragment.app.Fragment

/** 首页双开应用选择 UI 逻辑（坐标选手势页 + 模式保存）。 */
class DualAppSettingsController(
    private val host: Fragment,
    root: View,
    private val pickCoordLauncher: ActivityResultLauncher<Intent>,
) {
    private val ctx: Context get() = host.requireContext()
    private val spinner: Spinner = root.findViewById(R.id.spinnerDualApp)
    private val labelCoord1: TextView = root.findViewById(R.id.labelCoord1)
    private val labelCoord2: TextView = root.findViewById(R.id.labelCoord2)
    private val saveHint: TextView = root.findViewById(R.id.dualSaveHint)
    private var spinnerInitializing = false
    private var pendingDualMode = UserSettings.DUAL_OFF

    private val dualSpinnerLabels = listOf(
        "不开启该功能",
        "主应用",
        "双开应用",
        "手动选择坐标1",
        "手动选择坐标2",
    )

    init {
        setupSpinner()
    }

    fun load() {
        pendingDualMode = UserSettings.getDualAppMode(ctx)
        spinnerInitializing = true
        spinner.setSelection(pendingDualMode.coerceIn(UserSettings.DUAL_OFF, UserSettings.DUAL_CLONE))
        spinnerInitializing = false
        refreshCoordLabels()
        saveHint.visibility = View.GONE
    }

    fun onCoordPicked(slot: Int, x: Int, y: Int) {
        UserSettings.saveDualCoord(ctx, slot, x, y)
        refreshCoordLabels()
        showHint("坐标${slot}已保存 ($x, $y)")
        restoreSpinnerSelection()
    }

    fun onPickCancelled() {
        restoreSpinnerSelection()
    }

    private fun setupSpinner() {
        val adapter = object : ArrayAdapter<String>(
            ctx,
            android.R.layout.simple_spinner_item,
            dualSpinnerLabels,
        ) {
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val v = super.getView(position, convertView, parent)
                (v as? TextView)?.setTextColor(ctx.getColor(R.color.text_primary))
                return v
            }

            override fun getDropDownView(
                position: Int,
                convertView: View?,
                parent: android.view.ViewGroup,
            ): View {
                val v = super.getDropDownView(position, convertView, parent)
                (v as? TextView)?.apply {
                    setTextColor(ctx.getColor(R.color.text_primary))
                    setBackgroundColor(ctx.getColor(R.color.vault_surface_raised))
                }
                return v
            }
        }
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (spinnerInitializing) return
                when (position) {
                    UserSettings.SPINNER_PICK_COORD1 -> launchPick(CoordinatePickActivity.SLOT_1)
                    UserSettings.SPINNER_PICK_COORD2 -> launchPick(CoordinatePickActivity.SLOT_2)
                    else -> applyMode(position)
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun applyMode(mode: Int) {
        pendingDualMode = mode
        if (mode != UserSettings.DUAL_OFF) {
            val (c1x, c1y) = UserSettings.getDualCoord1(ctx)
            val (c2x, c2y) = UserSettings.getDualCoord2(ctx)
            if (mode == UserSettings.DUAL_MAIN && (c1x < 0 || c1y < 0)) {
                Toast.makeText(ctx, "请先手动选择坐标1（主应用）", Toast.LENGTH_LONG).show()
                restoreSpinnerSelection()
                return
            }
            if (mode == UserSettings.DUAL_CLONE && (c2x < 0 || c2y < 0)) {
                Toast.makeText(ctx, "请先手动选择坐标2（双开应用）", Toast.LENGTH_LONG).show()
                restoreSpinnerSelection()
                return
            }
        }
        UserSettings.saveDualAppMode(ctx, mode)
        showHint(
            if (mode == UserSettings.DUAL_OFF) {
                "已关闭双开自动选择"
            } else {
                "已保存：${UserSettings.dualAppModeLabel(ctx)}"
            },
        )
    }

    private fun launchPick(slot: Int) {
        pickCoordLauncher.launch(
            Intent(ctx, CoordinatePickActivity::class.java)
                .putExtra(CoordinatePickActivity.EXTRA_SLOT, slot),
        )
    }

    private fun restoreSpinnerSelection() {
        pendingDualMode = UserSettings.getDualAppMode(ctx)
        spinnerInitializing = true
        spinner.setSelection(pendingDualMode.coerceIn(UserSettings.DUAL_OFF, UserSettings.DUAL_CLONE))
        spinnerInitializing = false
    }

    private fun refreshCoordLabels() {
        labelCoord1.text = "坐标1（主应用）：${UserSettings.formatCoord(ctx, 1)}"
        labelCoord2.text = "坐标2（双开应用）：${UserSettings.formatCoord(ctx, 2)}"
    }

    private fun showHint(text: String) {
        saveHint.text = text
        saveHint.visibility = View.VISIBLE
    }
}
