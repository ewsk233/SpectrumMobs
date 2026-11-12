package org.ewsk.spectrummobs.skill

import org.ewsk.spectrummobs.core.ResourceId
import org.ewsk.spectrummobs.script.CompilationResult
import org.ewsk.spectrummobs.script.FunctionParameter
import org.ewsk.spectrummobs.script.RuleFunctionDeclaration
import org.ewsk.spectrummobs.script.ScriptApiKind
import org.ewsk.spectrummobs.script.ScriptApiRegistry
import org.ewsk.spectrummobs.script.ScriptCompiler
import org.ewsk.spectrummobs.script.ScriptDiagnostic
import org.ewsk.spectrummobs.script.ScriptFunctionSignature
import org.ewsk.spectrummobs.script.ScriptType
import org.ewsk.spectrummobs.script.TypeName
import org.ewsk.spectrummobs.script.TypedRuleFunction
import org.ewsk.spectrummobs.script.TypedSkill
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference

data class SkillSnapshot(
    val skills: Map<ResourceId, TypedSkill>,
    val rulesBySkill: Map<ResourceId, Map<String, TypedRuleFunction>> = emptyMap(),
    /** 所有通过校验的规则函数，供伤害、目标和掉落计算器调用。 */
    val rules: Map<String, TypedRuleFunction> = emptyMap()
) {
    fun find(rawId: String): TypedSkill? = ResourceId.parse(rawId)?.let(skills::get)

    companion object { fun empty(): SkillSnapshot = SkillSnapshot(emptyMap()) }
}

sealed interface SkillReloadResult {
    data class Success(val snapshot: SkillSnapshot, val diagnostics: List<ScriptDiagnostic> = emptyList()) : SkillReloadResult
    data class Failure(val diagnostics: List<ScriptDiagnostic>) : SkillReloadResult
}

private data class SourceEntry(
    val sourceName: String,
    val resourceId: ResourceId,
    val source: String,
    val parsed: org.ewsk.spectrummobs.script.ScriptFile
)

class SkillFileRepository(
    private val directory: File,
    private val defaultNamespace: String = "spectrum"
) {
    private val currentSnapshot = AtomicReference(SkillSnapshot.empty())
    fun current(): SkillSnapshot = currentSnapshot.get()

    fun reload(apiRegistry: ScriptApiRegistry = ScriptApiRegistry.default()): SkillReloadResult {
        directory.mkdirs()
        val diagnostics = mutableListOf<ScriptDiagnostic>()
        val sources = scanFiles().mapNotNull { file ->
            val sourceName = file.relativeTo(directory).invariantSeparatorsPath
            val resourceId = resourceIdOf(file, sourceName)
            if (resourceId == null) { diagnostics += diagnostic(sourceName, "Unable to derive a valid skill module ID from the file path"); return@mapNotNull null }
            val source = runCatching { file.readText(StandardCharsets.UTF_8) }.getOrElse { error ->
                diagnostics += diagnostic(sourceName, "Unable to read skill file: ${error.message ?: error.javaClass.simpleName}"); return@mapNotNull null
            }
            val parsed = ScriptCompiler(apiRegistry).parse(source, sourceName)
            if (parsed.diagnostics.isNotEmpty()) { diagnostics += parsed.diagnostics; return@mapNotNull null }
            SourceEntry(sourceName, resourceId, source, parsed.script!!)
        }

        val moduleSignatures = sources.flatMap { source ->
            source.parsed.declarations.filterIsInstance<RuleFunctionDeclaration>().mapNotNull { function ->
                val returnType = resolveType(function.returnType) ?: run {
                    diagnostics += diagnostic(source.sourceName, "Unknown return type in rule function ${function.name}"); return@mapNotNull null
                }
                val parameters = function.parameters.mapNotNull { parameter ->
                    val type = resolveType(parameter.typeName) ?: run {
                        diagnostics += diagnostic(source.sourceName, "Unknown parameter type in rule function ${function.name}.${parameter.name}"); return@mapNotNull null
                    }
                    FunctionParameter(parameter.name, type, parameter.defaultValue == null)
                }
                "${source.resourceId}.${function.name}" to ScriptFunctionSignature(parameters, returnType, ScriptApiKind.SERVICE, true)
            }
        }.toMap()

        val compiledSkills = linkedMapOf<ResourceId, TypedSkill>()
        val rulesBySkill = linkedMapOf<ResourceId, Map<String, TypedRuleFunction>>()
        val compiledRules = linkedMapOf<String, TypedRuleFunction>()
        val importsBySkill = linkedMapOf<ResourceId, List<org.ewsk.spectrummobs.script.ImportDeclaration>>()
        val importsByModule = sources.associate { it.resourceId.toString() to it.parsed.imports }
        val failedModules = linkedSetOf<ResourceId>()
        val moduleGraph = sources.associate { it.resourceId.toString() to it.parsed.imports.map { import -> import.moduleId } }
        val cyclicModules = detectCycles(moduleGraph)
        cyclicModules.forEach { module -> diagnostics += diagnostic(module, "Circular rule module import is not allowed") }

        sources.forEach { source ->
            if (source.resourceId.toString() in cyclicModules) {
                failedModules += source.resourceId
                return@forEach
            }
            val aliases = source.parsed.imports.associate { it.alias to it.moduleId }
            val available = linkedMapOf<String, ScriptFunctionSignature>()
            source.parsed.imports.forEach { import ->
                moduleSignatures.filterKeys { it.startsWith("${import.moduleId}.") }.forEach { (key, signature) ->
                    available[key.replaceFirst("${import.moduleId}.", "${import.alias}.")] = signature
                }
            }
            val compiler = ScriptCompiler(apiRegistry, available, aliases)
            when (val result = compiler.compile(source.source, source.sourceName)) {
                is CompilationResult.Failure -> {
                    diagnostics += result.diagnostics
                    failedModules += source.resourceId
                }
                is CompilationResult.Success -> {
                    result.script.rules.forEach { rule ->
                        val canonical = "${source.resourceId}.${rule.qualifiedName.substringAfterLast('.') }"
                        compiledRules[canonical] = rule.copy(qualifiedName = canonical)
                    }
                    val skill = result.script.skills.singleOrNull()
                    if (result.script.skills.size > 1) diagnostics += diagnostic(source.sourceName, "Each skill file must declare at most one skill")
                    if (skill != null) {
                        val id = ResourceId.parse("${source.resourceId.namespace}:${source.resourceId.path}") ?: source.resourceId
                        compiledSkills[id] = skill.copy(id = id.toString())
                        importsBySkill[id] = source.parsed.imports
                    } else if (result.script.rules.isEmpty()) {
                        diagnostics += diagnostic(source.sourceName, "Module must declare one skill or at least one rule function")
                    }
                }
            }
        }

        importsBySkill.forEach { (id, imports) ->
            val failedDependency = imports.firstOrNull { import -> failedModules.any { it.toString() == import.moduleId } }
            if (failedDependency != null) {
                compiledSkills.remove(id)
                diagnostics += diagnostic(id.toString(), "Skill depends on invalid rule module: ${failedDependency.moduleId}")
                return@forEach
            }
            val importedRules = linkedMapOf<String, TypedRuleFunction>()
            val visited = linkedSetOf<String>()
            fun collect(moduleId: String, alias: String) {
                val visitKey = "$moduleId|$alias"
                if (!visited.add(visitKey)) return
                compiledRules.filterKeys { it.startsWith("$moduleId.") }.forEach { (key, rule) ->
                    val aliasName = key.replaceFirst("$moduleId.", "$alias.")
                    importedRules[aliasName] = rule.copy(qualifiedName = aliasName)
                }
                importsByModule[moduleId].orEmpty().forEach { nested -> collect(nested.moduleId, nested.alias) }
            }
            imports.forEach { import -> collect(import.moduleId, import.alias) }
            rulesBySkill[id] = importedRules
        }

        return if (compiledSkills.isEmpty() && diagnostics.isNotEmpty()) SkillReloadResult.Failure(diagnostics) else {
            val snapshot = SkillSnapshot(compiledSkills.toMap(), rulesBySkill.toMap(), compiledRules.toMap())
            currentSnapshot.set(snapshot)
            SkillReloadResult.Success(snapshot, diagnostics)
        }
    }

    private fun resolveType(type: TypeName): ScriptType? = when (type) {
        is TypeName.Simple -> when (type.name) {
            "Number" -> ScriptType.Number
            "String", "Text" -> ScriptType.Text
            "Boolean" -> ScriptType.Boolean
            "Duration" -> ScriptType.Duration
            "Percent", "Percentage" -> ScriptType.Percentage
            "Entity" -> ScriptType.Entity
            "Player" -> ScriptType.Player
            "Position" -> ScriptType.Position
            "Particle" -> ScriptType.Particle
            "Unit" -> ScriptType.Unit
            else -> null
        }
        is TypeName.ListOf -> resolveType(type.element)?.let(ScriptType::ListType)
    }

    private fun diagnostic(source: String, message: String) = ScriptDiagnostic(source, org.ewsk.spectrummobs.script.SourceLocation(0, 1, 1), message)

    private fun detectCycles(graph: Map<String, List<String>>): Set<String> {
        val visiting = linkedSetOf<String>()
        val visited = linkedSetOf<String>()
        val cyclic = linkedSetOf<String>()
        fun visit(node: String, path: List<String>) {
            if (node in visiting) {
                val start = path.indexOf(node).takeIf { it >= 0 } ?: 0
                cyclic += path.drop(start)
                return
            }
            if (!visited.add(node)) return
            visiting += node
            graph[node].orEmpty().filter { it in graph }.forEach { visit(it, path + it) }
            visiting -= node
        }
        graph.keys.forEach { visit(it, listOf(it)) }
        return cyclic
    }
    private fun scanFiles(): List<File> = if (!directory.exists()) emptyList() else directory.walkTopDown().filter { it.isFile && it.extension.equals("sms", true) }.sortedBy { it.relativeTo(directory).invariantSeparatorsPath.lowercase() }.toList()
    private fun resourceIdOf(file: File, relativePath: String): ResourceId? {
        val path = relativePath.removeSuffix(".${file.extension}")
        val segments = path.split('/').filter { it.isNotBlank() }
        if (segments.isEmpty()) return null
        val namespace = if (segments.size > 1) segments.first() else defaultNamespace
        val resourcePath = if (segments.size > 1) segments.drop(1).joinToString("/") else segments.first()
        return ResourceId.parse("$namespace:$resourcePath")
    }
}
