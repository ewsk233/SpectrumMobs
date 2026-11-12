package org.ewsk.spectrummobs.script

/** 目标查询的统一语义；平台只提供当前加载的候选实体。 */
object RuntimeTargeting {
    fun within(
        candidates: Sequence<RuntimeValue.EntityValue>,
        center: RuntimeValue.PositionValue,
        radius: Double,
        limit: Int
    ): List<RuntimeValue.EntityValue> {
        require(radius.isFinite() && radius >= 0.0) { "Target query radius must be finite and non-negative" }
        require(limit > 0) { "Target query limit must be positive" }
        return candidates.distinctBy { it.id }
            .filter { it.position?.world == center.world }
            .mapNotNull { entity -> entity.position?.let { entity to distanceSquared(center, it) } }
            .filter { (_, distance) -> distance <= radius * radius }
            .sortedWith(compareBy<Pair<RuntimeValue.EntityValue, Double>> { it.second }.thenBy { it.first.id })
            .take(limit)
            .map { it.first }
            .toList()
    }

    fun distanceSquared(first: RuntimeValue.PositionValue, second: RuntimeValue.PositionValue): Double {
        if (first.world != second.world) return Double.POSITIVE_INFINITY
        val x = first.x - second.x
        val y = first.y - second.y
        val z = first.z - second.z
        return x * x + y * y + z * z
    }
}
