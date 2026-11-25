package com.personal.englishautotalk.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.personal.englishautotalk.util.EventLog
import java.time.LocalDate
import java.time.ZonedDateTime

/** 재부팅·시간/시간대 변경·앱 업데이트·정확 알람 권한 변경 후 예약을 다시 등록한다. */
class RestoreReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val reason = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> "재부팅"
            Intent.ACTION_TIME_CHANGED -> "시간 변경"
            Intent.ACTION_TIMEZONE_CHANGED -> "시간대 변경"
            Intent.ACTION_MY_PACKAGE_REPLACED -> "앱 업데이트"
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED" -> "정확 알람 권한 변경"
            else -> return
        }
        EventLog.add(context, "예약 복원 ($reason)")
        restore(context)
    }

    companion object {
        fun restore(context: Context, log: Boolean = true) {
            val store = ScheduleStore(context)
            val now = ZonedDateTime.now()
            // 오늘 작동을 중지했으면 놓친 시도를 되살리지 않는다(완료한 날과 같게 본다).
            val target = SchedulePolicy.restore(now, store.nextAttemptAt, store.skipDate(now.toLocalDate()))
            AlarmScheduler.scheduleDaily(context, target, log)
        }

        /** 홈 '오늘작동중지': 오늘은 더 말걸지 않고 내일 첫 시각으로 예약한다. */
        fun pauseToday(context: Context) {
            val now = ZonedDateTime.now()
            ScheduleStore(context).pausedDate = now.toLocalDate()
            EventLog.add(context, "오늘작동중지 — 오늘은 말걸지 않음")
            AlarmScheduler.scheduleDaily(context, SchedulePolicy.afterPauseToday(now))
        }

        /** 홈 '작동새로시작': 중지를 풀고 오늘 남은 일정대로 다시 말을 걸게 한다. */
        fun resume(context: Context) {
            val now = ZonedDateTime.now()
            val store = ScheduleStore(context)
            store.pausedDate = null
            EventLog.add(context, "작동새로시작 — 다시 말걸기")
            AlarmScheduler.scheduleDaily(context, SchedulePolicy.afterResume(now, store.completedDate))
        }

        fun isPausedToday(context: Context, today: LocalDate = LocalDate.now()) = ScheduleStore(context).isPaused(today)
    }
}
