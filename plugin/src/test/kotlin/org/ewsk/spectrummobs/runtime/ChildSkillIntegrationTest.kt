package org.ewsk.spectrummobs.runtime

import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Entity
import org.bukkit.persistence.PersistentDataContainer
import org.ewsk.spectrummobs.script.RuntimeEvent
import org.ewsk.spectrummobs.script.RuntimeValue
import org.ewsk.spectrummobs.script.ScriptType
import org.ewsk.spectrummobs.skill.SkillFileRepository
import org.ewsk.spectrummobs.skill.SkillReloadResult
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.nio.file.Files
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ChildSkillIntegrationTest {
    @Test
    fun scriptCallsUseExplicitTargetRecordParentAndCleanChildEffectsOnCancel() {
        val directory = Files.createTempDirectory("spectrum-child-casts-").toFile()
        try {
            directory.resolve("parent.sms").writeText("""
                skill parent {
                    let secret = 1
                    let child = skills.cast("spectrum:child", caster, target)
                    wait 30t
                }
            """.trimIndent())
            directory.resolve("child.sms").writeText("skill child { mechanic.test.capture(); wait 30t }")
            var seenTarget: String? = null
            var seenParent: UUID? = null
            var cleaned = 0
            val runtime = SkillRuntimeService(SkillFileRepository(directory), projectileService = ProjectileService(),
                summonMob = { _, _, _ -> false }, extendApi = {
                    register("mechanic.test.capture", emptyList(), ScriptType.Unit) { context, _ ->
                        seenTarget = (context.target as RuntimeValue.EntityValue).id
                        seenParent = context.parentExecutionId
                        assertEquals("damaged", context.event.name)
                        assertEquals(2, context.skillDepth)
                        assertTrue("secret" !in context.variables)
                        context.effects.register(context.executionId, "test-projectile") { cleaned++ }
                        RuntimeValue.UnitValue
                    }
                })
            assertIs<SkillReloadResult.Success>(runtime.reload())
            val caster = entity(UUID.randomUUID())
            val target = entity(UUID.randomUUID())
            val result = assertIs<SkillCastResult.Started>(runtime.cast("spectrum:parent", caster,
                mapOf("target" to runtime.runtimeEntity(target), "event" to RuntimeValue.EventValue("damaged"))))
            runtime.tick(1)
            runtime.tick(2)
            assertEquals(target.uniqueId.toString(), seenTarget)
            assertEquals(UUID.fromString(result.executionId), seenParent)
            assertEquals(2, runtime.activeSkills().size)
            assertEquals(2, runtime.cancelExecution(UUID.fromString(result.executionId), "Cancelled by command"))
            assertEquals(1, cleaned)
            assertTrue(runtime.activeSkills().isEmpty())
            assertEquals(2, runtime.tick(3).filterIsInstance<RuntimeEvent.Cancelled>().size)
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun repositoryPublishesCanonicalIdentityForSameNamedDeclarations() {
        val directory = Files.createTempDirectory("spectrum-skill-identities-").toFile()
        try {
            listOf("one", "two").forEach { namespace ->
                val file = directory.resolve("$namespace/bolt.sms")
                file.parentFile.mkdirs()
                file.writeText("skill same_name { cooldown = 30t; wait 1t }")
            }
            val result = assertIs<SkillReloadResult.Success>(SkillFileRepository(directory).reload())
            assertEquals(setOf("one:bolt", "two:bolt"), result.snapshot.skills.values.map { it.id }.toSet())
        } finally { directory.deleteRecursively() }
    }

    private fun entity(id: UUID): Entity {
        val world = mock(World::class.java)
        `when`(world.name).thenReturn("world")
        `when`(world.uid).thenReturn(UUID(0, 1))
        val pdc = mock(PersistentDataContainer::class.java)
        return mock(Entity::class.java).also { entity ->
            `when`(entity.uniqueId).thenReturn(id)
            `when`(entity.world).thenReturn(world)
            `when`(entity.location).thenReturn(Location(world, 0.0, 64.0, 0.0))
            `when`(entity.persistentDataContainer).thenReturn(pdc)
            `when`(entity.isValid).thenReturn(true)
        }
    }
}
