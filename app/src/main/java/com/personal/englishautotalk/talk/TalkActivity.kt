package com.personal.englishautotalk.talk

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.personal.englishautotalk.schedule.AlarmScheduler
import com.personal.englishautotalk.ui.AppTheme
import com.personal.englishautotalk.ui.ReviewContent

/** 잠금화면 위에 뜨는 대화 화면. 잠금 해제 없이 동작한다. */
class TalkActivity : ComponentActivity() {

    private var session: TalkSession? = null
    private val handler = Handler(Looper.getMainLooper())
    private val interruptCheck = Runnable { session?.interrupt() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()
        TalkNotifier.cancel(this)

        val mode = TalkMode.valueOf(intent.getStringExtra(EXTRA_MODE) ?: TalkMode.NORMAL.name)
        val test = intent.getBooleanExtra(AlarmScheduler.EXTRA_TEST, true)
        lateinit var s: TalkSession
        s = TalkSession(applicationContext, mode, test) {
            // 교정 요약이 있거나 사용자가 직접 교정을 요청했으면 읽고 닫을 때까지 두되, 너무 오래 켜져 있지 않게 한다.
            val keepOpen = s.summary != null || s.outcome == Outcome.REVIEW_REQUESTED
            handler.postDelayed({ finish() }, if (keepOpen) SUMMARY_CLOSE_MS else CLOSE_DELAY_MS)
        }
        session = s
        setContent { AppTheme { TalkScreen(s) } }
        s.start()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // 이미 진행 중인 회차가 있으면 새 요청으로 중복 세션을 만들지 않는다.
        TalkNotifier.cancel(this)
    }

    override fun onStart() {
        super.onStart()
        handler.removeCallbacks(interruptCheck)
    }

    override fun onStop() {
        super.onStop()
        // 잠금화면 전환 중 잠깐 onStop이 올 수 있어, 잠시 뒤에도 화면 밖이면 중단으로 본다.
        if (!isChangingConfigurations) handler.postDelayed(interruptCheck, INTERRUPT_GRACE_MS)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (!isChangingConfigurations) session?.interrupt()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_MODE = "mode"
        private const val CLOSE_DELAY_MS = 3_000L
        private const val SUMMARY_CLOSE_MS = 3 * 60_000L
        private const val INTERRUPT_GRACE_MS = 1_500L

        fun intent(context: Context, mode: TalkMode, test: Boolean): Intent =
            Intent(context, TalkActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_MODE, mode.name)
                .putExtra(AlarmScheduler.EXTRA_TEST, test)
    }
}

@Composable
private fun TalkScreen(session: TalkSession) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        if (session.phase == Phase.WAITING_TAP) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable { session.onTapToStart() }
                    .safeDrawingPadding()
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "영어 말걸기 시간이에요\n\n화면을 터치하면 시작합니다",
                    fontSize = 30.sp,
                    lineHeight = 40.sp,
                    textAlign = TextAlign.Center,
                )
                Column(Modifier.align(Alignment.BottomCenter)) {
                    ActionButtons(session)
                }
            }
            return@Surface
        }

        val close = { (context as? android.app.Activity)?.finish(); Unit }
        if (session.phase == Phase.ENDED && session.summary != null) {
            SummaryScreen(session, close)
            return@Surface
        }
        if (session.phase == Phase.ENDED && session.outcome == Outcome.REVIEW_REQUESTED) {
            NoReviewScreen(session, close)
            return@Surface
        }
        if (session.confirmingEnd) EndAndReviewDialog(session)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 화면 맨 위 한 줄: 음성 명령 세 가지만. 대화 글자 영역을 최대한 넓히려고 작게 한 줄로 둔다.
            CommandBar(session.koreanHelpActive)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = when (session.phase) {
                        Phase.PREPARING -> "준비 중…"
                        Phase.SPEAKING -> "🔊 말하는 중"
                        Phase.LISTENING -> "🎤 듣는 중"
                        Phase.THINKING -> "💭 생각 중…"
                        Phase.SUMMARIZING -> "📝 교정 중…"
                        Phase.ENDED -> "종료 — ${session.outcome?.label.orEmpty()}"
                        Phase.WAITING_TAP -> ""
                    },
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                if (session.phase == Phase.LISTENING) LanguageChip(session.hearingKorean, session.koreanDetected)
            }
            session.basicModeReason?.let {
                Text(
                    "기본 연습 — AI 응답이 아닙니다 ($it)",
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .padding(6.dp),
                )
            }
            session.notice?.let {
                Text(it, color = MaterialTheme.colorScheme.error, fontSize = 15.sp)
            }
            val listState = rememberLazyListState()
            LaunchedEffect(session.lines.size) {
                if (session.lines.isNotEmpty()) listState.animateScrollToItem(session.lines.size - 1)
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(session.lines) { line ->
                    Text(
                        text = when {
                            line.hint -> "💡 ${line.text}"
                            line.fromApp -> line.text
                            line.koreanHelp -> "You 🇰🇷: ${line.text}"
                            else -> "You: ${line.text}"
                        },
                        fontSize = if (line.hint) 22.sp else 28.sp,
                        lineHeight = if (line.hint) 30.sp else 36.sp,
                        color = when {
                            line.hint -> MaterialTheme.colorScheme.tertiary
                            line.fromApp -> MaterialTheme.colorScheme.onBackground
                            else -> MaterialTheme.colorScheme.primary
                        },
                    )
                }
                if (session.partial.isNotBlank()) {
                    item { Text("You: ${session.partial}…", fontSize = 24.sp, color = MaterialTheme.colorScheme.outline) }
                }
            }
            ActionButtons(session)
        }
    }
}

/**
 * 맨 위 두 줄: 1줄 "I'm all set" · "Over to you", 2줄 Help me in Korean 또는 한국어.
 * 글자 크기 18sp(사용자 지정). 화면이 좁아 한 줄에 안 들어갈 때만 그 줄을 작게 줄인다. 한국어 도움 중에는 2줄을 강조한다.
 */
@Composable
private fun CommandBar(koreanHelpActive: Boolean) {
    val base = TextStyle(color = MaterialTheme.colorScheme.onTertiaryContainer, fontWeight = FontWeight.Bold)
    val autoSize = TextAutoSize.StepBased(minFontSize = 12.sp, maxFontSize = 18.sp)
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.tertiaryContainer)) {
        BasicText(
            text = "\"I'm all set\"  ·  \"Over to you\"",
            style = base,
            maxLines = 1,
            softWrap = false,
            autoSize = autoSize,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp),
        )
        BasicText(
            text = "Help me in Korean 또는 한국어",
            style = if (koreanHelpActive) base.copy(color = MaterialTheme.colorScheme.onTertiary) else base,
            maxLines = 1,
            softWrap = false,
            autoSize = autoSize,
            modifier = Modifier
                .fillMaxWidth()
                .background(if (koreanHelpActive) MaterialTheme.colorScheme.tertiary else Color.Transparent)
                .padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

/**
 * 맨 아래 한 줄 버튼 4개(왼쪽부터): 패스(1시간뒤) · 오늘패스 · 대화끝/교정받기 · 내말끝.
 * 자리가 바뀌지 않도록 항상 4개를 두고, 지금 쓸 수 없는 버튼은 흐리게 한다.
 */
@Composable
private fun ActionButtons(session: TalkSession) {
    if (session.phase == Phase.ENDED || session.phase == Phase.SUMMARIZING) return
    val padding = PaddingValues(horizontal = 2.dp, vertical = 4.dp)
    val label = 15.sp
    Row(
        modifier = Modifier.fillMaxWidth().height(64.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        OutlinedButton(onClick = { session.pass() }, modifier = Modifier.weight(1f).fillMaxHeight(), contentPadding = padding) {
            Text("패스\n(1시간뒤)", fontSize = label, lineHeight = 18.sp, textAlign = TextAlign.Center)
        }
        OutlinedButton(onClick = { session.skipToday() }, modifier = Modifier.weight(1f).fillMaxHeight(), contentPadding = padding) {
            Text("오늘패스", fontSize = label, textAlign = TextAlign.Center)
        }
        Button(
            onClick = { session.requestEndAndReview() },
            enabled = session.phase != Phase.WAITING_TAP && !session.confirmingEnd,
            modifier = Modifier.weight(1f).fillMaxHeight(),
            contentPadding = padding,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary),
        ) {
            Text("대화끝/\n교정받기", fontSize = label, lineHeight = 18.sp, textAlign = TextAlign.Center, fontWeight = FontWeight.Bold)
        }
        Button(
            onClick = { session.finishMyTurn() },
            enabled = session.phase == Phase.LISTENING && !session.confirmingEnd,
            modifier = Modifier.weight(1f).fillMaxHeight(),
            contentPadding = padding,
        ) {
            Text("내말끝", fontSize = 18.sp, textAlign = TextAlign.Center, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun SummaryScreen(session: TalkSession, onClose: () -> Unit) {
    val summary = session.summary ?: return
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("오늘의 영어 교정", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("종료 — ${session.outcome?.label.orEmpty()}", color = MaterialTheme.colorScheme.outline)
        ReviewContent(summary.sentences, summary.corrections, summary.praise, summary.reviewExpressions, large = true, mindsetKo = summary.mindsetKo, praiseKo = summary.praiseKo)
        Button(onClick = onClose, modifier = Modifier.fillMaxWidth().height(64.dp)) {
            Text("닫기", fontSize = 20.sp)
        }
    }
}

/** '대화 끝내고 교정 받기' 확인 창. 이 창이 떠 있는 동안 앱은 듣지 않는다. */
@Composable
private fun EndAndReviewDialog(session: TalkSession) {
    val count = session.sentencesSoFar
    AlertDialog(
        onDismissRequest = { session.cancelEndAndReview() },
        title = { Text("오늘 영어 대화를 끝낼까요?", fontSize = 24.sp, fontWeight = FontWeight.Bold) },
        text = {
            Text(
                when {
                    count == 0 -> "아직 영어로 말한 문장이 없어 교정할 것이 없어요.\n그래도 오늘 대화를 끝낼까요?"
                    !session.aiReviewAvailable ->
                        "지금까지 말한 ${count}문장을 저장하고 오늘 대화를 끝냅니다.\n" +
                            "AI 키가 없거나 AI 오류로 지금은 교정할 수 없어요. 키를 확인한 뒤 홈 → 대화 기록에서 교정을 받을 수 있어요."
                    else ->
                        "지금까지 말한 ${count}문장을 모두 교정해 화면에 글로 보여 드려요.\n" +
                            "(틀린 부분 → 고친 문장 → 더 자연스러운 표현)\n\n오늘 대화는 완료로 처리되고, 오늘은 다시 말걸지 않아요."
                },
                fontSize = 18.sp,
                lineHeight = 26.sp,
            )
        },
        confirmButton = {
            Button(onClick = { session.confirmEndAndReview() }) {
                Text(if (count > 0 && session.aiReviewAvailable) "네, 끝내고 교정 받기" else "네, 끝내기", fontSize = 18.sp)
            }
        },
        dismissButton = {
            TextButton(onClick = { session.cancelEndAndReview() }) { Text("계속 대화하기", fontSize = 18.sp) }
        },
    )
}

/** 직접 교정을 요청했지만 교정을 만들지 못했을 때: 이유를 보여 주고 사용자가 닫을 때까지 둔다. */
@Composable
private fun NoReviewScreen(session: TalkSession, onClose: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("오늘 대화를 끝냈어요", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            session.notice ?: "교정을 만들지 못했어요. 홈 → 대화 기록에서 '교정 받기'를 눌러 다시 받을 수 있어요",
            fontSize = 20.sp,
            lineHeight = 28.sp,
        )
        Button(onClick = onClose, modifier = Modifier.fillMaxWidth().height(64.dp)) {
            Text("닫기", fontSize = 20.sp)
        }
    }
}

/**
 * 듣는 중에 내 말이 어떤 언어로 받아 적히는지 보여 준다(2026-10-17 사용자 요청).
 * 영어로 인식 → 영어 대화로 보냄, 한국어로 인식 → 한국어 도움으로 보냄.
 */
@Composable
private fun LanguageChip(hearingKorean: Boolean?, koreanDetected: Boolean) {
    val korean = hearingKorean == true
    val text = when (hearingKorean) {
        null -> "🇺🇸 영어로 듣는 중" + if (koreanDetected) " · 한국어 감지" else ""
        true -> "🇰🇷 한국어로 인식"
        false -> "🇺🇸 영어로 인식" + if (koreanDetected) " · 한국어 감지" else ""
    }
    Text(
        text,
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        color = if (korean) MaterialTheme.colorScheme.onTertiary else MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier
            .background(if (korean) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}
