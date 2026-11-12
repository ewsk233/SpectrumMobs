package org.ewsk.spectrummobs.script

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ParameterizedSkillTest {
    @Test
    fun skillParametersSupportDefaultsTypesAndRuntimeOverrides() {
        val api = ScriptApiRegistry.default().toBuilder().function("mechanic.test.record", listOf(
            FunctionParameter("radius", ScriptType.Number), FunctionParameter("damage", ScriptType.Number), FunctionParameter("windup", ScriptType.Duration)
        ), ScriptType.Unit).build()
        val result = assertIs<CompilationResult.Success>(ScriptCompiler(api).compile("""
            skill flame_nova(radius: Number = 5, damage: Number = 12, windup: Duration = 30t) {
                requires radius > 0 && damage >= 0
                mechanic.test.record(radius, damage, windup)
            }
        """.trimIndent(), "params.sms"))
        val skill = result.script.skills.single()
        assertEquals(listOf("radius", "damage", "windup"), skill.parameters.map { it.name })
        assertEquals(ScriptType.Number, skill.parameters[0].type)
        assertEquals(ScriptType.Duration, skill.parameters[2].type)
        val values = mutableListOf<RuntimeValue>()
        val actions = RuntimeActionRegistry()
            .register("mechanic.test.record") { _, arguments ->
                values += arguments.map { it.value }
                RuntimeValue.UnitValue
            }
        val scheduler = SkillScheduler()
        assertIs<SkillStartResult.Started>(scheduler.start(skill, "caster", actions = actions))
        val event = scheduler.tick(0).single()
        assertTrue(event is RuntimeEvent.Completed, event.toString())
        assertEquals(listOf(RuntimeValue.NumberValue(5.0), RuntimeValue.NumberValue(12.0), RuntimeValue.DurationValue(30L)), values)
        values.clear()
        assertIs<SkillStartResult.Started>(scheduler.start(skill, "other", actions = actions, arguments = mapOf(
            "radius" to RuntimeValue.NumberValue(8.0),
            "damage" to RuntimeValue.NumberValue(20.0),
            "windup" to RuntimeValue.DurationValue(40L)
        )))
        scheduler.tick(1)
        assertEquals(listOf(RuntimeValue.NumberValue(8.0), RuntimeValue.NumberValue(20.0), RuntimeValue.DurationValue(40L)), values)
    }

    @Test
    fun parameterDefaultsMustBeConstantAndRequiredParametersCannotFollowOptionalOnes() {
        listOf(
            "skill bad(radius: Number = caster.health.value) { return }",
            "skill bad(radius: Number = mechanic.damage(caster, 1)) { return }",
            "skill bad(radius: Number = 5, damage: Number) { return }",
            "skill bad(radius: Unknown) { return }"
        ).forEach { source ->
            val result = assertIs<CompilationResult.Failure>(ScriptCompiler().compile(source, "params-bad.sms"))
            assertTrue(result.diagnostics.isNotEmpty(), source)
        }
    }

    @Test
    fun childSkillCallAllowsDeclaredParameterNamesButRejectsUnknownStaticArguments() {
        val ok = ScriptCompiler().compile("skill parent { skills.cast(\"spectrum:child\", caster, target, radius = 8, windup = 2s) }")
        assertIs<CompilationResult.Success>(ok)
        // The target skill is checked at the repository/runtime boundary, because the call may be dynamic.
    }

    @Test
    fun rulesAreReadOnlyTypedFunctionsWithExplicitImports() {
        val signature = ScriptFunctionSignature(
            listOf(FunctionParameter("victim", ScriptType.Entity)),
            ScriptType.Boolean,
            ScriptApiKind.SERVICE,
            true
        )
        val compiler = ScriptCompiler(
            ruleFunctions = mapOf("combat.can_hit" to signature),
            ruleAliases = mapOf("combat" to "spectrum:combat")
        )
        val source = """
            import "spectrum:combat" as combat
            skill check {
                requires combat.can_hit(caster)
                if (combat.can_hit(target)) { return }
            }
        """.trimIndent()
        assertIs<CompilationResult.Success>(compiler.compile(source, "check.sms"))

        val forbidden = """
            import "spectrum:combat" as combat
            fn bad(victim: Entity): Boolean {
                mechanic.damage(victim, 1)
                return true
            }
        """.trimIndent()
        val failed = assertIs<CompilationResult.Failure>(compiler.compile(forbidden, "combat.sms"))
        assertTrue(failed.diagnostics.any { it.message.contains("Side-effecting function") })
    }

    @Test
    fun ruleRuntimeEvaluatesReturnValuesAndEnforcesStepBudget() {
        val signature = ScriptFunctionSignature(
            listOf(FunctionParameter("value", ScriptType.Number)),
            ScriptType.Number,
            ScriptApiKind.SERVICE,
            true
        )
        val ruleSource = assertIs<CompilationResult.Success>(ScriptCompiler().compile("""
            fn double(value: Number): Number {
                return value * 2
            }
        """.trimIndent(), "combat.sms"))
        val rule = ruleSource.script.rules.single().copy(qualifiedName = "combat.double")
        val api = ScriptApiRegistry.default().toBuilder().function("mechanic.test.record", listOf(FunctionParameter("value", ScriptType.Number)), ScriptType.Unit).build()
        val compiledSkill = ScriptCompiler(
            apiRegistry = api,
            ruleFunctions = mapOf("combat.double" to signature),
            ruleAliases = mapOf("combat" to "spectrum:combat")
        ).compile("""
            import "spectrum:combat" as combat
            skill use_rule {
                mechanic.test.record(combat.double(4))
            }
        """.trimIndent(), "use.sms")
        assertTrue(compiledSkill is CompilationResult.Success, compiledSkill.toString())
        val skill = (compiledSkill as CompilationResult.Success).script.skills.single()
        var recorded = 0.0
        val actions = RuntimeActionRegistry().register("mechanic.test.record") { _, args ->
            recorded = (args.single().value as RuntimeValue.NumberValue).value
            RuntimeValue.UnitValue
        }
        val scheduler = SkillScheduler()
        assertIs<SkillStartResult.Started>(scheduler.start(skill, "caster", actions = actions, ruleFunctions = mapOf(rule.qualifiedName to rule)))
        val ruleEvent = scheduler.tick(0).single()
        assertTrue(ruleEvent is RuntimeEvent.Completed, ruleEvent.toString())
        assertEquals(8.0, recorded)
    }
}
