package org.ewsk.spectrummobs.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class MobDefinitionParserTest {
    @Test
    fun parsesSkillArgumentsAlongsideLegacyResourceIds() {
        val result = MobDefinitionParser().parse(
            mapOf(
                "schema-version" to 1,
                "id" to "spectrum:test",
                "type" to "ZOMBIE",
                "events" to mapOf(
                    "damaged" to listOf(
                        "spectrum:legacy",
                        mapOf("skill" to "spectrum:flame_nova", "args" to mapOf("radius" to 8, "damage" to 20, "windup" to "2s"))
                    )
                )
            ),
            "parameterized.yml"
        )
        val success = assertIs<MobDefinitionParseResult.Success>(result)
        assertEquals(listOf("spectrum:legacy", "spectrum:flame_nova"), success.definition.events.getValue("damaged").map { it.toString() })
        assertEquals(mapOf("radius" to 8, "damage" to 20, "windup" to "2s"), success.definition.eventArguments.getValue("damaged")[1])
    }

    @Test
    fun parsesBehaviorTargetDamageAndDropRules() {
        val result = MobDefinitionParser().parse(
            mapOf(
                "schema-version" to 1,
                "id" to "spectrum:boss",
                "type" to "ZOMBIE",
                "behavior" to mapOf("skill" to "spectrum:boss_behavior", "period" to "10t"),
                "target-rules" to mapOf("threat" to "spectrum:combat.select"),
                "damage-rules" to mapOf("incoming" to "spectrum:combat.incoming"),
                "drops" to listOf(mapOf("material" to "DIAMOND", "amount" to 1, "rule" to "spectrum:combat.drop"))
            ),
            "rules.yml"
        )
        val definition = assertIs<MobDefinitionParseResult.Success>(result).definition
        assertEquals(10L, definition.behavior?.periodTicks)
        assertEquals("spectrum:combat.select", definition.targetRules.getValue("threat").toString())
        assertEquals("spectrum:combat.incoming", definition.damageRules.getValue("incoming").toString())
        assertEquals("spectrum:combat.drop", definition.drops.single().rule?.toString())
    }

    @Test
    fun parsesGenericMovementController() {
        val result = MobDefinitionParser().parse(
            mapOf(
                "schema-version" to 1,
                "id" to "spectrum:flying_boss",
                "type" to "VEX",
                "movement" to mapOf(
                    "controller" to "hover",
                    "hover-height" to 3.0,
                    "target-distance" to 8.0,
                    "speed" to 0.25,
                    "update-period" to "2t"
                )
            ),
            "movement.yml"
        )
        val definition = assertIs<MobDefinitionParseResult.Success>(result).definition
        assertEquals("hover", definition.movement?.controller)
        assertEquals(2L, definition.movement?.updatePeriodTicks)
    }

    @Test
    fun parsesValidDefinition() {
        val result = MobDefinitionParser().parse(
            mapOf(
                "schema-version" to 1,
                "id" to "Spectrum:Ember_Guardian",
                "type" to "WITHER_SKELETON",
                "display" to mapOf("name" to "熔火守卫", "name-visible" to true),
                "attributes" to mapOf("max-health" to 300.0, "knockback-resistance" to 0.5),
                "events" to mapOf("damaged" to listOf("spectrum:flame_nova")),
                "intervals" to listOf(
                    mapOf(
                        "id" to "heartbeat",
                        "period" to "10s",
                        "skills" to listOf("spectrum:flame_nova")
                    )
                ),
                "boss" to mapOf(
                    "enabled" to true,
                    "phases" to listOf(
                        mapOf(
                            "id" to "normal",
                            "health-threshold" to 1.0,
                            "on-enter" to listOf("spectrum:boss_start")
                        ),
                        mapOf(
                            "id" to "enraged",
                            "health-threshold" to 0.5,
                            "attributes" to mapOf("attack-damage" to 20.0),
                            "on-enter" to listOf("spectrum:enrage")
                        )
                    )
                ),
                "bossbar" to mapOf(
                    "enabled" to true,
                    "title" to "&4熔核君王",
                    "color" to "RED",
                    "style" to "SOLID",
                    "range" to 24.0,
                    "update-interval" to "1t"
                ),
                "summons" to mapOf(
                    "max-active" to 6,
                    "cleanup-on-death" to true
                ),
                "drops" to listOf(mapOf("material" to "DIAMOND", "amount" to 2, "chance" to 0.5)),
                "model" to mapOf("provider" to "modelengine", "id" to "ember_guardian")
            ),
            "ember_guardian.yml"
        )

        val success = assertIs<MobDefinitionParseResult.Success>(result)
        assertEquals("spectrum:ember_guardian", success.definition.id.toString())
        assertEquals(300.0, success.definition.attributes.maxHealth)
        assertEquals(1, success.definition.events["damaged"]?.size)
        assertEquals(200L, success.definition.intervals.single().periodTicks)
        assertEquals("enraged", success.definition.boss?.phases?.get(1)?.id)
        assertEquals("&4熔核君王", success.definition.bossBar?.title)
        assertEquals(24.0, success.definition.bossBar?.range)
        assertEquals(6, success.definition.summons?.maxActive)
        assertEquals(2, success.definition.drops.single().amount)
        assertEquals("modelengine", success.definition.model?.provider)
    }

    @Test
    fun rejectsInvalidBossPhaseAndDrop() {
        val result = MobDefinitionParser().parse(
            mapOf(
                "schema-version" to 1,
                "id" to "spectrum:test",
                "type" to "ZOMBIE",
                "boss" to mapOf(
                    "phases" to listOf(mapOf("id" to "rage", "health-threshold" to 0.5))
                ),
                "drops" to listOf(mapOf("material" to "DIAMOND", "amount" to 0))
            ),
            "invalid-boss.yml"
        )

        val failure = assertIs<MobDefinitionParseResult.Failure>(result)
        assertEquals(2, failure.diagnostics.size)
    }

    @Test
    fun rejectsInvalidIdAndUnknownField() {
        val result = MobDefinitionParser().parse(
            mapOf("schema-version" to 1, "id" to "invalid", "type" to "ZOMBIE", "unexpected" to true),
            "invalid.yml"
        )

        val failure = assertIs<MobDefinitionParseResult.Failure>(result)
        assertEquals(2, failure.diagnostics.size)
    }

    @Test
    fun rejectsDuplicateDefinitions() {
        val parser = MobDefinitionParser()
        val first = assertIs<MobDefinitionParseResult.Success>(parser.parse(
            mapOf("schema-version" to 1, "id" to "spectrum:test", "type" to "ZOMBIE"), "a.yml"
        )).definition
        val second = assertIs<MobDefinitionParseResult.Success>(parser.parse(
            mapOf("schema-version" to 1, "id" to "spectrum:test", "type" to "SKELETON"), "b.yml"
        )).definition

        val result = MobSnapshotBuilder.build(listOf(MobDefinitionCandidate("a.yml", first), MobDefinitionCandidate("b.yml", second)))
        assertIs<MobSnapshotBuildResult.Failure>(result)
    }
}
