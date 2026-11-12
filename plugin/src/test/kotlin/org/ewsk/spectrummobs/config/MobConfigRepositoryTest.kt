package org.ewsk.spectrummobs.config

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class MobConfigRepositoryTest {
    @Test
    fun invalidReloadKeepsPreviousSnapshot() {
        val directory = Files.createTempDirectory("spectrum-mobs-").toFile()
        try {
            val file = directory.resolve("ember.yml")
            file.writeText(validMobYaml)

            val repository = MobConfigRepository(directory, fileReader = { source ->
                if (source.readText().contains("unexpected")) {
                    mapOf("schema-version" to 1, "id" to "invalid", "type" to "ZOMBIE", "unexpected" to true)
                } else {
                    mapOf(
                        "schema-version" to 1,
                        "id" to "spectrum:test_mob",
                        "type" to "ZOMBIE",
                        "display" to mapOf("name" to "测试怪物"),
                        "attributes" to mapOf("max-health" to 40.0),
                        "events" to mapOf("damaged" to listOf("spectrum:test_skill"))
                    )
                }
            })
            val firstResult = repository.reload()
            val first = assertIs<MobConfigReloadResult.Success>(firstResult)
            assertEquals(1, first.snapshot.definitions.size)

            file.writeText(invalidMobYaml)
            assertIs<MobConfigReloadResult.Failure>(repository.reload())
            assertEquals(first.snapshot, repository.current())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun invalidMobDoesNotBlockOtherMobs() {
        val directory = Files.createTempDirectory("spectrum-partial-mobs-").toFile()
        try {
            directory.resolve("valid.yml").writeText("valid")
            directory.resolve("invalid.yml").writeText("invalid")
            val repository = MobConfigRepository(directory, fileReader = { file ->
                if (file.name == "valid.yml") {
                    mapOf("schema-version" to 1, "id" to "spectrum:valid", "type" to "ZOMBIE")
                } else {
                    mapOf("schema-version" to 1, "id" to "invalid", "type" to "ZOMBIE", "bad" to true)
                }
            })

            val result = assertIs<MobConfigReloadResult.Success>(repository.reload())
            assertEquals(setOf("spectrum:valid"), result.snapshot.definitions.keys.map { it.toString() }.toSet())
            assertEquals(2, result.diagnostics.size)
        } finally {
            directory.deleteRecursively()
        }
    }

    private companion object {
        const val validMobYaml = """
schema-version: 1
id: spectrum:test_mob
type: ZOMBIE
display:
  name: "测试怪物"
attributes:
  max-health: 40.0
events:
  damaged:
    - spectrum:test_skill
"""

        const val invalidMobYaml = """
schema-version: 1
id: invalid
type: ZOMBIE
unexpected: true
"""
    }
}
