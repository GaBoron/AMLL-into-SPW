package io.github.gaboron.amllspw.host

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** Cached source-marker locations, never trusted Hook addresses or parameter layouts. */
internal data class NativeLyricsIndex(val players: List<String>, val containers: List<String>)

/** Every lookup is bound to all archive bytes, including changes that preserve file timestamps. */
internal class NativeLyricsIndexCache(private val directory: Path? = cacheDirectory()) {
    private var remembered: Pair<String, NativeLyricsIndex>? = null

    fun fingerprint(archive: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(archive).buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException("Host archive fingerprint interrupted")
                val size = input.read(buffer)
                if (size < 0) break
                digest.update(buffer, 0, size)
            }
        }
        return digest.digest().toHex()
    }

    fun read(fingerprint: String): NativeLyricsIndex? {
        remembered?.takeIf { it.first == fingerprint }?.let { return it.second }
        val root = directory ?: return null
        return runCatching {
            val file = root.resolve("$fingerprint.index")
            if (!Files.isRegularFile(file) || Files.size(file) > 128 * 1024) return null
            val lines = Files.readAllLines(file)
            require(lines.size >= 5 && lines[0] == FORMAT && lines[1] == fingerprint)
            val payload = lines.drop(3).joinToString("\n")
            require(checksum(payload) == lines[2])
            val players = mutableListOf<String>()
            val containers = mutableListOf<String>()
            for (line in lines.drop(3)) {
                val entry = line.drop(2)
                require(entry.endsWith(".class") && entry.length <= 1024)
                when {
                    line.startsWith("P\t") -> players += entry
                    line.startsWith("C\t") -> containers += entry
                    else -> error("Invalid source marker index")
                }
            }
            require(players.isNotEmpty() && containers.isNotEmpty())
            require(players.distinct().size == players.size && containers.distinct().size == containers.size)
            NativeLyricsIndex(players, containers).also { remembered = fingerprint to it }
        }.getOrNull()
    }

    fun write(fingerprint: String, index: NativeLyricsIndex) {
        remembered = fingerprint to index
        val root = directory ?: return
        // An interrupted/concurrent write fails the checksum and triggers a complete rescan.
        // Cache IO is optional; inability to save must not prevent a verified Hook installation.
        runCatching {
            val payload = (index.players.map { "P\t$it" } + index.containers.map { "C\t$it" }).joinToString("\n")
            Files.createDirectories(root)
            Files.writeString(root.resolve("$fingerprint.index"), "$FORMAT\n$fingerprint\n${checksum(payload)}\n$payload")
        }
    }

    private fun checksum(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).toHex()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 255) }

    companion object {
        private const val FORMAT = "AMLL native lyric source index 1"

        private fun cacheDirectory(): Path? = runCatching {
            val base = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true))
                System.getenv("LOCALAPPDATA")?.takeIf(String::isNotBlank)?.let(Path::of)
                    ?: Path.of(System.getProperty("user.home"), "AppData", "Local")
            else System.getenv("XDG_CACHE_HOME")?.takeIf(String::isNotBlank)?.let(Path::of)
                ?: Path.of(System.getProperty("user.home"), ".cache")
            base.resolve("amll-into-spw").resolve("native-lyrics")
        }.getOrNull()
    }
}
