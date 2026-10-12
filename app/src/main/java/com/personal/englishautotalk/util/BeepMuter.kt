package com.personal.englishautotalk.util

import android.content.Context
import android.media.AudioManager

/**
 * 음성 인식기는 듣기를 시작·끝낼 때마다 비프음을 낸다(인식기 앱이 내며 끄는 옵션이 없다).
 * 말 끝 신호가 나올 때까지 인식기를 계속 다시 켜므로, 듣는 동안에는 비프음이 나오는 스트림을 잠시 음소거한다.
 * TTS는 미디어 스트림을 쓰므로 말하기 전에 반드시 [unmute]한다.
 */
class BeepMuter(context: Context) {

    private val audio = context.getSystemService(AudioManager::class.java)
    private val muted = mutableSetOf<Int>()

    fun mute() {
        for (stream in STREAMS) {
            if (stream in muted || audio.isStreamMute(stream)) continue
            // 방해금지 중에는 알림·시스템 스트림 변경이 거부될 수 있다. 그 경우 그 스트림만 건너뛴다.
            runCatching { audio.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, 0) }
                .onSuccess { muted += stream }
        }
    }

    /** 이 클래스가 음소거한 스트림만 되돌린다(사용자가 원래 꺼 둔 스트림은 건드리지 않는다). */
    fun unmute() {
        for (stream in muted) {
            runCatching { audio.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0) }
        }
        muted.clear()
    }

    private companion object {
        /** 기기·인식기마다 비프음을 내는 스트림이 달라 셋 다 막는다. */
        val STREAMS = intArrayOf(AudioManager.STREAM_MUSIC, AudioManager.STREAM_NOTIFICATION, AudioManager.STREAM_SYSTEM)
    }
}
