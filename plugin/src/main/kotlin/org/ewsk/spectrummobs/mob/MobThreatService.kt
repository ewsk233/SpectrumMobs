package org.ewsk.spectrummobs.mob

import org.bukkit.Bukkit
import org.bukkit.entity.Entity
import org.ewsk.spectrummobs.core.ThreatEntry
import org.ewsk.spectrummobs.core.ThreatTable
import java.util.UUID

class MobThreatService {
    private val tables = linkedMapOf<UUID, ThreatTable>()
    private val ownerWorlds = linkedMapOf<UUID, UUID>()

    fun table(owner: Entity): ThreatTable {
        ownerWorlds[owner.uniqueId] = owner.world.uid
        return tables.getOrPut(owner.uniqueId) { ThreatTable() }
    }

    fun add(owner: Entity, target: Entity, amount: Double, tick: Long) {
        if (target.uniqueId == owner.uniqueId) return
        table(owner).add(target.uniqueId, amount, tick)
    }

    fun set(owner: Entity, target: Entity, amount: Double, tick: Long) {
        if (target.uniqueId == owner.uniqueId) return
        table(owner).set(target.uniqueId, amount, tick)
    }
    fun remove(owner: Entity, target: Entity) { tables[owner.uniqueId]?.remove(target.uniqueId) }
    fun clear(owner: Entity) { tables[owner.uniqueId]?.clear() }
    fun amount(owner: Entity, target: Entity): Double = tables[owner.uniqueId]?.amount(target.uniqueId) ?: 0.0

    fun top(owner: Entity): Entity? = resolveValid(owner, table(owner).top())

    fun entries(owner: Entity): List<Pair<Entity, ThreatEntry>> = table(owner).entries().mapNotNull { entry ->
        resolveValid(owner, entry)?.let { it to entry }
    }

    fun decay(currentTick: Long, factor: Double = 0.995) {
        tables.values.forEach { it.decay(factor, currentTick) }
    }

    fun unregister(owner: Entity) {
        tables.remove(owner.uniqueId)
        ownerWorlds.remove(owner.uniqueId)
    }
    fun clearAll() {
        tables.clear()
        ownerWorlds.clear()
    }
    fun unregisterByWorld(worldId: UUID) {
        val removed = ownerWorlds.filterValues { it == worldId }.keys
        removed.forEach {
            tables.remove(it)
            ownerWorlds.remove(it)
        }
    }

    private fun resolveValid(owner: Entity, entry: ThreatEntry?): Entity? {
        val target = entry?.targetId?.let(Bukkit::getEntity)
        return target?.takeIf { it.isValid && !it.isDead && it.world.uid == owner.world.uid && (it !is org.bukkit.entity.Player || it.isOnline) }
    }
}
