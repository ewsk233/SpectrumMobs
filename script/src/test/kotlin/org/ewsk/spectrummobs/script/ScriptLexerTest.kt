package org.ewsk.spectrummobs.script

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScriptLexerTest {
    @Test
    fun recognizesDurationsPercentagesAndComments() {
        val result = ScriptLexer("wait 1.5s // comment\nrequires 25%", "values.sms").lex()

        assertTrue(result.diagnostics.isEmpty())
        assertEquals(TokenType.WAIT, result.tokens[0].type)
        assertEquals(TokenType.DURATION, result.tokens[1].type)
        assertEquals("1.5s", result.tokens[1].lexeme)
        assertEquals(TokenType.NEWLINE, result.tokens[2].type)
        assertEquals(TokenType.REQUIRES, result.tokens[3].type)
        assertEquals(TokenType.PERCENTAGE, result.tokens[4].type)
    }
}
