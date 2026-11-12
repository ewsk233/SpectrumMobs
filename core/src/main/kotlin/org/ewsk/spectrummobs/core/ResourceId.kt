package org.ewsk.spectrummobs.core

private val RESOURCE_ID_PATTERN = Regex("[a-z0-9_.-]+:[a-z0-9_./-]+")

class ResourceId private constructor(
    val namespace: String,
    val path: String
) {
    override fun toString(): String = "$namespace:$path"

    override fun equals(other: Any?): Boolean =
        other is ResourceId && namespace == other.namespace && path == other.path

    override fun hashCode(): Int = 31 * namespace.hashCode() + path.hashCode()

    companion object {
        fun parse(raw: String): ResourceId? {
            val normalized = raw.trim().lowercase()
            if (!RESOURCE_ID_PATTERN.matches(normalized)) return null
            val separator = normalized.indexOf(':')
            return ResourceId(
                namespace = normalized.substring(0, separator),
                path = normalized.substring(separator + 1)
            )
        }
    }
}
