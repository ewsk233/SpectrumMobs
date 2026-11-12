package org.ewsk.spectrummobs.command

import org.bukkit.Bukkit
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import taboolib.module.configuration.Configuration
import java.io.File

enum class CommandExecutorType {
    CONSOLE,
    PLAYER
}

data class RegisteredCommand(
    val id: String,
    val executor: CommandExecutorType,
    val template: String
)

data class CommandPolicy(
    val allowRaw: Boolean = false,
    val maxRawLength: Int = 256,
    val definitions: Map<String, RegisteredCommand> = emptyMap()
)

data class CommandPolicyLoadResult(
    val policy: CommandPolicy,
    val diagnostics: List<String> = emptyList()
)

object CommandPolicyLoader {
    fun load(file: File): CommandPolicyLoadResult {
        if (!file.exists()) return CommandPolicyLoadResult(CommandPolicy())
        return runCatching {
            fromValues(Configuration.loadFromFile(file).toPlainMap())
        }.getOrElse {
            CommandPolicyLoadResult(
                CommandPolicy(),
                listOf("Unable to read command configuration: ${it.message ?: it.javaClass.simpleName}")
            )
        }
    }

    fun fromValues(values: Map<String, Any?>): CommandPolicyLoadResult {
        val diagnostics = mutableListOf<String>()
        val commands = values["commands"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
        val allowRaw = commands["allow-raw"] as? Boolean ?: false
        val maxRawLength = (commands["max-raw-length"] as? Number)?.toInt()?.coerceIn(1, 4096) ?: 256
        val rawDefinitions = commands["definitions"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
        val definitions = linkedMapOf<String, RegisteredCommand>()
        rawDefinitions.forEach { (rawId, rawValue) ->
            val id = rawId?.toString()?.trim()?.lowercase()
            val path = "commands.definitions.$rawId"
            val entry = rawValue as? Map<*, *>
            if (id.isNullOrBlank() || !id.matches(Regex("[a-z0-9_.-]+"))) {
                diagnostics += "$path: ID must contain only lowercase letters, digits, dots, underscores, or hyphens"
                return@forEach
            }
            if (entry == null) {
                diagnostics += "$path: must be an object"
                return@forEach
            }
            val executor = when ((entry["executor"]?.toString() ?: "console").lowercase()) {
                "console" -> CommandExecutorType.CONSOLE
                "player" -> CommandExecutorType.PLAYER
                else -> {
                    diagnostics += "$path.executor: only console or player are supported"
                    return@forEach
                }
            }
            val template = entry["template"] as? String
            if (template.isNullOrBlank()) {
                diagnostics += "$path.template: must not be blank"
                return@forEach
            }
            definitions[id] = RegisteredCommand(id, executor, template)
        }
        return CommandPolicyLoadResult(CommandPolicy(allowRaw, maxRawLength, definitions), diagnostics)
    }
}

class CommandDispatcher(
    private val policy: CommandPolicy
) {
    fun raw(command: String) {
        check(policy.allowRaw) { "command.raw is disabled by configuration" }
        val normalized = normalize(command)
        require(normalized.length <= policy.maxRawLength) { "command.raw exceeds the maximum length ${policy.maxRawLength}" }
        check(Bukkit.dispatchCommand(Bukkit.getConsoleSender(), normalized)) { "Console command failed: $normalized" }
    }

    fun run(id: String, target: Entity) {
        val definition = policy.definitions[id.trim().lowercase()]
            ?: throw IllegalArgumentException("Command template is not registered: $id")
        val command = normalize(render(definition.template, target))
        check(command.length <= policy.maxRawLength) { "Rendered command exceeds the maximum length ${policy.maxRawLength}" }
        when (definition.executor) {
            CommandExecutorType.CONSOLE -> {
                check(Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command)) {
                    "Console command failed: ${definition.id}"
                }
            }
            CommandExecutorType.PLAYER -> {
                val player = target as? Player
                    ?: throw IllegalArgumentException("Command ${definition.id} requires a player target")
                check(player.performCommand(command)) { "Player command failed: ${definition.id}" }
            }
        }
    }

    private fun render(template: String, target: Entity): String {
        val playerName = (target as? Player)?.name ?: target.name
        val location = target.location
        return template
            .replace("{target}", playerName)
            .replace("{target.name}", playerName)
            .replace("{target.uuid}", target.uniqueId.toString())
            .replace("{target.world}", location.world?.name ?: "")
            .replace("{target.x}", location.x.toString())
            .replace("{target.y}", location.y.toString())
            .replace("{target.z}", location.z.toString())
    }

    private fun normalize(command: String): String = command.trim().removePrefix("/")
}

private fun Configuration.toPlainMap(): Map<String, Any?> = valuesToPlain(getValues(false))

private fun valuesToPlain(values: Map<String, Any?>): Map<String, Any?> =
    values.mapValues { (_, value) -> valueToPlain(value) }

private fun valueToPlain(value: Any?): Any? = when (value) {
    is taboolib.library.configuration.ConfigurationSection -> valuesToPlain(value.getValues(false))
    is Map<*, *> -> value.entries.associate { (key, item) -> key.toString() to valueToPlain(item) }
    is List<*> -> value.map(::valueToPlain)
    else -> value
}
