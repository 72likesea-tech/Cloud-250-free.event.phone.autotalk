package com.personal.englishautotalk.schedule

import android.content.Context
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** 예약 상태. 2단계는 SharedPreferences로 충분하고, 3단계에서 대화 기록은 Room으로 옮긴다. */
class ScheduleStore(context: Context) {
    private val prefs = context.getSharedPreferences("schedule", Context.MODE_PRIVATE)

    var nextAttemptAt: ZonedDateTime?
        get() = prefs.getLong(KEY_NEXT, 0L).takeIf { it > 0 }
            ?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()) }
        set(value) = prefs.edit().putLong(KEY_NEXT, value?.toInstant()?.toEpochMilli() ?: 0L).apply()

    var completedDate: LocalDate?
        get() = prefs.getString(KEY_COMPLETED, null)?.let(LocalDate::parse)
        set(value) = prefs.edit().putString(KEY_COMPLETED, value?.toString()).apply()

    /** '오늘작동중지'를 누른 날. 그날은 말을 걸지 않는다(완료와 달리 회차 기록·완료 처리는 하지 않는다). */
    var pausedDate: LocalDate?
        get() = prefs.getString(KEY_PAUSED, null)?.let(LocalDate::parse)
        set(value) = prefs.edit().putString(KEY_PAUSED, value?.toString()).apply()

    fun isPaused(today: LocalDate) = pausedDate == today

    /**
     * 일정 계산에 쓸 '오늘은 더 말걸지 않는 날'. 오늘 작동을 중지했으면 오늘, 아니면 완료한 날.
     * SchedulePolicy는 이 날짜를 완료일로 보고 다음 날 첫 시각을 고른다.
     */
    fun skipDate(today: LocalDate): LocalDate? = if (isPaused(today)) today else completedDate

    private companion object {
        const val KEY_NEXT = "next_attempt_at"
        const val KEY_COMPLETED = "completed_date"
        const val KEY_PAUSED = "paused_date"
    }
}
