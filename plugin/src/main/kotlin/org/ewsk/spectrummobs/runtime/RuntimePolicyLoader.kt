package org.ewsk.spectrummobs.runtime

import org.ewsk.spectrummobs.script.RuntimeLimits
import taboolib.module.configuration.Configuration
import java.io.File

data class RuntimeDebugPolicy(
    val traceSkills: Boolean = false
)

object RuntimePolicyLoader {
    fun load(file: File): RuntimeLifecyclePolicy {
        if (!file.exists()) return RuntimeLifecyclePolicy()
        return runCatching {
            val configuration = Configuration.loadFromFile(file)
            RuntimeLifecyclePolicy(
                cancelOnCasterDeath = configuration.getBoolean("engine.cancel-on-caster-death", true),
                cancelOnCasterDespawn = configuration.getBoolean("engine.cancel-on-caster-despawn", true),
                cancelOnWorldUnload = configuration.getBoolean("engine.cancel-on-world-unload", true)
            )
        }.getOrDefault(RuntimeLifecyclePolicy())
    }

    fun loadLimits(file: File): RuntimeLimits {
        if (!file.exists()) return RuntimeLimits()
        return runCatching {
            val configuration = Configuration.loadFromFile(file)
            RuntimeLimits(
                maxStepsPerResume = configuration.getInt("engine.max-steps-per-resume", 256),
                maxActiveExecutions = configuration.getInt("engine.max-active-executions", 2048),
                maxCallDepth = configuration.getInt("engine.max-call-depth", 16),
                maxLoopIterations = configuration.getInt("engine.max-loop-iterations", 1000),
                maxStateEntries = configuration.getInt("engine.max-state-entries", 128),
                maxQueryTargets = configuration.getInt("engine.max-query-targets", 64),
                maxActiveProjectiles = configuration.getInt("engine.max-active-projectiles", 2048)
            )
        }.getOrDefault(RuntimeLimits())
    }

    fun loadDebug(file: File): RuntimeDebugPolicy {
        if (!file.exists()) return RuntimeDebugPolicy()
        return runCatching {
            val configuration = Configuration.loadFromFile(file)
            RuntimeDebugPolicy(
                traceSkills = configuration.getBoolean("debug.trace-skills", false)
            )
        }.getOrDefault(RuntimeDebugPolicy())
    }
}
