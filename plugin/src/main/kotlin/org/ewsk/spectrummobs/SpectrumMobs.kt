package org.ewsk.spectrummobs

import org.ewsk.spectrummobs.config.MobConfigReloadResult
import org.ewsk.spectrummobs.config.MobConfigRepository
import org.ewsk.spectrummobs.runtime.SkillRuntimeService
import org.ewsk.spectrummobs.runtime.ProjectileService
import org.ewsk.spectrummobs.skill.SkillFileRepository
import org.ewsk.spectrummobs.skill.SkillReloadResult
import org.ewsk.spectrummobs.mob.MobService
import org.ewsk.spectrummobs.mob.MobSpawnResult
import org.ewsk.spectrummobs.mob.MobTriggerRouter
import org.ewsk.spectrummobs.mob.MobIntervalService
import org.ewsk.spectrummobs.mob.MobBossBarService
import org.ewsk.spectrummobs.mob.MobThreatService
import org.ewsk.spectrummobs.mob.MobMovementService
import org.ewsk.spectrummobs.runtime.RuntimeLifecyclePolicy
import org.ewsk.spectrummobs.runtime.RuntimePolicyLoader
import org.ewsk.spectrummobs.command.CommandPolicy
import org.ewsk.spectrummobs.command.CommandPolicyLoader
import org.ewsk.spectrummobs.integration.MobIntegrationFailure
import org.ewsk.spectrummobs.integration.MobIntegrationRegistry
import org.ewsk.spectrummobs.i18n.SpectrumMobsI18n
import org.ewsk.spectrummobs.script.ScriptApiBindings
import org.ewsk.spectrummobs.script.RuntimeValue
import taboolib.common.platform.Platform
import taboolib.common.platform.Plugin
import taboolib.common.platform.function.getDataFolder
import taboolib.common.platform.function.pluginVersion
import taboolib.common.platform.function.submit
import taboolib.common.platform.function.releaseResourceFolder
import taboolib.common.platform.service.PlatformExecutor
import taboolib.module.metrics.Metrics
import java.io.File
import org.ewsk.spectrummobs.visual.DisplayVisualService

object SpectrumMobs : Plugin() {
    lateinit var mobConfigs: MobConfigRepository
        private set
    lateinit var skillRuntime: SkillRuntimeService
        private set
    lateinit var projectiles: ProjectileService
        private set
    lateinit var mobService: MobService
        private set
    lateinit var mobIntervals: MobIntervalService
        private set
    lateinit var mobBossBars: MobBossBarService
        private set
    lateinit var mobTriggers: MobTriggerRouter
        private set
    lateinit var mobThreats: MobThreatService
        private set
    lateinit var mobMovement: MobMovementService
        private set
    lateinit var visuals: DisplayVisualService
        private set
    lateinit var lifecyclePolicy: RuntimeLifecyclePolicy
        private set
    lateinit var commandPolicy: CommandPolicy
        private set
    private var runtimeTask: PlatformExecutor.PlatformTask? = null
    private var runtimeTick: Long = 0
    private val scriptApiExtensions = mutableListOf<ScriptApiBindings.Builder.() -> Unit>()

    fun currentRuntimeTick(): Long = runtimeTick

    /** 扩展插件在主线程注册；启用后追加 API 需要 reload 才会重新编译现有脚本。 */
    fun registerScriptApi(configure: ScriptApiBindings.Builder.() -> Unit) {
        if (::skillRuntime.isInitialized) {
            skillRuntime.registerApi(configure)
        } else {
            scriptApiExtensions += configure
        }
    }

    override fun onEnable() {
        releaseResourceFolder("mobs")
        releaseResourceFolder("skills")
        val dataFolder = getDataFolder()
        ensureResource("config.yml", File(dataFolder, "config.yml"))
        SpectrumMobsI18n.configure(File(dataFolder, "config.yml"))
        lifecyclePolicy = RuntimePolicyLoader.load(File(dataFolder, "config.yml"))
        val runtimeLimits = RuntimePolicyLoader.loadLimits(File(dataFolder, "config.yml"))
        val debugPolicy = RuntimePolicyLoader.loadDebug(File(dataFolder, "config.yml"))
        val commandPolicyResult = CommandPolicyLoader.load(File(dataFolder, "config.yml"))
        commandPolicy = commandPolicyResult.policy
        commandPolicyResult.diagnostics.forEach { SpectrumMobsI18n.log("error-command-policy-diagnostic", it) }
        if (commandPolicy.allowRaw) {
            SpectrumMobsI18n.log("warning-raw-command-enabled")
        }
        projectiles = ProjectileService(runtimeLimits.maxActiveProjectiles)
        projectiles.cleanupOrphans()
        mobConfigs = MobConfigRepository(File(dataFolder, "mobs"))
        mobThreats = MobThreatService()
        mobMovement = MobMovementService(mobConfigs) { owner -> mobThreats.top(owner) }
        visuals = DisplayVisualService()
        visuals.cleanupOrphans(org.bukkit.Bukkit.getWorlds().flatMap { it.entities })
        MobIntegrationRegistry.register(visuals)
        skillRuntime = SkillRuntimeService(
            SkillFileRepository(File(dataFolder, "skills")),
            limits = runtimeLimits,
            lifecyclePolicy = lifecyclePolicy,
            commandPolicy = commandPolicy,
            traceSkills = debugPolicy.traceSkills,
            projectileService = projectiles,
            summonMob = { request ->
                mobService.summon(request.owner, request.id, request.location, request.batchId, request.executionId, request.tag)
            },
            threatTop = ::selectThreatTarget,
            threatList = { owner -> mobThreats.entries(owner).map { it.first } },
            threatAmount = mobThreats::amount,
            threatAdd = { owner, target, amount -> mobThreats.add(owner, target, amount, runtimeTick) },
            threatSet = { owner, target, amount -> mobThreats.set(owner, target, amount, runtimeTick) },
            threatRemove = mobThreats::remove,
            threatClear = mobThreats::clear,
            extendApi = {
                scriptApiExtensions.forEach { it.invoke(this) }
            }
        )
        mobTriggers = MobTriggerRouter(mobConfigs, skillRuntime, debugPolicy.traceSkills)
        mobIntervals = MobIntervalService(mobConfigs, mobTriggers)
        mobBossBars = MobBossBarService(mobConfigs)
        mobService = MobService(mobConfigs) { entity ->
            mobMovement.register(entity)
            mobIntervals.register(entity, runtimeTick)
            mobBossBars.register(entity, runtimeTick)
            mobTriggers.trigger("spawn", entity)
        }
        when (val result = skillRuntime.reload()) {
            is SkillReloadResult.Success -> {
                SpectrumMobsI18n.log("info-skills-loaded", result.snapshot.skills.size)
                result.diagnostics.forEach(SpectrumMobsI18n::logScriptDiagnostic)
            }
            is SkillReloadResult.Failure -> {
                SpectrumMobsI18n.log("error-skills-load-failed")
                result.diagnostics.forEach(SpectrumMobsI18n::logScriptDiagnostic)
            }
        }
        when (val result = mobConfigs.reload(skillRuntime::validateMobDefinition)) {
            is MobConfigReloadResult.Success -> {
                SpectrumMobsI18n.log("info-mobs-loaded", result.snapshot.definitions.size)
                result.diagnostics.forEach(SpectrumMobsI18n::logConfigurationDiagnostic)
            }
            is MobConfigReloadResult.Failure -> {
                SpectrumMobsI18n.log("error-mobs-load-failed")
                result.diagnostics.forEach(SpectrumMobsI18n::logConfigurationDiagnostic)
            }
        }
        org.bukkit.Bukkit.getWorlds().flatMap { it.entities }.forEach(skillRuntime.summons::cleanupOrphan)
        mobIntervals.registerLoaded(runtimeTick)
        mobBossBars.registerLoaded(runtimeTick)
        mobMovement.registerLoaded()
        runtimeTask = submit(period = 1) {
            runtimeTick++
            projectiles.tick(runtimeTick)
            mobThreats.decay(runtimeTick)
            mobMovement.tick(runtimeTick)
            visuals.tick(runtimeTick)
            mobIntervals.tick(runtimeTick)
            mobBossBars.tick(runtimeTick)
            skillRuntime.tick(runtimeTick).forEach { event ->
                if (event is org.ewsk.spectrummobs.script.RuntimeEvent.Failed) {
                    SpectrumMobsI18n.log("error-runtime-execution", event.executionId, event.message)
                }
                if (event is org.ewsk.spectrummobs.script.RuntimeEvent.EffectCleanupFailed) {
                    SpectrumMobsI18n.log("error-runtime-effect-cleanup", event.executionId, event.effectKey, event.message)
                }
            }
        }
        Metrics(35234, pluginVersion, Platform.BUKKIT)
        SpectrumMobsI18n.log("info-plugin-enabled")
    }

    override fun onDisable() {
        runtimeTask?.cancel()
        if (::projectiles.isInitialized) projectiles.clear()
        if (::mobThreats.isInitialized) mobThreats.clearAll()
        if (::mobMovement.isInitialized) mobMovement.clear()
        if (::visuals.isInitialized) {
            visuals.clear()
            MobIntegrationRegistry.unregister(visuals.id)
        }
        if (::mobBossBars.isInitialized) mobBossBars.clear()
        if (::skillRuntime.isInitialized) {
            skillRuntime.clearBossBars()
            skillRuntime.cancelAll("Plugin disabled")
        }
        SpectrumMobsI18n.log("info-plugin-disabled")
    }

    fun reportIntegrationFailures(failures: List<MobIntegrationFailure>) {
        failures.forEach { failure ->
            SpectrumMobsI18n.log(
                "error-integration-failed",
                failure.integrationId,
                failure.error.message ?: failure.error.javaClass.simpleName
            )
        }
    }

    /** 先读取纯 UUID 威胁表，再按怪物 YAML 的 target-rules.threat 交给 Typed fn 选择。 */
    private fun selectThreatTarget(owner: org.bukkit.entity.Entity): org.bukkit.entity.Entity? {
        val candidates = mobThreats.entries(owner).map { it.first }
        if (candidates.isEmpty()) return null
        val definition = mobConfigs.current().find(org.ewsk.spectrummobs.mob.MobIdentity.definitionId(owner) ?: return candidates.first())
        val rule = definition?.targetRules?.get("threat") ?: return candidates.first()
        val candidateValues = RuntimeValue.ListValue(candidates.map(skillRuntime::runtimeEntity))
        val variables = mapOf(
            "caster" to skillRuntime.runtimeEntity(owner),
            "target" to skillRuntime.runtimeEntity(candidates.first())
        )
        val result = runCatching {
            skillRuntime.evaluateRule(rule.toString(), variables, mapOf("candidates" to candidateValues))
                ?: throw IllegalArgumentException("Rule not found: $rule")
        }.onFailure {
            SpectrumMobsI18n.log("error-rule-evaluation", rule, it.message ?: it.javaClass.simpleName)
        }.getOrNull()
        val selected = (result as? RuntimeValue.EntityValue)?.origin as? org.bukkit.entity.Entity
        return selected?.takeIf { entity -> candidates.any { it.uniqueId == entity.uniqueId } }
            ?: candidates.first()
    }

    private fun ensureResource(path: String, target: File) {
        if (target.exists()) return
        target.parentFile.mkdirs()
        SpectrumMobs::class.java.classLoader.getResourceAsStream(path)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
    }

}
