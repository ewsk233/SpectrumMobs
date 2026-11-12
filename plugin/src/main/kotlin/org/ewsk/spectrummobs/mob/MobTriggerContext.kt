package org.ewsk.spectrummobs.mob

import org.ewsk.spectrummobs.script.RuntimeValue

/**
 * 所有怪物触发器共用的上下文。平台实体转换在插件层完成，脚本只接收统一的运行时值。
 */
data class MobTriggerContext(
    val eventName: String,
    val damage: Double = 0.0,
    val target: RuntimeValue.EntityValue? = null,
    val cause: String = "unknown",
    val hand: String = "unknown",
    val phase: String? = null,
    val interval: String? = null,
    val extraVariables: Map<String, RuntimeValue> = emptyMap()
) {
    fun variables(): Map<String, RuntimeValue> = buildMap {
        putAll(extraVariables)
        phase?.let { putIfAbsent("phase", RuntimeValue.TextValue(it)) }
        interval?.let { putIfAbsent("interval", RuntimeValue.TextValue(it)) }
        put("event", RuntimeValue.EventValue(eventName, damage, target, cause, hand))
        target?.let { put("target", it) }
    }
}
