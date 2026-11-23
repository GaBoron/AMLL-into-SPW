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
        val jobs = (state["jobs"] as? List<*>)?.mapNotNull { raw ->
            val job = raw as? Map<*, *> ?: return@mapNotNull null
            Job(
                id = job["id"] as? Int ?: return@mapNotNull null,
                title = job["title"] as? String ?: return@mapNotNull null,
                kind = job["kind"] as? String ?: "STANDARD",
                running = job["running"] == true,
                cancelling = job["cancelling"] == true,
                stage = job["stage"] as? String ?: "",
                progress = job["progress"] as? Double ?: 0.0
            )
        } ?: if (state["busy"] == true) {
            listOf(Job(0, "后台分离", "STANDARD", true, false,
                state["stage"] as? String ?: "", state["progress"] as? Double ?: 0.0))
        } else emptyList()
        val settings = (state["settings"] as? Map<*, *>)?.let { settings ->
            Settings(
                cacheGiB = settings["cacheGiB"] as? Int ?: 10,
                quality = settings["quality"] == true,
                cpu = settings["cpu"] == true,
                fp16 = settings["fp16"] == true,
                modelReady = settings["modelReady"] == true,
                cacheBytes = settings["cacheBytes"] as? Long ?: 0L,
                hasTrack = settings["hasTrack"] == true
            )
        }
        val identity = (state["trackPath"] as? String)?.takeIf { it.isNotBlank() }?.let {
            TrackIdentity(state["trackId"] as? String ?: "", it)
        }
        Snapshot(
            identity = identity,
            title = state["title"] as? String ?: "",
            cached = state["cached"] == true,
            ready = state["ready"] == true,
            canSeparate = state["canSeparate"] == true,
            canQuick = state["canQuick"] == true,
            quickCached = state["quickCached"] == true,
            currentQueued = state["currentQueued"] == true,
            levels = levels.toList(),
            muted = state["muted"] as? Int ?: 0,
            solos = state["solos"] as? Int ?: 0,
            preset = state["preset"] as? Int ?: 0,
            original = state["original"] == true,
            jobs = jobs,
            jobNotice = state["jobNotice"] as? String ?: "",
            settings = settings,
            notice = state["notice"] as? String ?: ""
        )
    }

    fun command(action: String, lane: Int = 0, value: Int = 0) {
        access { server.invoke(NAME, "command", arrayOf<Any>(action, lane, value), COMMAND_SIGNATURE) }
    }

    fun configure(cacheGiB: Int, quality: Boolean, cpu: Boolean, fp16: Boolean) {
        access {
            server.invoke(NAME, "configure", arrayOf<Any>(cacheGiB, quality, cpu, fp16), CONFIGURE_SIGNATURE)
        }
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

    data class Job(val id: Int, val title: String, val kind: String, val running: Boolean, val cancelling: Boolean,
        val stage: String, val progress: Double)
    data class Settings(val cacheGiB: Int, val quality: Boolean, val cpu: Boolean, val fp16: Boolean,
        val modelReady: Boolean, val cacheBytes: Long, val hasTrack: Boolean)
    data class Snapshot(val identity: TrackIdentity?, val title: String, val cached: Boolean, val ready: Boolean, val canSeparate: Boolean,
        val canQuick: Boolean, val quickCached: Boolean, val currentQueued: Boolean,
        val levels: List<Int>, val muted: Int, val solos: Int, val preset: Int, val original: Boolean,
        val jobs: List<Job>, val jobNotice: String, val settings: Settings?, val notice: String)

    companion object {
        private val NAME = ObjectName("dev.gaboron.spwtrackmix:type=Mixer,version=1")
        private val COMMAND_SIGNATURE = arrayOf("java.lang.String", "int", "int")
        private val CONFIGURE_SIGNATURE = arrayOf("int", "boolean", "boolean", "boolean")
        private val SPECTRUM_SIGNATURE = arrayOf("java.lang.String", "long")
    }
}
