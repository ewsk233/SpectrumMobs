package org.ewsk.spectrummobs.script

enum class CastingConcurrency { PARALLEL, REJECT, REPLACE }

data class SkillCastingPolicy(
    val group: String = "default",
    val concurrency: CastingConcurrency = CastingConcurrency.PARALLEL,
    val cancelOnPhaseChange: Boolean = true,
    val cleanupOnCancel: Boolean = true
)
