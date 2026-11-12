package org.ewsk.spectrummobs.script

import kotlin.math.roundToLong

class RuntimeEvaluator {
    fun evaluateRule(name: String, arguments: List<RuntimeArgument>, context: RuntimeContext): RuntimeValue =
        invokeRule(name, arguments, context)

    fun evaluate(expression: IrExpression, context: RuntimeContext): RuntimeValue = when (expression) {
        is IrLiteral -> evaluateLiteral(expression.value, context)
        is IrIdentifier -> context.variables[expression.name]
            ?: throw RuntimeEvaluationException("Runtime variable '${expression.name}' is missing")
        is IrMember -> evaluateMember(expression, context)
        is IrCall -> evaluateCall(expression, context)
        is IrBinary -> evaluateBinary(expression, context)
        is IrUnary -> evaluateUnary(expression, context)
        is IrErrorExpression -> throw RuntimeEvaluationException("Cannot execute an expression containing errors")
    }

    private fun evaluateLiteral(value: LiteralValue, context: RuntimeContext): RuntimeValue = when (value) {
        is LiteralValue.Number -> RuntimeValue.NumberValue(value.value)
        is LiteralValue.StringValue -> RuntimeValue.TextValue(interpolate(value.value, context))
        is LiteralValue.Duration -> RuntimeValue.DurationValue(toTicks(value))
        is LiteralValue.Percentage -> RuntimeValue.PercentageValue(value.value)
        is LiteralValue.BooleanValue -> RuntimeValue.BooleanValue(value.value)
        LiteralValue.NullValue -> RuntimeValue.NullValue
    }

    private fun evaluateMember(expression: IrMember, context: RuntimeContext): RuntimeValue {
        val receiver = evaluate(expression.receiver, context)
        if (receiver is RuntimeValue.ServiceValue && expression.type is ScriptType.Service) {
            return RuntimeValue.ServiceValue(expression.type.path)
        }
        return memberValue(receiver, expression.name)
    }

    private fun memberValue(receiver: RuntimeValue, name: String): RuntimeValue {
        return when (receiver) {
            is RuntimeValue.EntityValue -> when (name) {
                "health" -> {
                    val current = receiver.currentHealth
                        ?: throw RuntimeEvaluationException("Entity ${receiver.id} has no current health")
                    val max = receiver.maxHealth
                        ?: throw RuntimeEvaluationException("Entity ${receiver.id} has no maximum health")
                    RuntimeValue.HealthValue(current, max)
                }
                "position" -> receiver.position
                    ?: throw RuntimeEvaluationException("Entity ${receiver.id} has no position")
                else -> throw RuntimeEvaluationException("Entity has no member '$name'")
            }
            is RuntimeValue.HealthValue -> when (name) {
                "percent" -> RuntimeValue.PercentageValue(
                    if (receiver.max == 0.0) 0.0 else receiver.current / receiver.max * 100.0
                )
                "value" -> RuntimeValue.NumberValue(receiver.current)
                else -> throw RuntimeEvaluationException("Health has no member '$name'")
            }
            is RuntimeValue.EventValue -> when (name) {
                "name" -> RuntimeValue.TextValue(receiver.name)
                "damage" -> RuntimeValue.NumberValue(receiver.damage)
                "attacker" -> receiver.attacker ?: RuntimeValue.NullValue
                "cause" -> RuntimeValue.TextValue(receiver.cause)
                "hand" -> RuntimeValue.TextValue(receiver.hand)
                else -> throw RuntimeEvaluationException("Event has no member '$name'")
            }
            is RuntimeValue.ListValue -> when (name) {
                "size" -> RuntimeValue.NumberValue(receiver.values.size.toDouble())
                "isEmpty" -> RuntimeValue.BooleanValue(receiver.values.isEmpty())
                else -> throw RuntimeEvaluationException("List has no member '$name'")
            }
            is RuntimeValue.ServiceValue -> RuntimeValue.FunctionValue("${receiver.name}.$name")
            else -> throw RuntimeEvaluationException("Runtime value has no member '$name'")
        }
    }

    private fun evaluateCall(expression: IrCall, context: RuntimeContext): RuntimeValue {
        val name = (evaluate(expression.callee, context) as? RuntimeValue.FunctionValue)?.name
            ?: throw RuntimeEvaluationException("Runtime value is not a registered function")
        val arguments = expression.arguments.map { argument ->
            when (argument) {
                is IrCallArgument.Positional -> RuntimeArgument(null, evaluate(argument.value, context))
                is IrCallArgument.Named -> RuntimeArgument(argument.name, evaluate(argument.value, context))
            }
        }
        return if (context.actions.contains(name)) {
            context.invokeAction(name, arguments)
        } else {
            invokeRule(name, arguments, context)
        }
    }

    private fun invokeRule(name: String, arguments: List<RuntimeArgument>, context: RuntimeContext): RuntimeValue {
        val rule = context.ruleFunctions[name.lowercase()]
            ?: throw RuntimeEvaluationException("Rule function is not available: $name")
        val topLevel = context.callDepth == 0
        if (topLevel) context.ruleSteps = 0
        context.callDepth++
        if (context.callDepth > context.maxCallDepth) {
            context.callDepth--
            throw RuntimeEvaluationException("Maximum rule call depth exceeded: ${context.maxCallDepth}")
        }
        val previous = context.variables.toMap()
        try {
            val supplied = arguments.associateBy { it.name }
            rule.parameters.forEach { parameter ->
                val value = supplied[parameter.name]?.value
                    ?: parameter.defaultValue?.let { evaluate(it, context) }
                    ?: throw RuntimeEvaluationException("Missing rule argument: ${parameter.name}")
                context.variables[parameter.name] = value
            }
            val result = executeRuleBlock(rule.body, context)
            if (result is RuleReturn) return result.value
            throw RuntimeEvaluationException("Rule function does not return a value: ${rule.qualifiedName}")
        } finally {
            context.variables.clear()
            context.variables.putAll(previous)
            context.callDepth--
            if (topLevel) context.ruleSteps = 0
        }
    }

    private fun executeRuleBlock(statements: List<IrStatement>, context: RuntimeContext): RuleReturn? {
        for (statement in statements) {
            context.ruleSteps++
            if (context.ruleSteps > context.maxRuleSteps) {
                throw RuntimeEvaluationException("Maximum rule evaluation steps exceeded: ${context.maxRuleSteps}")
            }
            when (statement) {
                is IrBlock -> executeRuleBlock(statement.statements, context)?.let { return it }
                is IrLet -> context.variables[statement.name] = evaluate(statement.value, context)
                is IrIf -> {
                    val condition = evaluate(statement.condition, context) as? RuntimeValue.BooleanValue
                        ?: throw RuntimeEvaluationException("Rule if condition is not Boolean")
                    val block = if (condition.value) statement.thenBranch else statement.elseBranch
                    block?.let { executeRuleBlock(it.statements, context)?.let { result -> return result } }
                }
                is IrFor -> {
                    val list = evaluate(statement.iterable, context) as? RuntimeValue.ListValue
                        ?: throw RuntimeEvaluationException("Rule for target is not a list")
                    list.values.forEach { value ->
                        context.variables[statement.variable] = value
                        executeRuleBlock(statement.body.statements, context)?.let { return it }
                    }
                }
                is IrRepeat -> {
                    val number = (evaluate(statement.count, context) as? RuntimeValue.NumberValue)?.value
                        ?: throw RuntimeEvaluationException("Rule repeat count is not Number")
                    require(number.isFinite() && number >= 0.0 && number % 1.0 == 0.0) { "Rule repeat count must be a non-negative integer" }
                    repeat(number.toInt()) { executeRuleBlock(statement.body.statements, context)?.let { return it } }
                }
                is IrReturn -> return RuleReturn(statement.value?.let { evaluate(it, context) }
                    ?: throw RuntimeEvaluationException("Rule return requires a value"))
                is IrExpressionStatement -> evaluate(statement.expression, context)
                is IrWait, is IrStateAssignment -> throw RuntimeEvaluationException("Rule function contains a forbidden statement")
            }
        }
        return null
    }

    private data class RuleReturn(val value: RuntimeValue)

    private fun evaluateBinary(expression: IrBinary, context: RuntimeContext): RuntimeValue {
        val left = evaluate(expression.left, context)
        if (expression.operator == BinaryOperator.AND && !asBoolean(left)) return RuntimeValue.BooleanValue(false)
        if (expression.operator == BinaryOperator.OR && asBoolean(left)) return RuntimeValue.BooleanValue(true)
        val right = evaluate(expression.right, context)
        return when (expression.operator) {
            BinaryOperator.OR -> RuntimeValue.BooleanValue(asBoolean(left) || asBoolean(right))
            BinaryOperator.AND -> RuntimeValue.BooleanValue(asBoolean(left) && asBoolean(right))
            BinaryOperator.IN -> RuntimeValue.BooleanValue(contains(right, left))
            BinaryOperator.EQUAL -> RuntimeValue.BooleanValue(left == right)
            BinaryOperator.NOT_EQUAL -> RuntimeValue.BooleanValue(left != right)
            BinaryOperator.LESS -> RuntimeValue.BooleanValue(compare(left, right) < 0)
            BinaryOperator.LESS_EQUAL -> RuntimeValue.BooleanValue(compare(left, right) <= 0)
            BinaryOperator.GREATER -> RuntimeValue.BooleanValue(compare(left, right) > 0)
            BinaryOperator.GREATER_EQUAL -> RuntimeValue.BooleanValue(compare(left, right) >= 0)
            BinaryOperator.ADD -> RuntimeValue.NumberValue(asNumber(left) + asNumber(right))
            BinaryOperator.SUBTRACT -> RuntimeValue.NumberValue(asNumber(left) - asNumber(right))
            BinaryOperator.MULTIPLY -> RuntimeValue.NumberValue(asNumber(left) * asNumber(right))
            BinaryOperator.DIVIDE -> {
                val divisor = asNumber(right)
                if (divisor == 0.0) throw RuntimeEvaluationException("Division by zero")
                RuntimeValue.NumberValue(asNumber(left) / divisor)
            }
        }
    }

    private fun evaluateUnary(expression: IrUnary, context: RuntimeContext): RuntimeValue {
        val operand = evaluate(expression.operand, context)
        return when (expression.operator) {
            UnaryOperator.NOT -> RuntimeValue.BooleanValue(!asBoolean(operand))
            UnaryOperator.NEGATE -> RuntimeValue.NumberValue(-asNumber(operand))
        }
    }

    private fun toTicks(value: LiteralValue.Duration): Long {
        val ticks = when (value.unit) {
            DurationUnit.TICKS -> value.value
            DurationUnit.SECONDS -> value.value * 20.0
            DurationUnit.MINUTES -> value.value * 1200.0
        }
        if (!ticks.isFinite() || ticks < 0.0) {
            throw RuntimeEvaluationException("Duration must be finite and non-negative")
        }
        return ticks.roundToLong()
    }

    private fun asBoolean(value: RuntimeValue): Boolean = (value as? RuntimeValue.BooleanValue)?.value
        ?: throw RuntimeEvaluationException("Expected Boolean, got ${value::class.simpleName}")

    private fun interpolate(text: String, context: RuntimeContext): String {
        return INTERPOLATION.replace(text) { match ->
            val value = resolvePath(match.groupValues[1], context)
            format(value)
        }
    }

    private fun resolvePath(path: String, context: RuntimeContext): RuntimeValue {
        val parts = path.split('.')
        var value = context.variables[parts.first()]
            ?: throw RuntimeEvaluationException("Interpolation variable is missing: ${parts.first()}")
        parts.drop(1).forEach { value = memberValue(value, it) }
        return value
    }

    private fun format(value: RuntimeValue): String = when (value) {
        RuntimeValue.NullValue -> "null"
        is RuntimeValue.NumberValue -> value.value.toString().removeSuffix(".0")
        is RuntimeValue.TextValue -> value.value
        is RuntimeValue.BooleanValue -> value.value.toString()
        is RuntimeValue.DurationValue -> "${value.ticks}t"
        is RuntimeValue.PercentageValue -> "${value.value}%"
        is RuntimeValue.EntityValue -> value.id
        is RuntimeValue.PositionValue -> "${value.x},${value.y},${value.z}"
        is RuntimeValue.ParticleValue -> value.name
        is RuntimeValue.HealthValue -> "${value.current}/${value.max}"
        is RuntimeValue.EventValue -> value.name
        is RuntimeValue.ListValue -> value.values.joinToString(",") { format(it) }
        is RuntimeValue.ServiceValue -> value.name
        is RuntimeValue.FunctionValue -> value.name
        RuntimeValue.UnitValue -> "unit"
    }

    private fun contains(container: RuntimeValue, value: RuntimeValue): Boolean {
        val list = (container as? RuntimeValue.ListValue)?.values
            ?: throw RuntimeEvaluationException("Right operand of 'in' must be a list")
        return list.any { candidate ->
            if (candidate is RuntimeValue.EntityValue && value is RuntimeValue.EntityValue) {
                candidate.id == value.id
            } else {
                candidate == value
            }
        }
    }

    private fun asNumber(value: RuntimeValue): Double = when (value) {
        is RuntimeValue.NumberValue -> value.value
        is RuntimeValue.PercentageValue -> value.value
        else -> throw RuntimeEvaluationException("Expected Number, got ${value::class.simpleName}")
    }

    private fun compare(left: RuntimeValue, right: RuntimeValue): Int =
        asNumber(left).compareTo(asNumber(right))

    private companion object {
        val INTERPOLATION = Regex("\\$\\{([A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*)}")
    }
}
