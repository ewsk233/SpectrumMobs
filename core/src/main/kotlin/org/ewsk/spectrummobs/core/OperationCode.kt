package org.ewsk.spectrummobs.core

/** 稳定的机器可读结果码；只有预期拒绝允许由脚本显式忽略。 */
enum class OperationCode(val expectedRejection: Boolean = false) {
    SUCCESS,
    COOLDOWN(true),
    GROUP_BUSY(true),
    CONDITION_FAILED(true),
    CAPACITY(true),
    PARENT_INACTIVE,
    DEPTH_LIMIT,
    INVALID_ARGUMENT,
    MISSING_DEFINITION,
    WORLD_UNAVAILABLE,
    SPAWN_FAILED,
    START_FAILED;

    val key: String get() = name.lowercase()
}
