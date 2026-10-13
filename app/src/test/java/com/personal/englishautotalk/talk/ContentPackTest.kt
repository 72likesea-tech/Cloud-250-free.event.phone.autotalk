package com.personal.englishautotalk.talk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class ContentPackTest {
    private val pack = ContentPack.parse(File("src/main/assets/content_pack.json").readText())
    private val zone = ZoneId.of("Asia/Seoul")

    @Test fun packLoads() {
        assertEquals(30, pack.debateQuestions.size)
        assertEquals(4, pack.reprompts.size)
        assertEquals(10, pack.koreanNudges.size)
    }

    @Test fun openingFillsDebateQuestion() {
        val monday = ZonedDateTime.of(2026, 9, 28, 14, 0, 0, 0, zone)
        val text = pack.opening(monday)
        assertFalse(text.contains("{debate_question}"))
        assertTrue(text.endsWith(pack.debateQuestion(LocalDate.of(2026, 9, 28))))
    }

    @Test fun retryAndWeekendOpeningsDiffer() {
        val eightPm = ZonedDateTime.of(2026, 9, 28, 20, 0, 0, 0, zone)
        assertTrue(pack.opening(eightPm).startsWith("Hi! This is my last try today"))
        val saturday = ZonedDateTime.of(2026, 9, 26, 14, 0, 0, 0, zone)
        assertFalse(pack.opening(saturday) == pack.opening(ZonedDateTime.of(2026, 9, 28, 14, 0, 0, 0, zone)))
    }
}
