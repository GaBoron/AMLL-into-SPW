package io.github.gaboron.amllspw.host

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.FutureTask
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** One artwork read and one replaceable pending request keep network IO off lyric timing. */
internal class ExternalArtworkLoader(private val read: (String) -> ByteArray? = ::readExternalArtwork) : AutoCloseable {
    private class Request(val key: String, val result: CompletableFuture<ByteArray?> = CompletableFuture()) {
        lateinit var task: FutureTask<Unit>
    }

    private val worker = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1), { task ->
        Thread(task, "amll-external-artwork").apply { isDaemon = true }
    })
    private var current: Request? = null
    private var closed = false

    @Synchronized fun load(key: String, url: String): CompletableFuture<ByteArray?> {
        if (closed) return CompletableFuture.completedFuture(null)
        current?.takeIf { it.key == key }?.let { return it.result }
        cancel()
        val next = Request(key)
        next.task = FutureTask({
            runCatching { read(url) }.onSuccess { bytes ->
                synchronized(this) { if (!closed && current === next) next.result.complete(bytes) }
            }.onFailure { error -> next.result.completeExceptionally(error) }
        }, Unit)
        current = next
        try { worker.execute(next.task) }
        catch (error: RuntimeException) {
            current = null
            next.result.completeExceptionally(error)
        }
        return next.result
    }

    @Synchronized fun cancel() {
        val previous = current ?: return
        current = null
        previous.task.cancel(true)
        worker.remove(previous.task)
        previous.result.cancel(false)
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        cancel()
        worker.shutdownNow()
    }
}
