package org.ewsk.spectrummobs.mob

import org.bukkit.NamespacedKey
import org.bukkit.entity.Entity
import org.bukkit.persistence.PersistentDataType
import org.ewsk.spectrummobs.core.MobModel
import java.util.UUID

object MobIdentity {
    private val definitionKey = NamespacedKey("spectrummobs", "mob_definition")
    private val instanceKey = NamespacedKey("spectrummobs", "mob_instance")
    private val phaseKey = NamespacedKey("spectrummobs", "mob_phase")
    private val summonOwnerKey = NamespacedKey("spectrummobs", "summon_owner")
    private val summonBatchKey = NamespacedKey("spectrummobs", "summon_batch")
    private val summonExecutionKey = NamespacedKey("spectrummobs", "summon_execution")
    private val summonCleanupKey = NamespacedKey("spectrummobs", "summon_cleanup")
    private val modelProviderKey = NamespacedKey("spectrummobs", "model_provider")
    private val modelIdKey = NamespacedKey("spectrummobs", "model_id")

    fun mark(entity: Entity, definitionId: String, instanceId: UUID) {
        val container = entity.persistentDataContainer
        container.set(definitionKey, PersistentDataType.STRING, definitionId)
        container.set(instanceKey, PersistentDataType.STRING, instanceId.toString())
    }

    fun definitionId(entity: Entity): String? = entity.persistentDataContainer.get(
        definitionKey,
        PersistentDataType.STRING
    )

    fun instanceId(entity: Entity): UUID? = entity.persistentDataContainer.get(
        instanceKey,
        PersistentDataType.STRING
    )?.let { runCatching { UUID.fromString(it) }.getOrNull() }

    fun isManaged(entity: Entity): Boolean = definitionId(entity) != null

    fun markSummonOwner(entity: Entity, ownerId: UUID) {
        entity.persistentDataContainer.set(summonOwnerKey, PersistentDataType.STRING, ownerId.toString())
    }

    fun summonOwner(entity: Entity): UUID? = entity.persistentDataContainer.get(
        summonOwnerKey,
        PersistentDataType.STRING
    )?.let { runCatching { UUID.fromString(it) }.getOrNull() }

    fun markSummonBatch(entity: Entity, batchId: UUID, executionId: UUID) {
        entity.persistentDataContainer.set(summonBatchKey, PersistentDataType.STRING, batchId.toString())
        entity.persistentDataContainer.set(summonExecutionKey, PersistentDataType.STRING, executionId.toString())
    }

    fun summonBatch(entity: Entity): UUID? =
        entity.persistentDataContainer.get(summonBatchKey, PersistentDataType.STRING)
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }

    fun markSummonCleanup(entity: Entity) {
        entity.persistentDataContainer.set(summonCleanupKey, PersistentDataType.BYTE, 1.toByte())
    }

    fun isSummonCleanup(entity: Entity): Boolean =
        entity.persistentDataContainer.get(summonCleanupKey, PersistentDataType.BYTE) == 1.toByte()

    fun setPhaseId(entity: Entity, phaseId: String) {
        entity.persistentDataContainer.set(phaseKey, PersistentDataType.STRING, phaseId)
    }

    fun phaseId(entity: Entity): String? = entity.persistentDataContainer.get(
        phaseKey,
        PersistentDataType.STRING
    )

    fun markModel(entity: Entity, model: MobModel) {
        val container = entity.persistentDataContainer
        container.set(modelProviderKey, PersistentDataType.STRING, model.provider)
        container.set(modelIdKey, PersistentDataType.STRING, model.id)
    }

    fun model(entity: Entity): MobModel? {
        val container = entity.persistentDataContainer
        val provider = container.get(modelProviderKey, PersistentDataType.STRING) ?: return null
        val id = container.get(modelIdKey, PersistentDataType.STRING) ?: return null
        return MobModel(provider, id)
    }
}
