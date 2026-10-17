package com.personal.englishautotalk.talk

/**
 * 사용자 음성 명령 (requirements.md ③):
 * - 한국어 도움 요청: "Help me in Korean" 또는 한국어로 말하기
 * - 내 말 끝 신호: "I'm all set" 또는 "Over to you" — 이 말이 나올 때까지 대답을 이어서 듣는다.
 */
object VoiceCommands {

    private val endSignal = Regex(
        """[\s,.!?]*(?:(?:i\s*'?\s*m|i\s+am)\s+all\s+set|over\s+to\s+you)[\s,.!?]*$""",
        RegexOption.IGNORE_CASE,
    )

    private val koreanHelp = Regex("""help\s+me\s+(?:in\s+)?korean?\b""", RegexOption.IGNORE_CASE)


    data class Segment(val text: String, val endOfTurn: Boolean)

    /** 인식된 한 구간에서 끝 신호를 떼어 낸다. 끝 신호는 구간 끝에 있을 때만 인정한다. */
    fun parseSegment(raw: String): Segment {
        val trimmed = raw.trim()
        val match = endSignal.find(trimmed) ?: return Segment(trimmed, endOfTurn = false)
        return Segment(trimmed.substring(0, match.range.first).trim(), endOfTurn = true)
    }

    /** 인식기에 미리 알려 줄 명령 문장(EXTRA_BIASING_STRINGS). */
    val BIASING_PHRASES = arrayListOf("I'm all set", "Over to you", "Help me in Korean")

    /**
     * 인식 후보 중 하나를 고른다. 기본은 첫 후보(가장 그럴듯한 것)이고,
     * 첫 후보에는 없지만 다른 후보가 끝 신호로 끝나거나 "Help me in Korean"을 담고 있으면 그 후보를 쓴다.
     * 명령 문장이 비슷한 소리로 잘못 받아 적혀 놓치는 일을 줄이기 위해서다.
     */
    fun pickBest(alternatives: List<String>): String {
        val first = alternatives.firstOrNull().orEmpty()
        if (parseSegment(first).endOfTurn || koreanHelp.containsMatchIn(first)) return first
        return alternatives.drop(1).firstOrNull { parseSegment(it).endOfTurn || koreanHelp.containsMatchIn(it) } ?: first
    }

    /** "Help me in Korean"이라고 했거나, 한국어로 말했으면(한글 인식 또는 언어 감지) 한국어 도움 요청이다. */
    private val latinWord = Regex("[A-Za-z]+(?:'[A-Za-z]+)?")
    private val koreanWord = Regex("[가-힣ㄱ-ㆎ]+")

    /** 한국어로 받아 적힌 단어 수가 영어 단어 수보다 많으면 주로 한국어로 말한 것으로 본다. */
    fun isMostlyKorean(raw: String): Boolean {
        val korean = koreanWord.findAll(raw).count()
        return korean > 0 && korean > latinWord.findAll(raw).count()
    }

    /**
     * 한국어 도움 요청인지: "Help me in Korean"이라고 했거나, 주로 한국어로 말했을 때.
     * 영어 문장에 한국어 단어가 조금 섞인 것은 영어 대화로 본다(한국어와 영어를 섞어 말할 수 있게; 2026-10-17 사용자 요청).
     * 인식기의 한국어 감지(detectedKorean)는 한국어·영어 단어 수가 같을 때만 판단에 쓴다.
     */
    fun isKoreanHelpRequest(raw: String, detectedKorean: Boolean = false): Boolean {
        if (koreanHelp.containsMatchIn(raw)) return true
        val korean = koreanWord.findAll(raw).count()
        val english = latinWord.findAll(raw).count()
        return korean > english || (detectedKorean && korean > 0 && korean == english)
    }
}
