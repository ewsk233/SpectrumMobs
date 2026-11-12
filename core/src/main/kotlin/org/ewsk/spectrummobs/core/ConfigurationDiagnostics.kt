package org.ewsk.spectrummobs.core

enum class DiagnosticLevel {
    ERROR,
    WARNING
}

data class ConfigurationDiagnostic(
    val source: String,
    val path: String,
    val message: String,
    val level: DiagnosticLevel = DiagnosticLevel.ERROR
) {
    override fun toString(): String {
        val location = if (path.isBlank()) source else "$source:$path"
        return "[$level] $location - $message"
    }
}
