package io.github.gaboron.amllspw.interop

/** Only JDK types cross plugin class loaders; no markup, style or layout parameters. */
interface FooterReceiverMBean {
    fun getProtocolVersion(): Int
    fun isActive(): Boolean
    fun submitFooter(providerId: String?, providerName: String?, trackId: String?, audioPath: String?,
        keys: Array<out String?>?, values: Array<out String?>?): Boolean
    fun clearFooter(providerId: String?, trackId: String?, audioPath: String?): Boolean
}
