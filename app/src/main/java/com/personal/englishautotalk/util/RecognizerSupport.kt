package com.personal.englishautotalk.util

import android.content.Context
import android.content.Intent
import android.os.Build
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.RequiresApi

/**
 * 휴대폰 음성 인식기에 언어별 인식 모델이 설치돼 있는지 확인하고, 없으면 다운로드를 요청한다(API 33+).
 *
 * 한국어로 말하면 바로 한국어 도움으로 넘어가려면, 인식기가 영어로 듣는 중에 한국어를 알아채고(언어 감지)
 * 한국어로 바꿔 받아 적어야(언어 전환) 한다. 기기 안에서 인식하는 Google 인식기는 이때 한국어 모델이
 * 휴대폰에 설치돼 있어야 할 수 있다. 설치 여부를 홈 화면과 이벤트 기록에 보여 주고, 다운로드 버튼을 준다.
 * 모든 호출은 메인 스레드에서 한다(SpeechRecognizer 요구 사항).
 */
object RecognizerSupport {

    enum class Status(val label: String) {
        INSTALLED("설치됨"),
        DOWNLOADING("다운로드 중"),
        NOT_INSTALLED("미설치 — 다운로드 가능"),
        ONLINE_ONLY("인터넷 인식만 가능"),
        UNSUPPORTED("지원 안 함"),
        UNKNOWN("확인 불가"),
    }

    const val KOREAN = "ko-KR"
    const val ENGLISH = "en-US"

    /** 언어 하나의 상태를 확인해 콜백으로 알려 준다. 확인할 수 없는 기기(API 32 이하 등)면 UNKNOWN. */
    fun check(context: Context, language: String, onResult: (Status) -> Unit) {
        if (Build.VERSION.SDK_INT < 33 || !SpeechRecognizer.isRecognitionAvailable(context)) {
            onResult(Status.UNKNOWN)
            return
        }
        val recognizer = runCatching { SpeechRecognizer.createSpeechRecognizer(context) }.getOrNull()
        if (recognizer == null) {
            onResult(Status.UNKNOWN)
            return
        }
        var done = false
        fun finish(status: Status) {
            if (done) return
            done = true
            runCatching { recognizer.destroy() }
            onResult(status)
        }
        runCatching {
            recognizer.checkRecognitionSupport(
                intentFor(language),
                context.mainExecutor,
                object : RecognitionSupportCallback {
                    override fun onSupportResult(support: RecognitionSupport) = finish(statusOf(support, language))
                    override fun onError(error: Int) = finish(Status.UNKNOWN)
                },
            )
        }.onFailure { finish(Status.UNKNOWN) }
    }

    /** 인식기에 이 언어의 기기 내 인식 모델을 내려받으라고 요청한다. 진행은 인식기 앱이 알아서 한다. */
    fun requestDownload(context: Context, language: String): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false
        val recognizer = runCatching { SpeechRecognizer.createSpeechRecognizer(context) }.getOrNull() ?: return false
        val ok = runCatching { recognizer.triggerModelDownload(intentFor(language)) }.isSuccess
        recognizer.destroy()
        return ok
    }

    private fun intentFor(language: String) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)

    @RequiresApi(33)
    private fun statusOf(support: RecognitionSupport, language: String): Status {
        // "ko-KR", "ko_KR", "ko" 처럼 표기가 달라도 같은 언어로 본다.
        val base = language.substringBefore('-').lowercase()
        fun List<String>.has() = any { it.lowercase().replace('_', '-').substringBefore('-') == base }
        return when {
            support.installedOnDeviceLanguages.has() -> Status.INSTALLED
            support.pendingOnDeviceLanguages.has() -> Status.DOWNLOADING
            support.supportedOnDeviceLanguages.has() -> Status.NOT_INSTALLED
            support.onlineLanguages.has() -> Status.ONLINE_ONLY
            else -> Status.UNSUPPORTED
        }
    }
}
