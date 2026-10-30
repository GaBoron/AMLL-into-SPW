package io.github.gaboron.amllspw.interop

import java.lang.management.ManagementFactory
import javax.management.MBeanServer
import javax.management.ObjectName
import javax.management.StandardMBean

/** In-process discovery across isolated plugin class loaders. Opens no port or network service. */
internal class TtmlBridge(endpoint: TtmlReceiverMBean, private val server: MBeanServer = ManagementFactory.getPlatformMBeanServer()) : AutoCloseable {
    private var closed = false
    init { server.registerMBean(StandardMBean(endpoint, TtmlReceiverMBean::class.java, false), NAME) }
    @Synchronized override fun close() {
        if (closed) return
        if (server.isRegistered(NAME)) server.unregisterMBean(NAME)
        closed = true
    }
    companion object { val NAME = ObjectName("io.github.gaboron.amllspw:type=TtmlReceiver,version=1") }
}
