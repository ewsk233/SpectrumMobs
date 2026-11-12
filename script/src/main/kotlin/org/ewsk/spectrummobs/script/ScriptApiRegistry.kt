package org.ewsk.spectrummobs.script

enum class ScriptApiKind { TARGETER, CONDITION, MECHANIC, SERVICE }

data class ScriptFunctionSignature(
    val parameters: List<FunctionParameter>,
    val returnType: ScriptType,
    val kind: ScriptApiKind,
    val safeInHeaders: Boolean,
    val allowExtraNamed: Boolean = false
)

/**
 * Script 编译期 API 注册表。插件运行时必须用同名 RuntimeAction 注册实现。
 */
class ScriptApiRegistry private constructor(
    private val functions: Map<String, ScriptFunctionSignature>
) {
    fun signature(name: String): ScriptFunctionSignature? = functions[name.lowercase()]

    fun hasNamespace(namespace: String): Boolean = functions.keys.any { it.startsWith("${namespace.lowercase()}.") }

    val names: Set<String> get() = functions.keys.toSet()

    val rootNamespaces: Set<String> get() = functions.keys.map { it.substringBefore('.') }.toSet()

    fun toBuilder(): Builder = Builder(this)

    companion object {
        fun empty(): ScriptApiRegistry = Builder().build()

        fun default(): ScriptApiRegistry = Builder().apply {
            // Targeter
            function("targeter.self", emptyList(), ScriptType.Entity)
            function("targeter.target", emptyList(), ScriptType.Entity)
            function("targeter.attacker", emptyList(), ScriptType.Entity)
            function("targeter.threat.top", emptyList(), ScriptType.Entity)
            function("targeter.threat.list", emptyList(), ScriptType.ListType(ScriptType.Entity))
            function(
                "targeter.players.within",
                listOf(
                    FunctionParameter("center", ScriptType.Position),
                    FunctionParameter("radius", ScriptType.Number)
                ),
                ScriptType.ListType(ScriptType.Player)
            )
            function(
                "targeter.entities.within",
                listOf(
                    FunctionParameter("center", ScriptType.Position),
                    FunctionParameter("radius", ScriptType.Number)
                ),
                ScriptType.ListType(ScriptType.Entity)
            )
            function(
                "targeter.entities.nearest",
                listOf(
                    FunctionParameter("center", ScriptType.Position),
                    FunctionParameter("radius", ScriptType.Number)
                ),
                ScriptType.Entity
            )
            function(
                "targeter.entities.tagged",
                listOf(
                    FunctionParameter("center", ScriptType.Position),
                    FunctionParameter("radius", ScriptType.Number),
                    FunctionParameter("tag", ScriptType.Text)
                ),
                ScriptType.ListType(ScriptType.Entity)
            )
            function(
                "targeter.random",
                listOf(
                    FunctionParameter("targets", ScriptType.ListType(ScriptType.TypeParameter("T", ScriptType.Entity))),
                    FunctionParameter("count", ScriptType.Number)
                ),
                ScriptType.ListType(ScriptType.TypeParameter("T", ScriptType.Entity))
            )

            // Condition
            function("condition.all", listOf(FunctionParameter("left", ScriptType.Boolean), FunctionParameter("right", ScriptType.Boolean)), ScriptType.Boolean)
            function("condition.any", listOf(FunctionParameter("left", ScriptType.Boolean), FunctionParameter("right", ScriptType.Boolean)), ScriptType.Boolean)
            function("condition.not", listOf(FunctionParameter("value", ScriptType.Boolean)), ScriptType.Boolean)
            function("condition.isPlayer", listOf(FunctionParameter("entity", ScriptType.Entity)), ScriptType.Boolean)
            function("condition.isLiving", listOf(FunctionParameter("entity", ScriptType.Entity)), ScriptType.Boolean)
            function("condition.healthBelow", listOf(FunctionParameter("entity", ScriptType.Entity), FunctionParameter("threshold", ScriptType.Numeric)), ScriptType.Boolean)
            function("condition.healthAbove", listOf(FunctionParameter("entity", ScriptType.Entity), FunctionParameter("threshold", ScriptType.Numeric)), ScriptType.Boolean)
            function("condition.hasPotion", listOf(FunctionParameter("entity", ScriptType.Entity), FunctionParameter("effect", ScriptType.Text)), ScriptType.Boolean)
            function("condition.distanceBelow", listOf(FunctionParameter("first", ScriptType.Entity), FunctionParameter("second", ScriptType.Entity), FunctionParameter("radius", ScriptType.Number)), ScriptType.Boolean)
            function("condition.eventCause", listOf(FunctionParameter("cause", ScriptType.Text)), ScriptType.Boolean)
            function("condition.hasTag", listOf(FunctionParameter("entity", ScriptType.Entity), FunctionParameter("tag", ScriptType.Text)), ScriptType.Boolean)
            function("condition.threat.above", listOf(FunctionParameter("entity", ScriptType.Entity), FunctionParameter("amount", ScriptType.Number)), ScriptType.Boolean)
            function("condition.threat.isTop", listOf(FunctionParameter("entity", ScriptType.Entity)), ScriptType.Boolean)

            // Mechanics
            function("mechanic.damage", listOf(FunctionParameter("target", ScriptType.Entity), FunctionParameter("amount", ScriptType.Number)), ScriptType.Unit)
            function("mechanic.ignite", listOf(FunctionParameter("target", ScriptType.Entity), FunctionParameter("duration", ScriptType.Duration)), ScriptType.Unit)
            function(
                "mechanic.particles.circle",
                listOf(
                    FunctionParameter("at", ScriptType.Position),
                    FunctionParameter("particle", ScriptType.Particle),
                    FunctionParameter("radius", ScriptType.Number),
                    FunctionParameter("count", ScriptType.Number)
                ),
                ScriptType.Unit
            )
            function(
                "mechanic.particles.line",
                listOf(
                    FunctionParameter("from", ScriptType.Position),
                    FunctionParameter("to", ScriptType.Position),
                    FunctionParameter("particle", ScriptType.Particle),
                    FunctionParameter("step", ScriptType.Number)
                ),
                ScriptType.Unit
            )
            function(
                "mechanic.ray.damage",
                listOf(
                    FunctionParameter("from", ScriptType.Position),
                    FunctionParameter("to", ScriptType.Position),
                    FunctionParameter("damage", ScriptType.Number),
                    FunctionParameter("radius", ScriptType.Number)
                ),
                ScriptType.Unit
            )
            function("mechanic.message.send", listOf(FunctionParameter("player", ScriptType.Player), FunctionParameter("message", ScriptType.Text)), ScriptType.Unit)
            function(
                "mechanic.title.show",
                listOf(
                    FunctionParameter("player", ScriptType.Player),
                    FunctionParameter("title", ScriptType.Text),
                    FunctionParameter("subtitle", ScriptType.Text),
                    FunctionParameter("fadeIn", ScriptType.Duration),
                    FunctionParameter("stay", ScriptType.Duration),
                    FunctionParameter("fadeOut", ScriptType.Duration)
                ),
                ScriptType.Unit
            )
            function("mechanic.actionbar.show", listOf(FunctionParameter("player", ScriptType.Player), FunctionParameter("message", ScriptType.Text)), ScriptType.Unit)
            function(
                "mechanic.sound.play",
                listOf(
                    FunctionParameter("player", ScriptType.Player),
                    FunctionParameter("sound", ScriptType.Text),
                    FunctionParameter("volume", ScriptType.Number),
                    FunctionParameter("pitch", ScriptType.Number)
                ),
                ScriptType.Unit
            )
            function("mechanic.entity.setHealth", listOf(FunctionParameter("entity", ScriptType.Entity), FunctionParameter("amount", ScriptType.Number)), ScriptType.Unit)
            function("mechanic.entity.teleport", listOf(FunctionParameter("entity", ScriptType.Entity), FunctionParameter("position", ScriptType.Position)), ScriptType.Unit)
            function(
                "mechanic.entity.velocity",
                listOf(
                    FunctionParameter("entity", ScriptType.Entity),
                    FunctionParameter("x", ScriptType.Number),
                    FunctionParameter("y", ScriptType.Number),
                    FunctionParameter("z", ScriptType.Number)
                ),
                ScriptType.Unit
            )
            function("mechanic.entity.remove", listOf(FunctionParameter("entity", ScriptType.Entity)), ScriptType.Unit)
            function("mechanic.entity.addTag", listOf(FunctionParameter("entity", ScriptType.Entity), FunctionParameter("tag", ScriptType.Text)), ScriptType.Unit)
            function("mechanic.entity.removeTag", listOf(FunctionParameter("entity", ScriptType.Entity), FunctionParameter("tag", ScriptType.Text)), ScriptType.Unit)
            function("mechanic.entity.pull", listOf(FunctionParameter("entity", ScriptType.Entity), FunctionParameter("center", ScriptType.Position), FunctionParameter("strength", ScriptType.Number)), ScriptType.Unit)
            function("mechanic.entity.push", listOf(FunctionParameter("entity", ScriptType.Entity), FunctionParameter("center", ScriptType.Position), FunctionParameter("strength", ScriptType.Number)), ScriptType.Unit)
            function(
                "mechanic.entity.potion",
                listOf(
                    FunctionParameter("entity", ScriptType.Entity),
                    FunctionParameter("effect", ScriptType.Text),
                    FunctionParameter("duration", ScriptType.Duration),
                    FunctionParameter("amplifier", ScriptType.Number)
                ),
                ScriptType.Unit
            )
            function("mechanic.projectile.launch", projectileLaunchParameters(), ScriptType.Unit)
            function("mechanic.projectile.forward", projectileForwardParameters(), ScriptType.Unit)
            function(
                "mechanic.projectile.homing",
                listOf(
                    FunctionParameter("target", ScriptType.Entity),
                    FunctionParameter("material", ScriptType.Text),
                    FunctionParameter("speed", ScriptType.Number),
                    FunctionParameter("turnRate", ScriptType.Number),
                    FunctionParameter("damage", ScriptType.Number),
                    FunctionParameter("lifetime", ScriptType.Duration),
                    FunctionParameter("hitRadius", ScriptType.Number),
                    FunctionParameter("placeOnHit", ScriptType.Boolean, required = false)
                ),
                ScriptType.Unit
            )
            function(
                "mechanic.mob.summon",
                listOf(
                    FunctionParameter("id", ScriptType.Text),
                    FunctionParameter("at", ScriptType.Position),
                    FunctionParameter("count", ScriptType.Number, required = false),
                    FunctionParameter("tag", ScriptType.Text, required = false),
                    FunctionParameter("ignoreRejected", ScriptType.Boolean, required = false)
                ),
                ScriptType.Number
            )
            function("mechanic.threat.add", listOf(FunctionParameter("entity", ScriptType.Entity), FunctionParameter("amount", ScriptType.Number)), ScriptType.Unit)
            function("mechanic.threat.set", listOf(FunctionParameter("entity", ScriptType.Entity), FunctionParameter("amount", ScriptType.Number)), ScriptType.Unit)
            function("mechanic.threat.remove", listOf(FunctionParameter("entity", ScriptType.Entity)), ScriptType.Unit)
            function("mechanic.threat.clear", emptyList(), ScriptType.Unit)
            function(
                "mechanic.bossbar.show",
                listOf(
                    FunctionParameter("player", ScriptType.Player),
                    FunctionParameter("title", ScriptType.Text),
                    FunctionParameter("progress", ScriptType.Numeric),
                    FunctionParameter("duration", ScriptType.Duration)
                ),
                ScriptType.Unit
            )
            function(
                "mechanic.scoreboard.set",
                listOf(
                    FunctionParameter("player", ScriptType.Player),
                    FunctionParameter("objective", ScriptType.Text),
                    FunctionParameter("score", ScriptType.Number)
                ),
                ScriptType.Unit
            )

            // 受控辅助服务同样从此注册，类型检查器不再维护第二份签名。
            function("random.chance", listOf(FunctionParameter("chance", ScriptType.Percentage)), ScriptType.Boolean, safeInHeaders = true)
            function("random.number", listOf(FunctionParameter("minimum", ScriptType.Number), FunctionParameter("maximum", ScriptType.Number)), ScriptType.Number, safeInHeaders = true)
            function("state.get", listOf(FunctionParameter("key", ScriptType.Text)), ScriptType.Number, safeInHeaders = true)
            function("state.set", listOf(FunctionParameter("key", ScriptType.Text), FunctionParameter("value", ScriptType.Number)), ScriptType.Unit)
            function("state.increment", listOf(FunctionParameter("key", ScriptType.Text), FunctionParameter("amount", ScriptType.Number)), ScriptType.Number)
            function("skills.cast", listOf(
                FunctionParameter("id", ScriptType.Text), FunctionParameter("caster", ScriptType.Entity),
                FunctionParameter("target", ScriptType.Entity), FunctionParameter("linked", ScriptType.Boolean, required = false),
                FunctionParameter("ignoreRejected", ScriptType.Boolean, required = false)
            ), ScriptType.Text, allowExtraNamed = true)
            function("command.raw", listOf(FunctionParameter("command", ScriptType.Text)), ScriptType.Unit)
            function("command.run", listOf(FunctionParameter("id", ScriptType.Text), FunctionParameter("target", ScriptType.Entity)), ScriptType.Unit)
            function("variables.get", listOf(FunctionParameter("scope", ScriptType.Text), FunctionParameter("key", ScriptType.Text)), ScriptType.Number, safeInHeaders = true)
            function("variables.set", listOf(FunctionParameter("scope", ScriptType.Text), FunctionParameter("key", ScriptType.Text), FunctionParameter("value", ScriptType.Number)), ScriptType.Unit)
            function("variables.increment", listOf(FunctionParameter("scope", ScriptType.Text), FunctionParameter("key", ScriptType.Text), FunctionParameter("amount", ScriptType.Number)), ScriptType.Number)
            function("variables.has", listOf(FunctionParameter("scope", ScriptType.Text), FunctionParameter("key", ScriptType.Text)), ScriptType.Boolean, safeInHeaders = true)
        }.build()

        private fun projectileLaunchParameters() = listOf(
            FunctionParameter("from", ScriptType.Position),
            FunctionParameter("to", ScriptType.Position),
            FunctionParameter("material", ScriptType.Text),
            FunctionParameter("speed", ScriptType.Number),
            FunctionParameter("damage", ScriptType.Number),
            FunctionParameter("lifetime", ScriptType.Duration),
            FunctionParameter("hitRadius", ScriptType.Number),
            FunctionParameter("placeOnHit", ScriptType.Boolean, required = false)
        )

        private fun projectileForwardParameters() = listOf(
            FunctionParameter("material", ScriptType.Text),
            FunctionParameter("speed", ScriptType.Number),
            FunctionParameter("damage", ScriptType.Number),
            FunctionParameter("lifetime", ScriptType.Duration),
            FunctionParameter("hitRadius", ScriptType.Number),
            FunctionParameter("placeOnHit", ScriptType.Boolean, required = false)
        )
    }

    class Builder(base: ScriptApiRegistry? = null) {
        private val functions = base?.functions?.toMutableMap() ?: linkedMapOf()

        fun function(name: String, parameters: List<FunctionParameter>, returnType: ScriptType, safeInHeaders: Boolean? = null, allowExtraNamed: Boolean = false): Builder {
            require(API_NAME.matches(name)) { "Invalid script API name: $name" }
            val normalized = name.lowercase()
            require(normalized.substringBefore('.') !in RESERVED_ROOTS) { "Reserved script API namespace: $name" }
            require(normalized.substringBefore('.') in API_ROOTS) { "Unsupported script API namespace: $name" }
            check(normalized !in functions) { "Script API is already registered: $name" }
            check(functions.keys.none { it.startsWith("$normalized.") || normalized.startsWith("$it.") }) {
                "Script API function conflicts with a namespace: $name"
            }
            require(parameters.map { it.name }.distinct().size == parameters.size) { "Duplicate parameter in script API: $name" }
            require(parameters.all { PARAMETER_NAME.matches(it.name) }) { "Invalid parameter in script API: $name" }
            require(parameters.all { supportedType(it.type) } && supportedType(returnType)) {
                "Unsupported value type in script API: $name"
            }
            val firstOptional = parameters.indexOfFirst { !it.required }
            require(firstOptional == -1 || parameters.drop(firstOptional).none { it.required }) {
                "Required parameter cannot follow an optional parameter: $name"
            }
            val kind = when (normalized.substringBefore('.')) {
                "targeter" -> ScriptApiKind.TARGETER
                "condition" -> ScriptApiKind.CONDITION
                "mechanic" -> ScriptApiKind.MECHANIC
                else -> ScriptApiKind.SERVICE
            }
            require(kind != ScriptApiKind.CONDITION || returnType == ScriptType.Boolean) {
                "Condition must return Boolean: $name"
            }
            require(kind != ScriptApiKind.TARGETER || isTargetType(returnType)) {
                "Targeter must return Entity or List<Entity>: $name"
            }
            val headerSafe = safeInHeaders ?: (kind == ScriptApiKind.TARGETER || kind == ScriptApiKind.CONDITION)
            require(kind != ScriptApiKind.MECHANIC || !headerSafe) { "Mechanic cannot execute in skill headers: $name" }
            functions[normalized] = ScriptFunctionSignature(parameters.toList(), returnType, kind, headerSafe, allowExtraNamed)
            return this
        }

        fun build(): ScriptApiRegistry = ScriptApiRegistry(functions.toMap())

        private companion object {
            fun supportedType(type: ScriptType): Boolean = when (type) {
                ScriptType.Unknown, is ScriptType.Service, is ScriptType.Function -> false
                is ScriptType.ListType -> supportedType(type.elementType)
                is ScriptType.TypeParameter -> supportedType(type.bound)
                else -> true
            }

            fun isTargetType(type: ScriptType): Boolean = when (type) {
                ScriptType.Entity, ScriptType.Player -> true
                is ScriptType.TypeParameter -> isTargetType(type.bound)
                is ScriptType.ListType -> type.elementType !is ScriptType.ListType && isTargetType(type.elementType)
                else -> false
            }

            val API_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)+")
            val PARAMETER_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")
            val API_ROOTS = setOf("targeter", "condition", "mechanic", "random", "state", "skills", "command", "variables")
            val RESERVED_ROOTS = setOf("caster", "target", "event", "phase", "interval", "flame", "soul", "skill", "let", "if", "else", "for", "in", "repeat", "return", "wait", "cooldown", "requires", "true", "false", "null")
        }
    }
}
