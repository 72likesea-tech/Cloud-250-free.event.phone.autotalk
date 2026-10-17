package com.personal.englishautotalk.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.personal.englishautotalk.schedule.AlarmScheduler
import com.personal.englishautotalk.schedule.RestoreReceiver
import com.personal.englishautotalk.schedule.ScheduleStore
import com.personal.englishautotalk.talk.TalkActivity
import com.personal.englishautotalk.talk.TalkMode
import com.personal.englishautotalk.util.BeepMuter
import com.personal.englishautotalk.util.DeviceState
import com.personal.englishautotalk.util.EventLog
import java.time.ZonedDateTime

/** 홈: 다음 말걸기 시각, 자동 시작에 필요한 권한·설정 상태, 검수용 테스트 버튼, 기록. */
class MainActivity : ComponentActivity() {

    // onResume마다 올려 화면의 권한 상태를 다시 읽게 한다.
    private var refresh by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AppTheme { HomeScreen(refresh) } }
    }

    override fun onResume() {
        super.onResume()
        // 강제 중지 뒤 앱을 다시 열었을 때도 예약을 복구한다.
        RestoreReceiver.restore(this, log = true)
        // 대화 중 앱이 강제 종료돼 미디어 음소거가 남았으면 되돌린다.
        if (BeepMuter.restoreLeftover(this)) EventLog.add(this, "지난번에 남은 음소거를 되돌림")
        refresh++
    }

    private fun openSettings(action: String, withPackage: Boolean = true) {
        val intent = Intent(action)
        if (withPackage) intent.data = Uri.parse("package:$packageName")
        runCatching { startActivity(intent) }
            .onFailure { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
    }

    @Composable
    private fun HomeScreen(refreshKey: Int) {
        val ctx = this
        val permissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { refresh++ }

        // refreshKey를 읽어 onResume 때마다 아래 상태가 다시 계산되게 한다.
        @Suppress("UNUSED_VARIABLE") val key = refreshKey
        val next = ScheduleStore(ctx).nextAttemptAt
        val log = EventLog.read(ctx)

        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("영어자동말걸기", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("3단계 테스트 빌드 — AI 영어 대화 한 회차", color = MaterialTheme.colorScheme.outline)

                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("다음 말걸기", style = MaterialTheme.typography.labelLarge)
                        Text(
                            next?.let { EventLog.fmt(it) } ?: "예약 없음",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text("매일 14:00 · 패스/무응답 시 1시간 뒤 · 20:00까지", color = MaterialTheme.colorScheme.outline)
                    }
                }

                AiSettingsCard(ctx) { refresh++ }

                RecognizerCard(ctx, refreshKey)

                Text("자동 시작에 필요한 설정", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                val needsRuntime = mutableListOf<String>()
                if (!DeviceState.canPostNotifications(ctx) && Build.VERSION.SDK_INT >= 33) {
                    needsRuntime += Manifest.permission.POST_NOTIFICATIONS
                }
                if (!DeviceState.canRecordAudio(ctx)) needsRuntime += Manifest.permission.RECORD_AUDIO

                StatusRow("알림", DeviceState.canPostNotifications(ctx)) {
                    if (Build.VERSION.SDK_INT >= 33) permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                }
                StatusRow("마이크", DeviceState.canRecordAudio(ctx)) {
                    permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                }
                StatusRow("전체화면 알림 (잠금화면 위 대화 화면)", DeviceState.canUseFullScreenIntent(ctx)) {
                    if (Build.VERSION.SDK_INT >= 34) openSettings(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
                }
                StatusRow("정확한 시각 알람", DeviceState.canScheduleExactAlarms(ctx)) {
                    openSettings(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                }
                StatusRow("배터리 최적화 제외", DeviceState.isIgnoringBatteryOptimizations(ctx)) {
                    @Suppress("BatteryLife")
                    openSettings(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                }
                if (needsRuntime.isNotEmpty()) {
                    Button(onClick = { permissionLauncher.launch(needsRuntime.toTypedArray()) }, modifier = Modifier.fillMaxWidth()) {
                        Text("알림·마이크 권한 한 번에 허용")
                    }
                }
                Text(
                    "삼성 절전: 설정 → 배터리 → 백그라운드 사용 제한 → '절전 예외 앱'에 영어자동말걸기 추가",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.outline,
                )
                TextButton(onClick = { openSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS) }) {
                    Text("앱 정보 설정 열기")
                }

                HorizontalDivider()
                Text("검수용 테스트", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Button(
                    onClick = {
                        AlarmScheduler.scheduleTest(ctx, ZonedDateTime.now().plusMinutes(1))
                        refresh++
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("1분 뒤 말걸기 테스트 (예약 후 화면을 끄세요)") }
                OutlinedButton(
                    onClick = { startActivity(TalkActivity.intent(ctx, TalkMode.NORMAL, test = true)) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("지금 바로 말걸기 화면 열기") }
                Text("테스트는 매일 일정(14:00)과 재시도에 영향을 주지 않습니다.", fontSize = 14.sp, color = MaterialTheme.colorScheme.outline)

                HorizontalDivider()
                RecordsSection(ctx, refreshKey) { refresh++ }

                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("이벤트 기록", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    TextButton(onClick = { EventLog.clear(ctx); refresh++ }) { Text("지우기") }
                }
                if (log.isEmpty()) Text("아직 기록이 없습니다", color = MaterialTheme.colorScheme.outline)
                log.forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
            }
        }
    }

    @Composable
    private fun StatusRow(label: String, ok: Boolean, onFix: () -> Unit) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(if (ok) "✅" else "⚠️", fontSize = 20.sp)
            Text(label, modifier = Modifier.weight(1f).padding(start = 8.dp))
            if (!ok) OutlinedButton(onClick = onFix) { Text("설정") }
        }
    }
}
