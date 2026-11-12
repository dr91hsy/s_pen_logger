package com.rezonera.spenlogger

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import java.util.Date

/** Memo list: open / create / rename / delete memos and look at the daily distance records. */
class MainActivity : Activity() {

    private lateinit var summary: TextView
    private lateinit var list: ListView
    private var metas: List<MemoMeta> = emptyList()

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
        }

        summary = TextView(this).apply {
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.DKGRAY)
        }
        root.addView(summary)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val newBtn = Button(this).apply {
            text = "새 메모"
            setOnClickListener {
                val m = Store.newMemo()
                openMemo(m.id)
            }
        }
        val recBtn = Button(this).apply {
            text = "기록 보기"
            setOnClickListener { showRecords() }
        }
        row.addView(newBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(recBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(row)

        list = ListView(this)
        list.setOnItemClickListener { _, _, pos, _ -> openMemo(metas[pos].id) }
        list.setOnItemLongClickListener { _, _, pos, _ ->
            memoMenu(metas[pos])
            true
        }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun fmtM(mm: Double) = "%.3f m".format(mm / 1000.0)

    private fun refresh() {
        metas = Store.listMemos()
        val lines = metas.map { "${it.title}\n${Store.dateTimeFmt.format(Date(it.modified))} · ${it.pages}쪽" }
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, lines)
        val t = Store.todayTotals()
        val a = Store.allTotals()
        summary.text = "오늘  펜 ${fmtM(t[0])}  지우개 ${fmtM(t[1])}  합 ${fmtM(t[0] + t[1])}\n" +
            "누적  펜 ${fmtM(a[0])}  지우개 ${fmtM(a[1])}  합 ${fmtM(a[0] + a[1])}"
    }

    private fun openMemo(id: String) {
        val i = Intent(this, EditorActivity::class.java)
        i.putExtra("id", id)
        startActivity(i)
    }

    private fun memoMenu(m: MemoMeta) {
        AlertDialog.Builder(this)
            .setTitle(m.title)
            .setItems(arrayOf("이름 변경", "삭제")) { _, which ->
                if (which == 0) renameDialog(m) else deleteDialog(m)
            }
            .show()
    }

    private fun renameDialog(m: MemoMeta) {
        val et = EditText(this).apply {
            setText(m.title)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        AlertDialog.Builder(this)
            .setTitle("이름 변경")
            .setView(et)
            .setPositiveButton("저장") { _, _ ->
                val t = et.text.toString().trim()
                if (t.isNotEmpty()) {
                    Store.renameMemo(m.id, t)
                    refresh()
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun deleteDialog(m: MemoMeta) {
        AlertDialog.Builder(this)
            .setMessage("'${m.title}' 메모를 삭제할까? (되돌릴 수 없어요)\n누적 기록(m)은 그대로 남아요.")
            .setPositiveButton("삭제") { _, _ ->
                Store.deleteMemo(m.id)
                refresh()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun showRecords() {
        val sb = StringBuilder()
        val all = Store.allTotals()
        sb.append("누적  펜 ${fmtM(all[0])}  지우개 ${fmtM(all[1])}  합 ${fmtM(all[0] + all[1])}\n\n")
        for ((day, v) in Store.daily.descendingMap()) {
            sb.append("$day  펜 ${fmtM(v[0])}  지우개 ${fmtM(v[1])}  합 ${fmtM(v[0] + v[1])}\n")
        }
        if (Store.daily.isEmpty()) sb.append("아직 기록이 없어요")
        val tv = TextView(this).apply {
            text = sb.toString()
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setPadding(dp(16f), dp(8f), dp(16f), dp(8f))
        }
        val sv = ScrollView(this)
        sv.addView(tv)
        AlertDialog.Builder(this)
            .setTitle("필기량 기록 (KST)")
            .setView(sv)
            .setPositiveButton("닫기", null)
            .show()
    }
}
