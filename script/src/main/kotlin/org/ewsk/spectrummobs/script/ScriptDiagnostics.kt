package org.ewsk.spectrummobs.script

data class SourceLocation(
    val offset: Int,
    val line: Int,
    val column: Int
)

data class ScriptDiagnostic(
    val source: String,
    val location: SourceLocation,
    val message: String
) {
    override fun toString(): String = "$source:${location.line}:${location.column}: $message"
}
