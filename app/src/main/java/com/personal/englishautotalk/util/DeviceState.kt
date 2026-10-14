package com.personal.englishautotalk.util

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

enum class RingMode { NORMAL, SILENT, DND }

/** 말걸기 동작과 온보딩 화면이 참고하는 기기 상태. */
object DeviceState {

    /** 방해금지 → DND, 무음·진동(매너) → SILENT, 그 외 NORMAL. 방해금지를 먼저 판단한다. */
    fun ringMode(context: Context): RingMode {
        val nm = context.getSystemService(NotificationManager::class.java)
        when (nm.currentInterruptionFilter) {
            NotificationManager.INTERRUPTION_FILTER_PRIORITY,
            NotificationManager.INTERRUPTION_FILTER_NONE,
            NotificationManager.INTERRUPTION_FILTER_ALARMS -> return RingMode.DND
        }
        val audio = context.getSystemService(AudioManager::class.java)
        return if (audio.ringerMode == AudioManager.RINGER_MODE_NORMAL) RingMode.NORMAL else RingMode.SILENT
    }

    /** 방해금지 '완전 무음'은 통화 외 모든 소리를 막아 음성이 나오지 않는다. */
    fun isTotalSilence(context: Context) =
        context.getSystemService(NotificationManager::class.java).currentInterruptionFilter ==
            NotificationManager.INTERRUPTION_FILTER_NONE

    fun hasPermission(context: Context, permission: String) =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    fun canPostNotifications(context: Context) =
        Build.VERSION.SDK_INT < 33 || hasPermission(context, Manifest.permission.POST_NOTIFICATIONS)

    fun canRecordAudio(context: Context) = hasPermission(context, Manifest.permission.RECORD_AUDIO)

    fun canUseFullScreenIntent(context: Context) =
        Build.VERSION.SDK_INT < 34 ||
            context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

    fun canScheduleExactAlarms(context: Context) =
        context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    fun isIgnoringBatteryOptimizations(context: Context) =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    /**
     * 이 앱이 쓰는 음성 인식기 정보(홈 화면 표시·이벤트 기록용).
     * SpeechRecognizer.createSpeechRecognizer(context)는 시스템 설정의 기본 인식 서비스를 쓴다.
     * 설정값을 읽을 수 없으면 설치된 인식 서비스 목록으로 대신 보여 준다.
     */
    fun speechRecognizerInfo(context: Context): String {
        val pm = context.packageManager
        val installed = runCatching {
            pm.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
                .map { it.serviceInfo.packageName }.distinct()
        }.getOrDefault(emptyList())
        val configured = runCatching { Settings.Secure.getString(context.contentResolver, "voice_recognition_service") }
            .getOrNull()?.substringBefore('/')?.ifBlank { null }
        val onDevice = Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        val label = { pkg: String ->
            when {
                pkg.startsWith("com.google") -> "Google ($pkg)"
                pkg.startsWith("com.samsung") -> "Samsung ($pkg)"
                else -> pkg
            }
        }
        val main = configured?.let(label)
            ?: installed.singleOrNull()?.let { label(it) + " (설치된 유일한 인식기)" }
            ?: if (installed.isEmpty()) "설치된 인식기 없음" else "기본값 미확인 — 설치됨: " + installed.joinToString(", ", transform = label)
        return main + if (onDevice) " · 기기 내 인식 가능" else ""
    }

    fun isMediaMuted(context: Context) =
        context.getSystemService(AudioManager::class.java).getStreamVolume(AudioManager.STREAM_MUSIC) == 0
}
