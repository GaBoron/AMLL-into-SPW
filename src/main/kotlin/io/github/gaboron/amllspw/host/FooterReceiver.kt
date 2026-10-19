package io.github.gaboron.amllspw.host

import io.github.gaboron.amllspw.interop.FooterReceiverMBean
import io.github.gaboron.amllspw.interop.TrackIdentity

internal class FooterReceiver(private val runtime: AmllRuntime) : FooterReceiverMBean {
    override fun getProtocolVersion() = 1
    override fun isActive() = runtime.isActive
    override fun submitFooter(providerId: String?, providerName: String?, trackId: String?, audioPath: String?,
        keys: Array<out String?>?, values: Array<out String?>?): Boolean =
        trackId != null && audioPath != null && isActive() &&
            runtime.receiveFooter(providerId, providerName, TrackIdentity(trackId, audioPath), keys, values)

    override fun clearFooter(providerId: String?, trackId: String?, audioPath: String?): Boolean =
        trackId != null && audioPath != null && isActive() &&
            runtime.clearFooter(providerId, TrackIdentity(trackId, audioPath))
}
