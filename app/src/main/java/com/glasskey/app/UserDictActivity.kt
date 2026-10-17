package com.glasskey.app

import android.app.Activity
import android.app.ListActivity
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.glasskey.R
import com.glasskey.ime.data.UserDictDb

/**
 * 用户词管理（§5.2）：列表展示（词 / 拼音 / 频次），长按删除，清空。
 * 删除仅落 SQLite；引擎侧权重在下次服务创建时装载生效。
 */
class UserDictActivity : ListActivity() {

    private lateinit var db: UserDictDb

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = UserDictDb(this)
        // v1.5.8 M3E 化：统一水墨配色背景 + 卡片内边距
        val dark = (resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val bg = if (dark) 0xFF10161D.toInt() else 0xFFF5F7FB.toInt()
        val dp = resources.displayMetrics.density
        window.decorView.background = android.graphics.drawable.GradientDrawable().apply { setColor(bg) }
        listView.setPadding((dp * 18).toInt(), (dp * 16).toInt(), (dp * 18).toInt(), (dp * 24).toInt())
        listView.clipToPadding = false
        listView.divider = null
        if (db.all().isEmpty()) {
            listAdapter = ArrayAdapter(
                this, android.R.layout.simple_list_item_1, listOf(getString(R.string.user_dict_empty))
            )
        } else {
            loadList()
        }
        listView.setOnItemLongClickListener { _, _, pos, _ ->
            val e = db.all().getOrNull(pos) ?: return@setOnItemLongClickListener true
            db.delete(e.word, e.pinyin)
            Toast.makeText(this, getString(R.string.user_dict_deleted, e.word), Toast.LENGTH_SHORT).show()
            loadList()
            true
        }
    }

    private fun loadList() {
        val entries = db.all()
        listAdapter = object : ArrayAdapter<UserDictDb.Entry>(
            this, android.R.layout.simple_list_item_2, entries
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = convertView ?: layoutInflater.inflate(
                    android.R.layout.simple_list_item_2, parent, false
                )
                val e = entries[position]
                v.findViewById<TextView>(android.R.id.text1).text = e.word
                v.findViewById<TextView>(android.R.id.text2).text =
                    "${e.pinyin} · 频次 ${e.freq}"
                // M3E：词 = 加重主色，行卡片圆角
                v.findViewById<TextView>(android.R.id.text1).setTextColor(
                    com.glasskey.ime.ui.Theme.accentLight(GkPrefs.accentIndex(context)))
                v.background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = resources.displayMetrics.density * 16f
                    setColor(if (position % 2 == 0) 0x00000000 else 0x0D000000)
                }
                return v
            }
        }
    }

    override fun onDestroy() {
        db.close()
        super.onDestroy()
    }
}
