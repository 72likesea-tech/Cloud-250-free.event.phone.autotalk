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
import com.personal.englishautotalk.util.RecognizerSupport
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

/** hint = 소리 내어 읽지 않고 화면에만 보이는 한국어 힌트. koreanHelp = 한국어 도움으로 처리된 사용자 말. */
data class Line(val fromApp: Boolean, val text: String, val hint: Boolean = false, val koreanHelp: Boolean = false)

/**
 * 한 번의 말걸기 흐름: (방해금지면 터치 대기) → 첫 문장 음성 → 자동 듣기 →
 * 8초 무응답이면 1회 다시 말걸기 → 그래도 없으면 종료.
 * 영어 차례는 "I'm all set"/"Over to you"(또는 '내말끝' 버튼)로만 끝난다(듣는 동안 인식기 비프음은 음소거).
 * "Help me in Korean"이나 한국어로 말하면 끝 신호 없이, 말을 멈추면 바로 한국어로 돕는다.
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
    /** "Help me in Korean" 또는 한국어로 말해 한국어 도움을 받는 중. 다음 영어 대답을 보내면 꺼진다. */
    var koreanHelpActive by mutableStateOf(false)
        private set
    /**
     * 지금 듣고 있는 말이 어떤 언어로 받아 적히는지(화면 표시용). null = 아직 받아 적힌 말 없음.
     * true = 주로 한국어(한국어 도움으로 보냄), false = 영어.
     */
    var hearingKorean by mutableStateOf<Boolean?>(null)
        private set
    /**
     * '한국어로 말하기' 버튼으로 켠 한국어 모드. 켜져 있으면 인식기를 한국어(ko-KR)로 듣고,
     * 말을 멈추면 끝 신호 없이 한국어 도움으로 보낸다. 버튼을 다시 누를 때까지 켜져 있고(2026-10-17 사용자 요청),
     * 다시 누르면 영어 대화로 돌아간다. 예전에는 한 차례 뒤 저절로 꺼져, 영어로 돌아가려고 누르면 오히려 다시 켜졌다.
     */
    var koreanMode by mutableStateOf(false)
        private set
    /** 인식기가 이번 차례에 한국어를 감지했는지(화면 표시용). */
    var koreanDetected by mutableStateOf(false)
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
    /** 부분 인식에서 끝 신호를 본 문장. 잠깐 더 말이 이어지지 않으면 이 문장으로 바로 보낸다. */
    private var partialSignalText: String? = null
    /** 홈 화면에서 고른 인식 방식. 기기 내 인식기를 쓸 수 없으면 이 회차 동안 기본 인식기로 바꾼다. */
    private var useOnDevice = RecognizerSupport.useOnDevice(context)

    /** 영어로 듣다 한국어가 들리면 한국어로 바꿔 받아 적기(실험, 기기 내 인식기에서만 효과). */
    private val autoKorean = RecognizerSupport.autoKorean(context)
    /** 이번 차례에 마지막으로 기록한 언어 감지 결과(같은 결과를 반복 기록하지 않기 위해). */
    private var lastDetection: String? = null
    /** 무음이 아닌 인식 오류가 연달아 난 횟수. 너무 많으면 듣기를 포기한다. */
    private var consecutiveErrors = 0

    /** 연속 오류로 인식기를 새로 만든 적이 있는지(말이 들리면 풀린다). 두 번째도 실패하면 끝낸다. */
    private var recreatedAfterErrors = false
    private var submittedAt = 0L

    private val prefix get() = if (test) "[테스트] " else ""

    fun start() {
        // 지난 회차가 음소거한 채 강제 종료됐으면 먼저 되돌린다. 그래야 볼륨 0 경고가 잘못 뜨지 않는다.
        if (BeepMuter.restoreLeftover(context)) EventLog.add(context, "${prefix}지난번에 남은 음소거를 되돌림")
        EventLog.add(context, "${prefix}음성 인식기: ${DeviceState.speechRecognizerInfo(context)}")
        // 한국어로 말할 때 바로 알아채는지 살펴보려고 고른 인식기와 언어 모델 설치 상태를 남긴다.
        EventLog.add(context, "${prefix}인식 방식: ${RecognizerSupport.label(useOnDevice)}" + if (autoKorean) " · 한국어 자동 전환 켬" else "")
        RecognizerSupport.check(context, RecognizerSupport.KOREAN, useOnDevice) {
            EventLog.add(context, "${prefix}한국어 인식 모델: ${it.label}")
        }
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
            startRecognizerMuted()
            if (turn.isEmpty()) {
                heardSpeech = false
                handler.postDelayed(silenceTimeout, answerWaitMs())
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

    /**
     * 화면에 한 줄로 보여 주고 소리 내어 읽는다. 한국어 부분은 한국어 음성, 영어 부분은 영어 음성으로
     * 나눠 읽는다(SpeechText). 한국어 설명 속 영어 예문을 한국어 음성이 한국식 발음으로 읽지 않게 하기 위해서다.
     * 마지막 조각을 다 읽으면 then을 부른다.
     */
    private fun say(text: String, then: () -> Unit) {
        if (phase == Phase.ENDED || phase == Phase.SUMMARIZING) return
        phase = Phase.SPEAKING
        beepMuter.unmute()
        lines += Line(fromApp = true, text = text)
        val engine = tts ?: return
        val chunks = SpeechText.split(text).filter { chunk ->
            val result = engine.setLanguage(if (chunk.korean) Locale.KOREAN else Locale.US)
            val missing = result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED
            // 한국어 음성이 없으면 그 조각은 화면 표시만 하고, 영어 조각은 그대로 읽는다.
            if (missing && chunk.korean) notice = "한국어 음성 데이터가 없어 한국어는 화면에만 표시합니다"
            !(missing && chunk.korean)
        }
        if (chunks.isEmpty()) {
            handler.post(then)
            return
        }
        chunks.forEachIndexed { i, chunk ->
            // setLanguage는 다음 speak 요청에 적용되므로 조각마다 언어를 정하고 차례로 줄 세운다.
            engine.setLanguage(if (chunk.korean) Locale.KOREAN else Locale.US)
            val id = "u${++utteranceSeq}"
            if (i == chunks.lastIndex) afterSpeech[id] = then
            engine.speak(chunk.text, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, id)
        }
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
        koreanDetected = false
        hearingKorean = if (koreanMode) true else null
        lastDetection = null
        partial = ""
        turn.clear()
        if (confirmingEnd) {
            // 확인 창이 떠 있으면 듣기를 시작하지 않고, 창을 닫을 때 시작한다.
            resumeListening = true
            return
        }
        startRecognizerMuted()
        handler.postDelayed(silenceTimeout, answerWaitMs())
    }

    /**
     * 음소거한 뒤 듣기를 시작한다. 방금 음소거했으면 기기에 적용될 때까지 잠깐 기다린다.
     * 바로 시작하면 첫 시작 비프음이 음소거 전에 나올 수 있다.
     */
    private fun startRecognizerMuted() {
        if (beepMuter.mute()) handler.postDelayed({ startRecognizer() }, MUTE_SETTLE_MS) else startRecognizer()
    }

    private fun startRecognizer() {
        if (phase != Phase.LISTENING || confirmingEnd) return
        val r = recognizer ?: RecognizerSupport.create(context, useOnDevice).also {
            it.setRecognitionListener(listener)
            recognizer = it
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            // '한국어로 말하기'를 누르면 이 차례만 한국어로 듣는다. 한국어 문장에 섞인 영어 단어도 한국어 인식기가 받아 적는다.
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (koreanMode) "ko-KR" else "en-US")
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            // 후보를 여러 개 받아 끝 신호·한국어 도움 문장을 놓치지 않게 한다(VoiceCommands.pickBest).
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, MAX_ALTERNATIVES)
            // 문장 사이에 잠깐 쉬어도 구간을 끊지 않게 해, 다시 켜는 사이에 앞말이 잘리는 일을 줄인다.
            // 말 끝은 끝 신호(부분 인식 0.7초)나 '내말끝' 버튼으로 바로 알 수 있어 반응이 늦어지지 않는다.
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, COMPLETE_SILENCE_MS)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, POSSIBLY_COMPLETE_SILENCE_MS)
        if (Build.VERSION.SDK_INT >= 33 && !koreanMode) {
            // 자주 쓰는 명령 문장을 더 잘 알아듣도록 인식기에 알려 준다(지원하는 인식기에서만 효과).
            intent.putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, VoiceCommands.BIASING_PHRASES)
        }
        if (Build.VERSION.SDK_INT >= 34 && !koreanMode) {
            // 영어로 듣는 중에 한국어가 들리면 알려 달라고 언어 감지만 요청한다(화면 표시·보조 판단용, 지원하는 인식기만).
            // 말하는 도중 한국어로 바꿔 받아 적는 '언어 전환'은 끈다. 한국어는 '한국어로 말하기' 버튼으로 듣고(2026-10-17),
            // 전환을 켜 두면 한국식 억양 영어가 한글로 받아 적혀 영어 대화가 한국어 도움으로 바뀔 수 있기 때문이다.
            intent.putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true)
                .putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES, arrayListOf("en-US", "ko-KR"))
            if (autoKorean && useOnDevice) {
                // 실험 옵션(홈 '음성 인식기' 카드): 한국어가 '매우 확신'으로 감지되면 그 뒤부터 한국어로 받아 적는다.
                // 그러면 한국어 단어 수가 영어보다 많아져 버튼 없이도 한국어 도움으로 넘어간다(VoiceCommands.isKoreanHelpRequest).
                intent.putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH, RecognizerIntent.LANGUAGE_SWITCH_HIGH_PRECISION)
                    .putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES, arrayListOf("en-US", "ko-KR"))
            }
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
        handler.postDelayed(silenceTimeout, answerWaitMs())
    }

    /**
     * 첫 대답 전에는 8초 무응답이면 1회 다시 말걸기(자리에 없는지 확인). 대화가 시작된 뒤에는
     * 사용자가 생각할 시간을 주려고 아무 말도 하지 않고 기다리며, 아주 오래 조용할 때만 조용히 끝낸다.
     * '한국어로 말하기'를 켠 뒤에도 같다: 질문을 생각하는 사이 8초가 지나 다시 말걸기·종료로 가면 안 된다(2026-10-26 사용자 보고).
     */
    private fun answerWaitMs() = if (learnerSpoke || koreanMode) MID_TALK_IDLE_MS else ANSWER_WAIT_MS

    /** 사용자가 이 회차에서 한 번이라도 말했는지(한국어 도움 요청 포함). 했다면 자리에 있는 것이다. */
    private val learnerSpoke get() = lines.any { !it.fromApp }

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
            consecutiveErrors = 0
            recreatedAfterErrors = false
            handler.removeCallbacks(silenceTimeout)
        }
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onLanguageDetection(results: Bundle) {
            // 언어 감지는 API 34부터 요청하므로 그 아래에서는 오지 않는다.
            if (Build.VERSION.SDK_INT < 34) return
            val language = results.getString(SpeechRecognizer.DETECTED_LANGUAGE).orEmpty()
            // 억양 때문에 영어를 한국어로 잘못 감지하는 일을 막으려고 '매우 확신'일 때만 한국어로 본다.
            val level = results.getInt(SpeechRecognizer.LANGUAGE_DETECTION_CONFIDENCE_LEVEL)
            // 한국어로 말해도 바로 대답하지 않는 원인을 찾으려고, 차례마다 감지 결과가 바뀔 때 기록한다.
            val seen = "$language/$level"
            if (seen != lastDetection) {
                lastDetection = seen
                EventLog.add(context, "${prefix}언어 감지: ${language.ifBlank { "?" }} (확신 $level/3)")
            }
            if (language.startsWith("ko") && level >= SpeechRecognizer.LANGUAGE_DETECTION_CONFIDENCE_LEVEL_HIGHLY_CONFIDENT) {
                detectedKorean = true
                koreanDetected = true
                // 한국어로 말하는 중이면 끝 신호 없이, 말을 멈추면 바로 한국어 도움으로 보낸다.
                if (phase == Phase.LISTENING && partial.isNotBlank()) scheduleQuickSend(partial, KOREAN_PAUSE_MS)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (phase != Phase.LISTENING) return
            val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.takeIf { it.isNotEmpty() }?.let(VoiceCommands::pickBest) ?: return
            val joined = (turn + text).joinToString(" ")
            partial = joined
            hearingKorean = koreanMode || VoiceCommands.isKoreanHelpRequest(joined, detectedKorean)
            // 인식기가 문장 끝을 확정할 때까지(보통 1~2초) 기다리지 않고, 부분 결과가 끝 신호로 끝나고
            // 잠깐 더 말이 없으면 바로 보낸다. 그 뒤 말이 이어지면("I'm all set for…") 취소된다.
            handler.removeCallbacks(partialSignalSubmit)
            partialSignalText = null
            when {
                // 한국어 모드: 끝 신호 없이, 말을 멈추면 바로 한국어 도움으로 보낸다.
                koreanMode -> scheduleQuickSend(joined, KOREAN_PAUSE_MS)
                VoiceCommands.parseSegment(joined).endOfTurn -> scheduleQuickSend(joined, PARTIAL_SIGNAL_MS)
                // 한국어로 말하거나 "Help me in Korean"이라고 하면 끝 신호 없이, 말을 멈추면 바로 보낸다.
                VoiceCommands.isKoreanHelpRequest(joined, detectedKorean) -> scheduleQuickSend(joined, KOREAN_PAUSE_MS)
            }
        }

        override fun onResults(results: Bundle?) {
            if (phase != Phase.LISTENING) return
            handler.removeCallbacks(partialSignalSubmit)
            partialSignalText = null
            consecutiveErrors = 0
            recreatedAfterErrors = false
            val text = VoiceCommands.pickBest(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty())
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
            if (useOnDevice && error in ON_DEVICE_UNAVAILABLE_ERRORS) {
                // 기기 내 인식기에 영어 모델이 없거나 쓸 수 없으면, 이 회차는 기본 인식기로 바꿔 계속 듣는다.
                useOnDevice = false
                recreateRecognizer()
                notice = "기기 내 인식기를 쓸 수 없어 기본 인식기로 바꿨어요(코드 $error). 홈 화면에서 인식 모델을 내려받아 보세요"
                EventLog.add(context, "${prefix}기기 내 인식기 오류 $error — 기본 인식기로 전환")
                restartRecognizer()
                return
            }
            if (koreanMode && error in LANGUAGE_UNAVAILABLE_ERRORS) {
                // 이 인식기가 한국어를 들을 수 없으면 대화를 끝내지 않고 영어 듣기로 돌아간다.
                koreanMode = false
                hearingKorean = null
                recreateRecognizer()
                notice = "이 인식기는 한국어를 들을 수 없어요(코드 $error). 홈 화면에서 '기기 내 인식기'를 골라 보세요"
                EventLog.add(context, "${prefix}한국어 듣기 오류 $error — 영어 듣기로 돌아감")
                restartRecognizer()
                return
            }
            if (silence || error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                // 소리는 났지만 알아듣지 못한 경우: 무응답 대기를 다시 시작한다.
                if (turn.isEmpty() && heardSpeech) rearmNoAnswerWait()
                // 인식기 자체 무음 제한이 짧아도 8초 무응답·끝 신호 대기가 유지되도록 다시 듣는다.
                restartRecognizer()
                return
            }
            notice = "음성 인식 오류 (코드 $error)"
            // 원인을 찾을 수 있게 코드를 남긴다. 연속 오류는 다시 들을 때마다 한 줄씩 쌓인다.
            EventLog.add(context, "${prefix}음성 인식 오류 $error" + if (koreanMode) " (한국어로 듣는 중)" else "")
            if ((learnerSpoke || koreanMode) && ++consecutiveErrors < MAX_CONSECUTIVE_ERRORS) {
                // 대화 중이거나 한국어 질문을 받는 중에는 오류 한 번으로 끝내지 않고 다시 듣는다. 하던 말은 유지된다.
                restartRecognizer()
                return
            }
            if (koreanMode) {
                // 한국어 듣기에서 오류가 계속되면 대화를 끝내지 않고 영어 듣기로 돌아가 다시 시도한다.
                koreanMode = false
                brain.engine.setKoreanMode(false)
                hearingKorean = null
                consecutiveErrors = 0
                recreateRecognizer()
                notice = "한국어 듣기에서 오류가 계속돼 영어 듣기로 돌아왔어요(코드 $error)"
                EventLog.add(context, "${prefix}한국어 듣기 오류 반복 — 영어 듣기로 돌아감")
                restartRecognizer()
                return
            }
            if (learnerSpoke && !recreatedAfterErrors) {
                // 대화 중 오류가 이어지면(한국어에서 영어로 돌아온 직후 등) 인식기를 새로 만들어 한 번 더 듣는다.
                // 그래도 계속되면 그때 끝낸다. 아무 말도 안 들리면 90초 무응답으로 끝나도록 대기를 다시 건다.
                recreatedAfterErrors = true
                consecutiveErrors = 0
                recreateRecognizer()
                EventLog.add(context, "${prefix}음성 인식 오류 반복 — 인식기를 새로 만들어 다시 듣기")
                if (turn.isEmpty()) rearmNoAnswerWait()
                restartRecognizer()
                return
            }
            handler.removeCallbacks(silenceTimeout)
            if (turn.isNotEmpty()) submitTurn() else onNoAnswer()
        }
    }

    private fun onSegment(raw: String) {
        if (raw.isBlank()) {
            if (turn.isEmpty() && heardSpeech) rearmNoAnswerWait()
            restartRecognizer()
            return
        }
        if (koreanMode) {
            // 한국어 모드: 말을 멈춰 한 구간이 끝나면 하던 말까지 합쳐 바로 한국어 도움으로 보낸다.
            recognizer?.cancel()
            onKoreanHelp((turn + raw.trim()).joinToString(" "))
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
        hearingKorean = VoiceCommands.isKoreanHelpRequest(turn.joinToString(" "), detectedKorean)
        if (joined.endOfTurn) {
            turn.clear()
            if (joined.text.isNotBlank()) turn += joined.text
            submitTurn()
        } else {
            // 끝 신호가 나올 때까지는 아무리 오래 쉬어도 대답하지 않고 계속 듣는다.
            restartRecognizer()
        }
    }

    /** 잠깐(delayMs) 더 말이 이어지지 않으면 text를 바로 보낸다. 새 부분 결과가 오면 다시 잰다. */
    private fun scheduleQuickSend(text: String, delayMs: Long) {
        handler.removeCallbacks(partialSignalSubmit)
        partialSignalText = text
        handler.postDelayed(partialSignalSubmit, delayMs)
    }

    private val partialSignalSubmit = Runnable {
        val text = partialSignalText ?: return@Runnable
        partialSignalText = null
        if (phase == Phase.LISTENING && !confirmingEnd) sendNow(text)
    }

    /**
     * '한국어로 말하기' 버튼: 누르면 이번 차례를 한국어로 듣고, 다시 누르면 영어로 돌아간다.
     * 켜 두면 여러 차례 계속 한국어로 듣는다. Mia가 말하거나 생각하는 중에 누르면 다음 듣기부터 바뀐다. 하던 말은 유지한다.
     */
    fun toggleKoreanMode() {
        if (phase == Phase.ENDED || phase == Phase.SUMMARIZING || phase == Phase.WAITING_TAP || confirmingEnd) return
        koreanMode = !koreanMode
        // 한국어 모드였던 시간은 영어 대화 시간(5분 완료·10분 마무리)에서 뺀다.
        brain.engine.setKoreanMode(koreanMode)
        EventLog.add(context, "${prefix}${if (koreanMode) "한국어로 말하기 켬" else "한국어로 말하기 끔"}")
        // 같은 인식기 객체에 언어만 바꿔 다시 켜면 인식기에 따라 오류가 날 수 있어, 언어가 바뀌면 새로 만든다.
        recreateRecognizer()
        if (phase == Phase.LISTENING) {
            handler.removeCallbacks(partialSignalSubmit)
            partialSignalText = null
            hearingKorean = if (koreanMode) true else null
            // 바뀌기 전 언어로 받아 적던 중간 결과는 버린다(하던 말 turn은 유지).
            partial = turn.joinToString(" ")
            // 한국어 모드로 바꾸면 질문을 생각할 시간을 주고(90초), 영어로 돌아오면 원래 대기 시간으로 되돌린다.
            handler.removeCallbacks(silenceTimeout)
            if (turn.isEmpty()) handler.postDelayed(silenceTimeout, answerWaitMs())
            restartRecognizer()
        }
    }

    /** 쓰던 인식기를 버리고 다음 듣기에서 새로 만들게 한다. */
    private fun recreateRecognizer() {
        recognizer?.let { r ->
            runCatching { r.cancel() }
            runCatching { r.destroy() }
        }
        recognizer = null
    }

    /** '내 말 끝' 버튼: 끝 신호를 말한 것과 같다. 지금까지 들은 말(인식 중인 부분 포함)을 바로 보낸다. */
    fun finishMyTurn() {
        if (phase != Phase.LISTENING || confirmingEnd) return
        if (partial.isBlank() && turn.isEmpty()) {
            notice = NOTHING_HEARD
            return
        }
        if (notice == NOTHING_HEARD) notice = null
        sendNow(partial.ifBlank { turn.joinToString(" ") })
    }

    private fun sendNow(raw: String) {
        handler.removeCallbacks(partialSignalSubmit)
        recognizer?.cancel()
        if (koreanMode || VoiceCommands.isKoreanHelpRequest(raw, detectedKorean)) {
            onKoreanHelp(raw)
            return
        }
        turn.clear()
        VoiceCommands.parseSegment(raw).text.takeIf { it.isNotBlank() }?.let { turn += it }
        submitTurn()
    }

    private fun submitTurn() {
        handler.removeCallbacks(silenceTimeout)
        recognizer?.cancel()
        val text = turn.joinToString(" ").trim()
        turn.clear()
        partial = ""
        when {
            text.isNotBlank() -> onUserSaid(text)
            // 끝 신호만 말했거나 아무것도 못 들었으면, 대화 중에는 끝내지 않고 계속 듣는다.
            learnerSpoke && phase == Phase.LISTENING -> {
                notice = NOTHING_HEARD
                rearmNoAnswerWait()
                restartRecognizer()
            }
            else -> onNoAnswer()
        }
    }

    private fun onKoreanHelp(raw: String) {
        handler.removeCallbacks(silenceTimeout)
        partial = ""
        turn.clear()
        EventLog.add(context, "${prefix}한국어 도움 요청" + if (koreanMode) "(한국어로 말하기)" else "")
        koreanHelpActive = true
        lines += Line(fromApp = false, text = raw, koreanHelp = true)
        askBrain(raw, koreanHelp = true)
    }

    private fun onUserSaid(text: String) {
        partial = ""
        koreanHelpActive = false
        reprompted = false
        lines += Line(fromApp = false, text = text)
        askBrain(text, koreanHelp = false)
    }

    private fun askBrain(text: String, koreanHelp: Boolean) {
        phase = Phase.THINKING
        submittedAt = SystemClock.elapsedRealtime()
        brain.reply(text, koreanHelp) { reply -> handler.post { onReply(reply) } }
    }

    private fun onReply(reply: TalkBrain.Reply) {
        if (phase == Phase.ENDED || phase == Phase.SUMMARIZING) return
        updateBasicMode()
        EventLog.add(context, "${prefix}대답 준비 ${"%.1f".format((SystemClock.elapsedRealtime() - submittedAt) / 1000.0)}초")
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
        if (learnerSpoke || koreanMode) {
            // 대화 중에는 재촉하는 말을 하지 않는다. 오래 자리를 비운 것으로 보고 말없이 끝낸다(교정은 만든다).
            recognizer?.cancel()
            EventLog.add(context, "${prefix}대화 중 ${MID_TALK_IDLE_MS / 1000}초 무응답 — 조용히 종료")
            reviewThenFinish(Outcome.NO_ANSWER)
        } else if (!reprompted) {
            reprompted = true
            val seed = LocalDate.now().dayOfYear
            say(pack.pick(pack.reprompts, seed)) { listen() }
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
        const val ANSWER_WAIT_MS = 8_000L
        private const val NOTHING_HEARD = "아직 들은 말이 없어요. 말한 뒤 끝 신호를 말하거나 버튼을 눌러 주세요"
        /** 대화가 시작된 뒤 아무 말 없이 이만큼 지나면 자리를 비운 것으로 보고 조용히 끝낸다. */
        const val MID_TALK_IDLE_MS = 90_000L
        /** 부분 인식이 끝 신호로 끝난 뒤 이만큼 말이 이어지지 않으면 바로 보낸다. */
        const val PARTIAL_SIGNAL_MS = 700L
        /**
         * 한국어로 말할 때(또는 "Help me in Korean") 말을 멈춘 뒤 이만큼 지나면 끝 신호 없이 보낸다.
         * 영어 끝 신호(0.7초)보다 길게 잡아, 한국어로 생각하며 잠깐 쉬는 사이에 끊기지 않게 한다.
         */
        const val KOREAN_PAUSE_MS = 1_200L
        private const val MAX_CONSECUTIVE_ERRORS = 5

        /** ERROR_LANGUAGE_NOT_SUPPORTED(12)·ERROR_LANGUAGE_UNAVAILABLE(13, API 33): 요청한 언어를 이 인식기가 못 듣는다. */
        private val LANGUAGE_UNAVAILABLE_ERRORS = setOf(12, 13)
        /**
         * 기기 내 인식기를 쓸 수 없다는 뜻의 오류: 언어 미지원(12)·언어 모델 없음(13)·인식 서비스 연결 끊김(11).
         * 이때는 기본 인식기로 바꾼다. 다시 켜는 사이에 잠깐 나는 클라이언트 오류(5)는 넣지 않는다.
         */
        private val ON_DEVICE_UNAVAILABLE_ERRORS = setOf(
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
        )
        const val TAP_TIMEOUT_MS = 30_000L
        private const val RESTART_DELAY_MS = 250L
        private const val MUTE_SETTLE_MS = 300L
        private const val MAX_ALTERNATIVES = 5
        private const val COMPLETE_SILENCE_MS = 2_500L
        private const val POSSIBLY_COMPLETE_SILENCE_MS = 2_000L
    }
}
