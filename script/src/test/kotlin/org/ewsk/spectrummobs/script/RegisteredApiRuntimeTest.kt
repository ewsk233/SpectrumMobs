package org.ewsk.spectrummobs.script

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class RegisteredApiRuntimeTest {
    @Test
    fun waitingPreservesQuerySnapshotsWhileExplicitQueriesAndConditionsReadCurrentState() {
        var current = RuntimeValue.EntityValue("boss", currentHealth = 100.0, maxHealth = 100.0,
            position = RuntimeValue.PositionValue(0.0, 64.0, 0.0, "world"))
        var captured: List<RuntimeValue> = emptyList()
        val api = bindings().register("mechanic.test.capture", listOf(
            FunctionParameter("saved", ScriptType.Position), FunctionParameter("snapshot", ScriptType.Position),
            FunctionParameter("current", ScriptType.Position), FunctionParameter("below", ScriptType.Boolean)
        ), ScriptType.Unit) { _, args -> captured = args.map { it.value }; RuntimeValue.UnitValue }.build()
        val skill = compile(api, """
            skill delayed {
                let before = targeter.self()
                let saved = before.position
                wait 1t
                mechanic.test.capture(saved, before.position, targeter.self().position, condition.healthBelow(before, 50%))
            }
        """)
        val scheduler = SkillScheduler()
        assertIs<SkillStartResult.Started>(scheduler.start(skill, "boss", mapOf("caster" to current), api.actions,
            entityResolver = RuntimeEntityResolver { current }))
        assertIs<RuntimeEvent.Suspended>(scheduler.tick(0).single())
        val originalPosition = current.position!!
        current = current.copy(currentHealth = 40.0, position = originalPosition.copy(x = 8.0))
        assertIs<RuntimeEvent.Completed>(scheduler.tick(1).single())
        assertEquals(listOf(originalPosition, originalPosition, current.position, RuntimeValue.BooleanValue(true)), captured)
    }

    @Test
    fun invalidatedTargetsCanBeSkippedAfterWaiting() {
        listOf("dead", "offline", "removed", "world-unloaded").forEach { reason ->
            var available = true
            var calls = 0
            val target = RuntimeValue.EntityValue("target", currentHealth = 20.0, maxHealth = 20.0)
            val api = bindings().register("mechanic.test.record", emptyList(), ScriptType.Unit) { _, _ ->
                calls++; RuntimeValue.UnitValue
            }.build()
            val skill = compile(api, """
                skill guarded {
                    let selected = targeter.target()
                    wait 1t
                    if (condition.isLiving(selected)) {
                        mechanic.test.record()
                    }
                }
            """)
            val scheduler = SkillScheduler()
            assertIs<SkillStartResult.Started>(scheduler.start(skill, "boss", mapOf("target" to target), api.actions,
                entityResolver = RuntimeEntityResolver { if (available) it else null }))
            assertIs<RuntimeEvent.Suspended>(scheduler.tick(0).single(), reason)
            available = false
            assertIs<RuntimeEvent.Completed>(scheduler.tick(1).single(), reason)
            assertEquals(0, calls, reason)
        }
    }

    @Test
    fun booleanOperatorsShortCircuitRegisteredConditions() {
        var calls = 0
        val api = bindings().register("condition.test.expensive", emptyList(), ScriptType.Boolean) { _, _ ->
            calls++; error("Short-circuited condition must not run")
        }.build()
        val skill = compile(api, """
            skill guarded {
                requires true || condition.test.expensive()
                if (false && condition.test.expensive()) {
                    return
                }
            }
        """)
        val scheduler = SkillScheduler()
        assertIs<SkillStartResult.Started>(scheduler.start(skill, "boss", actions = api.actions))
        assertIs<RuntimeEvent.Completed>(scheduler.tick(0).single())
        assertEquals(0, calls)
    }

    @Test
    fun failedConditionDoesNotConsumeCooldownAndRegisteredActionsShareBudgets() {
        var living = RuntimeValue.EntityValue("boss", currentHealth = 100.0, maxHealth = 100.0)
        var calls = 0
        val api = bindings().register("mechanic.test.record", emptyList(), ScriptType.Unit) { _, _ -> calls++; RuntimeValue.UnitValue }.build()
        val skill = compile(api, """
            skill gated {
                cooldown = 2s
                requires condition.healthBelow(caster, 50%)
                repeat 2 {
                    mechanic.test.record()
                }
            }
        """)
        val scheduler = SkillScheduler(RuntimeLimits(maxStepsPerResume = 1))
        fun start() = scheduler.start(skill, "boss", mapOf("caster" to living), api.actions, entityResolver = RuntimeEntityResolver { living })
        assertIs<SkillStartResult.Rejected>(start())
        living = living.copy(currentHealth = 40.0)
        assertIs<SkillStartResult.Started>(start())
        assertIs<RuntimeEvent.BudgetYielded>(scheduler.tick(0).single())
        (1L..10L).forEach { scheduler.tick(it) }
        assertEquals(2, calls)
        assertIs<SkillStartResult.Rejected>(start())
        scheduler.tick(40)
        assertIs<SkillStartResult.Started>(start())
    }

    private fun bindings() = StandardScriptActions.bind(ScriptApiBindings.Builder()).apply {
        unboundNames.forEach { name -> bind(name) { _, _ -> error("Unexpected platform action: $name") } }
    }

    private fun compile(api: ScriptApiBindings, source: String): TypedSkill =
        assertIs<CompilationResult.Success>(ScriptCompiler(api.signatures).compile(source.trimIndent())).script.skills.single()
}
