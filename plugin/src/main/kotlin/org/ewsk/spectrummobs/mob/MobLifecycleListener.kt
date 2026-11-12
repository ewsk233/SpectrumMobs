package org.ewsk.spectrummobs.mob

import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.event.world.WorldUnloadEvent
import org.ewsk.spectrummobs.SpectrumMobs
import org.ewsk.spectrummobs.integration.MobSpawnContext
import taboolib.common.platform.event.OptionalEvent
import taboolib.common.platform.event.SubscribeEvent

object MobLifecycleListener {
    @SubscribeEvent
    fun onChunkLoad(event: ChunkLoadEvent) {
        event.chunk.entities
            .filter { MobIdentity.isManaged(it) }
            .forEach {
                SpectrumMobs.mobService.findDefinition(it)?.let { definition ->
                    SpectrumMobs.visuals.reattach(MobSpawnContext(it, definition))
                }
                SpectrumMobs.mobMovement.register(it)
                SpectrumMobs.mobIntervals.register(it, SpectrumMobs.currentRuntimeTick())
                SpectrumMobs.mobBossBars.register(it, SpectrumMobs.currentRuntimeTick())
            }
    }

    @SubscribeEvent
    fun onEntityDeath(event: EntityDeathEvent) {
        SpectrumMobs.mobService.cleanupSummons(event.entity)
        SpectrumMobs.mobIntervals.unregister(event.entity)
        SpectrumMobs.mobBossBars.unregister(event.entity)
        SpectrumMobs.mobThreats.unregister(event.entity)
        SpectrumMobs.mobMovement.unregister(event.entity)
        SpectrumMobs.visuals.detachOwner(event.entity.uniqueId)
        SpectrumMobs.projectiles.removeByOwner(event.entity.uniqueId)
        SpectrumMobs.skillRuntime.clearBossBarsForSource(event.entity.uniqueId)
        if (SpectrumMobs.skillRuntime.lifecyclePolicy.cancelOnCasterDeath) {
            SpectrumMobs.skillRuntime.cancelByCaster(event.entity, "Caster died")
        }
    }

    @SubscribeEvent
    fun onWorldUnload(event: WorldUnloadEvent) {
        SpectrumMobs.mobService.cleanupSummonsInWorld(event.world.uid)
        SpectrumMobs.mobIntervals.unregisterByWorld(event.world.uid.toString())
        SpectrumMobs.mobBossBars.unregisterByWorld(event.world.uid.toString())
        SpectrumMobs.mobThreats.unregisterByWorld(event.world.uid)
        SpectrumMobs.mobMovement.unregisterByWorld(event.world.uid)
        SpectrumMobs.visuals.detachWorld(event.world.uid)
        SpectrumMobs.projectiles.removeByWorld(event.world.uid)
        if (SpectrumMobs.skillRuntime.lifecyclePolicy.cancelOnWorldUnload) {
            SpectrumMobs.skillRuntime.cancelByWorld(event.world.uid.toString(), "World unloaded")
        }
    }

    @SubscribeEvent(bind = "org.bukkit.event.entity.EntityRemoveFromWorldEvent")
    fun onEntityRemoved(event: OptionalEvent) {
        val entity = event.read<org.bukkit.entity.Entity>("entity") ?: return
        SpectrumMobs.mobService.cleanupSummons(entity)
        SpectrumMobs.mobIntervals.unregister(entity)
        SpectrumMobs.mobBossBars.unregister(entity)
        SpectrumMobs.mobThreats.unregister(entity)
        SpectrumMobs.mobMovement.unregister(entity)
        SpectrumMobs.visuals.detachOwner(entity.uniqueId)
        SpectrumMobs.projectiles.removeByOwner(entity.uniqueId)
        SpectrumMobs.skillRuntime.clearBossBarsForSource(entity.uniqueId)
        if (!SpectrumMobs.skillRuntime.lifecyclePolicy.cancelOnCasterDespawn) return
        SpectrumMobs.skillRuntime.cancelByCaster(entity, "Caster removed from the world")
    }
}
