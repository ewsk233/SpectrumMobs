package org.ewsk.spectrummobs.integration

import org.bukkit.entity.Entity
import org.ewsk.spectrummobs.core.MobDefinition
import org.ewsk.spectrummobs.core.MobModel
import org.ewsk.spectrummobs.core.MobPhase
import java.util.concurrent.CopyOnWriteArrayList

data class MobSpawnContext(
    val entity: Entity,
    val definition: MobDefinition
)

data class MobPhaseChangeContext(
    val entity: Entity,
    val definition: MobDefinition,
    val previous: MobPhase,
    val current: MobPhase
)

data class MobDeathContext(
    val entity: Entity,
    val definition: MobDefinition
)

data class MobIntegrationFailure(
    val integrationId: String,
    val error: Throwable
)

interface MobIntegration {
    val id: String

    fun onSpawn(context: MobSpawnContext) {}

    fun onPhaseChange(context: MobPhaseChangeContext) {}

    fun onDeath(context: MobDeathContext) {}
}

/**
 * 模型和外部插件通过这个注册表接入，SpectrumMobs 本体不依赖任何具体插件。
 */
interface MobModelIntegration : MobIntegration {
    fun supports(model: MobModel): Boolean

    fun attach(context: MobSpawnContext, model: MobModel)

    fun detach(context: MobDeathContext, model: MobModel) {}

    override fun onSpawn(context: MobSpawnContext) {
        context.definition.model?.takeIf(::supports)?.let { attach(context, it) }
    }

    override fun onDeath(context: MobDeathContext) {
        context.definition.model?.takeIf(::supports)?.let { detach(context, it) }
    }
}

object MobIntegrationRegistry {
    private val integrations = CopyOnWriteArrayList<MobIntegration>()

    fun register(integration: MobIntegration) {
        require(integration.id.isNotBlank()) { "Integration ID must not be blank" }
        integrations.removeIf { it.id.equals(integration.id, ignoreCase = true) }
        integrations += integration
    }

    fun unregister(id: String): Boolean = integrations.removeIf { it.id.equals(id, ignoreCase = true) }

    fun snapshot(): List<MobIntegration> = integrations.toList()

    fun notifySpawn(context: MobSpawnContext): List<MobIntegrationFailure> =
        dispatch { it.onSpawn(context) }

    fun notifyPhaseChange(context: MobPhaseChangeContext): List<MobIntegrationFailure> =
        dispatch { it.onPhaseChange(context) }

    fun notifyDeath(context: MobDeathContext): List<MobIntegrationFailure> =
        dispatch { it.onDeath(context) }

    private fun dispatch(action: (MobIntegration) -> Unit): List<MobIntegrationFailure> =
        integrations.mapNotNull { integration ->
            runCatching { action(integration) }.exceptionOrNull()?.let {
                MobIntegrationFailure(integration.id, it)
            }
        }
}
