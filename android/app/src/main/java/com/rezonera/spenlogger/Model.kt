package com.rezonera.spenlogger

import android.graphics.Path
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.hypot

const val PEN_BASIC = 0
const val PEN_FOUNTAIN = 1

/** Logical page width in "page units". Every page is scaled to this width; height follows the aspect ratio. */
const val PAGE_W = 1000f
const val PAGE_H_DEFAULT = 1414f

fun newId(): String = UUID.randomUUID().toString().replace("-", "").substring(0, 12)

/** Growable list of (x, y) pairs stored in a flat float array. */
class FloatBuf(cap: Int = 64) {
    var data = FloatArray(cap)
    var size = 0

    fun add(x: Float, y: Float) {
        if (size + 2 > data.size) data = data.copyOf(data.size * 2)
        data[size++] = x
        data[size++] = y
    }

    fun toArray(): FloatArray = data.copyOf(size)
}

/**
 * Immutable vector stroke in page coordinates. pts = x0,y0,x1,y1,...
 * type PEN_BASIC: constant width. type PEN_FOUNTAIN: width follows pr (one pressure value per point).
 */
class Stroke(
    val color: Int,
    val width: Float,
    val pts: FloatArray,
    val type: Int = PEN_BASIC,
    val pr: FloatArray? = null,
) {
    val minX: Float
    val minY: Float
    val maxX: Float
    val maxY: Float

    init {
        var a = Float.MAX_VALUE
        var b = Float.MAX_VALUE
        var c = -Float.MAX_VALUE
        var d = -Float.MAX_VALUE
        var i = 0
        while (i + 1 < pts.size) {
            val x = pts[i]
            val y = pts[i + 1]
            if (x < a) a = x
            if (y < b) b = y
            if (x > c) c = x
            if (y > d) d = y
            i += 2
        }
        minX = a
        minY = b
        maxX = c
        maxY = d
    }

    /** Cached render path (a single point becomes a dot thanks to the round cap). */
    val path: Path by lazy {
        val p = Path()
        if (pts.size >= 2) {
            p.moveTo(pts[0], pts[1])
            if (pts.size == 2) {
                p.lineTo(pts[0], pts[1])
            } else {
                var i = 2
                while (i + 1 < pts.size) {
                    p.lineTo(pts[i], pts[i + 1])
                    i += 2
                }
            }
        }
        p
    }

    private fun halfWidth(i: Int): Float {
        val p = pr?.getOrNull(i) ?: 0.5f
        return width * (0.25f + 1.5f * p.coerceIn(0f, 1f)) / 2f
    }

    /**
     * Filled outline for the fountain pen. Every segment is its own quad with the same winding
     * direction, so overlaps union cleanly under the non-zero fill rule (no holes at sharp turns).
     */
    val fillPath: Path by lazy {
        val path = Path()
        val n = pts.size / 2
        if (n == 1) {
            path.addCircle(pts[0], pts[1], halfWidth(0), Path.Direction.CW)
        } else if (n > 1) {
            val lx = FloatArray(n)
            val ly = FloatArray(n)
            val rx = FloatArray(n)
            val ry = FloatArray(n)
            for (i in 0 until n) {
                val i0 = (i - 1).coerceAtLeast(0)
                val i1 = (i + 1).coerceAtMost(n - 1)
                var dx = pts[2 * i1] - pts[2 * i0]
                var dy = pts[2 * i1 + 1] - pts[2 * i0 + 1]
                val len = hypot(dx, dy)
                if (len < 1e-4f) {
                    dx = 1f
                    dy = 0f
                } else {
                    dx /= len
                    dy /= len
                }
                val nx = -dy
                val ny = dx
                val h = halfWidth(i)
                lx[i] = pts[2 * i] + nx * h
                ly[i] = pts[2 * i + 1] + ny * h
                rx[i] = pts[2 * i] - nx * h
                ry[i] = pts[2 * i + 1] - ny * h
            }
            for (i in 0 until n - 1) {
                val ax = lx[i]
                val ay = ly[i]
                val bx = lx[i + 1]
                val by = ly[i + 1]
                val cx = rx[i + 1]
                val cy = ry[i + 1]
                val ex = rx[i]
                val ey = ry[i]
                val area = ax * by - bx * ay + bx * cy - cx * by + cx * ey - ex * cy + ex * ay - ax * ey
                path.moveTo(ax, ay)
                if (area >= 0f) {
                    path.lineTo(bx, by)
                    path.lineTo(cx, cy)
                    path.lineTo(ex, ey)
                } else {
                    path.lineTo(ex, ey)
                    path.lineTo(cx, cy)
                    path.lineTo(bx, by)
                }
                path.close()
            }
            path.addCircle(pts[0], pts[1], halfWidth(0), Path.Direction.CW)
            path.addCircle(pts[2 * (n - 1)], pts[2 * (n - 1) + 1], halfWidth(n - 1), Path.Direction.CW)
        }
        path
    }
}

/** Shared stroke drawing used by the page view and the pen preview. */
object PenRender {
    fun draw(c: android.graphics.Canvas, s: Stroke, linePaint: android.graphics.Paint, fillPaint: android.graphics.Paint) {
        if (s.type == PEN_FOUNTAIN && s.pr != null) {
            fillPaint.color = s.color
            c.drawPath(s.fillPath, fillPaint)
        } else {
            linePaint.color = s.color
            linePaint.strokeWidth = s.width
            c.drawPath(s.path, linePaint)
        }
    }
}

data class TextBox(
    val id: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val text: String,
    val size: Float,
    val color: Int,
)

/** An image or a PDF page placed on a page. kind = "img" or "pdf". */
data class ImageObj(
    val id: String,
    val kind: String,
    val asset: String,
    val pdfPage: Int,
    val cx: Float,
    val cy: Float,
    val w: Float,
    val h: Float,
    val rot: Float,
    val locked: Boolean,
)

/** Immutable snapshot of everything on a page. Undo/redo simply swaps snapshots. */
data class PageState(
    val strokes: List<Stroke>,
    val texts: List<TextBox>,
    val images: List<ImageObj>,
) {
    fun isEmpty() = strokes.isEmpty() && texts.isEmpty() && images.isEmpty()

    companion object {
        val EMPTY = PageState(emptyList(), emptyList(), emptyList())
    }
}

class Page(val id: String, val w: Float, val h: Float, var state: PageState)

class Memo(
    val id: String,
    var title: String,
    val created: Long,
    var modified: Long,
    val pages: MutableList<Page>,
)

object MemoCodec {
    private fun r(v: Float): Double = Math.round(v * 100f) / 100.0

    fun toJson(m: Memo): JSONObject {
        val o = JSONObject()
        o.put("v", 1)
        o.put("id", m.id)
        o.put("title", m.title)
        o.put("created", m.created)
        o.put("modified", m.modified)
        val pages = JSONArray()
        for (p in m.pages) pages.put(pageJson(p))
        o.put("pages", pages)
        return o
    }

    private fun pageJson(p: Page): JSONObject {
        val o = JSONObject()
        o.put("id", p.id)
        o.put("w", r(p.w))
        o.put("h", r(p.h))
        val strokes = JSONArray()
        for (s in p.state.strokes) {
            val so = JSONObject()
            so.put("c", s.color)
            so.put("w", r(s.width))
            val arr = JSONArray()
            for (v in s.pts) arr.put(r(v))
            so.put("p", arr)
            so.put("t", s.type)
            val prs = s.pr
            if (prs != null) {
                val qa = JSONArray()
                for (v in prs) qa.put(r(v))
                so.put("q", qa)
            }
            strokes.put(so)
        }
        o.put("strokes", strokes)
        val texts = JSONArray()
        for (t in p.state.texts) {
            val to = JSONObject()
            to.put("id", t.id)
            to.put("x", r(t.x))
            to.put("y", r(t.y))
            to.put("w", r(t.width))
            to.put("t", t.text)
            to.put("s", r(t.size))
            to.put("c", t.color)
            texts.put(to)
        }
        o.put("texts", texts)
        val images = JSONArray()
        for (im in p.state.images) {
            val io = JSONObject()
            io.put("id", im.id)
            io.put("k", im.kind)
            io.put("a", im.asset)
            io.put("pg", im.pdfPage)
            io.put("cx", r(im.cx))
            io.put("cy", r(im.cy))
            io.put("w", r(im.w))
            io.put("h", r(im.h))
            io.put("r", r(im.rot))
            io.put("l", im.locked)
            images.put(io)
        }
        o.put("images", images)
        return o
    }

    fun fromJson(o: JSONObject): Memo {
        val pages = ArrayList<Page>()
        val pa = o.optJSONArray("pages") ?: JSONArray()
        for (i in 0 until pa.length()) pages.add(pageFrom(pa.getJSONObject(i)))
        if (pages.isEmpty()) pages.add(Page(newId(), PAGE_W, PAGE_H_DEFAULT, PageState.EMPTY))
        return Memo(
            id = o.getString("id"),
            title = o.optString("title", "Memo"),
            created = o.optLong("created", 0L),
            modified = o.optLong("modified", 0L),
            pages = pages,
        )
    }

    private fun pageFrom(o: JSONObject): Page {
        val strokes = ArrayList<Stroke>()
        val sa = o.optJSONArray("strokes") ?: JSONArray()
        for (i in 0 until sa.length()) {
            val so = sa.getJSONObject(i)
            val arr = so.getJSONArray("p")
            val pts = FloatArray(arr.length())
            for (j in pts.indices) pts[j] = arr.getDouble(j).toFloat()
            val qa = so.optJSONArray("q")
            var prs: FloatArray? = null
            if (qa != null && qa.length() == pts.size / 2) {
                prs = FloatArray(qa.length())
                for (j in prs.indices) prs[j] = qa.getDouble(j).toFloat()
            }
            strokes.add(Stroke(so.getInt("c"), so.getDouble("w").toFloat(), pts, so.optInt("t", PEN_BASIC), prs))
        }
        val texts = ArrayList<TextBox>()
        val ta = o.optJSONArray("texts") ?: JSONArray()
        for (i in 0 until ta.length()) {
            val to = ta.getJSONObject(i)
            texts.add(
                TextBox(
                    id = to.getString("id"),
                    x = to.getDouble("x").toFloat(),
                    y = to.getDouble("y").toFloat(),
                    width = to.getDouble("w").toFloat(),
                    text = to.getString("t"),
                    size = to.getDouble("s").toFloat(),
                    color = to.getInt("c"),
                )
            )
        }
        val images = ArrayList<ImageObj>()
        val ia = o.optJSONArray("images") ?: JSONArray()
        for (i in 0 until ia.length()) {
            val io = ia.getJSONObject(i)
            images.add(
                ImageObj(
                    id = io.getString("id"),
                    kind = io.getString("k"),
                    asset = io.getString("a"),
                    pdfPage = io.optInt("pg", 0),
                    cx = io.getDouble("cx").toFloat(),
                    cy = io.getDouble("cy").toFloat(),
                    w = io.getDouble("w").toFloat(),
                    h = io.getDouble("h").toFloat(),
                    rot = io.optDouble("r", 0.0).toFloat(),
                    locked = io.optBoolean("l", false),
                )
            )
        }
        return Page(
            id = o.getString("id"),
            w = o.getDouble("w").toFloat(),
            h = o.getDouble("h").toFloat(),
            state = PageState(strokes, texts, images),
        )
    }
}
