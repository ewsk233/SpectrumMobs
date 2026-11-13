package org.ewsk.spectrummobs.runtime

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.Server
import org.bukkit.World
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
import org.bukkit.persistence.PersistentDataContainer
import org.bukkit.persistence.PersistentDataType
import org.bukkit.util.Vector
import org.ewsk.spectrummobs.core.MobDefinition
import org.ewsk.spectrummobs.core.OperationCode
import org.ewsk.spectrummobs.core.ResourceId
import org.ewsk.spectrummobs.core.MobModel
import org.ewsk.spectrummobs.integration.MobSpawnContext
import org.ewsk.spectrummobs.visual.DisplayVisualService
import org.ewsk.spectrummobs.mob.MobIdentity
import org.ewsk.spectrummobs.mob.MobSpawnResult
import org.ewsk.spectrummobs.mob.MobService
import org.ewsk.spectrummobs.config.MobConfigRepository
import org.ewsk.spectrummobs.config.MobConfigReloadResult
import org.ewsk.spectrummobs.script.*
import org.ewsk.spectrummobs.skill.SkillFileRepository
import org.ewsk.spectrummobs.skill.SkillReloadResult
import org.mockito.Mockito.*
import java.nio.file.Files
import java.util.UUID
import kotlin.test.*

class SummonLifecycleTest {
    @Test
    fun childCastReturnsSuccessOrExpectedCodeButMissingAndDepthErrorsStillFail() = withWorld { f ->
        val results = mutableListOf<RuntimeValue.OperationResultValue>()
        f.runtime("""
            mechanic.test.capture(mechanic.skills.cast("spectrum:child", caster, target, ignoreRejected = true))
            mechanic.test.capture(mechanic.skills.cast("spectrum:child", caster, target, ignoreRejected = true))
        """.trimIndent(), extraSkills = mapOf("child" to "skill child { cooldown = 20t; wait 2t }"),
            capture = results::add) { runtime ->
            runtime.cast("spectrum:test", f.owner)
            assertTrue(runtime.tick(1).none { it is RuntimeEvent.Failed })
            assertTrue(results[0].success)
            assertNotNull(UUID.fromString(results[0].id))
            assertEquals(OperationCode.COOLDOWN, results[1].code)
            assertFalse(results[1].success)
        }
        f.runtime("""mechanic.skills.cast("spectrum:missing", caster, target, ignoreRejected = true)""") { runtime ->
            runtime.cast("spectrum:test", f.owner)
            assertContains(runtime.tick(1).filterIsInstance<RuntimeEvent.Failed>().single().message, "missing_definition")
        }
        f.runtime("""mechanic.skills.cast("spectrum:child", caster, target, ignoreRejected = true)""",
            extraSkills = mapOf("child" to """skill child { mechanic.skills.cast("spectrum:child", caster, target, linked = false, ignoreRejected = true) }"""),
            limits = RuntimeLimits(maxCallDepth = 2)) { runtime ->
            runtime.cast("spectrum:test", f.owner)
            runtime.tick(1)
            assertContains(runtime.tick(2).filterIsInstance<RuntimeEvent.Failed>().single().message, "depth_limit")
        }
    }

    @Test
    fun fullCapacityDoesNotHideMissingDefinitionsAndBackendKeepsFailureReason() = withWorld { f ->
        val directory = Files.createTempDirectory("spectrum-summon-backend-").toFile()
        try {
            directory.resolve("minion.yml").writeText("placeholder")
            val repository = MobConfigRepository(directory, fileReader = {
                mapOf("schema-version" to 1, "id" to "spectrum:minion", "type" to "ZOMBIE",
                    "summons" to mapOf("max-active" to 1))
            })
            assertIs<MobConfigReloadResult.Success>(repository.reload())
            MobIdentity.mark(f.owner, "spectrum:minion", UUID.randomUUID())
            MobIdentity.markSummonOwner(f.entity(), f.owner.uniqueId)
            val backend = MobService(repository)
            assertEquals(OperationCode.CAPACITY,
                assertIs<MobSpawnResult.Failed>(backend.summon(f.owner, "spectrum:minion", f.owner.location)).code)
            val missing = assertIs<MobSpawnResult.Failed>(backend.summon(f.owner, "spectrum:missing", f.owner.location))
            assertEquals(OperationCode.MISSING_DEFINITION, missing.code)
            assertContains(missing.reason, "spectrum:missing")
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun expectedCapacityRefusalIsReturnedWithoutRetryAndUnexpectedFailureCannotBeIgnored() = withWorld { f ->
        for (code in listOf(OperationCode.CAPACITY, OperationCode.MISSING_DEFINITION, OperationCode.SPAWN_FAILED)) {
            var attempts = 0
            var observed: RuntimeValue.OperationResultValue? = null
            f.runtime("""
                let batch = mechanic.mob.summon("spectrum:minion", caster.position, 3, ignoreRejected = true)
                mechanic.test.capture(batch)
            """.trimIndent(), spawn = { attempts++; MobSpawnResult.Failed("Backend reason", code) }, capture = { observed = it }) { runtime ->
                runtime.cast("spectrum:test", f.owner)
                val events = runtime.tick(1)
                assertEquals(1, attempts, code.key)
                if (code.expectedRejection) {
                    assertTrue(events.none { it is RuntimeEvent.Failed })
                    assertEquals(code, observed?.code)
                    assertEquals(0, observed?.count)
                    assertEquals("Backend reason", observed?.reason)
                } else {
                    val failure = events.filterIsInstance<RuntimeEvent.Failed>().single()
                    assertContains(failure.message, code.key)
                    assertContains(failure.message, "Backend reason")
                    assertNull(observed)
                }
                assertTrue(runtime.activeSkills().isEmpty())
            }
        }
    }

    @Test
    fun strictRefusalAndUnexpectedPartialFailureRollBackEveryCreatedEntity() = withWorld { f ->
        for (code in listOf(OperationCode.CAPACITY, OperationCode.SPAWN_FAILED)) {
            var attempts = 0
            val created = mutableListOf<Entity>()
            f.runtime("""mechanic.mob.summon("spectrum:minion", caster.position, 3)""",
                spawn = { request ->
                    if (++attempts == 1) f.spawn(request).also { created += it.entity }
                    else MobSpawnResult.Failed("Refusal after first spawn", code)
                }) { runtime ->
                runtime.cast("spectrum:test", f.owner)
                assertEquals(1, runtime.tick(1).filterIsInstance<RuntimeEvent.Failed>().size)
                assertEquals(2, attempts)
                assertFalse(created.single().isValid)
                assertTrue(MobIdentity.isSummonCleanup(created.single()))
                assertTrue(runtime.activeSkills().isEmpty())
            }
        }
    }

    @Test
    fun ownerAndBatchQueriesAreIsolatedEvenWithIdenticalTags() = withWorld { f ->
        val service = SummonService(f::spawn, f::resolve, f::owned)
        val first = service.summon(f.context(), f.owner, "minion", f.owner.location, 2, "core", 0)
        val second = service.summon(f.context(), f.owner, "minion", f.owner.location, 1, "core", 0)
        val otherOwner = f.entity()
        val third = service.summon(f.context(otherOwner), otherOwner, "minion", otherOwner.location, 2, "core", 0)
        assertEquals(3, service.targets(f.owner, tag = "core").size)
        assertEquals(2, service.targets(f.owner, first).size)
        assertEquals(1, service.targets(f.owner, second).size)
        assertEquals(2, service.targets(otherOwner, third).size)
        assertFailsWith<IllegalArgumentException> { service.targets(otherOwner, first) }
        service.cleanup(f.owner, first)
        assertEquals(1, service.targets(f.owner, tag = "core").size)
        assertEquals(2, service.targets(otherOwner, third).size)
        assertFalse(service.defeated(f.owner, first))
    }

    @Test
    fun onlyAllCombatDeathsCountAsDefeatedAndDeathFollowedByRemovalIsIdempotent() = withWorld { f ->
        val service = SummonService(f::spawn, f::resolve, f::owned)
        val context = f.context()
        val result = service.summon(context, f.owner, "minion", f.owner.location, 2, null, 0)
        val members = service.targets(f.owner, result)
        assertEquals(1, context.effects.count(context.executionId))
        service.onDeath(members[0])
        service.onRemoved(members[0])
        assertFalse(service.defeated(f.owner, result))
        service.onDeath(members[1])
        service.onRemoved(members[1])
        assertTrue(service.defeated(f.owner, result))
        assertEquals(0, context.effects.count(context.executionId))

        val lost = service.summon(context, f.owner, "minion", f.owner.location, 1, null, 0)
        service.onRemoved(service.targets(f.owner, lost).single())
        assertFalse(service.defeated(f.owner, lost))
        assertEquals(0, context.effects.count(context.executionId))
    }

    @Test
    fun timeoutPartialSpawnAndProgramCleanupNeverCountAsCombatVictory() = withWorld { f ->
        var attempts = 0
        val partial = SummonService({ request ->
            if (++attempts == 1) f.spawn(request) else MobSpawnResult.Failed("Full", OperationCode.CAPACITY)
        }, f::resolve, f::owned)
        val result = partial.summon(f.context(), f.owner, "minion", f.owner.location, 3, null, 0)
        assertEquals(1, result.count)
        assertFalse(result.success)
        partial.onDeath(partial.targets(f.owner, result).single())
        assertFalse(partial.defeated(f.owner, result))

        val service = SummonService(f::spawn, f::resolve, f::owned)
        val timed = service.summon(f.context(), f.owner, "minion", f.owner.location, 1, null, 10)
        val entity = service.targets(f.owner, timed).single()
        service.tick(10)
        service.onDeath(entity) // 即使平台晚到的死亡事件也不能改写清理原因。
        assertFalse(service.defeated(f.owner, timed))
        assertFalse(entity.isValid)
        val removed = service.summon(f.context(), f.owner, "minion", f.owner.location, 1, null, 0)
        service.cleanup(f.owner, removed)
        assertFalse(service.defeated(f.owner, removed))
    }

    @Test
    fun completedProgramRetainsSummonScopeUntilLastCombatDeath() = withWorld { f ->
        var result: RuntimeValue.OperationResultValue? = null
        f.runtime("""
            let batch = mechanic.mob.summon("spectrum:minion", caster.position, 2)
            mechanic.test.capture(batch)
        """.trimIndent(), capture = { result = it }) { runtime ->
            runtime.cast("spectrum:test", f.owner)
            runtime.tick(1)
            assertEquals(ExecutionState.COMPLETED, runtime.activeSkills().single().state)
            assertEquals(1, runtime.activeSkills().single().effectCount)
            runtime.summons.targets(f.owner, result).forEach(runtime.summons::onDeath)
            assertTrue(runtime.summons.defeated(f.owner, result!!))
            assertTrue(runtime.activeSkills().isEmpty())
        }
    }

    @Test
    fun phaseInterruptCleansBatchAndTemporaryStateWhileWaiting() = withWorld { f ->
        f.runtime("""
            mechanic.entity.temporaryTag(caster, "weak", 10s)
            mechanic.mob.summon("spectrum:minion", caster.position, 2, lifetime = 10s)
            wait 5s
        """.trimIndent()) { runtime ->
            runtime.cast("spectrum:test", f.owner, mapOf("phase" to RuntimeValue.TextValue("first")))
            runtime.tick(1)
            val summons = f.owned(f.owner.uniqueId)
            assertTrue("weak" in f.owner.scoreboardTags)
            assertEquals(2, summons.size)
            runtime.cancelForPhaseChange(f.owner, "first")
            assertFalse("weak" in f.owner.scoreboardTags)
            assertTrue(summons.none { it.isValid })
            assertTrue(runtime.activeSkills().isEmpty())
        }
    }

    @Test
    fun oneCleanupFailureIsReportedAndDoesNotSkipOtherMembersOrEffects() = withWorld { f ->
        f.runtime("""
            mechanic.mob.summon("spectrum:minion", caster.position, 2)
            mechanic.entity.temporaryTag(caster, "weak", 10s)
            wait 5s
        """.trimIndent()) { runtime ->
            runtime.cast("spectrum:test", f.owner)
            runtime.tick(1)
            val members = f.owned(f.owner.uniqueId)
            doThrow(IllegalStateException("Platform removal failed")).`when`(members[0]).remove()
            runtime.cancelAll("Test interrupt")
            assertFalse(members[1].isValid)
            assertFalse("weak" in f.owner.scoreboardTags)
            val event = runtime.tick(2).filterIsInstance<RuntimeEvent.EffectCleanupFailed>().single()
            assertContains(event.message, "Platform removal failed")
            assertTrue(runtime.activeSkills().isEmpty())
        }
    }

    @Test
    fun invalidCountDoesNotReachBackendEvenWhenIgnoringRefusals() = withWorld { f ->
        for (count in listOf("0", "1.5", "65")) {
            var attempts = 0
            f.runtime("""mechanic.mob.summon("spectrum:minion", caster.position, $count, ignoreRejected = true)""",
                spawn = { attempts++; f.spawn(it) }) { runtime ->
                runtime.cast("spectrum:test", f.owner)
                assertContains(runtime.tick(1).filterIsInstance<RuntimeEvent.Failed>().single().message, "integer between 1 and 64")
                assertEquals(0, attempts)
            }
        }
    }

    @Test
    fun retainedResourcesExpireAndCasterOrWorldLifecycleForcesCleanup() = withWorld { f ->
        for (action in listOf("expiry", "caster", "world")) {
            f.runtime("""
                cleanupOnCancel = false
                mechanic.entity.temporaryTag(caster, "weak", 4t)
                mechanic.mob.summon("spectrum:minion", caster.position, lifetime = 4t)
                wait 20t
            """.trimIndent()) { runtime ->
                val cast = assertIs<SkillCastResult.Started>(runtime.cast("spectrum:test", f.owner))
                runtime.tick(1)
                val spawned = f.owned(f.owner.uniqueId)
                runtime.cancelExecution(UUID.fromString(cast.executionId), "Manual interrupt")
                assertTrue(spawned.single().isValid)
                assertTrue("weak" in f.owner.scoreboardTags)
                when (action) {
                    "expiry" -> runtime.tick(5)
                    "caster" -> runtime.cancelByCaster(f.owner, "Caster died")
                    "world" -> runtime.cancelByWorld(f.world.uid.toString(), "World unloaded")
                }
                assertFalse(spawned.single().isValid)
                assertFalse("weak" in f.owner.scoreboardTags)
                assertTrue(runtime.activeSkills().isEmpty(), action)
            }
        }
    }

    @Test
    fun deathAndRemovalWithoutPlatformEventStillCancelWaitingSkillsAndTheirEffects() = withWorld { f ->
        for (cause in listOf("death", "removed")) {
            val caster = f.entity()
            f.runtime("""
                cleanupOnCancel = false
                mechanic.entity.temporaryTag(caster, "weak", 10s)
                mechanic.mob.summon("spectrum:minion", caster.position, lifetime = 10s)
                wait 5s
                mechanic.entity.addTag(caster, "should_never_run")
            """.trimIndent()) { runtime ->
                runtime.cast("spectrum:test", caster)
                runtime.tick(1)
                val summon = f.owned(caster.uniqueId).single()
                if (cause == "death") `when`(caster.isDead).thenReturn(true) else caster.remove()
                val events = runtime.tick(2)
                assertTrue(events.any { it is RuntimeEvent.Cancelled })
                assertTrue(events.none { it is RuntimeEvent.Failed })
                assertFalse(summon.isValid)
                assertFalse("weak" in caster.scoreboardTags)
                runtime.tick(200)
                assertFalse("should_never_run" in caster.scoreboardTags)
                assertTrue(runtime.activeSkills().isEmpty())
            }
        }
    }

    @Test
    fun displayAttachmentsAreRemovedOnDeathWorldUnloadAndOrphanScan() = withWorld { f ->
        val created = mutableListOf<TextDisplay>()
        val firstDisplay = f.textDisplay()
        `when`(f.world.spawn(any(Location::class.java), eq(TextDisplay::class.java))).thenAnswer {
            (if (created.isEmpty()) firstDisplay else f.textDisplay()).also(created::add)
        }
        val visual = DisplayVisualService()
        val model = MobModel("display", "orbit", mapOf("count" to "3"))
        val definition = MobDefinition(ResourceId.parse("spectrum:boss")!!, "VEX", model = model)
        visual.attach(MobSpawnContext(f.owner, definition), model)
        assertEquals(4, created.size)
        created.forEach { verify(it).setPersistent(false) }
        `when`(f.owner.isDead).thenReturn(true)
        visual.tick(1)
        assertTrue(created.none { it.isValid })

        `when`(f.owner.isDead).thenReturn(false)
        visual.attach(MobSpawnContext(f.owner, definition), model)
        visual.detachWorld(f.world.uid)
        assertTrue(created.none { it.isValid })

        val orphan = f.textDisplay()
        orphan.persistentDataContainer.set(NamespacedKey("spectrummobs", "visual_owner"),
            PersistentDataType.STRING, UUID.randomUUID().toString())
        visual.cleanupOrphans(listOf(orphan))
        assertFalse(orphan.isValid)
    }

    @Test
    fun unloadedOrOrphanedSummonsAreCleanedAndCannotReappearAsActiveBatchMembers() = withWorld { f ->
        val service = SummonService(f::spawn, f::resolve, f::owned)
        val batch = service.summon(f.context(), f.owner, "minion", f.owner.location, 2, null, 0)
        val members = service.targets(f.owner, batch)
        service.onRemoved(members[0])
        service.cleanupOrphan(members[0])
        assertFalse(members[0].isValid)
        assertFalse(service.defeated(f.owner, batch))
        service.cleanupWorld(f.world.uid)
        assertFalse(members[1].isValid)
        val newService = SummonService(f::spawn, f::resolve, f::owned)
        val orphan = f.entity()
        MobIdentity.markSummonBatch(orphan, UUID.randomUUID(), UUID.randomUUID())
        newService.cleanupOrphan(orphan)
        assertFalse(orphan.isValid)
    }

    @Test
    fun temporaryTagsUseIndependentLeasesAndPreserveExistingPermanentTags() = withWorld { f ->
        val service = TemporaryTagService()
        val first = f.context()
        val second = f.context()
        service.add(f.owner, "shared", 20, first)
        service.add(f.owner, "shared", 40, second)
        first.effects.cleanup(first.executionId)
        assertTrue("shared" in f.owner.scoreboardTags)
        service.tick(40)
        assertFalse("shared" in f.owner.scoreboardTags)
        assertEquals(0, second.effects.count(second.executionId))
        f.owner.addScoreboardTag("permanent")
        service.add(f.owner, "permanent", 10, first)
        first.effects.cleanup(first.executionId)
        assertTrue("permanent" in f.owner.scoreboardTags)
    }

    @Test
    fun finalChapterDoesNotAnnounceOrGrantWeaknessForZeroOrPartialSpawns() = withWorld { f ->
        val player = f.player()
        for (allowed in 0..1) {
            var attempts = 0
            f.runtime(resource = "manuscript_final_chapter", spawn = { request ->
                if (attempts++ < allowed) f.spawn(request) else MobSpawnResult.Failed("Full", OperationCode.CAPACITY)
            }) { runtime ->
                runtime.cast("spectrum:test", f.owner, mapOf("phase" to RuntimeValue.TextValue("finale")))
                assertTrue(runtime.tick(1).none { it is RuntimeEvent.Failed })
                runtime.tick(200)
                assertFalse("spectrum_manuscript_vulnerable" in f.owner.scoreboardTags)
                assertTrue(f.owned(f.owner.uniqueId).isEmpty())
                assertTrue(runtime.activeSkills().isEmpty())
                verify(player, never()).sendTitle(anyString(), anyString(), anyInt(), anyInt(), anyInt())
                verify(player, never()).damage(anyDouble())
            }
        }
    }

    @Test
    fun finalChapterRewardsCombatKillsButPenalizesRemovalAndCleansSurvivors() = withWorld { f ->
        val player = f.player()
        for (outcome in listOf("killed", "removed", "survived")) {
            f.runtime(resource = "manuscript_final_chapter") { runtime ->
                assertIs<SkillCastResult.Started>(runtime.cast("spectrum:test", f.owner, mapOf("phase" to RuntimeValue.TextValue("finale"))))
                val initialEvents = runtime.tick(1)
                assertTrue(initialEvents.none { it is RuntimeEvent.Failed }, initialEvents.toString())
                val members = f.owned(f.owner.uniqueId)
                assertEquals(3, members.size)
                when (outcome) {
                    "killed" -> members.forEach { runtime.summons.onDeath(it); it.remove() }
                    "removed" -> members.forEach { runtime.summons.onRemoved(it); it.remove() }
                }
                (2L..102L).forEach { tick ->
                    val events = runtime.tick(tick)
                    assertTrue(events.none { it is RuntimeEvent.Failed }, events.toString())
                }
                assertEquals(outcome == "killed", "spectrum_manuscript_vulnerable" in f.owner.scoreboardTags)
                if (outcome == "killed") verify(player, never()).damage(anyDouble())
                else verify(player).damage(28.0)
                assertTrue(f.owned(f.owner.uniqueId).isEmpty())
                runtime.cancelAll("Test ended")
                assertFalse("spectrum_manuscript_vulnerable" in f.owner.scoreboardTags)
                clearInvocations(player)
            }
        }
    }

    private fun withWorld(test: (Fixture) -> Unit) {
        val field = Bukkit::class.java.getDeclaredField("server").apply { isAccessible = true }
        val previous = field.get(null)
        val server = mock(Server::class.java)
        field.set(null, server)
        try { test(Fixture(server)) } finally { field.set(null, previous) }
    }

    private class Fixture(private val server: Server) {
        val world: World = mock(World::class.java)
        private val entities = linkedMapOf<UUID, Entity>()
        val owner: Entity
        init {
            `when`(world.uid).thenReturn(UUID(0, 1))
            `when`(world.name).thenReturn("world")
            `when`(server.worlds).thenReturn(listOf(world))
            `when`(server.getWorld("world")).thenReturn(world)
            `when`(world.entities).thenAnswer { entities.values.filter { it.isValid } }
            `when`(world.getNearbyEntities(any(Location::class.java), anyDouble(), anyDouble(), anyDouble()))
                .thenAnswer { entities.values.filter { it.isValid } }
            `when`(server.getEntity(any(UUID::class.java))).thenAnswer { entities[it.getArgument<UUID>(0)]?.takeIf { it.isValid } }
            owner = entity()
        }

        fun entity(): Entity = initialize(mock(Entity::class.java))
        fun textDisplay(): TextDisplay = initialize(mock(TextDisplay::class.java))
        fun player(): Player = initialize(mock(Player::class.java)).also {
            `when`(it.isOnline).thenReturn(true)
            `when`(it.health).thenReturn(20.0)
            `when`(it.locale).thenReturn("en_us")
        }

        private fun <T : Entity> initialize(entity: T): T {
            val id = UUID.randomUUID()
            val tags = linkedSetOf<String>()
            val values = mutableMapOf<NamespacedKey, Any>()
            val pdc = mock(PersistentDataContainer::class.java)
            `when`(pdc.get(any(NamespacedKey::class.java), eq(PersistentDataType.STRING)))
                .thenAnswer { values[it.getArgument<NamespacedKey>(0)] as? String }
            `when`(pdc.get(any(NamespacedKey::class.java), eq(PersistentDataType.BYTE)))
                .thenAnswer { values[it.getArgument<NamespacedKey>(0)] as? Byte }
            doAnswer { values[it.getArgument(0)] = it.getArgument<String>(2); null }
                .`when`(pdc).set(any(NamespacedKey::class.java), eq(PersistentDataType.STRING), anyString())
            doAnswer { values[it.getArgument(0)] = it.getArgument<Byte>(2); null }
                .`when`(pdc).set(any(NamespacedKey::class.java), eq(PersistentDataType.BYTE), anyByte())
            var valid = true
            `when`(entity.uniqueId).thenReturn(id)
            `when`(entity.world).thenReturn(world)
            `when`(entity.location).thenReturn(Location(world, 0.0, 64.0, 0.0))
            `when`(entity.velocity).thenAnswer { Vector() }
            `when`(entity.persistentDataContainer).thenReturn(pdc)
            `when`(entity.isValid).thenAnswer { valid }
            `when`(entity.scoreboardTags).thenReturn(tags)
            `when`(entity.addScoreboardTag(anyString())).thenAnswer { tags.add(it.getArgument(0)) }
            `when`(entity.removeScoreboardTag(anyString())).thenAnswer { tags.remove(it.getArgument<String>(0)) }
            doAnswer { valid = false; null }.`when`(entity).remove()
            entities[id] = entity
            return entity
        }

        fun resolve(id: UUID): Entity? = entities[id]?.takeIf { it.isValid }
        fun owned(id: UUID): List<Entity> = entities.values.filter { it.isValid && MobIdentity.summonOwner(it) == id }
        fun context(entity: Entity = owner): RuntimeContext = RuntimeContext(
            emptyMap(), RuntimeActionRegistry(), entity.uniqueId.toString(), 16, worldKey = world.uid.toString()
        )

        fun spawn(request: SummonRequest): MobSpawnResult.Spawned {
            val entity = entity()
            MobIdentity.markSummonOwner(entity, request.owner.uniqueId)
            MobIdentity.markSummonBatch(entity, request.batchId, request.executionId)
            request.tag?.let(entity::addScoreboardTag)
            return MobSpawnResult.Spawned(entity, MobDefinition(ResourceId.parse("spectrum:minion")!!, "ZOMBIE"), UUID.randomUUID())
        }

        fun runtime(
            body: String = "", resource: String? = null,
            spawn: (SummonRequest) -> MobSpawnResult = this::spawn,
            capture: (RuntimeValue.OperationResultValue) -> Unit = {},
            extraSkills: Map<String, String> = emptyMap(),
            limits: RuntimeLimits = RuntimeLimits(),
            test: (SkillRuntimeService) -> Unit
        ) {
            val directory = Files.createTempDirectory("spectrum-summon-lifecycle-").toFile()
            try {
                val text = resource?.let { javaClass.getResource("/skills/spectrum/$it.sms")!!.readText() }
                    ?: "skill test {\n$body\n}"
                directory.resolve("test.sms").writeText(text)
                extraSkills.forEach { (id, source) -> directory.resolve("$id.sms").writeText(source) }
                val runtime = SkillRuntimeService(SkillFileRepository(directory), projectileService = ProjectileService(),
                    summonMob = spawn, limits = limits, extendApi = {
                        register("mechanic.test.capture", listOf(FunctionParameter("result", ScriptType.OperationResult)), ScriptType.Unit) { _, args ->
                            capture(args.single().value as RuntimeValue.OperationResultValue)
                            RuntimeValue.UnitValue
                        }
                    })
                val reload = assertIs<SkillReloadResult.Success>(runtime.reload())
                assertTrue(reload.diagnostics.isEmpty(), reload.diagnostics.joinToString())
                try { test(runtime) } finally { runtime.cancelAll("Test cleanup") }
            } finally { directory.deleteRecursively() }
        }
    }
}
