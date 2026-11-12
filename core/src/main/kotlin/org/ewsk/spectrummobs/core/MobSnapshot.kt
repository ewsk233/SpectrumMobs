package org.ewsk.spectrummobs.core

data class MobConfigSnapshot(
    val definitions: Map<ResourceId, MobDefinition>
) {
    fun find(id: String): MobDefinition? = ResourceId.parse(id)?.let(definitions::get)

    companion object {
        fun empty(): MobConfigSnapshot = MobConfigSnapshot(emptyMap())
    }
}

data class MobDefinitionCandidate(
    val source: String,
    val definition: MobDefinition
)

sealed interface MobSnapshotBuildResult {
    data class Success(
        val snapshot: MobConfigSnapshot,
        val diagnostics: List<ConfigurationDiagnostic> = emptyList()
    ) : MobSnapshotBuildResult

    data class Failure(
        val diagnostics: List<ConfigurationDiagnostic>
    ) : MobSnapshotBuildResult
}

object MobSnapshotBuilder {
    fun build(candidates: List<MobDefinitionCandidate>): MobSnapshotBuildResult {
        val diagnostics = mutableListOf<ConfigurationDiagnostic>()
        val definitions = linkedMapOf<ResourceId, MobDefinition>()
        candidates.forEach { candidate ->
            val previous = definitions.putIfAbsent(candidate.definition.id, candidate.definition)
            if (previous != null) {
                diagnostics += ConfigurationDiagnostic(
                    source = candidate.source,
                    path = "id",
                    message = "Resource ID ${candidate.definition.id} is defined more than once"
                )
            }
        }
        return if (diagnostics.any { it.level == DiagnosticLevel.ERROR }) {
            MobSnapshotBuildResult.Failure(diagnostics)
        } else {
            MobSnapshotBuildResult.Success(MobConfigSnapshot(definitions), diagnostics)
        }
    }
}
