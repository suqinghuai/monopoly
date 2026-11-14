package com.monopoly.lan

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 安卓版存档：SQLite（monopoly.db），表结构和桌面版一致，方便互相读档思路对齐。
 * 存档文件落在应用私有目录 files/ 下，卸载即清。
 */
object Store {
    const val SAVE_SLOTS = 6

    @Volatile
    private var helper: Helper? = null

    private fun db(): SQLiteDatabase? = helper?.writableDatabase

    fun init(context: Context) {
        if (helper == null) helper = Helper(context.applicationContext)
    }

    private fun now(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    fun newGame(title: String, summary: String, snapshot: String): Long {
        val db = db() ?: return 0L
        val values = ContentValues().apply {
            put("title", title)
            put("created_at", now())
            put("updated_at", now())
            put("status", "playing")
            put("phase", "playing")
            put("summary", summary)
            put("snapshot", snapshot)
        }
        return db.insert("games", null, values)
    }

    fun saveGame(id: Long, phase: String, status: String, summary: String, snapshot: String) {
        if (id <= 0L) return
        val db = db() ?: return
        val values = ContentValues().apply {
            put("updated_at", now())
            put("status", status)
            put("phase", phase)
            put("summary", summary)
            put("snapshot", snapshot)
        }
        db.update("games", values, "id = ?", arrayOf(id.toString()))
    }

    fun getGame(id: Long): JSONObject? {
        val db = db() ?: return null
        db.rawQuery("SELECT * FROM games WHERE id = ?", arrayOf(id.toString())).use { c ->
            if (!c.moveToFirst()) return null
            val o = JSONObject()
            for (i in 0 until c.columnCount) {
                val name = c.getColumnName(i)
                val v = c.getString(i)
                o.put(name, v ?: JSONObject.NULL)
            }
            return o
        }
    }

    /** 存档列表（列表页用）：summary / updated_at 直接给前端。 */
    fun listGames(): JSONArray {
        val out = JSONArray()
        val db = db() ?: return out
        db.rawQuery(
            "SELECT id, title, created_at, updated_at, status, phase, summary" +
                " FROM games ORDER BY updated_at DESC, id DESC",
            null
        ).use { c ->
            while (c.moveToNext()) {
                val o = JSONObject()
                o.put("id", c.getLong(0))
                o.put("title", c.getString(1))
                o.put("created_at", c.getString(2))
                o.put("updated_at", c.getString(3))
                o.put("status", c.getString(4))
                o.put("phase", if (c.isNull(5)) JSONObject.NULL else c.getString(5))
                val sum = c.getString(6)
                o.put(
                    "summary",
                    try {
                        if (sum.isNullOrBlank()) JSONObject() else JSONObject(sum)
                    } catch (e: Exception) {
                        JSONObject()
                    }
                )
                out.put(o)
            }
        }
        return out
    }

    fun currentId(): Any {
        val db = db() ?: return JSONObject.NULL
        db.rawQuery("SELECT id FROM games ORDER BY id DESC LIMIT 1", null).use { c ->
            return if (c.moveToFirst()) c.getLong(0) else JSONObject.NULL
        }
    }

    /** 只留最近 keep 局，返回被删掉的那些（给日志用）。 */
    fun pruneGames(keep: Int = SAVE_SLOTS): List<JSONObject> {
        val db = db() ?: return emptyList()
        val ids = ArrayList<Long>()
        db.rawQuery("SELECT id FROM games ORDER BY id DESC", null).use { c ->
            while (c.moveToNext()) ids.add(c.getLong(0))
        }
        if (ids.size <= keep) return emptyList()
        val victims = ids.drop(keep)
        val out = ArrayList<JSONObject>()
        for (v in victims) {
            val title = getGame(v)?.optString("title") ?: "#$v"
            db.delete("games", "id = ?", arrayOf(v.toString()))
            out.add(JSONObject().put("id", v).put("title", title))
        }
        return out
    }

    /** AI 调用失败时留一份日志，方便在电脑上排查（手机上就是应用私有目录）。 */
    object Log {
        fun append(text: String) {
            try {
                val db = db() ?: return
                val values = ContentValues().apply {
                    put("created_at", now())
                    put("text", text.take(4000))
                }
                db.insert("ai_fail_log", null, values)
            } catch (e: Exception) {
                // 记日志本身失败就算了
            }
        }
    }

    private class Helper(context: Context) :
        SQLiteOpenHelper(context, "monopoly.db", null, 1) {

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS games (
                    id         INTEGER PRIMARY KEY AUTOINCREMENT,
                    title      TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    status     TEXT NOT NULL,
                    phase      TEXT,
                    summary    TEXT,
                    snapshot   TEXT NOT NULL
                );
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS ai_fail_log (
                    id         INTEGER PRIMARY KEY AUTOINCREMENT,
                    created_at TEXT NOT NULL,
                    text       TEXT
                );
                """.trimIndent()
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // 目前只有 v1，升级时按需迁移
        }
    }
}
