package com.personal.englishautotalk.util

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

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

    /** 기기의 기본 음성 인식 서비스(예: Google, Samsung). 인식 정확도 문제를 살펴볼 때 이벤트 기록에 남긴다. */
    fun speechRecognizerName(context: Context): String =
        runCatching { Settings.Secure.getString(context.contentResolver, "voice_recognition_service") }.getOrNull()
            ?.substringBefore('/')?.ifBlank { null } ?: "알 수 없음"

    fun isMediaMuted(context: Context) =
        context.getSystemService(AudioManager::class.java).getStreamVolume(AudioManager.STREAM_MUSIC) == 0
}
