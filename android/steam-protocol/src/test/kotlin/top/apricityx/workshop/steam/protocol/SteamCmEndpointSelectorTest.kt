package top.apricityx.workshop.steam.protocol

import java.io.Closeable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SteamCmEndpointSelectorTest {
    @Before
    fun setUp() {
        SteamCmEndpointHealthRegistry.resetForTests()
    }

    @After
    fun tearDown() {
        SteamCmEndpointHealthRegistry.resetForTests()
    }

    @Test
    fun candidatesAreSpreadAcrossRegionsBeforeSecondHostInSameRegion() {
        val ordered = SteamCmEndpointHealthRegistry.ordered(
            listOf(
                server("cmp1-tyo3.steamserver.net", 443),
                server("cmp2-tyo3.steamserver.net", 443),
                server("cmp1-hkg1.steamserver.net", 443),
                server("cmp2-hkg1.steamserver.net", 443),
            ),
            nowMs = 1_000L,
        )

        assertEquals(
            listOf("tyo3", "hkg1", "tyo3", "hkg1"),
            ordered.map(::region),
        )
    }

    @Test
    fun fastestReachableEndpointWinsParallelBatchAndClosesLateConnections() = runBlocking {
        val attempts = CopyOnWriteArrayList<String>()
        val connections = CopyOnWriteArrayList<FakeConnection>()
        val selector = SteamCmEndpointSelector(
            endpointTimeoutMs = 500L,
            batchSize = 4,
            maxAttempts = 4,
        )
        val candidates = listOf(
            server("cmp1-tyo3.steamserver.net", 443),
            server("cmp1-hkg1.steamserver.net", 443),
            server("cmp2-tyo3.steamserver.net", 443),
            server("cmp2-hkg1.steamserver.net", 443),
        )

        val selected = selector.connect(candidates) { candidate ->
            attempts += candidate.websocketUri
            delay(if (region(candidate) == "hkg1") 20L else 250L)
            FakeConnection(candidate.websocketUri).also(connections::add)
        }

        assertEquals("hkg1", region(selected.server))
        assertEquals(4, attempts.size)
        assertFalse(selected.connection.closed.get())
        selected.connection.close()
        assertTrue(selected.connection.closed.get())
    }

    @Test
    fun recentFailureIsCooledAndSuccessfulEndpointBecomesPreferred() {
        val failed = server("cmp1-tyo3.steamserver.net", 443)
        val successful = server("cmp1-hkg1.steamserver.net", 443)
        SteamCmEndpointHealthRegistry.recordFailure(failed, nowMs = 1_000L)
        SteamCmEndpointHealthRegistry.recordSuccess(successful, latencyMs = 25L, nowMs = 1_100L)

        val ordered = SteamCmEndpointHealthRegistry.ordered(
            listOf(failed, successful),
            nowMs = 1_200L,
        )

        assertEquals(successful.websocketUri, ordered.first().websocketUri)
        assertEquals(failed.websocketUri, ordered.last().websocketUri)
    }

    @Test
    fun rememberedCellIdIgnoresZeroAndKeepsLastSuccessfulValue() {
        SteamCmEndpointHealthRegistry.rememberCellId(47u)
        SteamCmEndpointHealthRegistry.rememberCellId(0u)

        assertEquals(47u, SteamCmEndpointHealthRegistry.preferredCellId())
    }

    private fun server(host: String, port: Int): CmServer =
        CmServer(endpoint = "$host:$port", type = "websockets")

    private fun region(server: CmServer): String =
        server.endpoint.substringBefore('.').substringAfter('-')

    private class FakeConnection(
        val id: String,
    ) : Closeable {
        val closed = AtomicBoolean(false)

        override fun close() {
            closed.set(true)
        }
    }
}
