package com.pupu.taojinbi

import android.content.Intent
import android.os.Bundle
import android.view.MotionEvent
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** 全屏点击取坐标，用于双开选择器图标位置。 */
class CoordinatePickActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_coordinate_pick)
        val slot = intent.getIntExtra(EXTRA_SLOT, SLOT_1)
        val title = findViewById<TextView>(R.id.pickTitle)
        val hint = findViewById<TextView>(R.id.pickHint)
        if (slot == SLOT_2) {
            title.text = "手动选择坐标 2"
            hint.text =
                "请先打开淘宝，等系统弹出双开选择器后，点击「双开应用 / 分身」图标所在的位置。"
        } else {
            title.text = "手动选择坐标 1"
            hint.text =
                "请先打开淘宝，等系统弹出双开选择器后，点击「主应用 / 原版」图标所在的位置。"
        }
        findViewById<Button>(R.id.pickCancel).setOnClickListener { finish() }
        findViewById<FrameLayout>(R.id.pickRoot).setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                val x = event.rawX.toInt()
                val y = event.rawY.toInt()
                setResult(
                    RESULT_OK,
                    Intent().apply {
                        putExtra(EXTRA_X, x)
                        putExtra(EXTRA_Y, y)
                        putExtra(EXTRA_SLOT, slot)
                    },
                )
                finish()
                true
            } else {
                false
            }
        }
    }

    companion object {
        const val EXTRA_SLOT = "slot"
        const val EXTRA_X = "x"
        const val EXTRA_Y = "y"
        const val SLOT_1 = 1
        const val SLOT_2 = 2
    }
}
