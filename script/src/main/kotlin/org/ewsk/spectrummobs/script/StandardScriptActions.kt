package org.ewsk.spectrummobs.script

/** 不访问服务器的内置 Targeter 与 Condition，使用同一套实现进行模拟测试和服务器执行。 */
object StandardScriptActions {
    fun bind(builder: ScriptApiBindings.Builder): ScriptApiBindings.Builder = builder
        .bind("targeter.self") { context, _ -> context.resolveEntity(context.caster) ?: RuntimeValue.NullValue }
        .bind("targeter.target") { context, _ -> context.resolveEntity(context.target) ?: RuntimeValue.NullValue }
        .bind("targeter.attacker") { context, _ ->
            val attacker = context.event.attacker
            attacker?.let(context::resolveEntity) ?: RuntimeValue.NullValue
        }
        .bind("targeter.random") { context, arguments ->
            val targets = arguments.value("targets", 0) as? RuntimeValue.ListValue
                ?: throw RuntimeEvaluationException("Random targeter requires a list")
            val count = arguments.number("count", 1)
            require(count.isFinite() && count >= 0 && count % 1.0 == 0.0) { "Random target count must be a non-negative integer" }
            val valid = targets.values.mapNotNull(context::resolveEntity).distinctBy { it.id }
            RuntimeValue.ListValue(valid.shuffled().take(count.coerceAtMost(context.maxQueryTargets.toDouble()).toInt()))
        }
        .bind("condition.all") { _, args -> RuntimeValue.BooleanValue(args.boolean("left", 0) && args.boolean("right", 1)) }
        .bind("condition.any") { _, args -> RuntimeValue.BooleanValue(args.boolean("left", 0) || args.boolean("right", 1)) }
        .bind("condition.not") { _, args -> RuntimeValue.BooleanValue(!args.boolean("value", 0)) }
        .bind("condition.isPlayer") { context, args ->
            RuntimeValue.BooleanValue(context.resolveEntity(args.value("entity", 0))?.kind == RuntimeValue.EntityKind.PLAYER)
        }
        .bind("condition.isLiving") { context, args ->
            RuntimeValue.BooleanValue(context.resolveEntity(args.value("entity", 0))?.currentHealth != null)
        }
        .bind("condition.healthBelow") { context, args -> healthCondition(context, args) { current, threshold -> current < threshold } }
        .bind("condition.healthAbove") { context, args -> healthCondition(context, args) { current, threshold -> current > threshold } }
        .bind("condition.distanceBelow") { context, args ->
            val first = context.resolveEntity(args.value("first", 0))?.position
            val second = context.resolveEntity(args.value("second", 1))?.position
            val radius = args.number("radius", 2)
            require(radius.isFinite() && radius >= 0.0) { "Condition radius must be finite and non-negative" }
            RuntimeValue.BooleanValue(first != null && second != null && RuntimeTargeting.distanceSquared(first, second) < radius * radius)
        }
        .bind("condition.eventCause") { context, args ->
            val expected = (args.value("cause", 0) as? RuntimeValue.TextValue)?.value
                ?: throw RuntimeEvaluationException("Event cause must be text")
            RuntimeValue.BooleanValue(context.event.cause.equals(expected, true))
        }

    private fun healthCondition(
        context: RuntimeContext,
        args: List<RuntimeArgument>,
        compare: (Double, Double) -> Boolean
    ): RuntimeValue {
        val entity = context.resolveEntity(args.value("entity", 0)) ?: return RuntimeValue.BooleanValue(false)
        val current = entity.currentHealth ?: return RuntimeValue.BooleanValue(false)
        val threshold = when (val value = args.value("threshold", 1)) {
            is RuntimeValue.NumberValue -> value.value.also {
                require(it.isFinite() && it >= 0.0) { "Health threshold must be finite and non-negative" }
            }
            is RuntimeValue.PercentageValue -> {
                require(value.value.isFinite() && value.value in 0.0..100.0) { "Health percentage must be between 0 and 100" }
                (entity.maxHealth ?: return RuntimeValue.BooleanValue(false)) * value.value / 100.0
            }
            else -> throw RuntimeEvaluationException("Health threshold must be Number or Percent")
        }
        return RuntimeValue.BooleanValue(compare(current, threshold))
    }

    private fun List<RuntimeArgument>.value(name: String, index: Int): RuntimeValue =
        firstOrNull { it.name == name }?.value ?: filter { it.name == null }.getOrNull(index)?.value
        ?: throw RuntimeEvaluationException("Missing runtime argument: $name")

    private fun List<RuntimeArgument>.number(name: String, index: Int): Double =
        (value(name, index) as? RuntimeValue.NumberValue)?.value ?: throw RuntimeEvaluationException("Argument $name must be Number")

    private fun List<RuntimeArgument>.boolean(name: String, index: Int): Boolean =
        (value(name, index) as? RuntimeValue.BooleanValue)?.value ?: throw RuntimeEvaluationException("Argument $name must be Boolean")
}
