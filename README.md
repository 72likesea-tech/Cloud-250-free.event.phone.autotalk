# 영어자동말걸기

매일 정해진 시각에 휴대폰이 먼저 영어로 말을 걸어 대화 연습을 시키는 개인용 Android 앱.

- **인계 문서: [`HANDOFF.md`](HANDOFF.md)** — 새 세션은 여기부터
- 요구사항: [`requirements.md`](requirements.md)
- 원본 문서: [`docs/work-order.md`](docs/work-order.md), [`docs/ai-role-prompts.md`](docs/ai-role-prompts.md)
- 대화 콘텐츠: [`docs/content/`](docs/content) · Gemini 엔진 조사·사양: [`docs/gemini/`](docs/gemini)
- 검수 안내: [`docs/stage3-test-guide.md`](docs/stage3-test-guide.md) (현재) · [`docs/stage2-test-guide.md`](docs/stage2-test-guide.md)

## 빌드

```bash
./scripts/setup-android-sdk.sh   # 클라우드 세션에서는 SessionStart 훅이 자동 실행
./gradlew testDebugUnitTest assembleDebug
# 결과: app/build/outputs/apk/debug/app-debug.apk
```

Kotlin 2.2 · Jetpack Compose · AGP 8.13 · compile/target SDK 36 · minSdk 31.

## 구조

| 경로 | 역할 |
| --- | --- |
| `schedule/SchedulePolicy.kt` | 14:00 첫 시도, 1시간 재시도, 20:00 마감, 복원 규칙 (단위 테스트 있음) |
| `schedule/AlarmScheduler.kt` | `AlarmManager.setAlarmClock` 정확 알람 등록 (매일 1개 + 테스트 1개) |
| `schedule/AlarmReceiver.kt` | 알람 수신 → 재시도 선예약 → 무음/방해금지 판단 → 말걸기 |
| `schedule/RestoreReceiver.kt` | 재부팅·시간 변경·업데이트 후 예약 복원 |
| `talk/TalkNotifier.kt` | 전체화면 인텐트 알림으로 잠금화면 위에 대화 화면 표시 |
| `talk/TalkActivity.kt`, `talk/TalkSession.kt` | 잠금화면 위 대화 화면, 말하기(TTS) → 듣기(STT) → 무응답 재말걸기 상태 머신 |
| `talk/TalkBrain.kt`, `talk/ConversationEngine.kt` | Gemini 응답·완료 판단(5분/8왕복, 10분 마무리), 오류 시 기본 연습 전환, 종료 요약 |
| `talk/ContentPack.kt`, `assets/content_pack.json` | 오프닝·토론 질문·재말걸기·한국어 힌트 |
| `talk/VoiceCommands.kt` | "I'm all set"/"Over to you" 끝 신호, "Help me in Korean"/한국어 감지 |
| `ai/GeminiClient.kt`, `ai/GeminiProtocol.kt` | REST 호출·재시도(최대 2회), 요청/응답 JSON, 오류 분류 |
| `ai/AiSettings.kt` | API 키 Keystore 암호화 저장, 모델 선택 |
| `ai/Reviewer.kt` | 대화 전문 → 문장 전체 교정 + 핵심 3개 (회차 종료 직후와 기록 화면 '교정 받기' 공용) |
| `record/SessionStore.kt` | 회차 기록(기기 내 파일), 교정 다시 저장 |
| `ui/ReviewContent.kt` | 교정 표시(종료 화면·기록 화면 공용) |
| `assets/prompts/` | 대화·요약 시스템 프롬프트, 요약 JSON 스키마 |
| `ui/MainActivity.kt`, `ui/HomeSections.kt` | 다음 예약, AI 설정·연결 테스트, 권한 안내, 테스트 버튼, 대화 기록 |

## 서명
`keystore/debug.keystore`는 테스트 빌드 전용 공용 디버그 키(비밀번호는 Android 기본값)다. 컨테이너가 바뀌어도 같은 서명으로 업데이트 설치가 되도록 저장소에 둔다. 최종 배포용 릴리스 키는 5단계에서 사용자가 별도로 만들고 저장소에 넣지 않는다.
