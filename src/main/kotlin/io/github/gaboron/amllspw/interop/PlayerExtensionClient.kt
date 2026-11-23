package io.github.gaboron.amllspw.interop

import io.github.gaboron.amllspw.spectrum.SpectrumFrame
import java.lang.management.ManagementFactory
import javax.management.JMException
import javax.management.JMRuntimeException
import javax.management.MBeanServer
import javax.management.ObjectName

/** Discovers installed contributors using a protocol shared by independent Workshop class loaders. */
internal class PlayerExtensionClient(private val server: MBeanServer = ManagementFactory.getPlatformMBeanServer()) {
    data class Panel(val endpoint: ObjectName, val label: String, val svg: String, val width: Int,
        val keepOnTrackChange: Boolean, val order: Int)

    data class Context(val trackId: String, val path: String) {
        fun values(): Map<String, String> = mapOf("trackId" to trackId, "path" to path)
    }

    data class Control(val type: String, val id: String, val properties: Map<*, *>, val children: List<Control>) {
        fun text(key: String, fallback: String = "") = properties[key] as? String ?: fallback
        fun number(key: String, fallback: Int = 0) = (properties[key] as? Number)?.toInt() ?: fallback
        fun flag(key: String, fallback: Boolean = false) = properties[key] as? Boolean ?: fallback
    }

    private var spectrumEndpoints: List<ObjectName> = emptyList()
    private var lastSpectrumScan = 0L

    fun panels(): List<Panel> = server.queryNames(PANELS, null).mapNotNull { name ->
        access {
            val descriptor = server.getAttribute(name, "Descriptor") as? Map<*, *> ?: return@access null
            val label = (descriptor["label"] as? String)?.takeIf { it.isNotBlank() } ?: return@access null
            val svg = descriptor["svg"] as? String ?: ""
            Panel(name, label, svg, (descriptor["width"] as? Int ?: 320).coerceIn(240, 640),
                descriptor["keepOnTrackChange"] == true, descriptor["order"] as? Int ?: 0)
        }
    }.sortedWith(compareBy({ it.order }, { it.endpoint.canonicalName }))

    fun render(panel: Panel, session: String, context: Context): List<Control>? = access {
        val rows = server.invoke(panel.endpoint, "render", arrayOf(session, context.values()), RENDER) as? List<*>
            ?: return@access null
        readControls(rows, 0)
    }

    fun action(panel: Panel, session: String, context: Context, action: String, value: Int) {
        access {
            server.invoke(panel.endpoint, "action", arrayOf<Any>(session, context.values(), action, value), ACTION)
        }
    }

    fun close(panel: Panel, session: String) {
        access { server.invoke(panel.endpoint, "close", arrayOf(session), CLOSE) }
    }

    fun spectrum(path: String, positionMs: Long): SpectrumFrame? {
        val now = System.nanoTime()
        if (lastSpectrumScan == 0L || now - lastSpectrumScan >= 500_000_000L) {
            spectrumEndpoints = server.queryNames(SPECTRA, null).sortedBy(ObjectName::getCanonicalName)
            lastSpectrumScan = now
        }
        for (endpoint in spectrumEndpoints) {
            val frame = access {
                val values = server.invoke(endpoint, "spectrum", arrayOf<Any>(path, positionMs), SPECTRUM) as? Array<*>
                    ?: return@access null
                val rate = (values.getOrNull(0) as? Int)?.takeIf { it in 8000..384000 } ?: return@access null
                val full = values.getOrNull(1) as? FloatArray ?: return@access null
                SpectrumFrame(full, rate, values.getOrNull(2) as? FloatArray)
            }
            if (frame != null) return frame
        }
        return null
    }

    private fun readControls(rows: List<*>, depth: Int): List<Control> {
        if (depth >= 12) return emptyList()
        return rows.mapNotNull { raw ->
            val row = raw as? Map<*, *> ?: return@mapNotNull null
            val type = row["type"] as? String ?: return@mapNotNull null
            val id = row["id"] as? String ?: return@mapNotNull null
            Control(type, id, row, (row["children"] as? List<*>)?.let { readControls(it, depth + 1) }.orEmpty())
        }
    }

    private inline fun <T> access(block: () -> T): T? = try {
        block()
    } catch (_: JMException) {
        null
    } catch (_: JMRuntimeException) {
        null
    }

    companion object {
        private val PANELS = ObjectName("io.github.gaboron.amllspw:type=PlayerPanel,version=1,provider=*")
        private val SPECTRA = ObjectName("io.github.gaboron.amllspw:type=SpectrumSource,version=1,provider=*")
        private val RENDER = arrayOf("java.lang.String", "java.util.Map")
        private val ACTION = arrayOf("java.lang.String", "java.util.Map", "java.lang.String", "int")
        private val CLOSE = arrayOf("java.lang.String")
        private val SPECTRUM = arrayOf("java.lang.String", "long")
    }
}
