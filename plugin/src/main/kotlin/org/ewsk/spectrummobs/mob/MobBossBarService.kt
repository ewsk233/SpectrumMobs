package org.ewsk.spectrummobs.mob

import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.boss.BarColor
import org.bukkit.boss.BarStyle
import org.bukkit.boss.BossBar
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.ewsk.spectrummobs.config.MobConfigRepository
import org.ewsk.spectrummobs.core.MobBossBarDefinition
import org.ewsk.spectrummobs.core.MobDefinition
import java.util.UUID

/** 管理 YAML 声明的持久 BossBar；mechanic.bossbar.show 用于技能临时反馈。 */
class MobBossBarService(
    private val repository: MobConfigRepository
) {
    private data class Entry(
        val entityId: UUID,
        val worldId: UUID,
        val bar: BossBar,
        var nextUpdateTick: Long
    )

    private val entries = linkedMapOf<UUID, Entry>()

    fun register(entity: Entity, currentTick: Long) {
        unregister(entity)
        val definition = MobIdentity.definitionId(entity)?.let(repository.current()::find) ?: return
        val config = definition.bossBar?.takeIf { it.enabled } ?: return
        val entry = Entry(
            entityId = entity.uniqueId,
            worldId = entity.world.uid,
            bar = Bukkit.createBossBar(
                legacy(config.title),
                color(config.color),
                style(config.style)
            ),
            nextUpdateTick = currentTick
        )
        entries[entity.uniqueId] = entry
        update(entry, entity, definition, config, currentTick)
    }

    fun registerLoaded(currentTick: Long) {
        Bukkit.getWorlds().forEach { world ->
            world.entities.filter { MobIdentity.isManaged(it) }.forEach { register(it, currentTick) }
        }
    }

    fun unregister(entity: Entity) {
        entries.remove(entity.uniqueId)?.bar?.removeAll()
    }

    fun unregisterByWorld(worldKey: String) {
        entries.values
            .filter { it.worldId.toString() == worldKey }
            .map { it.entityId }
            .forEach { entries.remove(it)?.bar?.removeAll() }
    }

    fun tick(currentTick: Long) {
        entries.values.toList().forEach { entry ->
            val entity = Bukkit.getEntity(entry.entityId)
            val definition = entity?.takeIf { it.isValid }?.let { MobIdentity.definitionId(it)?.let(repository.current()::find) }
            val config = definition?.bossBar?.takeIf { it.enabled }
            if (entity == null || definition == null || config == null) {
                entries.remove(entry.entityId)?.bar?.removeAll()
                return@forEach
            }
            if (currentTick >= entry.nextUpdateTick) {
                update(entry, entity, definition, config, currentTick)
            }
        }
    }

    fun clear() {
        entries.values.forEach { it.bar.removeAll() }
        entries.clear()
    }

    private fun update(
        entry: Entry,
        entity: Entity,
        definition: MobDefinition,
        config: MobBossBarDefinition,
        currentTick: Long
    ) {
        val phaseTitle = MobIdentity.phaseId(entity)
            ?.let { phaseId -> definition.boss?.phases?.firstOrNull { it.id == phaseId }?.bossbarTitle }
        entry.bar.setTitle(legacy(phaseTitle ?: config.title))
        entry.bar.color = color(config.color)
        entry.bar.style = style(config.style)
        entry.bar.progress = healthProgress(entity)

        val rangeSquared = config.range * config.range
        val location = entity.location
        val nearby = entity.world.getNearbyEntities(location, config.range, config.range, config.range)
            .filterIsInstance<Player>()
            .filter { it.location.distanceSquared(location) <= rangeSquared }
            .associateBy { it.uniqueId }
        entry.bar.players.toList()
            .filterNot { it.uniqueId in nearby }
            .forEach(entry.bar::removePlayer)
        nearby.values.forEach(entry.bar::addPlayer)
        entry.nextUpdateTick = currentTick + config.updateIntervalTicks
    }

    private fun healthProgress(entity: Entity): Double {
        val living = entity as? LivingEntity ?: return 0.0
        val maximum = living.getAttribute(Attribute.GENERIC_MAX_HEALTH)?.value ?: living.maxHealth
        if (maximum <= 0.0) return 0.0
        return (living.health / maximum).coerceIn(0.0, 1.0)
    }

    private fun color(raw: String): BarColor = runCatching {
        BarColor.valueOf(raw.trim().uppercase())
    }.getOrDefault(BarColor.RED)

    private fun style(raw: String): BarStyle = runCatching {
        BarStyle.valueOf(raw.trim().uppercase())
    }.getOrDefault(BarStyle.SOLID)

    private fun legacy(text: String): String = ChatColor.translateAlternateColorCodes('&', text)
}
