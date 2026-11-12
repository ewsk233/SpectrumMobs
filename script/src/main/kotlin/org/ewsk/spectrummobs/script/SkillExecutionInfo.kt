package org.ewsk.spectrummobs.script

import java.util.UUID

data class SkillExecutionInfo(
    val id: UUID,
    val skillId: String,
    val casterKey: String,
    val worldKey: String?,
    val group: String,
    val state: ExecutionState,
    val startedAtTick: Long,
    val waitTicks: Long,
    val source: String,
    val location: SourceLocation,
    val parentId: UUID?,
    val rootId: UUID,
    val depth: Int,
    val linkedToParent: Boolean,
    val childCount: Int,
    val effectCount: Int,
    val cancellationReason: String?
)
