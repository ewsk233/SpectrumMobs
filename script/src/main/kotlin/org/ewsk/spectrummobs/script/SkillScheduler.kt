package org.ewsk.spectrummobs.script

import java.util.UUID

sealed interface SkillStartResult {
    data class Started(val execution: SkillExecution) : SkillStartResult
    data class Rejected(val reason: String) : SkillStartResult
}

sealed interface RuntimeEvent {
    val executionId: UUID
    data class Suspended(override val executionId: UUID, val resumeAtTick: Long, val steps: Int) : RuntimeEvent
    data class BudgetYielded(override val executionId: UUID, val steps: Int) : RuntimeEvent
    data class Completed(override val executionId: UUID, val steps: Int) : RuntimeEvent
    data class Cancelled(override val executionId: UUID, val reason: String) : RuntimeEvent
    data class Failed(override val executionId: UUID, val message: String, val steps: Int) : RuntimeEvent
    data class EffectCleanupFailed(override val executionId: UUID, val effectKey: String, val message: String) : RuntimeEvent
}

class SkillScheduler(private val limits: RuntimeLimits = RuntimeLimits()) {
    private data class CooldownKey(val skillId: String, val casterKey: String)
    private data class Scope(
        val execution: SkillExecution,
        val linkedToParent: Boolean,
        val startedAt: Long,
        var terminalState: ExecutionState? = null,
        var reason: String? = null
    ) {
        val id get() = execution.id
        val context get() = execution.context
        val policy get() = execution.skill.casting
        val state get() = terminalState ?: execution.state
    }

    private val executions = linkedMapOf<UUID, SkillExecution>()
    private val scopes = linkedMapOf<UUID, Scope>()
    private val children = mutableMapOf<UUID, MutableSet<UUID>>()
    private val pendingEvents = mutableListOf<RuntimeEvent>()
    private val cooldowns = mutableMapOf<CooldownKey, Long>()
    private val states = mutableMapOf<String, MutableMap<String, RuntimeValue>>()
    private val stateWorlds = mutableMapOf<String, String?>()
    private val targetStates = mutableMapOf<String, MutableMap<String, RuntimeValue>>()
    private val worldStates = mutableMapOf<String, MutableMap<String, RuntimeValue>>()
    private val globalState = linkedMapOf<String, RuntimeValue>()
    private val evaluator = RuntimeEvaluator()
    private var currentTick: Long = 0

    val effects = RuntimeEffectRegistry(
        accepts = { id -> scopes[id]?.let { it.terminalState !in setOf(ExecutionState.CANCELLED, ExecutionState.FAILED) || !it.policy.cleanupOnCancel } == true },
        onChanged = ::prune,
        onFailure = { id, key, error -> pendingEvents += RuntimeEvent.EffectCleanupFailed(id, key, error.message ?: error.javaClass.simpleName) }
    )

    fun currentTick(): Long = currentTick
    fun activeExecutions(): List<SkillExecution> = executions.values.toList()

    fun executionInfos(): List<SkillExecutionInfo> = scopes.values.map { scope ->
        val execution = scope.execution
        val context = scope.context
        SkillExecutionInfo(scope.id, execution.skill.id, context.casterKey, context.worldKey,
            scope.policy.group, scope.state, scope.startedAt,
            if (scope.state == ExecutionState.WAITING) ((execution.waitingUntilTick ?: currentTick) - currentTick).coerceAtLeast(0) else 0,
            execution.skill.source, execution.currentLocation, context.parentExecutionId, context.rootExecutionId,
            context.skillDepth, scope.linkedToParent, children[scope.id]?.size ?: 0, effects.count(scope.id), scope.reason)
    }

    fun start(
        skill: TypedSkill,
        casterKey: String,
        variables: Map<String, RuntimeValue> = emptyMap(),
        actions: RuntimeActionRegistry = RuntimeActionRegistry(),
        worldKey: String? = null,
        entityResolver: RuntimeEntityResolver = RuntimeEntityResolver { it },
        arguments: Map<String, RuntimeValue> = emptyMap(),
        parent: RuntimeContext? = null,
        linkedToParent: Boolean = true,
        ruleFunctions: Map<String, TypedRuleFunction> = emptyMap()
    ): SkillStartResult {
        if (parent != null && executions[parent.executionId]?.context !== parent) {
            return SkillStartResult.Rejected("Parent skill execution is no longer active")
        }
        val depth = (parent?.skillDepth ?: 0) + 1
        if (depth > limits.maxCallDepth) return SkillStartResult.Rejected("Maximum skill call depth exceeded: ${limits.maxCallDepth}")
        val id = UUID.randomUUID()
        val context = RuntimeContext(variables, actions, casterKey, limits.maxCallDepth,
            limits.maxQueryTargets, worldKey, limits.maxStateEntries,
            states.getOrPut(casterKey) { linkedMapOf() }, targetStates, worldStates, globalState,
            entityResolver, id, skill.id, parent?.executionId, parent?.rootExecutionId ?: id, depth, effects,
            ruleFunctions, limits.maxStepsPerResume)
        context.currentTick = currentTick
        ruleFunctions.keys.map { it.substringBefore('.') }.distinct().forEach { namespace ->
            context.variables.putIfAbsent(namespace, RuntimeValue.ServiceValue(namespace))
        }
        stateWorlds[casterKey] = worldKey
        try {
            bindSkillParameters(skill, context, arguments)
            val requires = skill.requires?.let { evaluator.evaluate(it, context) }
            if (requires != null && (requires as? RuntimeValue.BooleanValue)?.value != true) {
                return SkillStartResult.Rejected("Skill requires condition was not satisfied")
            }
            val cooldown = skill.cooldown?.let { evaluator.evaluate(it, context) }
            val cooldownTicks = when (cooldown) {
                null -> 0L
                is RuntimeValue.DurationValue -> cooldown.ticks
                else -> throw RuntimeEvaluationException("Skill cooldown runtime value is not Duration")
            }
            val key = CooldownKey(skill.id, casterKey)
            val readyAt = cooldowns[key] ?: 0L
            if (currentTick < readyAt) return SkillStartResult.Rejected("Skill is on cooldown; wait ${readyAt - currentTick} ticks")

            val ancestors = if (parent != null && linkedToParent) ancestors(parent.executionId) else emptySet()
            val conflicts = scopes.values.filter {
                it.id !in ancestors && it.context.casterKey == casterKey && it.policy.group == skill.casting.group && isBusy(it.id)
            }.map { it.id }
            if (skill.casting.concurrency == CastingConcurrency.REJECT && conflicts.isNotEmpty()) {
                return SkillStartResult.Rejected("Casting group is already active: ${skill.casting.group}")
            }
            val replaced = if (skill.casting.concurrency == CastingConcurrency.REPLACE) linkedSubtree(conflicts) else emptySet()
            if (projectedScopeCount(replaced) + 1 > limits.maxActiveExecutions) {
                return SkillStartResult.Rejected("Maximum active skill scopes reached: ${limits.maxActiveExecutions}")
            }
            if (replaced.isNotEmpty()) cancelScopes(replaced, "Replaced by skill ${skill.id}")
            // 替换过程可能触发平台回调，再次确认父实例仍然可用。
            if (parent != null && linkedToParent && executions[parent.executionId]?.context !== parent) {
                return SkillStartResult.Rejected("Parent skill execution was cancelled during replacement")
            }
            val execution = SkillExecution(id, skill, context, limits)
            scopes[id] = Scope(execution, parent != null && linkedToParent, currentTick)
            executions[id] = execution
            if (parent != null && linkedToParent) children.getOrPut(parent.executionId) { linkedSetOf() }.add(id)
            if (cooldownTicks > 0) cooldowns[key] = if (Long.MAX_VALUE - currentTick < cooldownTicks) Long.MAX_VALUE else currentTick + cooldownTicks
            return SkillStartResult.Started(execution)
        } catch (error: RuntimeEvaluationException) {
            return SkillStartResult.Rejected("Skill start failed: ${error.message}")
        }
    }

    fun tick(tick: Long = currentTick + 1): List<RuntimeEvent> {
        require(tick >= currentTick) { "Runtime tick cannot move backwards" }
        currentTick = tick
        val events = mutableListOf<RuntimeEvent>()
        events += drainEvents()
        executions.values.toList().forEach { execution ->
            if (executions[execution.id] !== execution) return@forEach
            val result = execution.resume(tick)
            // 同步事件或替换可能在一次动作内部取消当前实例，不能再把它标记为完成。
            if (executions[execution.id] !== execution) return@forEach
            when (result) {
                is ResumeResult.NotReady -> Unit
                is ResumeResult.Suspended -> events += RuntimeEvent.Suspended(execution.id, result.resumeAtTick, result.steps)
                is ResumeResult.BudgetYielded -> events += RuntimeEvent.BudgetYielded(execution.id, result.steps)
                is ResumeResult.Completed -> {
                    executions.remove(execution.id)
                    scopes[execution.id]?.terminalState = ExecutionState.COMPLETED
                    events += RuntimeEvent.Completed(execution.id, result.steps)
                    prune(execution.id)
                }
                is ResumeResult.Cancelled -> cancelExecution(execution.id, result.reason)
                is ResumeResult.Failed -> {
                    executions.remove(execution.id)
                    val scope = scopes[execution.id] ?: return@forEach
                    scope.terminalState = ExecutionState.FAILED
                    scope.reason = result.message
                    val location = execution.currentLocation
                    events += RuntimeEvent.Failed(execution.id, "${execution.skill.source}:${location.line}:${location.column}: ${result.message}", result.steps)
                    cancelScopes(linkedSubtree(children[execution.id]?.toList().orEmpty()), "Parent execution failed: ${execution.id}")
                    if (scope.policy.cleanupOnCancel) effects.cleanup(scope.id)
                    prune(scope.id)
                }
            }
        }
        events += drainEvents()
        return events
    }

    fun cancel(executionId: UUID, reason: String = "Execution cancelled"): Boolean = cancelExecution(executionId, reason).isNotEmpty()

    fun cancelExecution(executionId: UUID, reason: String = "Execution cancelled"): List<UUID> =
        cancelScopes(linkedSubtree(listOf(executionId)), reason)

    fun cancelForPhaseChange(casterKey: String, previousPhase: String): List<UUID> {
        val ids = scopes.values.filter {
            it.context.casterKey == casterKey && it.policy.cancelOnPhaseChange &&
                it.context.phase == previousPhase
        }.map { it.id }
        return cancelScopes(linkedSubtree(ids), "Phase changed from $previousPhase")
    }

    fun cancelByCaster(casterKey: String, reason: String = "Caster lifecycle ended"): List<UUID> {
        val ids = scopes.values.filter { it.context.casterKey == casterKey }.map { it.id }
        val cancelled = cancelScopes(linkedSubtree(ids), reason, forceCleanup = true)
        states.remove(casterKey)
        targetStates.remove(casterKey)
        stateWorlds.remove(casterKey)
        cooldowns.keys.removeAll { it.casterKey == casterKey }
        return cancelled
    }

    fun cancelByWorld(worldKey: String, reason: String = "World unloaded"): List<UUID> {
        val ids = scopes.values.filter { it.context.worldKey == worldKey }.map { it.id }
        val cancelled = cancelScopes(linkedSubtree(ids), reason, forceCleanup = true)
        stateWorlds.filterValues { it == worldKey }.keys.toList().forEach { caster ->
            states.remove(caster)
            stateWorlds.remove(caster)
            cooldowns.keys.removeAll { it.casterKey == caster }
        }
        worldStates.remove(worldKey)
        return cancelled
    }

    fun cancelAll(reason: String = "Runtime disabled"): List<UUID> {
        val cancelled = cancelScopes(scopes.keys.toSet(), reason, forceCleanup = true)
        states.clear()
        stateWorlds.clear()
        targetStates.clear()
        worldStates.clear()
        globalState.clear()
        cooldowns.clear()
        return cancelled
    }

    private fun cancelScopes(ids: Set<UUID>, reason: String, forceCleanup: Boolean = false): List<UUID> {
        val selected = ids.mapNotNull(scopes::get).filter { it.terminalState != ExecutionState.CANCELLED || forceCleanup }
        // 先终止整个子树，再清理资源，防止清理回调重新启动被取消的父实例。
        selected.forEach { scope ->
            executions.remove(scope.id)
            scope.execution.cancel(reason)
            scope.terminalState = ExecutionState.CANCELLED
            scope.reason = reason
            pendingEvents += RuntimeEvent.Cancelled(scope.id, reason)
        }
        selected.asReversed().forEach { scope ->
            if (forceCleanup || scope.policy.cleanupOnCancel) effects.cleanup(scope.id)
            prune(scope.id)
        }
        return selected.map { it.id }
    }

    private fun linkedSubtree(roots: Collection<UUID>): Set<UUID> {
        val found = linkedSetOf<UUID>()
        fun visit(id: UUID) {
            if (id !in scopes || !found.add(id)) return
            children[id]?.toList()?.forEach(::visit)
        }
        roots.forEach(::visit)
        return found
    }

    private fun ancestors(id: UUID): Set<UUID> {
        val result = linkedSetOf<UUID>()
        var current: UUID? = id
        while (current != null && result.add(current)) {
            val scope = scopes[current]
            current = if (scope?.linkedToParent == true) scope.context.parentExecutionId else null
        }
        return result
    }

    private fun isBusy(id: UUID): Boolean = id in executions || children[id]?.any(::isBusy) == true

    private fun projectedScopeCount(cancelled: Set<UUID>): Int {
        val retained = mutableMapOf<UUID, Boolean>()
        fun remains(id: UUID): Boolean = retained.getOrPut(id) {
            val scope = scopes.getValue(id)
            val active = id in executions && id !in cancelled
            val retainedEffects = effects.count(id) > 0 && (id !in cancelled || !scope.policy.cleanupOnCancel)
            active || retainedEffects || children[id]?.any(::remains) == true
        }
        return scopes.keys.count(::remains)
    }

    private fun prune(id: UUID) {
        val scope = scopes[id] ?: return
        if (id in executions || effects.count(id) > 0 || !children[id].isNullOrEmpty()) return
        scopes.remove(id)
        children.remove(id)
        if (scope.linkedToParent) scope.context.parentExecutionId?.let { parent ->
            children[parent]?.remove(id)
            if (children[parent].isNullOrEmpty()) children.remove(parent)
            prune(parent)
        }
    }

    private fun drainEvents(): List<RuntimeEvent> = pendingEvents.toList().also { pendingEvents.clear() }

    private fun bindSkillParameters(skill: TypedSkill, context: RuntimeContext, arguments: Map<String, RuntimeValue>) {
        val known = skill.parameters.map { it.name }.toSet()
        arguments.keys.filterNot { it in known }.forEach { throw RuntimeEvaluationException("Unknown skill argument: $it") }
        skill.parameters.forEach { parameter ->
            val value = arguments[parameter.name]
                ?: parameter.defaultValue?.let { evaluator.evaluate(it, context) }
                ?: throw RuntimeEvaluationException("Missing skill argument: ${parameter.name}")
            if (!matches(value, parameter.type)) {
                throw RuntimeEvaluationException("Skill argument '${parameter.name}' has type ${value::class.simpleName}; expected ${parameter.type.displayName}")
            }
            context.variables[parameter.name] = value
        }
    }

    private fun matches(value: RuntimeValue, type: ScriptType): Boolean = when (type) {
        ScriptType.Number -> value is RuntimeValue.NumberValue
        ScriptType.Numeric -> value is RuntimeValue.NumberValue || value is RuntimeValue.PercentageValue
        ScriptType.Text -> value is RuntimeValue.TextValue
        ScriptType.Boolean -> value is RuntimeValue.BooleanValue
        ScriptType.Duration -> value is RuntimeValue.DurationValue
        ScriptType.Percentage -> value is RuntimeValue.PercentageValue
        ScriptType.Entity -> value is RuntimeValue.EntityValue || value == RuntimeValue.NullValue
        ScriptType.Player -> value == RuntimeValue.NullValue || (value is RuntimeValue.EntityValue && value.kind == RuntimeValue.EntityKind.PLAYER)
        ScriptType.Position -> value is RuntimeValue.PositionValue
        ScriptType.Particle -> value is RuntimeValue.ParticleValue
        ScriptType.Unit -> value == RuntimeValue.UnitValue
        is ScriptType.ListType -> value is RuntimeValue.ListValue && value.values.all { matches(it, type.elementType) }
        is ScriptType.TypeParameter -> matches(value, type.bound)
        else -> true
    }
}
