package nl.teleprompter.stemvolger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptMatcherTest {

    private val script = """
        Goedemorgen allemaal, en welkom bij deze presentatie.
        Vandaag vertel ik jullie over ons nieuwe product.
        Het is sneller, slimmer en goedkoper dan ooit.
        Laten we beginnen met een korte demonstratie.
    """.trimIndent()

    @Test
    fun followsGrowingHypotheses() {
        val m = ScriptMatcher(script)
        m.onHeard("goedemorgen")
        assertEquals(1, m.position)
        m.onHeard("goedemorgen allemaal en")
        assertEquals(3, m.position)
        m.onHeard("goedemorgen allemaal en welkom bij deze presentatie")
        assertEquals(7, m.position)
    }

    @Test
    fun newRecognizerSessionContinues() {
        val m = ScriptMatcher(script)
        m.onHeard("goedemorgen allemaal en welkom bij deze presentatie")
        m.onHeard("vandaag vertel ik")
        assertEquals(10, m.position)
    }

    @Test
    fun toleratesRecognitionErrorsAndCase() {
        val m = ScriptMatcher(script)
        m.position = 7
        m.onHeard("Vandaag vertelt ik julie over ons nieuw product")
        assertEquals(15, m.position)
    }

    @Test
    fun skipsAheadWhenSentenceIsSkipped() {
        val m = ScriptMatcher(script)
        m.position = 7
        m.onHeard("het is sneller slimmer")
        assertEquals(19, m.position)
    }

    @Test
    fun ignoresUnrelatedSpeech() {
        val m = ScriptMatcher(script)
        m.position = 7
        assertFalse(m.onHeard("wacht even mijn microfoon doet raar"))
        assertEquals(7, m.position)
    }

    @Test
    fun doesNotJumpFarOnSingleCommonWord() {
        val m = ScriptMatcher(script)
        // "met" appears much further ahead; one word must not cause a big jump.
        assertFalse(m.onHeard("met"))
        assertEquals(0, m.position)
    }

    @Test
    fun wordIndexAtOffset() {
        val m = ScriptMatcher(script)
        assertEquals(0, m.wordIndexAt(0))
        assertEquals(1, m.wordIndexAt(script.indexOf("allemaal")))
        assertTrue(m.wordIndexAt(script.length) == m.words.size)
    }

    @Test
    fun similarity() {
        assertTrue(ScriptMatcher.similar("presentatie", "presentaties"))
        assertTrue(ScriptMatcher.similar("cafe", ScriptMatcher.normalize("Café")))
        assertFalse(ScriptMatcher.similar("de", "het"))
        assertFalse(ScriptMatcher.similar("kat", "kar"))
    }
}
