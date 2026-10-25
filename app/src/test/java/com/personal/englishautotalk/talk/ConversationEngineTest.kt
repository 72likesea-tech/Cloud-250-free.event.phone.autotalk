package com.personal.englishautotalk.talk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationEngineTest {
    private var now = 0L
    private val q = "Should companies adopt a four-day work week?"
    private fun engine() = ConversationEngine(q) { now }

    @Test fun detectsTopicChoice() {
        assertEquals("work", ConversationEngine.detectTopic("Let's talk about work", q))
        assertEquals("travel", ConversationEngine.detectTopic("I want travel", q))
        assertEquals("debate", ConversationEngine.detectTopic("the question please", q))
        assertEquals("debate", ConversationEngine.detectTopic("four-day week sounds fun", q))
        assertNull(ConversationEngine.detectTopic("hmm I don't know", q))
    }

    @Test fun phasesFollowTurnsAndTime() {
        val e = engine()
        e.addApp("opening")
        e.addLearner("travel")
        assertEquals(ConversationEngine.Phase.CHOOSE_TOPIC, e.phaseForReply())
        e.addApp("reply")
        e.addLearner("I went to Jeju")
        assertEquals(ConversationEngine.Phase.TALK, e.phaseForReply())
        now = 5 * 60_000L
        assertTrue(e.isComplete())
        assertEquals(ConversationEngine.Phase.WRAP_UP, e.phaseForReply())
    }

    @Test fun koreanQuestionIsAnsweredEvenAfterCompletion() {
        val e = engine()
        e.addLearner("travel")
        e.addLearner("I went to Jeju")
        now = 11 * 60_000L
        e.addLearner("'미루다'는 영어로 뭐야?", koreanHelp = true)
        assertEquals(ConversationEngine.Phase.TALK, e.phaseForReply(koreanHelp = true))
        // 다음 영어 차례에 마무리한다.
        e.addLearner("I want to put it off")
        assertEquals(ConversationEngine.Phase.WRAP_UP, e.phaseForReply())
    }

    @Test fun eightTurnsCompleteTheSession() {
        val e = engine()
        repeat(7) { e.addLearner("work $it") }
        assertFalse(e.isComplete())
        e.addLearner("more")
        assertTrue(e.isComplete())
    }

    @Test fun koreanHelpDoesNotCountAsTurn() {
        val e = engine()
        e.addLearner("Help me in Korean", koreanHelp = true)
        assertEquals(0, e.learnerTurns)
        assertTrue(e.messages.last().text.startsWith(ConversationEngine.HELP_MARKER))
    }

    @Test fun recentMessagesStartWithUserAndAreCapped() {
        val e = engine()
        repeat(30) { e.addApp("a$it"); e.addLearner("u$it") }
        val recent = e.recentMessages()
        assertEquals(ConversationEngine.MAX_MESSAGES, recent.size)
        assertTrue(recent.first().fromLearner)
        assertEquals("u29", recent.last().text)
    }

    @Test fun systemPromptPlaceholdersAreFilled() {
        val e = engine()
        e.addLearner("work")
        val p = e.systemPrompt("{{debate_question}}|{{chosen_topic}}|{{elapsed_min}}|{{turn_count}}|{{phase}}", ConversationEngine.Phase.TALK)
        assertEquals("$q|work|0|1|talk", p)
    }
}
