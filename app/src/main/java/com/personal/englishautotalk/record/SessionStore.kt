package com.personal.englishautotalk.record

import android.content.Context
import com.personal.englishautotalk.ai.Correction
import com.personal.englishautotalk.ai.SentenceReview
import com.personal.englishautotalk.ai.SessionSummary
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 회차 기록. 기기 안 파일(한 줄에 한 회차 JSON)에만 저장한다. 음성 원본은 저장하지 않는다. */
data class SessionRecord(
    val startedAt: Long,
    val test: Boolean,
    val outcome: String,
    val topic: String?,
    val aiMode: Boolean,
    val lines: List<Pair<Boolean, String>>, // (사용자 발화 여부, 텍스트)
    val corrections: List<Correction>,
    val praise: String?,
    val sentences: List<SentenceReview> = emptyList(),
    val reviewExpressions: List<String> = emptyList(),
    val mindsetKo: String? = null,
    val praiseKo: String? = null,
) {
    /** 사용자가 한 말이 있어 교정을 받을 수 있는 회차. */
    val hasLearnerLines get() = lines.any { it.first }
    val hasReview get() = corrections.isNotEmpty() || sentences.isNotEmpty()

    fun withSummary(s: SessionSummary) = copy(
        corrections = s.corrections,
        praise = s.praise.ifBlank { null },
        sentences = s.sentences,
        reviewExpressions = s.reviewExpressions,
        mindsetKo = s.mindsetKo.ifBlank { null },
        praiseKo = s.praiseKo.ifBlank { null },
    )

    fun toJson(): JSONObject = JSONObject()
        .put("started_at", startedAt)
        .put("test", test)
        .put("outcome", outcome)
        .put("topic", topic ?: JSONObject.NULL)
        .put("ai_mode", aiMode)
        .put("lines", JSONArray().apply { lines.forEach { put(JSONObject().put("learner", it.first).put("text", it.second)) } })
        .put(
            "corrections",
            JSONArray().apply { corrections.forEach { put(it.toJson()) } },
        )
        .put("praise", praise ?: JSONObject.NULL)
        .put(
            "sentences",
            JSONArray().apply { sentences.forEach { put(it.toJson()) } },
        )
        .put("review_expressions", JSONArray(reviewExpressions))
        .put("mindset_ko", mindsetKo ?: JSONObject.NULL)
        .put("praise_ko", praiseKo ?: JSONObject.NULL)

    companion object {
        fun fromJson(o: JSONObject): SessionRecord {
            val lines = o.optJSONArray("lines") ?: JSONArray()
            val corr = o.optJSONArray("corrections") ?: JSONArray()
            val sent = o.optJSONArray("sentences") ?: JSONArray()
            val review = o.optJSONArray("review_expressions") ?: JSONArray()
            return SessionRecord(
                startedAt = o.getLong("started_at"),
                test = o.optBoolean("test"),
                outcome = o.optString("outcome"),
                topic = if (o.isNull("topic")) null else o.optString("topic"),
                aiMode = o.optBoolean("ai_mode"),
                lines = (0 until lines.length()).map { lines.getJSONObject(it).let { l -> l.getBoolean("learner") to l.getString("text") } },
                corrections = (0 until corr.length()).map { Correction.fromJson(corr.getJSONObject(it)) },
                praise = if (o.isNull("praise")) null else o.optString("praise"),
                sentences = (0 until sent.length()).map { SentenceReview.fromJson(sent.getJSONObject(it)) },
                reviewExpressions = (0 until review.length()).map { review.getString(it) },
                mindsetKo = if (o.isNull("mindset_ko")) null else o.optString("mindset_ko").ifBlank { null },
                praiseKo = if (o.isNull("praise_ko")) null else o.optString("praise_ko").ifBlank { null },
            )
        }
    }
}

object SessionStore {
    private const val FILE = "sessions.jsonl"

    @Synchronized
    fun append(context: Context, record: SessionRecord) {
        File(context.filesDir, FILE).appendText(record.toJson().toString() + "\n")
    }

    fun recent(context: Context, limit: Int = 20): List<SessionRecord> {
        val file = File(context.filesDir, FILE)
        if (!file.exists()) return emptyList()
        return file.readLines().asReversed().asSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { runCatching { SessionRecord.fromJson(JSONObject(it)) }.getOrNull() }
            .take(limit)
            .toList()
    }

    /** 기록 하나의 교정을 바꿔 저장한다(홈 화면 '교정 받기'). 시작 시각으로 찾는다. */
    @Synchronized
    fun replace(context: Context, record: SessionRecord) {
        val file = File(context.filesDir, FILE)
        if (!file.exists()) return
        val updated = file.readLines().map { line ->
            val startedAt = runCatching { JSONObject(line).getLong("started_at") }.getOrNull()
            if (startedAt == record.startedAt) record.toJson().toString() else line
        }
        file.writeText(updated.filter { it.isNotBlank() }.joinToString("\n", postfix = "\n"))
    }

    @Synchronized
    fun clear(context: Context) {
        File(context.filesDir, FILE).delete()
    }
}
