package com.personal.englishautotalk.talk

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.VibratorManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.personal.englishautotalk.ai.SessionSummary
import com.personal.englishautotalk.record.SessionRecord
import com.personal.englishautotalk.record.SessionStore
import com.personal.englishautotalk.schedule.AlarmScheduler
import com.personal.englishautotalk.schedule.SchedulePolicy
import com.personal.englishautotalk.schedule.ScheduleStore
import com.personal.englishautotalk.util.BeepMuter
import com.personal.englishautotalk.util.DeviceState
import com.personal.englishautotalk.util.EventLog
import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.Locale

enum class Phase { WAITING_TAP, PREPARING, SPEAKING, LISTENING, THINKING, SUMMARIZING, ENDED }

enum class Outcome(val label: String, val completesToday: Boolean) {
    ANSWERED("대화 완료", true),
    SKIPPED_TODAY("오늘은 그만", true),
    REVIEW_REQUESTED("대화 끝내고 교정 받기", true),
    PASSED("패스 — 1시간 뒤 다시", false),
    NO_ANSWER("대답 없음 — 1시간 뒤 다시", false),
    NO_TAP("방해금지 중 30초 무반응 — 1시간 뒤 다시", false),
    INTERRUPTED("화면 이탈로 중단 — 1시간 뒤 다시", false),
    ERROR("오류로 종료 — 1시간 뒤 다시", false),
}

/** hint = 소리 내어 읽지 않고 화면에만 보이는 한국어 힌트. */
data class Line(val fromApp: Boolean, val text: String, val hint: Boolean = false)

/**
 * 한 번의 말걸기 흐름: (방해금지면 터치 대기) → 첫 문장 음성 → 자동 듣기 →
 * 8초 무응답이면 1회 다시 말걸기 → 그래도 없으면 종료.
 * 사용자 차례는 "I'm all set"/"Over to you"라고 말해야만 끝나고(듣는 동안 인식기 비프음은 음소거), "Help me in Korean"이나 한국어로 말하면 한국어로 돕는다.
 * 모든 상태 변경은 메인 스레드에서 한다.
 */
class TalkSession(
    private val context: Context,
    val mode: TalkMode,
    private val test: Boolean,
    private val onEnded: (Outcome) -> Unit,
) {
    var phase by mutableStateOf(if (mode == TalkMode.DND) Phase.WAITING_TAP else Phase.PREPARING)
        private set
    var notice by mutableStateOf<String?>(null)
        private set
    var partial by mutableStateOf("")
        private set
    val lines = mutableStateListOf<Line>()
    /** 회차 종료 후 교정 요약. 있으면 화면이 닫히지 않고 요약을 보여준다. */
    var summary by mutableStateOf<SessionSummary?>(null)
        private set
    /** 기본 연습(AI 아님)으로 바뀐 이유. null이면 AI 대화. */
    var basicModeReason by mutableStateOf<String?>(null)
        private set
    var outcome by mutableStateOf<Outcome?>(null)
        private set
    /** '대화 끝내고 교정 받기' 확인 창이 열려 있는 동안 true. 이때는 듣기를 멈춘다. */
    var confirmingEnd by mutableStateOf(false)
        private set
    /** 확인 창에 보여 줄, 지금까지 말한 문장 수(말하는 중인 문장 포함). */
    var sentencesSoFar by mutableStateOf(0)
        private set
    /** 교정을 받을 수 있는 상태인지(키가 있고 AI 모드). 확인 창 문구에 쓴다. */
    val aiReviewAvailable get() = brain.aiMode

    private val handler = Handler(Looper.getMainLooper())
    private val startedAt = System.currentTimeMillis()
    private val pack = ContentPack.load(context)
    private val brain = TalkBrain(
        context,
        ConversationEngine(pack.debateQuestion(LocalDate.now())) { SystemClock.elapsedRealtime() },
        pack,
        test,
    )
    private val afterSpeech = mutableMapOf<String, () -> Unit>()
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var recognizer: SpeechRecognizer? = null
    private val beepMuter = BeepMuter(context)
    private var utteranceSeq = 0
    private var began = false
    private var reprompted = false
    private var heardSpeech = false
    private var detectedKorean = false
    private val turn = mutableListOf<String>()
    /** 확인 창 때문에 듣기를 멈췄으면, 창을 닫을 때 이어서 듣는다. */
    private var resumeListening = false

    private val prefix get() = if (test) "[테스트] " else ""

    fun start() {
        if (DeviceState.isMediaMuted(context)) notice = "미디어 볼륨이 0이라 소리가 들리지 않습니다"
        if (DeviceState.isTotalSilence(context)) notice = "방해금지 '완전 무음'이라 소리가 나지 않습니다. 자막을 보세요"
        tts = TextToSpeech(context) { status -> handler.post { onTtsInit(status) } }
        if (mode == TalkMode.DND) {
            vibrate()
            handler.postDelayed(tapTimeout, TAP_TIMEOUT_MS)
        }
    }

    private val tapTimeout = Runnable { finish(Outcome.NO_TAP) }

    fun onTapToStart() {
        if (phase != Phase.WAITING_TAP) return
        handler.removeCallbacks(tapTimeout)
        phase = Phase.PREPARING
        maybeBegin()
    }

    /** 패스: 1시간 뒤 다시. 이미 말한 문장이 있으면 교정을 먼저 만들고 끝낸다. */
    fun pass() = reviewThenFinish(Outcome.PASSED)

    /** 교정 없이 오늘은 그만. 교정을 원하면 '대화 끝내고 교정 받기'를 쓴다. */
    fun skipToday() = finish(Outcome.SKIPPED_TODAY)

    /**
     * '대화 끝내고 교정 받기' 버튼: 확인 창을 띄우고, 창이 떠 있는 동안에는 듣기를 멈춰
     * 그 사이에 한 말이 대답으로 넘어가지 않게 한다.
     */
    fun requestEndAndReview() {
        if (phase == Phase.ENDED || phase == Phase.SUMMARIZING || phase == Phase.WAITING_TAP || confirmingEnd) return
        confirmingEnd = true
        sentencesSoFar = brain.engine.learnerTurns + if (pendingTurnText().isNotBlank()) 1 else 0
        if (phase == Phase.LISTENING) {
            resumeListening = true
            handler.removeCallbacks(silenceTimeout)
            recognizer?.cancel()
            beepMuter.unmute()
        }
    }

    /** 확인 창에서 '계속 대화하기': 멈춘 듣기를 그대로 이어간다(말하던 문장은 유지). */
    fun cancelEndAndReview() {
        if (!confirmingEnd) return
        confirmingEnd = false
        if (resumeListening && phase == Phase.LISTENING) {
            resumeListening = false
            beepMuter.mute()
            startRecognizer()
            if (turn.isEmpty()) {
                heardSpeech = false
                handler.postDelayed(silenceTimeout, ANSWER_WAIT_MS)
            }
        }
    }

    /** 확인 창에서 '끝내고 교정 받기': 말하던 문장까지 포함해 교정을 받고 오늘 회차를 끝낸다. */
    fun confirmEndAndReview() {
        if (!confirmingEnd) return
        confirmingEnd = false
        resumeListening = false
        val pending = pendingTurnText()
        turn.clear()
        partial = ""
        if (pending.isNotBlank()) {
            lines += Line(fromApp = false, text = pending)
            brain.engine.addLearner(pending)
        }
        EventLog.add(context, "${prefix}사용자가 대화 종료·교정 요청")
        reviewThenFinish(Outcome.REVIEW_REQUESTED)
    }

    private fun pendingTurnText() = VoiceCommands.parseSegment(turn.joinToString(" ")).text

    fun interrupt() = finish(Outcome.INTERRUPTED)

    private fun onTtsInit(status: Int) {
        val engine = tts ?: return
        if (status != TextToSpeech.SUCCESS) {
            notice = "음성 출력(TTS)을 시작할 수 없습니다"
            finish(Outcome.ERROR)
            return
        }
        val lang = engine.setLanguage(Locale.US)
        if (lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED) {
            notice = "영어 음성 데이터가 없습니다. 설정 → 텍스트 읽어주기에서 영어(미국)를 설치하세요"
            finish(Outcome.ERROR)
            return
        }
        // 미디어 스트림으로 내보내면 이어폰이 연결된 경우 이어폰으로 나온다.
        engine.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) = Unit
            override fun onDone(utteranceId: String) {
                handler.post { afterSpeech.remove(utteranceId)?.invoke() }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String) {
                handler.post {
                    notice = "음성 출력 오류"
                    afterSpeech.remove(utteranceId)?.invoke()
                }
            }
        })
        ttsReady = true
        maybeBegin()
    }

    private fun maybeBegin() {
        if (began || !ttsReady || phase != Phase.PREPARING) return
        began = true
        EventLog.add(context, "${prefix}첫 문장 재생 시작")
        val opening = pack.opening(ZonedDateTime.now())
        brain.engine.addApp(opening)
        updateBasicMode()
        say(opening) { listen() }
    }

    private fun say(text: String, locale: Locale = localeFor(text), then: () -> Unit) {
        if (phase == Phase.ENDED || phase == Phase.SUMMARIZING) return
        phase = Phase.SPEAKING
        beepMuter.unmute()
        lines += Line(fromApp = true, text = text)
        val engine = tts ?: return
        val result = engine.setLanguage(locale)
        if (locale != Locale.US &&
            (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED)
        ) {
            // 한국어 음성이 없으면 화면 표시만 하고 흐름은 이어간다.
            notice = "한국어 음성 데이터가 없어 화면에만 표시합니다"
            handler.post(then)
            return
        }
        val id = "u${++utteranceSeq}"
        afterSpeech[id] = then
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    /** 사용자 차례 시작. 끝 신호(I'm all set / Over to you)가 나올 때까지 여러 구간을 이어 듣는다. */
    private fun listen() {
        if (phase == Phase.ENDED || phase == Phase.SUMMARIZING) return
        if (!DeviceState.canRecordAudio(context)) {
            notice = "마이크 권한이 없어 대답을 들을 수 없습니다"
            finish(Outcome.ERROR)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            notice = "이 기기에서 음성 인식을 사용할 수 없습니다"
            finish(Outcome.ERROR)
            return
        }
        phase = Phase.LISTENING
        heardSpeech = false
        detectedKorean = false
        partial = ""
        turn.clear()
        if (confirmingEnd) {
            // 확인 창이 떠 있으면 듣기를 시작하지 않고, 창을 닫을 때 시작한다.
            resumeListening = true
            return
        }
        beepMuter.mute()
        startRecognizer()
        handler.postDelayed(silenceTimeout, ANSWER_WAIT_MS)
    }

    private fun startRecognizer() {
        if (phase != Phase.LISTENING || confirmingEnd) return
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also {
            it.setRecognitionListener(listener)
            recognizer = it
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        if (Build.VERSION.SDK_INT >= 34) {
            // 한국어로 말하면 한국어 도움으로 처리하기 위해 언어 감지를 요청한다(인식기가 지원할 때만 동작).
            val languages = arrayListOf("en-US", "ko-KR")
            intent.putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true)
                .putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES, languages)
                .putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH, RecognizerIntent.LANGUAGE_SWITCH_BALANCED)
                .putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES, languages)
        }
        r.startListening(intent)
    }

    /** 인식기가 한 구간을 끝낸 뒤 곧바로 다시 켜면 BUSY가 날 수 있어 잠깐 쉬고 다시 듣는다. */
    private fun restartRecognizer() {
        handler.postDelayed({ startRecognizer() }, RESTART_DELAY_MS)
    }

    private fun rearmNoAnswerWait() {
        heardSpeech = false
        handler.removeCallbacks(silenceTimeout)
        handler.postDelayed(silenceTimeout, ANSWER_WAIT_MS)
    }

    private val silenceTimeout = Runnable {
        if (phase == Phase.LISTENING && !heardSpeech && turn.isEmpty()) {
            recognizer?.cancel()
            onNoAnswer()
        }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() {
            heardSpeech = true
            handler.removeCallbacks(silenceTimeout)
        }
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onLanguageDetection(results: Bundle) {
            val language = results.getString(SpeechRecognizer.DETECTED_LANGUAGE).orEmpty()
            if (language.startsWith("ko")) detectedKorean = true
        }

        override fun onPartialResults(partialResults: Bundle?) {
            partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()?.let { partial = (turn + it).joinToString(" ") }
        }

        override fun onResults(results: Bundle?) {
            if (phase != Phase.LISTENING) return
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            onSegment(text)
        }

        override fun onError(error: Int) {
            if (phase != Phase.LISTENING) return
            val silence = error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT || error == SpeechRecognizer.ERROR_NO_MATCH
            if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                notice = "마이크 권한이 없어 대답을 들을 수 없습니다"
                finish(Outcome.ERROR)
                return
            }
            if (silence || error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                // 소리는 났지만 알아듣지 못한 경우: 무응답 대기를 다시 시작한다.
                if (turn.isEmpty() && heardSpeech) rearmNoAnswerWait()
                // 인식기 자체 무음 제한이 짧아도 8초 무응답·끝 신호 대기가 유지되도록 다시 듣는다.
                restartRecognizer()
                return
            }
            handler.removeCallbacks(silenceTimeout)
            notice = "음성 인식 오류 (코드 $error)"
            if (turn.isNotEmpty()) submitTurn() else onNoAnswer()
        }
    }

    private fun onSegment(raw: String) {
        if (raw.isBlank()) {
            if (turn.isEmpty() && heardSpeech) rearmNoAnswerWait()
            restartRecognizer()
            return
        }
        if (VoiceCommands.isKoreanHelpRequest(raw, detectedKorean)) {
            recognizer?.cancel()
            onKoreanHelp(raw)
            return
        }
        turn += raw.trim()
        // 쉬는 사이 인식 구간이 나뉘어 "I'm" / "all set"처럼 신호가 쪼개질 수 있어 이어 붙인 전체에서 찾는다.
        val joined = VoiceCommands.parseSegment(turn.joinToString(" "))
        partial = joined.text
        if (joined.endOfTurn) {
            turn.clear()
            if (joined.text.isNotBlank()) turn += joined.text
            submitTurn()
        } else {
            // 끝 신호가 나올 때까지는 아무리 오래 쉬어도 대답하지 않고 계속 듣는다.
            restartRecognizer()
        }
    }

    private fun submitTurn() {
        handler.removeCallbacks(silenceTimeout)
        recognizer?.cancel()
        val text = turn.joinToString(" ").trim()
        turn.clear()
        partial = ""
        if (text.isBlank()) onNoAnswer() else onUserSaid(text)
    }

    private fun onKoreanHelp(raw: String) {
        handler.removeCallbacks(silenceTimeout)
        partial = ""
        turn.clear()
        EventLog.add(context, "${prefix}한국어 도움 요청")
        lines += Line(fromApp = false, text = raw)
        askBrain(raw, koreanHelp = true)
    }

    private fun onUserSaid(text: String) {
        partial = ""
        reprompted = false
        lines += Line(fromApp = false, text = text)
        askBrain(text, koreanHelp = false)
    }

    private fun askBrain(text: String, koreanHelp: Boolean) {
        phase = Phase.THINKING
        brain.reply(text, koreanHelp) { reply -> handler.post { onReply(reply) } }
    }

    private fun onReply(reply: TalkBrain.Reply) {
        if (phase == Phase.ENDED || phase == Phase.SUMMARIZING) return
        updateBasicMode()
        reply.koreanHintOnScreen?.let { lines += Line(fromApp = true, text = it, hint = true) }
        say(reply.text) {
            if (reply.phase == ConversationEngine.Phase.WRAP_UP) summarizeAndFinish() else listen()
        }
    }

    private fun updateBasicMode() {
        basicModeReason = brain.fallbackReason?.userMessage
    }

    private fun summarizeAndFinish() = reviewThenFinish(Outcome.ANSWERED)

    /**
     * 사용자가 한 문장이 있고 AI 모드면 교정(문장 전체 + 핵심 3개)을 받은 뒤 끝낸다.
     * 교정 중에는 패스·그만 버튼을 숨기므로 두 번 들어오지 않는다. 기본 연습이면 바로 끝낸다.
     */
    private fun reviewThenFinish(outcome: Outcome) {
        if (phase == Phase.ENDED || phase == Phase.SUMMARIZING) return
        confirmingEnd = false
        if (!brain.canReview) {
            if (outcome == Outcome.REVIEW_REQUESTED) {
                notice = if (brain.engine.learnerTurns == 0) {
                    "아직 말한 문장이 없어 교정할 것이 없어요"
                } else {
                    "AI 키가 없거나 AI 오류로 지금은 교정할 수 없어요. 대화는 저장했으니 키를 확인한 뒤 홈 → 대화 기록 → '교정 받기'를 누르세요"
                }
            }
            finish(outcome)
            return
        }
        handler.removeCallbacksAndMessages(null)
        recognizer?.cancel()
        tts?.stop()
        partial = ""
        phase = Phase.SUMMARIZING
        brain.summarize { result ->
            handler.post {
                summary = result
                if (result == null) notice = "교정을 만들지 못했습니다. 홈 화면 '대화 기록'에서 다시 받을 수 있어요"
                finish(outcome)
            }
        }
    }

    private fun onNoAnswer() {
        partial = ""
        val started = brain.engine.learnerTurns > 0
        if (!reprompted) {
            reprompted = true
            val seed = LocalDate.now().dayOfYear
            say(if (started) Scripts.REPROMPT_MID_TALK else pack.pick(pack.reprompts, seed)) { listen() }
        } else {
            val now = ZonedDateTime.now()
            val laterToday = SchedulePolicy.retryAfter(now, null).toLocalDate() == now.toLocalDate()
            val goodbye = pack.pick(if (laterToday) pack.goodbyeRetry else pack.goodbyeTomorrow, now.hour)
            say(goodbye) { reviewThenFinish(Outcome.NO_ANSWER) }
        }
    }

    private fun vibrate() {
        val vibrator = context.getSystemService(VibratorManager::class.java).defaultVibrator
        vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 600, 400, 600, 400, 600), -1))
    }

    fun finish(requested: Outcome) {
        if (phase == Phase.ENDED) return
        confirmingEnd = false
        // 완료 기준(5분 또는 8왕복)을 채운 뒤 패스·이탈·무응답으로 끝나도 오늘 회차는 완료로 본다.
        val outcome = if (!requested.completesToday && requested != Outcome.ERROR && brain.engine.isComplete()) {
            Outcome.ANSWERED
        } else {
            requested
        }
        this.outcome = outcome
        phase = Phase.ENDED
        brain.shutdown()
        handler.removeCallbacksAndMessages(null)
        afterSpeech.clear()
        recognizer?.destroy()
        recognizer = null
        beepMuter.unmute()
        tts?.stop()
        tts?.shutdown()
        tts = null
        record(outcome)
        saveRecord(outcome)
        onEnded(outcome)
    }

    private fun saveRecord(outcome: Outcome) {
        if (brain.engine.learnerTurns == 0 && lines.none { !it.fromApp }) return
        runCatching {
            SessionStore.append(
                context,
                SessionRecord(
                    startedAt = startedAt,
                    test = test,
                    outcome = outcome.label,
                    topic = brain.engine.chosenTopic,
                    aiMode = brain.aiMode,
                    lines = lines.filter { !it.hint }.map { !it.fromApp to it.text },
                    corrections = summary?.corrections.orEmpty(),
                    praise = summary?.praise,
                ),
            )
        }.onFailure { EventLog.add(context, "${prefix}기록 저장 실패") }
    }

    private fun localeFor(text: String): Locale = if (hangul.containsMatchIn(text)) Locale.KOREAN else Locale.US

    private fun record(outcome: Outcome) {
        EventLog.add(context, "${prefix}세션 종료: ${outcome.label}")
        if (test) return
        val now = ZonedDateTime.now()
        val store = ScheduleStore(context)
        if (outcome.completesToday) {
            store.completedDate = now.toLocalDate()
            AlarmScheduler.scheduleDaily(context, SchedulePolicy.nextFirstAttempt(now, store.completedDate))
        } else {
            // 알람 도착 시 이미 재시도를 예약했지만, '1시간 뒤'가 종료 시점 기준이 되도록 다시 맞춘다.
            AlarmScheduler.scheduleDaily(context, SchedulePolicy.retryAfter(now, store.completedDate))
        }
    }

    companion object {
        private val hangul = Regex("[\uAC00-\uD7A3]")
        const val ANSWER_WAIT_MS = 8_000L
        const val TAP_TIMEOUT_MS = 30_000L
        private const val RESTART_DELAY_MS = 250L
    }
}
