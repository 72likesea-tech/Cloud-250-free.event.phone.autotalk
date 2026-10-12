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
            corrections = listOf(Correction("I go", "I went", "과거형")),
            praise = "Nice",
            reviewExpressions = listOf("went to"),
            sentences = listOf(SentenceReview("I go to work yesterday", "I went to work yesterday", "I went to the office yesterday", "과거형", false)),
        )
        val saved = base.withSummary(summary)
        val loaded = SessionRecord.fromJson(JSONObject(saved.toJson().toString()))
        assertEquals(saved, loaded)
        assertTrue(loaded.hasReview)
        assertEquals("I went to the office yesterday", loaded.sentences[0].natural)
    }

    @Test fun readsOldRecordsWithoutSentences() {
        val old = JSONObject("""{"started_at":1,"test":false,"outcome":"대화 완료","topic":null,"ai_mode":true,"lines":[{"learner":true,"text":"hi"}],"corrections":[],"praise":null}""")
        val r = SessionRecord.fromJson(old)
        assertTrue(r.sentences.isEmpty())
        assertFalse(r.hasReview)
        assertTrue(r.hasLearnerLines)
    }

    @Test fun recordWithoutLearnerLinesCannotBeReviewed() {
        assertFalse(base.copy(lines = listOf(false to "Hi")).hasLearnerLines)
    }
}
