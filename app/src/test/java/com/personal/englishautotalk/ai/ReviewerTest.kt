package com.personal.englishautotalk.ai

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewerTest {

    private val good = """{"candidates":[{"content":{"parts":[{"text":"{\"sentences\":[{\"said\":\"I go\",\"corrected\":\"I went\",\"natural\":\"I went\",\"note_ko\":\"과거형\",\"ok\":false}],\"corrections\":[{\"said\":\"I go\",\"better\":\"I went\",\"note_ko\":\"과거형\"},{\"said\":\"a\",\"better\":\"b\",\"note_ko\":\"c\"},{\"said\":\"d\",\"better\":\"e\",\"note_ko\":\"f\"}],\"praise_en\":\"Good\",\"review_expressions\":[]}"}]}}]}"""

    private fun reviewer(vararg results: GeminiClient.Result, bodies: MutableList<String>): Reviewer {
        val queue = ArrayDeque(results.toList())
        val client = GeminiClient("k", "m", sleeper = {}) { _, _, body ->
            bodies += body.orEmpty()
            queue.removeFirst()
        }
        return Reviewer(client, "prompt", """{"type":"object"}""")
    }

    @Test fun returnsSummaryOnFirstTry() {
        val bodies = mutableListOf<String>()
        val s = reviewer(GeminiClient.Result.Ok(good), bodies = bodies).review("Learner: I go")!!
        assertEquals(1, bodies.size)
        assertEquals("I went", s.sentences[0].corrected)
        assertTrue(JSONObject(bodies[0]).getJSONObject("generationConfig").has("responseSchema"))
    }

    @Test fun retriesWithoutSchemaOnBadRequest() {
        val bodies = mutableListOf<String>()
        val s = reviewer(GeminiClient.Result.Fail(ApiError.BAD_REQUEST, 400), GeminiClient.Result.Ok(good), bodies = bodies).review("t")
        assertEquals(2, bodies.size)
        assertTrue(!JSONObject(bodies[1]).getJSONObject("generationConfig").has("responseSchema"))
        assertEquals(1, s!!.sentences.size)
    }

    @Test fun retriesOnceWhenResponseIsNotJson() {
        val bodies = mutableListOf<String>()
        val junk = GeminiClient.Result.Ok("""{"candidates":[{"content":{"parts":[{"text":"sorry"}]}}]}""")
        assertNull(reviewer(junk, junk, bodies = bodies).review("t"))
        assertEquals(2, bodies.size)
        assertEquals(1, reviewer(junk, GeminiClient.Result.Ok(good), bodies = mutableListOf()).review("t")!!.sentences.size)
    }

    @Test fun quotaFailureGivesNullWithoutRetry() {
        val bodies = mutableListOf<String>()
        assertNull(reviewer(GeminiClient.Result.Fail(ApiError.QUOTA, 429), bodies = bodies).review("t"))
        assertEquals(1, bodies.size)
    }

    @Test fun transcriptMatchesEngineFormat() {
        assertEquals("Mia: Hi\nLearner: work", Reviewer.transcriptOf(listOf(false to "Hi", true to "work")))
    }
}
