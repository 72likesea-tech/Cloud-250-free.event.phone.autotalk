package com.personal.englishautotalk.schedule

import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

/** 말걸기 일정 규칙. requirements.md ③ 참고. */
data class ScheduleConfig(
    val firstTime: LocalTime = LocalTime.of(14, 0),
    val lastTime: LocalTime = LocalTime.of(20, 0),
    val retryInterval: Duration = Duration.ofHours(1),
    /** 19:05에 패스하면 20:05 재시도를 허용하도록 마지막 시각에 주는 여유. */
    val lastGrace: Duration = Duration.ofMinutes(10),
    /** 재부팅 등으로 놓친 시도를 복구할 때 지금으로부터의 지연. */
    val catchUpDelay: Duration = Duration.ofMinutes(1),
)

object SchedulePolicy {

    /** 오늘 첫 시각 전이고 아직 완료하지 않았으면 오늘 첫 시각, 아니면 내일 첫 시각. */
    fun nextFirstAttempt(
        now: ZonedDateTime,
        completedDate: LocalDate?,
        c: ScheduleConfig = ScheduleConfig(),
    ): ZonedDateTime {
        val today = now.toLocalDate()
        val todayFirst = ZonedDateTime.of(today, c.firstTime, now.zone)
        return if (completedDate != today && now.isBefore(todayFirst)) {
            todayFirst
        } else {
            ZonedDateTime.of(today.plusDays(1), c.firstTime, now.zone)
        }
    }

    /** 패스·무응답·무음·DND 무반응 뒤의 다음 시도. 마지막 시각을 넘기면 다음 날 첫 시각. */
    fun retryAfter(
        now: ZonedDateTime,
        completedDate: LocalDate?,
        c: ScheduleConfig = ScheduleConfig(),
    ): ZonedDateTime {
        val today = now.toLocalDate()
        if (completedDate == today) return nextFirstAttempt(now, completedDate, c)
        val todayFirst = ZonedDateTime.of(today, c.firstTime, now.zone)
        if (now.isBefore(todayFirst)) return todayFirst
        val candidate = now.plus(c.retryInterval)
        val deadline = ZonedDateTime.of(today, c.lastTime, now.zone).plus(c.lastGrace)
        return if (candidate.toLocalDate() == today && !candidate.isAfter(deadline)) {
            candidate
        } else {
            ZonedDateTime.of(today.plusDays(1), c.firstTime, now.zone)
        }
    }

    /** '오늘작동중지': 오늘 남은 시도를 모두 건너뛰고 내일 첫 시각에 말을 건다. */
    fun afterPauseToday(now: ZonedDateTime, c: ScheduleConfig = ScheduleConfig()): ZonedDateTime =
        nextFirstAttempt(now, now.toLocalDate(), c)

    /**
     * '작동새로시작': 오늘 다시 말을 걸게 한다. 14:00 전이면 오늘 14:00,
     * 14:00~20:00 사이면 패스와 같이 1시간 뒤, 그 뒤나 오늘 이미 완료했으면 내일 14:00.
     */
    fun afterResume(now: ZonedDateTime, completedDate: LocalDate?, c: ScheduleConfig = ScheduleConfig()): ZonedDateTime =
        retryAfter(now, completedDate, c)

    /**
     * 재부팅·시간 변경·앱 업데이트·앱 실행 시 다시 등록할 시각.
     * 저장된 예약이 아직 미래면 그대로, 오늘 시도 시간대에 놓친 예약이 있으면 곧바로 한 번 시도한다.
     */
    fun restore(
        now: ZonedDateTime,
        stored: ZonedDateTime?,
        completedDate: LocalDate?,
        c: ScheduleConfig = ScheduleConfig(),
    ): ZonedDateTime {
        if (stored != null && stored.isAfter(now)) return stored
        val today = now.toLocalDate()
        val todayFirst = ZonedDateTime.of(today, c.firstTime, now.zone)
        val deadline = ZonedDateTime.of(today, c.lastTime, now.zone).plus(c.lastGrace)
        val inWindow = !now.isBefore(todayFirst) && !now.isAfter(deadline)
        if (stored != null && completedDate != today && inWindow) {
            return now.plus(c.catchUpDelay)
        }
        return nextFirstAttempt(now, completedDate, c)
    }
}
