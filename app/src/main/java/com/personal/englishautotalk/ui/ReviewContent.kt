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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.personal.englishautotalk.ai.Correction
import com.personal.englishautotalk.ai.SentenceReview

/**
 * 교정 내용 표시. 회차 종료 화면(large = 큰 글자)과 홈 화면 '대화 기록' 양쪽에서 쓴다.
 * 1) 내 문장 전체 교정: 내가 한 말 → 고친 문장 → 더 자연스러운 표현 → 한국어 설명
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
) {
    val label = if (large) 14.sp else 12.sp
    val body = if (large) 20.sp else 15.sp
    val strong = if (large) 22.sp else 16.sp
    val note = if (large) 17.sp else 14.sp

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
                Column(Modifier.padding(if (large) 16.dp else 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${i + 1}. 내가 한 말" + if (s.ok) "  ✓ 좋아요" else "", fontSize = label, color = MaterialTheme.colorScheme.outline)
                    Text(s.said, fontSize = body)
                    if (!s.ok) {
                        Text("고친 문장", fontSize = label, color = MaterialTheme.colorScheme.outline)
                        Text(s.corrected, fontSize = strong, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                    }
                    if (!s.natural.equals(s.corrected, ignoreCase = true)) {
                        Text("더 자연스럽게", fontSize = label, color = MaterialTheme.colorScheme.outline)
                        Text(s.natural, fontSize = strong, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }
                    if (s.noteKo.isNotBlank()) Text(s.noteKo, fontSize = note, lineHeight = note * 1.45f)
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
                Column(Modifier.padding(if (large) 16.dp else 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${i + 1}. 내가 한 말", fontSize = label, color = MaterialTheme.colorScheme.outline)
                    Text(c.said, fontSize = body)
                    Text("더 자연스럽게", fontSize = label, color = MaterialTheme.colorScheme.outline)
                    Text(c.better, fontSize = strong, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Text(c.noteKo, fontSize = note, lineHeight = note * 1.45f)
                }
            }
        }
    }

    if (!mindsetKo.isNullOrBlank()) {
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        ) {
            Column(Modifier.padding(if (large) 16.dp else 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("🧭 오늘의 영어식 사고 전환", fontSize = strong, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onTertiaryContainer)
                Text(mindsetKo, fontSize = note, lineHeight = note * 1.45f, color = MaterialTheme.colorScheme.onTertiaryContainer)
            }
        }
    }

    if (!praise.isNullOrBlank()) Text(praise, fontSize = body)
    if (reviewExpressions.isNotEmpty()) {
        Text("다음에 써 볼 표현: " + reviewExpressions.joinToString(" · "), fontSize = note)
    }
}
