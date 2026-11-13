package org.ewsk.spectrummobs.runtime

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Server
import org.bukkit.World
import org.bukkit.block.data.BlockData
import org.bukkit.boss.BarColor
import org.bukkit.boss.BarStyle
import org.bukkit.boss.BossBar
import org.bukkit.entity.Entity
import org.bukkit.entity.FallingBlock
import org.bukkit.entity.Player
import org.bukkit.persistence.PersistentDataContainer
import org.ewsk.spectrummobs.skill.SkillFileRepository
import org.ewsk.spectrummobs.skill.SkillReloadResult
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*
import java.nio.file.Files
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SkillEffectOwnershipTest {
    @Test
    fun projectileCleanupOnlyRemovesTheCancelledInstanceAndExpiryReleasesTheOther() = withServer { server, world ->
        val spawned = mutableListOf<FallingBlock>()
        val block = mock(BlockData::class.java)
        `when`(server.createBlockData(Material.MAGMA_BLOCK)).thenReturn(block)
        `when`(world.spawnFallingBlock(any(Location::class.java), any(BlockData::class.java))).thenAnswer {
            val projectile = mock(FallingBlock::class.java)
            val id = UUID.randomUUID()
            `when`(projectile.uniqueId).thenReturn(id)
            `when`(projectile.persistentDataContainer).thenReturn(mock(PersistentDataContainer::class.java))
            `when`(server.getEntity(id)).thenReturn(projectile)
            spawned += projectile
            projectile
        }
        withSkill("mechanic.projectile.forward(\"MAGMA_BLOCK\", 1, 5, 60t, 0.5)") { runtime, projectiles ->
            val caster = entity(world)
            val first = assertIs<SkillCastResult.Started>(runtime.cast("spectrum:test", caster))
            runtime.tick(1)
            runtime.cast("spectrum:test", caster)
            runtime.tick(2)
            assertEquals(2, spawned.size)
            assertEquals(2, runtime.activeSkills().size)
            assertEquals(1, runtime.cancelExecution(UUID.fromString(first.executionId), "Test interrupt"))
            verify(spawned[0]).remove()
            verify(spawned[1], never()).remove()
            projectiles.tick(100)
            verify(spawned[1]).remove()
            assertTrue(runtime.activeSkills().isEmpty())
        }
    }

    @Test
    fun bossbarsAreReusedWithinAnExecutionAndOwnedSeparatelyBetweenExecutions() = withServer { server, world ->
        val player = mock(Player::class.java)
        val playerId = UUID.randomUUID()
        `when`(player.uniqueId).thenReturn(playerId)
        `when`(player.world).thenReturn(world)
        `when`(player.location).thenReturn(Location(world, 0.0, 64.0, 0.0))
        `when`(player.isValid).thenReturn(true)
        `when`(player.isOnline).thenReturn(true)
        `when`(player.health).thenReturn(20.0)
        `when`(server.getPlayer(playerId)).thenReturn(player)
        `when`(world.getNearbyEntities(any(Location::class.java), anyDouble(), anyDouble(), anyDouble())).thenReturn(listOf(player))
        val bars = mutableListOf<BossBar>()
        `when`(server.createBossBar("§cTest", BarColor.BLUE, BarStyle.SOLID)).thenAnswer {
            mock(BossBar::class.java).also(bars::add)
        }
        withSkill("""
            for player in targeter.players.within(caster.position, 5) {
                mechanic.bossbar.show(player, "&cTest", 0.5, 10t)
                mechanic.bossbar.show(player, "&cTest", 0.4, 10t)
            }
        """.trimIndent()) { runtime, _ ->
            val caster = entity(world)
            val first = assertIs<SkillCastResult.Started>(runtime.cast("spectrum:test", caster))
            runtime.tick(1)
            runtime.cast("spectrum:test", caster)
            runtime.tick(2)
            assertEquals(2, bars.size)
            assertEquals(listOf(1, 1), runtime.activeSkills().map { it.effectCount })
            runtime.cancelExecution(UUID.fromString(first.executionId), "Test interrupt")
            verify(bars[0]).removeAll()
            verify(bars[1], never()).removeAll()
            runtime.tick(12)
            verify(bars[1]).removeAll()
            assertTrue(runtime.activeSkills().isEmpty())
        }
    }

    private fun entity(world: World): Entity = mock(Entity::class.java).also {
        `when`(it.uniqueId).thenReturn(UUID.randomUUID())
        `when`(it.world).thenReturn(world)
        `when`(it.location).thenReturn(Location(world, 0.0, 64.0, 0.0))
        `when`(it.persistentDataContainer).thenReturn(mock(PersistentDataContainer::class.java))
        `when`(it.isValid).thenReturn(true)
    }

    private fun withSkill(body: String, test: (SkillRuntimeService, ProjectileService) -> Unit) {
        val directory = Files.createTempDirectory("spectrum-effect-ownership-").toFile()
        try {
            directory.resolve("test.sms").writeText("skill test {\n$body\n}")
            val projectiles = ProjectileService()
            val runtime = SkillRuntimeService(SkillFileRepository(directory), projectileService = projectiles, summonMob = { org.ewsk.spectrummobs.mob.MobSpawnResult.Failed("Summoning disabled", org.ewsk.spectrummobs.core.OperationCode.CAPACITY) })
            assertIs<SkillReloadResult.Success>(runtime.reload())
            test(runtime, projectiles)
        } finally { directory.deleteRecursively() }
    }

    private fun withServer(test: (Server, World) -> Unit) {
        val field = Bukkit::class.java.getDeclaredField("server").apply { isAccessible = true }
        val previous = field.get(null)
        val server = mock(Server::class.java)
        field.set(null, server)
        try {
            val world = mock(World::class.java)
            `when`(world.name).thenReturn("world")
            `when`(world.uid).thenReturn(UUID(0, 1))
            `when`(server.getWorld("world")).thenReturn(world)
            test(server, world)
        } finally { field.set(null, previous) }
    }
}
