package org.ewsk.spectrummobs.config

import org.ewsk.spectrummobs.core.ConfigurationDiagnostic
import org.ewsk.spectrummobs.core.DiagnosticLevel
import org.ewsk.spectrummobs.core.MobConfigSnapshot
import org.ewsk.spectrummobs.core.MobDefinitionCandidate
import org.ewsk.spectrummobs.core.MobDefinition
import org.ewsk.spectrummobs.core.MobDefinitionParseResult
import org.ewsk.spectrummobs.core.MobDefinitionParser
import org.ewsk.spectrummobs.core.MobSnapshotBuildResult
import org.ewsk.spectrummobs.core.MobSnapshotBuilder
import taboolib.library.configuration.ConfigurationSection
import taboolib.module.configuration.Configuration
import java.io.File
import java.util.concurrent.atomic.AtomicReference

sealed interface MobConfigReloadResult {
    data class Success(
        val snapshot: MobConfigSnapshot,
        val diagnostics: List<ConfigurationDiagnostic> = emptyList()
    ) : MobConfigReloadResult

    data class Failure(
        val diagnostics: List<ConfigurationDiagnostic>
    ) : MobConfigReloadResult
}

/**
 * 负责扫描 mobs 目录并将本次重载中合法配置整体替换为新快照。
 * 单个文件出错时会保留其他合法文件，并通过结果诊断报告错误。
 */
class MobConfigRepository(
    private val directory: File,
    private val strict: Boolean = true,
    private val fileReader: (File) -> Map<String, Any?> = { file ->
        Configuration.loadFromFile(file).toPlainMap()
    }
) {
    private val currentSnapshot = AtomicReference(MobConfigSnapshot.empty())

    fun current(): MobConfigSnapshot = currentSnapshot.get()

    fun reload(
        validateDefinition: (MobDefinition) -> List<ConfigurationDiagnostic> = { emptyList() }
    ): MobConfigReloadResult {
        directory.mkdirs()
        val diagnostics = mutableListOf<ConfigurationDiagnostic>()
        val candidates = mutableListOf<MobDefinitionCandidate>()

        scanFiles().forEach { file ->
            val values = runCatching { fileReader(file) }.getOrElse { error ->
                diagnostics += ConfigurationDiagnostic(
                    source = file.name,
                    path = "",
                    message = "Unable to read YAML: ${error.message ?: error.javaClass.simpleName}"
                )
                return@forEach
            }

            val result = MobDefinitionParser(strict).parse(values, file.name)
            when (result) {
                is MobDefinitionParseResult.Success -> {
                    diagnostics += result.diagnostics
                    val validation = runCatching { validateDefinition(result.definition) }.getOrElse { error ->
                        listOf(
                            ConfigurationDiagnostic(
                                source = file.name,
                                path = "",
                                message = "Unable to validate configuration references: ${error.message ?: error.javaClass.simpleName}"
                            )
                        )
                    }
                    diagnostics += validation
                    if (validation.none { it.level == DiagnosticLevel.ERROR }) {
                        candidates += MobDefinitionCandidate(file.name, result.definition)
                    }
                }
                is MobDefinitionParseResult.Failure -> diagnostics += result.diagnostics
            }
        }

        val uniqueCandidates = candidates
            .groupBy { it.definition.id }
            .values
            .map { sameId ->
                sameId.drop(1).forEach { duplicate ->
                    diagnostics += ConfigurationDiagnostic(
                        source = duplicate.source,
                        path = "id",
                        message = "Resource ID ${duplicate.definition.id} is defined more than once"
                    )
                }
                sameId.first()
            }

        if (uniqueCandidates.isEmpty() && diagnostics.any { it.level == DiagnosticLevel.ERROR }) {
            return MobConfigReloadResult.Failure(diagnostics)
        }

        return when (val result = MobSnapshotBuilder.build(uniqueCandidates)) {
            is MobSnapshotBuildResult.Success -> {
                currentSnapshot.set(result.snapshot)
                MobConfigReloadResult.Success(result.snapshot, diagnostics + result.diagnostics)
            }
            is MobSnapshotBuildResult.Failure -> MobConfigReloadResult.Failure(diagnostics + result.diagnostics)
        }
    }

    private fun scanFiles(): List<File> {
        if (!directory.exists()) return emptyList()
        return directory.walkTopDown()
            .filter { it.isFile && (it.extension.equals("yml", true) || it.extension.equals("yaml", true)) }
            .sortedBy { it.relativeTo(directory).path.lowercase() }
            .toList()
    }
}

private fun Configuration.toPlainMap(): Map<String, Any?> = valuesToPlain(getValues(false))

private fun valuesToPlain(values: Map<String, Any?>): Map<String, Any?> = values.mapValues { (_, value) -> valueToPlain(value) }

private fun valueToPlain(value: Any?): Any? = when (value) {
    is ConfigurationSection -> valuesToPlain(value.getValues(false))
    is Map<*, *> -> value.entries.associate { (key, item) -> key.toString() to valueToPlain(item) }
    is List<*> -> value.map(::valueToPlain)
    else -> value
}
