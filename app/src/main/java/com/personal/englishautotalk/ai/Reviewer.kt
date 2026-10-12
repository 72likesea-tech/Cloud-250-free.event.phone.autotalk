package com.personal.englishautotalk.ai

/**
 * 대화 전문을 Gemini에 보내 교정(SessionSummary)을 받는다. 네트워크와 분리해 단위 테스트한다.
 * 회차 종료 직후(TalkBrain)와 홈 화면 '대화 기록'의 교정 다시 받기 양쪽에서 쓴다.
 * 스키마 요청이 거부되면 스키마 없이 1회, 응답 해석에 실패하면 1회만 다시 요청한다.
 */
class Reviewer(private val client: GeminiClient, private val prompt: String, private val schema: String) {

    fun review(transcript: String): SessionSummary? {
        var result = client.generate(GeminiProtocol.summaryRequest(prompt, transcript, schema))
        if (result is GeminiClient.Result.Fail && result.error == ApiError.BAD_REQUEST) {
            result = client.generate(GeminiProtocol.summaryRequest(prompt, transcript, null))
        }
        var summary = parse(result)
        if (summary == null && result is GeminiClient.Result.Ok) {
            summary = parse(client.generate(GeminiProtocol.summaryRequest(prompt, transcript, schema)))
        }
        return summary
    }

    private fun parse(result: GeminiClient.Result): SessionSummary? =
        (result as? GeminiClient.Result.Ok)?.body?.let(GeminiProtocol::parseText)?.text?.let(GeminiProtocol::parseSummary)

    companion object {
        /** 저장된 기록(사용자 발화 여부, 텍스트)으로 ConversationEngine.transcript()와 같은 형식을 만든다. */
        fun transcriptOf(lines: List<Pair<Boolean, String>>): String =
            lines.joinToString("\n") { (learner, text) -> (if (learner) "Learner: " else "Mia: ") + text }
    }
}
