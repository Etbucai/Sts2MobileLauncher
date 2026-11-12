package top.apricityx.workshop.workshop

import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import top.apricityx.workshop.steam.protocol.CdnServer

internal object SteamChunkDownloadPolicy {
    const val MAX_CONCURRENT_CHUNKS = 8

    fun workerCount(requested: Int, availableChunks: Int): Int {
        if (availableChunks <= 0) return 0
        return requested.coerceIn(1, minOf(MAX_CONCURRENT_CHUNKS, availableChunks))
    }

    fun distinctPhysicalServers(servers: List<CdnServer>): List<CdnServer> =
        servers.distinctBy { it.host.trim().lowercase(Locale.ROOT) }

    fun estimatedMemoryBytes(chunk: ManifestChunk): Long {
        val compressed = chunk.compressedLength.coerceAtLeast(0).toLong()
        val uncompressed = chunk.uncompressedLength.coerceAtLeast(0).toLong()
        return saturatingMultiply(
            saturatingAdd(compressed, uncompressed),
            CHUNK_MEMORY_ESTIMATE_MULTIPLIER,
        )
    }

    fun memoryBudgetBytes(maxMemoryBytes: Long = Runtime.getRuntime().maxMemory()): Long {
        val adaptive = maxMemoryBytes.coerceAtLeast(0L) / CHUNK_MEMORY_HEAP_DIVISOR
        return adaptive.coerceIn(MIN_CHUNK_MEMORY_BUDGET_BYTES, MAX_CHUNK_MEMORY_BUDGET_BYTES)
    }

    fun candidateServers(
        weightedServers: List<CdnServer>,
        chunkKey: String,
        attempt: Int,
    ): List<CdnServer> {
        require(attempt > 0) { "Chunk download attempt must be positive" }
        if (weightedServers.isEmpty()) return emptyList()

        val weightedOffset = (unsignedHash(chunkKey) % weightedServers.size.toLong()).toInt()
        val preferred = distinctPhysicalServers(rotate(weightedServers, weightedOffset))
        return rotate(preferred, attempt - 1).take(MAX_SERVER_CANDIDATES)
    }

    suspend fun <T> downloadWithRetries(
        chunkKey: String,
        weightedServers: List<CdnServer>,
        waitIfPaused: suspend () -> Unit = {},
        onFailure: suspend (attempt: Int, server: CdnServer, error: Throwable) -> Unit = { _, _, _ -> },
        onRetry: suspend (nextAttempt: Int) -> Unit = {},
        retryDelayMillis: Long = RETRY_DELAY_MILLIS,
        fetch: suspend (CdnServer) -> T,
    ): T {
        require(retryDelayMillis >= 0L) { "Chunk retry delay must not be negative" }
        if (weightedServers.isEmpty()) {
            throw WorkshopDownloadException("No Steam CDN server is available")
        }

        var lastError: Throwable? = null
        for (attempt in 1..MAX_DOWNLOAD_ATTEMPTS) {
            currentCoroutineContext().ensureActive()
            waitIfPaused()
            for (server in candidateServers(weightedServers, chunkKey, attempt)) {
                currentCoroutineContext().ensureActive()
                waitIfPaused()
                try {
                    return fetch(server)
                } catch (error: Throwable) {
                    error.rethrowCancellation()
                    lastError = error
                    onFailure(attempt, server, error)
                }
            }
            if (attempt < MAX_DOWNLOAD_ATTEMPTS) {
                onRetry(attempt + 1)
                delay(retryDelayMillis * attempt)
            }
        }
        throw WorkshopDownloadException("Failed to download chunk $chunkKey", lastError)
    }

    private fun rotate(values: List<CdnServer>, offset: Int): List<CdnServer> {
        if (values.isEmpty()) return emptyList()
        val start = Math.floorMod(offset, values.size)
        return List(values.size) { index -> values[(start + index) % values.size] }
    }

    private fun unsignedHash(value: String): Long =
        value.hashCode().toLong() and 0xffff_ffffL

    private fun saturatingAdd(left: Long, right: Long): Long =
        if (right > 0L && left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

    private fun saturatingMultiply(value: Long, multiplier: Long): Long =
        if (value > 0L && multiplier > Long.MAX_VALUE / value) Long.MAX_VALUE else value * multiplier

    private fun Throwable.rethrowCancellation() {
        when (this) {
            is Error -> throw this
            is CancellationException -> throw this
            is InterruptedException -> {
                Thread.currentThread().interrupt()
                throw this
            }
        }
    }

    private const val MAX_DOWNLOAD_ATTEMPTS = 3
    private const val MAX_SERVER_CANDIDATES = 8
    private const val RETRY_DELAY_MILLIS = 750L
    private const val CHUNK_MEMORY_HEAP_DIVISOR = 12L
    private const val CHUNK_MEMORY_ESTIMATE_MULTIPLIER = 2L
    private const val MIN_CHUNK_MEMORY_BUDGET_BYTES = 16L * 1024L * 1024L
    private const val MAX_CHUNK_MEMORY_BUDGET_BYTES = 64L * 1024L * 1024L
}
