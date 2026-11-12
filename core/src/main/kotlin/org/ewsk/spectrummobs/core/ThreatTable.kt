package org.ewsk.spectrummobs.core

import java.util.UUID

data class ThreatEntry(
    val targetId: UUID,
    val amount: Double,
    val lastUpdatedTick: Long
)

/** 与服务器 API 无关的威胁表，插件层负责把 UUID 解析为实体。 */
class ThreatTable {
    private val entries = linkedMapOf<UUID, ThreatEntry>()

    fun add(targetId: UUID, amount: Double, tick: Long): ThreatEntry {
        require(amount.isFinite()) { "Threat amount must be finite" }
        val value = (entries[targetId]?.amount ?: 0.0) + amount
        return set(targetId, value, tick)
    }

    fun set(targetId: UUID, amount: Double, tick: Long): ThreatEntry {
        require(amount.isFinite()) { "Threat amount must be finite" }
        val entry = ThreatEntry(targetId, amount.coerceAtLeast(0.0), tick)
        if (entry.amount <= 0.0) entries.remove(targetId) else entries[targetId] = entry
        return entry
    }

    fun remove(targetId: UUID) { entries.remove(targetId) }
    fun clear() { entries.clear() }
    fun amount(targetId: UUID): Double = entries[targetId]?.amount ?: 0.0

    fun decay(factor: Double, tick: Long) {
        require(factor in 0.0..1.0) { "Threat decay factor must be between 0 and 1" }
        entries.keys.toList().forEach { id -> set(id, amount(id) * factor, tick) }
    }

    fun entries(): List<ThreatEntry> = entries.values
        .sortedWith(compareByDescending<ThreatEntry> { it.amount }.thenBy { it.targetId.toString() })

    fun top(): ThreatEntry? = entries().firstOrNull()
}
