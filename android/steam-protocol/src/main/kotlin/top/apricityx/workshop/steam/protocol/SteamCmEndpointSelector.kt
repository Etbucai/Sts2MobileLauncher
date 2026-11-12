package top.apricityx.workshop.steam.protocol

import java.io.Closeable
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

data class SteamCmEndpointSelection<T : Closeable>(
    val server: CmServer,
    val connection: T,
    val latencyMs: Long,
)

object SteamCmEndpointHealthRegistry {
    private const val BASE_FAILURE_COOLDOWN_MS = 30_000L
    private const val MAX_FAILURE_COOLDOWN_MS = 10 * 60_000L

    private data class Health(
        val consecutiveFailures: Int = 0,
        val blockedUntilMs: Long = 0L,
        val lastSuccessAtMs: Long = 0L,
        val lastLatencyMs: Long = Long.MAX_VALUE,
    )

    private val health = ConcurrentHashMap<String, Health>()

    @Volatile
    private var preferredEndpoint: String? = null

    @Volatile
    private var cellId: UInt = 0u

    fun preferredCellId(): UInt = cellId

    fun preferredEndpoint(): String? = preferredEndpoint

    fun rememberCellId(value: UInt) {
        if (value > 0u) {
            cellId = value
        }
    }

    fun rememberPreferredEndpoint(websocketUri: String?) {
        websocketUri
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?.let { preferredEndpoint = it }
    }

    internal fun recordSuccess(server: CmServer, latencyMs: Long, nowMs: Long) {
        preferredEndpoint = server.websocketUri
        health[server.websocketUri] = Health(
            lastSuccessAtMs = nowMs,
            lastLatencyMs = latencyMs.coerceAtLeast(0L),
        )
    }

    internal fun recordFailure(server: CmServer, nowMs: Long) {
        health.compute(server.websocketUri) { _, previous ->
            val failures = ((previous?.consecutiveFailures ?: 0) + 1).coerceAtMost(8)
            val multiplier = 1L shl (failures - 1).coerceAtMost(4)
            Health(
                consecutiveFailures = failures,
                blockedUntilMs = nowMs + (BASE_FAILURE_COOLDOWN_MS * multiplier)
                    .coerceAtMost(MAX_FAILURE_COOLDOWN_MS),
                lastSuccessAtMs = previous?.lastSuccessAtMs ?: 0L,
                lastLatencyMs = previous?.lastLatencyMs ?: Long.MAX_VALUE,
            )
        }
    }

    internal fun ordered(servers: List<CmServer>, nowMs: Long): List<CmServer> {
        val distinct = servers.distinctBy(CmServer::websocketUri)
        val preferred = preferredEndpoint
        val comparator = compareBy<CmServer>(
            { if (it.websocketUri == preferred) 0 else 1 },
            { if ((health[it.websocketUri]?.blockedUntilMs ?: 0L) <= nowMs) 0 else 1 },
            { -(health[it.websocketUri]?.lastSuccessAtMs ?: 0L) },
            { health[it.websocketUri]?.lastLatencyMs ?: Long.MAX_VALUE },
        )
        val available = distinct
            .filter { (health[it.websocketUri]?.blockedUntilMs ?: 0L) <= nowMs }
            .sortedWith(comparator)
        val cooling = distinct
            .filter { (health[it.websocketUri]?.blockedUntilMs ?: 0L) > nowMs }
            .sortedWith(comparator)
        return spreadFailureDomains(available) + spreadFailureDomains(cooling)
    }

    private fun spreadFailureDomains(servers: List<CmServer>): List<CmServer> {
        if (servers.size <= 1) {
            return servers
        }
        val groups = LinkedHashMap<String, ArrayDeque<CmServer>>()
        servers.forEach { server ->
            groups.getOrPut(failureDomain(server)) { ArrayDeque() }.addLast(server)
        }
        // Interleave regions so one unreachable data center cannot occupy a full probe batch.
        return buildList(servers.size) {
            while (groups.values.any(ArrayDeque<CmServer>::isNotEmpty)) {
                groups.values.forEach { group ->
                    if (group.isNotEmpty()) {
                        add(group.removeFirst())
                    }
                }
            }
        }
    }

    private fun failureDomain(server: CmServer): String {
        val host = runCatching { URI(server.websocketUri).host.orEmpty() }.getOrDefault("")
        val label = host.substringBefore('.')
        return label.substringAfter('-', label)
    }

    internal fun resetForTests() {
        health.clear()
        preferredEndpoint = null
        cellId = 0u
    }
}

class SteamCmEndpointSelector(
    private val endpointTimeoutMs: Long = 5_000L,
    private val batchSize: Int = 4,
    private val maxAttempts: Int = 16,
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
    init {
        require(endpointTimeoutMs > 0L)
        require(batchSize > 0)
        require(maxAttempts > 0)
    }

    suspend fun <T : Closeable> connect(
        servers: List<CmServer>,
        connector: suspend (CmServer) -> T,
    ): SteamCmEndpointSelection<T> {
        val candidates = SteamCmEndpointHealthRegistry
            .ordered(servers, clockMs())
            .take(maxAttempts)
        require(candidates.isNotEmpty()) { "No Steam CM servers available" }

        var lastError: Throwable? = null
        for (batch in candidates.chunked(batchSize)) {
            try {
                return connectBatch(batch, connector)
            } catch (error: SteamCmBatchConnectionException) {
                lastError = error.cause ?: error
            }
        }
        throw SteamProtocolException(
            "Unable to connect to any Steam CM websocket after ${candidates.size} endpoint attempts",
            lastError,
        )
    }

    private suspend fun <T : Closeable> connectBatch(
        servers: List<CmServer>,
        connector: suspend (CmServer) -> T,
    ): SteamCmEndpointSelection<T> = coroutineScope {
        // Each batch races real WebSocket handshakes; the first open transport wins.
        val results = Channel<AttemptResult<T>>(servers.size)
        val opened = ConcurrentLinkedQueue<T>()
        val jobs = servers.map { server ->
            launch {
                val startedAt = clockMs()
                try {
                    val connection = withTimeout(endpointTimeoutMs) { connector(server) }
                    opened.add(connection)
                    results.send(
                        AttemptResult(
                            server = server,
                            connection = connection,
                            latencyMs = (clockMs() - startedAt).coerceAtLeast(0L),
                        ),
                    )
                } catch (error: TimeoutCancellationException) {
                    SteamCmEndpointHealthRegistry.recordFailure(server, clockMs())
                    results.send(AttemptResult(server = server, error = error))
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    if (error is Error) {
                        throw error
                    }
                    SteamCmEndpointHealthRegistry.recordFailure(server, clockMs())
                    results.send(AttemptResult(server = server, error = error))
                }
            }
        }

        var winner: T? = null
        var lastError: Throwable? = null
        try {
            repeat(servers.size) {
                val result = results.receive()
                val connection = result.connection
                if (connection != null) {
                    winner = connection
                    SteamCmEndpointHealthRegistry.recordSuccess(
                        result.server,
                        result.latencyMs,
                        clockMs(),
                    )
                    return@coroutineScope SteamCmEndpointSelection(
                        server = result.server,
                        connection = connection,
                        latencyMs = result.latencyMs,
                    )
                }
                lastError = result.error
            }
            throw SteamCmBatchConnectionException(lastError)
        } finally {
            jobs.forEach { it.cancelAndJoin() }
            opened.forEach { connection ->
                if (connection !== winner) {
                    runCatching(connection::close)
                }
            }
            results.close()
        }
    }

    private data class AttemptResult<T : Closeable>(
        val server: CmServer,
        val connection: T? = null,
        val latencyMs: Long = 0L,
        val error: Throwable? = null,
    )

    private class SteamCmBatchConnectionException(cause: Throwable?) :
        Exception("Steam CM endpoint batch failed", cause)
}
