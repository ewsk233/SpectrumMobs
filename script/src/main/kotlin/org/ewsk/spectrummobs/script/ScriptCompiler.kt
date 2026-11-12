package org.ewsk.spectrummobs.script

sealed interface CompilationResult {
    data class Success(val script: TypedScriptFile) : CompilationResult
    data class Failure(val diagnostics: List<ScriptDiagnostic>) : CompilationResult
}

class ScriptCompiler(
    private val apiRegistry: ScriptApiRegistry = ScriptApiRegistry.default(),
    private val ruleFunctions: Map<String, ScriptFunctionSignature> = emptyMap(),
    private val ruleAliases: Map<String, String> = emptyMap()
) {
    fun parse(source: String, sourceName: String = "<memory>"): ParseResult {
        val lexResult = ScriptLexer(source, sourceName).lex()
        if (lexResult.diagnostics.isNotEmpty()) return ParseResult(null, lexResult.diagnostics)
        return ScriptParser(lexResult.tokens, sourceName).parse()
    }

    fun compile(source: String, sourceName: String = "<memory>"): CompilationResult {
        if (source.isBlank()) {
            return CompilationResult.Failure(
                listOf(ScriptDiagnostic(sourceName, SourceLocation(0, 1, 1), "Script source must not be blank"))
            )
        }

        val lexResult = ScriptLexer(source, sourceName).lex()
        if (lexResult.diagnostics.isNotEmpty()) {
            return CompilationResult.Failure(lexResult.diagnostics)
        }

        val parseResult = ScriptParser(lexResult.tokens, sourceName).parse()
        if (parseResult.diagnostics.isNotEmpty()) {
            return CompilationResult.Failure(parseResult.diagnostics)
        }

        return when (val typeResult = TypeChecker(sourceName, apiRegistry, ruleFunctions, ruleAliases).check(parseResult.script!!)) {
            is TypeCheckResult.Success -> CompilationResult.Success(typeResult.script)
            is TypeCheckResult.Failure -> CompilationResult.Failure(typeResult.diagnostics)
        }
    }
}
