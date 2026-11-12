package org.ewsk.spectrummobs.skill

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SkillFileRepositoryTest {
    @Test
    fun parameterizedSkillsAndImportedRulesCompileAsOneSnapshot() {
        val directory = Files.createTempDirectory("spectrum-parameterized-snapshot-").toFile()
        try {
            val combat = directory.resolve("spectrum/combat.sms")
            combat.parentFile.mkdirs()
            combat.writeText("""
                fn double(value: Number): Number {
                    return value * 2
                }
            """.trimIndent())
            val skill = directory.resolve("spectrum/attack.sms")
            skill.writeText("""
                import "spectrum:combat" as combat
                skill attack(radius: Number = 5, damage: Number = 12) {
                    requires radius > 0
                    let amount = combat.double(damage)
                    wait 1t
                }
            """.trimIndent())
            val result = assertIs<SkillReloadResult.Success>(SkillFileRepository(directory).reload())
            val compiled = result.snapshot.skills.getValue(org.ewsk.spectrummobs.core.ResourceId.parse("spectrum:attack")!!)
            assertEquals(listOf("radius", "damage"), compiled.parameters.map { it.name })
            assertEquals(setOf("combat.double"), result.snapshot.rulesBySkill.values.single().keys)
            assertTrue(result.snapshot.rules.containsKey("spectrum:combat.double"))
            assertEquals(emptyList(), result.diagnostics)
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun missingRuleImportDoesNotInvalidateUnrelatedSkillFiles() {
        val directory = Files.createTempDirectory("spectrum-rule-partial-").toFile()
        try {
            directory.resolve("spectrum/valid.sms").apply {
                parentFile.mkdirs()
                writeText("skill valid(radius: Number = 5) { wait 1t }")
            }
            directory.resolve("spectrum/broken.sms").writeText("import \"spectrum:missing\" as missing\nskill broken { requires missing.ok(caster) }")
            val result = assertIs<SkillReloadResult.Success>(SkillFileRepository(directory).reload())
            assertEquals(setOf("spectrum:valid"), result.snapshot.skills.keys.map { it.toString() }.toSet())
            assertTrue(result.diagnostics.any { it.message.contains("Imported rule module") })
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun cyclicRuleModulesAreRejectedWithoutBlockingUnrelatedSkills() {
        val directory = Files.createTempDirectory("spectrum-rule-cycle-").toFile()
        try {
            directory.resolve("spectrum/a.sms").apply { parentFile.mkdirs(); writeText("import \"spectrum:b\" as b\nfn a(): Boolean { return b.b() }") }
            directory.resolve("spectrum/b.sms").writeText("import \"spectrum:a\" as a\nfn b(): Boolean { return a.a() }")
            directory.resolve("spectrum/valid.sms").writeText("skill valid { wait 1t }")
            val result = assertIs<SkillReloadResult.Success>(SkillFileRepository(directory).reload())
            assertEquals(setOf("spectrum:valid"), result.snapshot.skills.keys.map { it.toString() }.toSet())
            assertTrue(result.diagnostics.any { it.message.contains("Circular rule module import") })
        } finally { directory.deleteRecursively() }
    }
    @Test
    fun everyBundledSkillCompilesWithFinalApi() {
        val directory = java.io.File(assertNotNull(javaClass.getResource("/skills")).toURI())
        val result = assertIs<SkillReloadResult.Success>(SkillFileRepository(directory).reload())
        val skillFiles = directory.walkTopDown()
            .filter { it.isFile && it.extension == "sms" }
            .count { Regex("(?m)^\\s*skill\\s+").containsMatchIn(it.readText()) }
        assertEquals(skillFiles, result.snapshot.skills.size)
        assertEquals(emptyList(), result.diagnostics)
    }

    @Test
    fun invalidReloadKeepsPreviousSkillSnapshot() {
        val directory = Files.createTempDirectory("spectrum-skills-").toFile()
        try {
            val file = directory.resolve("spectrum/test.sms")
            file.parentFile.mkdirs()
            file.writeText("skill test { wait 1t }\n")

            val repository = SkillFileRepository(directory)
            val first = assertIs<SkillReloadResult.Success>(repository.reload())
            assertEquals(1, first.snapshot.skills.size)
            assertEquals("spectrum:test", first.snapshot.skills.keys.single().toString())

            file.writeText("skill broken { wait 5 }\n")
            assertIs<SkillReloadResult.Failure>(repository.reload())
            assertEquals(first.snapshot, repository.current())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun invalidSkillDoesNotBlockOtherSkills() {
        val directory = Files.createTempDirectory("spectrum-partial-skills-").toFile()
        try {
            val valid = directory.resolve("spectrum/valid.sms")
            valid.parentFile.mkdirs()
            valid.writeText("skill valid { wait 1t }\n")
            val invalid = directory.resolve("spectrum/invalid.sms")
            invalid.writeText("skill invalid { wait 5 }\n")

            val result = assertIs<SkillReloadResult.Success>(SkillFileRepository(directory).reload())
            assertEquals(setOf("spectrum:valid"), result.snapshot.skills.keys.map { it.toString() }.toSet())
            assertEquals(1, result.diagnostics.size)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun bundledFeedbackSkillCompiles() {
        val resource = assertNotNull(javaClass.getResource("/skills/spectrum/boss_warning.sms"))
        val directory = Files.createTempDirectory("spectrum-bundled-skills-").toFile()
        try {
            val file = directory.resolve("spectrum/boss_warning.sms")
            file.parentFile.mkdirs()
            resource.openStream().use { input -> file.outputStream().use { output -> input.copyTo(output) } }
            val result = assertIs<SkillReloadResult.Success>(SkillFileRepository(directory).reload())
            assertEquals("spectrum:boss_warning", result.snapshot.skills.keys.single().toString())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun bundledBlueFlameRingSkillCompiles() {
        val resource = assertNotNull(javaClass.getResource("/skills/spectrum/blue_flame_ring.sms"))
        val directory = Files.createTempDirectory("spectrum-blue-ring-skills-").toFile()
        try {
            val file = directory.resolve("spectrum/blue_flame_ring.sms")
            file.parentFile.mkdirs()
            resource.openStream().use { input -> file.outputStream().use { output -> input.copyTo(output) } }
            val result = assertIs<SkillReloadResult.Success>(SkillFileRepository(directory).reload())
            assertEquals("spectrum:blue_flame_ring", result.snapshot.skills.keys.single().toString())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun bundledMagmaBossSkillsCompileTogether() {
        val names = listOf(
            "magma_lord_intro",
            "magma_lord_bolt",
            "magma_lord_wave",
            "magma_lord_summon",
            "magma_lord_rebuke",
            "magma_lord_phase_heated",
            "magma_lord_phase_overheated",
            "magma_minion_bolt",
            "magma_minion_pulse"
        )
        val directory = Files.createTempDirectory("spectrum-magma-boss-skills-").toFile()
        try {
            names.forEach { name ->
                val file = directory.resolve("spectrum/$name.sms")
                file.parentFile.mkdirs()
                assertNotNull(javaClass.getResourceAsStream("/skills/spectrum/$name.sms")).use { input ->
                    input.copyTo(file.outputStream())
                }
            }
            val result = assertIs<SkillReloadResult.Success>(SkillFileRepository(directory).reload())
            assertEquals(names.size, result.snapshot.skills.size, result.diagnostics.joinToString())
            assertEquals(emptyList(), result.diagnostics)
        } finally {
            directory.deleteRecursively()
        }
    }
}
