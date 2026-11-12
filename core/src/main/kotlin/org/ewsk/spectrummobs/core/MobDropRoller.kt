package org.ewsk.spectrummobs.core

import java.util.Random

data class RolledMobDrop(
    val material: String,
    val amount: Int
)

/**
 * 只负责根据声明式掉落定义进行概率计算，不依赖 Bukkit。
 * 服务器层负责把结果转换成 ItemStack。
 */
object MobDropRoller {
    fun roll(drops: List<MobDrop>, random: Random = Random()): List<RolledMobDrop> =
        drops.mapNotNull { drop ->
            if (random.nextDouble() < drop.chance) {
                RolledMobDrop(drop.material, drop.amount)
            } else {
                null
            }
        }
}
