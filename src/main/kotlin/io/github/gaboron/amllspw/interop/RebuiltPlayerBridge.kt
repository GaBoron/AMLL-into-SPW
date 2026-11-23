package io.github.gaboron.amllspw.interop

import java.lang.management.ManagementFactory
import javax.management.MBeanServer
import javax.management.ObjectName
import javax.management.StandardMBean

/** Allows a companion plugin to select the rebuilt page instead of the native player menu. */
internal class RebuiltPlayerBridge(active: () -> Boolean,
    private val server: MBeanServer = ManagementFactory.getPlatformMBeanServer()) : AutoCloseable {
    private var registered = false

    init {
        val endpoint = object : RebuiltPlayerMBean {
            override fun isActive() = active()
            override fun getPanelApiVersion() = 1
            override fun getSpectrumApiVersion() = 1
        }
        server.registerMBean(StandardMBean(endpoint, RebuiltPlayerMBean::class.java, false), NAME)
        registered = true
    }

    override fun close() {
        if (registered) {
            server.unregisterMBean(NAME)
            registered = false
        }
    }

    companion object {
        private val NAME = ObjectName("io.github.gaboron.amllspw:type=RebuiltPlayer,version=2")
    }
}

interface RebuiltPlayerMBean {
    fun isActive(): Boolean
    fun getPanelApiVersion(): Int
    fun getSpectrumApiVersion(): Int
}
