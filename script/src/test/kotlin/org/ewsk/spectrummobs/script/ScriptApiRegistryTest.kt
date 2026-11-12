package org.ewsk.spectrummobs.script

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ScriptApiRegistryTest {
    @Test
    fun rejectsLegacyNamesAndEntityConditionMembers() {
        val oldExpressions = listOf(
            "players.within(caster.position, 8)", "players.nearby(caster.position, 8)",
            "entities.nearest(caster.position, 8)", "damage(target, 1)", "ignite(target, 1s)",
            "particles.circle(caster.position, FLAME, 3, 8)",
            "message.send(target, \"text\")", "title.show(target, \"a\", \"b\", 1t, 1t, 1t)",
            "actionbar.show(target, \"text\")", "sound.play(target, \"entity.blaze.shoot\", 1, 1)",
            "entity.setHealth(target, 20)", "entity.teleport(target, caster.position)",
            "entity.velocity(target, 0, 1, 0)", "entity.remove(target)", "entity.potion(target, \"SPEED\", 1s, 0)",
            "entity.hasPotion(target, \"SPEED\")", "target.isPlayer", "target.hasPotion(\"SPEED\")",
            "bossbar.show(target, \"a\", 0.5, 1s)", "scoreboard.set(target, \"a\", 1)",
            "projectile.forward(\"MAGMA_BLOCK\", 1, 1, 1s, 0.5)",
            "projectile.launch(caster.position, target.position, \"MAGMA_BLOCK\", 1, 1, 1s, 0.5)",
            "mob.summon(\"spectrum:minion\", caster.position)"
        )
        oldExpressions.forEach { expression ->
            val result = assertIs<CompilationResult.Failure>(ScriptCompiler().compile("skill old { $expression }", "legacy.sms"), expression)
            assertTrue(result.diagnostics.all { it.source == "legacy.sms" }, expression)
        }
    }

    @Test
    fun randomTargeterPreservesPlayerTypeAndRejectsInvalidElementTypes() {
        val result = assertIs<CompilationResult.Success>(ScriptCompiler().compile("""
            skill select_players {
                let selected = targeter.random(targeter.players.within(caster.position, 12), 2)
                for player in selected {
                    mechanic.message.send(player, "&aSelected")
                }
            }
        """.trimIndent()))
        val selected = assertIs<IrLet>(result.script.skills.single().body.first())
        assertEquals(ScriptType.ListType(ScriptType.Player), selected.value.type)
        assertIs<CompilationResult.Failure>(ScriptCompiler().compile("skill bad { targeter.random(\"players\", 1) }"))
        assertIs<CompilationResult.Failure>(ScriptCompiler().compile("skill bad { condition.healthBelow(caster, 1s) }"))
    }

    @Test
    fun headersAllowQueriesAndConditionsButRejectSideEffectsBeforeCooldown() {
        assertIs<CompilationResult.Success>(ScriptCompiler().compile("skill valid { requires condition.healthBelow(targeter.self(), 50%) && state.get(\"rage\") > 0 }"))
        listOf("mechanic.mob.summon(\"spectrum:minion\", caster.position) > 0", "state.increment(\"rage\", 1) > 0").forEach { condition ->
            val result = assertIs<CompilationResult.Failure>(ScriptCompiler().compile("skill bad { requires $condition }"))
            assertTrue(result.diagnostics.any { it.message.contains("Side-effecting function") })
        }
    }

    @Test
    fun extensionRegistrationPublishesSignaturesAndImplementationsTogether() {
        val bindings = ScriptApiBindings.Builder(ScriptApiRegistry.empty()).register(
            "mechanic.demo.record",
            listOf(FunctionParameter("first", ScriptType.Number), FunctionParameter("second", ScriptType.Number, required = false)),
            ScriptType.Unit
        ) { context, args ->
            context.setStateValue("first", args.single { it.name == "first" }.value)
            context.setStateValue("argCount", RuntimeValue.NumberValue(args.size.toDouble()))
            RuntimeValue.UnitValue
        }.build()
        val source = """
            skill extension {
                let actions = mechanic.demo
                let record = actions.record
                record(first = 7)
            }
        """.trimIndent()
        assertIs<CompilationResult.Failure>(ScriptCompiler().compile(source))
        val skill = assertIs<CompilationResult.Success>(ScriptCompiler(bindings.signatures).compile(source)).script.skills.single()
        val scheduler = SkillScheduler()
        val start = assertIs<SkillStartResult.Started>(scheduler.start(skill, "caster", actions = bindings.actions))
        assertIs<RuntimeEvent.Completed>(scheduler.tick(0).single())
        assertEquals(RuntimeValue.NumberValue(7.0), start.execution.context.stateValue("first"))
        assertEquals(RuntimeValue.NumberValue(1.0), start.execution.context.stateValue("argCount"))
        assertFailsWith<IllegalStateException> { bindings.actions.replace("mechanic.demo.record") { _, _ -> RuntimeValue.UnitValue } }

        val extended = bindings.toBuilder().register("condition.demo.ready", emptyList(), ScriptType.Boolean) { _, _ ->
            RuntimeValue.BooleanValue(true)
        }.build()
        assertFalse(bindings.actions.contains("condition.demo.ready"))
        assertTrue(extended.actions.contains("condition.demo.ready"))
    }

    @Test
    fun namedArgumentsKeepEvaluationOrderAndNeverFallBackToAnotherNamedValue() {
        val bindings = ScriptApiBindings.Builder(ScriptApiRegistry.empty())
            .register("mechanic.demo.record", listOf(FunctionParameter("first", ScriptType.Number), FunctionParameter("second", ScriptType.Number)), ScriptType.Unit) { context, args ->
                assertEquals(listOf("second", "first"), args.map { it.name })
                context.setStateValue("first", args.single { it.name == "first" }.value)
                RuntimeValue.UnitValue
            }.build()
        val skill = assertIs<CompilationResult.Success>(ScriptCompiler(bindings.signatures).compile("skill args { mechanic.demo.record(second = 9, first = 2) }")).script.skills.single()
        val scheduler = SkillScheduler()
        val started = assertIs<SkillStartResult.Started>(scheduler.start(skill, "caster", actions = bindings.actions))
        assertIs<RuntimeEvent.Completed>(scheduler.tick(0).single())
        assertEquals(RuntimeValue.NumberValue(2.0), started.execution.context.stateValue("first"))
        listOf("mechanic.demo.record(2, first = 3)", "mechanic.demo.record(first = 2)", "mechanic.demo.record(first = 2, second = \"bad\")").forEach {
            assertIs<CompilationResult.Failure>(ScriptCompiler(bindings.signatures).compile("skill bad { $it }"))
        }
    }

    @Test
    fun rejectsIncompleteDuplicateOrConflictingRegistrationsWithoutReplacingPublishedEntries() {
        val builder = ScriptApiRegistry.Builder().function("condition.demo.ready", emptyList(), ScriptType.Boolean)
        val first = builder.build()
        assertFailsWith<IllegalStateException> { builder.function("CONDITION.DEMO.READY", emptyList(), ScriptType.Number) }
        assertEquals(first.signature("condition.demo.ready"), builder.build().signature("condition.demo.ready"))
        assertFailsWith<IllegalStateException> { builder.function("condition.demo.ready.child", emptyList(), ScriptType.Boolean) }
        assertFailsWith<IllegalArgumentException> { builder.function("condition.demo.bad", emptyList(), ScriptType.Number) }
        assertFailsWith<IllegalArgumentException> { builder.function("caster.custom", emptyList(), ScriptType.Number) }
        assertFailsWith<IllegalArgumentException> { builder.function("demo.invalid-name", emptyList(), ScriptType.Number) }
        assertFailsWith<IllegalArgumentException> {
            builder.function("mechanic.demo.parameters", listOf(FunctionParameter("x", ScriptType.Number, false), FunctionParameter("y", ScriptType.Number)), ScriptType.Unit)
        }
        assertFailsWith<IllegalStateException> { ScriptApiBindings.Builder(first).build() }
        assertFailsWith<IllegalStateException> { ScriptApiBindings.Builder(first).bind("condition.missing") { _, _ -> RuntimeValue.BooleanValue(true) } }
    }

    @Test
    fun extensionExceptionsRejectSkillStartWithReadableReason() {
        val api = ScriptApiBindings.Builder(ScriptApiRegistry.empty())
            .register("condition.demo.broken", emptyList(), ScriptType.Boolean) { _, _ -> throw IllegalArgumentException("Invalid extension input") }.build()
        val skill = assertIs<CompilationResult.Success>(ScriptCompiler(api.signatures).compile("skill broken { requires condition.demo.broken() }")).script.skills.single()
        val result = assertIs<SkillStartResult.Rejected>(SkillScheduler().start(skill, "caster", actions = api.actions))
        assertTrue(result.reason.contains("condition.demo.broken"))
        assertTrue(result.reason.contains("Invalid extension input"))
    }

    @Test
    fun extensionsMustHonorReturnTypesAndQueryBudgets() {
        val api = ScriptApiBindings.Builder(ScriptApiRegistry.empty())
            .register("targeter.demo.players", emptyList(), ScriptType.ListType(ScriptType.Player)) { _, _ ->
                RuntimeValue.ListValue(listOf("one", "one", "two", "three").map {
                    RuntimeValue.EntityValue(it, kind = RuntimeValue.EntityKind.PLAYER)
                })
            }
            .register("condition.demo.invalid", emptyList(), ScriptType.Boolean) { _, _ -> RuntimeValue.UnitValue }
            .build()
        val context = RuntimeContext(emptyMap(), api.actions, "caster", 16, maxQueryTargets = 2)
        val selected = assertIs<RuntimeValue.ListValue>(context.invokeAction("targeter.demo.players", emptyList()))
        assertEquals(listOf("one", "two"), selected.values.map { (it as RuntimeValue.EntityValue).id })
        val error = assertFailsWith<RuntimeEvaluationException> { context.invokeAction("condition.demo.invalid", emptyList()) }
        assertTrue(error.message!!.contains("expected Boolean"))
    }
}
