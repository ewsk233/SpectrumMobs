package org.ewsk.spectrummobs.script

data class LexResult(
    val tokens: List<ScriptToken>,
    val diagnostics: List<ScriptDiagnostic>
)

class ScriptLexer(
    private val source: String,
    private val sourceName: String = "<memory>"
) {
    private val tokens = mutableListOf<ScriptToken>()
    private val diagnostics = mutableListOf<ScriptDiagnostic>()
    private var start = 0
    private var current = 0
    private var line = 1
    private var column = 1
    private var tokenColumn = 1

    fun lex(): LexResult {
        while (!isAtEnd()) {
            start = current
            tokenColumn = column
            scanToken()
        }
        tokens += ScriptToken(TokenType.EOF, "", SourceLocation(current, line, column))
        return LexResult(tokens, diagnostics)
    }

    private fun scanToken() {
        when (val character = advance()) {
            ' ', '\t', '\u000B', '\u000C' -> Unit
            '\r' -> {
                if (match('\n')) addToken(TokenType.NEWLINE, "\r\n") else addToken(TokenType.NEWLINE, "\r")
                line++
                column = 1
            }
            '\n' -> {
                addToken(TokenType.NEWLINE, "\n")
                line++
                column = 1
            }
            '/' -> if (match('/')) skipComment() else addToken(TokenType.SLASH)
            '{' -> addToken(TokenType.LEFT_BRACE)
            '}' -> addToken(TokenType.RIGHT_BRACE)
            '(' -> addToken(TokenType.LEFT_PAREN)
            ')' -> addToken(TokenType.RIGHT_PAREN)
            ',' -> addToken(TokenType.COMMA)
            ':' -> addToken(TokenType.COLON)
            '.' -> addToken(TokenType.DOT)
            ';' -> addToken(TokenType.SEMICOLON)
            '=' -> addToken(if (match('=')) TokenType.EQUAL_EQUAL else TokenType.ASSIGN)
            '!' -> addToken(if (match('=')) TokenType.BANG_EQUAL else TokenType.BANG)
            '<' -> addToken(if (match('=')) TokenType.LESS_EQUAL else TokenType.LESS)
            '>' -> addToken(if (match('=')) TokenType.GREATER_EQUAL else TokenType.GREATER)
            '+' -> addToken(if (match('=')) TokenType.PLUS_ASSIGN else TokenType.PLUS)
            '-' -> addToken(if (match('=')) TokenType.MINUS_ASSIGN else TokenType.MINUS)
            '*' -> addToken(TokenType.STAR)
            '&' -> if (match('&')) addToken(TokenType.AND_AND) else unexpected(character)
            '|' -> if (match('|')) addToken(TokenType.OR_OR) else unexpected(character)
            '"' -> string()
            in '0'..'9' -> number()
            else -> when {
                character.isLetter() || character == '_' -> identifier()
                else -> unexpected(character)
            }
        }
    }

    private fun skipComment() {
        while (!isAtEnd() && peek() != '\n' && peek() != '\r') advance()
    }

    private fun string() {
        val builder = StringBuilder()
        while (!isAtEnd() && peek() != '"') {
            if (peek() == '\n' || peek() == '\r') {
                diagnostics += diagnostic("String literals cannot span multiple lines")
                return
            }
            if (advance() == '\\') {
                if (isAtEnd()) break
                builder.append(
                    when (val escaped = advance()) {
                        'n' -> '\n'
                        'r' -> '\r'
                        't' -> '\t'
                        '\\' -> '\\'
                        '"' -> '"'
                        else -> {
                            diagnostics += diagnostic("Unsupported string escape \\$escaped")
                            escaped
                        }
                    }
                )
            } else {
                builder.append(source[current - 1])
            }
        }
        if (isAtEnd()) {
            diagnostics += diagnostic("Unterminated string literal")
            return
        }
        advance()
        addToken(TokenType.STRING, builder.toString())
    }

    private fun number() {
        while (peek().isDigit()) advance()
        if (peek() == '.' && peekNext().isDigit()) {
            advance()
            while (peek().isDigit()) advance()
        }
        when {
            peek() == 't' || peek() == 's' || peek() == 'm' -> {
                advance()
                addToken(TokenType.DURATION)
            }
            peek() == '%' -> {
                advance()
                addToken(TokenType.PERCENTAGE)
            }
            else -> addToken(TokenType.NUMBER)
        }
    }

    private fun identifier() {
        while (peek().isLetterOrDigit() || peek() == '_') advance()
        val text = source.substring(start, current)
        val type = KEYWORDS[text] ?: TokenType.IDENTIFIER
        addToken(type, text)
    }

    private fun unexpected(character: Char) {
        diagnostics += diagnostic("Unexpected character '$character'")
    }

    private fun addToken(type: TokenType, lexeme: String = source.substring(start, current)) {
        tokens += ScriptToken(type, lexeme, SourceLocation(start, line, tokenColumn))
    }

    private fun diagnostic(message: String): ScriptDiagnostic =
        ScriptDiagnostic(sourceName, SourceLocation(start, line, tokenColumn), message)

    private fun advance(): Char {
        val character = source[current++]
        column++
        return character
    }

    private fun match(expected: Char): Boolean {
        if (isAtEnd() || source[current] != expected) return false
        current++
        column++
        return true
    }

    private fun peek(): Char = if (isAtEnd()) '\u0000' else source[current]

    private fun peekNext(): Char = if (current + 1 >= source.length) '\u0000' else source[current + 1]

    private fun isAtEnd(): Boolean = current >= source.length

    private companion object {
        val KEYWORDS = mapOf(
            "skill" to TokenType.SKILL,
            "import" to TokenType.IMPORT,
            "as" to TokenType.AS,
            "fn" to TokenType.FN,
            "cooldown" to TokenType.COOLDOWN,
            "requires" to TokenType.REQUIRES,
            "let" to TokenType.LET,
            "if" to TokenType.IF,
            "else" to TokenType.ELSE,
            "for" to TokenType.FOR,
            "repeat" to TokenType.REPEAT,
            "in" to TokenType.IN,
            "wait" to TokenType.WAIT,
            "return" to TokenType.RETURN,
            "true" to TokenType.TRUE,
            "false" to TokenType.FALSE,
            "null" to TokenType.NULL
        )
    }
}
