package top.apricityx.workshop.steam.protocol

import com.google.protobuf.MessageLite
import com.google.protobuf.Parser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import top.apricityx.workshop.steam.proto.CContentServerDirectory_GetServersForSteamPipe_Response
import top.apricityx.workshop.steam.proto.CContentServerDirectory_ServerInfo
import kotlin.test.assertEquals

class SteamContentServerMappingTest {
    @Test
    fun steamWebSocketHostsKeepListedPortAndAddStandardTlsFallback() {
        assertEquals(
            "wss://cmp1-tyo3.steamserver.net:27020/cmsocket/",
            SteamPacketCodec.buildWebSocketUri("cmp1-tyo3.steamserver.net:27020"),
        )
        assertEquals(
            listOf(
                "wss://cmp1-tyo3.steamserver.net:27020/cmsocket/",
                "wss://cmp1-tyo3.steamserver.net:443/cmsocket/",
            ),
            SteamPacketCodec.buildWebSocketUris("cmp1-tyo3.steamserver.net:27020"),
        )
        assertEquals(
            "wss://example.test:27020/cmsocket/",
            SteamPacketCodec.buildWebSocketUri("example.test:27020"),
        )
        assertEquals(
            "wss://192.0.2.1:27020/cmsocket/",
            SteamPacketCodec.buildWebSocketUri("192.0.2.1:27020"),
        )
    }

    @Test
    fun cmResponseMapsBypassProxyTypes() = runBlocking {
        val response = CContentServerDirectory_GetServersForSteamPipe_Response.newBuilder()
            .addServers(
                CContentServerDirectory_ServerInfo.newBuilder()
                    .setType("CDN")
                    .setHost("origin.example")
                    .setVhost("origin.example")
                    .setHttpsSupport("mandatory")
                    .addBypassProxiesOfType("SteamCache")
                    .addBypassProxiesOfType("RegionalProxy")
                    .build(),
            )
            .build()
        val client = SteamContentClient(ResponseSession(response), SteamDirectoryClient())

        val server = client.getServersForSteamPipe().single()

        assertEquals(listOf("SteamCache", "RegionalProxy"), server.bypassProxiesOfType)
    }

    @Test
    fun webApiResponseMapsBypassProxyTypes() = runBlocking {
        val body = """
            {
              "response": {
                "servers": [{
                  "type": "CDN",
                  "source_id": 1,
                  "host": "origin.example",
                  "vhost": "origin.example",
                  "https_support": "mandatory",
                  "bypass_proxies_of_type": ["SteamCache"]
                }]
              }
            }
        """.trimIndent()
        val httpClient = OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(body.toResponseBody("application/json".toMediaType()))
                        .build()
                },
            )
            .build()

        val server = SteamDirectoryClient(client = httpClient).loadContentServers().single()

        assertEquals(listOf("SteamCache"), server.bypassProxiesOfType)
    }

    @Test
    fun cmDirectoryRequestsBroadQosCandidatesAndExpandsTlsFallbacks() = runBlocking {
        var requestedUrl = ""
        val body = """
            {
              "response": {
                "serverlist": [
                  {"endpoint":"cmp1-hkg1.steamserver.net:27025","type":"websockets"},
                  {"endpoint":"192.0.2.1:27020","type":"websockets"},
                  {"endpoint":"192.0.2.2:27017","type":"netfilter"}
                ]
              }
            }
        """.trimIndent()
        val httpClient = OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    requestedUrl = chain.request().url.toString()
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(body.toResponseBody("application/json".toMediaType()))
                        .build()
                },
            )
            .build()

        val servers = SteamDirectoryClient(client = httpClient).loadServers(cellId = 47u)

        assertEquals(
            listOf(
                "wss://cmp1-hkg1.steamserver.net:27025/cmsocket/",
                "wss://cmp1-hkg1.steamserver.net:443/cmsocket/",
                "wss://192.0.2.1:27020/cmsocket/",
            ),
            servers.map(CmServer::websocketUri),
        )
        kotlin.test.assertTrue(requestedUrl.contains("cellid=47"))
        kotlin.test.assertTrue(requestedUrl.contains("maxcount=50"))
        kotlin.test.assertTrue(requestedUrl.contains("qoslevel=1"))
    }

    private class ResponseSession(
        private val response: CContentServerDirectory_GetServersForSteamPipe_Response,
    ) : SteamCmSession {
        override val currentSession: StateFlow<SessionContext?> = MutableStateFlow(null)

        override suspend fun connect(servers: List<CmServer>) = Unit

        override suspend fun connectAnonymous(servers: List<CmServer>): SessionContext = error("unused")

        override suspend fun connectWithRefreshToken(
            servers: List<CmServer>,
            account: SteamAccountSession,
        ): SessionContext = error("unused")

        override suspend fun <T : MessageLite> callServiceMethod(
            methodName: String,
            request: MessageLite,
            parser: Parser<T>,
        ): T {
            assertEquals("ContentServerDirectory.GetServersForSteamPipe#1", methodName)
            @Suppress("UNCHECKED_CAST")
            return response as T
        }

        override suspend fun requestDepotDecryptionKey(appId: UInt, depotId: UInt): ByteArray = error("unused")

        override suspend fun requestAppProductInfo(appId: UInt): SteamAppProductInfo = error("unused")

        override fun close() = Unit
    }
}
