package io.github.gaboron.amllspw.interop

import java.nio.file.Path
import java.net.URI
import java.util.Locale

/** File paths are normalized, while virtual tracks retain their source's exact URI or stable ID. */
data class TrackIdentity(val id: String, val path: String) {
    fun matches(other: TrackIdentity): Boolean {
        if ((id.startsWith("external:") && path.isEmpty()) ||
            (other.id.startsWith("external:") && other.path.isEmpty())) return id == other.id &&
            path.isEmpty() && other.path.isEmpty()
        if (id.isNotBlank() && other.id.isNotBlank() && id != other.id) return false
        if (path.isBlank() || other.path.isBlank()) return path.isBlank() && other.path.isBlank() &&
            id.isNotBlank() && id == other.id
        return normalized(path) == normalized(other.path)
    }
    private fun normalized(value: String): String {
        val path = localMediaPath(value) ?: return value
        return runCatching {
            val result = path.toAbsolutePath().normalize().toString()
            if (System.getProperty("os.name").startsWith("Windows")) result.lowercase(Locale.ROOT) else result
        }.getOrDefault(value)
    }
}

/** Network/custom URIs and absent paths must never become relative filesystem probes. */
internal fun localMediaPath(value: String): Path? {
    if (value.isBlank()) return null
    return runCatching {
        val uri = runCatching { URI(value) }.getOrNull()
        val scheme = URI_SCHEME.find(value)?.value?.dropLast(1)
        val windowsDrive = value.length >= 3 && value[0].isLetter() && value[1] == ':' &&
            (value[2] == '\\' || value[2] == '/')
        when {
            scheme.equals("file", ignoreCase = true) -> uri?.let(Path::of)
            scheme != null && !windowsDrive -> null
            else -> Path.of(value)
        }
    }.getOrNull()
}

private val URI_SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")
