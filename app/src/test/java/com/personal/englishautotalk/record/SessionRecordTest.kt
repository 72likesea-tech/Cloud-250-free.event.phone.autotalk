package com.personal.englishautotalk.record

import com.personal.englishautotalk.ai.Correction
import com.personal.englishautotalk.ai.SentenceReview
import com.personal.englishautotalk.ai.SessionSummary
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionRecordTest {

    private val base = SessionRecord(
        startedAt = 1L, test = false, outcome = "패스", topic = "work", aiMode = true,
        lines = listOf(false to "Hi", true to "I go to work yesterday"),
        corrections = emptyList(), praise = null,
    )

    @Test fun roundTripsReviewFields() {
        val summary = SessionSummary(
            corrections = listOf(Correction("I go", "I went", "과거형", saidKo = "가요", betterKo = "갔어요")),
            praise = "Nice",
            reviewExpressions = listOf("went to"),
            sentences = listOf(
                SentenceReview(
                    "I go to work yesterday", "I went to work yesterday", "I went to the office yesterday", "과거형", false,
                    saidKo = "어제 출근했어요", correctedKo = "어제 출근했어요", naturalKo = "어제 사무실에 갔어요",
                    thinkingKo = "사고", grammarKo = "문법", wordingKo = "표현",
                ),
            ),
            praiseKo = "좋아요",
            mindsetKo = "한국어는 시제를 문맥에 맡기지만 영어는 동사에 꼭 표시합니다.",
        )
        val saved = base.withSummary(summary)
        val loaded = SessionRecord.fromJson(JSONObject(saved.toJson().toString()))
        assertEquals(saved, loaded)
        assertTrue(loaded.hasReview)
        assertEquals("I went to the office yesterday", loaded.sentences[0].natural)
        assertEquals("어제 사무실에 갔어요", loaded.sentences[0].naturalKo)
        assertEquals("표현", loaded.sentences[0].wordingKo)
        assertEquals("갔어요", loaded.corrections[0].betterKo)
        assertEquals("좋아요", loaded.praiseKo)
        assertEquals("한국어는 시제를 문맥에 맡기지만 영어는 동사에 꼭 표시합니다.", loaded.mindsetKo)
    }

    @Test fun readsOldRecordsWithoutSentences() {
        val old = JSONObject("""{"started_at":1,"test":false,"outcome":"대화 완료","topic":null,"ai_mode":true,"lines":[{"learner":true,"text":"hi"}],"corrections":[],"praise":null}""")
        val r = SessionRecord.fromJson(old)
        assertTrue(r.sentences.isEmpty())
        assertEquals(null, r.mindsetKo)
        assertEquals(null, r.praiseKo)
        assertFalse(r.hasReview)
        assertTrue(r.hasLearnerLines)
    }

    @Test fun readsSentenceReviewsSavedBeforeTranslations() {
        // 0.4.9 이전 기록: 번역·세 갈래 설명 키가 없다.
        val old = JSONObject(
            """{"started_at":1,"lines":[],"corrections":[{"said":"a","better":"b","note_ko":"c"}],"praise":null,
                "sentences":[{"said":"I go","corrected":"I went","natural":"I went","note_ko":"과거형","ok":false}]}""",
        )
        val r = SessionRecord.fromJson(old)
        assertEquals("I went", r.sentences[0].corrected)
        assertEquals("", r.sentences[0].saidKo)
        assertEquals("", r.sentences[0].thinkingKo)
        assertEquals("", r.corrections[0].betterKo)
    }

    @Test fun recordWithoutLearnerLinesCannotBeReviewed() {
        assertFalse(base.copy(lines = listOf(false to "Hi")).hasLearnerLines)
    }
}
