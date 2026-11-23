package io.github.gaboron.amllspw.verify

import io.github.gaboron.amllspw.interop.TrackIdentity
import io.github.gaboron.amllspw.interop.TrackMixClient
import javax.management.MBeanServerFactory
import javax.management.ObjectName
import javax.management.StandardMBean

internal fun verifyTrackMixQueueProtocol() {
    val server = MBeanServerFactory.newMBeanServer()
    val name = ObjectName("dev.gaboron.spwtrackmix:type=Mixer,version=1")
    val fixture = TrackMixFixture()
    server.registerMBean(StandardMBean(fixture, TrackMixFixtureMBean::class.java), name)
    val client = TrackMixClient(server)

    val snapshot = checkNotNull(client.snapshot())
    check(snapshot.identity?.matches(TrackIdentity("b", "C:/music/second.flac")) == true)
    check(snapshot.title == "Second" && snapshot.currentQueued)
    check(snapshot.jobs.map { it.title } == listOf("First", "Second"))
    check(snapshot.jobs.first().running && snapshot.jobs.first().progress == .42)
    check(!snapshot.jobs.last().running && snapshot.jobs.last().kind == "QUICK")
    check(snapshot.settings?.cacheGiB == 10)

    client.configure(18, quality = true, cpu = false, fp16 = true)
    val settings = checkNotNull(client.snapshot()?.settings)
    check(settings.cacheGiB == 18 && settings.quality && settings.fp16)
    client.command("cancel", value = 102)
    check(client.snapshot()?.jobs?.map { it.id } == listOf(101))

    fixture.legacy = true
    val legacy = checkNotNull(client.snapshot())
    check(legacy.settings == null && legacy.jobs.single().title == "后台分离")
    server.unregisterMBean(name)
    check(client.snapshot() == null)
    println("Track Mix interop: current track, earlier progress, queued cancellation and settings passed")
}

interface TrackMixFixtureMBean {
    fun getState(): Map<String, Any>
    fun configure(cacheGiB: Int, quality: Boolean, cpu: Boolean, fp16: Boolean)
    fun command(action: String, lane: Int, value: Int)
}

private class TrackMixFixture : TrackMixFixtureMBean {
    var legacy = false
    private var jobs = listOf(
        mapOf("id" to 101, "title" to "First", "kind" to "STANDARD", "running" to true, "progress" to .42),
        mapOf("id" to 102, "title" to "Second", "kind" to "QUICK", "running" to false, "progress" to 0.0)
    )
    private var settings: Map<String, Any> = mapOf("cacheGiB" to 10, "quality" to false, "cpu" to false, "fp16" to false)

    override fun getState(): Map<String, Any> {
        val mixer = mapOf("title" to "Second", "levels" to IntArray(6) { 100 }, "busy" to true, "progress" to .42)
        return if (legacy) mixer else mixer + mapOf("trackId" to "b", "trackPath" to "C:/music/second.flac",
            "currentQueued" to (jobs.size == 2), "jobs" to jobs, "settings" to settings)
    }

    override fun configure(cacheGiB: Int, quality: Boolean, cpu: Boolean, fp16: Boolean) {
        settings = mapOf("cacheGiB" to cacheGiB, "quality" to quality, "cpu" to cpu, "fp16" to fp16)
    }

    override fun command(action: String, lane: Int, value: Int) {
        if (action == "cancel") jobs = jobs.filterNot { it["id"] == value }
    }
}
