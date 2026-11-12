package org.ewsk.spectrummobs.script

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ScriptCompilerTest {
    @Test
    fun blankSourceProducesDiagnostic() {
        assertIs<CompilationResult.Failure>(ScriptCompiler().compile("  "))
    }

    @Test
    fun parsesSkillWithWaitLoopAndNamedArguments() {
        val source = """
            skill flame_nova {
                cooldown = 8s
                requires caster.health.percent <= 50%

                let center = caster.position
                mechanic.particles.circle(at = center, particle = FLAME, radius = 5, count = 48)
                wait 30t

                for player in targeter.players.within(center, 5) {
                    mechanic.damage(player, 12)
                    mechanic.ignite(player, 3s)
                }
            }
        """.trimIndent()

        val result = assertIs<CompilationResult.Success>(ScriptCompiler().compile(source, "flame_nova.sms"))
        val skill = result.script.skills.single()
        assertEquals("flame_nova", skill.name)
        assertIs<IrLiteral>(skill.cooldown)
        assertIs<IrBinary>(skill.requires)
        assertEquals(4, skill.body.size)
        assertIs<IrWait>(skill.body[2])
        assertIs<IrFor>(skill.body[3])
    }

    @Test
    fun reportsUnknownCharactersWithLocation() {
        val result = ScriptCompiler().compile("skill test { @ }", "broken.sms")
        val failure = assertIs<CompilationResult.Failure>(result)
        assertTrue(failure.diagnostics.any { it.source == "broken.sms" && it.location.line == 1 })
    }

    @Test
    fun reportsTypeMismatchBeforeRuntime() {
        val result = ScriptCompiler().compile("skill broken {\n    wait 5\n}", "types.sms")
        val failure = assertIs<CompilationResult.Failure>(result)
        assertTrue(failure.diagnostics.any { it.message.contains("expected Duration") })
    }

    @Test
    fun acceptsTriggerContextTypes() {
        val result = ScriptCompiler().compile(
            "skill contextual {\n    requires event.damage > 0 && phase == \"enraged\"\n    mechanic.damage(target, event.damage)\n}",
            "context.sms"
        )
        assertIs<CompilationResult.Success>(result)
    }

    @Test
    fun acceptsFeedbackStateRepeatRandomAndEventFields() {
        val source = """
            skill feedback {
                requires (event.attacker == null || phase == "enraged") && condition.isPlayer(target) && condition.hasPotion(target, "SPEED")
                let text = "阶段 ${'$'}{phase}，伤害 ${'$'}{event.damage}"
                for player in targeter.players.within(caster.position, 5) {
                    mechanic.message.send(player, text)
                    mechanic.title.show(player, "&cBoss", text, 5t, 20t, 5t)
                    mechanic.actionbar.show(player, "&e${'$'}{event.cause}")
                    mechanic.sound.play(player, "entity.blaze.shoot", 1, 1)
                }
                state.increment("rageCount", 1)
                state bursts += 1
                skills.cast("spectrum:other", caster, target)
                command.raw("say ${'$'}{phase}")
                command.run("reward_player", target)
                let nearest = targeter.entities.nearest(caster.position, 8)
                for nearby in targeter.players.within(caster.position, 8) {
                    mechanic.entity.setHealth(nearby, 20)
                    mechanic.entity.teleport(nearby, caster.position)
                    mechanic.entity.velocity(nearby, 0, 1, 0)
                    mechanic.entity.potion(nearby, "SPEED", 5s, 1)
                    mechanic.bossbar.show(nearby, "&cBoss", 0.5, 5s)
                    mechanic.scoreboard.set(nearby, "boss_phase", 2)
                }
                mechanic.entity.remove(nearest)
                variables.increment("skill", "casts", 1)
                if (random.chance(25%)) {
                    repeat 2 {
                        state.increment("bursts", 1)
                    }
                }
            }
        """.trimIndent()

        val result = ScriptCompiler().compile(source, "feedback.sms")
        assertTrue(result is CompilationResult.Success, result.toString())
    }

    @Test
    fun acceptsMagmaProjectileLaunch() {
        val result = ScriptCompiler().compile(
            """
            skill magma_bolt {
                mechanic.projectile.launch(from = caster.position, to = target.position, material = "MAGMA_BLOCK", speed = 1.2, damage = 12, lifetime = 60t, hitRadius = 0.5, placeOnHit = true)
            }
            """.trimIndent(),
            "magma_bolt.sms"
        )
        assertTrue(result is CompilationResult.Success, result.toString())
    }

    @Test
    fun acceptsForwardProjectileLaunch() {
        val result = ScriptCompiler().compile(
            """
            skill forward_magma {
                mechanic.projectile.forward(material = "MAGMA_BLOCK", speed = 1.2, damage = 12, lifetime = 60t, hitRadius = 0.5, placeOnHit = false)
            }
            """.trimIndent(),
            "forward_magma.sms"
        )
        assertTrue(result is CompilationResult.Success, result.toString())
    }

    @Test
    fun acceptsMobSummonMechanic() {
        val result = ScriptCompiler().compile(
            """
            skill summon_minions {
                mechanic.mob.summon(id = "spectrum:magma_minion", at = caster.position, count = 3)
            }
            """.trimIndent(),
            "summon_minions.sms"
        )
        assertTrue(result is CompilationResult.Success, result.toString())
    }
}
