package com.personal.englishautotalk.ai

import com.personal.englishautotalk.talk.ConversationEngine
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GeminiProtocolTest {

    @Test fun chatRequestHasRolesAndConfig() {
        val body = JSONObject(
            GeminiProtocol.chatRequest(
                "sys",
                listOf(ConversationEngine.Message(true, "[start]"), ConversationEngine.Message(false, "Hi"), ConversationEngine.Message(true, "work")),
            ),
        )
        assertEquals("sys", body.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text"))
        val contents = body.getJSONArray("contents")
        assertEquals("user", contents.getJSONObject(0).getString("role"))
        assertEquals("model", contents.getJSONObject(1).getString("role"))
        assertEquals(150, body.getJSONObject("generationConfig").getInt("maxOutputTokens"))
    }

    @Test fun summaryRequestUsesSchemaFile() {
        val schema = File("src/main/assets/prompts/summary_schema.json").readText()
        val body = JSONObject(GeminiProtocol.summaryRequest("sys", "Mia: hi", schema))
        val config = body.getJSONObject("generationConfig")
        assertEquals("application/json", config.getString("responseMimeType"))
        assertTrue(config.getJSONObject("responseSchema").has("properties"))
        val noSchema = JSONObject(GeminiProtocol.summaryRequest("sys", "Mia: hi", null))
        assertTrue(!noSchema.getJSONObject("generationConfig").has("responseSchema"))
    }

    @Test fun parsesTextSkippingThoughts() {
        val body = """{"candidates":[{"content":{"parts":[{"text":"thinking","thought":true},{"text":"Nice! "},{"text":"Where?"}]},"finishReason":"STOP"}]}"""
        val r = GeminiProtocol.parseText(body)!!
        assertEquals("Nice! Where?", r.text)
        assertEquals("STOP", r.finishReason)
        assertNull(GeminiProtocol.parseText("""{"candidates":[]}"""))
        assertNull(GeminiProtocol.parseText("""{"promptFeedback":{"blockReason":"SAFETY"}}"""))
    }

    @Test fun parsesSummaryJson() {
        val text = """{"corrections":[{"said":"I go","better":"I went","note_ko":"과거형"},{"said":"a","better":"b","note_ko":"c"},{"said":"d","better":"e","note_ko":"f"}],"praise_en":"Great!","review_expressions":["went"]}"""
        val s = GeminiProtocol.parseSummary(text)!!
        assertEquals(3, s.corrections.size)
        assertEquals("I went", s.corrections[0].better)
        assertEquals("Great!", s.praise)
        assertNull(GeminiProtocol.parseSummary("not json"))
        assertEquals(3, GeminiProtocol.parseSummary("```json\n$text\n```")!!.corrections.size)
        // 문장 전체 교정이 없는 옛 형식도 읽는다.
        assertTrue(s.sentences.isEmpty())
    }

    @Test fun parsesSentenceReviews() {
        val text = """{"sentences":[
            {"said":"I go to Busan yesterday","corrected":"I went to Busan yesterday","natural":"I took a trip to Busan yesterday","note_ko":"과거형","ok":false},
            {"said":"It was fun","corrected":"It was fun","natural":"I had a great time","note_ko":"더 자연스러운 표현","ok":true},
            {"said":"Thank you","corrected":"","natural":"","note_ko":""}],
            "corrections":[{"said":"a","better":"b","note_ko":"c"},{"said":"d","better":"e","note_ko":"f"},{"said":"g","better":"h","note_ko":"i"}],
            "praise_en":"Nice","review_expressions":[]}"""
        val s = GeminiProtocol.parseSummary(text)!!
        assertEquals(3, s.sentences.size)
        assertEquals("I went to Busan yesterday", s.sentences[0].corrected)
        assertTrue(!s.sentences[0].ok)
        assertTrue(s.sentences[1].ok)
        // corrected·natural이 비면 원문으로 채우고, ok가 없으면 원문과 같은지로 판단한다.
        assertEquals("Thank you", s.sentences[2].corrected)
        assertEquals("Thank you", s.sentences[2].natural)
        assertTrue(s.sentences[2].ok)
        assertEquals(GeminiProtocol.SUMMARY_MAX_TOKENS, JSONObject(GeminiProtocol.summaryRequest("p", "t", null)).getJSONObject("generationConfig").getInt("maxOutputTokens"))
    }

    @Test fun classifiesErrors() {
        assertEquals(ApiError.QUOTA, GeminiProtocol.classify(429, """{"error":{"status":"RESOURCE_EXHAUSTED"}}"""))
        assertEquals(ApiError.NOT_FOUND, GeminiProtocol.classify(404, null))
        assertEquals(ApiError.AUTH, GeminiProtocol.classify(403, "{}"))
        assertEquals(ApiError.AUTH, GeminiProtocol.classify(400, """{"error":{"status":"PERMISSION_DENIED"}}"""))
        assertEquals(ApiError.SERVER, GeminiProtocol.classify(503, ""))
        assertEquals(ApiError.BAD_REQUEST, GeminiProtocol.classify(400, """{"error":{"status":"INVALID_ARGUMENT"}}"""))
    }

    @Test fun cleansTextForSpeech() {
        assertEquals("Great job! Where did you go?", GeminiProtocol.cleanForSpeech("**Great** job! 😀 Where did you go?"))
        assertEquals("One. Two?", GeminiProtocol.cleanForSpeech("One. Two? Three and", truncated = true))
    }
}
