package org.ewsk.spectrummobs.core

import kotlin.math.roundToLong

sealed interface MobDefinitionParseResult {
    data class Success(
        val definition: MobDefinition,
        val diagnostics: List<ConfigurationDiagnostic> = emptyList()
    ) : MobDefinitionParseResult

    data class Failure(
        val diagnostics: List<ConfigurationDiagnostic>
    ) : MobDefinitionParseResult
}

class MobDefinitionParser(
    private val strict: Boolean = true
) {
    fun parse(values: Map<String, Any?>, source: String): MobDefinitionParseResult {
        val diagnostics = mutableListOf<ConfigurationDiagnostic>()
        val knownKeys = setOf(
            "schema-version", "id", "type", "display", "attributes", "equipment", "events",
            "intervals", "boss", "bossbar", "summons", "drops", "model", "behavior", "damage-rules", "target-rules", "movement"
        )
        checkUnknownKeys(values, knownKeys, "", source, diagnostics)

        val schemaVersion = integer(values["schema-version"], "schema-version", source, diagnostics, 1)
        if (schemaVersion != 1) {
            error(diagnostics, source, "schema-version", "Only configuration version 1 is supported; got $schemaVersion")
        }

        val rawId = string(values["id"], "id", source, diagnostics)
        val id = rawId?.let(ResourceId::parse)
        if (rawId != null && id == null) {
            error(diagnostics, source, "id", "Must be a valid resource ID, for example spectrum:ember_guardian")
        }

        val entityType = string(values["type"], "type", source, diagnostics)
        if (entityType != null && entityType.isBlank()) {
            error(diagnostics, source, "type", "Entity type must not be blank")
        }

        val display = parseDisplay(values["display"], source, diagnostics, id)
        val attributes = parseAttributes(values["attributes"], source, diagnostics)
        val equipment = parseEquipment(values["equipment"], source, diagnostics)
        val events = parseEvents(values["events"], source, diagnostics)
        val eventArguments = parseEventArguments(values["events"], source, diagnostics)
        val intervals = parseIntervals(values["intervals"], source, diagnostics)
        val boss = parseBoss(values["boss"], source, diagnostics)
        val bossBar = parseBossBar(values["bossbar"], source, diagnostics, display.name)
        val summons = parseSummons(values["summons"], source, diagnostics)
        val drops = parseDrops(values["drops"], source, diagnostics)
        val model = parseModel(values["model"], source, diagnostics)
        val behavior = parseBehavior(values["behavior"], source, diagnostics)
        val damageRules = parseDamageRules(values["damage-rules"], source, diagnostics)
        val targetRules = parseTargetRules(values["target-rules"], source, diagnostics)
        val movement = parseMovement(values["movement"], source, diagnostics)

        if (id == null || entityType.isNullOrBlank()) {
            return MobDefinitionParseResult.Failure(diagnostics)
        }

        return if (diagnostics.any { it.level == DiagnosticLevel.ERROR }) {
            MobDefinitionParseResult.Failure(diagnostics)
        } else {
            MobDefinitionParseResult.Success(
                MobDefinition(id, entityType.trim(), display, attributes, equipment, events, intervals, boss, drops, model, bossBar, summons, eventArguments, behavior, damageRules, targetRules, movement),
                diagnostics
            )
        }
    }

    private fun parseDisplay(
        value: Any?,
        source: String,
        diagnostics: MutableList<ConfigurationDiagnostic>,
        id: ResourceId?
    ): MobDisplay {
        val values = section(value, "display", source, diagnostics) ?: return MobDisplay(id?.path ?: "Mob")
        checkUnknownKeys(values, setOf("name", "name-visible"), "display", source, diagnostics)
        val name = optionalString(values["name"], "display.name", source, diagnostics) ?: id?.path ?: "Mob"
        val nameVisible = boolean(values["name-visible"], "display.name-visible", source, diagnostics, false)
        return MobDisplay(name, nameVisible)
    }

    private fun parseAttributes(
        value: Any?,
        source: String,
        diagnostics: MutableList<ConfigurationDiagnostic>
    ): MobAttributes {
        val values = section(value, "attributes", source, diagnostics) ?: return MobAttributes()
        checkUnknownKeys(
            values,
            setOf("max-health", "attack-damage", "movement-speed", "follow-range", "knockback-resistance"),
            "attributes",
            source,
            diagnostics
        )
        val maxHealth = nonNegativeNumber(values["max-health"], "attributes.max-health", source, diagnostics, 20.0)
        val attackDamage = optionalNonNegativeNumber(values["attack-damage"], "attributes.attack-damage", source, diagnostics)
        val movementSpeed = optionalNonNegativeNumber(values["movement-speed"], "attributes.movement-speed", source, diagnostics)
        val followRange = optionalNonNegativeNumber(values["follow-range"], "attributes.follow-range", source, diagnostics)
        val knockbackResistance = boundedNumber(values["knockback-resistance"], "attributes.knockback-resistance", source, diagnostics, 0.0, 1.0)
        return MobAttributes(maxHealth, attackDamage, movementSpeed, followRange, knockbackResistance)
    }

    private fun parseEquipment(
        value: Any?,
        source: String,
        diagnostics: MutableList<ConfigurationDiagnostic>
    ): Map<String, EquipmentDefinition> {
        val values = section(value, "equipment", source, diagnostics) ?: return emptyMap()
        val result = linkedMapOf<String, EquipmentDefinition>()
        values.forEach { (slot, raw) ->
            val path = "equipment.$slot"
            val entry = section(raw, path, source, diagnostics) ?: return@forEach
            checkUnknownKeys(entry, setOf("material", "drop-chance"), path, source, diagnostics)
            val material = string(entry["material"], "$path.material", source, diagnostics)
            val dropChance = boundedNumber(entry["drop-chance"], "$path.drop-chance", source, diagnostics, 0.0, 1.0) ?: 0.0
            if (!material.isNullOrBlank()) {
                result[slot] = EquipmentDefinition(material.trim().uppercase(), dropChance)
            }
        }
        return result
    }

    private fun parseEvents(
        value: Any?,
        source: String,
        diagnostics: MutableList<ConfigurationDiagnostic>
    ): Map<String, List<ResourceId>> {
        val values = section(value, "events", source, diagnostics) ?: return emptyMap()
        val result = linkedMapOf<String, List<ResourceId>>()
        values.forEach { (event, raw) ->
            val path = "events.$event"
            val list = raw as? List<*>
            if (list == null) {
                error(diagnostics, source, path, "Must be a list of resource IDs")
                return@forEach
            }
            val ids = mutableListOf<ResourceId>()
            list.forEachIndexed { index, item ->
                val itemPath = "$path[$index]"
                val entry = if (item is Map<*, *>) section(item, itemPath, source, diagnostics) else null
                if (entry != null) checkUnknownKeys(entry, setOf("skill", "args"), itemPath, source, diagnostics)
                val text = item as? String ?: entry?.get("skill") as? String
                val id = text?.let(ResourceId::parse)
                if (id == null) {
                    error(diagnostics, source, itemPath, "Must be a valid resource ID")
                } else {
                    ids += id
                }
            }
            result[event] = ids
        }
        return result
    }

    private fun parseEventArguments(
        value: Any?,
        source: String,
        diagnostics: MutableList<ConfigurationDiagnostic>
    ): Map<String, List<Map<String, Any?>>> {
        val values = section(value, "events", source, diagnostics) ?: return emptyMap()
        val result = linkedMapOf<String, List<Map<String, Any?>>>()
        values.forEach { (event, raw) ->
            val list = raw as? List<*> ?: return@forEach
            result[event] = list.mapIndexedNotNull { index, item ->
                val map = item as? Map<*, *> ?: return@mapIndexedNotNull emptyMap()
                val path = "events.$event[$index]"
                val args = map["args"]
                if (args == null) return@mapIndexedNotNull emptyMap()
                val plain = args as? Map<*, *> ?: run {
                    error(diagnostics, source, "$path.args", "Must be an object")
                    return@mapIndexedNotNull emptyMap()
                }
                plain.entries.associate { (key, value) -> key.toString() to value }
            }
        }
        return result
    }

    private fun parseIntervals(
        value: Any?,
        source: String,
        diagnostics: MutableList<ConfigurationDiagnostic>
    ): List<MobIntervalTrigger> {
        if (value == null) return emptyList()
        val list = value as? List<*> ?: run {
            error(diagnostics, source, "intervals", "Must be a list of objects")
            return emptyList()
        }
        val result = list.mapIndexedNotNull { index, raw ->
            val path = "intervals[$index]"
            val values = section(raw, path, source, diagnostics) ?: return@mapIndexedNotNull null
            checkUnknownKeys(values, setOf("id", "period", "skills"), path, source, diagnostics)
            val rawId = string(values["id"], "$path.id", source, diagnostics)
            if (rawId != null && (rawId.isBlank() || !rawId.trim().matches(Regex("[a-z0-9_.-]+")))) {
                error(diagnostics, source, "$path.id", "Must be a non-blank lowercase trigger ID")
            }
            val id = rawId?.trim()?.lowercase()?.takeIf { it.matches(Regex("[a-z0-9_.-]+")) }
            val period = parseDurationTicks(values["period"], "$path.period", source, diagnostics)
            if (period != null && period <= 0L) {
                error(diagnostics, source, "$path.period", "Must be greater than 0")
            }
            val bindings = parseSkillBindings(values["skills"], "$path.skills", source, diagnostics)
            val skills = bindings.map { it.first }
            if (skills.isEmpty()) error(diagnostics, source, "$path.skills", "At least one skill ID is required")
            if (id == null || period == null || period <= 0L || skills.isEmpty()) null
            else MobIntervalTrigger(id, period, skills, bindings.map { it.second })
        }
        result.groupBy { it.id }.filterValues { it.size > 1 }.keys.forEach { id ->
            error(diagnostics, source, "intervals", "Interval trigger ID $id is defined more than once")
        }
        return result
    }

    private fun parseDurationTicks(
        value: Any?,
        path: String,
        source: String,
        diagnostics: MutableList<ConfigurationDiagnostic>
    ): Long? {
        if (value == null) {
            error(diagnostics, source, path, "Required field is missing")
            return null
        }
        val ticks = when (value) {
            is Number -> if (value.toDouble().isFinite()) value.toDouble() else null
            is String -> {
                val match = Regex("([0-9]+(?:\\.[0-9]+)?)([tsm])").matchEntire(value.trim())
                if (match == null) null else {
                    val amount = match.groupValues[1].toDouble()
                    amount * when (match.groupValues[2]) {
                        "t" -> 1.0
                        "s" -> 20.0
                        else -> 1200.0
                    }
                }
            }
            else -> null
        }
        if (ticks == null || !ticks.isFinite() || ticks < 0.0 || ticks.roundToLong().toDouble() != ticks) {
            error(diagnostics, source, path, "Must be a non-negative integer tick count or duration such as 200t or 10s")
            return null
        }
        return ticks.roundToLong()
    }

    private fun parseBoss(
        value: Any?,
        source: String,
        diagnostics: MutableList<ConfigurationDiagnostic>
    ): MobBossDefinition? {
        val values = section(value, "boss", source, diagnostics) ?: return null
        checkUnknownKeys(values, setOf("enabled", "phases"), "boss", source, diagnostics)
        val enabled = boolean(values["enabled"], "boss.enabled", source, diagnostics, true)
        val rawPhases = values["phases"]
        if (rawPhases == null) {
            if (enabled) error(diagnostics, source, "boss.phases", "Required field is missing")
            return MobBossDefinition(enabled)
        }
        val phases = rawPhases as? List<*> ?: run {
            error(diagnostics, source, "boss.phases", "Must be a list of objects")
            return MobBossDefinition(enabled)
        }
        val parsed = phases.mapIndexedNotNull { index, raw ->
            parsePhase(raw, "boss.phases[$index]", source, diagnostics)
        }
        if (enabled && parsed.isEmpty()) {
            error(diagnostics, source, "boss.phases", "At least one phase is required when boss phases are enabled")
        }
        if (enabled && parsed.none { it.healthThreshold == 1.0 }) {
            error(diagnostics, source, "boss.phases", "Must contain an initial phase with health-threshold 1.0")
        }
        parsed.groupBy { it.id }.filterValues { it.size > 1 }.keys.forEach { id ->
            error(diagnostics, source, "boss.phases", "Phase ID $id is defined more than once")
        }
        parsed.groupBy { it.healthThreshold }.filterValues { it.size > 1 }.keys.forEach { threshold ->
            error(diagnostics, source, "boss.phases", "Health threshold $threshold is defined more than once")
        }
        return MobBossDefinition(enabled, parsed)
    }

    private fun parseBossBar(
        value: Any?,
        source: String,
        diagnostics: MutableList<ConfigurationDiagnostic>,
        defaultTitle: String
    ): MobBossBarDefinition? {
        val values = section(value, "bossbar", source, diagnostics) ?: return null
        checkUnknownKeys(
            values,
            setOf("enabled", "title", "color", "style", "range", "update-interval"),
            "bossbar",
            source,
            diagnostics
        )
        val enabled = boolean(values["enabled"], "bossbar.enabled", source, diagnostics, true)
        val title = optionalString(values["title"], "bossbar.title", source, diagnostics)
            ?.takeIf { it.isNotBlank() }
            ?: defaultTitle
        val color = optionalString(values["color"], "bossbar.color", source, diagnostics)
            ?.trim()
            ?.uppercase()
            ?.takeIf { it.matches(Regex("[A-Z0-9_]+")) }
            ?: "RED"
        val style = optionalString(values["style"], "bossbar.style", source, diagnostics)
            ?.trim()
            ?.uppercase()
            ?.takeIf { it.matches(Regex("[A-Z0-9_]+")) }
            ?: "SOLID"
        val range = boundedNumber(values["range"], "bossbar.range", source, diagnostics, 1.0, 128.0) ?: 32.0
        val updateInterval = parseDurationTicks(
            values["update-interval"] ?: 1,
            "bossbar.update-interval",
            source,
            diagnostics
        ) ?: 1L
        if (updateInterval <= 0L) {
            error(diagnostics, source, "bossbar.update-interval", "Must be greater than 0")
        }
        return MobBossBarDefinition(enabled, title, color, style, range, updateInterval)
    }

    private fun parseSummons(
        value: Any?,
        source: String,
        diagnostics: MutableList<ConfigurationDiagnostic>
    ): MobSummonDefinition? {
        val values = section(value, "summons", source, diagnostics) ?: return null
        checkUnknownKeys(values, setOf("max-active", "cleanup-on-death"), "summons", source, diagnostics)
        val maxActive = integer(values["max-active"], "summons.max-active", source, diagnostics, 6)
        if (maxActive <= 0) {
            error(diagnostics, source, "summons.max-active", "Must be greater than 0")
        }
        val cleanupOnDeath = boolean(values["cleanup-on-death"], "summons.cleanup-on-death", source, diagnostics, true)
        return MobSummonDefinition(maxActive.coerceAtLeast(1), cleanupOnDeath)
    }

    private fun parsePhase(
        value: Any?,
        path: String,
        source: String,
        diagnostics: MutableList<ConfigurationDiagnostic>
    ): MobPhase? {
        val values = section(value, path, source, diagnostics) ?: return null
        checkUnknownKeys(
            values,
            setOf("id", "health-threshold", "display-name", "bossbar-title", "attributes", "on-enter", "on-exit"),
            path,
            source,
            diagnostics
        )
        val id = string(values["id"], "$path.id", source, diagnostics)
            ?.trim()
            ?.lowercase()
            ?.takeIf { it.matches(Regex("[a-z0-9_.-]+")) }
            ?: run {
                if (values["id"] is String) {
                    error(diagnostics, source, "$path.id", "Must be a non-blank lowercase phase ID")
                }
                return null
            }
        val thresholdValue = values["health-threshold"]
        if (thresholdValue == null) {
            error(diagnostics, source, "$path.health-threshold", "Required field is missing")
        }
        val threshold = boundedNumber(
            thresholdValue,
            "$path.health-threshold",
            source,
            diagnostics,
            0.0,
            1.0
        ) ?: 1.0
        val displayName = optionalString(values["display-name"], "$path.display-name", source, diagnostics)
        val bossbarTitle = optionalString(values["bossbar-title"], "$path.bossbar-title", source, diagnostics)
        val attributes = parsePhaseAttributes(values["attributes"], "$path.attributes", source, diagnostics)
        val onEnter = parseResourceIdList(values["on-enter"], "$path.on-enter", source, diagnostics)
        val onExit = parseResourceIdList(values["on-exit"], "$path.on-exit", source, diagnostics)
        return MobPhase(id, threshold, displayName, attributes, onEnter, onExit, bossbarTitle)
    }

    private fun parsePhaseAttributes(
        value: Any?,
        path: String,
        source: String,
        diagnostics: MutableList<ConfigurationDiagnostic>
    ): MobPhaseAttributes {
        val values = section(value, path, source, diagnostics) ?: return MobPhaseAttributes()
        checkUnknownKeys(
            values,
            setOf("attack-damage", "movement-speed", "knockback-resistance"),
            path,
            source,
            diagnostics
        )
        return MobPhaseAttributes(
            attackDamage = optionalNonNegativeNumber(values["attack-damage"], "$path.attack-damage", source, diagnostics),
            movementSpeed = optionalNonNegativeNumber(values["movement-speed"], "$path.movement-speed", source, diagnostics),
            knockbackResistance = boundedNumber(
                values["knockback-resistance"],
                "$path.knockback-resistance",
                source,
                diagnostics,
                0.0,
                1.0
            )
        )
    }

    private fun parseDrops(
        value: Any?,
        source: String,
        diagnostics: MutableList<ConfigurationDiagnostic>
    ): List<MobDrop> {
        if (value == null) return emptyList()
        val list = value as? List<*> ?: run {
            error(diagnostics, source, "drops", "Must be a list of objects")
            return emptyList()
        }
        return list.mapIndexedNotNull { index, raw ->
            val path = "drops[$index]"
            val values = section(raw, path, source, diagnostics) ?: return@mapIndexedNotNull null
            checkUnknownKeys(values, setOf("material", "amount", "chance", "rule"), path, source, diagnostics)
            val rawMaterial = string(values["material"], "$path.material", source, diagnostics)
            if (rawMaterial != null && rawMaterial.isBlank()) {
                error(diagnostics, source, "$path.material", "Must not be blank")
            }
            val material = rawMaterial?.trim()?.uppercase()?.takeIf(String::isNotEmpty)
            val amountValue = values["amount"]
            if (amountValue == null) error(diagnostics, source, "$path.amount", "Required field is missing")
            val amount = integer(amountValue, "$path.amount", source, diagnostics, 1)
            if (amount <= 0) error(diagnostics, source, "$path.amount", "Must be greater than 0")
            val chance = boundedNumber(values["chance"], "$path.chance", source, diagnostics, 0.0, 1.0) ?: 1.0
            val rawRule = optionalString(values["rule"], "$path.rule", source, diagnostics)
            val rule = rawRule?.let(ResourceId::parse)
            if (rawRule != null && rule == null) error(diagnostics, source, "$path.rule", "Must be a valid rule resource ID")
            if (material == null) null else MobDrop(material, amount, chance, rule)
        }
    }

    private fun parseBehavior(value: Any?, source: String, diagnostics: MutableList<ConfigurationDiagnostic>): MobBehaviorDefinition? {
        val values = section(value, "behavior", source, diagnostics) ?: return null
        checkUnknownKeys(values, setOf("skill", "period"), "behavior", source, diagnostics)
        val rawSkill = string(values["skill"], "behavior.skill", source, diagnostics)
        val skill = rawSkill?.let(ResourceId::parse)
        if (rawSkill != null && skill == null) error(diagnostics, source, "behavior.skill", "Must be a valid skill resource ID")
        val period = if (values["period"] == null) 10L else parseDurationTicks(values["period"], "behavior.period", source, diagnostics)
        if (period != null && period <= 0L) error(diagnostics, source, "behavior.period", "Must be greater than 0")
        return if (skill != null && period != null && period > 0L) MobBehaviorDefinition(skill, period) else null
    }

    private fun parseDamageRules(value: Any?, source: String, diagnostics: MutableList<ConfigurationDiagnostic>): Map<String, ResourceId> {
        val values = section(value, "damage-rules", source, diagnostics) ?: return emptyMap()
        val result = linkedMapOf<String, ResourceId>()
        values.forEach { (name, raw) ->
            val path = "damage-rules.$name"
            val text = string(raw, path, source, diagnostics)
            val id = text?.let(ResourceId::parse)
            if (id == null) error(diagnostics, source, path, "Must be a valid rule resource ID") else result[name.lowercase()] = id
        }
        return result
    }

    private fun parseTargetRules(value: Any?, source: String, diagnostics: MutableList<ConfigurationDiagnostic>): Map<String, ResourceId> {
        val values = section(value, "target-rules", source, diagnostics) ?: return emptyMap()
        val result = linkedMapOf<String, ResourceId>()
        values.forEach { (name, raw) ->
            val path = "target-rules.$name"
            val text = string(raw, path, source, diagnostics)
            val id = text?.let(ResourceId::parse)
            if (id == null) error(diagnostics, source, path, "Must be a valid rule resource ID") else result[name.lowercase()] = id
        }
        return result
    }

    private fun parseMovement(value: Any?, source: String, diagnostics: MutableList<ConfigurationDiagnostic>): MobMovementDefinition? {
        val values = section(value, "movement", source, diagnostics) ?: return null
        checkUnknownKeys(values, setOf("controller", "hover-height", "target-distance", "speed", "update-period"), "movement", source, diagnostics)
        val controller = optionalString(values["controller"], "movement.controller", source, diagnostics)
            ?.trim()?.lowercase()?.takeIf { it.matches(Regex("[a-z0-9_.-]+")) } ?: "none"
        val hoverHeight = boundedNumber(values["hover-height"], "movement.hover-height", source, diagnostics, 0.0, 128.0) ?: 3.0
        val targetDistance = boundedNumber(values["target-distance"], "movement.target-distance", source, diagnostics, 0.0, 128.0) ?: 8.0
        val speed = boundedNumber(values["speed"], "movement.speed", source, diagnostics, 0.0, 10.0) ?: 0.25
        val updatePeriod = parseDurationTicks(values["update-period"] ?: "1t", "movement.update-period", source, diagnostics) ?: 1L
        if (updatePeriod <= 0L) error(diagnostics, source, "movement.update-period", "Must be greater than 0")
        return MobMovementDefinition(controller, hoverHeight, targetDistance, speed, updatePeriod)
    }

    private fun parseModel(
        value: Any?,
        source: String,
        diagnostics: MutableList<ConfigurationDiagnostic>
    ): MobModel? {
        val values = section(value, "model", source, diagnostics) ?: return null
        checkUnknownKeys(values, setOf("provider", "id", "options"), "model", source, diagnostics)
        val rawProvider = string(values["provider"], "model.provider", source, diagnostics)
        if (rawProvider != null && rawProvider.isBlank()) error(diagnostics, source, "model.provider", "Must not be blank")
        val provider = rawProvider?.trim()?.lowercase()?.takeIf(String::isNotEmpty)
        val rawId = string(values["id"], "model.id", source, diagnostics)
        if (rawId != null && rawId.isBlank()) error(diagnostics, source, "model.id", "Must not be blank")
        val id = rawId?.trim()?.takeIf(String::isNotEmpty)
        val options = parseStringMap(values["options"], "model.options", source, diagnostics)
        return if (provider == null || id == null) null else MobModel(provider, id, options)
    }

    private fun parseStringMap(
        value: Any?,
        path: String,
        source: String,
        diagnostics: MutableList<ConfigurationDiagnostic>
    ): Map<String, String> {
        if (value == null) return emptyMap()
        val map = value as? Map<*, *> ?: run {
            error(diagnostics, source, path, "Must be an object with string keys and values")
            return emptyMap()
        }
        val result = linkedMapOf<String, String>()
        map.forEach { (rawKey, rawValue) ->
            val key = rawKey?.toString()?.trim()
            if (key.isNullOrEmpty() || rawValue !is String) {
                error(diagnostics, source, "$path.${rawKey ?: "<null>"}", "Key and value must both be non-blank strings")
            } else {
                result[key] = rawValue
            }
        }
        return result
    }

    private fun parseResourceIdList(
        value: Any?,
        path: String,
        source: String,
        diagnostics: MutableList<ConfigurationDiagnostic>
    ): List<ResourceId> {
        if (value == null) return emptyList()
        val list = value as? List<*> ?: run {
            error(diagnostics, source, path, "Must be a list of resource IDs")
            return emptyList()
        }
        return list.mapIndexedNotNull { index, item ->
            val id = (item as? String)?.let(ResourceId::parse)
            if (id == null) {
                error(diagnostics, source, "$path[$index]", "Must be a valid resource ID")
                null
            } else {
                id
            }
        }
    }

    private fun parseSkillBindings(
        value: Any?,
        path: String,
        source: String,
        diagnostics: MutableList<ConfigurationDiagnostic>
    ): List<Pair<ResourceId, Map<String, Any?>>> {
        val list = value as? List<*> ?: run {
            error(diagnostics, source, path, "Must be a list of resource IDs or skill objects")
            return emptyList()
        }
        return list.mapIndexedNotNull { index, item ->
            val itemPath = "$path[$index]"
            val (rawId, rawArgs) = when (item) {
                is String -> item to emptyMap<String, Any?>()
                is Map<*, *> -> {
                    val id = item["skill"] as? String
                    val args = item["args"] as? Map<*, *>
                    if (args == null && "args" in item) error(diagnostics, source, "$itemPath.args", "Must be an object")
                    id to (args?.entries?.associate { (key, value) -> key.toString() to value } ?: emptyMap())
                }
                else -> null to emptyMap()
            }
            val id = rawId?.let(ResourceId::parse)
            if (id == null) {
                error(diagnostics, source, itemPath, "Must be a valid resource ID or an object with a valid skill field")
                null
            } else id to rawArgs
        }
    }

    private fun checkUnknownKeys(
        values: Map<String, Any?>,
        known: Set<String>,
        path: String,
        source: String,
        diagnostics: MutableList<ConfigurationDiagnostic>
    ) {
        values.keys.filterNot { it in known }.forEach { key ->
            val keyPath = if (path.isBlank()) key else "$path.$key"
            if (strict) error(diagnostics, source, keyPath, "Unknown configuration field")
            else diagnostics += ConfigurationDiagnostic(source, keyPath, "Unknown configuration field", DiagnosticLevel.WARNING)
        }
    }

    private fun section(
        value: Any?,
        path: String,
        source: String,
        diagnostics: MutableList<ConfigurationDiagnostic>
    ): Map<String, Any?>? {
        if (value == null) return null
        @Suppress("UNCHECKED_CAST")
        val map = value as? Map<*, *> ?: run {
            error(diagnostics, source, path, "Must be an object")
            return null
        }
        return map.entries.associate { (key, item) -> key.toString() to item }
    }

    private fun string(value: Any?, path: String, source: String, diagnostics: MutableList<ConfigurationDiagnostic>): String? {
        if (value == null) {
            error(diagnostics, source, path, "Required field is missing")
            return null
        }
        if (value !is String) {
            error(diagnostics, source, path, "Must be a string")
            return null
        }
        return value
    }

    private fun optionalString(value: Any?, path: String, source: String, diagnostics: MutableList<ConfigurationDiagnostic>): String? {
        if (value == null) return null
        if (value !is String) {
            error(diagnostics, source, path, "Must be a string")
            return null
        }
        return value
    }

    private fun boolean(value: Any?, path: String, source: String, diagnostics: MutableList<ConfigurationDiagnostic>, default: Boolean): Boolean {
        if (value == null) return default
        if (value !is Boolean) {
            error(diagnostics, source, path, "Must be a boolean")
            return default
        }
        return value
    }

    private fun integer(value: Any?, path: String, source: String, diagnostics: MutableList<ConfigurationDiagnostic>, default: Int): Int {
        if (value == null) return default
        if (value !is Number || value.toDouble() % 1.0 != 0.0) {
            error(diagnostics, source, path, "Must be an integer")
            return default
        }
        return value.toInt()
    }

    private fun nonNegativeNumber(value: Any?, path: String, source: String, diagnostics: MutableList<ConfigurationDiagnostic>, default: Double): Double {
        return optionalNonNegativeNumber(value, path, source, diagnostics) ?: default
    }

    private fun optionalNonNegativeNumber(value: Any?, path: String, source: String, diagnostics: MutableList<ConfigurationDiagnostic>): Double? {
        if (value == null) return null
        if (value !is Number) {
            error(diagnostics, source, path, "Must be a number")
            return null
        }
        val number = value.toDouble()
        if (!number.isFinite() || number < 0.0) {
            error(diagnostics, source, path, "Must be a finite non-negative number")
            return null
        }
        return number
    }

    private fun boundedNumber(value: Any?, path: String, source: String, diagnostics: MutableList<ConfigurationDiagnostic>, min: Double, max: Double): Double? {
        val number = optionalNonNegativeNumber(value, path, source, diagnostics) ?: return null
        if (number !in min..max) {
            error(diagnostics, source, path, "Must be between $min and $max")
            return null
        }
        return number
    }

    private fun error(diagnostics: MutableList<ConfigurationDiagnostic>, source: String, path: String, message: String) {
        diagnostics += ConfigurationDiagnostic(source, path, message)
    }
}
