package org.ewsk.spectrummobs.script

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SkillSchedulerTest {
    @Test
    fun waitsWithoutBlockingAndResumesAtTargetTick() {
        val skill = compile(
            """
            skill flame_nova {
                cooldown = 8s
                requires caster.health.percent <= 50%
                let center = caster.position
                mechanic.particles.circle(at = center, particle = FLAME, radius = 5, count = 48)
                wait 30t
                for player in targeter.players.within(center, 5) {
                    mechanic.damage(player, 12)
                    mechanic.ignite(player, 3s)
                }
            }
            """
        )
        val calls = mutableListOf<String>()
        val actions = RuntimeActionRegistry()
            .register("mechanic.particles.circle") { _, _ -> calls += "mechanic.particles.circle"; RuntimeValue.UnitValue }
            .register("targeter.players.within") { _, _ ->
                calls += "targeter.players.within"
                RuntimeValue.ListValue(
                    listOf(RuntimeValue.EntityValue("player-1", RuntimeValue.EntityKind.PLAYER))
                )
            }
            .register("mechanic.damage") { _, _ -> calls += "mechanic.damage"; RuntimeValue.UnitValue }
            .register("mechanic.ignite") { _, _ -> calls += "mechanic.ignite"; RuntimeValue.UnitValue }

        val scheduler = SkillScheduler(RuntimeLimits(maxStepsPerResume = 64))
        val start = assertIs<SkillStartResult.Started>(scheduler.start(
            skill,
            casterKey = "mob-1",
            variables = mapOf(
                "caster" to RuntimeValue.EntityValue(
                    id = "mob-1",
                    kind = RuntimeValue.EntityKind.MOB,
                    currentHealth = 40.0,
                    maxHealth = 100.0,
                    position = RuntimeValue.PositionValue(0.0, 64.0, 0.0, "world")
                )
            ),
            actions = actions
        ))

        val first = scheduler.tick(0)
        assertIs<RuntimeEvent.Suspended>(first.single())
        assertEquals(listOf("mechanic.particles.circle"), calls)
        assertTrue(scheduler.activeExecutions().contains(start.execution))

        assertTrue(scheduler.tick(10).isEmpty())
        assertEquals(listOf("mechanic.particles.circle"), calls)

        val completed = scheduler.tick(30)
        assertIs<RuntimeEvent.Completed>(completed.single())
        assertEquals(listOf("mechanic.particles.circle", "targeter.players.within", "mechanic.damage", "mechanic.ignite"), calls)
        assertTrue(scheduler.activeExecutions().isEmpty())
    }

    @Test
    fun membershipUsesEntityIdentityAcrossQuerySnapshots() {
        val skill = compile(
            """
            skill compare_targets {
                let center = caster.position
                let before = targeter.players.within(center, 5)
                wait 1t
                let after = targeter.players.within(center, 5)
                for player in before {
                    if (!(player in after)) {
                        mechanic.damage(player, 2)
                    }
                }
            }
            """.trimIndent()
        )
        var queryCount = 0
        var damageCalls = 0
        val actions = RuntimeActionRegistry()
            .register("targeter.players.within") { _, _ ->
                queryCount++
                RuntimeValue.ListValue(
                    listOf(
                        RuntimeValue.EntityValue(
                            id = "player-1",
                            kind = RuntimeValue.EntityKind.PLAYER,
                            position = RuntimeValue.PositionValue(queryCount.toDouble(), 64.0, 0.0, "world")
                        )
                    )
                )
            }
            .register("mechanic.damage") { _, _ ->
                damageCalls++
                RuntimeValue.UnitValue
            }
        val scheduler = SkillScheduler()
        val start = assertIs<SkillStartResult.Started>(scheduler.start(
            skill,
            casterKey = "mob-1",
            variables = mapOf(
                "caster" to RuntimeValue.EntityValue(
                    id = "mob-1",
                    kind = RuntimeValue.EntityKind.MOB,
                    position = RuntimeValue.PositionValue(0.0, 64.0, 0.0, "world")
                )
            ),
            actions = actions
        ))

        assertIs<RuntimeEvent.Suspended>(scheduler.tick(0).single())
        assertIs<RuntimeEvent.Completed>(scheduler.tick(1).single())
        assertEquals(2, queryCount)
        assertEquals(0, damageCalls)
        assertTrue(scheduler.activeExecutions().none { it.id == start.execution.id })
    }

    @Test
    fun repeatExecutesBodyWithinLoopBudget() {
        val skill = compile(
            """
            skill repeated {
                repeat 3 {
                    mechanic.damage(caster, 1)
                }
            }
            """.trimIndent()
        )
        var damageCalls = 0
        val actions = RuntimeActionRegistry().register("mechanic.damage") { _, _ ->
            damageCalls++
            RuntimeValue.UnitValue
        }
        val scheduler = SkillScheduler(RuntimeLimits(maxLoopIterations = 4))
        assertIs<SkillStartResult.Started>(scheduler.start(skill, "mob-1", actions = actions))
        assertIs<RuntimeEvent.Completed>(scheduler.tick(0).single())
        assertEquals(3, damageCalls)
    }

    @Test
    fun stateSurvivesCompletedExecutionsForSameCaster() {
        val skill = compile("stateful.sms", """
            skill stateful {
                state hits += 1
            }
        """.trimIndent())
        val scheduler = SkillScheduler()
        val first = assertIs<SkillStartResult.Started>(scheduler.start(skill, "mob-1"))
        assertIs<RuntimeEvent.Completed>(scheduler.tick(0).single())
        assertEquals(1.0, (first.execution.context.stateValue("hits") as RuntimeValue.NumberValue).value)
        val second = assertIs<SkillStartResult.Started>(scheduler.start(skill, "mob-1"))
        assertIs<RuntimeEvent.Completed>(scheduler.tick(1).single())
        assertEquals(2.0, (second.execution.context.stateValue("hits") as RuntimeValue.NumberValue).value)
    }

    @Test
    fun interpolatesEventAndPhaseValues() {
        val context = RuntimeContext(
            variables = mapOf(
                "phase" to RuntimeValue.TextValue("enraged"),
                "event" to RuntimeValue.EventValue("damaged", 12.5, cause = "ENTITY_ATTACK")
            ),
            actions = RuntimeActionRegistry(),
            casterKey = "mob-1",
            maxCallDepth = 4
        )
        val expression = IrLiteral(
            LiteralValue.StringValue("&c阶段 ${'$'}{phase}，伤害 ${'$'}{event.damage}，原因 ${'$'}{event.cause}"),
            ScriptType.Text,
            SourceLocation(0, 1, 1)
        )
        assertEquals(
            "&c阶段 enraged，伤害 12.5，原因 ENTITY_ATTACK",
            (RuntimeEvaluator().evaluate(expression, context) as RuntimeValue.TextValue).value
        )
    }

    @Test
    fun yieldsWhenStepBudgetIsReached() {
        val skill = compile(
            """
            skill repeated {
                mechanic.damage(caster, 1)
                mechanic.damage(caster, 2)
            }
            """
        )
        var calls = 0
        val actions = RuntimeActionRegistry().register("mechanic.damage") { _, _ -> calls++; RuntimeValue.UnitValue }
        val scheduler = SkillScheduler(RuntimeLimits(maxStepsPerResume = 1))
        assertIs<SkillStartResult.Started>(scheduler.start(skill, "mob-1", actions = actions))

        assertIs<RuntimeEvent.BudgetYielded>(scheduler.tick(0).single())
        assertEquals(1, calls)
        assertIs<RuntimeEvent.Completed>(scheduler.tick(1).single())
        assertEquals(2, calls)
    }

    @Test
    fun requirementsDoNotConsumeCooldownWhenRejected() {
        val skill = compile(
            """
            skill gated {
                cooldown = 2s
                requires caster.health.percent <= 50%
                mechanic.damage(caster, 1)
            }
            """
        )
        val actions = RuntimeActionRegistry().register("mechanic.damage") { _, _ -> RuntimeValue.UnitValue }
        val scheduler = SkillScheduler()
        val fullHealth = mapOf("caster" to RuntimeValue.EntityValue("mob-1", currentHealth = 100.0, maxHealth = 100.0))
        val lowHealth = mapOf("caster" to RuntimeValue.EntityValue("mob-1", currentHealth = 40.0, maxHealth = 100.0))

        assertIs<SkillStartResult.Rejected>(scheduler.start(skill, "mob-1", fullHealth, actions))
        assertIs<SkillStartResult.Started>(scheduler.start(skill, "mob-1", lowHealth, actions))
        scheduler.tick(0)
        assertIs<SkillStartResult.Rejected>(scheduler.start(skill, "mob-1", lowHealth, actions))
        assertIs<SkillStartResult.Started>(scheduler.runAt(40, skill, "mob-1", lowHealth, actions))
    }

    @Test
    fun cancellationRemovesWaitingExecution() {
        val skill = compile("skill delayed { wait 10t }\n")
        val scheduler = SkillScheduler()
        val started = assertIs<SkillStartResult.Started>(scheduler.start(skill, "mob-1"))
        scheduler.tick(0)
        assertTrue(scheduler.cancel(started.execution.id, "Caster died"))
        assertTrue(scheduler.activeExecutions().isEmpty())
    }

    @Test
    fun lifecycleCancellationCanTargetCasterAndWorld() {
        val skill = compile("skill delayed { wait 10t }\n")
        val scheduler = SkillScheduler()
        assertIs<SkillStartResult.Started>(scheduler.start(skill, "mob-1", worldKey = "world"))
        assertIs<SkillStartResult.Started>(scheduler.start(skill, "mob-2", worldKey = "other"))
        scheduler.tick(0)

        assertEquals(1, scheduler.cancelByCaster("mob-1", "Caster died").size)
        assertEquals(1, scheduler.cancelByWorld("other", "World unloaded").size)
        assertTrue(scheduler.activeExecutions().isEmpty())
    }

    private fun compile(source: String): TypedSkill = compile("runtime.sms", source)

    private fun compile(sourceName: String, source: String): TypedSkill {
        val result = assertIs<CompilationResult.Success>(ScriptCompiler().compile(source, sourceName))
        return result.script.skills.single()
    }

    private fun SkillScheduler.runAt(
        tick: Long,
        skill: TypedSkill,
        casterKey: String,
        variables: Map<String, RuntimeValue>,
        actions: RuntimeActionRegistry
    ): SkillStartResult {
        this.tick(tick)
        return start(skill, casterKey, variables, actions)
    }
}
