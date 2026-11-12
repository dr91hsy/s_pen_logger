package com.rezonera.spenlogger

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import kotlin.math.PI
import kotlin.math.sin

/** Memo editor: toolbar + PageView. Autosaves shortly after every change and on pause. */
class EditorActivity : Activity() {

    private lateinit var pv: PageView
    private lateinit var counters: TextView
    private lateinit var debugView: TextView
    private lateinit var toolbar: LinearLayout
    private lateinit var toolBtn: Button
    private lateinit var modeBtn: Button
    private lateinit var lockBtn: Button
    private lateinit var pageLabel: TextView
    private var memo: Memo? = null
    private var showDebug = false
    private var fittedOnce = false
    private val handler = Handler(Looper.getMainLooper())
    private val saveRunnable = Runnable { saveNow() }

    private val tick = object : Runnable {
        override fun run() {
            refreshHud()
            handler.postDelayed(this, 150)
        }
    }

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        val id = intent.getStringExtra("id")
        val m = if (id != null) Store.loadMemo(id) else null
        if (m == null) {
            Toast.makeText(this, "메모를 열 수 없어요", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        memo = m

        pv = PageView(this).apply {
            penType = Store.penType
            penColor = Store.penColor
            penWidth = Store.penWidth
            eraserDp = Store.eraserDp
        }

        val root = FrameLayout(this)
        root.fitsSystemWindows = true
        root.addView(pv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // --- toolbar ---
        toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xE614141E.toInt())
            setPadding(dp(8f), dp(4f), dp(8f), dp(4f))
        }
        counters = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            typeface = Typeface.MONOSPACE
        }
        toolbar.addView(counters)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun addBtn(label: String, onClick: (Button) -> Unit): Button {
            val b = Button(this).apply {
                text = label
                textSize = 12f
                isAllCaps = false
                minWidth = 0
                minimumWidth = 0
                setOnClickListener { onClick(this) }
            }
            row.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            return b
        }

        addBtn("←") { finish() }
        toolBtn = addBtn("도구: 펜") {
            pv.toolEraser = !pv.toolEraser
            updateToolbarState()
        }
        addBtn("되돌리기") { pv.undo() }
        addBtn("다시") { pv.redo() }
        addBtn("도구 설정") { showToolDialog() }
        modeBtn = addBtn("손가락: 이동") {
            pv.mode = when (pv.mode) {
                PageView.Mode.PAN -> PageView.Mode.TEXT
                PageView.Mode.TEXT -> PageView.Mode.OBJECT
                PageView.Mode.OBJECT -> PageView.Mode.PAN
            }
            updateToolbarState()
        }
        lockBtn = addBtn("잠금") { pv.toggleLockSelected() }
        addBtn("삭제") { pv.deleteSelected() }
        addBtn("PDF") { pickFile("application/pdf", REQ_PDF) }
        addBtn("이미지") { pickFile("image/*", REQ_IMAGE) }
        addBtn("◀") { pv.setPageIndex(pv.pageIndex - 1) }
        pageLabel = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(dp(6f), 0, dp(6f), 0)
        }
        row.addView(pageLabel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addBtn("▶") { pv.setPageIndex(pv.pageIndex + 1) }
        addBtn("+쪽") { pv.addBlankPage() }
        addBtn("-쪽") { confirmDeletePage() }
        addBtn("보정") {
            pv.calibrating = true
            Toast.makeText(this, "자를 대고 직선을 한 줄 긋고, 실제 길이를 입력해줘 (15cm 이상 추천)", Toast.LENGTH_LONG).show()
        }
        addBtn("디버그") { showDebug = !showDebug }

        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
        toolbar.addView(scroll)
        root.addView(toolbar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        debugView = TextView(this).apply {
            setTextColor(0xFF9FE6A0.toInt())
            setBackgroundColor(0xAA000000.toInt())
            textSize = 10f
            typeface = Typeface.MONOSPACE
            visibility = View.GONE
        }
        root.addView(debugView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START))

        val toggle = Button(this).apply {
            text = "☰"
            textSize = 14f
            minWidth = 0
            minimumWidth = 0
            alpha = 0.75f
            setOnClickListener {
                if (toolbar.visibility == View.VISIBLE) {
                    toolbar.visibility = View.GONE
                    pv.topInset = 0f
                } else {
                    toolbar.visibility = View.VISIBLE
                }
            }
        }
        root.addView(toggle, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END))
        setContentView(root)

        // Keep the page below the toolbar; fit once the toolbar height is known.
        toolbar.addOnLayoutChangeListener { _, _, _, _, bottom, _, _, _, _ ->
            if (toolbar.visibility == View.VISIBLE) {
                pv.topInset = bottom.toFloat()
                if (!fittedOnce) {
                    fittedOnce = true
                    pv.fitToPage()
                }
            }
        }

        pv.onContentChanged = { scheduleSave() }
        pv.onCalibStroke = { raw -> askCalibration(raw) }
        pv.onEditText = { tb, x, y -> showTextDialog(tb, x, y) }
        pv.onSelectionChanged = { updateToolbarState() }
        pv.onPageChanged = { updatePageLabel() }
        pv.loadMemo(m)
        updateToolbarState()
    }

    override fun onResume() {
        super.onResume()
        handler.post(tick)
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        saveNow()
        super.onPause()
    }

    // ---- saving ------------------------------------------------------------------------------------

    private fun scheduleSave() {
        handler.removeCallbacks(saveRunnable)
        handler.postDelayed(saveRunnable, 2500)
    }

    private fun saveNow() {
        handler.removeCallbacks(saveRunnable)
        val m = memo ?: return
        m.modified = System.currentTimeMillis()
        Store.saveMemo(m)
        Store.saveDaily()
    }

    // ---- HUD -----------------------------------------------------------------------------------------

    private fun fmtM(mm: Double) = "%.3f m".format(mm / 1000.0)

    private fun line(label: String, a: DoubleArray) =
        "$label  펜 ${fmtM(a[0])}  지우개 ${fmtM(a[1])}  합 ${fmtM(a[0] + a[1])}"

    private fun refreshHud() {
        counters.text = line("오늘", Store.todayTotals()) + "\n" + line("누적", Store.allTotals())
        if (!showDebug) {
            debugView.visibility = View.GONE
            return
        }
        debugView.visibility = View.VISIBLE
        val sb = StringBuilder()
        sb.append("LIVE ").append(pv.lastInfo).append('\n')
        sb.append("calib=").append("%.4f".format(Store.calib)).append("  btnState: 0x20=S Pen button\n")
        for (l in pv.logLines.asReversed()) sb.append(l).append('\n')
        debugView.text = sb.toString()
    }

    private fun updatePageLabel() {
        pageLabel.text = "${pv.pageIndex + 1}/${pv.pageCount}"
    }

    private fun updateToolbarState() {
        toolBtn.text = if (pv.toolEraser) "도구: 지우개" else "도구: 펜"
        modeBtn.text = when (pv.mode) {
            PageView.Mode.PAN -> "손가락: 이동"
            PageView.Mode.TEXT -> "손가락: 글자"
            PageView.Mode.OBJECT -> "손가락: 객체"
        }
        val im = pv.selectedImage()
        lockBtn.text = when {
            im == null -> "잠금"
            im.locked -> "잠금 해제"
            else -> "잠그기"
        }
    }

    // ---- page ops --------------------------------------------------------------------------------------

    private fun confirmDeletePage() {
        if (pv.pageCount <= 1) {
            Toast.makeText(this, "마지막 쪽은 지울 수 없어요", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setMessage("이 쪽을 삭제할까?")
            .setPositiveButton("삭제") { _, _ -> pv.deleteCurrentPage() }
            .setNegativeButton("취소", null)
            .show()
    }

    // ---- calibration -------------------------------------------------------------------------------------

    private fun askCalibration(rawMm: Double) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = "실제 길이 (mm)"
        }
        AlertDialog.Builder(this)
            .setTitle("보정")
            .setMessage("측정값 %.1f mm (보정 전). 실제 길이를 mm로 입력해줘.".format(rawMm))
            .setView(input)
            .setPositiveButton("적용") { _, _ ->
                val real = input.text.toString().toDoubleOrNull()
                if (real != null && real > 0.0 && rawMm > 0.0) Store.calib = real / rawMm
            }
            .setNegativeButton("취소", null)
            .show()
    }

    // ---- text dialog ----------------------------------------------------------------------------------------

    private fun showTextDialog(existing: TextBox?, x: Float, y: Float) {
        var size = existing?.size ?: 36f
        val et = EditText(this).apply {
            setText(existing?.text ?: "")
            minLines = 3
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        val sizeLabel = TextView(this)
        fun upd() {
            sizeLabel.text = "글자 크기 ${size.toInt()}"
        }
        upd()
        val minus = Button(this).apply {
            text = "A−"
            setOnClickListener {
                size = (size - 4f).coerceAtLeast(16f)
                upd()
            }
        }
        val plus = Button(this).apply {
            text = "A+"
            setOnClickListener {
                size = (size + 4f).coerceAtMost(120f)
                upd()
            }
        }
        val sizeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(minus)
            addView(plus)
            addView(sizeLabel)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(8f), dp(16f), 0)
            addView(et)
            addView(sizeRow)
        }
        val b = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "글자 입력" else "글자 수정")
            .setView(box)
            .setPositiveButton("저장") { _, _ ->
                val t = et.text.toString()
                if (t.isBlank()) {
                    if (existing != null) pv.deleteText(existing.id)
                } else {
                    pv.upsertText(
                        TextBox(
                            id = existing?.id ?: newId(),
                            x = existing?.x ?: x,
                            y = existing?.y ?: y,
                            width = existing?.width ?: 520f,
                            text = t,
                            size = size,
                            color = existing?.color ?: pv.penColor,
                        )
                    )
                }
            }
            .setNegativeButton("취소", null)
        if (existing != null) b.setNeutralButton("삭제") { _, _ -> pv.deleteText(existing.id) }
        val d = b.create()
        d.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        d.show()
        et.requestFocus()
    }

    // ---- tool settings dialog -----------------------------------------------------------------------------------

    private val palette = intArrayOf(
        0xFF111111.toInt(), 0xFF555555.toInt(), 0xFF1E5BFF.toInt(), 0xFF0B8A8A.toInt(), 0xFF2E9E44.toInt(),
        0xFFE68A00.toInt(), 0xFFD62828.toInt(), 0xFFD6468F.toInt(), 0xFF7B3FBF.toInt(), 0xFF8B5A2B.toInt(),
    )

    private fun showToolDialog() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(8f), dp(16f), dp(8f))
        }

        val preview = StrokePreview(this)
        preview.penType = pv.penType
        preview.penColor = pv.penColor
        preview.penW = pv.penWidth
        box.addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(70f)))

        // pen type
        val basic = Button(this).apply { text = "기본 펜" }
        val fountain = Button(this).apply { text = "만년필" }
        fun refreshType() {
            basic.alpha = if (pv.penType == PEN_BASIC) 1f else 0.4f
            fountain.alpha = if (pv.penType == PEN_FOUNTAIN) 1f else 0.4f
            preview.penType = pv.penType
            preview.invalidate()
        }
        basic.setOnClickListener {
            pv.penType = PEN_BASIC
            Store.penType = PEN_BASIC
            refreshType()
        }
        fountain.setOnClickListener {
            pv.penType = PEN_FOUNTAIN
            Store.penType = PEN_FOUNTAIN
            refreshType()
        }
        refreshType()
        val typeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(basic, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(fountain, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        box.addView(typeRow)

        // colors
        val swatches = ArrayList<View>()
        fun refreshColors() {
            for ((i, v) in swatches.withIndex()) {
                val g = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(palette[i])
                    if (palette[i] == pv.penColor) setStroke(dp(3f), 0xFF1E88E5.toInt()) else setStroke(dp(1f), 0xFFBBBBBB.toInt())
                }
                v.background = g
            }
            preview.penColor = pv.penColor
            preview.invalidate()
        }
        val colorRow1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val colorRow2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((i, col) in palette.withIndex()) {
            val v = View(this)
            v.setOnClickListener {
                pv.penColor = col
                Store.penColor = col
                refreshColors()
            }
            swatches.add(v)
            val lp = LinearLayout.LayoutParams(0, dp(40f), 1f).apply { setMargins(dp(4f), dp(6f), dp(4f), dp(6f)) }
            if (i < 5) colorRow1.addView(v, lp) else colorRow2.addView(v, lp)
        }
        refreshColors()
        box.addView(colorRow1)
        box.addView(colorRow2)

        // pen width: progress 0..110 -> 1.0..12.0 page units
        val widthLabel = TextView(this)
        val widthBar = SeekBar(this).apply {
            max = 110
            progress = ((pv.penWidth - 1f) * 10f).toInt().coerceIn(0, 110)
        }
        fun updWidth() {
            widthLabel.text = "펜 굵기 %.1f".format(pv.penWidth)
            preview.penW = pv.penWidth
            preview.invalidate()
        }
        widthBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                pv.penWidth = 1f + p / 10f
                Store.penWidth = pv.penWidth
                updWidth()
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        updWidth()
        box.addView(widthLabel)
        box.addView(widthBar)

        // eraser size: progress 0..34 -> 6..40 dp (radius)
        val eraserLabel = TextView(this)
        val eraserBar = SeekBar(this).apply {
            max = 34
            progress = (pv.eraserDp - 6f).toInt().coerceIn(0, 34)
        }
        fun updEraser() {
            eraserLabel.text = "지우개 크기 %.0f".format(pv.eraserDp)
        }
        eraserBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                pv.eraserDp = 6f + p
                Store.eraserDp = pv.eraserDp
                updEraser()
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        updEraser()
        box.addView(eraserLabel)
        box.addView(eraserBar)

        AlertDialog.Builder(this)
            .setTitle("도구 설정")
            .setView(box)
            .setPositiveButton("닫기", null)
            .show()
    }

    // ---- import (PDF / image) ------------------------------------------------------------------------------------

    private fun pickFile(mime: String, req: Int) {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = mime
        startActivityForResult(i, req)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        when (requestCode) {
            REQ_PDF -> importPdf(uri)
            REQ_IMAGE -> importImage(uri)
        }
    }

    private fun importPdf(uri: Uri) {
        val m = memo ?: return
        Toast.makeText(this, "PDF 불러오는 중…", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val name = "pdf_${newId()}.pdf"
                val f = Store.assetFile(m.id, name)
                f.parentFile?.mkdirs()
                contentResolver.openInputStream(uri)?.use { ins -> f.outputStream().use { out -> ins.copyTo(out) } }
                val sizes = ArrayList<Pair<Float, Float>>()
                val pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
                val r = PdfRenderer(pfd)
                try {
                    for (i in 0 until r.pageCount) {
                        val pg = r.openPage(i)
                        sizes.add(Pair(pg.width.toFloat(), pg.height.toFloat()))
                        pg.close()
                    }
                } finally {
                    r.close()
                    pfd.close()
                }
                runOnUiThread {
                    pv.addPdfPages(name, sizes)
                    saveNow()
                }
            } catch (t: Throwable) {
                runOnUiThread { Toast.makeText(this, "PDF 열기 실패: ${t.message}", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun importImage(uri: Uri) {
        val m = memo ?: return
        Thread {
            try {
                val name = "img_${newId()}.bin"
                val f = Store.assetFile(m.id, name)
                f.parentFile?.mkdirs()
                contentResolver.openInputStream(uri)?.use { ins -> f.outputStream().use { out -> ins.copyTo(out) } }
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(f.path, o)
                if (o.outWidth <= 0 || o.outHeight <= 0) throw IllegalStateException("이미지를 읽을 수 없어요")
                runOnUiThread {
                    pv.addImage(name, o.outWidth, o.outHeight)
                    saveNow()
                }
            } catch (t: Throwable) {
                runOnUiThread { Toast.makeText(this, "이미지 열기 실패: ${t.message}", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    companion object {
        private const val REQ_PDF = 101
        private const val REQ_IMAGE = 102
    }
}

/** Small preview of the current pen type / color / width, drawn at roughly real on-page size. */
class StrokePreview(ctx: Context) : View(ctx) {
    var penType = PEN_BASIC
    var penColor = Color.BLACK
    var penW = 2.5f

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    override fun onDraw(c: Canvas) {
        c.drawColor(Color.WHITE)
        if (width <= 0 || height <= 0) return
        val unit = resources.displayMetrics.widthPixels / PAGE_W // screen px per page unit at fit zoom
        val wu = width / unit
        val hu = height / unit
        val n = 60
        val pts = FloatArray(n * 2)
        val pr = FloatArray(n)
        for (i in 0 until n) {
            val t = i / (n - 1f)
            pts[2 * i] = wu * (0.08f + 0.84f * t)
            pts[2 * i + 1] = hu / 2f + sin(t * 2.0 * PI * 1.2).toFloat() * hu * 0.22f
            pr[i] = 0.15f + 0.7f * sin(PI * t).toFloat()
        }
        c.save()
        c.scale(unit, unit)
        PenRender.draw(c, Stroke(penColor, penW, pts, penType, if (penType == PEN_FOUNTAIN) pr else null), line, fill)
        c.restore()
    }
}
