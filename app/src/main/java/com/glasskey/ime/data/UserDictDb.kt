package com.glasskey.ime.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * 用户词 SQLite 持久化（§4.5 单向持久化）：
 * - 启动装载：SELECT 全表 → 序列化 blob → ime_load_user_words
 * - 提交回写：引擎 Learn 后由服务层 upsert
 * blob 格式：word'\0'pinyin'\0'freq(u32 LE) 依序拼接
 */
class UserDictDb(context: Context) : SQLiteOpenHelper(context, "user_dict.db", null, 1) {

    data class Entry(val word: String, val pinyin: String, val freq: Int)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE user_word(" +
                "word TEXT NOT NULL, pinyin TEXT NOT NULL, freq INTEGER NOT NULL DEFAULT 1, " +
                "updated_at INTEGER NOT NULL, PRIMARY KEY(word, pinyin))"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS user_word")
        onCreate(db)
    }

    fun upsert(word: String, pinyin: String, addFreq: Int = 1) {
        if (word.isEmpty()) return
        val now = System.currentTimeMillis()
        writableDatabase.beginTransaction()
        try {
            val cur = readableDatabase.rawQuery(
                "SELECT freq FROM user_word WHERE word=? AND pinyin=?", arrayOf(word, pinyin)
            )
            val old = if (cur.moveToFirst()) cur.getInt(0) else 0
            cur.close()
            val newFreq = old + addFreq
            writableDatabase.insertWithOnConflict(
                "user_word", null,
                ContentValues().apply {
                    put("word", word)
                    put("pinyin", pinyin)
                    put("freq", newFreq)
                    put("updated_at", now)
                },
                SQLiteDatabase.CONFLICT_REPLACE
            )
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    fun all(): List<Entry> {
        val out = ArrayList<Entry>()
        readableDatabase.rawQuery(
            "SELECT word, pinyin, freq FROM user_word ORDER BY freq DESC", null
        ).use { c ->
            while (c.moveToNext()) out.add(Entry(c.getString(0), c.getString(1), c.getInt(2)))
        }
        return out
    }

    fun delete(word: String, pinyin: String) {
        writableDatabase.delete("user_word", "word=? AND pinyin=?", arrayOf(word, pinyin))
    }

    fun clear() {
        writableDatabase.delete("user_word", null, null)
    }

    companion object {
        /** 全表 → blob（word\0pinyin\0freq u32 LE） */
        fun toBlob(entries: List<Entry>): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            for (e in entries) {
                val f = e.freq.coerceIn(0, Int.MAX_VALUE)
                out.write(e.word.toByteArray(Charsets.UTF_8))
                out.write(0)
                out.write(e.pinyin.toByteArray(Charsets.UTF_8))
                out.write(0)
                out.write(byteArrayOf(
                    (f and 0xFF).toByte(), (f shr 8 and 0xFF).toByte(),
                    (f shr 16 and 0xFF).toByte(), (f shr 24 and 0xFF).toByte()
                ))
            }
            return out.toByteArray()
        }
    }
}
