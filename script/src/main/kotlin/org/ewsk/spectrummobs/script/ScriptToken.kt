package org.ewsk.spectrummobs.script

enum class TokenType {
    EOF,
    NEWLINE,
    IDENTIFIER,
    NUMBER,
    STRING,
    DURATION,
    PERCENTAGE,

    SKILL,
    IMPORT,
    AS,
    FN,
    COOLDOWN,
    REQUIRES,
    LET,
    IF,
    ELSE,
    FOR,
    REPEAT,
    IN,
    WAIT,
    RETURN,
    TRUE,
    FALSE,
    NULL,

    LEFT_BRACE,
    RIGHT_BRACE,
    LEFT_PAREN,
    RIGHT_PAREN,
    COMMA,
    COLON,
    DOT,
    SEMICOLON,

    ASSIGN,
    PLUS_ASSIGN,
    MINUS_ASSIGN,
    EQUAL_EQUAL,
    BANG,
    BANG_EQUAL,
    LESS,
    LESS_EQUAL,
    GREATER,
    GREATER_EQUAL,
    PLUS,
    MINUS,
    STAR,
    SLASH,
    AND_AND,
    OR_OR
}

data class ScriptToken(
    val type: TokenType,
    val lexeme: String,
    val location: SourceLocation
)
