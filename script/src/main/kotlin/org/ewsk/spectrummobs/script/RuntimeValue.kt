package org.ewsk.spectrummobs.script

sealed interface RuntimeValue {
    data object NullValue : RuntimeValue

    data class NumberValue(val value: Double) : RuntimeValue

    data class TextValue(val value: String) : RuntimeValue

    data class BooleanValue(val value: Boolean) : RuntimeValue

    data class DurationValue(val ticks: Long) : RuntimeValue

    data class PercentageValue(val value: Double) : RuntimeValue

    enum class EntityKind {
        GENERIC,
        PLAYER,
        MOB
    }

    data class EntityValue(
        val id: String,
        val kind: EntityKind = EntityKind.GENERIC,
        val currentHealth: Double? = null,
        val maxHealth: Double? = null,
        val position: PositionValue? = null,
        /** 平台对象的透明引用，脚本模块不读取其具体类型。 */
        val origin: Any? = null
    ) : RuntimeValue

    data class PositionValue(
        val x: Double,
        val y: Double,
        val z: Double,
        val world: String? = null
    ) : RuntimeValue

    data class ParticleValue(val name: String) : RuntimeValue

    data class HealthValue(
        val current: Double,
        val max: Double
    ) : RuntimeValue

    data class EventValue(
        val name: String,
        val damage: Double = 0.0,
        val attacker: EntityValue? = null,
        val cause: String = "unknown",
        val hand: String = "unknown"
    ) : RuntimeValue

    data class ListValue(val values: List<RuntimeValue>) : RuntimeValue

    data class ServiceValue(val name: String) : RuntimeValue

    data class FunctionValue(val name: String) : RuntimeValue

    data object UnitValue : RuntimeValue
}
