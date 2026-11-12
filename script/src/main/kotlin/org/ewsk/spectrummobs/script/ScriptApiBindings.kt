package org.ewsk.spectrummobs.script

/** 编译签名与运行实现一起发布，任何缺失、重复或拼错的绑定都会在构造时失败。 */
class ScriptApiBindings private constructor(
    val signatures: ScriptApiRegistry,
    val actions: RuntimeActionRegistry
) {
    fun toBuilder(): Builder = Builder(this)

    class Builder(base: ScriptApiRegistry = ScriptApiRegistry.default()) {
        private val signatures = base.toBuilder()
        private val actions = RuntimeActionRegistry()

        val unboundNames: Set<String> get() = signatures.build().names - actions.names

        constructor(base: ScriptApiBindings) : this(base.signatures) {
            base.actions.names.forEach { actions.register(it, base.actions.action(it)) }
        }

        fun bind(name: String, action: RuntimeAction): Builder {
            val signature = signatures.build().signature(name)
                ?: error("Script API signature is not registered: $name")
            actions.register(name) { context, args ->
                val value = action.invoke(context, args)
                if (!matches(value, signature.returnType)) {
                    throw RuntimeEvaluationException("Script API $name returned ${value::class.simpleName}; expected ${signature.returnType.displayName}")
                }
                if (signature.kind == ScriptApiKind.TARGETER && value is RuntimeValue.ListValue) {
                    RuntimeValue.ListValue(value.values.distinctBy { (it as RuntimeValue.EntityValue).id }.take(context.maxQueryTargets))
                } else value
            }
            return this
        }

        fun register(name: String, parameters: List<FunctionParameter>, returnType: ScriptType, safeInHeaders: Boolean? = null, action: RuntimeAction): Builder {
            signatures.function(name, parameters, returnType, safeInHeaders)
            return bind(name, action)
        }

        fun build(): ScriptApiBindings {
            val registry = signatures.build()
            val missing = unboundNames
            check(missing.isEmpty()) { "Script API implementations are missing: ${missing.sorted().joinToString()}" }
            return ScriptApiBindings(registry, actions.snapshot())
        }

        private fun matches(value: RuntimeValue, type: ScriptType): Boolean = when (type) {
            ScriptType.Entity -> value is RuntimeValue.EntityValue || value == RuntimeValue.NullValue
            ScriptType.Player -> value == RuntimeValue.NullValue || (value is RuntimeValue.EntityValue && value.kind == RuntimeValue.EntityKind.PLAYER)
            ScriptType.Number -> value is RuntimeValue.NumberValue
            ScriptType.Numeric -> value is RuntimeValue.NumberValue || value is RuntimeValue.PercentageValue
            ScriptType.Percentage -> value is RuntimeValue.PercentageValue
            ScriptType.Text -> value is RuntimeValue.TextValue
            ScriptType.Boolean -> value is RuntimeValue.BooleanValue
            ScriptType.Duration -> value is RuntimeValue.DurationValue
            ScriptType.Position -> value is RuntimeValue.PositionValue
            ScriptType.Particle -> value is RuntimeValue.ParticleValue
            ScriptType.Health -> value is RuntimeValue.HealthValue
            ScriptType.EventContext -> value is RuntimeValue.EventValue
            ScriptType.Null -> value == RuntimeValue.NullValue
            ScriptType.Unit -> value == RuntimeValue.UnitValue
            is ScriptType.ListType -> value is RuntimeValue.ListValue && value.values.all { it != RuntimeValue.NullValue && matches(it, type.elementType) }
            is ScriptType.TypeParameter -> matches(value, type.bound)
            else -> false
        }
    }
}
