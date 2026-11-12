package com.rezonera.spenlogger

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.hypot

/**
 * S Pen Logger v0 (probe build).
 *
 * Goals of this build:
 *  1. Verify that the S Pen side button is visible to a native app (MotionEvent.buttonState).
 *  2. Hold the side button => eraser, like Samsung Notes.
 *  3. Accumulate physical stroke length in meters: pen / eraser / total.
 */
class MainActivity : Activity() {

    private lateinit var ink: InkView
    private lateinit var counters: TextView
    private lateinit var debugView: TextView
    private lateinit var toolBtn: Button
    private var showDebug = true
    private val handler = Handler(Looper.getMainLooper())

    private val tick = object : Runnable {
        override fun run() {
            refreshHud()
            handler.postDelayed(this, 100)
        }
    }

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        ink = InkView(this).apply {
            penMm = prefs.getString("penMm", null)?.toDoubleOrNull() ?: 0.0
            eraMm = prefs.getString("eraMm", null)?.toDoubleOrNull() ?: 0.0
            calib = prefs.getString("calib", null)?.toDoubleOrNull() ?: 1.0
            onStrokeEnd = { save() }
            onCalibStroke = { rawMm -> askCalibration(rawMm) }
        }

        val root = FrameLayout(this)
        root.fitsSystemWindows = true
        root.addView(ink, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val hud = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xDD14141E.toInt())
            setPadding(dp(8f), dp(6f), dp(8f), dp(6f))
        }

        counters = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = Typeface.MONOSPACE
        }
        hud.addView(counters)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun addBtn(label: String, onClick: (Button) -> Unit): Button {
            val b = Button(this).apply {
                text = label
                textSize = 12f
                isAllCaps = false
                setOnClickListener { onClick(this) }
            }
            row.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            return b
        }
        toolBtn = addBtn("Tool: PEN") { b ->
            ink.toolEraser = !ink.toolEraser
            b.text = if (ink.toolEraser) "Tool: ERASER" else "Tool: PEN"
        }
        addBtn("Clear") { ink.clearInk() }
        addBtn("Calibrate") {
            ink.calibrating = true
            Toast.makeText(this, "Draw ONE straight line along a ruler, then enter its real length", Toast.LENGTH_LONG).show()
        }
        addBtn("Reset") { confirmReset() }
        addBtn("Debug") { showDebug = !showDebug }
        hud.addView(row)

        debugView = TextView(this).apply {
            setTextColor(0xFF9FE6A0.toInt())
            textSize = 10f
            typeface = Typeface.MONOSPACE
        }
        hud.addView(debugView)

        root.addView(hud, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        handler.post(tick)
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        save()
        super.onPause()
    }

    private fun save() {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("penMm", ink.penMm.toString())
            .putString("eraMm", ink.eraMm.toString())
            .putString("calib", ink.calib.toString())
            .apply()
    }

    private fun fmtM(mm: Double) = "%.3f m".format(mm / 1000.0)

    private fun refreshHud() {
        counters.text = "PEN ${fmtM(ink.penMm)}  ERASER ${fmtM(ink.eraMm)}  TOTAL ${fmtM(ink.penMm + ink.eraMm)}"
        if (!showDebug) {
            debugView.visibility = View.GONE
            return
        }
        debugView.visibility = View.VISIBLE
        val sb = StringBuilder()
        sb.append("LIVE ").append(ink.lastInfo).append('\n')
        sb.append("eraserNow=").append(ink.eraserNow)
            .append("  calib=").append("%.4f".format(ink.calib))
            .append("  dpi=").append("%.0f/%.0f".format(ink.xdpiValue, ink.ydpiValue)).append('\n')
        sb.append("btnState bits: 0x20=STYLUS_PRIMARY(S Pen button) 0x2=SECONDARY\n")
        for (line in ink.logLines.asReversed()) sb.append(line).append('\n')
        debugView.text = sb.toString()
    }

    private fun confirmReset() {
        AlertDialog.Builder(this)
            .setMessage("Reset pen/eraser counters to 0?")
            .setPositiveButton("Reset") { _, _ ->
                ink.penMm = 0.0
                ink.eraMm = 0.0
                save()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun askCalibration(rawMm: Double) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = "real length (mm)"
        }
        AlertDialog.Builder(this)
            .setTitle("Calibration")
            .setMessage("Measured %.1f mm (uncalibrated). Enter the real length in mm.".format(rawMm))
            .setView(input)
            .setPositiveButton("Apply") { _, _ ->
                val real = input.text.toString().toDoubleOrNull()
                if (real != null && real > 0.0 && rawMm > 0.0) {
                    ink.calib = real / rawMm
                    save()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    companion object {
        private const val PREFS = "spenlogger"
    }
}

/** Full-screen drawing surface that also measures the physical length of every stylus stroke. */
class InkView(ctx: Context) : View(ctx) {

    var penMm = 0.0
    var eraMm = 0.0
    var calib = 1.0
    var toolEraser = false
    var calibrating = false
    var onStrokeEnd: (() -> Unit)? = null
    var onCalibStroke: ((Double) -> Unit)? = null

    var lastInfo = "(no input yet)"
    var eraserNow = false
    val logLines = ArrayList<String>()

    private val density = resources.displayMetrics.density
    val xdpiValue: Float = resources.displayMetrics.xdpi.let { if (it > 1f) it else 160f * density }
    val ydpiValue: Float = resources.displayMetrics.ydpi.let { if (it > 1f) it else 160f * density }

    private var bmp: Bitmap? = null
    private var bmpCanvas: Canvas? = null

    private val penPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val erasePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        strokeWidth = 28f * density
    }

    private var stroking = false
    private var lastX = 0f
    private var lastY = 0f
    private var calibRaw = 0.0
    private var lastLoggedButtons = -1

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (w <= 0 || h <= 0) return
        val nb = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val nc = Canvas(nb)
        bmp?.let { nc.drawBitmap(it, 0f, 0f, null) }
        bmp = nb
        bmpCanvas = nc
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.WHITE)
        bmp?.let { canvas.drawBitmap(it, 0f, 0f, null) }
    }

    fun clearInk() {
        bmp?.eraseColor(Color.TRANSPARENT)
        invalidate()
    }

    // --- input -------------------------------------------------------------------------------

    private fun toolName(t: Int) = when (t) {
        MotionEvent.TOOL_TYPE_FINGER -> "finger"
        MotionEvent.TOOL_TYPE_STYLUS -> "stylus"
        MotionEvent.TOOL_TYPE_ERASER -> "eraser"
        MotionEvent.TOOL_TYPE_MOUSE -> "mouse"
        else -> "unknown($t)"
    }

    /** S Pen side button held (reported as BUTTON_STYLUS_PRIMARY; some stacks use SECONDARY). */
    private fun buttonHeld(e: MotionEvent): Boolean =
        (e.buttonState and (MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_SECONDARY)) != 0

    private fun isEraser(e: MotionEvent): Boolean =
        toolEraser || buttonHeld(e) || e.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER

    /** Update the live readout; only log discrete events or button-state changes (not every move). */
    private fun note(e: MotionEvent, tag: String) {
        val bs = e.buttonState
        val action = e.actionMasked
        lastInfo = "${MotionEvent.actionToString(action)} tool=${toolName(e.getToolType(0))} " +
            "btnState=0x${Integer.toHexString(bs)} p=${"%.2f".format(e.pressure)}$tag"
        val quiet = action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_HOVER_MOVE
        if (!quiet || bs != lastLoggedButtons) {
            lastLoggedButtons = bs
            logLines.add(lastInfo)
            if (logLines.size > 14) logLines.removeAt(0)
        }
        eraserNow = isEraser(e)
    }

    override fun onHoverEvent(e: MotionEvent): Boolean {
        note(e, " (hover)")
        return true
    }

    override fun onGenericMotionEvent(e: MotionEvent): Boolean {
        // ACTION_BUTTON_PRESS / ACTION_BUTTON_RELEASE for the S Pen side button arrive here.
        note(e, " (generic)")
        return true
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val tool = e.getToolType(0)
        val stylus = tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER
        note(e, if (stylus) "" else " [IGNORED]")
        if (!stylus) return true // consume and ignore fingers / palm

        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = e.x
                lastY = e.y
                stroking = true
                calibRaw = 0.0
            }
            MotionEvent.ACTION_MOVE -> if (stroking) {
                // Historical samples carry every hardware sample between two frames.
                for (i in 0 until e.historySize) {
                    segment(e.getHistoricalX(i), e.getHistoricalY(i), e.getHistoricalPressure(i), e)
                }
                segment(e.x, e.y, e.pressure, e)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (stroking) {
                stroking = false
                if (calibrating) {
                    calibrating = false
                    if (calibRaw > 5.0) onCalibStroke?.invoke(calibRaw)
                }
                onStrokeEnd?.invoke()
            }
        }
        return true
    }

    /** Physical distance in mm between two screen points, before calibration. */
    private fun rawMm(x0: Float, y0: Float, x1: Float, y1: Float): Double =
        hypot(((x1 - x0) / xdpiValue).toDouble(), ((y1 - y0) / ydpiValue).toDouble()) * 25.4

    private fun segment(x: Float, y: Float, pressure: Float, e: MotionEvent) {
        val raw = rawMm(lastX, lastY, x, y)
        if (raw > 0.0) {
            if (calibrating) {
                calibRaw += raw
                drawSeg(lastX, lastY, x, y, pressure, false, Color.BLUE)
            } else {
                val er = isEraser(e)
                val mm = raw * calib
                if (er) eraMm += mm else penMm += mm
                drawSeg(lastX, lastY, x, y, pressure, er, Color.BLACK)
            }
        }
        lastX = x
        lastY = y
    }

    private fun drawSeg(x0: Float, y0: Float, x1: Float, y1: Float, p: Float, eraser: Boolean, color: Int) {
        val c = bmpCanvas ?: return
        if (eraser) {
            c.drawLine(x0, y0, x1, y1, erasePaint)
        } else {
            penPaint.color = color
            penPaint.strokeWidth = (1f + p.coerceIn(0f, 1f) * 2.5f) * density
            c.drawLine(x0, y0, x1, y1, penPaint)
        }
        invalidate()
    }
}
