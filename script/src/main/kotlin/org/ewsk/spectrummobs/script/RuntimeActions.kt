package org.ewsk.spectrummobs.script

import java.util.UUID

data class RuntimeArgument(
    val name: String?,
    val value: RuntimeValue
)

fun interface RuntimeAction {
    fun invoke(context: RuntimeContext, arguments: List<RuntimeArgument>): RuntimeValue
}

class RuntimeActionRegistry private constructor(
    private val actions: MutableMap<String, RuntimeAction>,
    private val frozen: Boolean
) {
    constructor() : this(linkedMapOf(), false)

    val names: Set<String> get() = actions.keys.toSet()

    internal fun action(name: String): RuntimeAction = actions.getValue(name.lowercase())

    fun snapshot(): RuntimeActionRegistry = RuntimeActionRegistry(actions.toMutableMap(), true)

    fun register(name: String, action: RuntimeAction): RuntimeActionRegistry {
        check(!frozen) { "Runtime action registry is frozen" }
        val normalized = name.trim().lowercase()
        require(normalized.isNotEmpty()) { "Action name must not be blank" }
        check(normalized !in actions) { "Action is already registered: $normalized" }
        actions[normalized] = action
        return this
    }

    fun replace(name: String, action: RuntimeAction): RuntimeActionRegistry {
        check(!frozen) { "Runtime action registry is frozen" }
        val normalized = name.trim().lowercase()
        require(normalized.isNotEmpty()) { "Action name must not be blank" }
        actions[normalized] = action
        return this
    }

    internal fun invoke(name: String, context: RuntimeContext, arguments: List<RuntimeArgument>): RuntimeValue {
        val action = actions[name.lowercase()]
            ?: throw RuntimeEvaluationException("Runtime action is not registered: $name")
        return action.invoke(context, arguments)
    }

    fun contains(name: String): Boolean = name.lowercase() in actions
}

/** 平台负责解析当前实体；默认实现供无服务器测试使用。失效实体返回 null。 */
fun interface RuntimeEntityResolver {
    fun resolve(entity: RuntimeValue.EntityValue): RuntimeValue.EntityValue?
}

data class RuntimeLimits(
    val maxStepsPerResume: Int = 256,
    val maxActiveExecutions: Int = 2048,
    val maxCallDepth: Int = 16,
    val maxLoopIterations: Int = 1000,
    val maxStateEntries: Int = 128,
    val maxQueryTargets: Int = 64,
    val maxActiveProjectiles: Int = 2048
) {
    init {
        require(maxStepsPerResume > 0) { "maxStepsPerResume must be greater than 0" }
        require(maxActiveExecutions > 0) { "maxActiveExecutions must be greater than 0" }
        require(maxCallDepth > 0) { "maxCallDepth must be greater than 0" }
        require(maxLoopIterations > 0) { "maxLoopIterations must be greater than 0" }
        require(maxStateEntries > 0) { "maxStateEntries must be greater than 0" }
        require(maxQueryTargets > 0) { "maxQueryTargets must be greater than 0" }
        require(maxActiveProjectiles > 0) { "maxActiveProjectiles must be greater than 0" }
    }
}

class RuntimeContext(
    variables: Map<String, RuntimeValue>,
    val actions: RuntimeActionRegistry,
    val casterKey: String,
    val maxCallDepth: Int,
    val maxQueryTargets: Int = 64,
    val worldKey: String? = null,
    val maxStateEntries: Int = 128,
    state: MutableMap<String, RuntimeValue> = linkedMapOf(),
    val targetStates: MutableMap<String, MutableMap<String, RuntimeValue>> = linkedMapOf(),
    val worldStates: MutableMap<String, MutableMap<String, RuntimeValue>> = linkedMapOf(),
    val globalState: MutableMap<String, RuntimeValue> = linkedMapOf(),
    val entityResolver: RuntimeEntityResolver = RuntimeEntityResolver { it },
    val executionId: UUID = UUID.randomUUID(),
    val skillId: String = "<context>",
    val parentExecutionId: UUID? = null,
    val rootExecutionId: UUID = executionId,
    val skillDepth: Int = 1,
    val effects: RuntimeEffectRegistry = RuntimeEffectRegistry(),
    val ruleFunctions: Map<String, TypedRuleFunction> = emptyMap(),
    val maxRuleSteps: Int = 256
) {
    val variables: MutableMap<String, RuntimeValue> = variables.toMutableMap()
    val caster: RuntimeValue = variables["caster"] ?: RuntimeValue.EntityValue("caster")
    val target: RuntimeValue = variables["target"] ?: caster
    val event: RuntimeValue.EventValue = variables["event"] as? RuntimeValue.EventValue ?: RuntimeValue.EventValue("manual")
    val phase: String = (variables["phase"] as? RuntimeValue.TextValue)?.value ?: "default"
    val state: MutableMap<String, RuntimeValue> = state
    private val skillState: MutableMap<String, RuntimeValue> = linkedMapOf()
    var currentTick: Long = 0
        internal set
    var callDepth: Int = 0
        internal set
    var ruleSteps: Int = 0
        internal set

    fun resolveEntity(value: RuntimeValue): RuntimeValue.EntityValue? =
        (value as? RuntimeValue.EntityValue)?.let(entityResolver::resolve)

    fun stateValue(key: String): RuntimeValue? = state[key]

    fun setStateValue(key: String, value: RuntimeValue) {
        if (key !in state && state.size >= maxStateEntries) {
            throw RuntimeEvaluationException("Maximum state entries per caster exceeded: $maxStateEntries")
        }
        state[key] = value
    }

    fun scopedValue(scope: String, key: String): RuntimeValue? = scopedMap(scope)[key]

    fun setScopedValue(scope: String, key: String, value: RuntimeValue) {
        val map = scopedMap(scope)
        if (key !in map && map.size >= maxStateEntries) {
            throw RuntimeEvaluationException("Maximum state entries exceeded for scope $scope: $maxStateEntries")
        }
        map[key] = value
    }

    fun hasScopedValue(scope: String, key: String): Boolean = key in scopedMap(scope)

    private fun scopedMap(scope: String): MutableMap<String, RuntimeValue> = when (scope.lowercase()) {
        "skill" -> skillState
        "caster" -> state
        "target" -> {
            val targetEntity = target as? RuntimeValue.EntityValue
                ?: throw RuntimeEvaluationException("The target scope requires a valid target")
            targetStates.getOrPut(targetEntity.id) { linkedMapOf() }
        }
        "world" -> worldStates.getOrPut(worldKey ?: "unknown") { linkedMapOf() }
        "global" -> globalState
        else -> throw RuntimeEvaluationException("Unknown variable scope: $scope")
    }

    init {
        this.variables.putIfAbsent("caster", caster)
        this.variables.putIfAbsent("target", target)
        this.variables.putIfAbsent("event", event)
        this.variables.putIfAbsent("phase", RuntimeValue.TextValue("default"))
        this.variables.putIfAbsent("interval", RuntimeValue.TextValue("default"))
        actions.names.map { it.substringBefore('.') }.distinct().forEach {
            this.variables.putIfAbsent(it, RuntimeValue.ServiceValue(it))
        }
        PARTICLE_NAMES.forEach { this.variables.putIfAbsent(it, RuntimeValue.ParticleValue(it)) }
    }

    internal fun invokeAction(name: String, arguments: List<RuntimeArgument>): RuntimeValue {
        callDepth++
        if (callDepth > maxCallDepth) {
            callDepth--
            throw RuntimeEvaluationException("Maximum call depth exceeded: $maxCallDepth")
        }
        return try {
            actions.invoke(name, this, arguments)
        } catch (error: RuntimeEvaluationException) {
            throw error
        } catch (error: Exception) {
            throw RuntimeEvaluationException("Script API $name failed: ${error.message ?: error.javaClass.simpleName}")
        } finally {
            callDepth--
        }
    }

    private companion object {
        val PARTICLE_NAMES = setOf("FLAME", "SOUL", "PORTAL", "END_ROD", "ELECTRIC_SPARK", "SOUL_FIRE_FLAME", "REDSTONE", "ENCHANT", "WITCH")
    }
}

class RuntimeEvaluationException(message: String) : RuntimeException(message)
