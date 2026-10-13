package com.personal.englishautotalk.ai

import com.personal.englishautotalk.talk.ConversationEngine
import org.json.JSONArray
import org.json.JSONObject

/** 오류 분류 (docs/gemini/taskA-free-tier.md §3). */
enum class ApiError(val retryable: Boolean, val userMessage: String) {
    QUOTA(false, "오늘 무료 AI 한도를 다 썼어요"),
    SERVER(true, "AI 서버가 잠시 응답하지 않아요"),
    NOT_FOUND(false, "이 키로는 선택한 모델을 쓸 수 없어요. 설정에서 모델을 바꿔 보세요"),
    AUTH(false, "API 키를 확인해 주세요"),
    BAD_REQUEST(false, "AI 요청 형식 오류"),
    NETWORK(true, "인터넷에 연결할 수 없어요"),
    NO_KEY(false, "API 키가 없어요. 설정에서 입력해 주세요"),
    BLOCKED(false, "AI가 응답을 만들지 못했어요"),
}

data class Correction(val said: String, val better: String, val noteKo: String)

/**
 * 사용자 문장 하나의 교정. corrected = 틀린 부분만 고친 문장, natural = 더 자연스러운 표현.
 * ok = 문법·의미 오류가 없음(이때 corrected는 said와 같다).
 */
data class SentenceReview(val said: String, val corrected: String, val natural: String, val noteKo: String, val ok: Boolean)

/** 회차 종료 후 교정: 내 문장 전체 교정(sentences) + 핵심 교정 3개 + 칭찬 + 다음에 써 볼 표현. */
data class SessionSummary(
    val corrections: List<Correction>,
    val praise: String,
    val reviewExpressions: List<String>,
    val sentences: List<SentenceReview> = emptyList(),
)

/** Gemini generateContent 요청 본문 생성과 응답 해석. 네트워크와 분리해 단위 테스트한다. */
object GeminiProtocol {

    /**
     * 대화 응답 요청. minimalThinking이면 사고(thinking) 단계를 최소로 해 응답을 빠르게 한다.
     * Gemini 3.x는 사고가 항상 켜져 있고 기본값이 high다(google-gemini/cookbook Get_started_thinking, 2026-10-13 확인).
     * 이 설정을 받지 않는 모델(400)이면 TalkBrain이 끄고 다시 보낸다.
     */
    fun chatRequest(systemPrompt: String, messages: List<ConversationEngine.Message>, minimalThinking: Boolean = true): String {
        val contents = JSONArray()
        messages.forEach { m ->
            contents.put(
                JSONObject()
                    .put("role", if (m.fromLearner) "user" else "model")
                    .put("parts", JSONArray().put(JSONObject().put("text", m.text))),
            )
        }
        return JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt))))
            .put("contents", contents)
            .put(
                "generationConfig",
                JSONObject().put("temperature", 0.7).put("maxOutputTokens", 150).apply {
                    if (minimalThinking) put("thinkingConfig", JSONObject().put("thinkingLevel", "MINIMAL"))
                },
            )
            .toString()
    }

    fun summaryRequest(systemPrompt: String, transcript: String, schemaJson: String?): String {
        val config = JSONObject()
            .put("temperature", 0.3)
            .put("maxOutputTokens", SUMMARY_MAX_TOKENS)
            .put("responseMimeType", "application/json")
        if (schemaJson != null) config.put("responseSchema", JSONObject(schemaJson))
        return JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt))))
            .put(
                "contents",
                JSONArray().put(
                    JSONObject().put("role", "user")
                        .put("parts", JSONArray().put(JSONObject().put("text", transcript))),
                ),
            )
            .put("generationConfig", config)
            .toString()
    }

    data class TextResult(val text: String, val finishReason: String?)

    /** 응답 본문에서 텍스트를 꺼낸다. 후보가 없거나 비어 있으면 null. */
    fun parseText(body: String): TextResult? {
        val candidates = JSONObject(body).optJSONArray("candidates") ?: return null
        if (candidates.length() == 0) return null
        val first = candidates.getJSONObject(0)
        val parts = first.optJSONObject("content")?.optJSONArray("parts") ?: return null
        val text = buildString {
            for (i in 0 until parts.length()) {
                val part = parts.getJSONObject(i)
                // 사고(thought) 요약 파트는 읽지 않는다.
                if (!part.optBoolean("thought", false)) append(part.optString("text"))
            }
        }.trim()
        if (text.isEmpty()) return null
        return TextResult(text, first.optString("finishReason").ifEmpty { null })
    }

    fun parseSummary(text: String): SessionSummary? = runCatching {
        val json = JSONObject(text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim())
        val arr = json.getJSONArray("corrections")
        val corrections = (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Correction(o.getString("said"), o.getString("better"), o.getString("note_ko"))
        }
        val review = json.optJSONArray("review_expressions")?.let { r -> (0 until r.length()).map { r.getString(it) } }.orEmpty()
        val sentences = json.optJSONArray("sentences")?.let { arr2 ->
            (0 until arr2.length()).map {
                val o = arr2.getJSONObject(it)
                val said = o.getString("said")
                val corrected = o.optString("corrected").ifBlank { said }
                SentenceReview(
                    said = said,
                    corrected = corrected,
                    natural = o.optString("natural").ifBlank { corrected },
                    noteKo = o.optString("note_ko"),
                    ok = o.optBoolean("ok", corrected.trim().equals(said.trim(), ignoreCase = true)),
                )
            }
        }.orEmpty()
        SessionSummary(corrections.take(3), json.optString("praise_en"), review, sentences)
    }.getOrNull()

    fun classify(httpCode: Int, body: String?): ApiError {
        val status = runCatching { JSONObject(body ?: "").getJSONObject("error").optString("status") }.getOrDefault("")
        return when {
            httpCode == 429 || status == "RESOURCE_EXHAUSTED" -> ApiError.QUOTA
            httpCode == 404 || status == "NOT_FOUND" -> ApiError.NOT_FOUND
            httpCode == 401 || httpCode == 403 || status == "PERMISSION_DENIED" || status == "UNAUTHENTICATED" -> ApiError.AUTH
            httpCode >= 500 -> ApiError.SERVER
            else -> ApiError.BAD_REQUEST
        }
    }

    /** 문장별 교정이 들어가므로 넉넉히 잡는다(사용자 문장 10개 기준 약 1,500 토큰). */
    const val SUMMARY_MAX_TOKENS = 2_500

    private val markdown = Regex("""[*_#`>~|]""")
    private val nonBmp = Regex("[\\x{10000}-\\x{10FFFF}]")

    /** TTS로 읽기 전에 이모지·마크다운을 없애고, 잘린 응답은 마지막 문장까지만 남긴다. */
    fun cleanForSpeech(text: String, truncated: Boolean = false): String {
        var t = text.replace(nonBmp, "").replace(markdown, "").replace(Regex("\\s+"), " ").trim()
        if (truncated) {
            val end = t.indexOfLast { it == '.' || it == '?' || it == '!' }
            if (end > 0) t = t.substring(0, end + 1)
        }
        return t
    }
}
