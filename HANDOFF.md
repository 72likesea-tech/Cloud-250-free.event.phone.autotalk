# 인계 문서 (HANDOFF) — 영어자동말걸기

- 작성: 2026-09-26, Claude Code 클라우드 세션
- 저장소: `72likesea-tech/Cloud-250-free.event` (**공개 저장소**)
- 작업 브랜치: `claude/fervent-keller-l85wi9` (저장소 기본 브랜치이기도 함)
- 이 문서만 읽으면 새 세션에서 바로 이어서 작업할 수 있게 정리했다. 세부 근거는 아래 링크된 문서를 본다.

## 1. 한 줄 요약
매일 14:00에 잠긴 휴대폰에서 앱이 먼저 영어로 말을 걸고, 사용자가 말로만 5~10분 대화하면 AI(Gemini)가 응답·교정하고 기록을 남기는 개인용 Android 앱.
**3단계(AI 대화 한 회차)까지 구현을 마쳤고, 실기기 검수 결과를 기다리는 중이다.**

## 2. 진행 상태

| 단계 (작업지시서 5절) | 상태 | 비고 |
| --- | --- | --- |
| 1. 환경 확인·인터뷰·요구사항 확정 | ✅ 완료 | `requirements.md` 확정 |
| 2. 잠긴 휴대폰에서 지정 시각 자동 말걸기 + 첫 문장 음성 | ✅ 구현 / ⏳ **실기기 미검증** | T2(화면 끄고 1분 뒤 자동 시작) 결과 대기 |
| 3. 수업 한 회차 (AI 대화 → 교정 요약 → 기록) | ✅ 구현 / ⏳ **실기기 미검증** | 실제 Gemini 호출은 한 번도 시험 못 함(개발 환경에 키 없음) |
| 4. 일정·화면 완성, 미루기·복습 | ⬜ 미착수 | |
| 5. 서명 APK(릴리스 키), 설치·업데이트, 3일 실생활 검수, 최종 보고 | ⬜ 미착수 | |

- 현재 빌드: **0.4.1-stage4** (versionCode 7, 공용 디버그 키 서명)
- 다운로드: https://github.com/72likesea-tech/Cloud-250-free.event.phone.autotalk/raw/claude/voice-chat-beep-removal-38tjne/dist/EnglishAutoTalk-0.4.1-stage4.apk
- 단위 테스트 36개 통과, 정적 검사(lint) 오류 0.

## 3. 사용자가 확정한 핵심 결정 (바꾸려면 사용자 확인 필요)

전체는 `requirements.md`, 회차별 원문은 `docs/interview-notes.md`에 있다.

**사용 환경**
- 기기: Galaxy S24 Ultra / One UI 8.5 / Android 16.
- 개발: 클라우드 세션에서 빌드하고, 사용자가 APK를 받아 직접 설치한다(A안).

**학습 설정**
- 수준: 초급 상 ~ 중급 하. 전 영역(여행·업무·일상·비즈니스·토론).
- 한국어: 막힐 때 말문을 여는 용도로만 쓴다.

**일정과 재시도**
- 매일 14:00 첫 시도, 하루 1회, 5~10분.
- 완료 기준: 5분 또는 8왕복. 10분이면 마무리한다.
- 재시도 사유: 패스, 무응답(8초 뒤 1회 다시 말걸기 후 종료), 무음·매너 모드(말 걸지 않음), 방해금지 30초 무반응.
- 재시도는 1시간 뒤, 20:00까지. 그날 회차를 완료하면 남은 재시도를 모두 취소한다.

**기기 상태별 동작**
- 방해금지: 소리 없이 화면+진동만 쓰고, 터치하면 시작한다.
- 이어폰이 연결돼 있으면 이어폰으로 말한다.

**대화 흐름**
- 첫 인사 뒤 주제를 묻는다: work / travel / **그날의 구체적 토론 질문**. '의견과 토론'이라는 말은 쓰지 않는다.
- 교정(2026-10-12 변경): 대화 중에는 뜻이 안 통하는 **심한 오류만 즉시** 고쳐 말한다. 일반 실수는 지적하지 않고, 끝난 뒤 **화면 텍스트**로 내 문장 전체 교정(고친 문장·더 자연스러운 표현·한국어 설명) + 핵심 3개를 보여 준다. 패스·그만·무응답으로 끝나도 말한 문장이 있으면 교정한다. 홈 '대화 기록'에서 교정 보기·다시 받기(`ai/Reviewer.kt`, `ui/ReviewContent.kt`). 대화 화면 **'오늘 대화 끝내고 교정 받기'** 버튼 + 확인 창(`Outcome.REVIEW_REQUESTED`, 완료 처리). '오늘은 그만'은 교정 없이 끝낸다.

**음성 명령**
- 말 끝 신호: **"I'm all set" / "Over to you"**. 이 신호를 말해야만 Mia가 대답한다(20초 안전장치는 2026-10-09 사용자 지시로 삭제). 듣는 동안 인식기 비프음은 음소거(`util/BeepMuter.kt`).
- 한국어 도움: **"Help me in Korean"** 또는 한국어로 말하기.

**AI와 비용**
- AI: 사용자가 가진 Gemini 키, **월 0원 필수**.
- 모델 기본값 `gemini-3.5-flash-lite`. 설정에서 `3.1-flash-lite`, `2.5-flash-lite`로 변경할 수 있다. 2.5 계열은 2026-09-18부터 새 프로젝트에 막혔다는 조사 결과에 따른 것이다.
- 무료 티어 데이터 이용 조건(Google 제품 개선에 사용, 사람 검토 가능)에 **동의**했다.
- 외부 전송은 대화 텍스트만 한다. 음성 원본은 저장하지 않고, 기록 전체 삭제를 제공한다.

**화면**
- 앱 이름 **영어자동말걸기**, 페르소나 **Mia**.
- 테마는 휴대폰 설정을 따른다. 대화 화면만 큰 글자, 자막 표시.

**역할 분담**
- 원래 Gemini·GPT에 맡길 작업(`docs/ai-role-prompts.md`)도 사용자 지시에 따라 **Claude가 직접 수행**했다. 이 세션에는 Gemini·GPT 연결이 없었다.

## 4. 코드 구조
`README.md`의 구조 표를 본다. 핵심 흐름은 다음과 같다.
```
AlarmScheduler(setAlarmClock) → AlarmReceiver(재시도 선예약, 무음/DND 판단)
  → TalkNotifier(전체화면 인텐트) → TalkActivity(잠금화면 위) → TalkSession(TTS↔STT, 음성 명령)
  → TalkBrain(Gemini 또는 기본 연습) + ConversationEngine(단계·완료) → 요약 화면 → SessionStore(기록)
```
- 순수 로직과 단위 테스트: `SchedulePolicy`, `VoiceCommands`, `ConversationEngine`, `ContentPack`, `GeminiProtocol`, `GeminiClient`(전송 주입).
- 프롬프트: `app/src/main/assets/prompts/`. 원본 설명은 `docs/gemini/taskD-system-prompts.md`.
- 콘텐츠: `app/src/main/assets/content_pack.json`. 원본 설명은 `docs/content/`.

## 5. 빌드 환경 (클라우드 세션)
- 클라우드 환경 네트워크는 **Custom**이고 `dl.google.com` 허용 + 기본 목록을 포함한다. 사용자가 설정해 둔 상태다.
- 세션이 시작되면 `.claude/settings.json`의 SessionStart 훅이 `scripts/setup-android-sdk.sh`를 실행해 `/opt/android-sdk`에 SDK를 설치한다.
- 빌드: `./gradlew testDebugUnitTest assembleDebug`
  - Maven Central이 가끔 429를 낸다. 그러면 잠시 뒤 다시 실행한다.
- 버전 조합: AGP 8.13.2 / Gradle 8.14.3 / Kotlin 2.2.21 / Compose BOM 2025.10.01 / compileSdk·targetSdk 36 / minSdk 31.
- **새 빌드를 낼 때:**
  1. `app/build.gradle.kts`의 versionCode를 올린다.
  2. APK를 `dist/`에 복사하고 커밋·푸시한다. 링크는 `.../raw/claude/fervent-keller-l85wi9/dist/<파일명>`.
  3. 파일 전달(SendUserFile)도 함께 한다.
- 막힌 사이트: ai.google.dev, firebase.google.com, samsung.com, source.android.com, api.openai.com. developer.android.com과 github.com은 열린다.

## 6. 사용자에게서 받아야 할 것 (다음 세션 시작 시 먼저 확인)
1. **2단계 T2** 결과: 화면을 끈 뒤 1분 뒤 테스트에서 잠금 해제 없이 화면이 켜지고 음성이 나오는지. 안내서: `docs/stage2-test-guide.md`.
2. **음성 명령 V1~V5**: 특히 한국어로 말했을 때 감지되는지(V4). 안내서: `docs/stage2-test-guide.md` 5절.
3. **3단계 A1~A9**와 **연결 테스트 결과 문구**(✅ 또는 ⚠️ 코드). 안내서: `docs/stage3-test-guide.md`.
4. Mia 응답이 길거나 어려웠던 예, 교정 3개 화면 스크린샷 → 프롬프트 조정에 쓴다.
5. AI Studio(aistudio.google.com/rate-limit)의 실제 무료 한도 → `docs/gemini/taskA-free-tier.md`의 2차 자료 수치를 교체한다.

## 7. 다음 할 일

**우선순위 순서 (4단계)**
1. 검수 결과에 따른 버그 수정. T2가 실패하면 전체화면 알림 권한과 삼성 절전 설정을 먼저 본다(`docs/gemini/taskB-android16-constraints.md`).
2. 실제 Gemini 응답 확인 후 프롬프트 조정. 모델 404가 나면 기본값을 조정한다.
3. **4단계:**
   - 일정 설정 화면: 현재 14:00 고정. 변경 기능은 요구사항상 제외지만 사용자 요청 시 추가.
   - '오늘 건너뛰기'·패스 UX 다듬기.
   - 복습: `review_expressions`를 다음 회차에 녹여 쓰기.
   - 기록 화면 정리.
   - 녹음·재생·대기·오류 상태 구분.
4. GPT 작업 2(콘텐츠 팩 확장: 주제별 후속 질문·기본 연습 스크립트 5왕복), 작업 4(QA 체크리스트), 작업 5(코드 세컨드 리뷰)는 아직 안 했다. Claude가 대신하거나 사용자가 GPT로 돌린다(`docs/handoff/gpt-task-1-3.txt` 형식 참고).
5. **5단계:**
   - 사용자 관리 릴리스 키를 만든다(저장소에 넣지 않음).
   - 디버그 서명 → 릴리스 서명으로 바꾸면 **서명이 달라 재설치가 필요**하다. 기록이 지워지므로 사용자에게 먼저 안내한다.
   - 3일 연속 실생활 14:00 검수, 최종 보고.

## 8. 알려진 제약·주의
- **공개 저장소:** 인터뷰 기록, 테스트용 디버그 키, APK가 공개돼 있다. 사용자에게 알렸고, Private 전환은 사용자 선택이다.
- 휴대폰 사용 중(화면 켜짐·잠금 해제)에는 전체화면 대신 헤드업 알림이 뜬다(Android 정책). 강제 중지하면 알람이 사라지고, 앱을 열면 복구된다.
- 방해금지 '완전 무음'에서는 소리가 나지 않고 자막만 나온다. 한국어 말하기 감지는 기기 인식기 지원에 따라 다르다.
- Android 개발자 인증 정책은 한국에 2027년 적용 예정이다. ADB 설치는 면제. 그 전에 무료 제한 배포 계정 등록을 검토한다.
- Gemini 키는 앱 설정에서만 입력한다. **어느 대화창·문서·로그에도 키를 남기지 않는다.**

## 9. 새 세션 시작 문장 (복사해서 사용)
```
HANDOFF.md를 읽고 이어서 진행해 줘. 먼저 6절의 검수 결과를 내가 알려줄게.
```
