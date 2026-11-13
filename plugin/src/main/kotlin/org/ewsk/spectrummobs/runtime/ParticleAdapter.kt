package org.ewsk.spectrummobs.runtime

import org.bukkit.Particle

/** Bukkit 粒子重命名集中在平台适配层，脚本使用稳定名称。 */
object ParticleAdapter {
    fun resolve(name: String): Particle {
        val normalized = name.uppercase()
        val names = when (normalized) {
            "ENCHANT", "ENCHANTMENT_TABLE" -> listOf("ENCHANT", "ENCHANTMENT_TABLE")
            "WITCH", "SPELL_WITCH" -> listOf("WITCH", "SPELL_WITCH")
            else -> listOf(normalized)
        }
        return names.firstNotNullOfOrNull { candidate -> Particle.values().firstOrNull { it.name == candidate } }
            ?: throw IllegalArgumentException("Particle is not supported by this server: $name")
    }
}
