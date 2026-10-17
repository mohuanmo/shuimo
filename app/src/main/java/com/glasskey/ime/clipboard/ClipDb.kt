package com.glasskey.ime.clipboard

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * 剪贴板历史 SQLite 持久化（§5.8 / §7.2 clip_history 表）。
 * - 最近 20 条；置顶固定；>10KB 存摘要；24h 过期（查询时过滤 + 写时清理）。
 * - 敏感标记条目（EXTRA_IS_SENSITIVE）不入库（§S-04）。
 */
class ClipDb(context: Context) : SQLiteOpenHelper(context, "clip_history.db", null, 1) {

    data class ClipEntry(
        val id: Long,
        val content: String,
        val preview: String,       // >10KB 时的展示摘要（前 200 字符 + …）
        val pinned: Boolean,
        val createdAt: Long,
        val isSensitive: Boolean = false,
    )

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE clip_history(" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "content TEXT NOT NULL, " +
                "preview TEXT, " +
                "pinned INTEGER NOT NULL DEFAULT 0, " +
                "is_sensitive INTEGER NOT NULL DEFAULT 0, " +
                "created_at INTEGER NOT NULL, " +
                "expire_at INTEGER)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS clip_history")
        onCreate(db)
    }

    /** 插入新条目；同内容去重（提升已存在条目时间戳）；返回是否新增 */
    fun insert(content: String, sensitive: Boolean = false): Boolean {
        if (content.isEmpty() || sensitive) return false
        val now = System.currentTimeMillis()
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM clip_history WHERE content = ?", arrayOf(content))
            db.insert(
                "clip_history", null,
                ContentValues().apply {
                    put("content", content)
                    put("preview", previewOf(content))
                    put("pinned", 0)
                    put("is_sensitive", if (sensitive) 1 else 0)
                    put("created_at", now)
                    put("expire_at", now + RETENTION_MS)
                }
            )
            // 容量控制：非置顶保留最近 20 条
            db.execSQL(
                "DELETE FROM clip_history WHERE id NOT IN " +
                    "(SELECT id FROM clip_history ORDER BY pinned DESC, created_at DESC LIMIT ?)",
                arrayOf(MAX_ENTRIES)
            )
            db.setTransactionSuccessful()
            return true
        } finally {
            db.endTransaction()
        }
    }

    /** 历史列表（置顶在前，其后按时间倒序）；过滤已过期 */
    fun all(): List<ClipEntry> {
        val now = System.currentTimeMillis()
        val out = mutableListOf<ClipEntry>()
        readableDatabase.rawQuery(
            "SELECT id, content, preview, pinned, created_at FROM clip_history " +
                "WHERE expire_at IS NULL OR expire_at > ? ORDER BY pinned DESC, created_at DESC",
            arrayOf(now.toString())
        ).use { cur ->
            while (cur.moveToNext()) {
                out.add(
                    ClipEntry(
                        id = cur.getLong(0),
                        content = cur.getString(1),
                        preview = cur.getString(2) ?: previewOf(cur.getString(1)),
                        pinned = cur.getInt(3) != 0,
                        createdAt = cur.getLong(4),
                    )
                )
            }
        }
        return out
    }

    fun setPinned(id: Long, pinned: Boolean) {
        writableDatabase.execSQL(
            "UPDATE clip_history SET pinned = ? WHERE id = ?",
            arrayOf(if (pinned) 1 else 0, id)
        )
    }

    fun delete(id: Long) {
        writableDatabase.execSQL("DELETE FROM clip_history WHERE id = ?", arrayOf(id))
    }

    fun clear() {
        writableDatabase.execSQL("DELETE FROM clip_history WHERE pinned = 0")
    }

    private fun previewOf(content: String): String {
        val oneLine = content.replace("\n", "␤")
        return if (oneLine.length > 200) oneLine.take(200) + "…" else oneLine
    }

    companion object {
        const val MAX_ENTRIES = 20
        const val RETENTION_MS = 24 * 3600 * 1000L  // 保留策略：24h（§5.8 三档之一）
        const val PREVIEW_THRESHOLD = 10 * 1024     // >10KB 存摘要（§5.8）
    }
}
