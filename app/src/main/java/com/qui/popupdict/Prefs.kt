package com.qui.popupdict

import android.content.Context

data class PromptItem(val name: String, val text: String)

object Prefs {

    // Prompt khi CHẠM 1 TỪ. {text} = từ được chạm, {context} = câu chứa từ đó.
    const val DEFAULT_WORD_PROMPTS = """Dịch từ
Bạn là từ điển Anh-Việt. Giải thích từ "{text}" trong câu: "{context}"
Trả lời gọn bằng tiếng Việt, đúng 4 dòng:
1) /phiên âm IPA/ · loại từ
2) Nghĩa của từ ĐÚNG trong câu này (ngắn gọn)
3) Các nghĩa phổ biến khác (tối đa 3, nếu có)
4) Một câu ví dụ tiếng Anh kèm bản dịch
Nếu từ nằm trong cụm động từ hoặc thành ngữ thì nêu cả cụm.
===
Nghĩa ngắn
Cho nghĩa tiếng Việt ngắn nhất của từ "{text}" trong câu: "{context}". Chỉ trả về nghĩa, tối đa 8 từ.
===
Giải thích tiếng Anh
Explain the word "{text}" as used in this sentence: "{context}". Give: IPA, part of speech, a simple English definition (use easy words only), two synonyms, then the Vietnamese meaning in one short line."""

    // Prompt khi GIỮ 1 TỪ (cả câu) hoặc KÉO qua nhiều từ. {text} = đoạn được chọn.
    const val DEFAULT_SENT_PROMPTS = """Dịch câu
Dịch đoạn tiếng Anh sau sang tiếng Việt tự nhiên, đúng ý. Chỉ trả về bản dịch.

{text}
===
Dịch + phân tích
Dịch đoạn tiếng Anh sau sang tiếng Việt tự nhiên. Sau đó giải thích ngắn gọn cấu trúc ngữ pháp đáng chú ý và liệt kê 3-5 từ/cụm khó kèm nghĩa.

{text}
===
Dịch từng cụm
Chia đoạn tiếng Anh sau thành các cụm nghĩa ngắn. Mỗi cụm một dòng theo dạng: cụm tiếng Anh = nghĩa tiếng Việt. Cuối cùng ghi bản dịch hoàn chỉnh.

{text}"""

    private fun sp(c: Context) = c.getSharedPreferences("cfg", Context.MODE_PRIVATE)

    // Dùng tên khóa MỚI (groq...) để không dính API key / model Gemini cũ đã lưu trong máy
    const val DEFAULT_MODEL = "openai/gpt-oss-20b"

    fun key(c: Context): String = sp(c).getString("groqKey", "") ?: ""
    fun model(c: Context): String = sp(c).getString("groqModel", DEFAULT_MODEL) ?: DEFAULT_MODEL
    fun think(c: Context): String = sp(c).getString("groqThink", "") ?: ""

    fun rawWordPrompts(c: Context): String = sp(c).getString("wordPrompts", DEFAULT_WORD_PROMPTS) ?: DEFAULT_WORD_PROMPTS
    fun rawSentPrompts(c: Context): String = sp(c).getString("sentPrompts", DEFAULT_SENT_PROMPTS) ?: DEFAULT_SENT_PROMPTS

    fun wordPrompts(c: Context): List<PromptItem> = parse(rawWordPrompts(c), DEFAULT_WORD_PROMPTS)
    fun sentPrompts(c: Context): List<PromptItem> = parse(rawSentPrompts(c), DEFAULT_SENT_PROMPTS)

    fun wordIdx(c: Context): Int = sp(c).getInt("wordIdx", 0)
    fun sentIdx(c: Context): Int = sp(c).getInt("sentIdx", 0)
    fun setWordIdx(c: Context, i: Int) { sp(c).edit().putInt("wordIdx", i).apply() }
    fun setSentIdx(c: Context, i: Int) { sp(c).edit().putInt("sentIdx", i).apply() }

    // Chỉ lưu riêng model (dùng khi chọn model từ danh sách)
    fun setModel(c: Context, model: String) {
        sp(c).edit().putString("groqModel", model).apply()
    }

    fun save(c: Context, key: String, model: String, think: String, wordPrompts: String, sentPrompts: String) {
        sp(c).edit()
            .putString("groqKey", key)
            .putString("groqModel", model)
            .putString("groqThink", think)
            .putString("wordPrompts", wordPrompts)
            .putString("sentPrompts", sentPrompts)
            .apply()
    }

    // Mỗi prompt cách nhau bằng 1 dòng chỉ có ===. Dòng đầu của khối = tên, phần còn lại = nội dung.
    fun parse(raw: String, fallback: String): List<PromptItem> {
        val list = raw.split(Regex("(?m)^\\s*===\\s*$")).mapNotNull { block ->
            val lines = block.trim().lines()
            if (lines.isEmpty() || lines[0].isBlank()) null
            else PromptItem(lines[0].trim(), lines.drop(1).joinToString("\n").trim())
        }
        return if (list.isEmpty()) parse(fallback, fallback) else list
    }
}
