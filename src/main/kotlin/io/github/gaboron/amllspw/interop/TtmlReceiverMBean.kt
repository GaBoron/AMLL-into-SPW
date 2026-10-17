package io.github.gaboron.amllspw.interop

/** Protocol 1 uses only bootstrap JVM types so PF4J plugins need no shared class dependency. */
interface TtmlReceiverMBean {
    fun getProtocolVersion(): Int
    fun isActive(): Boolean
    fun submitTtml(trackId: String, audioPath: String, ttml: String, offsetMs: Long): Boolean
    fun clearTtml(trackId: String, audioPath: String): Boolean
}
