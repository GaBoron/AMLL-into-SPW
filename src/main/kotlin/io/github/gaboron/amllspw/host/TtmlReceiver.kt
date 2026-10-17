package io.github.gaboron.amllspw.host

import io.github.gaboron.amllspw.interop.*

internal class TtmlReceiver(private val runtime: AmllRuntime) : TtmlReceiverMBean {
    override fun getProtocolVersion() = 1
    override fun isActive() = runtime.isActive
    override fun submitTtml(trackId: String, audioPath: String, ttml: String, offsetMs: Long): Boolean =
        isActive() && runtime.receiveTtml(TrackIdentity(trackId, audioPath), ttml, offsetMs)
    override fun clearTtml(trackId: String, audioPath: String): Boolean =
        isActive() && runtime.clearTtml(TrackIdentity(trackId, audioPath))
}
