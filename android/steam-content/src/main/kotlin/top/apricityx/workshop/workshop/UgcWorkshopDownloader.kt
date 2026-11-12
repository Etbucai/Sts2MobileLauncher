package top.apricityx.workshop.workshop

import top.apricityx.workshop.steam.protocol.CdnServer
import top.apricityx.workshop.steam.protocol.SteamCmEndpointHealthRegistry
import top.apricityx.workshop.steam.protocol.OkHttpSteamCmSession
import top.apricityx.workshop.steam.protocol.SteamCmSession
import top.apricityx.workshop.steam.protocol.SteamContentClient
import top.apricityx.workshop.steam.protocol.SteamDirectoryClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.zip.ZipInputStream

class UgcWorkshopDownloader(
    private val client: OkHttpClient,
    private val directoryClient: SteamDirectoryClient,
    private val maxConcurrentChunks: Int = DEFAULT_MAX_CONCURRENT_CHUNKS,
    private val sessionFactory: () -> SteamCmSession = { OkHttpSteamCmSession(client) },
    private val sessionConnector: suspend (SteamCmSession, List<top.apricityx.workshop.steam.protocol.CmServer>) -> top.apricityx.workshop.steam.protocol.SessionContext =
        { session, servers -> session.connectAnonymous(servers) },
    private val allowPublicCdnFallbackOnSessionFailure: Boolean = true,
) {
    suspend fun download(
        request: WorkshopDownloadRequest,
        item: ResolvedWorkshopItem.UgcManifestItem,
        emit: suspend (DownloadEvent) -> Unit,
        log: suspend (String) -> Unit,
    ) = withContext(Dispatchers.IO) {
        log("Loading Steam CM websocket candidates")
        val cmServers = directoryClient.loadServers()
        val cmRegions = cmServers.mapNotNull(::cmRegion).distinct()
        log(
            "Loaded ${cmServers.size} CM websocket endpoint candidates" +
                if (cmRegions.isEmpty()) "" else " across regions=${cmRegions.joinToString(",")}",
        )
        val cdnTransport = SteamCdnTransport(client)

        sessionFactory().use { session ->
            val contentClient = SteamContentClient(session, directoryClient)
            val connectResult = runCatching { sessionConnector(session, cmServers) }
            connectResult.exceptionOrNull()?.throwIfCancellation()
            connectResult
                .onSuccess {
                    log(
                        "Connected to Steam CM endpoint=" +
                            "${SteamCmEndpointHealthRegistry.preferredEndpoint() ?: "unknown"} " +
                            "cell=${it.cellId} steamId=${it.steamId}",
                    )
                }
                .onFailure {
                    if (allowPublicCdnFallbackOnSessionFailure) {
                        log("Steam CM connection failed, continuing with public CDN flow: ${it.message}")
                    } else {
                        throw it
                    }
                }

            val manifestRequestCode = runCatching {
                contentClient.getManifestRequestCode(
                    appId = request.appId,
                    depotId = item.depotId,
                    manifestId = item.manifestId,
                    branch = request.branch,
                )
            }.getOrElse {
                it.throwIfCancellation()
                log("Manifest request code unavailable for branch=${request.branch}, retrying without request code: ${it.message}")
                0uL
            }

            val contentServers = runCatching {
                contentClient.getServersForSteamPipe()
            }.getOrElse {
                it.throwIfCancellation()
                log("Falling back to public content server directory API")
                directoryClient.loadContentServers()
            }

            require(contentServers.isNotEmpty()) { "No CDN servers available for SteamPipe" }
            log("Loaded ${contentServers.size} SteamPipe content servers")
            val serverPool = cdnTransport.buildServerPool(request.appId, contentServers)
            require(serverPool.downloadServers.isNotEmpty()) { "No CDN download servers available for app=${request.appId}" }
            val downloadServers = serverPool.downloadServers
            val physicalServerCount = SteamChunkDownloadPolicy.distinctPhysicalServers(downloadServers).size
            log(
                "Selected ${serverPool.downloadServers.size} weighted CDN entries " +
                    "from $physicalServerCount physical servers",
            )
            serverPool.proxyServer?.let { proxy ->
                log("Detected CDN proxy host=${proxy.host} template=${proxy.proxyRequestPathTemplate ?: "(none)"}")
            }
            val cdnAuthTokenCache = SteamCdnAuthTokenCache()

            val depotKey = runCatching {
                session.requestDepotDecryptionKey(
                    appId = request.appId,
                    depotId = item.depotId,
                )
            }.onSuccess {
                log("Loaded depot key for depot=${item.depotId}")
            }.onFailure {
                it.throwIfCancellation()
                log("Depot key request failed for depot=${item.depotId}: ${it.message}")
            }.getOrNull()

            downloadAuthorized(request, item, downloadServers, depotKey, manifestRequestCode,
                { server, path, expectedLength ->
                    requestBytes(server, serverPool.proxyServer, path, request.appId, item.depotId,
                        contentClient, cdnTransport, cdnAuthTokenCache, expectedLength)
                }, emit, log)
        }
    }

    private fun cmRegion(server: top.apricityx.workshop.steam.protocol.CmServer): String? {
        val label = server.endpoint.substringBefore(':').substringBefore('.')
        return label.substringAfter('-', "").takeIf(String::isNotBlank)
    }

    internal suspend fun downloadAuthorized(
        request: WorkshopDownloadRequest,
        item: ResolvedWorkshopItem.UgcManifestItem,
        servers: List<CdnServer>,
        depotKey: ByteArray?,
        manifestRequestCode: ULong,
        fetchBytes: suspend (CdnServer, String, Long?) -> ByteArray,
        emit: suspend (DownloadEvent) -> Unit,
        log: suspend (String) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val manifest = downloadManifest(
            item,
            SteamChunkDownloadPolicy.distinctPhysicalServers(servers),
            manifestRequestCode,
            fetchBytes,
            log,
        )
        require(manifest.depotId == item.depotId && manifest.manifestId == item.manifestId) {
            "Steam manifest identity does not match the requested content"
        }
        val preparedManifest = when {
            !manifest.filenamesEncrypted -> manifest
            request.useSupplyStation -> manifest.decryptFilenames(requireNotNull(depotKey) { "Encrypted manifest requires a depot key" })
            depotKey != null -> runCatching { manifest.decryptFilenames(depotKey) }.getOrElse {
                it.throwIfCancellation()
                log("Manifest filename decryption failed; continuing with encoded names: ${it.message}")
                manifest
            }
            else -> {
                log("Manifest filenames are encrypted but depot key is unavailable; continuing with encoded names")
                manifest
            }
        }
        emit(DownloadEvent.StateChanged(DownloadState.Downloading))
        val chunks = preparedManifest.uniqueChunks()
        val totalBytes = chunks.sumOf { it.uncompressedLength.toLong() }
        val totalFiles = preparedManifest.files.count {
            it.linkTarget.isNullOrBlank() && !preparedManifest.requiresDirectory(it)
        }
        log("Manifest ${preparedManifest.manifestId} contains ${preparedManifest.files.size} files and ${chunks.size} unique chunks")
        emit(DownloadEvent.Progress(0L, totalBytes, 0, chunks.size, 0, totalFiles))
        val stageDir = File(request.outputDir, ".chunks").apply { mkdirs() }
        cacheChunks(item.depotId, servers, fetchBytes, chunks, stageDir, depotKey, totalFiles, emit, log)
        clearAssembledOutput(request.outputDir, stageDir, log)
        assembleFiles(preparedManifest, request.outputDir, stageDir, totalBytes, chunks.size, totalFiles, emit, log)
    }

    private suspend fun downloadManifest(
        item: ResolvedWorkshopItem.UgcManifestItem,
        contentServers: List<CdnServer>,
        manifestRequestCode: ULong,
        fetchBytes: suspend (CdnServer, String, Long?) -> ByteArray,
        log: suspend (String) -> Unit,
    ): DepotManifest {
        var lastError: Throwable? = null
        for (server in contentServers) {
            try {
                log("Trying manifest download from ${server.host}")
                val path = buildString {
                    append("depot/${item.depotId}/manifest/${item.manifestId}/5")
                    if (manifestRequestCode > 0uL) {
                        append("/$manifestRequestCode")
                    }
                }
                val bytes = fetchBytes(server, path, null)
                return DepotManifestParser.parse(unzipSingleEntry(bytes))
            } catch (error: Throwable) {
                error.throwIfCancellation()
                lastError = error
                log("Manifest download failed from ${server.host}: ${error.message}")
            }
        }
        throw WorkshopDownloadException("Unable to download UGC manifest", lastError)
    }

    private suspend fun cacheChunks(
        depotId: UInt,
        contentServers: List<CdnServer>,
        fetchBytes: suspend (CdnServer, String, Long?) -> ByteArray,
        chunks: List<ManifestChunk>,
        stageDir: File,
        depotKey: ByteArray?,
        totalFiles: Int,
        emit: suspend (DownloadEvent) -> Unit,
        log: suspend (String) -> Unit,
    ) {
        if (chunks.isEmpty()) {
            return
        }
        val workerCount = SteamChunkDownloadPolicy.workerCount(maxConcurrentChunks, chunks.size)
        val totalBytes = chunks.sumOf { it.uncompressedLength.toLong() }
        val totalChunks = chunks.size
        val plans = chunks.map { chunk ->
            PlannedChunk(
                chunk = chunk,
                destinations = listOf(File(stageDir, "${chunk.idHex}.chunk")),
                estimatedBytes = SteamChunkDownloadPolicy.estimatedMemoryBytes(chunk),
                progressBytes = chunk.uncompressedLength.coerceAtLeast(0).toLong(),
            )
        }
        log(
            "Downloading $totalChunks chunks with $workerCount workers across " +
                "${SteamChunkDownloadPolicy.distinctPhysicalServers(contentServers).size} physical CDN servers",
        )
        runBoundedChunkPipeline(
            plannedChunks = plans,
            options = BoundedChunkPipelineOptions(
                workerCount = workerCount,
                resultBufferCapacity = if (workerCount > 1) 1 else 0,
                maxEstimatedBytesInFlight = SteamChunkDownloadPolicy.memoryBudgetBytes(),
            ),
            fetch = { plan ->
                val stageFile = plan.destinations.single()
                if (stageFile.isFile && validateChunkFile(stageFile, plan.chunk)) {
                    CachedChunkPayload(bytes = null)
                } else {
                    if (stageFile.exists()) {
                        stageFile.delete()
                    }
                    val processed = SteamChunkDownloadPolicy.downloadWithRetries(
                        chunkKey = plan.chunk.idHex,
                        weightedServers = contentServers,
                        onFailure = { _, server, error ->
                            log("Chunk ${plan.chunk.idHex} failed from ${server.host}: ${error.message}")
                        },
                        onRetry = { nextAttempt ->
                            log("Retrying chunk ${plan.chunk.idHex}, attempt $nextAttempt")
                        },
                    ) { server ->
                        val path = "depot/$depotId/chunk/${plan.chunk.idHex}"
                        val raw = fetchBytes(
                            server,
                            path,
                            plan.chunk.compressedLength.toLong().takeIf { it > 0L },
                        )
                        ChunkProcessor.process(raw, plan.chunk, depotKey)
                    }
                    CachedChunkPayload(processed)
                }
            },
            write = { plan, payload ->
                payload.bytes?.let { writeAtomically(plan.destinations.single(), it) }
            },
            onProgress = { progress ->
                emit(
                    DownloadEvent.Progress(
                        writtenBytes = progress.completedBytes,
                        totalBytes = totalBytes,
                        completedChunks = progress.completedChunks,
                        totalChunks = totalChunks,
                        completedFiles = 0,
                        totalFiles = totalFiles,
                    ),
                )
            },
        )
    }

    private data class CachedChunkPayload(val bytes: ByteArray?)

    private suspend fun clearAssembledOutput(
        outputDir: File,
        stageDir: File,
        log: suspend (String) -> Unit,
    ) {
        val preservedNames = setOf(
            stageDir.name,
            "metadata.json",
            "download.log",
            ACTIVE_DOWNLOAD_MARKER,
        )
        outputDir.listFiles().orEmpty()
            .filterNot { it.name in preservedNames }
            .forEach { entry ->
                deleteOutputEntry(entry, outputDir)
            }
        log("Cleared previous assembled output while preserving verified chunk cache")
    }

    private fun deleteOutputEntry(entry: File, outputDir: File) {
        if (Files.isSymbolicLink(entry.toPath())) {
            Files.deleteIfExists(entry.toPath())
            return
        }
        val rootPath = outputDir.canonicalPath
        val entryPath = entry.canonicalPath
        if (entryPath != rootPath && !entryPath.startsWith(rootPath + File.separator)) {
            throw WorkshopDownloadException("Refusing to clear output outside the Workshop download directory")
        }
        if (entry.isDirectory) {
            entry.listFiles().orEmpty().forEach { child -> deleteOutputEntry(child, outputDir) }
        }
        if (!entry.delete() && entry.exists()) {
            throw WorkshopDownloadException("Unable to clear previous Workshop output: ${entry.absolutePath}")
        }
    }

    private suspend fun assembleFiles(
        manifest: DepotManifest,
        outputDir: File,
        stageDir: File,
        totalBytes: Long,
        totalChunks: Int,
        totalFiles: Int,
        emit: suspend (DownloadEvent) -> Unit,
        log: suspend (String) -> Unit,
    ) {
        var completedFiles = 0
        val progress = CoalescedProgressEmitter(
            totalBytes = totalBytes,
            totalChunks = totalChunks,
            totalFiles = totalFiles,
            eventSink = emit,
        )
        manifest.files.forEach { file ->
            if (!file.linkTarget.isNullOrBlank()) {
                log("Skipping symlink-like manifest entry ${file.path} -> ${file.linkTarget}")
                return@forEach
            }

            val preparedEntry = WorkshopOutputPathManager.prepare(
                outputDir = outputDir,
                manifest = manifest,
                file = file,
            )
            if (preparedEntry is PreparedManifestEntry.DirectoryEntry) {
                log("Created directory entry ${file.path}")
                return@forEach
            }
            val target = (preparedEntry as PreparedManifestEntry.FileEntry).target

            val existingValidation = target
                .takeIf { it.exists() && it.length() == file.size }
                ?.let { WorkshopFileIntegrityVerifier.assess(it, file) }
            val reuse = existingValidation != null && existingValidation !is AssembledFileValidation.Invalid
            if (existingValidation is AssembledFileValidation.ChunkVerifiedHashMismatch) {
                log(
                    "Reusing ${file.path} despite SHA-1 mismatch because all chunk ranges validated " +
                        "expected=${existingValidation.expectedShaHex} actual=${existingValidation.actualShaHex}",
                )
            }
            if (!reuse) {
                RandomAccessFile(target, "rw").use { output ->
                    output.setLength(file.size)
                    file.chunks.forEach { chunk ->
                        val chunkFile = File(stageDir, "${chunk.idHex}.chunk")
                        output.seek(chunk.offset)
                        chunkFile.inputStream().buffered(IO_BUFFER_SIZE).use { input ->
                            val buffer = ByteArray(IO_BUFFER_SIZE)
                            var bytesSinceYield = 0L
                            while (true) {
                                val read = input.read(buffer)
                                if (read == -1) {
                                    break
                                }
                                output.write(buffer, 0, read)
                                bytesSinceYield += read
                                if (bytesSinceYield >= IO_YIELD_BYTES) {
                                    yield()
                                    bytesSinceYield = 0L
                                }
                            }
                        }
                    }
                }

                when (val validation = WorkshopFileIntegrityVerifier.assess(target, file)) {
                    AssembledFileValidation.Verified -> Unit

                    is AssembledFileValidation.ChunkVerifiedHashMismatch -> {
                        log(
                            "Assembled ${file.path} with valid chunk coverage, but manifest SHA-1 differed; " +
                                "continuing expected=${validation.expectedShaHex} actual=${validation.actualShaHex}",
                        )
                    }

                    is AssembledFileValidation.Invalid -> {
                        throw WorkshopDownloadException(
                            "Assembled file checksum mismatch for ${file.path} " +
                                "(expected=${validation.expectedShaHex} actual=${validation.actualShaHex} " +
                                "exactChunkCoverage=${validation.exactChunkCoverage} " +
                                "chunkChecksumsValid=${validation.chunkChecksumsValid})",
                        )
                    }
                }
            }

            completedFiles += 1
            progress.emit(
                writtenBytes = totalBytes,
                completedChunks = totalChunks,
                completedFiles = completedFiles,
                force = completedFiles >= totalFiles,
            )
        }
    }

    private suspend fun requestBytes(
        server: CdnServer,
        proxyServer: CdnServer?,
        path: String,
        appId: UInt,
        depotId: UInt,
        contentClient: SteamContentClient,
        cdnTransport: SteamCdnTransport,
        cdnAuthTokenCache: SteamCdnAuthTokenCache,
        expectedLength: Long? = null,
    ): ByteArray {
        return cdnTransport.requestBytes(
            server = server,
            path = path,
            query = cdnAuthTokenCache.cached(server.host),
            proxyServer = proxyServer,
            expectedLength = expectedLength,
            resolveAuthToken = { host ->
                cdnAuthTokenCache.resolve(host) { tokenHost ->
                    contentClient.getCdnAuthToken(appId, depotId, tokenHost)
                }
            },
            resolveRejectedAuthToken = { host, rejectedToken ->
                cdnAuthTokenCache.resolve(host, rejectedToken) { tokenHost ->
                    contentClient.getCdnAuthToken(appId, depotId, tokenHost)
                }
            },
        )
    }

    private fun unzipSingleEntry(zipBytes: ByteArray): ByteArray {
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zip ->
            val entry = zip.nextEntry ?: throw WorkshopDownloadException("Zip payload was empty")
            val output = ByteArrayOutputStream()
            zip.copyTo(output)
            zip.closeEntry()
            return output.toByteArray()
        }
    }

    private fun validateChunkFile(file: File, chunk: ManifestChunk): Boolean {
        if (!file.isFile || file.length() != chunk.uncompressedLength.toLong()) {
            return false
        }
        file.inputStream().buffered().use { input ->
            val checksum = steamAdler32(input)
            return checksum == chunk.checksum
        }
    }

    private suspend fun writeAtomically(target: File, bytes: ByteArray) {
        val temp = File(target.parentFile, "${target.name}.tmp")
        temp.outputStream().buffered(IO_BUFFER_SIZE).use { output ->
            var offset = 0
            var bytesSinceYield = 0L
            while (offset < bytes.size) {
                val count = minOf(IO_BUFFER_SIZE, bytes.size - offset)
                output.write(bytes, offset, count)
                offset += count
                bytesSinceYield += count
                if (bytesSinceYield >= IO_YIELD_BYTES) {
                    yield()
                    bytesSinceYield = 0L
                }
            }
        }
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
    }

    private fun Throwable.throwIfCancellation() {
        when (this) {
            is Error -> throw this
            is CancellationException -> throw this
            is InterruptedException -> {
                Thread.currentThread().interrupt()
                throw this
            }
        }
    }

    private class CoalescedProgressEmitter(
        private val totalBytes: Long,
        private val totalChunks: Int,
        private val totalFiles: Int,
        private val eventSink: suspend (DownloadEvent) -> Unit,
    ) {
        private val mutex = Mutex()
        private var lastEmittedAtMs = 0L
        private var lastWrittenBytes = -1L
        private var lastCompletedChunks = -1
        private var lastCompletedFiles = -1

        suspend fun emit(
            writtenBytes: Long,
            completedChunks: Int,
            completedFiles: Int,
            force: Boolean = false,
        ) {
            val event = mutex.withLock {
                val now = System.currentTimeMillis()
                val byteDelta = if (lastWrittenBytes < 0L) Long.MAX_VALUE else (writtenBytes - lastWrittenBytes).coerceAtLeast(0L)
                val chunkDelta = completedChunks - lastCompletedChunks
                val fileDelta = completedFiles - lastCompletedFiles
                val shouldEmit = force ||
                    byteDelta >= PROGRESS_EMIT_BYTES ||
                    chunkDelta >= PROGRESS_EMIT_CHUNKS ||
                    fileDelta >= PROGRESS_EMIT_FILES ||
                    now - lastEmittedAtMs >= PROGRESS_EMIT_INTERVAL_MS
                if (!shouldEmit) {
                    null
                } else {
                    lastEmittedAtMs = now
                    lastWrittenBytes = writtenBytes
                    lastCompletedChunks = completedChunks
                    lastCompletedFiles = completedFiles
                    DownloadEvent.Progress(
                        writtenBytes = writtenBytes,
                        totalBytes = totalBytes,
                        completedChunks = completedChunks,
                        totalChunks = totalChunks,
                        completedFiles = completedFiles,
                        totalFiles = totalFiles,
                    )
                }
            }
            if (event != null) {
                eventSink(event)
            }
        }
    }

    companion object {
        const val DEFAULT_MAX_CONCURRENT_CHUNKS = 4
private const val IO_BUFFER_SIZE = 64 * 1024
        private const val IO_YIELD_BYTES = 1024L * 1024L
        private const val ACTIVE_DOWNLOAD_MARKER = ".workshop-download-active"
        private const val PROGRESS_EMIT_BYTES = 512 * 1024L
        private const val PROGRESS_EMIT_CHUNKS = 4
        private const val PROGRESS_EMIT_FILES = 8
        private const val PROGRESS_EMIT_INTERVAL_MS = 250L
    }
}
