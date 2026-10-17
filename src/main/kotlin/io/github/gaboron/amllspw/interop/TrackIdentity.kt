package io.github.gaboron.amllspw.interop

import java.nio.file.Path
import java.util.Locale

/** Both identifiers must agree when IDs are available; the path is always required. */
data class TrackIdentity(val id: String, val path: String) {
    fun matches(other: TrackIdentity): Boolean {
        if (id.isNotBlank() && other.id.isNotBlank() && id != other.id) return false
        if (path.isBlank() || other.path.isBlank()) return false
        return normalized(path) == normalized(other.path)
    }
    private fun normalized(value: String): String = runCatching {
        val result = Path.of(value).toAbsolutePath().normalize().toString()
        if (System.getProperty("os.name").startsWith("Windows")) result.lowercase(Locale.ROOT) else result
    }.getOrDefault(value)
}
