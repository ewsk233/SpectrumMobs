package org.ewsk.spectrummobs.script

data class ParseResult(
    val script: ScriptFile?,
    val diagnostics: List<ScriptDiagnostic>
)

class ScriptParser(
    private val tokens: List<ScriptToken>,
    private val sourceName: String = "<memory>"
) {
    private val diagnostics = mutableListOf<ScriptDiagnostic>()
    private var current = 0

    fun parse(): ParseResult {
        val declarations = mutableListOf<ScriptDeclaration>()
        val imports = mutableListOf<ImportDeclaration>()
        skipSeparators()
        while (!isAtEnd()) {
            if (match(TokenType.IMPORT)) {
                imports += parseImport(previous())
            } else if (match(TokenType.SKILL)) {
                declarations += parseSkill(previous())
            } else if (match(TokenType.FN)) {
                declarations += parseRuleFunction(previous())
            } else {
                report(peek(), "Only skill declarations are allowed at the top level")
                synchronizeTopLevel()
            }
            skipSeparators()
        }
        return ParseResult(ScriptFile(declarations, imports = imports), diagnostics)
    }

    private fun parseSkill(start: ScriptToken): SkillDeclaration {
        val name = consume(TokenType.IDENTIFIER, "Skill name must be an identifier").lexeme
        val parameters = if (match(TokenType.LEFT_PAREN)) parseParameters() else emptyList()
        consume(TokenType.LEFT_BRACE, "Skill declaration requires '{'")
        skipSeparators()

        var cooldown: Expression? = null
        var requires: Expression? = null
        val casting = linkedMapOf<String, Expression>()
        val statements = mutableListOf<Statement>()

        while (!check(TokenType.RIGHT_BRACE) && !isAtEnd()) {
            when {
                check(TokenType.IDENTIFIER) && peek().lexeme in CASTING_PROPERTIES &&
                    tokens.getOrNull(current + 1)?.type == TokenType.ASSIGN -> {
                    val property = advance()
                    advance()
                    val value = parseExpression()
                    if (casting.putIfAbsent(property.lexeme, value) != null) report(property, "Duplicate skill property '${property.lexeme}'")
                    consumeTerminator()
                }
                match(TokenType.COOLDOWN) -> {
                    if (cooldown != null) report(previous(), "Duplicate skill property 'cooldown'")
                    consume(TokenType.ASSIGN, "cooldown requires '='")
                    cooldown = parseExpression()
                    consumeTerminator()
                }
                match(TokenType.REQUIRES) -> {
                    if (requires != null) report(previous(), "Duplicate skill property 'requires'")
                    requires = parseExpression()
                    consumeTerminator()
                }
                else -> statements += parseStatement()
            }
            skipSeparators()
        }

        consume(TokenType.RIGHT_BRACE, "Skill declaration requires '}'")
        return SkillDeclaration(name, parameters, cooldown, requires, BlockStatement(statements, start.location), start.location, casting)
    }

    private fun parseImport(start: ScriptToken): ImportDeclaration {
        val module = consume(TokenType.STRING, "import requires a quoted module ID").lexeme
        consume(TokenType.AS, "import requires 'as'")
        val alias = consume(TokenType.IDENTIFIER, "import alias must be an identifier").lexeme
        consumeTerminator()
        return ImportDeclaration(module, alias, start.location)
    }

    private fun parseRuleFunction(start: ScriptToken): RuleFunctionDeclaration {
        val name = consume(TokenType.IDENTIFIER, "Function name must be an identifier").lexeme
        consume(TokenType.LEFT_PAREN, "Function declaration requires '('")
        val parameters = parseParameters()
        consume(TokenType.COLON, "Function declaration requires a return type")
        val returnType = parseTypeName()
        val body = parseRequiredBlock("Function declaration requires a code block")
        return RuleFunctionDeclaration(name, parameters, returnType, body, start.location)
    }

    private fun parseParameters(): List<ScriptParameterDeclaration> {
        val parameters = mutableListOf<ScriptParameterDeclaration>()
        if (!check(TokenType.RIGHT_PAREN)) {
            do {
                val name = consume(TokenType.IDENTIFIER, "Parameter name must be an identifier")
                consume(TokenType.COLON, "Parameter requires ':' and a type")
                val type = parseTypeName()
                val defaultValue = if (match(TokenType.ASSIGN)) parseExpression() else null
                parameters += ScriptParameterDeclaration(name.lexeme, type, defaultValue, name.location)
            } while (match(TokenType.COMMA))
        }
        consume(TokenType.RIGHT_PAREN, "Parameter list requires ')'")
        return parameters
    }

    private fun parseTypeName(): TypeName {
        val name = consume(TokenType.IDENTIFIER, "Type name must be an identifier").lexeme
        return if (name == "List" && match(TokenType.LESS)) {
            val element = parseTypeName()
            consume(TokenType.GREATER, "List type requires '>'")
            TypeName.ListOf(element)
        } else TypeName.Simple(name)
    }

    private companion object {
        val CASTING_PROPERTIES = setOf("group", "concurrency", "cancelOnPhaseChange", "cleanupOnCancel")
    }

    private fun parseStatement(): Statement {
        if (match(TokenType.LEFT_BRACE)) return parseBlock(previous())
        if (match(TokenType.LET)) return parseVariable(previous())
        if (isStateAssignment()) return parseStateAssignment()
        if (match(TokenType.WAIT)) return parseWait(previous())
        if (match(TokenType.IF)) return parseIf(previous())
        if (match(TokenType.FOR)) return parseFor(previous())
        if (match(TokenType.REPEAT)) return parseRepeat(previous())
        if (match(TokenType.RETURN)) return parseReturn(previous())

        val expression = parseExpression()
        val statement = ExpressionStatement(expression, expression.location)
        consumeTerminator()
        return statement
    }

    private fun parseBlock(start: ScriptToken): BlockStatement {
        val statements = mutableListOf<Statement>()
        skipSeparators()
        while (!check(TokenType.RIGHT_BRACE) && !isAtEnd()) {
            statements += parseStatement()
            skipSeparators()
        }
        consume(TokenType.RIGHT_BRACE, "Code block requires '}'")
        return BlockStatement(statements, start.location)
    }

    private fun parseVariable(start: ScriptToken): VariableDeclaration {
        val name = consume(TokenType.IDENTIFIER, "Variable name must be an identifier").lexeme
        consume(TokenType.ASSIGN, "Variable declaration requires '='")
        val initializer = parseExpression()
        consumeTerminator()
        return VariableDeclaration(name, initializer, start.location)
    }

    private fun parseStateAssignment(): StateAssignmentStatement {
        val start = advance()
        val name = consume(TokenType.IDENTIFIER, "state requires a state name").lexeme
        val operator = when {
            match(TokenType.ASSIGN) -> StateAssignmentOperator.SET
            match(TokenType.PLUS_ASSIGN) -> StateAssignmentOperator.ADD
            match(TokenType.MINUS_ASSIGN) -> StateAssignmentOperator.SUBTRACT
            else -> {
                report(peek(), "state requires '=', '+=' or '-='")
                StateAssignmentOperator.SET
            }
        }
        val value = parseExpression()
        consumeTerminator()
        return StateAssignmentStatement(name, operator, value, start.location)
    }

    private fun parseWait(start: ScriptToken): WaitStatement {
        val duration = parseExpression()
        consumeTerminator()
        return WaitStatement(duration, start.location)
    }

    private fun parseIf(start: ScriptToken): IfStatement {
        val condition = parseExpression()
        val thenBranch = parseRequiredBlock("if requires a code block")
        skipSeparators()
        val elseBranch = if (match(TokenType.ELSE)) {
            parseRequiredBlock("else requires a code block")
        } else null
        return IfStatement(condition, thenBranch, elseBranch, start.location)
    }

    private fun parseFor(start: ScriptToken): ForStatement {
        val variable = consume(TokenType.IDENTIFIER, "for loop variable must be an identifier").lexeme
        consume(TokenType.IN, "for loop requires 'in'")
        val iterable = parseExpression()
        val body = parseRequiredBlock("for requires a code block")
        return ForStatement(variable, iterable, body, start.location)
    }

    private fun parseRepeat(start: ScriptToken): RepeatStatement {
        val count = parseExpression()
        val body = parseRequiredBlock("repeat requires a code block")
        return RepeatStatement(count, body, start.location)
    }

    private fun parseReturn(start: ScriptToken): ReturnStatement {
        val value = if (check(TokenType.NEWLINE) || check(TokenType.SEMICOLON) || check(TokenType.RIGHT_BRACE) || isAtEnd()) {
            null
        } else {
            parseExpression()
        }
        consumeTerminator()
        return ReturnStatement(value, start.location)
    }

    private fun parseRequiredBlock(message: String): BlockStatement {
        skipSeparators()
        if (!match(TokenType.LEFT_BRACE)) {
            report(peek(), message)
            return BlockStatement(emptyList(), peek().location)
        }
        return parseBlock(previous())
    }

    private fun parseExpression(): Expression = parseOr()

    private fun parseOr(): Expression = parseBinary(::parseAnd, TokenType.OR_OR, BinaryOperator.OR)

    private fun parseAnd(): Expression = parseBinary(::parseEquality, TokenType.AND_AND, BinaryOperator.AND)

    private fun parseEquality(): Expression {
        var expression = parseComparison()
        while (match(TokenType.EQUAL_EQUAL, TokenType.BANG_EQUAL)) {
            val operator = if (previous().type == TokenType.EQUAL_EQUAL) BinaryOperator.EQUAL else BinaryOperator.NOT_EQUAL
            val right = parseComparison()
            expression = BinaryExpression(expression, operator, right, expression.location)
        }
        return expression
    }

    private fun parseComparison(): Expression {
        var expression = parseTerm()
        while (match(TokenType.IN, TokenType.LESS, TokenType.LESS_EQUAL, TokenType.GREATER, TokenType.GREATER_EQUAL)) {
            val operator = when (previous().type) {
                TokenType.IN -> BinaryOperator.IN
                TokenType.LESS -> BinaryOperator.LESS
                TokenType.LESS_EQUAL -> BinaryOperator.LESS_EQUAL
                TokenType.GREATER -> BinaryOperator.GREATER
                else -> BinaryOperator.GREATER_EQUAL
            }
            val right = parseTerm()
            expression = BinaryExpression(expression, operator, right, expression.location)
        }
        return expression
    }

    private fun parseTerm(): Expression {
        var expression = parseFactor()
        while (match(TokenType.PLUS, TokenType.MINUS)) {
            val operator = if (previous().type == TokenType.PLUS) BinaryOperator.ADD else BinaryOperator.SUBTRACT
            val right = parseFactor()
            expression = BinaryExpression(expression, operator, right, expression.location)
        }
        return expression
    }

    private fun parseFactor(): Expression {
        var expression = parseUnary()
        while (match(TokenType.STAR, TokenType.SLASH)) {
            val operator = if (previous().type == TokenType.STAR) BinaryOperator.MULTIPLY else BinaryOperator.DIVIDE
            val right = parseUnary()
            expression = BinaryExpression(expression, operator, right, expression.location)
        }
        return expression
    }

    private fun parseUnary(): Expression {
        if (match(TokenType.BANG, TokenType.MINUS)) {
            val operator = if (previous().type == TokenType.BANG) UnaryOperator.NOT else UnaryOperator.NEGATE
            val operand = parseUnary()
            return UnaryExpression(operator, operand, previous().location)
        }
        return parsePostfix()
    }

    private fun parsePostfix(): Expression {
        var expression = parsePrimary()
        while (true) {
            expression = when {
                match(TokenType.DOT) -> {
                    val name = consume(TokenType.IDENTIFIER, "Member name must be an identifier")
                    MemberAccessExpression(expression, name.lexeme, name.location)
                }
                match(TokenType.LEFT_PAREN) -> parseCall(expression)
                else -> return expression
            }
        }
    }

    private fun parseCall(callee: Expression): Expression {
        val arguments = mutableListOf<CallArgument>()
        if (!check(TokenType.RIGHT_PAREN)) {
            do {
                if (check(TokenType.IDENTIFIER) && checkNext(TokenType.ASSIGN)) {
                    val name = advance().lexeme
                    advance()
                    arguments += CallArgument.Named(name, parseExpression())
                } else {
                    arguments += CallArgument.Positional(parseExpression())
                }
            } while (match(TokenType.COMMA))
        }
        val closing = consume(TokenType.RIGHT_PAREN, "Function call requires ')'")
        return CallExpression(callee, arguments, closing.location)
    }

    private fun parsePrimary(): Expression {
        val token = advance()
        return when (token.type) {
            TokenType.NUMBER -> LiteralExpression(LiteralValue.Number(token.lexeme.toDouble()), token.location)
            TokenType.STRING -> LiteralExpression(LiteralValue.StringValue(token.lexeme), token.location)
            TokenType.DURATION -> LiteralExpression(parseDuration(token), token.location)
            TokenType.PERCENTAGE -> LiteralExpression(LiteralValue.Percentage(token.lexeme.dropLast(1).toDouble()), token.location)
            TokenType.TRUE -> LiteralExpression(LiteralValue.BooleanValue(true), token.location)
            TokenType.FALSE -> LiteralExpression(LiteralValue.BooleanValue(false), token.location)
            TokenType.NULL -> LiteralExpression(LiteralValue.NullValue, token.location)
            TokenType.IDENTIFIER -> IdentifierExpression(token.lexeme, token.location)
            TokenType.LEFT_PAREN -> {
                val expression = parseExpression()
                consume(TokenType.RIGHT_PAREN, "Expression requires ')'")
                expression
            }
            else -> {
                report(token, "Expected an expression")
                LiteralExpression(LiteralValue.NullValue, token.location)
            }
        }
    }

    private fun parseDuration(token: ScriptToken): LiteralValue.Duration {
        val unit = when (token.lexeme.last()) {
            't' -> DurationUnit.TICKS
            's' -> DurationUnit.SECONDS
            'm' -> DurationUnit.MINUTES
            else -> DurationUnit.TICKS
        }
        return LiteralValue.Duration(token.lexeme.dropLast(1).toDouble(), unit)
    }

    private fun parseBinary(
        next: () -> Expression,
        tokenType: TokenType,
        operator: BinaryOperator
    ): Expression {
        var expression = next()
        while (match(tokenType)) {
            val right = next()
            expression = BinaryExpression(expression, operator, right, expression.location)
        }
        return expression
    }

    private fun consumeTerminator() {
        if (match(TokenType.SEMICOLON)) {
            match(TokenType.NEWLINE)
            return
        }
        if (match(TokenType.NEWLINE)) return
        if (!check(TokenType.RIGHT_BRACE) && !isAtEnd()) {
            report(peek(), "Statement requires a newline or ';'")
            synchronizeStatement()
        }
    }

    private fun skipSeparators() {
        while (match(TokenType.NEWLINE, TokenType.SEMICOLON)) Unit
    }

    private fun synchronizeTopLevel() {
        while (!isAtEnd() && !check(TokenType.NEWLINE) && !check(TokenType.SKILL)) advance()
    }

    private fun synchronizeStatement() {
        while (!isAtEnd() && !check(TokenType.NEWLINE) && !check(TokenType.RIGHT_BRACE)) advance()
        match(TokenType.NEWLINE)
    }

    private fun report(token: ScriptToken, message: String) {
        diagnostics += ScriptDiagnostic(sourceName, token.location, message)
    }

    private fun consume(type: TokenType, message: String): ScriptToken {
        if (check(type)) return advance()
        report(peek(), message)
        return ScriptToken(type, "", peek().location)
    }

    private fun match(vararg types: TokenType): Boolean {
        if (types.any(::check)) {
            advance()
            return true
        }
        return false
    }

    private fun check(type: TokenType): Boolean = !isAtEnd() && peek().type == type

    private fun checkNext(type: TokenType): Boolean = if (current + 1 >= tokens.size) false else tokens[current + 1].type == type

    private fun checkAt(offset: Int, type: TokenType): Boolean =
        if (current + offset >= tokens.size) false else tokens[current + offset].type == type

    private fun isStateAssignment(): Boolean {
        if (!check(TokenType.IDENTIFIER) || peek().lexeme != "state") return false
        if (!checkAt(1, TokenType.IDENTIFIER)) return false
        return checkAt(2, TokenType.ASSIGN) || checkAt(2, TokenType.PLUS_ASSIGN) || checkAt(2, TokenType.MINUS_ASSIGN)
    }

    private fun advance(): ScriptToken {
        if (!isAtEnd()) current++
        return previous()
    }

    private fun isAtEnd(): Boolean = peek().type == TokenType.EOF

    private fun peek(): ScriptToken = tokens[current]

    private fun previous(): ScriptToken = tokens[current - 1]
}
