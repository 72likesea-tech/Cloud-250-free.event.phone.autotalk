package com.personal.englishautotalk.talk

import com.personal.englishautotalk.talk.SpeechText.Chunk
import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechTextTest {

    @Test fun englishOnlyIsOneEnglishChunk() {
        assertEquals(listOf(Chunk("Nice! Where did you go?", korean = false)), SpeechText.split("Nice! Where did you go?"))
    }

    @Test fun koreanOnlyIsOneKoreanChunk() {
        assertEquals(listOf(Chunk("여행 이야기를 해 볼까요?", korean = true)), SpeechText.split("여행 이야기를 해 볼까요?"))
    }

    @Test fun koreanHelpWithEnglishExampleIsSplit() {
        assertEquals(
            listOf(
                Chunk("좋아하는 일을 말할 때는 이렇게 말해 보세요.", korean = true),
                Chunk("I like hiking on weekends.", korean = false),
                Chunk("이제 영어로 말해 보세요!", korean = true),
            ),
            SpeechText.split("좋아하는 일을 말할 때는 이렇게 말해 보세요. I like hiking on weekends. 이제 영어로 말해 보세요!"),
        )
    }

    @Test fun englishPhraseInsideKoreanSentence() {
        assertEquals(
            listOf(
                Chunk("다 말하면", korean = true),
                Chunk("Over to you", korean = false),
                Chunk("라고 해 주세요.", korean = true),
            ),
            SpeechText.split("다 말하면 Over to you 라고 해 주세요."),
        )
    }

    @Test fun punctuationOnlyPiecesAreDropped() {
        assertEquals(
            listOf(Chunk("예:", korean = true), Chunk("Let's talk about travel.", korean = false)),
            SpeechText.split("예: \"Let's talk about travel.\""),
        )
        assertEquals(emptyList<Chunk>(), SpeechText.split("  ...  "))
    }
}
