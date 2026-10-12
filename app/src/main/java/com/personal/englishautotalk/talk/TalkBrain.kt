package com.personal.englishautotalk.talk

import android.content.Context
import com.personal.englishautotalk.ai.AiSettings
import com.personal.englishautotalk.ai.ApiError
import com.personal.englishautotalk.ai.GeminiClient
import com.personal.englishautotalk.ai.GeminiProtocol
import com.personal.englishautotalk.ai.Reviewer
import com.personal.englishautotalk.ai.SessionSummary
import com.personal.englishautotalk.util.EventLog
import java.util.concurrent.Executors

/**
 * 대화 응답을 만든다. 평소에는 Gemini, 키가 없거나 오류·한도 초과면 그 회차는 '기본 연습(AI 아님)'으로 전환한다.
 * 유료 모델 자동 전환, 키 교체, 무제한 재시도는 하지 않는다.
 */
class TalkBrain(
    private val context: Context,
    val engine: ConversationEngine,
    private val pack: ContentPack,
    private val test: Boolean,
) {
    data class Reply(val text: String, val koreanHintOnScreen: String?, val phase: ConversationEngine.Phase)

    private val executor = Executors.newSingleThreadExecutor()
    private val client: GeminiClient?
    private val conversationPrompt: String
    private val summaryPrompt: String
    private val summarySchema: String

    /** 기본 연습으로 바뀐 이유. null이면 AI 모드. */
    var fallbackReason: ApiError? = null
        private set
    val aiMode get() = fallbackReason == null
    private var basicTurn = 0

    init {
        val settings = AiSettings(context)
        val key = settings.loadApiKey()
        client = key?.let { GeminiClient(it, settings.model) }
        if (client == null) fallbackReason = ApiError.NO_KEY
        conversationPrompt = asset("prompts/conversation.txt")
        summaryPrompt = asset("prompts/summary.txt")
        summarySchema = asset("prompts/summary_schema.json")
    }

    private fun asset(path: String) = context.assets.open(path).bufferedReader().use { it.readText() }

    private val prefix get() = if (test) "[테스트] " else ""

    /** 사용자 턴을 기록하고 응답을 만든다. 콜백은 백그라운드 스레드에서 불린다. */
    fun reply(learnerText: String, koreanHelp: Boolean, callback: (Reply) -> Unit) {
        engine.addLearner(learnerText, koreanHelp)
        val phase = engine.phaseForReply()
        executor.execute {
            val reply = aiReply(phase) ?: basicReply(phase, koreanHelp)
            engine.addApp(reply.text)
            callback(reply)
        }
    }

    private fun aiReply(phase: ConversationEngine.Phase): Reply? {
        val c = client ?: return null
        if (!aiMode) return null
        val body = GeminiProtocol.chatRequest(engine.systemPrompt(conversationPrompt, phase), engine.recentMessages())
        return when (val result = c.generate(body)) {
            is GeminiClient.Result.Ok -> {
                val parsed = GeminiProtocol.parseText(result.body)
                if (parsed == null) {
                    switchToBasic(ApiError.BLOCKED, 200)
                    null
                } else {
                    val text = GeminiProtocol.cleanForSpeech(parsed.text, truncated = parsed.finishReason == "MAX_TOKENS")
                    Reply(text, null, phase)
                }
            }
            is GeminiClient.Result.Fail -> {
                switchToBasic(result.error, result.httpCode)
                null
            }
        }
    }

    private fun switchToBasic(error: ApiError, code: Int) {
        fallbackReason = error
        EventLog.add(context, "${prefix}AI 오류(${code}, ${error.name}) — 기본 연습으로 전환")
    }

    /** AI 없이 이어가는 고정 문장. 화면에 'AI 아님'이 표시된다. */
    private fun basicReply(phase: ConversationEngine.Phase, koreanHelp: Boolean): Reply {
        val seed = basicTurn++
        return when {
            koreanHelp && engine.learnerTurns == 0 ->
                Reply(Scripts.KOREAN_HELP_TOPIC, Scripts.KOREAN_HELP_EXAMPLE, phase)
            koreanHelp -> {
                val (en, ko) = pack.koreanNudges[Math.floorMod(seed, pack.koreanNudges.size)]
                Reply(en, ko, phase)
            }
            phase == ConversationEngine.Phase.WRAP_UP -> Reply(BASIC_WRAP_UP, null, phase)
            phase == ConversationEngine.Phase.CHOOSE_TOPIC -> Reply(BASIC_START, null, phase)
            else -> Reply(BASIC_FOLLOW_UPS[Math.floorMod(seed, BASIC_FOLLOW_UPS.size)], null, phase)
        }
    }

    /** 회차 종료 후 교정을 만들 수 있는지: 키가 있고 AI 모드이며 사용자가 한 번 이상 말했을 때. */
    val canReview get() = client != null && aiMode && engine.learnerTurns > 0

    /** 회차 종료 교정(문장 전체 + 핵심 3개). 만들 수 없거나 실패하면 null. */
    fun summarize(callback: (SessionSummary?) -> Unit) {
        val c = client
        if (c == null || !canReview) {
            callback(null)
            return
        }
        executor.execute {
            val summary = Reviewer(c, summaryPrompt, summarySchema).review(engine.transcript())
            if (summary == null) EventLog.add(context, "${prefix}교정 요약을 만들지 못함")
            callback(summary)
        }
    }

    fun shutdown() = executor.shutdownNow()

    companion object {
        const val BASIC_START = "Okay! Let's start. Tell me a little about it."
        const val BASIC_WRAP_UP = "Great practice today. Thanks for talking with me!"
        val BASIC_FOLLOW_UPS = listOf(
            "Tell me more about that.",
            "Why do you think so?",
            "Can you give me an example?",
            "How did that make you feel?",
            "What happened next?",
            "Do you think that will change in the future?",
        )
    }
}
