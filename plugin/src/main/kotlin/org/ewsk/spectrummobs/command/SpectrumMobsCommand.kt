package org.ewsk.spectrummobs.command

import org.bukkit.entity.Player
import org.ewsk.spectrummobs.SpectrumMobs
import org.ewsk.spectrummobs.config.MobConfigReloadResult
import org.ewsk.spectrummobs.core.DiagnosticLevel
import org.ewsk.spectrummobs.runtime.SkillCastResult
import org.ewsk.spectrummobs.skill.SkillReloadResult
import org.ewsk.spectrummobs.mob.MobSpawnResult
import org.ewsk.spectrummobs.mob.MobKillResult
import org.ewsk.spectrummobs.i18n.SpectrumMobsI18n
import taboolib.common.platform.ProxyCommandSender
import taboolib.common.platform.command.CommandBody
import taboolib.common.platform.command.CommandHeader
import taboolib.common.platform.command.PermissionDefault
import taboolib.common.platform.command.subCommand
import java.util.UUID

@CommandHeader(name = "spectrummobs", permission = "spectrummobs.admin",permissionDefault = PermissionDefault.OP)
object SpectrumMobsCommand {
    @CommandBody
    val reload = subCommand {
        execute<ProxyCommandSender> { sender, _, _ ->
            reload(sender)
        }
    }
    @CommandBody
    val skill = subCommand {
        literal("list"){
            execute<ProxyCommandSender> { sender, _, _ ->
                list(sender)
            }
        }
        literal("cast"){
            dynamic("id"){
                suggestionUncheck<ProxyCommandSender> { _, _ ->
                    SpectrumMobs.skillRuntime.skillIds()
                }
                execute<ProxyCommandSender> { sender, context, _ ->
                    cast(sender, context["id"], emptyMap())
                }
                dynamic("arg1") {
                    execute<ProxyCommandSender> { sender, context, _ -> cast(sender, context["id"], parseSkillArguments(context)) }
                    dynamic("arg2") {
                        execute<ProxyCommandSender> { sender, context, _ -> cast(sender, context["id"], parseSkillArguments(context)) }
                        dynamic("arg3") {
                            execute<ProxyCommandSender> { sender, context, _ -> cast(sender, context["id"], parseSkillArguments(context)) }
                            dynamic("arg4") {
                                execute<ProxyCommandSender> { sender, context, _ -> cast(sender, context["id"], parseSkillArguments(context)) }
                                dynamic("arg5") {
                                    execute<ProxyCommandSender> { sender, context, _ -> cast(sender, context["id"], parseSkillArguments(context)) }
                                    dynamic("arg6") {
                                        execute<ProxyCommandSender> { sender, context, _ -> cast(sender, context["id"], parseSkillArguments(context)) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        literal("active") {
            execute<ProxyCommandSender> { sender, _, _ -> active(sender, 1) }
            dynamic("page") {
                execute<ProxyCommandSender> { sender, context, _ -> active(sender, context["page"].toIntOrNull() ?: 0) }
            }
        }
        literal("cancel") {
            dynamic("uuid") {
                suggestionUncheck<ProxyCommandSender> { _, _ -> SpectrumMobs.skillRuntime.activeSkills().map { it.id.toString() } }
                execute<ProxyCommandSender> { sender, context, _ -> cancelSkill(sender, context["uuid"]) }
            }
        }

    }

    @CommandBody
    val mob = subCommand {
        literal("list") {
            execute<ProxyCommandSender> { sender, _, _ ->
                val ids = SpectrumMobs.mobConfigs.current().definitions.keys.map { it.toString() }.sorted()
                SpectrumMobsI18n.send(
                    sender,
                    "command-mob-list",
                    if (ids.isEmpty()) SpectrumMobsI18n.text(sender, "command-empty-list") else ids.joinToString(", ")
                )
            }
        }
        literal("spawn") {
            dynamic("id") {
                suggestionUncheck<ProxyCommandSender> { _, _ ->
                    SpectrumMobs.mobConfigs.current().definitions.keys.map { it.toString() }
                }
                execute<ProxyCommandSender> { sender, context, _ ->
                    spawn(sender, context["id"])
                }
            }
        }
        literal("kill") {
            dynamic("name") {
                suggestionUncheck<ProxyCommandSender> { _, _ ->
                    SpectrumMobs.mobConfigs.current().definitions.keys.map { it.toString() }
                }
                dynamic("uuid") {
                    execute<ProxyCommandSender> { sender, context, _ ->
                        kill(sender, context["name"], context["uuid"])
                    }
                }
            }
        }
        literal("killall") {
            execute<ProxyCommandSender> { sender, _, _ ->
                killAll(sender)
            }
        }
    }
    private fun reload(sender: ProxyCommandSender) {
        val skillResult = SpectrumMobs.skillRuntime.reload()
        val mobResult = SpectrumMobs.mobConfigs.reload(SpectrumMobs.skillRuntime::validateMobDefinition)
        val mobMessage = when (mobResult) {
            is MobConfigReloadResult.Success -> SpectrumMobsI18n.text(
                sender,
                if (mobResult.diagnostics.any { it.level == DiagnosticLevel.ERROR }) {
                    "command-reload-mob-partial"
                } else {
                    "command-reload-mob-success"
                },
                mobResult.snapshot.definitions.size,
                mobResult.diagnostics
                    .filter { it.level == DiagnosticLevel.ERROR }
                    .map { it.source }
                    .distinct()
                    .size
            )
            is MobConfigReloadResult.Failure -> SpectrumMobsI18n.text(
                sender,
                "command-reload-mob-failure",
                mobResult.diagnostics.size
            )
        }
        val skillMessage = when (skillResult) {
            is SkillReloadResult.Success -> SpectrumMobsI18n.text(
                sender,
                if (skillResult.diagnostics.isNotEmpty()) {
                    "command-reload-skill-partial"
                } else {
                    "command-reload-skill-success"
                },
                skillResult.snapshot.skills.size,
                skillResult.diagnostics.map { it.source }.distinct().size
            )
            is SkillReloadResult.Failure -> SpectrumMobsI18n.text(
                sender,
                "command-reload-skill-failure",
                skillResult.diagnostics.size
            )
        }
        SpectrumMobsI18n.send(sender, "command-reload-success", mobMessage, skillMessage)
        when (mobResult) {
            is MobConfigReloadResult.Success -> mobResult.diagnostics
                .forEach { SpectrumMobsI18n.sendConfigurationDiagnostic(sender, it) }
            is MobConfigReloadResult.Failure -> mobResult.diagnostics
                .forEach { SpectrumMobsI18n.sendConfigurationDiagnostic(sender, it) }
        }
        when (skillResult) {
            is SkillReloadResult.Success -> skillResult.diagnostics
                .forEach { SpectrumMobsI18n.sendScriptDiagnostic(sender, it) }
            is SkillReloadResult.Failure -> skillResult.diagnostics
                .forEach { SpectrumMobsI18n.sendScriptDiagnostic(sender, it) }
        }
    }

    private fun list(sender: ProxyCommandSender) {
        val ids = SpectrumMobs.skillRuntime.skillIds()
        SpectrumMobsI18n.send(
            sender,
            "command-skill-list",
            if (ids.isEmpty()) SpectrumMobsI18n.text(sender, "command-empty-list") else ids.joinToString(", ")
        )
    }

    private fun cast(sender: ProxyCommandSender, id: String, arguments: Map<String, org.ewsk.spectrummobs.script.RuntimeValue>) {
        val player = runCatching { sender.cast<Player>() }.getOrNull()
        if (player == null) {
            SpectrumMobsI18n.send(sender, "command-player-only-cast")
            return
        }
        when (val result = SpectrumMobs.skillRuntime.cast(id, player, arguments = arguments)) {
            is SkillCastResult.Started -> SpectrumMobsI18n.send(sender, "command-skill-started", id, result.executionId)
            is SkillCastResult.Missing -> SpectrumMobsI18n.send(sender, "command-skill-missing", result.id)
            is SkillCastResult.Rejected -> SpectrumMobsI18n.send(sender, "command-skill-rejected", result.reason)
        }
    }

    private fun parseSkillArguments(context: taboolib.common.platform.command.CommandContext<ProxyCommandSender>): Map<String, org.ewsk.spectrummobs.script.RuntimeValue> =
        listOf("arg1", "arg2", "arg3", "arg4", "arg5", "arg6").mapNotNull { context.getOrNull(it) }.flatMap { it.split(Regex("\\s+")) }.filter { it.isNotBlank() }.mapNotNull { raw ->
                val separator = raw.indexOf('=')
                if (separator <= 0) return@mapNotNull null
                raw.substring(0, separator).trim() to parseSkillArgumentValue(raw.substring(separator + 1).trim())
            }
            .toMap()

    private fun parseSkillArgumentValue(raw: String): org.ewsk.spectrummobs.script.RuntimeValue = when {
        raw.equals("true", true) -> org.ewsk.spectrummobs.script.RuntimeValue.BooleanValue(true)
        raw.equals("false", true) -> org.ewsk.spectrummobs.script.RuntimeValue.BooleanValue(false)
        raw.matches(Regex("[0-9]+(?:\\.[0-9]+)?%")) -> org.ewsk.spectrummobs.script.RuntimeValue.PercentageValue(raw.dropLast(1).toDouble())
        raw.matches(Regex("[0-9]+(?:\\.[0-9]+)?[tsm]")) -> {
            val amount = raw.dropLast(1).toDouble()
            val ticks = amount * when (raw.last()) { 't' -> 1.0; 's' -> 20.0; else -> 1200.0 }
            org.ewsk.spectrummobs.script.RuntimeValue.DurationValue(ticks.toLong())
        }
        raw.toDoubleOrNull() != null -> org.ewsk.spectrummobs.script.RuntimeValue.NumberValue(raw.toDouble())
        else -> org.ewsk.spectrummobs.script.RuntimeValue.TextValue(raw.removeSurrounding("\""))
    }

    private fun active(sender: ProxyCommandSender, page: Int) {
        val entries = SpectrumMobs.skillRuntime.activeSkills()
        val pages = ((entries.size + 4) / 5).coerceAtLeast(1)
        if (page !in 1..pages) {
            SpectrumMobsI18n.send(sender, "command-skill-active-page-error", pages)
            return
        }
        SpectrumMobsI18n.send(sender, "command-skill-active-header", page, pages, entries.size)
        if (entries.isEmpty()) SpectrumMobsI18n.send(sender, "command-skill-active-empty")
        entries.drop((page - 1) * 5).take(5).forEach { entry ->
            SpectrumMobsI18n.send(sender, "command-skill-active-entry", entry.id, entry.skillId, entry.casterKey, entry.group,
                SpectrumMobsI18n.text(sender, "skill-state-${entry.state.name.lowercase()}"), entry.waitTicks,
                entry.source, entry.location.line, entry.location.column, entry.parentId ?: "-", entry.depth,
                entry.childCount, entry.effectCount, entry.cancellationReason ?: "-", entry.rootId)
        }
    }

    private fun cancelSkill(sender: ProxyCommandSender, rawId: String) {
        val id = runCatching { UUID.fromString(rawId) }.getOrNull()
        if (id == null) {
            SpectrumMobsI18n.send(sender, "command-skill-invalid-execution", rawId)
            return
        }
        val reason = "Cancelled by command"
        val count = SpectrumMobs.skillRuntime.cancelExecution(id, reason)
        if (count == 0) SpectrumMobsI18n.send(sender, "command-skill-execution-missing", id)
        else SpectrumMobsI18n.send(sender, "command-skill-cancelled", id, count, reason)
    }

    private fun spawn(sender: ProxyCommandSender, id: String) {
        val player = runCatching { sender.cast<Player>() }.getOrNull()
        if (player == null) {
            SpectrumMobsI18n.send(sender, "command-player-only-spawn")
            return
        }
        when (val result = SpectrumMobs.mobService.spawn(id, player.location)) {
            is MobSpawnResult.Spawned -> SpectrumMobsI18n.send(
                sender,
                "command-mob-spawned",
                result.definition.id,
                result.instanceId
            )
            is MobSpawnResult.Failed -> SpectrumMobsI18n.send(sender, "command-mob-spawn-failed", result.reason)
        }
    }

    private fun kill(sender: ProxyCommandSender, name: String, uuid: String) {
        when (val result = SpectrumMobs.mobService.kill(name, uuid)) {
            is MobKillResult.Killed -> {
                cleanupKilledEntity(result.entity)
                SpectrumMobsI18n.send(sender, "command-mob-killed", result.definitionId, result.entity.uniqueId)
            }
            is MobKillResult.Failed -> SpectrumMobsI18n.send(sender, "command-mob-kill-failed", result.reason)
        }
    }

    private fun killAll(sender: ProxyCommandSender) {
        val killed = SpectrumMobs.mobService.killAll()
        killed.forEach { cleanupKilledEntity(it.entity) }
        SpectrumMobsI18n.send(sender, "command-mob-killall", killed.size)
    }

    private fun cleanupKilledEntity(entity: org.bukkit.entity.Entity) {
        SpectrumMobs.mobIntervals.unregister(entity)
        SpectrumMobs.projectiles.removeByOwner(entity.uniqueId)
        SpectrumMobs.skillRuntime.cancelByCaster(entity, "Mob killed by command")
    }
}
