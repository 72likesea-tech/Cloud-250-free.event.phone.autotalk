package com.personal.englishautotalk.util

import android.content.Context
import android.content.Intent
import android.os.Build
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.RequiresApi
import androidx.core.content.edit

/**
 * 어떤 음성 인식기를 쓸지 고르고, 언어별 인식 모델이 휴대폰에 설치돼 있는지 확인·다운로드한다.
 *
 * 한국어로 말하면 바로 한국어 도움으로 넘어가려면, 인식기가 영어로 듣는 중에 한국어를 알아채고(언어 감지)
 * 한국어로 바꿔 받아 적어야(언어 전환) 한다. 사용자 기기의 기본 인식기(com.google.android.tts)는
 * 모델 확인 요청에 답하지 않았고(2026-10-16, '확인 불가') 한국어도 알아채지 못했다.
 * Android의 '기기 내 인식기'(createOnDeviceSpeechRecognizer)는 언어 감지·모델 다운로드를 위해 만들어진
 * 방식이라 선택할 수 있게 한다. 기본값은 지금까지 쓰던 기본 인식기다.
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

    private const val PREFS = "speech"
    private const val KEY_ON_DEVICE = "use_on_device"

    /** 이 기기에서 '기기 내 인식기'를 만들 수 있는지. */
    fun onDeviceAvailable(context: Context) = SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    /** 사용자가 '기기 내 인식기'를 골랐는지(기본값: 아니오). */
    fun useOnDevice(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ON_DEVICE, false)

    fun setUseOnDevice(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean(KEY_ON_DEVICE, value) }
    }

    /** 고른 방식으로 인식기를 만든다. 기기 내 인식기를 쓸 수 없으면 기본 인식기로 만든다. */
    fun create(context: Context, onDevice: Boolean): SpeechRecognizer =
        if (onDevice && onDeviceAvailable(context)) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }

    fun label(onDevice: Boolean) = if (onDevice) "기기 내 인식기" else "기본 인식기"

    /** 언어 하나의 상태를 확인해 콜백으로 알려 준다. 확인할 수 없으면(API 32 이하, 인식기가 답하지 않음) UNKNOWN. */
    fun check(context: Context, language: String, onDevice: Boolean, onResult: (Status) -> Unit) {
        if (Build.VERSION.SDK_INT < 33 || !SpeechRecognizer.isRecognitionAvailable(context)) {
            onResult(Status.UNKNOWN)
            return
        }
        val recognizer = runCatching { create(context, onDevice) }.getOrNull()
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
    fun requestDownload(context: Context, language: String, onDevice: Boolean): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false
        val recognizer = runCatching { create(context, onDevice) }.getOrNull() ?: return false
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
