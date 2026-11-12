package org.ewsk.spectrummobs.script

import java.io.File
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ScriptDocumentationTest {
    private val docs = File(System.getProperty("spectrum.docs"))

    @Test
    fun completeSmsExamplesInChineseDocumentationCompile() {
        var count = 0
        val fence = Regex("```sms(?:-rule)?\\r?\\n([\\s\\S]*?)```")
        docs.listFiles()!!.filter { it.extension == "md" }.forEach { file ->
            fence.findAll(file.readText()).forEachIndexed { index, match ->
                val sourceName = "${file.name}#example-${index + 1}"
                val compiler = if (match.groupValues[1].contains("import \"spectrum:combat\"")) {
                    ScriptCompiler(
                        ruleFunctions = mapOf(
                            "combat.can_hit" to ScriptFunctionSignature(
                                listOf(FunctionParameter("victim", ScriptType.Entity)), ScriptType.Boolean, ScriptApiKind.SERVICE, true
                            ),
                            "combat.damage_for" to ScriptFunctionSignature(
                                listOf(FunctionParameter("source", ScriptType.Entity), FunctionParameter("base", ScriptType.Number)), ScriptType.Number, ScriptApiKind.SERVICE, true
                            )
                        ),
                        ruleAliases = mapOf("combat" to "spectrum:combat")
                    )
                } else ScriptCompiler()
                val result = compiler.compile(match.groupValues[1], sourceName)
                assertIs<CompilationResult.Success>(result, result.toString())
                count++
            }
        }
        assertTrue(count >= 3, "Complete examples must remain covered by compilation tests")
    }

    @Test
    fun everyRegisteredBuiltinHasAnApiReferenceEntry() {
        val reference = docs.resolve("SCRIPT_API.md").readText()
        ScriptApiRegistry.default().names.forEach { name ->
            assertTrue(reference.contains("$name(", ignoreCase = true), "Missing API documentation: $name")
        }
    }
}
