package org.ewsk.spectrummobs.script

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFalse

class RuntimeScopeTest {
    @Test
    fun blockAndLoopVariablesRestoreTheirOuterValuesAcrossWaits() {
        val recorded = mutableListOf<Double>()
        val api = StandardScriptActions.bind(ScriptApiBindings.Builder()).apply {
            bind("targeter.players.within") { _, _ ->
                RuntimeValue.ListValue(listOf("one", "two").map { RuntimeValue.EntityValue(it, RuntimeValue.EntityKind.PLAYER) })
            }
            unboundNames.forEach { name -> bind(name) { _, _ -> error("Unexpected platform action: $name") } }
            register("mechanic.test.record", listOf(FunctionParameter("value", ScriptType.Number)), ScriptType.Unit) { _, args ->
                recorded += (args.single().value as RuntimeValue.NumberValue).value
                RuntimeValue.UnitValue
            }
        }.build()
        val source = """
            skill scopes {
                let actions = mechanic.test
                let value = 1
                if (true) {
                    let actions = targeter
                    let value = 2
                    let temporary = actions.self()
                    wait 1t
                }
                actions.record(value)
                repeat 2 {
                    let value = 3
                    actions.record(value)
                }
                let target = 7
                for target in targeter.random(targeter.players.within(caster.position, 8), 2) {
                    let actions = targeter
                    wait 1t
                }
                actions.record(target)
                actions.record(value)
            }
        """.trimIndent()
        val skill = assertIs<CompilationResult.Success>(ScriptCompiler(api.signatures).compile(source)).script.skills.single()
        val scheduler = SkillScheduler()
        val started = assertIs<SkillStartResult.Started>(scheduler.start(skill, "boss", mapOf(
            "caster" to RuntimeValue.EntityValue("boss", position = RuntimeValue.PositionValue(0.0, 64.0, 0.0, "world"))
        ), api.actions))
        assertIs<RuntimeEvent.Suspended>(scheduler.tick(0).single())
        assertIs<RuntimeEvent.Suspended>(scheduler.tick(1).single())
        assertIs<RuntimeEvent.Suspended>(scheduler.tick(2).single())
        assertIs<RuntimeEvent.Completed>(scheduler.tick(3).single())
        assertEquals(listOf(1.0, 3.0, 3.0, 7.0, 1.0), recorded)
        assertFalse(started.execution.variables().containsKey("temporary"))
    }
}
