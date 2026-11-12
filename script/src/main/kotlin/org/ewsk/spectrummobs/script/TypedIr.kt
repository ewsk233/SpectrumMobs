package org.ewsk.spectrummobs.script

sealed interface TypedIrNode {
    val location: SourceLocation
}

data class TypedScriptFile(
    val skills: List<TypedSkill>,
    val rules: List<TypedRuleFunction> = emptyList(),
    override val location: SourceLocation = SourceLocation(0, 1, 1)
) : TypedIrNode

data class TypedSkillParameter(
    val name: String,
    val type: ScriptType,
    val defaultValue: IrExpression?
)

data class TypedRuleFunction(
    val qualifiedName: String,
    val parameters: List<TypedSkillParameter>,
    val returnType: ScriptType,
    val body: List<IrStatement>,
    val source: String,
    override val location: SourceLocation
) : TypedIrNode

data class TypedSkill(
    val name: String,
    val parameters: List<TypedSkillParameter> = emptyList(),
    val cooldown: IrExpression?,
    val requires: IrExpression?,
    val body: List<IrStatement>,
    override val location: SourceLocation,
    val id: String = "spectrum:$name",
    val source: String = "<memory>",
    val casting: SkillCastingPolicy = SkillCastingPolicy()
) : TypedIrNode

sealed interface IrStatement : TypedIrNode

data class IrBlock(
    val statements: List<IrStatement>,
    override val location: SourceLocation
) : IrStatement

data class IrLet(
    val name: String,
    val value: IrExpression,
    override val location: SourceLocation
) : IrStatement

data class IrStateAssignment(
    val name: String,
    val operator: StateAssignmentOperator,
    val value: IrExpression,
    override val location: SourceLocation
) : IrStatement

data class IrWait(
    val duration: IrExpression,
    override val location: SourceLocation
) : IrStatement

data class IrIf(
    val condition: IrExpression,
    val thenBranch: IrBlock,
    val elseBranch: IrBlock?,
    override val location: SourceLocation
) : IrStatement

data class IrFor(
    val variable: String,
    val variableType: ScriptType,
    val iterable: IrExpression,
    val body: IrBlock,
    override val location: SourceLocation
) : IrStatement

data class IrRepeat(
    val count: IrExpression,
    val body: IrBlock,
    override val location: SourceLocation
) : IrStatement

data class IrReturn(
    val value: IrExpression?,
    override val location: SourceLocation
) : IrStatement

data class IrExpressionStatement(
    val expression: IrExpression,
    override val location: SourceLocation
) : IrStatement

sealed interface IrExpression : TypedIrNode {
    val type: ScriptType
}

data class IrLiteral(
    val value: LiteralValue,
    override val type: ScriptType,
    override val location: SourceLocation
) : IrExpression

data class IrIdentifier(
    val name: String,
    override val type: ScriptType,
    override val location: SourceLocation
) : IrExpression

data class IrMember(
    val receiver: IrExpression,
    val name: String,
    override val type: ScriptType,
    override val location: SourceLocation
) : IrExpression

data class IrCall(
    val callee: IrExpression,
    val arguments: List<IrCallArgument>,
    override val type: ScriptType,
    override val location: SourceLocation
) : IrExpression

data class IrBinary(
    val left: IrExpression,
    val operator: BinaryOperator,
    val right: IrExpression,
    override val type: ScriptType,
    override val location: SourceLocation
) : IrExpression

data class IrUnary(
    val operator: UnaryOperator,
    val operand: IrExpression,
    override val type: ScriptType,
    override val location: SourceLocation
) : IrExpression

data class IrErrorExpression(
    override val location: SourceLocation
) : IrExpression {
    override val type: ScriptType = ScriptType.Unknown
}

sealed interface IrCallArgument {
    val value: IrExpression

    data class Positional(override val value: IrExpression) : IrCallArgument
    data class Named(val name: String, override val value: IrExpression) : IrCallArgument
}
