package com.personal.englishautotalk.util

import android.content.Context
import android.media.AudioManager
import androidx.core.content.edit

/**
 * 음성 인식기는 듣기를 시작·끝낼 때마다 비프음을 낸다(인식기 앱이 내며 끄는 옵션이 없다).
 * 말 끝 신호가 나올 때까지 인식기를 계속 다시 켜므로, 듣는 동안에는 비프음이 나오는 스트림을 잠시 음소거한다.
 * TTS는 미디어 스트림을 쓰므로 말하기 전에 반드시 [unmute]한다.
 *
 * 앱이 음소거한 채로 강제 종료되면 미디어 음소거가 남아 다음 회차에 "미디어 볼륨이 0"으로 보인다.
 * 그래서 음소거한 스트림을 기기에 기록해 두고, 앱이 다시 시작할 때 [restoreLeftover]로 되돌린다.
 */
class BeepMuter(context: Context) {

    private val audio = context.getSystemService(AudioManager::class.java)
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val muted = mutableSetOf<Int>()

    /** 음소거한다. 이번에 새로 음소거한 스트림이 있으면 true(음소거가 적용될 시간을 잠깐 주는 데 쓴다). */
    fun mute(): Boolean {
        var changed = false
        for (stream in STREAMS) {
            if (stream in muted || audio.isStreamMute(stream)) continue
            // 방해금지 중에는 알림·시스템 스트림 변경이 거부될 수 있다. 그 경우 그 스트림만 건너뛴다.
            runCatching { audio.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, 0) }
                .onSuccess {
                    muted += stream
                    changed = true
                }
        }
        if (changed) save()
        return changed
    }

    /** 이 클래스가 음소거한 스트림만 되돌린다(사용자가 원래 꺼 둔 스트림은 건드리지 않는다). */
    fun unmute() {
        if (muted.isEmpty()) return
        for (stream in muted) {
            runCatching { audio.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0) }
        }
        muted.clear()
        save()
    }

    private fun save() {
        prefs.edit { putStringSet(KEY_MUTED, muted.map { it.toString() }.toSet()) }
    }

    companion object {
        /** 기기·인식기마다 비프음을 내는 스트림이 달라 셋 다 막는다. */
        private val STREAMS = intArrayOf(AudioManager.STREAM_MUSIC, AudioManager.STREAM_NOTIFICATION, AudioManager.STREAM_SYSTEM)
        private const val PREFS = "beep_muter"
        private const val KEY_MUTED = "muted_streams"

        /** 지난번에 앱이 음소거한 채 종료됐으면 그 스트림을 되돌린다. 되돌린 게 있으면 true. */
        fun restoreLeftover(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val left = prefs.getStringSet(KEY_MUTED, emptySet()).orEmpty().mapNotNull { it.toIntOrNull() }
            if (left.isEmpty()) return false
            val audio = context.getSystemService(AudioManager::class.java)
            left.forEach { stream -> runCatching { audio.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0) } }
            prefs.edit { remove(KEY_MUTED) }
            return true
        }
    }
}
