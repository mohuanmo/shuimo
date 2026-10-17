package com.glasskey.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.glasskey.ime.predict.NextWordStats
import com.glasskey.ime.ui.Theme

/**
 * 学习数据页（v1.5.8，Material 3 Expressive）：
 *  - 联系人实体卡片：姓名大字 + 电话/邮箱 chips（点击复制，长按删除）；
 *  - 下文搭配历史：用户输入学习到的 (上一词 → 下一词) 热度榜；
 *  - 语音学习记忆：ASR 纠错映射；
 *  全部数据仅存本机，可分区清空。
 */
class LearnDataActivity : Activity() {

    private val d by lazy { resources.displayMetrics.density }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashGuard.install(this)
        buildUi()
    }

    private fun buildUi() {
        val dark = (resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val bg = if (dark) 0xFF10161D.toInt() else 0xFFF5F7FB.toInt()
        val cardBg = if (dark) 0xFF1A222C.toInt() else 0xFFFFFFFF.toInt()
        val titleC = if (dark) 0xFFEDF2F8.toInt() else 0xFF16202B.toInt()
        val subC = if (dark) 0xFF9AA8B6.toInt() else 0xFF7A8694.toInt()
        val accent = Theme.accentLight(GkPrefs.accentIndex(this))

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val centerWrap = LinearLayout(this).apply { gravity = Gravity.CENTER_HORIZONTAL }
        centerWrap.addView(root, LinearLayout.LayoutParams(
            minOf(resources.displayMetrics.widthPixels, (d * 640).toInt()),
            LinearLayout.LayoutParams.MATCH_PARENT))
        val scroll = android.widget.ScrollView(this).apply { addView(centerWrap) }
        setContentView(scroll)
        scroll.background = GradientDrawable().apply { setColor(bg) }

        root.addView(text("学习数据", 32f, titleC, bold = true))
        root.addView(text("输入法从你的使用中学到的一切（仅存本机）", 13f, subC).apply {
            setPadding(0, (d * 2).toInt(), 0, (d * 14).toInt())
        })

        // —— 联系人实体卡片 ——
        val contacts = NextWordStats.contacts(this)
        if (contacts.isNotEmpty()) {
            root.addView(sectionTitle("联系人（${contacts.size}）", titleC))
            for (c in contacts) {
                val card = card(cardBg)
                card.addView(text(c.name, 22f, titleC, bold = true))
                val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                c.phone?.let { chips.addView(infoChip("📞 $it", accent, cardBg) {
                    copy(it); toast("已复制电话号码")
                }) }
                c.email?.let { chips.addView(infoChip("✉️ $it", accent, cardBg) {
                    copy(it); toast("已复制邮箱")
                }) }
                if (c.phone == null && c.email == null) {
                    chips.addView(text("（暂无联系方式）", 12f, subC))
                }
                card.addView(chips, matchWrap().apply { topMargin = (d * 8).toInt() })
                card.addView(text("学习 ${c.heat} 次 · 长按删除", 11f, subC).apply {
                    setPadding(0, (d * 6).toInt(), 0, 0)
                })
                card.setOnLongClickListener {
                    NextWordStats.deleteContact(this@LearnDataActivity, c.name)
                    toast("已删除 ${c.name}")
                    recreate()
                    true
                }
                root.addView(card, matchWrap().apply { topMargin = (d * 8).toInt() })
            }
            root.addView(pillButton("清空全部联系人", 0xFFD24D57.toInt(), cardBg).apply {
                setOnClickListener { NextWordStats.clearContacts(this@LearnDataActivity); toast("已清空"); recreate() }
            }, matchWrap().apply { topMargin = (d * 10).toInt() })
        } else {
            val card = card(cardBg)
            card.addView(text("还没有联系人卡片", 16f, titleC, bold = true))
            card.addView(text("当你在任何输入框输入包含电话号码、邮箱或姓名（如\"我叫张三 13800138000\"）的文本时，输入法会自动学习并在这里以卡片形式展示，下次输入姓名即可预测出对应电话/邮箱。", 13f, subC).apply {
                setPadding(0, (d * 6).toInt(), 0, 0)
            })
            root.addView(sectionTitle("联系人", titleC)); root.addView(card, matchWrap())
        }

        // —— 下文搭配历史 ——
        val bigrams = NextWordStats.bigrams(this)
        root.addView(sectionTitle("下文搭配历史（${bigrams.size}）", titleC))
        val bCard = card(cardBg)
        if (bigrams.isEmpty()) {
            bCard.addView(text("暂无记录：正常打字即可自动学习常用搭配，输入左词时右词会进入预测候选。", 13f, subC))
        } else {
            for ((p, c, h) in bigrams.take(30)) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, (d * 4).toInt(), 0, (d * 4).toInt())
                }
                row.addView(text(p, 15f, titleC, bold = true))
                row.addView(text("  →  ", 14f, subC))
                row.addView(text(c, 15f, accent, bold = true))
                row.addView(text("   ×$h", 12f, subC))
                bCard.addView(row, matchWrap())
            }
            if (bigrams.size > 30) bCard.addView(text("… 其余 ${bigrams.size - 30} 条按热度优先参与预测", 12f, subC))
            bCard.addView(pillButton("清空搭配历史", 0xFFD24D57.toInt(), cardBg).apply {
                setOnClickListener { NextWordStats.clearBigrams(this@LearnDataActivity); toast("已清空"); recreate() }
            }, matchWrap().apply { topMargin = (d * 8).toInt() })
        }
        root.addView(bCard, matchWrap().apply { topMargin = (d * 6).toInt() })

        // —— 语音学习记忆 ——
        val entries = GkPrefs.voiceLearnEntries(this)
        root.addView(sectionTitle("语音学习记忆（${entries.size}）", titleC))
        val vCard = card(cardBg)
        if (entries.isEmpty()) {
            vCard.addView(text("暂无记录：语音上屏后用键盘改正错词，会自动记成纠错映射。", 13f, subC))
        } else {
            for (e in entries.take(20)) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, (d * 4).toInt(), 0, (d * 4).toInt())
                }
                row.addView(text(e.from, 15f, subC))
                row.addView(text("  →  ", 14f, subC))
                row.addView(text(e.to, 15f, titleC, bold = true))
                row.addView(text("   ×${e.heat}", 12f, subC))
                vCard.addView(row, matchWrap())
            }
            vCard.addView(pillButton("清空语音学习", 0xFFD24D57.toInt(), cardBg).apply {
                setOnClickListener { GkPrefs.clearVoiceLearn(this@LearnDataActivity); toast("已清空"); recreate() }
            }, matchWrap().apply { topMargin = (d * 8).toInt() })
        }
        root.addView(vCard, matchWrap().apply { topMargin = (d * 6).toInt() })

        root.addView(text("隐私：以上数据全部保存在本机应用私有目录，不上传、不共享。", 12f, subC)
            .apply { setPadding(0, (d * 14).toInt(), 0, (d * 20).toInt()) })
    }

    // ── M3E 组件（与 MainActivity 同风格）─────────────────────

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false): TextView =
        TextView(this).apply {
            this.text = s; textSize = size; setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun sectionTitle(s: String, color: Int): TextView =
        text(s, 17f, color, bold = true).apply {
            setPadding((d * 4).toInt(), (d * 16).toInt(), 0, (d * 6).toInt())
        }

    private fun card(bg: Int): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding((d * 16).toInt(), (d * 16).toInt(), (d * 16).toInt(), (d * 16).toInt())
        background = GradientDrawable().apply { cornerRadius = d * 24f; setColor(bg) }
    }

    private fun infoChip(label: String, accent: Int, cardBg: Int, onClick: () -> Unit): TextView =
        TextView(this).apply {
            this.text = label; textSize = 14f; setTextColor(accent)
            gravity = Gravity.CENTER
            setPadding((d * 12).toInt(), (d * 6).toInt(), (d * 12).toInt(), (d * 6).toInt())
            background = GradientDrawable().apply {
                cornerRadius = d * 999f
                setColor(accent and 0x1AFFFFFF or 0x14000000.toInt())
                setStroke((d * 1.2f).toInt(), accent)
            }
            setOnClickListener { onClick() }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.marginEnd = (d * 8).toInt()
            layoutParams = lp
        }

    private fun pillButton(label: String, accent: Int, cardBg: Int): TextView =
        TextView(this).apply {
            this.text = label; textSize = 15f; gravity = Gravity.CENTER
            setTextColor(accent)
            setPadding((d * 16).toInt(), (d * 10).toInt(), (d * 16).toInt(), (d * 10).toInt())
            background = GradientDrawable().apply {
                cornerRadius = d * 999f
                setColor(accent and 0x1AFFFFFF or 0x14000000.toInt())
                setStroke((d * 1.4f).toInt(), accent)
            }
        }

    private fun copy(s: String) {
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("gk", s))
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun matchWrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
    )
}
