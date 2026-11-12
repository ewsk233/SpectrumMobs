package org.ewsk.spectrummobs.script

sealed interface ScriptType {
    val displayName: String

    data class Service(val path: String) : ScriptType {
        override val displayName: String = path
    }

    data object Number : ScriptType {
        override val displayName: String = "Number"
    }

    data object Numeric : ScriptType {
        override val displayName: String = "Number or Percent"
    }

    data object Text : ScriptType {
        override val displayName: kotlin.String = "String"
    }

    data object Boolean : ScriptType {
        override val displayName: String = "Boolean"
    }

    data object Duration : ScriptType {
        override val displayName: String = "Duration"
    }

    data object Percentage : ScriptType {
        override val displayName: String = "Percent"
    }

    data object Entity : ScriptType {
        override val displayName: String = "Entity"
    }

    data object Player : ScriptType {
        override val displayName: String = "Player"
    }

    data object Position : ScriptType {
        override val displayName: String = "Position"
    }

    data object Particle : ScriptType {
        override val displayName: String = "Particle"
    }

    data object Health : ScriptType {
        override val displayName: String = "Health"
    }

    data class TypeParameter(val name: String, val bound: ScriptType) : ScriptType {
        override val displayName: String = name
    }

    data object EventContext : ScriptType {
        override val displayName: String = "Event"
    }

    data object Unit : ScriptType {
        override val displayName: String = "Unit"
    }

    data object Null : ScriptType {
        override val displayName: String = "Null"
    }

    data object Unknown : ScriptType {
        override val displayName: String = "Unknown"
    }

    data class ListType(val elementType: ScriptType) : ScriptType {
        override val displayName: String = "List<${elementType.displayName}>"
    }

    data class Function(
        val parameters: List<FunctionParameter>,
        val returnType: ScriptType,
        val safeInHeaders: kotlin.Boolean = false,
        val allowExtraNamed: kotlin.Boolean = false
    ) : ScriptType {
        override val displayName: String = "Function"
    }
}

data class FunctionParameter(
    val name: String,
    val type: ScriptType,
    val required: Boolean = true
)
