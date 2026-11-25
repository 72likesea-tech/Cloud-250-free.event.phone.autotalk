package com.personal.englishautotalk.schedule

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class SchedulePolicyTest {

    private val zone = ZoneId.of("Asia/Seoul")
    private val day = LocalDate.of(2026, 9, 28)

    private fun at(h: Int, m: Int = 0, d: LocalDate = day) = ZonedDateTime.of(d.year, d.monthValue, d.dayOfMonth, h, m, 0, 0, zone)

    @Test fun pauseTodaySkipsToTomorrowFirstTime() {
        val tomorrow = at(14, 0, day.plusDays(1))
        assertEquals(tomorrow, SchedulePolicy.afterPauseToday(at(9)))
        assertEquals(tomorrow, SchedulePolicy.afterPauseToday(at(15, 30)))
    }

    @Test fun pausedTodayRestoreDoesNotCatchUp() {
        // 오늘 작동을 중지했으면(완료일처럼 오늘을 넘김) 놓친 시도를 되살리지 않는다.
        assertEquals(at(14, 0, day.plusDays(1)), SchedulePolicy.restore(at(15), at(14, 30), day))
    }

    @Test fun resumeFollowsTodaysSchedule() {
        assertEquals(at(14), SchedulePolicy.afterResume(at(10), null))
        assertEquals(at(16, 30), SchedulePolicy.afterResume(at(15, 30), null))
        assertEquals(at(14, 0, day.plusDays(1)), SchedulePolicy.afterResume(at(21), null))
        assertEquals(at(14, 0, day.plusDays(1)), SchedulePolicy.afterResume(at(15), day))
    }

    @Test fun firstAttemptIsTodayBeforeTwoPm() {
        assertEquals(at(14), SchedulePolicy.nextFirstAttempt(at(9), null))
    }

    @Test fun firstAttemptIsTomorrowAfterTwoPm() {
        assertEquals(at(14, d = day.plusDays(1)), SchedulePolicy.nextFirstAttempt(at(14, 1), null))
    }

    @Test fun firstAttemptIsTomorrowWhenCompletedToday() {
        assertEquals(at(14, d = day.plusDays(1)), SchedulePolicy.nextFirstAttempt(at(9), day))
    }

    @Test fun retryIsOneHourLater() {
        assertEquals(at(15), SchedulePolicy.retryAfter(at(14), null))
        assertEquals(at(15, 7), SchedulePolicy.retryAfter(at(14, 7), null))
    }

    @Test fun lastRetryIsAtEightPm() {
        assertEquals(at(20), SchedulePolicy.retryAfter(at(19), null))
        assertEquals(at(20, 5), SchedulePolicy.retryAfter(at(19, 5), null))
    }

    @Test fun afterEightPmRetryMovesToNextDay() {
        assertEquals(at(14, d = day.plusDays(1)), SchedulePolicy.retryAfter(at(20), null))
        assertEquals(at(14, d = day.plusDays(1)), SchedulePolicy.retryAfter(at(19, 30), null))
    }

    @Test fun retryAfterCompletionMovesToNextDay() {
        assertEquals(at(14, d = day.plusDays(1)), SchedulePolicy.retryAfter(at(15), day))
    }

    @Test fun retryBeforeFirstTimeWaitsForFirstTime() {
        assertEquals(at(14), SchedulePolicy.retryAfter(at(10), null))
    }

    @Test fun restoreKeepsFutureStoredAlarm() {
        assertEquals(at(16), SchedulePolicy.restore(at(15, 30), at(16), null))
    }

    @Test fun restoreCatchesUpMissedAttemptInsideWindow() {
        assertEquals(at(16, 31), SchedulePolicy.restore(at(16, 30), at(16), null))
    }

    @Test fun restoreAfterWindowGoesToNextDay() {
        assertEquals(at(14, d = day.plusDays(1)), SchedulePolicy.restore(at(21), at(20), null))
    }

    @Test fun restoreWithoutStoredAlarmUsesFirstAttempt() {
        assertEquals(at(14, d = day.plusDays(1)), SchedulePolicy.restore(at(16), null, null))
        assertEquals(at(14), SchedulePolicy.restore(at(8), null, null))
    }

    @Test fun restoreDoesNotCatchUpWhenCompleted() {
        assertEquals(at(14, d = day.plusDays(1)), SchedulePolicy.restore(at(16, 30), at(16), day))
    }
}
