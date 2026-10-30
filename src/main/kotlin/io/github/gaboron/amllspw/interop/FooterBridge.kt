package io.github.gaboron.amllspw.interop

import java.lang.management.ManagementFactory
import javax.management.MBeanServer
import javax.management.ObjectName
import javax.management.StandardMBean

/** Independent of TTML delivery; the endpoint exists only during this plugin's lifetime. */
internal class FooterBridge(endpoint: FooterReceiverMBean,
    private val server: MBeanServer = ManagementFactory.getPlatformMBeanServer()) : AutoCloseable {
    private var closed = false
    init { server.registerMBean(StandardMBean(endpoint, FooterReceiverMBean::class.java, false), NAME) }
    @Synchronized override fun close() {
        if (closed) return
        if (server.isRegistered(NAME)) server.unregisterMBean(NAME)
        closed = true
    }
    companion object { val NAME = ObjectName("io.github.gaboron.amllspw:type=FooterReceiver,version=1") }
}
