package org.ewsk.spectrummobs.runtime

import org.ewsk.spectrummobs.script.RuntimeValue
import org.ewsk.spectrummobs.script.ScriptApiRegistry
import org.ewsk.spectrummobs.script.ScriptType
import org.ewsk.spectrummobs.config.MobConfigReloadResult
import org.ewsk.spectrummobs.config.MobConfigRepository
import org.ewsk.spectrummobs.skill.SkillFileRepository
import org.ewsk.spectrummobs.skill.SkillReloadResult
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SkillRuntimeApiTest {
    @Test
    fun platformBindsEveryDeclaredApiAndCompilesExtensionsDuringReload() {
        val directory = Files.createTempDirectory("spectrum-api-bindings-").toFile()
        try {
            val skillFile = directory.resolve("extension.sms")
            skillFile.writeText("skill extension { mechanic.test.mark() }\n")
            val runtime = SkillRuntimeService(
                SkillFileRepository(directory),
                projectileService = ProjectileService(),
                summonMob = { _, _, _ -> false },
                extendApi = {
                    register("mechanic.test.mark", emptyList(), ScriptType.Unit) { _, _ -> RuntimeValue.UnitValue }
                }
            )
            assertEquals(ScriptApiRegistry.default().names + "mechanic.test.mark", runtime.scriptApi.actions.names)
            val result = assertIs<SkillReloadResult.Success>(runtime.reload())
            assertEquals(1, result.snapshot.skills.size)
            assertEquals(emptyList(), result.diagnostics)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun mobValidationRejectsMissingSkillsAndKeepsValidDefinitions() {
        val skills = Files.createTempDirectory("spectrum-validation-skills-").toFile()
        val mobs = Files.createTempDirectory("spectrum-validation-mobs-").toFile()
        try {
            skills.resolve("spectrum/valid.sms").apply {
                parentFile.mkdirs()
                writeText("skill valid { wait 1t }")
            }
            val runtime = SkillRuntimeService(
                SkillFileRepository(skills),
                projectileService = ProjectileService(),
                summonMob = { _, _, _ -> false }
            )
            assertIs<SkillReloadResult.Success>(runtime.reload())
            mobs.resolve("valid.yml").writeText("valid")
            mobs.resolve("invalid.yml").writeText("invalid")
            val repository = MobConfigRepository(mobs, fileReader = { file ->
                if (file.name == "valid.yml") {
                    mapOf("schema-version" to 1, "id" to "spectrum:valid_mob", "type" to "ZOMBIE", "events" to mapOf("spawn" to listOf("spectrum:valid")))
                } else {
                    mapOf("schema-version" to 1, "id" to "spectrum:invalid_mob", "type" to "ZOMBIE", "events" to mapOf("spawn" to listOf("spectrum:missing")))
                }
            })
            val result = assertIs<MobConfigReloadResult.Success>(repository.reload(runtime::validateMobDefinition))
            assertEquals(setOf("spectrum:valid_mob"), result.snapshot.definitions.keys.map { it.toString() }.toSet())
            assertEquals(1, result.diagnostics.count { it.path.startsWith("events.spawn") })
        } finally {
            skills.deleteRecursively()
            mobs.deleteRecursively()
        }
    }

    @Test
    fun bundledRuleContractsValidateForMobFields() {
        val skills = java.io.File(requireNotNull(javaClass.getResource("/skills")).toURI())
        val mobs = Files.createTempDirectory("spectrum-rule-contract-mobs-").toFile()
        try {
            val runtime = SkillRuntimeService(
                SkillFileRepository(skills),
                projectileService = ProjectileService(),
                summonMob = { _, _, _ -> false }
            )
            assertIs<SkillReloadResult.Success>(runtime.reload())
            mobs.resolve("boss.yml").writeText("boss")
            val repository = MobConfigRepository(mobs, fileReader = {
                mapOf(
                    "schema-version" to 1,
                    "id" to "spectrum:boss",
                    "type" to "WITHER_SKELETON",
                    "behavior" to mapOf("skill" to "spectrum:magma_lord_behavior", "period" to "10t"),
                    "target-rules" to mapOf("threat" to "spectrum:combat.select_threat"),
                    "damage-rules" to mapOf("incoming" to "spectrum:combat.incoming", "outgoing" to "spectrum:combat.outgoing"),
                    "drops" to listOf(mapOf("material" to "MAGMA_CREAM", "amount" to 1, "chance" to 1.0, "rule" to "spectrum:combat.drop_multiplier"))
                )
            })
            val result = assertIs<MobConfigReloadResult.Success>(repository.reload(runtime::validateMobDefinition))
            assertEquals(emptyList(), result.diagnostics)
        } finally {
            mobs.deleteRecursively()
        }
    }

}
