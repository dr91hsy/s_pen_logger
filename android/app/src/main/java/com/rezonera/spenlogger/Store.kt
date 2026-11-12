package com.rezonera.spenlogger

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.TreeMap

data class MemoMeta(val id: String, val title: String, val modified: Long, val pages: Int)

/** File-based persistence: memos (JSON + assets), daily pen/eraser distance log, calibration factor. */
object Store {
    private var inited = false
    lateinit var memosDir: File
        private set
    private lateinit var dailyFile: File
    private lateinit var prefs: SharedPreferences

    /** date (KST, yyyy-MM-dd) -> [penMm, eraserMm]. Append-only in spirit: undo never subtracts. */
    val daily = TreeMap<String, DoubleArray>()

    private val kst: TimeZone = TimeZone.getTimeZone("Asia/Seoul")
    private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = kst }
    private val titleFmt = SimpleDateFormat("MM-dd HH:mm", Locale.KOREA).apply { timeZone = kst }
    val dateTimeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREA).apply { timeZone = kst }

    private var dayCache = ""
    private var dayCacheAt = 0L

    /** Multiplier applied to every measured distance (set by the ruler calibration). */
    var calib: Double
        get() = prefs.getString("calib", null)?.toDoubleOrNull() ?: 1.0
        set(v) {
            prefs.edit().putString("calib", v.toString()).apply()
        }

    // ---- tool settings (persisted) -----------------------------------------------------------

    var penType: Int
        get() = prefs.getInt("penType", PEN_BASIC)
        set(v) {
            prefs.edit().putInt("penType", v).apply()
        }

    var penColor: Int
        get() = prefs.getInt("penColor", 0xFF111111.toInt())
        set(v) {
            prefs.edit().putInt("penColor", v).apply()
        }

    /** Pen width in page units. */
    var penWidth: Float
        get() = prefs.getFloat("penWidth", 2.5f)
        set(v) {
            prefs.edit().putFloat("penWidth", v).apply()
        }

    /** Eraser radius in dp (screen size, independent of zoom). */
    var eraserDp: Float
        get() = prefs.getFloat("eraserDp", 14f)
        set(v) {
            prefs.edit().putFloat("eraserDp", v).apply()
        }

    fun init(ctx: Context) {
        if (inited) return
        val app = ctx.applicationContext
        prefs = app.getSharedPreferences("spenlogger", Context.MODE_PRIVATE)
        memosDir = File(app.filesDir, "memos")
        memosDir.mkdirs()
        dailyFile = File(app.filesDir, "daily.json")
        loadDaily()
        inited = true
    }

    // ---- daily counters --------------------------------------------------------------------

    fun today(): String {
        val now = System.currentTimeMillis()
        if (dayCache.isEmpty() || now - dayCacheAt > 1000) {
            dayCache = dayFmt.format(Date(now))
            dayCacheAt = now
        }
        return dayCache
    }

    fun addDistance(penMm: Double, eraMm: Double) {
        val a = daily.getOrPut(today()) { DoubleArray(2) }
        a[0] += penMm
        a[1] += eraMm
    }

    fun todayTotals(): DoubleArray = daily[today()]?.copyOf() ?: DoubleArray(2)

    fun allTotals(): DoubleArray {
        val t = DoubleArray(2)
        for (a in daily.values) {
            t[0] += a[0]
            t[1] += a[1]
        }
        return t
    }

    private fun loadDaily() {
        daily.clear()
        try {
            if (dailyFile.exists()) {
                val o = JSONObject(dailyFile.readText())
                for (k in o.keys()) {
                    val a = o.getJSONArray(k)
                    daily[k] = doubleArrayOf(a.getDouble(0), a.getDouble(1))
                }
            }
        } catch (_: Exception) {
        }
    }

    fun saveDaily() {
        val o = JSONObject()
        for ((k, v) in daily) {
            val a = JSONArray()
            a.put(v[0])
            a.put(v[1])
            o.put(k, a)
        }
        writeAtomic(dailyFile, o.toString())
    }

    // ---- memos -----------------------------------------------------------------------------

    private fun writeAtomic(f: File, text: String) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) {
            f.writeText(text)
            tmp.delete()
        }
    }

    fun memoDir(id: String) = File(memosDir, id)

    fun assetFile(memoId: String, name: String) = File(File(memoDir(memoId), "assets"), name)

    fun newMemo(): Memo {
        val now = System.currentTimeMillis()
        val m = Memo(
            id = newId(),
            title = "메모 " + titleFmt.format(Date(now)),
            created = now,
            modified = now,
            pages = mutableListOf(Page(newId(), PAGE_W, PAGE_H_DEFAULT, PageState.EMPTY)),
        )
        saveMemo(m)
        return m
    }

    fun saveMemo(m: Memo) {
        val dir = memoDir(m.id)
        dir.mkdirs()
        writeAtomic(File(dir, "memo.json"), MemoCodec.toJson(m).toString())
        val meta = JSONObject()
        meta.put("id", m.id)
        meta.put("title", m.title)
        meta.put("modified", m.modified)
        meta.put("pages", m.pages.size)
        writeAtomic(File(dir, "meta.json"), meta.toString())
    }

    fun loadMemo(id: String): Memo? {
        return try {
            val f = File(memoDir(id), "memo.json")
            if (!f.exists()) null else MemoCodec.fromJson(JSONObject(f.readText()))
        } catch (_: Exception) {
            null
        }
    }

    fun listMemos(): List<MemoMeta> {
        val out = ArrayList<MemoMeta>()
        val dirs = memosDir.listFiles() ?: return out
        for (d in dirs) {
            try {
                val f = File(d, "meta.json")
                if (!f.exists()) continue
                val o = JSONObject(f.readText())
                out.add(MemoMeta(o.getString("id"), o.optString("title", "Memo"), o.optLong("modified", 0L), o.optInt("pages", 1)))
            } catch (_: Exception) {
            }
        }
        out.sortByDescending { it.modified }
        return out
    }

    fun deleteMemo(id: String) {
        memoDir(id).deleteRecursively()
    }

    fun renameMemo(id: String, title: String) {
        val m = loadMemo(id) ?: return
        m.title = title
        saveMemo(m)
    }
}
