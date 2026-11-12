package org.ewsk.spectrummobs.script

sealed interface AstNode {
    val location: SourceLocation
}

data class ScriptFile(
    val declarations: List<ScriptDeclaration>,
    override val location: SourceLocation = SourceLocation(0, 1, 1),
    val imports: List<ImportDeclaration> = emptyList()
) : AstNode

sealed interface ScriptDeclaration : AstNode

data class ImportDeclaration(
    val moduleId: String,
    val alias: String,
    override val location: SourceLocation
) : AstNode

data class ScriptParameterDeclaration(
    val name: String,
    val typeName: TypeName,
    val defaultValue: Expression?,
    override val location: SourceLocation
) : AstNode

sealed interface TypeName {
    data class Simple(val name: String) : TypeName
    data class ListOf(val element: TypeName) : TypeName
}

data class SkillDeclaration(
    val name: String,
    val parameters: List<ScriptParameterDeclaration>,
    val cooldown: Expression?,
    val requires: Expression?,
    val body: BlockStatement,
    override val location: SourceLocation,
    val casting: Map<String, Expression> = emptyMap()
) : ScriptDeclaration

data class RuleFunctionDeclaration(
    val name: String,
    val parameters: List<ScriptParameterDeclaration>,
    val returnType: TypeName,
    val body: BlockStatement,
    override val location: SourceLocation
) : ScriptDeclaration

sealed interface Statement : AstNode

data class BlockStatement(
    val statements: List<Statement>,
    override val location: SourceLocation
) : Statement

data class VariableDeclaration(
    val name: String,
    val initializer: Expression,
    override val location: SourceLocation
) : Statement

data class StateAssignmentStatement(
    val name: String,
    val operator: StateAssignmentOperator,
    val value: Expression,
    override val location: SourceLocation
) : Statement

data class WaitStatement(
    val duration: Expression,
    override val location: SourceLocation
) : Statement

data class IfStatement(
    val condition: Expression,
    val thenBranch: BlockStatement,
    val elseBranch: BlockStatement?,
    override val location: SourceLocation
) : Statement

data class ForStatement(
    val variable: String,
    val iterable: Expression,
    val body: BlockStatement,
    override val location: SourceLocation
) : Statement

data class RepeatStatement(
    val count: Expression,
    val body: BlockStatement,
    override val location: SourceLocation
) : Statement

data class ReturnStatement(
    val value: Expression?,
    override val location: SourceLocation
) : Statement

data class ExpressionStatement(
    val expression: Expression,
    override val location: SourceLocation
) : Statement

sealed interface Expression : AstNode

data class LiteralExpression(
    val value: LiteralValue,
    override val location: SourceLocation
) : Expression

data class IdentifierExpression(
    val name: String,
    override val location: SourceLocation
) : Expression

data class MemberAccessExpression(
    val receiver: Expression,
    val name: String,
    override val location: SourceLocation
) : Expression

data class CallExpression(
    val callee: Expression,
    val arguments: List<CallArgument>,
    override val location: SourceLocation
) : Expression

data class BinaryExpression(
    val left: Expression,
    val operator: BinaryOperator,
    val right: Expression,
    override val location: SourceLocation
) : Expression

data class UnaryExpression(
    val operator: UnaryOperator,
    val operand: Expression,
    override val location: SourceLocation
) : Expression

sealed interface CallArgument {
    data class Positional(val value: Expression) : CallArgument
    data class Named(val name: String, val value: Expression) : CallArgument
}

sealed interface LiteralValue {
    data class Number(val value: Double) : LiteralValue
    data class StringValue(val value: String) : LiteralValue
    data class Duration(val value: Double, val unit: DurationUnit) : LiteralValue
    data class Percentage(val value: Double) : LiteralValue
    data class BooleanValue(val value: Boolean) : LiteralValue
    data object NullValue : LiteralValue
}

enum class DurationUnit {
    TICKS,
    SECONDS,
    MINUTES
}

enum class BinaryOperator {
    OR,
    AND,
    IN,
    EQUAL,
    NOT_EQUAL,
    LESS,
    LESS_EQUAL,
    GREATER,
    GREATER_EQUAL,
    ADD,
    SUBTRACT,
    MULTIPLY,
    DIVIDE
}

enum class UnaryOperator {
    NOT,
    NEGATE
}

enum class StateAssignmentOperator {
    SET,
    ADD,
    SUBTRACT
}
