package com.personal.englishautotalk.talk

/**
 * 앱에 고정된 짧은 문장. 오프닝·토론 질문·재말걸기·작별 인사는 content_pack.json에서,
 * 대화 응답은 Gemini(또는 기본 연습)에서 온다.
 */
object Scripts {
    /** 주제를 고르기 전에 "Help me in Korean"/한국어로 말했고 AI를 쓸 수 없을 때. */
    const val KOREAN_HELP_TOPIC =
        "도와드릴게요. 오늘 이야기할 주제를 영어로 하나 골라 주세요. " +
            "일은 워크, 여행은 트래블, 아니면 오늘의 토론 질문이에요. " +
            "다 말하면 오버 투 유 라고 해 주세요."

    const val KOREAN_HELP_EXAMPLE = "예: Let's talk about travel. Over to you."
}
