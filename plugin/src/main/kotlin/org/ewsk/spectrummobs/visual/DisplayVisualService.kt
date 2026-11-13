package org.ewsk.spectrummobs.visual

import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.entity.Entity
import org.bukkit.NamespacedKey
import org.bukkit.persistence.PersistentDataType
import org.ewsk.spectrummobs.integration.MobDeathContext
import org.ewsk.spectrummobs.integration.MobModelIntegration
import org.ewsk.spectrummobs.integration.MobSpawnContext
import org.ewsk.spectrummobs.core.MobModel
import org.ewsk.spectrummobs.runtime.ParticleAdapter
import java.util.UUID
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 通用 Display 视觉提供方。它只读取 provider/id/options，不包含任何 Boss ID。
 * 旧版本没有 TextDisplay 时自动退化为无视觉，不影响战斗实体。
 */
class DisplayVisualService : MobModelIntegration {
    override val id: String = "spectrum-display-visual"

    private data class ActiveVisual(
        val ownerId: UUID,
        val worldId: UUID,
        val glyphs: List<Entity>,
        val core: Entity?,
        val radius: Double,
        val height: Double,
        val speed: Double,
        val rotationTag: String?,
        val particle: Particle?,
        val particleEvery: Long
    )

    private val active = linkedMapOf<UUID, ActiveVisual>()
    private val visualOwnerKey = NamespacedKey("spectrummobs", "visual_owner")
    private val textDisplayClass: Class<*>? = runCatching { Class.forName("org.bukkit.entity.TextDisplay") }.getOrNull()

    override fun supports(model: MobModel): Boolean =
        model.provider.equals("display", true) || model.provider.equals("text-display", true)

    override fun attach(context: MobSpawnContext, model: MobModel) {
        if (textDisplayClass == null) return
        detachOwner(context.entity.uniqueId)
        val options = model.options
        val count = options["count"]?.toIntOrNull()?.coerceIn(1, 96) ?: 24
        val glyphs = options["glyphs"]?.split('|')?.filter(String::isNotEmpty).orEmpty()
            .ifEmpty { listOf("✦", "✧", "❖", "◇", "✺", "⋆") }
        val radius = options["radius"]?.toDoubleOrNull()?.coerceIn(0.1, 16.0) ?: 2.4
        val height = options["height"]?.toDoubleOrNull()?.coerceIn(0.0, 32.0) ?: 1.8
        val speed = options["rotation"]?.toDoubleOrNull()?.coerceIn(-1.0, 1.0) ?: 0.035
        val rotationTag = options["rotation-tag"]?.trim()?.takeIf { it.isNotEmpty() }
        val particle = options["particle"]?.let { runCatching { ParticleAdapter.resolve(it) }.getOrNull() }
        val particleEvery = options["particle-every"]?.toLongOrNull()?.coerceAtLeast(1L) ?: 2L
        val spawned = mutableListOf<Entity>()
        repeat(count) { index ->
            createText(context.entity.location, legacy(glyphs[index % glyphs.size]), options, context.entity.uniqueId)?.let(spawned::add)
        }
        val core = createText(context.entity.location.clone().add(0.0, height, 0.0), legacy(options["core"] ?: "✦"), options, context.entity.uniqueId)
        active[context.entity.uniqueId] = ActiveVisual(context.entity.uniqueId, context.entity.world.uid, spawned, core, radius, height, speed, rotationTag, particle, particleEvery)
    }

    override fun onDeath(context: MobDeathContext) {
        detachOwner(context.entity.uniqueId)
    }

    fun reattach(context: MobSpawnContext) {
        context.definition.model?.takeIf(::supports)?.let { attach(context, it) }
    }

    fun tick(currentTick: Long) {
        active.values.toList().forEach { visual ->
            val owner = Bukkit.getEntity(visual.ownerId)
            if (owner == null || !owner.isValid || owner.isDead) {
                detachOwner(visual.ownerId)
                return@forEach
            }
            val center = owner.location.clone().add(0.0, visual.height, 0.0)
            val speed = if (visual.rotationTag != null && owner.scoreboardTags.contains(visual.rotationTag)) {
                kotlin.math.abs(visual.speed)
            } else if (visual.rotationTag != null) {
                -kotlin.math.abs(visual.speed)
            } else visual.speed
            visual.glyphs.forEachIndexed { index, display ->
                if (!display.isValid) return@forEachIndexed
                val angle = currentTick * speed + index * (PI * 2.0 / visual.glyphs.size)
                val ring = when {
                    index % 3 == 0 -> visual.radius
                    index % 3 == 1 -> visual.radius * 0.68
                    else -> visual.radius * 1.22
                }
                val location = center.clone().add(cos(angle) * ring, sin(angle * 1.7) * 0.35, sin(angle) * ring)
                display.teleport(location)
            }
            visual.core?.takeIf(Entity::isValid)?.teleport(center)
            if (visual.particle != null && currentTick % visual.particleEvery == 0L) {
                owner.world.spawnParticle(visual.particle, center, 4, visual.radius * 0.2, visual.radius * 0.2, visual.radius * 0.2, 0.0)
            }
        }
    }

    fun detachOwner(ownerId: UUID) {
        val visual = active.remove(ownerId) ?: return
        visual.glyphs.forEach(Entity::remove)
        visual.core?.remove()
    }

    fun detachWorld(worldId: UUID) {
        active.values.filter { it.worldId == worldId }.map { it.ownerId }.forEach(::detachOwner)
    }

    fun clear() {
        active.keys.toList().forEach(::detachOwner)
    }

    fun cleanupOrphans(entities: Iterable<Entity>) {
        entities.forEach { entity ->
            val owner = entity.persistentDataContainer.get(visualOwnerKey, PersistentDataType.STRING) ?: return@forEach
            val visual = runCatching { UUID.fromString(owner) }.getOrNull()?.let(active::get)
            if (visual == null || (visual.glyphs.none { it.uniqueId == entity.uniqueId } && visual.core?.uniqueId != entity.uniqueId)) {
                entity.remove()
            }
        }
    }

    private fun createText(location: Location, text: String, options: Map<String, String>, owner: UUID): Entity? {
        val type = textDisplayClass ?: return null
        val world = location.world ?: return null
        val spawn = world.javaClass.methods.firstOrNull {
            it.name == "spawn" && it.parameterTypes.size == 2 && it.parameterTypes[0] == Location::class.java && it.parameterTypes[1] == Class::class.java
        } ?: return null
        val display = runCatching { spawn.invoke(world, location, type) as? Entity }.getOrNull() ?: return null
        display.isPersistent = false
        display.persistentDataContainer.set(visualOwnerKey, PersistentDataType.STRING, owner.toString())
        invoke(display, "setText", text)
        invoke(display, "setSeeThrough", options["see-through"]?.toBoolean() ?: true)
        invoke(display, "setShadowed", options["shadowed"]?.toBoolean() ?: true)
        invoke(display, "setDefaultBackground", false)
        invoke(display, "setViewRange", options["view-range"]?.toFloatOrNull() ?: 64f)
        invoke(display, "setInterpolationDuration", options["interpolation"]?.toIntOrNull()?.coerceIn(0, 10) ?: 2)
        invoke(display, "setBillboard", enumValue("org.bukkit.entity.Display\$Billboard", options["billboard"] ?: "CENTER"))
        invoke(display, "setGlowing", options["glowing"]?.toBoolean() ?: false)
        return display
    }

    private fun invoke(target: Any, name: String, vararg arguments: Any?) {
        val method = target.javaClass.methods.firstOrNull { it.name == name && it.parameterTypes.size == arguments.size } ?: return
        runCatching { method.invoke(target, *arguments) }
    }

    private fun enumValue(className: String, value: String): Any? = runCatching {
        @Suppress("UNCHECKED_CAST")
        java.lang.Enum.valueOf(Class.forName(className) as Class<out Enum<*>>, value.uppercase())
    }.getOrNull()

    private fun legacy(text: String): String = ChatColor.translateAlternateColorCodes('&', text)
}
