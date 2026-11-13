package org.ewsk.spectrummobs.script

import java.util.UUID

/** 只管理可清理资源的所有权，平台通过回调执行实际移除。 */
class RuntimeEffectRegistry(
    private val accepts: (UUID) -> Boolean = { true },
    private val onChanged: (UUID) -> Unit = {},
    private val onFailure: (UUID, String, Exception) -> Unit = { _, _, _ -> }
) {
    private val owned = linkedMapOf<UUID, MutableMap<String, () -> Unit>>()

    fun count(owner: UUID): Int = owned[owner]?.size ?: 0

    fun register(owner: UUID, key: String, cleanup: () -> Unit) {
        require(key.isNotBlank()) { "Effect key must not be blank" }
        if (!accepts(owner)) {
            runCleanup(owner, key, cleanup)
            return
        }
        val effects = owned.getOrPut(owner) { linkedMapOf() }
        check(key !in effects) { "Effect is already owned by execution $owner: $key" }
        effects[key] = cleanup
        onChanged(owner)
    }

    fun release(owner: UUID, key: String): Boolean {
        val effects = owned[owner] ?: return false
        if (effects.remove(key) == null) return false
        if (effects.isEmpty()) owned.remove(owner)
        onChanged(owner)
        return true
    }

    /** 主动销毁单项效果；与整实例清理相同地隔离并报告平台异常。 */
    fun dispose(owner: UUID, key: String): Boolean {
        val effects = owned[owner] ?: return false
        val cleanup = effects.remove(key) ?: return false
        if (effects.isEmpty()) owned.remove(owner)
        runCleanup(owner, key, cleanup)
        onChanged(owner)
        return true
    }

    fun cleanup(owner: UUID) {
        val effects = owned.remove(owner) ?: return
        effects.forEach { (key, cleanup) -> runCleanup(owner, key, cleanup) }
        onChanged(owner)
    }

    private fun runCleanup(owner: UUID, key: String, cleanup: () -> Unit) {
        try { cleanup() } catch (error: Exception) { onFailure(owner, key, error) }
    }
}
