package com.personal.englishautotalk.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.personal.englishautotalk.talk.TalkMode
import com.personal.englishautotalk.talk.TalkNotifier
import com.personal.englishautotalk.util.DeviceState
import com.personal.englishautotalk.util.EventLog
import com.personal.englishautotalk.util.RingMode
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AlarmScheduler.ACTION_FIRE) return
        val test = intent.getBooleanExtra(AlarmScheduler.EXTRA_TEST, false)
        val scheduledAt = intent.getLongExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, 0L)
        val now = ZonedDateTime.now()
        val prefix = if (test) "[테스트] " else ""
        val delaySec = if (scheduledAt > 0) (now.toInstant().toEpochMilli() - scheduledAt) / 1000 else 0
        val scheduled = Instant.ofEpochMilli(scheduledAt).atZone(ZoneId.systemDefault())
        EventLog.add(context, "${prefix}알람 도착 (예약 ${EventLog.fmt(scheduled)}, 지연 ${delaySec}초)")

        if (!test) {
            val store = ScheduleStore(context)
            // 대화 중 앱이 종료돼도 재시도가 남도록, 말을 걸기 전에 다음 시도를 먼저 예약한다.
            // 회차를 완료하면 TalkSession이 다음 날 첫 시각으로 덮어쓴다.
            AlarmScheduler.scheduleDaily(context, SchedulePolicy.retryAfter(now, store.skipDate(now.toLocalDate())))
            if (store.isPaused(now.toLocalDate())) {
                EventLog.add(context, "오늘 작동 중지됨 — 말걸기 생략")
                return
            }
            if (store.completedDate == now.toLocalDate()) {
                EventLog.add(context, "오늘 회차 완료됨 — 말걸기 생략")
                return
            }
        }

        when (DeviceState.ringMode(context)) {
            RingMode.SILENT -> EventLog.add(context, "${prefix}무음/매너 모드 — 말걸기 건너뜀")
            RingMode.DND -> TalkNotifier.show(context, TalkMode.DND, test)
            RingMode.NORMAL -> TalkNotifier.show(context, TalkMode.NORMAL, test)
        }
    }
}
