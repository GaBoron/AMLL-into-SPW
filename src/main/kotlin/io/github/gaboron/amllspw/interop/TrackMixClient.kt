package io.github.gaboron.amllspw.interop

import io.github.gaboron.amllspw.spectrum.SpectrumFrame
import java.lang.management.ManagementFactory
import javax.management.JMException
import javax.management.MBeanServer
import javax.management.ObjectName

/** Only Java platform types cross the two Workshop class loaders. */
internal class TrackMixClient(private val server: MBeanServer = ManagementFactory.getPlatformMBeanServer()) {
    fun available(): Boolean = server.isRegistered(NAME)

    fun snapshot(): Snapshot? = access {
        val state = server.getAttribute(NAME, "State") as? Map<*, *> ?: return@access null
        val levels = state["levels"] as? IntArray ?: return@access null
        if (levels.size != 6) return@access null
        Snapshot(
            title = state["title"] as? String ?: "",
            cached = state["cached"] == true,
            ready = state["ready"] == true,
            canSeparate = state["canSeparate"] == true,
            levels = levels.toList(),
            muted = state["muted"] as? Int ?: 0,
            solos = state["solos"] as? Int ?: 0,
            preset = state["preset"] as? Int ?: 0,
            original = state["original"] == true,
            busy = state["busy"] == true,
            stage = state["stage"] as? String ?: "",
            progress = state["progress"] as? Double ?: 0.0,
            notice = state["notice"] as? String ?: ""
        )
    }

    fun command(action: String, lane: Int = 0, value: Int = 0) {
        access { server.invoke(NAME, "command", arrayOf<Any>(action, lane, value), COMMAND_SIGNATURE) }
    }

    fun spectrum(path: String, positionMs: Long): SpectrumFrame? = access {
        val frame = server.invoke(NAME, "spectrum", arrayOf<Any>(path, positionMs), SPECTRUM_SIGNATURE)
            as? Array<*> ?: return@access null
        val rate = frame.getOrNull(0) as? Int ?: return@access null
        val low = frame.getOrNull(1) as? FloatArray ?: return@access null
        SpectrumFrame(low, rate, frame.getOrNull(2) as? FloatArray)
    }

    private inline fun <T> access(block: () -> T): T? {
        if (!available()) return null
        return try {
            block()
        } catch (_: JMException) {
            null
        } catch (_: javax.management.JMRuntimeException) {
            null
        }
    }

    data class Snapshot(val title: String, val cached: Boolean, val ready: Boolean, val canSeparate: Boolean,
        val levels: List<Int>, val muted: Int, val solos: Int, val preset: Int, val original: Boolean,
        val busy: Boolean, val stage: String, val progress: Double, val notice: String)

    companion object {
        private val NAME = ObjectName("dev.gaboron.spwtrackmix:type=Mixer,version=1")
        private val COMMAND_SIGNATURE = arrayOf("java.lang.String", "int", "int")
        private val SPECTRUM_SIGNATURE = arrayOf("java.lang.String", "long")
    }
}
