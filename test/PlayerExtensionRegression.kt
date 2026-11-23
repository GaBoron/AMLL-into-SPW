package io.github.gaboron.amllspw.verify

import io.github.gaboron.amllspw.interop.PlayerExtensionClient
import io.github.gaboron.amllspw.interop.RebuiltPlayerBridge
import javax.management.MBeanServerFactory
import javax.management.ObjectName
import javax.management.StandardMBean

internal fun verifyPlayerExtensions() {
    val server = MBeanServerFactory.newMBeanServer()
    val client = PlayerExtensionClient(server)
    val firstName = ObjectName("io.github.gaboron.amllspw:type=PlayerPanel,version=1,provider=fixture.notes")
    val secondName = ObjectName("io.github.gaboron.amllspw:type=PlayerPanel,version=1,provider=fixture.bookmarks")
    val first = PanelFixture("Notes", 25)
    val second = PanelFixture("Bookmarks", -1)
    server.registerMBean(StandardMBean(first, PanelFixtureMBean::class.java), firstName)
    server.registerMBean(StandardMBean(second, PanelFixtureMBean::class.java), secondName)
    val context = PlayerExtensionClient.Context("song-2", "C:/music/song-2.flac")
    val panels = client.panels()
    check(panels.map { it.label } == listOf("Bookmarks", "Notes"))
    val panel = panels.last()
    val controls = checkNotNull(client.render(panel, "one", context))
    val group = controls.single()
    check(group.type == "column" && group.children.single().text("text") == "Notes: song-2")
    client.action(panel, "one", context, "bookmark", 17)
    check(first.events.single() == listOf("one", context.values(), "bookmark", 17) && second.events.isEmpty())
    client.close(panel, "one")
    check(first.closed == listOf("one"))
    server.unregisterMBean(firstName)
    check(client.render(panel, "one", context) == null && client.panels().single().endpoint == secondName)

    val inactive = ObjectName("io.github.gaboron.amllspw:type=SpectrumSource,version=1,provider=fixture.a")
    val active = ObjectName("io.github.gaboron.amllspw:type=SpectrumSource,version=1,provider=fixture.b")
    server.registerMBean(StandardMBean(SpectrumFixtureMBean { _, _ -> null }, SpectrumFixtureMBean::class.java), inactive)
    server.registerMBean(StandardMBean(SpectrumFixtureMBean { path, position ->
        if (path == context.path && position == 300L) arrayOf(48000, floatArrayOf(), floatArrayOf()) else null
    }, SpectrumFixtureMBean::class.java), active)
    check(client.spectrum(context.path, 300)?.let { it.sampleRate == 48000 && it.magnitudes.isEmpty() } == true)
    check(client.spectrum(context.path, 301) == null)
    server.unregisterMBean(active)
    check(client.spectrum(context.path, 300) == null)

    var enabled = false
    val host = ObjectName("io.github.gaboron.amllspw:type=RebuiltPlayer,version=2")
    RebuiltPlayerBridge({ enabled }, server).use {
        check(server.getAttribute(host, "PanelApiVersion") == 1 && server.getAttribute(host, "SpectrumApiVersion") == 1)
        check(server.getAttribute(host, "Active") == false)
        enabled = true
        check(server.getAttribute(host, "Active") == true)
    }
    check(!server.isRegistered(host))
    println("Player extensions: independent panels, typed actions, close, spectrum, and host discovery passed")
}

interface PanelFixtureMBean {
    fun getDescriptor(): Map<String, Any>
    fun render(session: String, context: Map<String, String>): List<Map<String, Any>>
    fun action(session: String, context: Map<String, String>, action: String, value: Int)
    fun close(session: String)
}

fun interface SpectrumFixtureMBean {
    fun spectrum(path: String, positionMs: Long): Array<Any>?
}

private class PanelFixture(private val label: String, private val order: Int) : PanelFixtureMBean {
    val events = mutableListOf<List<Any>>()
    val closed = mutableListOf<String>()

    override fun getDescriptor(): Map<String, Any> = mapOf("label" to label, "order" to order, "width" to 320)

    override fun render(session: String, context: Map<String, String>): List<Map<String, Any>> = listOf(
        mapOf("type" to "column", "id" to "summary", "children" to listOf(
            mapOf("type" to "text", "id" to "song", "text" to "$label: ${context["trackId"]}"),
            mapOf("id" to "invalid")
        ))
    )

    override fun action(session: String, context: Map<String, String>, action: String, value: Int) {
        events.add(listOf(session, context, action, value))
    }

    override fun close(session: String) { closed.add(session) }
}
