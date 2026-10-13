# 작업 1 — 페르소나와 먼저 말 걸기 규칙

- 작성: Claude (원래 GPT 담당 작업. 사용자 결정 (가)에 따라 대신 작성, 2026-09-26)
- 앱이 읽는 원본 데이터: `app/src/main/assets/content_pack.json`
- 작성 원칙: 초급 상~중급 하, 한 문장 12단어 안팎, 오프닝마다 질문 1개, 오프닝은 항상 `work / travel / {debate_question}` 세 선택지로 끝남, '의견과 토론(opinion and debate)'이라는 말은 쓰지 않음.

## 1. 페르소나

| 안 | 이름 | 말투 | 다정함 | 유머 | 어울리는 상황 |
| --- | --- | --- | --- | --- | --- |
| **A (추천)** | **Mia** | 옆자리 동료처럼 편한 구어체, 짧은 문장 | 높음 | 가볍게 | 오후에 갑자기 말을 걸어도 부담이 적음 |
| B | Sam | 차분한 코치, 또박또박 | 중간 | 거의 없음 | 교정·연습 느낌이 강함 |
| C | Jake | 에너지 넘치는 친구, 감탄사 많음 | 높음 | 많음 | 재미있지만 지치는 날엔 부담 |

**추천: Mia.** 잠긴 휴대폰에서 소리가 갑자기 나오는 앱이라 첫인상이 부드러워야 합니다. 말하기 부담을 줄이는 것이 이 앱의 목적에 가장 잘 맞습니다.
- TTS 음성: 영어(미국) 여성 음성, 속도 0.9~0.95배 (3단계 기본값, 설정에서 조정 가능)

## 2. 오프닝 문장 (40개)

`{debate_question}` 자리에는 그날의 토론 질문(아래 4절)이 들어갑니다. 예: *"Hi there! It's English time. Pick one for today: work, travel, or this question: Should companies adopt a four-day work week?"*

### 첫 시도 — 14:00 (20개)
1. Hi there! It's English time. Pick one for today: work, travel, or this question: {debate_question}
2. Good afternoon! Let's warm up your English. We can talk about work, travel, or this: {debate_question}
3. Hey, it's me again! Time for a quick chat. Work, travel, or this one: {debate_question}
4. Hello! Just five minutes of English, I promise. Work, travel, or this question: {debate_question}
5. Hi! I hope your day is going well. Let's talk about work, travel, or this question: {debate_question}
6. Hey there! It's coffee break English time. Your choice: work, travel, or this one: {debate_question}
7. Hi! I'm here for our daily chat. Tell me: work, travel, or this question: {debate_question}
8. Good afternoon! A short English chat keeps your brain fresh. Work, travel, or this: {debate_question}
9. Hello again! Let's speak some English. Pick work, travel, or today's question: {debate_question}
10. Hi! Quick English break. We can talk about your work, a trip, or this question: {debate_question}
11. Hey! Let's get talking. Work, travel, or something to debate: {debate_question}
12. Hi there! Ready or not, it's English time. Work, travel, or this question: {debate_question}
13. Good afternoon! Let's practice a little. Choose work, travel, or this one: {debate_question}
14. Hello! Your English buddy is here. Work, travel, or today's big question: {debate_question}
15. Hi! Take a deep breath and let's chat. Work, travel, or this question: {debate_question}
16. Hey! Short chat, big progress. Pick work, travel, or this one: {debate_question}
17. Hi! Let's make your afternoon a bit more English. Work, travel, or this: {debate_question}
18. Hello! Time to speak some English out loud. Work, travel, or this question: {debate_question}
19. Hi there! Just you and me, a few minutes of English. Work, travel, or this one: {debate_question}
20. Hey, good to hear from you! Let's chat. Work, travel, or today's question: {debate_question}

### 재시도 — 15:00~20:00 (10개, 괄호는 사용 시간대)
1. Hi again! Looks like you were busy earlier. Let's try now: work, travel, or this question: {debate_question} (15–20시)
2. Knock knock, I'm back for our chat! Work, travel, or this one: {debate_question} (15–20시)
3. Hey, second try! No pressure, just a few minutes. Work, travel, or this question: {debate_question} (15시만)
4. Hi! I didn't want you to miss today's English. Work, travel, or this: {debate_question} (15–20시)
5. Hello again! Maybe now is a better time. Pick work, travel, or this question: {debate_question} (15–20시)
6. Hi! It's me, your English buddy, one more time. Work, travel, or today's question: {debate_question} (16–20시)
7. Hey! Let's squeeze in a quick chat before the day ends. Work, travel, or this one: {debate_question} (17–20시)
8. Hi there! Still here and ready to talk. Work, travel, or this question: {debate_question} (15–20시)
9. Good evening! Let's end the day with a little English. Work, travel, or this: {debate_question} (18–20시)
10. Hi! This is my last try today, so let's chat now. Work, travel, or this one: {debate_question} (20시만)

### 주말 (10개)
1. Happy weekend! Let's have a relaxed English chat. Work, travel, or this question: {debate_question}
2. Hi! It's the weekend, so let's keep it easy. Your week at work, a trip, or this one: {debate_question}
3. Hey! Lazy weekend English time. Work, travel, or this question: {debate_question}
4. Good afternoon! Weekends are perfect for a chat. Pick work, travel, or this: {debate_question}
5. Hi there! No work rush today, just English. Work, travel, or this question: {debate_question}
6. Hello! Let's talk like friends on a weekend. Work, travel, or this one: {debate_question}
7. Hi! A little English makes a great weekend. Work, travel, or today's question: {debate_question}
8. Hey! Weekend plans can wait five minutes. Work, travel, or this question: {debate_question}
9. Hi! Let's chat about your week, your trips, or this question: {debate_question}
10. Happy weekend! Let's speak some English. Work, travel, or this: {debate_question}

**선택 규칙 (앱 구현):** 주말이면 주말용, 평일 첫 시도는 첫 시도용, 재시도는 시간대가 맞는 재시도용 중에서 고릅니다. 최근 7일 안에 쓴 문장은 피합니다.

## 3. 대답이 없을 때

재말걸기는 1회만 합니다(8초 무응답 후).

**재말걸기 (5개)**
1. Are you there? No rush. Just say work, travel, or today's question.
2. ~~Take your time. Even one word is fine: work, travel, or the question.~~ (2026-10-13 사용자 요청으로 삭제)
3. Hmm, I didn't catch that. Which one sounds fun: work, travel, or today's question?
4. No worries if it's hard. Just pick one: work, travel, or the question.
5. I'm still here! Say anything you like, even just work or travel.

**마무리 — 1시간 뒤 재시도가 남은 경우 (5개)**
1. Okay, looks like you're busy right now. I'll try again in an hour!
2. No problem, I'll let you go. Talk to you in an hour!
3. Seems like a busy moment. I'll come back a bit later!
4. That's okay! I'll check in again in about an hour.
5. All good, take care of what you're doing. See you in an hour!

**마무리 — 20:00 마지막 시도라 다음 날로 넘어가는 경우 (3개)**
원래 요청 범위에는 없지만, "1시간 뒤"라고 말하면 틀리는 상황이라 추가했습니다.
1. Okay, it's getting late. Let's talk tomorrow afternoon!
2. No problem at all. I'll see you tomorrow at two!
3. That's fine, rest well tonight. Talk to you tomorrow!

## 4. 토론 질문 (30개)

| 분야 | 질문 |
| --- | --- |
| 업무 (8) | Should companies adopt a four-day work week? · Is working from home better than working in an office? · Should people be allowed to use their phones during meetings? · Is it better to work for a big company or a small startup? · Should bosses and employees be friends on social media? · Is it okay to check work emails on weekends? · Should companies pay for their employees' English lessons? · Is a high salary more important than a good boss? |
| 기술 (6) | Should AI be allowed to help make hiring decisions? · Will AI make our jobs easier or harder? · Should kids under sixteen be allowed on social media? · Is it a good idea to go one day a week without a smartphone? · Should self-driving cars be allowed on city roads? · Is online shopping better than shopping in stores? |
| 사회·일상 (9) | Is it better to live in a big city or a small town? · Should public transportation be free in big cities? · Is it worth paying more for eco-friendly products? · Should cafes stop using single-use plastic cups? · Is it better to cook at home or eat out? · Should schools start later in the morning? · Is it rude to talk on the phone on the subway? · Should people retire at sixty? · Is money the most important thing for happiness? |
| 여행·생활 (7) | Is it better to travel alone or with friends? · Should tourists learn some of the local language before a trip? · Is a package tour better than planning a trip yourself? · Is one long vacation better than several short trips? · Should everyone live abroad at least once? · Is it better to rent a home or buy one? · Should pets be allowed in restaurants and cafes? |

모든 질문은 찬성·반대와 이유 한두 개로 답할 수 있게 만들었습니다. 30일 주기로 돌아가며, 3단계에서 Gemini가 새 질문을 만들어 보충할 수 있습니다.

## 5. 한국어로 답하거나 "모르겠어"라고 할 때 (10개)

| 영어로 유도 | 화면에 보여줄 한국어 힌트 |
| --- | --- |
| That's okay! Try it in English, just a few words. For example: I'm busy today. | 짧게라도 영어로 말해 보세요. 예: I'm busy today. |
| Good idea! Now let's say it in English. You can start with: I think... | 'I think...'(제 생각에는)로 시작해 보세요. |
| No problem. Let me make it easier: do you like it, yes or no? | Yes나 No로만 답해도 괜찮아요. |
| I hear you! Try one English word first. Just one. | 먼저 영어 단어 하나만 말해 보세요. |
| That's fine! You can say: I'm not sure, but... and keep going. | '잘 모르겠지만'은 I'm not sure, but... 이에요. |
| Nice try! In English, maybe: It was a long day. Now you try. | 제가 한 문장을 들려드렸어요. 따라 말해 보세요. |
| Don't worry about mistakes. Just say it simply, like texting a friend. | 틀려도 괜찮아요. 친구에게 문자하듯 쉽게 말해요. |
| Let's go smaller. Is your day good or bad so far? | good 또는 bad 한 단어로 시작해 보세요. |
| Okay! Try this: I don't know much about it, but I think... | '잘 모르지만 제 생각에는' = I don't know much about it, but I think... |
| Almost there! Say it in English and I'll help you with it. | 영어로 말해 보시면 자연스럽게 다듬어 드릴게요. |

한국어 힌트는 **음성으로 읽지 않고 화면에만** 표시합니다. 영어로 말문을 여는 보조 역할만 합니다.
