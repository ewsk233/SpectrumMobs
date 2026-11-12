package org.ewsk.spectrummobs.script

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CastingControlTest {
    private val registry = ScriptApiRegistry.default().toBuilder()
        .function("mechanic.test.action", emptyList(), ScriptType.Unit).build()

    @Test
    fun castingHeadersAreValidatedAndKeepSourceLocations() {
        val configured = skill("""
            group = "main"
            concurrency = "replace"
            cancelOnPhaseChange = false
            cleanupOnCancel = false
            wait 30t
        """)
        assertEquals(SkillCastingPolicy("main", CastingConcurrency.REPLACE, false, false), configured.casting)
        listOf("group = \"\"", "group = caster", "concurrency = \"queue\"", "cleanupOnCancel = 1", "cancelOnPhaseChange = \"true\"", "group = \"a\"; group = \"b\"").forEach {
            val failure = assertIs<CompilationResult.Failure>(ScriptCompiler().compile("skill bad { $it }", "bad.sms"))
            assertTrue(failure.diagnostics.all { diagnostic -> diagnostic.source == "bad.sms" && diagnostic.location.line == 1 })
        }
    }

    @Test
    fun childCallsRequireExplicitTargetAndReturnAnExecutionIdentifier() {
        val configured = skill("let child = skills.cast(\"spectrum:child\", caster, target, linked = false)")
        assertEquals(ScriptType.Text, (configured.body.single() as IrLet).value.type)
        assertIs<CompilationResult.Failure>(ScriptCompiler().compile("skill bad { skills.cast(\"spectrum:child\", caster) }"))
        assertIs<CompilationResult.Failure>(ScriptCompiler().compile("skill bad { skills.cast(\"spectrum:child\", caster, target, linked = \"false\") }"))
    }

    @Test
    fun cooldownUsesFullResourceIdInsteadOfDeclarationName() {
        val scheduler = SkillScheduler()
        val first = skill("cooldown = 30t\nwait 1t", "one:bolt")
        val second = first.copy(id = "two:bolt")
        assertIs<SkillStartResult.Started>(scheduler.start(first, "boss"))
        assertIs<SkillStartResult.Started>(scheduler.start(second, "boss"))
        assertIs<SkillStartResult.Rejected>(scheduler.start(first, "boss"))
    }

    @Test
    fun groupsArePerCasterAndRejectedStartsDoNotConsumeCooldown() {
        val scheduler = SkillScheduler()
        val guarded = skill("group = \"main\"\nconcurrency = \"reject\"\ncooldown = 30t\nwait 5t")
        val first = started(scheduler.start(guarded, "one"))
        assertIs<SkillStartResult.Started>(scheduler.start(guarded, "two"))
        val other = guarded.copy(id = "spectrum:other")
        assertIs<SkillStartResult.Rejected>(scheduler.start(other, "one"))
        scheduler.cancel(first.id)
        assertIs<SkillStartResult.Started>(scheduler.start(other, "one"))
        assertIs<SkillStartResult.Started>(scheduler.start(other.copy(casting = other.casting.copy(concurrency = CastingConcurrency.PARALLEL)), "three"))
    }

    @Test
    fun replacementReleasesCapacityButInvalidReplacementPreservesOldCast() {
        val scheduler = SkillScheduler(RuntimeLimits(maxActiveExecutions = 1))
        val old = started(scheduler.start(skill("wait 30t"), "boss"))
        val replacement = skill("concurrency = \"replace\"\nwait 30t", "spectrum:replacement")
        assertIs<SkillStartResult.Rejected>(scheduler.start(replacement.copy(requires = skill("requires false").requires), "boss"))
        assertEquals(old.id, scheduler.activeExecutions().single().id)
        val newer = started(scheduler.start(replacement, "boss"))
        assertEquals(newer.id, scheduler.activeExecutions().single().id)
        assertEquals(ExecutionState.CANCELLED, old.state)
        assertTrue(scheduler.tick(0).any { it is RuntimeEvent.Cancelled && it.executionId == old.id })
    }

    @Test
    fun replacementCannotEvictOldCastIfRetainedEffectsWouldExceedBudget() {
        val scheduler = SkillScheduler(RuntimeLimits(maxActiveExecutions = 1))
        val old = started(scheduler.start(skill("cleanupOnCancel = false\nwait 30t"), "boss"))
        var cleaned = false
        scheduler.effects.register(old.id, "projectile") { cleaned = true }
        val replacement = skill("concurrency = \"replace\"\nwait 30t", "spectrum:new")
        assertIs<SkillStartResult.Rejected>(scheduler.start(replacement, "boss"))
        assertFalse(cleaned)
        assertEquals(ExecutionState.RUNNABLE, old.state)
    }

    @Test
    fun linkedChildrenKeepCompletedParentAndItsGroupUntilTheyFinish() {
        val scheduler = SkillScheduler()
        lateinit var child: SkillExecution
        val childSkill = skill("group = \"main\"\nconcurrency = \"reject\"\nwait 3t", "spectrum:child")
        lateinit var actions: RuntimeActionRegistry
        actions = RuntimeActionRegistry().register("mechanic.test.action") { context, _ ->
            child = started(scheduler.start(childSkill, "boss", actions = actions, parent = context))
            RuntimeValue.UnitValue
        }
        val parent = started(scheduler.start(skill("group = \"main\"\nmechanic.test.action()", "spectrum:parent"), "boss", actions = actions))
        assertIs<RuntimeEvent.Completed>(scheduler.tick(0).single())
        val parentInfo = scheduler.executionInfos().first { it.id == parent.id }
        assertEquals(ExecutionState.COMPLETED, parentInfo.state)
        assertEquals(parent.id, child.context.parentExecutionId)
        assertEquals(parent.id, child.context.rootExecutionId)
        assertEquals(2, child.context.skillDepth)
        assertIs<SkillStartResult.Rejected>(scheduler.start(childSkill, "boss", actions = actions))
        assertEquals(setOf(parent.id, child.id), scheduler.cancelExecution(parent.id, "Manual interrupt").toSet())
        assertTrue(scheduler.executionInfos().isEmpty())
    }

    @Test
    fun cancellationPropagatesOnlyToLinkedChildrenAndWorldUnloadStillCleansAll() {
        val scheduler = SkillScheduler()
        val parent = started(scheduler.start(skill("wait 30t"), "parent", worldKey = "world"))
        val linked = started(scheduler.start(skill("wait 30t", "spectrum:linked"), "other-caster", parent = parent.context, worldKey = "other"))
        val detached = started(scheduler.start(skill("wait 30t", "spectrum:detached"), "parent", parent = parent.context, linkedToParent = false, worldKey = "world"))
        assertEquals(setOf(parent.id, linked.id), scheduler.cancelExecution(parent.id, "Interrupted").toSet())
        assertEquals(detached.id, scheduler.activeExecutions().single().id)
        assertEquals(listOf(detached.id), scheduler.cancelByWorld("world"))
        assertTrue(scheduler.executionInfos().isEmpty())
        assertIs<SkillStartResult.Rejected>(scheduler.start(skill("wait 1t"), "parent", parent = parent.context))
    }

    @Test
    fun detachedRecursiveCallsStillShareRootAndDepthLimitAcrossTicks() {
        val scheduler = SkillScheduler(RuntimeLimits(maxCallDepth = 3))
        val looping = skill("mechanic.test.action()", "spectrum:loop")
        val depths = mutableListOf<Int>()
        var rejections = 0
        lateinit var actions: RuntimeActionRegistry
        actions = RuntimeActionRegistry().register("mechanic.test.action") { context, _ ->
            depths += context.skillDepth
            when (val next = scheduler.start(looping, "boss", actions = actions, parent = context, linkedToParent = false)) {
                is SkillStartResult.Started -> assertEquals(context.rootExecutionId, next.execution.context.rootExecutionId)
                is SkillStartResult.Rejected -> { rejections++; assertTrue(next.reason.contains("skill call depth")) }
            }
            RuntimeValue.UnitValue
        }
        scheduler.start(looping, "boss", actions = actions)
        (0L..5L).forEach { scheduler.tick(it) }
        assertEquals(listOf(1, 2, 3), depths)
        assertEquals(1, rejections)
        assertTrue(scheduler.executionInfos().isEmpty())
    }

    @Test
    fun detachedParentsDoNotGrantGrandchildrenAnExemptionFromUnrelatedGroupConflicts() {
        val scheduler = SkillScheduler()
        val root = started(scheduler.start(skill("group = \"main\"\nwait 30t"), "boss"))
        val detached = started(scheduler.start(skill("group = \"ambient\"\nwait 30t", "spectrum:detached"), "boss", parent = root.context, linkedToParent = false))
        assertIs<SkillStartResult.Rejected>(scheduler.start(skill("group = \"main\"\nconcurrency = \"reject\"\nwait 1t", "spectrum:grandchild"), "boss", parent = detached.context))
    }

    @Test
    fun cancellingAnExecutionInsideItsActionStopsItsRemainingStatementsAndLaterTickEntries() {
        val scheduler = SkillScheduler()
        var executed = 0
        lateinit var later: SkillExecution
        val actions = RuntimeActionRegistry().register("mechanic.test.action") { context, _ ->
            if (context.skillId == "spectrum:interrupt") {
                scheduler.cancel(later.id)
                scheduler.cancel(context.executionId)
            } else executed++
            RuntimeValue.UnitValue
        }
        val first = started(scheduler.start(skill("mechanic.test.action()\nmechanic.test.action()", "spectrum:interrupt"), "boss", actions = actions))
        later = started(scheduler.start(skill("mechanic.test.action()", "spectrum:later"), "boss", actions = actions))
        val events = scheduler.tick(0)
        assertEquals(0, executed)
        assertEquals(setOf(first.id, later.id), events.filterIsInstance<RuntimeEvent.Cancelled>().map { it.executionId }.toSet())
        assertTrue(events.none { it is RuntimeEvent.Completed })
    }

    @Test
    fun phaseChangesRespectPoliciesAndCleanInstanceOwnedEffects() {
        val scheduler = SkillScheduler()
        val oldPhase = mapOf("phase" to RuntimeValue.TextValue("normal"))
        val interrupted = started(scheduler.start(skill("wait 30t"), "boss", oldPhase))
        val ambient = started(scheduler.start(skill("cancelOnPhaseChange = false\nwait 30t", "spectrum:ambient"), "boss", oldPhase))
        val next = started(scheduler.start(skill("wait 30t", "spectrum:next"), "boss", mapOf("phase" to RuntimeValue.TextValue("enraged"))))
        var cleaned = 0
        scheduler.effects.register(interrupted.id, "projectile") { cleaned++ }
        scheduler.effects.register(ambient.id, "bossbar") { cleaned++ }
        interrupted.context.variables["phase"] = RuntimeValue.TextValue("local-shadow")
        assertEquals(listOf(interrupted.id), scheduler.cancelForPhaseChange("boss", "normal"))
        assertEquals(1, cleaned)
        assertEquals(setOf(ambient.id, next.id), scheduler.activeExecutions().map { it.id }.toSet())
        scheduler.cancelAll()
        assertEquals(2, cleaned)
    }

    @Test
    fun completedExecutionsRetainOwnedEffectsUntilExpiryOrManualCancellation() {
        val scheduler = SkillScheduler(RuntimeLimits(maxActiveExecutions = 1))
        var cleaned = 0
        val execution = started(scheduler.start(skill(""), "boss"))
        scheduler.effects.register(execution.id, "projectile") { cleaned++ }
        scheduler.tick(0)
        assertTrue(scheduler.activeExecutions().isEmpty())
        assertEquals(1, scheduler.executionInfos().single().effectCount)
        assertIs<SkillStartResult.Rejected>(scheduler.start(skill("wait 1t"), "boss"))
        assertTrue(scheduler.cancel(execution.id, "Cleanup completed cast"))
        assertEquals(1, cleaned)
        assertTrue(scheduler.executionInfos().isEmpty())
        val expired = started(scheduler.start(skill(""), "boss"))
        scheduler.effects.register(expired.id, "projectile") { cleaned++ }
        scheduler.tick(1)
        scheduler.effects.release(expired.id, "projectile")
        assertTrue(scheduler.executionInfos().isEmpty())
        assertEquals(1, cleaned)
    }

    @Test
    fun retainingEffectsOnCancelDoesNotKeepTasksRunningAndShutdownForcesCleanup() {
        val scheduler = SkillScheduler()
        var cleaned = 0
        val execution = started(scheduler.start(skill("cleanupOnCancel = false\nwait 20t"), "boss"))
        scheduler.effects.register(execution.id, "bossbar") { cleaned++ }
        scheduler.tick(0)
        scheduler.cancel(execution.id, "Keep visual")
        val info = scheduler.executionInfos().single()
        assertEquals(ExecutionState.CANCELLED, info.state)
        assertEquals(0, info.waitTicks)
        assertEquals("Keep visual", info.cancellationReason)
        assertEquals(0, cleaned)
        scheduler.cancelAll("Plugin disabled")
        assertEquals(1, cleaned)
        assertTrue(scheduler.executionInfos().isEmpty())
    }

    @Test
    fun failureCancelsLinkedChildrenAndCleanupFailuresDoNotPreventOtherCleanup() {
        val scheduler = SkillScheduler()
        lateinit var child: SkillExecution
        var cleaned = 0
        val actions = RuntimeActionRegistry().register("mechanic.test.action") { context, _ ->
            child = started(scheduler.start(skill("wait 20t", "spectrum:child"), "child-caster", parent = context))
            context.effects.register(context.executionId, "broken") { error("Cleanup refused") }
            context.effects.register(context.executionId, "good") { cleaned++ }
            error("Mechanic refused")
        }
        val parent = started(scheduler.start(skill("mechanic.test.action()"), "boss", actions = actions))
        val events = scheduler.tick(0)
        assertEquals(1, cleaned)
        assertTrue(events.any { it is RuntimeEvent.Failed && it.executionId == parent.id && it.message.contains("casting.sms:2") })
        assertTrue(events.any { it is RuntimeEvent.Cancelled && it.executionId == child.id })
        assertTrue(events.any { it is RuntimeEvent.EffectCleanupFailed && it.effectKey == "broken" })
        assertTrue(scheduler.executionInfos().isEmpty())
    }

    @Test
    fun waitingInspectionReportsSourceRemainingTimeAndCancellationEventsOnce() {
        val scheduler = SkillScheduler()
        val execution = started(scheduler.start(skill("wait 30t"), "boss"))
        scheduler.tick(0)
        scheduler.tick(10)
        val info = scheduler.executionInfos().single()
        assertEquals(20, info.waitTicks)
        assertEquals("casting.sms", info.source)
        assertEquals(2, info.location.line)
        scheduler.cancel(execution.id, "Manual interrupt")
        assertEquals(1, scheduler.tick(11).filterIsInstance<RuntimeEvent.Cancelled>().size)
        assertTrue(scheduler.tick(12).isEmpty())
    }

    private fun skill(body: String, id: String = "spectrum:test"): TypedSkill =
        assertIs<CompilationResult.Success>(ScriptCompiler(registry).compile("skill same_name {\n${body.trimIndent()}\n}", "casting.sms")).script.skills.single().copy(id = id)

    private fun started(result: SkillStartResult): SkillExecution = assertIs<SkillStartResult.Started>(result).execution
}
