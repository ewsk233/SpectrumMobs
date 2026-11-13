package org.ewsk.spectrummobs.script

import org.ewsk.spectrummobs.core.OperationCode
import kotlin.test.*

class OperationResultTest {
    @Test
    fun resultsHaveCheckedMembersAndDoNotImplicitlyConvertToNumbersOrStrings() {
        val compiler = ScriptCompiler()
        assertIs<CompilationResult.Success>(compiler.compile("""
            skill result {
                let batch = mechanic.mob.summon("spectrum:minion", caster.position, 3, lifetime = 5s)
                if (batch.success && batch.count == batch.requested) {
                    let sameBatch = targeter.summons(caster, batch, tag = "core")
                    let won = condition.summons.defeated(caster, batch)
                    let reason = batch.reason
                    let code = batch.code
                    let id = batch.id
                    mechanic.mob.cleanup(caster, batch)
                }
                let cast = mechanic.skills.cast("spectrum:child", caster, target, ignoreRejected = true)
                let success = cast.success
            }
        """.trimIndent()))
        listOf(
            "skills.cast(\"spectrum:child\", caster, target)",
            "let batch = mechanic.mob.summon(\"spectrum:minion\", caster.position); state.set(\"n\", batch)",
            "targeter.summons(caster, \"batch-id\")",
            "mechanic.mob.summon(\"spectrum:minion\", caster.position).missing",
            "mechanic.skills.cast(\"spectrum:child\", caster, target).success + 1"
        ).forEach { assertIs<CompilationResult.Failure>(compiler.compile("skill invalid { $it }"), it) }
        assertIs<CompilationResult.Failure>(compiler.compile(
            "skill invalid { requires mechanic.mob.summon(\"spectrum:minion\", caster.position).success }"
        ))
    }

    @Test
    fun runtimeMembersMatchTheirDeclaredTypesIncludingExpectedRefusal() {
        val result = RuntimeValue.OperationResultValue("batch", 1, 3, OperationCode.CAPACITY, "Summon limit reached")
        val api = ScriptApiBindings.Builder(ScriptApiRegistry.empty())
            .register("mechanic.test.result", emptyList(), ScriptType.OperationResult) { _, _ -> result }
            .build()
        val skill = assertIs<CompilationResult.Success>(ScriptCompiler(api.signatures).compile("""
            skill result {
                let result = mechanic.test.result()
                let success = result.success
                let code = result.code
                let reason = result.reason
                let count = result.count
                let requested = result.requested
                let id = result.id
            }
        """.trimIndent())).script.skills.single()
        val scheduler = SkillScheduler()
        val execution = assertIs<SkillStartResult.Started>(scheduler.start(skill, "boss", actions = api.actions)).execution
        assertIs<RuntimeEvent.Completed>(scheduler.tick(1).single())
        val values = execution.context.variables
        assertEquals(RuntimeValue.BooleanValue(false), values["success"])
        assertEquals(RuntimeValue.TextValue("capacity"), values["code"])
        assertEquals(RuntimeValue.TextValue("Summon limit reached"), values["reason"])
        assertEquals(RuntimeValue.NumberValue(1.0), values["count"])
        assertEquals(RuntimeValue.NumberValue(3.0), values["requested"])
        assertEquals(RuntimeValue.TextValue("batch"), values["id"])
    }

    @Test
    fun schedulerDistinguishesExpectedRefusalsFromInvalidStarts() {
        fun skill(body: String) = assertIs<CompilationResult.Success>(ScriptCompiler().compile("skill test { $body }"))
            .script.skills.single()
        val scheduler = SkillScheduler(RuntimeLimits(maxCallDepth = 1))
        assertEquals(OperationCode.CONDITION_FAILED,
            assertIs<SkillStartResult.Rejected>(scheduler.start(skill("requires false"), "boss")).code)
        val busy = skill("group = \"main\"; concurrency = \"reject\"; wait 10t")
        val parent = assertIs<SkillStartResult.Started>(scheduler.start(busy, "boss")).execution.context
        assertEquals(OperationCode.GROUP_BUSY, assertIs<SkillStartResult.Rejected>(scheduler.start(busy, "boss")).code)
        val depth = assertIs<SkillStartResult.Rejected>(scheduler.start(busy, "other", parent = parent))
        assertEquals(OperationCode.DEPTH_LIMIT, depth.code)
        assertFalse(depth.code.expectedRejection)
        val cooldown = skill("cooldown = 10t; wait 1t").copy(id = "spectrum:cooldown")
        scheduler.start(cooldown, "other")
        assertEquals(OperationCode.COOLDOWN, assertIs<SkillStartResult.Rejected>(scheduler.start(cooldown, "other")).code)
    }
}
