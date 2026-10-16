package com.personal.englishautotalk.talk

/**
 * 소리 내어 읽을 문장을 한국어 부분과 영어 부분으로 나눈다.
 * 한국어 도움 응답("…라고 말해 보세요. I like to travel.")을 한 목소리로 읽으면
 * 영어 예문을 한국어 음성이 한국식 발음으로 읽어 어색하다. 나눈 조각마다 맞는 언어 음성으로 읽는다.
 */
object SpeechText {

    data class Chunk(val text: String, val korean: Boolean)

    /**
     * 한글이 나오면 한국어 조각, 영어 알파벳이 나오면 영어 조각으로 바꾼다.
     * 숫자·공백·문장부호는 앞 조각에 붙인다. 따옴표만 남는 조각처럼 읽을 글자가 없는 조각은 버린다.
     */
    fun split(text: String): List<Chunk> {
        val chunks = mutableListOf<Chunk>()
        val current = StringBuilder()
        var korean: Boolean? = null
        for (c in text) {
            val kind = when {
                isHangul(c) -> true
                c in 'A'..'Z' || c in 'a'..'z' -> false
                else -> null
            }
            if (kind != null && korean != null && kind != korean) {
                chunks.add(Chunk(current.toString(), korean))
                current.clear()
            }
            if (kind != null) korean = kind
            current.append(c)
        }
        if (current.isNotEmpty()) chunks.add(Chunk(current.toString(), korean ?: false))
        return merge(chunks.map { it.copy(text = it.text.trim { c -> c.isWhitespace() || c in QUOTES }) }.filter { it.text.any(Char::isLetterOrDigit) })
    }

    /** 조각 앞뒤에 남는 따옴표는 읽을 필요가 없어 떼어 낸다("Let's"처럼 가운데 있는 것은 그대로 둔다). */
    private const val QUOTES = "\"'“”‘’"

    /** 나눈 뒤 같은 언어 조각이 이어지면(가운데 조각을 버린 경우) 하나로 합친다. */
    private fun merge(chunks: List<Chunk>): List<Chunk> {
        val out = mutableListOf<Chunk>()
        for (chunk in chunks) {
            val last = out.lastOrNull()
            if (last != null && last.korean == chunk.korean) {
                out[out.lastIndex] = last.copy(text = last.text + " " + chunk.text)
            } else {
                out.add(chunk)
            }
        }
        return out
    }

    private fun isHangul(c: Char) = c in '가'..'힣' || c in 'ㄱ'..'ㆎ'
}
