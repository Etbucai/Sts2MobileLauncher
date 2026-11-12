package top.apricityx.workshop.workshop

import kotlinx.coroutines.runBlocking
import org.junit.Test
import top.apricityx.workshop.steam.protocol.CdnServer
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SteamChunkDownloadPolicyTest {
    @Test
    fun chunkHashDistributesFirstChoiceAcrossWeightedServers() {
        val servers = listOf(server("a"), server("a"), server("b"), server("c"))

        val firstChoices = (0 until 128)
            .map { chunk -> SteamChunkDownloadPolicy.candidateServers(servers, "chunk-$chunk", 1).first().host }
            .toSet()

        assertEquals(setOf("a", "b", "c"), firstChoices)
    }

    @Test
    fun candidatesArePhysicalServerDistinctAndCapped() {
        val servers = (0 until 12).flatMap { index ->
            listOf(server("cdn-$index"), server("cdn-$index"))
        }

        val candidates = SteamChunkDownloadPolicy.candidateServers(servers, "chunk", 1)

        assertEquals(8, candidates.size)
        assertEquals(candidates.size, candidates.map(CdnServer::host).distinct().size)
    }

    @Test
    fun retriesRotateCandidatesAndRespectAttemptLimit() = runBlocking {
        val servers = (0 until 10).map { server("cdn-$it") }
        val calls = mutableListOf<String>()
        val retries = mutableListOf<Int>()

        assertFailsWith<WorkshopDownloadException> {
            SteamChunkDownloadPolicy.downloadWithRetries<Unit>(
                chunkKey = "chunk",
                weightedServers = servers,
                retryDelayMillis = 0L,
                onRetry = retries::add,
            ) { server ->
                calls += server.host
                throw IllegalStateException("unavailable")
            }
        }

        assertEquals(24, calls.size)
        assertEquals(listOf(2, 3), retries)
        assertTrue(calls.subList(0, 8) != calls.subList(8, 16))
    }

    @Test
    fun workerAndMemoryLimitsAreSharedAcrossDownloaders() {
        assertEquals(0, SteamChunkDownloadPolicy.workerCount(8, 0))
        assertEquals(1, SteamChunkDownloadPolicy.workerCount(0, 20))
        assertEquals(3, SteamChunkDownloadPolicy.workerCount(8, 3))
        assertEquals(8, SteamChunkDownloadPolicy.workerCount(99, 20))
        assertEquals(16L * 1024L * 1024L, SteamChunkDownloadPolicy.memoryBudgetBytes(12L * 1024L * 1024L))
        assertEquals(64L * 1024L * 1024L, SteamChunkDownloadPolicy.memoryBudgetBytes(Long.MAX_VALUE))

        val chunk = ManifestChunk(
            id = byteArrayOf(1),
            checksum = 0u,
            offset = 0L,
            compressedLength = 2_000,
            uncompressedLength = 3_000,
        )
        assertEquals(10_000L, SteamChunkDownloadPolicy.estimatedMemoryBytes(chunk))
    }

    private fun server(host: String) = CdnServer(
        type = "CDN",
        sourceId = 1,
        cellId = 1,
        load = 0,
        weightedLoad = 0f,
        numEntriesInClientList = 1,
        steamChinaOnly = false,
        host = host,
        vHost = host,
        useAsProxy = false,
        proxyRequestPathTemplate = null,
        httpsSupport = "mandatory",
        allowedAppIds = emptyList(),
        priorityClass = 0u,
    )
}
