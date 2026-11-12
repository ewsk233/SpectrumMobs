package org.ewsk.spectrummobs.script

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TargeterConditionTest {
    private val api = StandardScriptActions.bind(ScriptApiBindings.Builder()).apply {
        // 这些测试只执行无平台依赖的动作，其余绑定一旦被调用就立即失败。
        unboundNames.forEach { name -> bind(name) { _, _ -> error("Unexpected platform action: $name") } }
    }.build()

    @Test
    fun sphereQueriesIncludeBoundaryFilterWorldsDeduplicateAndLimitAfterSorting() {
        val center = RuntimeValue.PositionValue(0.0, 64.0, 0.0, "world")
        val candidates = listOf(
            entity("far", 5.0), entity("cube-corner", 4.0, z = 4.0), entity("other-world", 0.0, world = "other"),
            entity("nearest", 1.0), entity("second", 2.0), entity("nearest", 1.0)
        )
        assertEquals(listOf("nearest", "second", "far"), RuntimeTargeting.within(candidates.asSequence(), center, 5.0, 64).map { it.id })
        assertEquals(listOf("nearest"), RuntimeTargeting.within(candidates.asSequence(), center, 5.0, 1).map { it.id })
        assertTrue(RuntimeTargeting.within(candidates.asSequence(), center, 0.0, 64).isEmpty())
        assertFailsWith<IllegalArgumentException> { RuntimeTargeting.within(candidates.asSequence(), center, -1.0, 64) }
        assertFailsWith<IllegalArgumentException> { RuntimeTargeting.within(candidates.asSequence(), center, Double.NaN, 64) }
    }

    @Test
    fun selfTargetAndAttackerResolveCurrentEntitiesOrNull() {
        val caster = entity("caster", 0.0)
        val target = entity("target", 1.0)
        val currentTarget = target.copy(position = target.position!!.copy(x = 8.0))
        val context = context(mapOf("caster" to caster, "target" to target, "event" to RuntimeValue.EventValue("damaged", attacker = target))) {
            when (it.id) { "target" -> currentTarget; "caster" -> caster; else -> null }
        }
        assertEquals(caster, invoke("targeter.self", context))
        assertEquals(currentTarget, invoke("targeter.target", context))
        assertEquals(currentTarget, invoke("targeter.attacker", context))
        context.variables["caster"] = entity("local-shadow", 0.0)
        context.variables["target"] = entity("local-shadow", 0.0)
        context.variables["event"] = RuntimeValue.TextValue("local-shadow")
        assertEquals(caster, invoke("targeter.self", context))
        assertEquals(currentTarget, invoke("targeter.target", context))
        assertEquals(currentTarget, invoke("targeter.attacker", context))
        val missing = context(mapOf("caster" to caster)) { null }
        assertEquals(RuntimeValue.NullValue, invoke("targeter.self", missing))
        assertEquals(RuntimeValue.NullValue, invoke("targeter.attacker", missing))
        assertEquals(RuntimeValue.BooleanValue(false), invoke("condition.isPlayer", missing, RuntimeValue.NullValue))
    }

    @Test
    fun randomSelectionIsBoundedUniqueAndFiltersInvalidTargets() {
        val player = entity("player", 0.0).copy(kind = RuntimeValue.EntityKind.PLAYER)
        val other = entity("other", 1.0)
        val gone = entity("gone", 2.0)
        val context = context(maxTargets = 1) { if (it.id == "gone") null else it }
        repeat(10) {
            val result = assertIs<RuntimeValue.ListValue>(invoke("targeter.random", context,
                RuntimeValue.ListValue(listOf(player, other, gone, player)), RuntimeValue.NumberValue(20.0)))
            assertEquals(1, result.values.size)
            assertFalse((result.values.single() as RuntimeValue.EntityValue).id == "gone")
        }
        val all = assertIs<RuntimeValue.ListValue>(invoke("targeter.random", context(maxTargets = 10),
            RuntimeValue.ListValue(listOf(player, other, player)), RuntimeValue.NumberValue(20.0)))
        assertEquals(setOf("player", "other"), all.values.map { (it as RuntimeValue.EntityValue).id }.toSet())
        assertEquals(2, all.values.size)
        assertEquals(RuntimeValue.ListValue(emptyList()), invoke("targeter.random", context, RuntimeValue.ListValue(listOf(player)), RuntimeValue.NumberValue(0.0)))
        assertFailsWith<RuntimeEvaluationException> {
            invoke("targeter.random", context, RuntimeValue.ListValue(listOf(player)), RuntimeValue.NumberValue(0.5))
        }
    }

    @Test
    fun healthConditionsDistinguishAbsoluteHealthAndPercentageAndUseFreshState() {
        val old = entity("boss", 0.0).copy(currentHealth = 90.0, maxHealth = 200.0)
        val context = context { old.copy(currentHealth = 40.0) }
        assertEquals(RuntimeValue.BooleanValue(true), invoke("condition.healthBelow", context, old, RuntimeValue.PercentageValue(25.0)))
        assertEquals(RuntimeValue.BooleanValue(false), invoke("condition.healthBelow", context, old, RuntimeValue.NumberValue(25.0)))
        assertEquals(RuntimeValue.BooleanValue(true), invoke("condition.healthAbove", context, old, RuntimeValue.NumberValue(25.0)))
        assertEquals(RuntimeValue.BooleanValue(false), invoke("condition.healthAbove", context, old, RuntimeValue.PercentageValue(20.0)))
        assertEquals(RuntimeValue.BooleanValue(false), invoke("condition.isLiving", context { it.copy(currentHealth = null) }, old))
        assertEquals(RuntimeValue.BooleanValue(false), invoke("condition.healthBelow", context { null }, old, RuntimeValue.NumberValue(100.0)))
    }

    @Test
    fun distanceEventAndBooleanConditionsCompose() {
        val first = entity("one", 0.0)
        val second = entity("two", 3.0)
        val context = context(mapOf("event" to RuntimeValue.EventValue("damaged", cause = "ENTITY_ATTACK")))
        assertEquals(RuntimeValue.BooleanValue(true), invoke("condition.distanceBelow", context, first, second, RuntimeValue.NumberValue(4.0)))
        assertEquals(RuntimeValue.BooleanValue(false), invoke("condition.distanceBelow", context, first, second.copy(position = second.position!!.copy(world = "other")), RuntimeValue.NumberValue(4.0)))
        assertEquals(RuntimeValue.BooleanValue(true), invoke("condition.eventCause", context, RuntimeValue.TextValue("entity_attack")))
        assertEquals(RuntimeValue.BooleanValue(false), invoke("condition.all", context, RuntimeValue.BooleanValue(true), RuntimeValue.BooleanValue(false)))
        assertEquals(RuntimeValue.BooleanValue(true), invoke("condition.any", context, RuntimeValue.BooleanValue(true), RuntimeValue.BooleanValue(false)))
        assertEquals(RuntimeValue.BooleanValue(true), invoke("condition.not", context, RuntimeValue.BooleanValue(false)))
    }

    private fun entity(id: String, x: Double, z: Double = 0.0, world: String = "world") = RuntimeValue.EntityValue(
        id, position = RuntimeValue.PositionValue(x, 64.0, z, world), currentHealth = 20.0, maxHealth = 20.0
    )

    private fun context(
        variables: Map<String, RuntimeValue> = emptyMap(),
        maxTargets: Int = 64,
        resolver: RuntimeEntityResolver = RuntimeEntityResolver { it }
    ) = RuntimeContext(variables, api.actions, "caster", 16, maxTargets, entityResolver = resolver)

    private fun invoke(name: String, context: RuntimeContext, vararg args: RuntimeValue): RuntimeValue =
        context.invokeAction(name, args.map { RuntimeArgument(null, it) })
}
