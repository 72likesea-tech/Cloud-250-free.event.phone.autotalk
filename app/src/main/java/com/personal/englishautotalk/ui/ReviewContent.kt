package com.personal.englishautotalk.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.personal.englishautotalk.ai.Correction
import com.personal.englishautotalk.ai.SentenceReview

/**
 * 교정 내용 표시. 회차 종료 화면(large = 큰 글자)과 홈 화면 '대화 기록' 양쪽에서 쓴다.
 * 1) 내 문장 전체 교정: 내가 한 말 → 고친 문장 → 더 자연스러운 표현(영어 문장마다 밑에 한국어 번역을 흐리게)
 *    → 한 줄 요약 → 자세한 설명 세 갈래(사고방식 / 문법·어순 / 단어·표현)
 * 2) 꼭 기억할 교정 3개  3) 오늘의 영어식 사고 전환(mindsetKo)  4) 칭찬  5) 다음에 써 볼 표현
 */
@Composable
fun ReviewContent(
    sentences: List<SentenceReview>,
    corrections: List<Correction>,
    praise: String?,
    reviewExpressions: List<String>,
    large: Boolean,
    mindsetKo: String? = null,
    praiseKo: String? = null,
) {
    val sizes = ReviewSizes(large)

    if (sentences.isNotEmpty()) {
        val wrong = sentences.count { !it.ok }
        Text(
            "내 문장 전체 교정 (${sentences.size}문장 중 고칠 곳 $wrong)",
            style = if (large) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        sentences.forEachIndexed { i, s ->
            Card(
                Modifier.fillMaxWidth(),
                colors = if (s.ok) {
                    CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                } else {
                    CardDefaults.cardColors()
                },
            ) {
                Column(Modifier.padding(sizes.padding), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Label("${i + 1}. 내가 한 말" + if (s.ok) "  ✓ 좋아요" else "", sizes)
                    EnglishWithKorean(s.said, s.saidKo, sizes, sizes.body)
                    if (!s.ok) {
                        Label("고친 문장", sizes)
                        EnglishWithKorean(s.corrected, s.correctedKo, sizes, sizes.strong, MaterialTheme.colorScheme.error, bold = true)
                    }
                    if (!s.natural.equals(s.corrected, ignoreCase = true)) {
                        Label("더 자연스럽게", sizes)
                        EnglishWithKorean(s.natural, s.naturalKo, sizes, sizes.strong, MaterialTheme.colorScheme.primary, bold = true)
                    }
                    if (s.noteKo.isNotBlank()) {
                        Text(s.noteKo, fontSize = sizes.note, lineHeight = sizes.note * 1.45f, fontWeight = FontWeight.SemiBold)
                    }
                    Explanation("🧠 사고방식 차이", s.thinkingKo, sizes)
                    Explanation("📐 문법·어순 차이", s.grammarKo, sizes)
                    Explanation("💬 단어 선택·표현 차이", s.wordingKo, sizes)
                }
            }
        }
    }

    if (corrections.isNotEmpty()) {
        Text(
            "꼭 기억할 교정 ${corrections.size}개",
            style = if (large) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        corrections.forEachIndexed { i, c ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(sizes.padding), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Label("${i + 1}. 내가 한 말", sizes)
                    EnglishWithKorean(c.said, c.saidKo, sizes, sizes.body)
                    Label("더 자연스럽게", sizes)
                    EnglishWithKorean(c.better, c.betterKo, sizes, sizes.strong, MaterialTheme.colorScheme.primary, bold = true)
                    Text(c.noteKo, fontSize = sizes.note, lineHeight = sizes.note * 1.45f)
                }
            }
        }
    }

    if (!mindsetKo.isNullOrBlank()) {
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        ) {
            Column(Modifier.padding(sizes.padding), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("🧭 오늘의 영어식 사고 전환", fontSize = sizes.strong, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onTertiaryContainer)
                Text(mindsetKo, fontSize = sizes.note, lineHeight = sizes.note * 1.45f, color = MaterialTheme.colorScheme.onTertiaryContainer)
            }
        }
    }

    if (!praise.isNullOrBlank()) EnglishWithKorean(praise, praiseKo.orEmpty(), sizes, sizes.body)
    if (reviewExpressions.isNotEmpty()) {
        Text("다음에 써 볼 표현", fontSize = sizes.note, fontWeight = FontWeight.Bold)
        reviewExpressions.forEach { Text("• $it", fontSize = sizes.note, lineHeight = sizes.note * 1.4f) }
    }
}

private class ReviewSizes(large: Boolean) {
    val label = if (large) 14.sp else 12.sp
    val body = if (large) 20.sp else 15.sp
    val strong = if (large) 22.sp else 16.sp
    val note = if (large) 17.sp else 14.sp
    val korean = if (large) 15.sp else 12.sp
    val padding = if (large) 16.dp else 12.dp
}

@Composable
private fun Label(text: String, sizes: ReviewSizes) {
    Text(text, fontSize = sizes.label, color = MaterialTheme.colorScheme.outline)
}

/** 영어 문장을 보여 주고, 바로 밑에 한국어 번역을 작고 흐리게 보여 준다(번역이 없으면 영어만). */
@Composable
private fun EnglishWithKorean(
    english: String,
    korean: String,
    sizes: ReviewSizes,
    size: TextUnit,
    color: Color = Color.Unspecified,
    bold: Boolean = false,
) {
    Column {
        Text(english, fontSize = size, color = color, fontWeight = if (bold) FontWeight.Bold else null)
        if (korean.isNotBlank()) {
            Text(
                korean,
                fontSize = sizes.korean,
                lineHeight = sizes.korean * 1.35f,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            )
        }
    }
}

/** 자세한 설명 한 갈래(제목 + 내용). 내용이 없으면 보여 주지 않는다. */
@Composable
private fun Explanation(title: String, body: String, sizes: ReviewSizes) {
    if (body.isBlank()) return
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Text(title, fontSize = sizes.label, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.tertiary)
        Text(body, fontSize = sizes.note, lineHeight = sizes.note * 1.45f)
    }
}
