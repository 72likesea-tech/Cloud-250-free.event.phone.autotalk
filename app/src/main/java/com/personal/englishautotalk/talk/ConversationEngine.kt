package com.personal.englishautotalk.talk

/**
 * 한 회차의 대화 상태: 메시지 이력, 고른 주제, 턴 수, 경과 시간, 완료·마무리 판단.
 * requirements.md ④: 5분 이상 또는 8왕복 이상이면 완료 → 마무리, 10분이면 무조건 마무리.
 */
class ConversationEngine(
    val debateQuestion: String,
    private val clock: () -> Long,
) {
    enum class Phase(val apiName: String) { CHOOSE_TOPIC("choose_topic"), TALK("talk"), WRAP_UP("wrap_up") }

    data class Message(val fromLearner: Boolean, val text: String)

    private val startedAt = clock()
    val messages = mutableListOf<Message>()
    var chosenTopic: String? = null
        private set
    var learnerTurns = 0
        private set

    /** 회차 시작부터 흐른 시간. */
    val elapsedMs get() = clock() - startedAt

    private var koreanModeMs = 0L
    private var koreanModeSince: Long? = null

    /**
     * '한국어로 말하기'를 켜고 끈 시점을 알려 준다. 켜 둔 동안은 영어 대화 시간으로 치지 않는다.
     * 한국어로 질문하고 답을 듣느라 5분이 지나, 영어로 돌아온 첫 차례에 바로 마무리해 버리는 일을 막는다(2026-10-26 사용자 보고).
     */
    fun setKoreanMode(on: Boolean) {
        val now = clock()
        if (on) {
            if (koreanModeSince == null) koreanModeSince = now
        } else {
            koreanModeSince?.let { koreanModeMs += now - it }
            koreanModeSince = null
        }
    }

    /** 영어 대화에 쓴 시간: 한국어 모드였던 시간을 뺀다. 완료(5분)·마무리(10분) 판단에 쓴다. */
    val englishMs get() = elapsedMs - koreanModeMs - (koreanModeSince?.let { clock() - it } ?: 0L)

    fun addApp(text: String) {
        messages += Message(fromLearner = false, text = text)
    }

    /** 한국어 도움 요청은 턴 수에 넣지 않고, 모델이 알아보도록 [HELP_KO] 표식을 붙인다. */
    fun addLearner(text: String, koreanHelp: Boolean = false) {
        if (koreanHelp) {
            messages += Message(fromLearner = true, text = "$HELP_MARKER $text".trim())
            return
        }
        learnerTurns++
        messages += Message(fromLearner = true, text = text)
        if (chosenTopic == null) chosenTopic = detectTopic(text, debateQuestion)
    }

    fun isComplete(): Boolean = englishMs >= COMPLETE_MS || learnerTurns >= COMPLETE_TURNS

    /**
     * 방금 들어온 사용자 턴에 대한 응답의 단계.
     * 한국어 질문(koreanHelp)에는 마무리하지 않고 답한다. 질문하다 대화가 끝나 버리지 않게, 마무리는 다음 영어 차례에 한다.
     */
    fun phaseForReply(koreanHelp: Boolean = false): Phase = when {
        (isComplete() || englishMs >= MAX_MS) && !koreanHelp -> Phase.WRAP_UP
        isComplete() -> Phase.TALK
        chosenTopic == null && learnerTurns < 3 -> Phase.CHOOSE_TOPIC
        learnerTurns <= 1 -> Phase.CHOOSE_TOPIC
        else -> Phase.TALK
    }

    fun systemPrompt(template: String, phase: Phase): String = template
        .replace("{{debate_question}}", debateQuestion)
        .replace("{{chosen_topic}}", chosenTopic ?: "not chosen yet")
        .replace("{{elapsed_min}}", (englishMs / 60_000).toString())
        .replace("{{turn_count}}", learnerTurns.toString())
        .replace("{{phase}}", phase.apiName)

    /** API로 보낼 최근 메시지. 첫 메시지가 사용자 역할이 되도록 시작 표식을 앞에 둔다. */
    fun recentMessages(max: Int = MAX_MESSAGES): List<Message> =
        listOf(Message(fromLearner = true, text = SESSION_START)) + messages.takeLast(max - 1)

    fun transcript(): String = messages.joinToString("\n") { m ->
        (if (m.fromLearner) "Learner: " else "Mia: ") + m.text
    }

    companion object {
        const val HELP_MARKER = "[HELP_KO]"
        const val SESSION_START = "[Session start. Mia speaks first.]"
        const val COMPLETE_MS = 5 * 60_000L
        const val MAX_MS = 10 * 60_000L
        const val COMPLETE_TURNS = 8
        const val MAX_MESSAGES = 20

        private val workWords = Regex("""\b(work|job|business|office|company|meeting)s?\b""", RegexOption.IGNORE_CASE)
        private val travelWords = Regex("""\b(travel|trip|vacation|holiday|traveling|travelling)s?\b""", RegexOption.IGNORE_CASE)
        private val debateWords = Regex("""\b(question|debate|third|last one|that one|the last|number three)\b""", RegexOption.IGNORE_CASE)

        private val commonWords = setOf("allowed", "important", "everyone", "something", "without", "another")

        fun detectTopic(text: String, debateQuestion: String): String? {
            // 토론 질문의 특징적인 단어(7자 이상, 흔한 단어 제외)를 말하면 토론 주제를 고른 것으로 본다.
            val debateKeywords = debateQuestion.lowercase().split(Regex("[^a-z-]+"))
                .filter { it.length >= 7 && it !in commonWords }
            val lower = text.lowercase()
            return when {
                debateWords.containsMatchIn(text) || debateKeywords.any { it in lower } -> "debate"
                workWords.containsMatchIn(text) -> "work"
                travelWords.containsMatchIn(text) -> "travel"
                else -> null
            }
        }
    }
}
