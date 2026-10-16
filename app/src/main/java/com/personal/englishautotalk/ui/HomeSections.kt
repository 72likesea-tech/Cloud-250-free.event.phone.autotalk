package com.personal.englishautotalk.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.personal.englishautotalk.ai.AiSettings
import com.personal.englishautotalk.ai.GeminiClient
import com.personal.englishautotalk.ai.Reviewer
import com.personal.englishautotalk.record.SessionRecord
import com.personal.englishautotalk.record.SessionStore
import com.personal.englishautotalk.util.DeviceState
import com.personal.englishautotalk.util.RecognizerSupport
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.concurrent.thread

/** AI 설정: API 키(암호화 저장), 모델 선택, 연결 테스트, 무료 데이터 조건 안내. */
@Composable
fun AiSettingsCard(context: Context, onChanged: () -> Unit) {
    val settings = remember { AiSettings(context) }
    var keyInput by remember { mutableStateOf("") }
    var hint by remember { mutableStateOf(settings.keyHint()) }
    var model by remember { mutableStateOf(settings.model) }
    var testResult by remember { mutableStateOf<String?>(null) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("AI 대화 설정 (Gemini)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                if (hint != null) "API 키: 저장됨 (…$hint)" else "API 키: 없음 — 입력하지 않으면 '기본 연습(AI 아님)'으로 진행",
                color = if (hint != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
            )
            OutlinedTextField(
                value = keyInput,
                onValueChange = { keyInput = it },
                label = { Text("Gemini API 키 붙여넣기") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = keyInput.isNotBlank(),
                    onClick = {
                        settings.saveApiKey(keyInput)
                        keyInput = ""
                        hint = settings.keyHint()
                        testResult = null
                        onChanged()
                    },
                ) { Text("키 저장") }
                if (hint != null) {
                    OutlinedButton(onClick = {
                        settings.clearApiKey()
                        hint = null
                        onChanged()
                    }) { Text("키 삭제") }
                }
            }

            Text("모델", style = MaterialTheme.typography.labelLarge)
            AiSettings.MODEL_CHOICES.forEach { choice ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = model == choice, onClick = {
                        model = choice
                        settings.model = choice
                        testResult = null
                    })
                    Text(choice + if (choice == AiSettings.DEFAULT_MODEL) " (기본)" else "")
                }
            }

            OutlinedButton(
                enabled = hint != null,
                onClick = {
                    testResult = "확인 중…"
                    val key = settings.loadApiKey()
                    val selected = settings.model
                    thread {
                        val message = if (key == null) {
                            "저장된 키를 읽을 수 없어요. 다시 저장해 주세요"
                        } else {
                            when (val r = GeminiClient(key, selected).testModel()) {
                                is GeminiClient.Result.Ok -> "✅ 연결 성공 — $selected 사용 가능"
                                is GeminiClient.Result.Fail -> "⚠️ 실패 (${r.httpCode}): ${r.error.userMessage}"
                            }
                        }
                        Handler(Looper.getMainLooper()).post { testResult = message }
                    }
                },
            ) { Text("연결 테스트") }
            testResult?.let { Text(it) }

            Text(
                "무료 Gemini는 보낸 대화를 Google 제품 개선에 쓰고 사람이 검토할 수 있어요. " +
                    "개인정보·회사 기밀은 말하지 마세요. 음성 원본은 보내지도 저장하지도 않습니다.",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

/** 최근 회차 기록, 회차별 교정 보기·다시 받기, 전체 삭제. */
@Composable
fun RecordsSection(context: Context, refreshKey: Int, onChanged: () -> Unit) {
    @Suppress("UNUSED_VARIABLE") val key = refreshKey
    val records = SessionStore.recent(context, limit = 10)
    var confirmDelete by remember { mutableStateOf(false) }
    val fmt = remember { DateTimeFormatter.ofPattern("MM-dd HH:mm") }
    // 교정을 받고 있는 회차(시작 시각)와 결과 문구. 한 번에 한 회차만 받는다.
    var reviewing by remember { mutableStateOf<Long?>(null) }
    var reviewMessage by remember { mutableStateOf<Pair<Long, String>?>(null) }

    fun requestReview(r: SessionRecord) {
        val settings = AiSettings(context)
        val apiKey = settings.loadApiKey()
        if (apiKey == null) {
            reviewMessage = r.startedAt to "API 키가 없어요. 위 AI 설정에서 키를 저장한 뒤 다시 눌러 주세요"
            return
        }
        reviewing = r.startedAt
        reviewMessage = null
        val model = settings.model
        thread {
            val prompt = context.assets.open("prompts/summary.txt").bufferedReader().use { it.readText() }
            val schema = context.assets.open("prompts/summary_schema.json").bufferedReader().use { it.readText() }
            val summary = Reviewer(GeminiClient(apiKey, model), prompt, schema).review(Reviewer.transcriptOf(r.lines))
            Handler(Looper.getMainLooper()).post {
                reviewing = null
                if (summary == null) {
                    reviewMessage = r.startedAt to "교정을 받지 못했어요. 인터넷·키·무료 한도를 확인하고 다시 눌러 주세요"
                } else {
                    SessionStore.replace(context, r.withSummary(summary))
                    onChanged()
                }
            }
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("대화 기록", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        if (records.isNotEmpty()) TextButton(onClick = { confirmDelete = true }) { Text("전체 삭제") }
    }
    if (records.isEmpty()) Text("아직 대화 기록이 없습니다", color = MaterialTheme.colorScheme.outline)
    records.forEach { r ->
        var expanded by remember(r.startedAt) { mutableStateOf(false) }
        var showReview by remember(r.startedAt) { mutableStateOf(false) }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val time = Instant.ofEpochMilli(r.startedAt).atZone(ZoneId.systemDefault()).format(fmt)
                Text(
                    "$time · ${r.outcome}" + (r.topic?.let { " · $it" } ?: "") +
                        (if (!r.aiMode) " · 기본 연습" else "") + (if (r.test) " · 테스트" else ""),
                    fontWeight = FontWeight.Bold,
                )
                if (!r.hasReview && r.hasLearnerLines) {
                    Text(
                        if (r.aiMode) "교정을 아직 받지 않았어요" else "기본 연습 회차 — 교정은 API 키가 있을 때 받을 수 있어요",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (r.hasReview) {
                        TextButton(onClick = { showReview = !showReview }) { Text(if (showReview) "교정 접기" else "교정 보기") }
                    }
                    TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "대화 접기" else "대화 보기 (${r.lines.size}줄)") }
                    if (r.hasLearnerLines) {
                        OutlinedButton(enabled = reviewing == null, onClick = { requestReview(r) }) {
                            Text(
                                when {
                                    reviewing == r.startedAt -> "교정 중…"
                                    r.hasReview -> "교정 다시 받기"
                                    else -> "교정 받기"
                                },
                            )
                        }
                    }
                }
                reviewMessage?.takeIf { it.first == r.startedAt }?.let { Text(it.second, color = MaterialTheme.colorScheme.error, fontSize = 14.sp) }
                if (showReview) ReviewContent(r.sentences, r.corrections, r.praise, r.reviewExpressions, large = false, mindsetKo = r.mindsetKo, praiseKo = r.praiseKo)
                if (expanded) r.lines.forEach { (learner, text) ->
                    Text((if (learner) "You: " else "Mia: ") + text, fontSize = 14.sp)
                }
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("대화 기록 전체 삭제") },
            text = { Text("저장된 모든 회차 기록과 교정 요약을 지웁니다. 되돌릴 수 없어요.") },
            confirmButton = {
                TextButton(onClick = {
                    SessionStore.clear(context)
                    confirmDelete = false
                    onChanged()
                }) { Text("삭제") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("취소") } },
        )
    }
}

/**
 * 음성 인식기 카드: 어떤 인식기를 쓰는지, 영어·한국어 인식 모델이 휴대폰에 설치돼 있는지.
 * 한국어 모델이 없으면 한국어로 말해도 알아채지 못해 바로 대답하지 못할 수 있어 다운로드 버튼을 준다.
 */
@Composable
fun RecognizerCard(context: Context, refreshKey: Int) {
    var english by remember { mutableStateOf<RecognizerSupport.Status?>(null) }
    var korean by remember { mutableStateOf<RecognizerSupport.Status?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(refreshKey) {
        RecognizerSupport.check(context, RecognizerSupport.ENGLISH) { english = it }
        RecognizerSupport.check(context, RecognizerSupport.KOREAN) { korean = it }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("음성 인식기 (내 말을 글자로 바꾸는 기능)", style = MaterialTheme.typography.labelLarge)
            Text(DeviceState.speechRecognizerInfo(context), fontWeight = FontWeight.Bold)
            Text("영어 인식 모델: ${english?.label ?: "확인 중…"}")
            Text("한국어 인식 모델: ${korean?.label ?: "확인 중…"}")
            if (korean == RecognizerSupport.Status.NOT_INSTALLED || english == RecognizerSupport.Status.NOT_INSTALLED) {
                Text(
                    "한국어로 말했을 때 바로 알아채려면 휴대폰에 한국어 인식 모델이 있어야 할 수 있어요.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.error,
                )
                OutlinedButton(onClick = {
                    var ok = true
                    if (korean == RecognizerSupport.Status.NOT_INSTALLED) ok = RecognizerSupport.requestDownload(context, RecognizerSupport.KOREAN) && ok
                    if (english == RecognizerSupport.Status.NOT_INSTALLED) ok = RecognizerSupport.requestDownload(context, RecognizerSupport.ENGLISH) && ok
                    message = if (ok) "다운로드를 요청했어요. Wi-Fi에서 몇 분 뒤 앱을 다시 열어 '설치됨'인지 확인하세요" else "다운로드를 요청하지 못했어요"
                }) { Text("인식 모델 다운로드") }
            }
            message?.let { Text(it, fontSize = 13.sp) }
            Text(
                "인식 정확도는 이 인식기가 정합니다. Google이 아니면 휴대폰 설정 → 일반 → 음성 인식(또는 '기본 디지털 어시스턴트 앱')에서 바꿀 수 있습니다.",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}
