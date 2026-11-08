package io.github.gaboron.amllspw.interop

import java.nio.file.Path
import java.util.Locale

/** Local tracks require matching paths; external identities carry their playback session in the ID. */
data class TrackIdentity(val id: String, val path: String) {
    fun matches(other: TrackIdentity): Boolean {
        if (id.startsWith("external:") || other.id.startsWith("external:")) return id == other.id &&
            path.isEmpty() && other.path.isEmpty()
        if (id.isNotBlank() && other.id.isNotBlank() && id != other.id) return false
        if (path.isBlank() || other.path.isBlank()) return false
        return normalized(path) == normalized(other.path)
    }
    private fun normalized(value: String): String = runCatching {
        val result = Path.of(value).toAbsolutePath().normalize().toString()
        if (System.getProperty("os.name").startsWith("Windows")) result.lowercase(Locale.ROOT) else result
    }.getOrDefault(value)
}
