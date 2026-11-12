package com.rezonera.spenlogger

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.StaticLayout
import android.text.TextPaint
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Growable list of floats. */
class FloatList(cap: Int = 64) {
    var data = FloatArray(cap)
    var size = 0

    fun add(v: Float) {
        if (size + 1 > data.size) data = data.copyOf(data.size * 2)
        data[size++] = v
    }

    fun last(): Float = data[size - 1]

    fun toArray(): FloatArray = data.copyOf(size)
}

/**
 * The editing surface for one page at a time.
 *
 * Input rules:
 *  - Stylus (S Pen): always draws. Side button held (or eraser toggle) = area eraser.
 *  - Finger: pans / pinch-zooms the view; in TEXT / OBJECT mode it also edits text and moves images.
 *  - Distances are measured on the glass (physical mm), independent of zoom, and never rolled back by undo.
 */
class PageView(ctx: Context) : View(ctx) {

    enum class Mode { PAN, TEXT, OBJECT }

    private enum class Kind { NONE, PEN, ERASE }

    var memo: Memo? = null
        private set
    var pageIndex = 0
        private set
    val page: Page? get() = memo?.pages?.getOrNull(pageIndex)
    val pageCount: Int get() = memo?.pages?.size ?: 0

    // ---- tool settings (set by the activity) ------------------------------------------------
    var toolEraser = false
    var penType = PEN_BASIC
    var penColor = Color.BLACK
    var penWidth = 2.5f
    var eraserDp = 14f
    var mode = Mode.PAN
    var calibrating = false
    var topInset = 0f
        set(v) {
            field = v
            clampView()
            invalidate()
        }

    // ---- callbacks ---------------------------------------------------------------------------
    var onContentChanged: (() -> Unit)? = null
    var onStrokeEnd: (() -> Unit)? = null
    var onCalibStroke: ((Double) -> Unit)? = null
    var onEditText: ((TextBox?, Float, Float) -> Unit)? = null
    var onSelectionChanged: (() -> Unit)? = null
    var onPageChanged: (() -> Unit)? = null

    var selectedId: String? = null
        private set

    // ---- debug readout -----------------------------------------------------------------------
    var lastInfo = "(no input yet)"
    val logLines = ArrayList<String>()
    private var lastLoggedButtons = -1

    // ---- view transform (page units -> screen px) ---------------------------------------------
    private var scale = 1f
    private var tx = 0f
    private var ty = 0f
    private var fitScale = 1f
    private var needFit = true

    private val density = resources.displayMetrics.density
    private val xdpi = resources.displayMetrics.xdpi.let { if (it > 1f) it else 160f * density }
    private val ydpi = resources.displayMetrics.ydpi.let { if (it > 1f) it else 160f * density }
    private val touchSlop = ViewConfiguration.get(ctx).scaledTouchSlop.toFloat()

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val pagePaint = Paint().apply { color = Color.WHITE }
    private val borderPaint = Paint().apply {
        style = Paint.Style.STROKE
        color = 0xFF999999.toInt()
    }
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val placeholderPaint = Paint().apply { color = 0xFFEEEEEE.toInt() }
    private val selPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFF1E5BFF.toInt()
        strokeWidth = 2f * density
    }
    private val dst = RectF()

    // ---- undo / redo (per page, snapshot based) ----------------------------------------------
    private val undoMap = HashMap<String, java.util.ArrayDeque<PageState>>()
    private val redoMap = HashMap<String, java.util.ArrayDeque<PageState>>()

    private fun undoOf(p: Page) = undoMap.getOrPut(p.id) { java.util.ArrayDeque() }
    private fun redoOf(p: Page) = redoMap.getOrPut(p.id) { java.util.ArrayDeque() }

    private fun pushUndoBase(p: Page, base: PageState) {
        val u = undoOf(p)
        u.push(base)
        while (u.size > 100) u.removeLast()
        redoOf(p).clear()
        onContentChanged?.invoke()
    }

    /** Replace the page state as one undoable step. */
    private fun commit(ns: PageState) {
        val p = page ?: return
        pushUndoBase(p, p.state)
        p.state = ns
        invalidate()
    }

    fun canUndo(): Boolean = page?.let { undoOf(it).isNotEmpty() } ?: false
    fun canRedo(): Boolean = page?.let { redoOf(it).isNotEmpty() } ?: false

    fun undo() {
        val p = page ?: return
        val u = undoOf(p)
        if (u.isEmpty()) return
        redoOf(p).push(p.state)
        p.state = u.pop()
        afterStateSwap()
    }

    fun redo() {
        val p = page ?: return
        val r = redoOf(p)
        if (r.isEmpty()) return
        undoOf(p).push(p.state)
        p.state = r.pop()
        afterStateSwap()
    }

    private fun afterStateSwap() {
        val st = page?.state
        val sel = selectedId
        if (st != null && sel != null && st.images.none { it.id == sel } && st.texts.none { it.id == sel }) {
            selectedId = null
            onSelectionChanged?.invoke()
        }
        onContentChanged?.invoke()
        invalidate()
    }

    // ---- memo / page management ---------------------------------------------------------------

    fun loadMemo(m: Memo) {
        memo = m
        undoMap.clear()
        redoMap.clear()
        selectedId = null
        pageIndex = 0
        needFit = true
        fitToPage()
        onPageChanged?.invoke()
        invalidate()
    }

    fun setPageIndex(i: Int) {
        if (pageCount == 0) return
        pageIndex = i.coerceIn(0, pageCount - 1)
        selectedId = null
        onSelectionChanged?.invoke()
        needFit = true
        fitToPage()
        onPageChanged?.invoke()
        invalidate()
    }

    fun addBlankPage() {
        val m = memo ?: return
        m.pages.add(pageIndex + 1, Page(newId(), PAGE_W, PAGE_H_DEFAULT, PageState.EMPTY))
        onContentChanged?.invoke()
        setPageIndex(pageIndex + 1)
    }

    fun deleteCurrentPage(): Boolean {
        val m = memo ?: return false
        if (m.pages.size <= 1) return false
        val removed = m.pages.removeAt(pageIndex)
        undoMap.remove(removed.id)
        redoMap.remove(removed.id)
        onContentChanged?.invoke()
        setPageIndex(pageIndex.coerceAtMost(m.pages.size - 1))
        return true
    }

    /** Insert one page per PDF page after the current one. Each page holds a locked, page-fit PDF image. */
    fun addPdfPages(asset: String, sizes: List<Pair<Float, Float>>) {
        val m = memo ?: return
        if (sizes.isEmpty()) return
        val onlyBlank = m.pages.size == 1 && m.pages[0].state.isEmpty()
        var at = if (onlyBlank) 0 else pageIndex + 1
        val first = at
        for ((i, sz) in sizes.withIndex()) {
            val ph = PAGE_W * sz.second / sz.first
            val img = ImageObj(newId(), "pdf", asset, i, PAGE_W / 2f, ph / 2f, PAGE_W, ph, 0f, true)
            m.pages.add(at, Page(newId(), PAGE_W, ph, PageState(emptyList(), emptyList(), listOf(img))))
            at++
        }
        if (onlyBlank) {
            val removed = m.pages.removeAt(m.pages.size - 1)
            undoMap.remove(removed.id)
        }
        onContentChanged?.invoke()
        setPageIndex(first)
    }

    /** Place an image near the middle of the visible area, unlocked and selected so it can be moved. */
    fun addImage(asset: String, bmpW: Int, bmpH: Int) {
        val p = page ?: return
        if (bmpW <= 0 || bmpH <= 0) return
        val w = (p.w * 0.6f)
        val h = w * bmpH / bmpW
        val cx = toPageX(width / 2f).coerceIn(0f, p.w)
        val cy = toPageY(height / 2f).coerceIn(0f, p.h)
        val img = ImageObj(newId(), "img", asset, 0, cx, cy, w, h, 0f, false)
        commit(p.state.copy(images = p.state.images + img))
        select(img.id)
    }

    // ---- selection / object operations ---------------------------------------------------------

    private fun select(id: String?) {
        selectedId = id
        onSelectionChanged?.invoke()
        invalidate()
    }

    fun selectedImage(): ImageObj? = page?.state?.images?.firstOrNull { it.id == selectedId }

    fun selectedText(): TextBox? = page?.state?.texts?.firstOrNull { it.id == selectedId }

    fun toggleLockSelected() {
        val p = page ?: return
        val im = selectedImage() ?: return
        commit(p.state.copy(images = p.state.images.map { if (it.id == im.id) it.copy(locked = !it.locked) else it }))
        onSelectionChanged?.invoke()
    }

    fun deleteSelected() {
        val p = page ?: return
        val id = selectedId ?: return
        val st = p.state
        commit(st.copy(images = st.images.filter { it.id != id }, texts = st.texts.filter { it.id != id }))
        select(null)
    }

    fun upsertText(tb: TextBox) {
        val p = page ?: return
        val st = p.state
        val exists = st.texts.any { it.id == tb.id }
        val texts = if (exists) st.texts.map { if (it.id == tb.id) tb else it } else st.texts + tb
        commit(st.copy(texts = texts))
        select(tb.id)
    }

    fun deleteText(id: String) {
        val p = page ?: return
        commit(p.state.copy(texts = p.state.texts.filter { it.id != id }))
        if (selectedId == id) select(null)
    }

    // ---- view transform -------------------------------------------------------------------------

    private fun toPageX(sx: Float) = (sx - tx) / scale
    private fun toPageY(sy: Float) = (sy - ty) / scale

    fun fitToPage() {
        val p = page ?: return
        if (width <= 0) return
        fitScale = width / p.w
        scale = fitScale
        tx = 0f
        ty = topInset
        needFit = false
        clampView()
        invalidate()
    }

    private fun clampView() {
        val p = page ?: return
        if (width <= 0 || height <= 0) return
        val pw = p.w * scale
        val ph = p.h * scale
        tx = if (pw <= width) (width - pw) / 2f else tx.coerceIn(width - pw, 0f)
        ty = if (ph + topInset <= height) topInset else ty.coerceIn(height - ph, topInset)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (needFit && w > 0) fitToPage() else clampView()
    }

    // ---- text layout cache ------------------------------------------------------------------------

    private val layouts = HashMap<TextBox, StaticLayout>()

    private fun layoutFor(t: TextBox): StaticLayout {
        layouts[t]?.let { return it }
        if (layouts.size > 300) layouts.clear()
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG)
        tp.color = t.color
        tp.textSize = t.size
        val l = StaticLayout.Builder.obtain(t.text, 0, t.text.length, tp, t.width.toInt().coerceAtLeast(10)).build()
        layouts[t] = l
        return l
    }

    // ---- drawing --------------------------------------------------------------------------------------

    private var cursorOn = false
    private var cursorX = 0f
    private var cursorY = 0f

    // live stroke (pen)
    private var livePts: FloatBuf? = null
    private var livePr: FloatList? = null
    private var liveColor = Color.BLACK
    private var liveWidth = 2.5f
    private var liveType = PEN_BASIC
    private var smoothP = 0.5f
    private var liveStroke: Stroke? = null
    private var liveDirty = false

    private fun liveStrokeObj(): Stroke? {
        val b = livePts ?: return null
        if (liveDirty || liveStroke == null) {
            val pr = if (liveType == PEN_FOUNTAIN) livePr?.toArray() else null
            liveStroke = Stroke(liveColor, liveWidth, b.toArray(), liveType, pr)
            liveDirty = false
        }
        return liveStroke
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(0xFFCFCFCF.toInt())
        val p = page
        if (p != null) {
            val st = p.state
            c.save()
            c.translate(tx, ty)
            c.scale(scale, scale)
            c.drawRect(0f, 0f, p.w, p.h, pagePaint)

            c.save()
            c.clipRect(0f, 0f, p.w, p.h)
            for (im in st.images) drawImage(c, im)
            for (t in st.texts) {
                val l = layoutFor(t)
                c.save()
                c.translate(t.x, t.y)
                l.draw(c)
                c.restore()
            }
            val vl = -tx / scale
            val vt = -ty / scale
            val vr = (width - tx) / scale
            val vb = (height - ty) / scale
            for (s in st.strokes) {
                if (s.maxX < vl || s.minX > vr || s.maxY < vt || s.minY > vb) continue
                PenRender.draw(c, s, linePaint, fillPaint)
            }
            liveStrokeObj()?.let { PenRender.draw(c, it, linePaint, fillPaint) }
            c.restore()

            borderPaint.strokeWidth = 1f / scale
            c.drawRect(0f, 0f, p.w, p.h, borderPaint)
            drawSelection(c, st)
            c.restore()
        }
        if (cursorOn) c.drawCircle(cursorX, cursorY, eraserDp * density, cursorPaint)
    }

    private fun drawImage(c: Canvas, im: ImageObj) {
        val m = memo ?: return
        val f = Store.assetFile(m.id, im.asset)
        val key: String
        val loader: () -> android.graphics.Bitmap?
        if (im.kind == "pdf") {
            key = "pdf:${f.path}:${im.pdfPage}:2000"
            loader = Bitmaps.pdfLoader(f, im.pdfPage, 2000)
        } else {
            key = "img:${f.path}:1600"
            loader = Bitmaps.imageLoader(f, 1600)
        }
        val bmp = Bitmaps.get(key, loader) { invalidate() }
        c.save()
        c.translate(im.cx, im.cy)
        c.rotate(im.rot)
        dst.set(-im.w / 2f, -im.h / 2f, im.w / 2f, im.h / 2f)
        if (bmp != null) c.drawBitmap(bmp, null, dst, bmpPaint) else c.drawRect(dst, placeholderPaint)
        c.restore()
    }

    private fun drawSelection(c: Canvas, st: PageState) {
        val sel = selectedId ?: return
        selPaint.strokeWidth = 2f / scale
        val im = st.images.firstOrNull { it.id == sel }
        if (im != null) {
            selPaint.color = if (im.locked) 0xFF888888.toInt() else 0xFF1E6BFF.toInt()
            c.save()
            c.translate(im.cx, im.cy)
            c.rotate(im.rot)
            c.drawRect(-im.w / 2f, -im.h / 2f, im.w / 2f, im.h / 2f, selPaint)
            c.restore()
            return
        }
        val t = st.texts.firstOrNull { it.id == sel } ?: return
        val l = layoutFor(t)
        selPaint.color = 0xFF1E6BFF.toInt()
        c.drawRect(t.x, t.y, t.x + t.width, t.y + l.height, selPaint)
    }

    // ---- debug / helpers ------------------------------------------------------------------------------

    private fun toolName(t: Int) = when (t) {
        MotionEvent.TOOL_TYPE_FINGER -> "finger"
        MotionEvent.TOOL_TYPE_STYLUS -> "stylus"
        MotionEvent.TOOL_TYPE_ERASER -> "eraser"
        MotionEvent.TOOL_TYPE_MOUSE -> "mouse"
        else -> "unknown($t)"
    }

    private fun isStylusTool(t: Int) = t == MotionEvent.TOOL_TYPE_STYLUS || t == MotionEvent.TOOL_TYPE_ERASER

    /** S Pen side button held (BUTTON_STYLUS_PRIMARY; some stacks report SECONDARY). */
    private fun buttonHeld(e: MotionEvent): Boolean =
        (e.buttonState and (MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_SECONDARY)) != 0

    private fun note(e: MotionEvent, idx: Int, tag: String) {
        val bs = e.buttonState
        val action = e.actionMasked
        val i = idx.coerceIn(0, e.pointerCount - 1)
        lastInfo = "${MotionEvent.actionToString(action)} tool=${toolName(e.getToolType(i))} " +
            "btnState=0x${Integer.toHexString(bs)} p=${"%.2f".format(e.getPressure(i))}$tag"
        val quiet = action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_HOVER_MOVE
        if (!quiet || bs != lastLoggedButtons) {
            lastLoggedButtons = bs
            logLines.add(lastInfo)
            if (logLines.size > 12) logLines.removeAt(0)
        }
    }

    private fun rawMm(x0: Float, y0: Float, x1: Float, y1: Float): Double =
        hypot(((x1 - x0) / xdpi).toDouble(), ((y1 - y0) / ydpi).toDouble()) * 25.4

    // ---- stylus ---------------------------------------------------------------------------------------

    private var stylusId = -1
    private var kind = Kind.NONE
    private var lastSx = 0f
    private var lastSy = 0f
    private var calibRaw = 0.0
    private var gestureBase: PageState? = null

    private fun startStylus(e: MotionEvent, idx: Int) {
        val p = page ?: return
        stylusId = e.getPointerId(idx)
        lastSx = e.getX(idx)
        lastSy = e.getY(idx)
        val eraser = toolEraser || buttonHeld(e) || e.getToolType(idx) == MotionEvent.TOOL_TYPE_ERASER
        if (eraser && !calibrating) {
            kind = Kind.ERASE
            gestureBase = p.state
            eraseSegment(lastSx, lastSy, lastSx, lastSy)
            cursorOn = true
            cursorX = lastSx
            cursorY = lastSy
        } else {
            kind = Kind.PEN
            val b = FloatBuf()
            val pr = FloatList()
            b.add(toPageX(lastSx), toPageY(lastSy))
            smoothP = e.getPressure(idx)
            pr.add(smoothP)
            livePts = b
            livePr = pr
            liveColor = if (calibrating) Color.BLUE else penColor
            liveWidth = penWidth
            liveType = if (calibrating) PEN_BASIC else penType
            liveDirty = true
            calibRaw = 0.0
        }
        invalidate()
    }

    private fun moveStylus(e: MotionEvent, idx: Int) {
        for (h in 0 until e.historySize) {
            stylusSample(e.getHistoricalX(idx, h), e.getHistoricalY(idx, h), e.getHistoricalPressure(idx, h))
        }
        stylusSample(e.getX(idx), e.getY(idx), e.getPressure(idx))
        invalidate()
    }

    private fun stylusSample(sx: Float, sy: Float, pressure: Float) {
        val raw = rawMm(lastSx, lastSy, sx, sy)
        if (raw <= 0.0) return
        when (kind) {
            Kind.PEN -> {
                if (calibrating) calibRaw += raw else Store.addDistance(raw * Store.calib, 0.0)
                appendLive(toPageX(sx), toPageY(sy), pressure)
            }
            Kind.ERASE -> {
                Store.addDistance(0.0, raw * Store.calib)
                eraseSegment(lastSx, lastSy, sx, sy)
                cursorX = sx
                cursorY = sy
            }
            Kind.NONE -> {}
        }
        lastSx = sx
        lastSy = sy
    }

    /** Append a point, inserting interpolated points so the spacing stays <= 2 page units (needed for area erase). */
    private fun appendLive(px: Float, py: Float, pressure: Float) {
        val b = livePts ?: return
        val pr = livePr ?: return
        if (b.size < 2) return
        val lx = b.data[b.size - 2]
        val ly = b.data[b.size - 1]
        val dx = px - lx
        val dy = py - ly
        val d = hypot(dx, dy)
        if (d < 0.3f) return
        val lastP = pr.last()
        smoothP = smoothP * 0.6f + pressure * 0.4f
        val n = ceil(d / 2f).toInt().coerceAtLeast(1)
        for (i in 1..n) {
            val t = i.toFloat() / n
            b.add(lx + dx * t, ly + dy * t)
            pr.add(lastP + (smoothP - lastP) * t)
        }
        liveDirty = true
    }

    private fun endStylus(e: MotionEvent, idx: Int, cancel: Boolean) {
        val p = page
        if (!cancel) stylusSample(e.getX(idx), e.getY(idx), e.getPressure(idx))
        when (kind) {
            Kind.PEN -> {
                val b = livePts
                if (calibrating) {
                    calibrating = false
                    if (calibRaw > 5.0) onCalibStroke?.invoke(calibRaw)
                } else if (b != null && b.size >= 2 && p != null && !cancel) {
                    val pr = if (liveType == PEN_FOUNTAIN) livePr?.toArray() else null
                    val s = Stroke(liveColor, liveWidth, b.toArray(), liveType, pr)
                    commit(p.state.copy(strokes = p.state.strokes + s))
                }
                livePts = null
                livePr = null
                liveStroke = null
            }
            Kind.ERASE -> {
                val base = gestureBase
                if (base != null && p != null && p.state !== base) pushUndoBase(p, base)
            }
            Kind.NONE -> {}
        }
        kind = Kind.NONE
        stylusId = -1
        gestureBase = null
        cursorOn = false
        Store.saveDaily()
        onStrokeEnd?.invoke()
        invalidate()
    }

    // ---- area eraser -----------------------------------------------------------------------------------

    private fun eraseSegment(sx0: Float, sy0: Float, sx1: Float, sy1: Float) {
        val p = page ?: return
        val r = eraserDp * density / scale
        val ax = toPageX(sx0)
        val ay = toPageY(sy0)
        val bx = toPageX(sx1)
        val by = toPageY(sy1)
        val d = hypot(bx - ax, by - ay)
        val n = ceil(d / (r * 0.5f)).toInt().coerceAtLeast(1)
        var strokes = p.state.strokes
        var changed = false
        for (i in 0..n) {
            val t = i.toFloat() / n
            val res = eraseAt(strokes, ax + (bx - ax) * t, ay + (by - ay) * t, r)
            if (res != null) {
                strokes = res
                changed = true
            }
        }
        if (changed) p.state = p.state.copy(strokes = strokes)
    }

    private fun eraseAt(list: List<Stroke>, cx: Float, cy: Float, r: Float): List<Stroke>? {
        val r2 = r * r
        var out: ArrayList<Stroke>? = null
        for (i in list.indices) {
            val s = list[i]
            val near = s.maxX >= cx - r && s.minX <= cx + r && s.maxY >= cy - r && s.minY <= cy + r
            val parts = if (near) splitStroke(s, cx, cy, r2) else null
            if (parts != null) {
                val o = out ?: ArrayList<Stroke>(list.size + 4).also { n ->
                    for (j in 0 until i) n.add(list[j])
                    out = n
                }
                o.addAll(parts)
            } else {
                out?.add(s)
            }
        }
        return out
    }

    /** Remove the points inside the eraser circle; the remaining runs become new strokes. */
    private fun splitStroke(s: Stroke, cx: Float, cy: Float, r2: Float): List<Stroke>? {
        val p = s.pts
        val n = p.size / 2
        var anyHit = false
        for (i in 0 until n) {
            val dx = p[2 * i] - cx
            val dy = p[2 * i + 1] - cy
            if (dx * dx + dy * dy <= r2) {
                anyHit = true
                break
            }
        }
        if (!anyHit) return null
        val out = ArrayList<Stroke>()
        var run = FloatBuf()
        var runPr = FloatList()
        val srcPr = s.pr
        fun flush() {
            if (run.size >= 4) {
                out.add(Stroke(s.color, s.width, run.toArray(), s.type, if (srcPr != null) runPr.toArray() else null))
            }
            run = FloatBuf()
            runPr = FloatList()
        }
        for (i in 0 until n) {
            val dx = p[2 * i] - cx
            val dy = p[2 * i + 1] - cy
            if (dx * dx + dy * dy <= r2) {
                flush()
            } else {
                run.add(p[2 * i], p[2 * i + 1])
                if (srcPr != null) runPr.add(srcPr[i])
            }
        }
        flush()
        return out
    }

    // ---- finger gestures --------------------------------------------------------------------------------

    private var f1 = -1
    private var f2 = -1
    private var f1x = 0f
    private var f1y = 0f
    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private var dragText: String? = null
    private var dragImage: String? = null
    private var manipImage: String? = null
    private var pinchDist = 1f
    private var pinchAngle = 0f
    private var pinchCx = 0f
    private var pinchCy = 0f

    private fun hitText(px: Float, py: Float): TextBox? {
        val st = page?.state ?: return null
        for (i in st.texts.indices.reversed()) {
            val t = st.texts[i]
            val l = layoutFor(t)
            if (px >= t.x && px <= t.x + t.width && py >= t.y && py <= t.y + l.height) return t
        }
        return null
    }

    private fun hitImage(px: Float, py: Float): ImageObj? {
        val st = page?.state ?: return null
        for (i in st.images.indices.reversed()) {
            val im = st.images[i]
            val rad = Math.toRadians(im.rot.toDouble())
            val c = cos(rad).toFloat()
            val s = sin(rad).toFloat()
            val dx = px - im.cx
            val dy = py - im.cy
            val lx = dx * c + dy * s
            val ly = -dx * s + dy * c
            if (abs(lx) <= im.w / 2f && abs(ly) <= im.h / 2f) return im
        }
        return null
    }

    private fun beginManip() {
        if (gestureBase == null) gestureBase = page?.state
    }

    private fun endFingerGesture() {
        val base = gestureBase
        val p = page
        if (base != null && p != null && p.state !== base) pushUndoBase(p, base)
        gestureBase = null
    }

    private fun cancelFinger() {
        endFingerGesture()
        f1 = -1
        f2 = -1
        dragText = null
        dragImage = null
        manipImage = null
        moved = false
    }

    private fun fingerDown(e: MotionEvent, idx: Int) {
        val id = e.getPointerId(idx)
        if (f1 == -1) {
            f1 = id
            f1x = e.getX(idx)
            f1y = e.getY(idx)
            downX = f1x
            downY = f1y
            moved = false
            dragText = null
            dragImage = null
            manipImage = null
            val px = toPageX(f1x)
            val py = toPageY(f1y)
            when (mode) {
                Mode.OBJECT -> {
                    val t = hitText(px, py)
                    val im = if (t == null) hitImage(px, py) else null
                    if (t != null) {
                        select(t.id)
                        dragText = t.id
                    } else if (im != null) {
                        select(im.id)
                        if (!im.locked) dragImage = im.id
                    }
                }
                Mode.TEXT -> {
                    val t = hitText(px, py)
                    if (t != null) {
                        select(t.id)
                        dragText = t.id
                    }
                }
                Mode.PAN -> {}
            }
        } else if (f2 == -1) {
            f2 = id
            dragText = null
            dragImage = null
            val i1 = e.findPointerIndex(f1)
            if (i1 >= 0) {
                val x1 = e.getX(i1)
                val y1 = e.getY(i1)
                val x2 = e.getX(idx)
                val y2 = e.getY(idx)
                pinchDist = hypot(x2 - x1, y2 - y1).coerceAtLeast(1f)
                pinchAngle = atan2(y2 - y1, x2 - x1)
                pinchCx = (x1 + x2) / 2f
                pinchCy = (y1 + y2) / 2f
            }
            val im = selectedImage()
            manipImage = if (mode == Mode.OBJECT && im != null && !im.locked) im.id else null
            if (manipImage != null) beginManip()
            moved = true
        }
    }

    private fun fingerMove(e: MotionEvent) {
        val i1 = e.findPointerIndex(f1)
        if (i1 < 0) return
        if (f2 != -1) {
            val i2 = e.findPointerIndex(f2)
            if (i2 < 0) return
            val x1 = e.getX(i1)
            val y1 = e.getY(i1)
            val x2 = e.getX(i2)
            val y2 = e.getY(i2)
            val cx = (x1 + x2) / 2f
            val cy = (y1 + y2) / 2f
            val dist = hypot(x2 - x1, y2 - y1).coerceAtLeast(1f)
            val ang = atan2(y2 - y1, x2 - x1)
            val mid = manipImage
            if (mid != null) {
                var da = ang - pinchAngle
                if (da > Math.PI.toFloat()) da -= (2 * Math.PI).toFloat()
                if (da < -Math.PI.toFloat()) da += (2 * Math.PI).toFloat()
                transformImage(mid, (cx - pinchCx) / scale, (cy - pinchCy) / scale, dist / pinchDist, Math.toDegrees(da.toDouble()).toFloat())
            } else {
                val px = (pinchCx - tx) / scale
                val py = (pinchCy - ty) / scale
                scale = (scale * dist / pinchDist).coerceIn(fitScale * 0.4f, fitScale * 10f)
                tx = cx - px * scale
                ty = cy - py * scale
                clampView()
            }
            pinchDist = dist
            pinchAngle = ang
            pinchCx = cx
            pinchCy = cy
            invalidate()
            return
        }
        val x = e.getX(i1)
        val y = e.getY(i1)
        val dx = x - f1x
        val dy = y - f1y
        if (!moved && hypot(x - downX, y - downY) > touchSlop) moved = true
        if (moved) {
            val dt = dragText
            val di = dragImage
            if (dt != null) {
                beginManip()
                moveText(dt, dx / scale, dy / scale)
            } else if (di != null) {
                beginManip()
                transformImage(di, dx / scale, dy / scale, 1f, 0f)
            } else {
                tx += dx
                ty += dy
                clampView()
            }
        }
        f1x = x
        f1y = y
        invalidate()
    }

    private fun fingerUp(e: MotionEvent, idx: Int, last: Boolean) {
        val id = e.getPointerId(idx)
        if (id == f2) {
            f2 = -1
            manipImage = null
            moved = true
        } else if (id == f1) {
            if (f2 != -1) {
                // promote the remaining finger to be the primary one
                f1 = f2
                f2 = -1
                manipImage = null
                val j = e.findPointerIndex(f1)
                if (j >= 0) {
                    f1x = e.getX(j)
                    f1y = e.getY(j)
                }
                moved = true
            } else {
                if (!moved && last) handleTap()
                endFingerGesture()
                f1 = -1
                dragText = null
                dragImage = null
            }
        }
    }

    private fun handleTap() {
        val px = toPageX(downX)
        val py = toPageY(downY)
        when (mode) {
            Mode.TEXT -> {
                val t = hitText(px, py)
                if (t != null) onEditText?.invoke(t, t.x, t.y) else onEditText?.invoke(null, px, py)
            }
            Mode.OBJECT -> {
                if (hitText(px, py) == null && hitImage(px, py) == null) select(null)
            }
            Mode.PAN -> {}
        }
    }

    private fun moveText(id: String, dx: Float, dy: Float) {
        val p = page ?: return
        p.state = p.state.copy(texts = p.state.texts.map { if (it.id == id) it.copy(x = it.x + dx, y = it.y + dy) else it })
    }

    private fun transformImage(id: String, dx: Float, dy: Float, ratio: Float, rotDeg: Float) {
        val p = page ?: return
        p.state = p.state.copy(
            images = p.state.images.map {
                if (it.id != id) {
                    it
                } else {
                    val nw = (it.w * ratio).coerceIn(20f, 6000f)
                    it.copy(cx = it.cx + dx, cy = it.cy + dy, w = nw, h = it.h * (nw / it.w), rot = it.rot + rotDeg)
                }
            }
        )
    }

    // ---- event entry points ---------------------------------------------------------------------------------

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val action = e.actionMasked
        when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = e.actionIndex
                note(e, idx, "")
                if (isStylusTool(e.getToolType(idx))) {
                    // A pen touching down while a palm/finger is already on the glass wins.
                    if (stylusId == -1) {
                        cancelFinger()
                        startStylus(e, idx)
                    }
                } else if (stylusId == -1) {
                    fingerDown(e, idx)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                note(e, 0, "")
                if (stylusId != -1) {
                    val i = e.findPointerIndex(stylusId)
                    if (i >= 0) moveStylus(e, i)
                } else if (f1 != -1) {
                    fingerMove(e)
                }
            }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> {
                val idx = e.actionIndex
                note(e, idx, "")
                val id = e.getPointerId(idx)
                if (id == stylusId) {
                    endStylus(e, idx, false)
                } else if (stylusId == -1) {
                    fingerUp(e, idx, action == MotionEvent.ACTION_UP)
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                note(e, 0, "")
                if (stylusId != -1) {
                    val i = e.findPointerIndex(stylusId).coerceAtLeast(0)
                    endStylus(e, i, true)
                }
                cancelFinger()
            }
        }
        return true
    }

    override fun onHoverEvent(e: MotionEvent): Boolean {
        note(e, 0, " (hover)")
        val on = (toolEraser || buttonHeld(e)) && e.actionMasked != MotionEvent.ACTION_HOVER_EXIT
        if (on || cursorOn) {
            cursorOn = on
            cursorX = e.x
            cursorY = e.y
            invalidate()
        }
        return true
    }

    override fun onGenericMotionEvent(e: MotionEvent): Boolean {
        // ACTION_BUTTON_PRESS / ACTION_BUTTON_RELEASE for the S Pen side button arrive here.
        note(e, 0, " (generic)")
        return true
    }
}
