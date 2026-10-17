package com.glasskey.ime.data

import org.junit.Assert.assertEquals
import org.junit.Test

class UserDictBlobTest {

    private data class Rec(val word: String, val pinyin: String, val freq: Int)

    /** 与 C++ UserModel::Load 对称的解析（word\0pinyin\0freq u32 LE） */
    private fun parse(blob: ByteArray): List<Rec> {
        val out = ArrayList<Rec>()
        var p = 0
        fun nul(from: Int): Int {
            var i = from
            while (blob[i] != 0.toByte()) ++i
            return i
        }
        while (p < blob.size) {
            val wEnd = nul(p)
            val word = String(blob, p, wEnd - p, Charsets.UTF_8)
            val yEnd = nul(wEnd + 1)
            val py = String(blob, wEnd + 1, yEnd - (wEnd + 1), Charsets.UTF_8)
            val f = (blob[yEnd + 1].toInt() and 0xFF) or
                ((blob[yEnd + 2].toInt() and 0xFF) shl 8) or
                ((blob[yEnd + 3].toInt() and 0xFF) shl 16) or
                ((blob[yEnd + 4].toInt() and 0xFF) shl 24)
            out.add(Rec(word, py, f))
            p = yEnd + 5
        }
        return out
    }

    @Test
    fun `blob layout matches native parser`() {
        val blob = UserDictDb.toBlob(
            listOf(
                UserDictDb.Entry("县", "xian", 64),
                UserDictDb.Entry("西安", "xi an", 10000),
            )
        )
        // 县=3B+\0+xian=4B+\0+freq=4B → 13；西安=6B+\0+"xi an"=5B+\0+4B → 17
        assertEquals(13 + 17, blob.size)
        val recs = parse(blob)
        assertEquals(listOf(Rec("县", "xian", 64), Rec("西安", "xi an", 10000)), recs)
    }

    @Test
    fun `roundtrip preserves order and freq`() {
        val src = (1..50).map { UserDictDb.Entry("词$it", "ci $it", it * 137) }
        val recs = parse(UserDictDb.toBlob(src))
        assertEquals(src.size, recs.size)
        src.zip(recs).forEach { (a, b) ->
            assertEquals(a.word, b.word)
            assertEquals(a.pinyin, b.pinyin)
            assertEquals(a.freq, b.freq)
        }
    }

    @Test
    fun `empty list yields empty blob`() {
        assertEquals(0, UserDictDb.toBlob(emptyList()).size)
    }
}
