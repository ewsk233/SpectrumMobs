package org.ewsk.spectrummobs.runtime

import org.bukkit.entity.Entity
import org.ewsk.spectrummobs.script.RuntimeContext
import java.util.UUID

/** 同名临时标签使用租约计数，取消一个实例不会移除其他实例仍在使用的标签。 */
class TemporaryTagService {
    private data class Key(val entity: UUID, val tag: String)
    private data class Lease(val id: UUID, val expires: Long, val context: RuntimeContext) {
        val effectKey get() = "tag:$id"
    }
    private class Entry(val entity: Entity, var original: Boolean, val leases: MutableMap<UUID, Lease> = linkedMapOf())
    private val entries = linkedMapOf<Key, Entry>()

    fun addPermanent(entity: Entity, tag: String) {
        check(entity.addScoreboardTag(tag) || tag in entity.scoreboardTags) { "Unable to apply tag: $tag" }
        entries[Key(entity.uniqueId, tag)]?.original = true
    }

    fun add(entity: Entity, tag: String, duration: Long, context: RuntimeContext) {
        require(tag.isNotBlank() && tag.length <= 1024) { "Temporary tag must contain between 1 and 1024 characters" }
        require(duration > 0) { "Temporary tag duration must be positive" }
        val key = Key(entity.uniqueId, tag)
        val entry = entries.getOrPut(key) { Entry(entity, tag in entity.scoreboardTags) }
        if (!entity.addScoreboardTag(tag) && tag !in entity.scoreboardTags) {
            if (entry.leases.isEmpty()) entries.remove(key)
            throw IllegalStateException("Unable to apply temporary tag: $tag")
        }
        val lease = Lease(UUID.randomUUID(), context.currentTick + duration.coerceAtMost(Long.MAX_VALUE - context.currentTick), context)
        entry.leases[lease.id] = lease
        context.effects.register(context.executionId, lease.effectKey) { releaseLease(key, lease.id) }
    }

    fun remove(entity: Entity, tag: String) {
        val key = Key(entity.uniqueId, tag)
        entries[key]?.leases?.keys?.toList()?.forEach { release(key, it) }
        entity.removeScoreboardTag(tag)
    }

    fun tick(tick: Long) {
        entries.toMap().forEach { (key, entry) ->
            entry.leases.values.toList().filter { tick >= it.expires || !entry.entity.isValid || entry.entity.isDead }
                .forEach { release(key, it.id) }
        }
    }

    private fun release(key: Key, leaseId: UUID) {
        val lease = entries[key]?.leases?.get(leaseId) ?: return
        if (!lease.context.effects.dispose(lease.context.executionId, lease.effectKey)) releaseLease(key, leaseId)
    }

    private fun releaseLease(key: Key, leaseId: UUID) {
        val entry = entries[key] ?: return
        val lease = entry.leases.remove(leaseId) ?: return
        try {
            if (entry.leases.isEmpty()) {
                entries.remove(key)
                if (!entry.original) entry.entity.removeScoreboardTag(key.tag)
            }
        } finally {
            lease.context.effects.release(lease.context.executionId, lease.effectKey)
        }
    }
}
