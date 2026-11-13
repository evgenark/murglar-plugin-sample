package com.badmanners.murglar.lib.sample

import com.badmanners.murglar.lib.core.localization.DefaultMessages.Companion.DEFAULT
import com.badmanners.murglar.lib.core.localization.RussianMessages.Companion.RUSSIAN
import com.badmanners.murglar.lib.core.log.LoggerMiddleware
import com.badmanners.murglar.lib.core.model.node.Node
import com.badmanners.murglar.lib.core.model.node.NodeType
import com.badmanners.murglar.lib.core.model.tag.Tags
import com.badmanners.murglar.lib.core.model.track.source.Bitrate
import com.badmanners.murglar.lib.core.model.track.source.Container
import com.badmanners.murglar.lib.core.model.track.source.Extension
import com.badmanners.murglar.lib.core.model.track.source.QualityTier.HIGH_QUALITY
import com.badmanners.murglar.lib.core.model.track.source.Source
import com.badmanners.murglar.lib.core.model.track.source.SourceFormat
import com.badmanners.murglar.lib.core.model.track.source.SourceFormat.SourceFormatAvailability.AVAILABLE
import com.badmanners.murglar.lib.core.network.NetworkMiddleware
import com.badmanners.murglar.lib.core.notification.NotificationMiddleware
import com.badmanners.murglar.lib.core.preference.ActionPreference
import com.badmanners.murglar.lib.core.preference.Preference
import com.badmanners.murglar.lib.core.preference.PreferenceMiddleware
import com.badmanners.murglar.lib.core.service.BaseMurglar
import com.badmanners.murglar.lib.core.utils.MediaId
import com.badmanners.murglar.lib.sample.decrypt.SampleDecryptor
import com.badmanners.murglar.lib.sample.localization.SampleDefaultMessages
import com.badmanners.murglar.lib.sample.localization.SampleMessages
import com.badmanners.murglar.lib.sample.localization.SampleRussianMessages
import com.badmanners.murglar.lib.sample.login.SampleLoginResolver
import com.badmanners.murglar.lib.sample.model.track.SampleTrack
import com.badmanners.murglar.lib.sample.node.SampleNodeResolver
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean


class SampleMurglar(
    id: String,
    preferences: PreferenceMiddleware,
    network: NetworkMiddleware,
    notifications: NotificationMiddleware,
    logger: LoggerMiddleware
) : BaseMurglar<SampleTrack, SampleMessages>(
    id, MESSAGES, preferences, network, notifications, logger, SampleDecryptor(logger)
) {

    companion object {
        const val SERVICE_ID = "localfiles"
        private const val TAG = "LocalFilesPlugin"

        /**
         * SAF tree granted to Murglar ("save path" setting).
         * If your tree differs, change both constants below.
         */
        const val TREE_URI = "content://com.android.externalstorage.documents/tree/primary%3AMusic"
        private const val TREE_PREFIX = "primary:Music"

        val AUDIO_EXTENSIONS = setOf("mp3", "flac", "ogg", "oga", "m4a", "aac", "wav", "opus", "wma", "ape")

        const val REMOTE_TEST_TRACK_ID = "remote-test"

        private val MESSAGES = mapOf(
            DEFAULT to SampleDefaultMessages,
            RUSSIAN to SampleRussianMessages
        )
    }

    override val loginResolver = SampleLoginResolver(messages)

    override val nodeResolver = SampleNodeResolver(this, messages)

    private val useLan = AtomicBoolean(false)

    override val murglarPreferences: List<Preference>
        get() = listOf(
            ActionPreference(
                id = "diagnostics",
                title = "Diagnostics",
                summary = "Tests SAF access and the local HTTP server",
                action = { notifications.longNotify(buildDiagnosticsReport()) },
                needConfirmation = false
            ),
            ActionPreference(
                id = "toggle-lan",
                title = "Переключить адрес сервера",
                summary = "127.0.0.1 ↔ LAN (Wi-Fi). После переключения заново откройте папку с треками",
                action = {
                    useLan.set(!useLan.get())
                    notifications.longNotify(
                        "Now: " + if (useLan.get()) (lanHost() ?: "LAN unavailable") else "127.0.0.1"
                    )
                },
                needConfirmation = false
            )
        )

    override val possibleFormats: List<SourceFormat>
        get() = AUDIO_EXTENSIONS
            .mapNotNull { ext -> runCatching { Extension.valueOf(ext.uppercase()) }.getOrNull() }
            .map { SourceFormat(it, Bitrate.B_UNKNOWN, HIGH_QUALITY, AVAILABLE) }
            .ifEmpty {
                listOf(SourceFormat(Extension.UNKNOWN, Bitrate.B_UNKNOWN, HIGH_QUALITY, AVAILABLE))
            }

    override suspend fun onCreate() {
    }

    override suspend fun resolveSourceForUrl(track: SampleTrack, source: Source): Source {
        logd("resolveSourceForUrl called: ${source.url}")
        return source
    }

    override fun hasLyrics(track: SampleTrack) = false

    override suspend fun getTags(track: SampleTrack, parent: Node?) = Tags.Builder().apply {
        title = track.title
        artists = track.artistNames
        url = track.serviceUrl
        mediaId = track.mediaId
    }.createTags()

    override suspend fun getTracksByMediaIds(mediaIds: List<String>): List<SampleTrack> =
        mediaIds.map { MediaId.getIds(it).first() }.mapNotNull { getTrackById(it) }


    //region SAF via reflection (sample-core has no Android SDK on classpath)

    class SafEntry(val relPath: String, val name: String, val isDir: Boolean, val bytes: Long)

    private val context: Any? by lazy {
        try {
            Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication").invoke(null)
        } catch (e: Throwable) {
            null
        }
    }

    private val uriClass: Class<*> by lazy { Class.forName("android.net.Uri") }
    private val documentsContractClass: Class<*> by lazy { Class.forName("android.provider.DocumentsContract") }

    private fun contentResolver(): Any? = try {
        context?.javaClass?.getMethod("getContentResolver")?.invoke(context)
    } catch (e: Throwable) {
        null
    }

    private fun parseUri(s: String): Any? =
        uriClass.getMethod("parse", String::class.java).invoke(null, s)

    fun relToDocId(rel: String) = if (rel.isEmpty()) TREE_PREFIX else "$TREE_PREFIX/$rel"

    fun contentUriFor(rel: String): String? = try {
        documentsContractClass
            .getMethod("buildDocumentUriUsingTree", uriClass, String::class.java)
            .invoke(null, parseUri(TREE_URI), relToDocId(rel))
            .toString()
    } catch (e: Throwable) {
        null
    }

    fun encodePath(path: String): String =
        path.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) }

    fun decodeId(id: String): String? = try {
        id.chunked(2).map { it.toInt(16).toByte() }.toByteArray().toString(Charsets.UTF_8)
    } catch (e: Exception) {
        null
    }

    fun isAudioFile(name: String) =
        AUDIO_EXTENSIONS.contains(name.substringAfterLast('.', "").lowercase())

    fun extensionFor(name: String): Extension {
        val ext = name.substringAfterLast('.', "").uppercase()
        val candidates = when (ext) {
            "MP3" -> listOf("MP3")
            "FLAC" -> listOf("FLAC")
            "OGG", "OGA" -> listOf("OGG", "VORBIS")
            "OPUS" -> listOf("OPUS", "OGG")
            "M4A", "AAC" -> listOf("AAC", "M4A")
            "WAV" -> listOf("WAV")
            else -> emptyList()
        }
        return candidates.firstNotNullOfOrNull { c -> runCatching { Extension.valueOf(c) }.getOrNull() }
            ?: Extension.UNKNOWN
    }

    fun listSaf(rel: String): Pair<List<SafEntry>, List<SafEntry>>? {
        val resolver = contentResolver() ?: return null
        var cursor: Any? = null
        return try {
            val tree = parseUri(TREE_URI)
            val childrenUri = documentsContractClass
                .getMethod("buildChildDocumentsUriUsingTree", uriClass, String::class.java)
                .invoke(null, tree, relToDocId(rel))

            val projection = arrayOf("document_id", "_display_name", "mime_type", "_size")

            cursor = resolver.javaClass.getMethod(
                "query",
                uriClass, Array<String>::class.java,
                String::class.java, Array<String>::class.java, String::class.java
            ).invoke(resolver, childrenUri, projection, null, null, null)
                ?: return null

            val cursorClass = cursor.javaClass
            val moveToNext = cursorClass.getMethod("moveToNext")
            val getString = cursorClass.getMethod("getString", Int::class.javaPrimitiveType)
            val getLong = cursorClass.getMethod("getLong", Int::class.javaPrimitiveType)
            val isNull = cursorClass.getMethod("isNull", Int::class.javaPrimitiveType)
            val close = cursorClass.getMethod("close")

            val dirs = mutableListOf<SafEntry>()
            val files = mutableListOf<SafEntry>()

            while (moveToNext.invoke(cursor) as Boolean) {
                val documentId = getString.invoke(cursor, 0) as? String ?: continue
                val name = getString.invoke(cursor, 1) as? String ?: continue
                if (name.startsWith(".")) continue
                val mime = getString.invoke(cursor, 2) as? String
                val isDir = mime == "vnd.android.document/directory"
                val bytes = if (isNull.invoke(cursor, 3) as Boolean) 0L else getLong.invoke(cursor, 3) as Long

                val relPath = documentId.removePrefix("$TREE_PREFIX/")

                if (isDir)
                    dirs += SafEntry(relPath, name, true, bytes)
                else if (isAudioFile(name))
                    files += SafEntry(relPath, name, false, bytes)
            }

            close.invoke(cursor)

            dirs.sortBy { it.name.lowercase() }
            files.sortBy { it.name.lowercase() }
            dirs to files
        } catch (e: Throwable) {
            try { cursor?.javaClass?.getMethod("close")?.invoke(cursor) } catch (ignored: Throwable) {}
            null
        }
    }

    fun openSafStream(rel: String): InputStream? {
        val resolver = contentResolver() ?: return null
        val uri = contentUriFor(rel) ?: return null
        return try {
            resolver.javaClass.getMethod("openInputStream", uriClass)
                .invoke(resolver, parseUri(uri)) as InputStream
        } catch (e: Throwable) {
            null
        }
    }

    fun safFileSize(rel: String): Long {
        val resolver = contentResolver() ?: return -1L
        val uri = contentUriFor(rel) ?: return -1L
        var cursor: Any? = null
        return try {
            cursor = resolver.javaClass.getMethod(
                "query", uriClass, Array<String>::class.java,
                String::class.java, Array<String>::class.java, String::class.java
            ).invoke(resolver, parseUri(uri), arrayOf("_size"), null, null, null)
                ?: return -1L
            val c = cursor
            val moveToFirst = c.javaClass.getMethod("moveToFirst")
            val getLong = c.javaClass.getMethod("getLong", Int::class.javaPrimitiveType)
            val close = c.javaClass.getMethod("close")
            if (moveToFirst.invoke(c) as Boolean) {
                val size = getLong.invoke(c, 0) as Long
                close.invoke(c)
                size
            } else {
                close.invoke(c)
                -1L
            }
        } catch (e: Throwable) {
            try { cursor?.javaClass?.getMethod("close")?.invoke(cursor) } catch (ignored: Throwable) {}
            -1L
        }
    }

    //endregion


    //region local http server

    private val server: LocalHttpServer by lazy {
        LocalHttpServer(
            opener = { rel -> openSafStream(rel) },
            sizer = { rel -> safFileSize(rel) }
        ).also { it.start() }
    }

    fun lanHost(): String? = try {
        val iter = NetworkInterface.getNetworkInterfaces()
        while (iter.hasMoreElements()) {
            val ni = iter.nextElement()
            if (ni.isUp && !ni.isLoopback) {
                val addrs = ni.inetAddresses
                while (addrs.hasMoreElements()) {
                    val a = addrs.nextElement()
                    if (!a.isLoopbackAddress && a is Inet4Address) return a.hostAddress
                }
            }
        }
        null
    } catch (e: Throwable) {
        null
    }

    fun streamUrlFor(rel: String): String {
        val host = if (useLan.get()) (lanHost() ?: "127.0.0.1") else "127.0.0.1"
        return "http://$host:${server.port}/t/${encodePath(rel)}"
    }

    fun buildTrack(rel: String): SampleTrack {
        val name = rel.substringAfterLast('/')
        val parent = rel.substringBeforeLast('/', "")
        val artist = if (parent.isEmpty()) "Music" else parent.substringAfterLast('/').replace('_', ' ')
        return SampleTrack(
            id = encodePath(rel),
            title = name.substringBeforeLast('.').replace('_', ' '),
            filePath = rel,
            artistNames = listOf(artist),
            sources = listOf(
                Source(
                    streamUrlFor(rel), null, "Local file",
                    extensionFor(name), Container.PROGRESSIVE, Bitrate.B_UNKNOWN, HIGH_QUALITY
                )
            ),
            mediaId = MediaId.build(SERVICE_ID, encodePath(rel)),
            nodeType = NodeType.TRACK
        )
    }

    fun buildRemoteTestTrack(): SampleTrack = SampleTrack(
        id = REMOTE_TEST_TRACK_ID,
        title = "Remote URL test",
        filePath = "",
        artistNames = listOf("Test"),
        sources = listOf(
            Source(
                "https://download.samplelib.com/mp3/sample-15s.mp3", null, "Test",
                Extension.MP3, Container.PROGRESSIVE, Bitrate.B_320, HIGH_QUALITY
            )
        ),
        mediaId = MediaId.build(SERVICE_ID, REMOTE_TEST_TRACK_ID),
        nodeType = NodeType.TRACK
    )

    fun getTrackById(id: String): SampleTrack? {
        if (id == REMOTE_TEST_TRACK_ID) return buildRemoteTestTrack()
        val rel = decodeId(id) ?: return null
        return buildTrack(rel)
    }

    //endregion


    private fun logd(message: String) {
        try {
            Class.forName("android.util.Log")
                .getMethod("d", String::class.java, String::class.java)
                .invoke(null, TAG, message)
        } catch (e: Throwable) {
            // no logging available — ignore
        }
    }

    private fun buildDiagnosticsReport(): String {
        val sb = StringBuilder()

        sb.append("context: ${if (context != null) "ok" else "NULL"}")
        sb.append(" | resolver: ${if (contentResolver() != null) "ok" else "NULL"}")

        val root = listSaf("")
        if (root == null) {
            sb.append(" | ROOT LIST FAILED (tree unavailable?)")
        } else {
            sb.append(" | Music: dirs=${root.first.size} files=${root.second.size}")

            // первый попавшийся аудиофайл: из корня или из первой подпапки
            var probe: SafEntry? = root.second.firstOrNull()
            if (probe == null && root.first.isNotEmpty()) {
                val sub = listSaf(root.first[0].relPath)
                if (sub == null)
                    sb.append(" | ${root.first[0].name}: LIST FAILED")
                else
                    sb.append(" | ${root.first[0].name}: dirs=${sub.first.size} files=${sub.second.size}")
                probe = sub?.second?.firstOrNull()
            }

            val file = probe
            if (file == null) {
                sb.append(" | no audio file for probe")
            } else {
                sb.append(" | probe: ${file.name}")
                sb.append(" | server port: ${server.port}")
                sb.append(" | lan host: ${lanHost() ?: "none"}")

                // тест 1: читаем файл напрямую через SAF
                try {
                    val stream = openSafStream(file.relPath)
                    if (stream == null)
                        sb.append(" | saf read: OPEN FAILED")
                    else {
                        val head = ByteArray(16)
                        val read = stream.read(head)
                        stream.close()
                        sb.append(" | saf read: ok ($read bytes)")
                    }
                } catch (e: Exception) {
                    sb.append(" | saf read: error ${e.message}")
                }

                // тест 2: обращаемся к собственному серверу из фонового потока
                var result = "no result"
                val t = Thread {
                    try {
                        val conn = URL(streamUrlFor(file.relPath)).openConnection() as HttpURLConnection
                        conn.connectTimeout = 5000
                        conn.readTimeout = 5000
                        conn.requestMethod = "GET"
                        val code = conn.responseCode
                        val ins = if (code in 200..299) conn.inputStream else conn.errorStream
                        val first = ins?.read() ?: -1
                        ins?.close()
                        conn.disconnect()
                        result = "$code (first byte: $first)"
                    } catch (e: Exception) {
                        result = "error ${e.javaClass.simpleName}: ${e.message}"
                    }
                }
                t.isDaemon = true
                t.start()
                t.join(6000)
                sb.append(" | http self-test: ").append(result)
            }
        }

        val reportPath = try {
            val pkg = Class.forName("android.app.ActivityThread")
                .getMethod("currentPackageName").invoke(null) as String
            val dir = File("/storage/emulated/0/Android/data/$pkg/files")
            dir.mkdirs()
            val f = File(dir, "diagnostics.txt")
            f.writeText(sb.toString())
            f.absolutePath
        } catch (e: Exception) {
            "report write failed: ${e.message}"
        }
        sb.append(" | FULL REPORT: $reportPath")
        return sb.toString()
    }
}


private class LocalHttpServer(
    private val opener: (String) -> InputStream?,
    private val sizer: (String) -> Long
) {
    private var serverSocket: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool { r ->
        Thread(r).apply { isDaemon = true; name = "localfiles-http" }
    }
    var port: Int = -1
        private set

    fun start() {
        if (serverSocket != null) return
        val ss = ServerSocket(0, 64, InetAddress.getByName("0.0.0.0"))
        serverSocket = ss
        port = ss.localPort
        val acceptor = Thread {
            while (!ss.isClosed) {
                val client = try { ss.accept() } catch (e: Throwable) { break }
                pool.execute { handle(client) }
            }
        }
        acceptor.isDaemon = true
        acceptor.name = "localfiles-acceptor"
        acceptor.start()
    }

    private fun handle(client: Socket) {
        try {
            client.use { socket ->
                socket.soTimeout = 15000
                val input = BufferedInputStream(socket.getInputStream())
                val out = BufferedOutputStream(socket.getOutputStream())

                val requestLine = readLine(input) ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) { respondError(out, 400, "Bad request"); return }
                val method = parts[0].uppercase()
                val path = parts[1]

                var rangeHeader: String? = null
                while (true) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty()) break
                    val idx = line.indexOf(':')
                    if (idx > 0) {
                        val name = line.substring(0, idx).trim().lowercase()
                        if (name == "range")
                            rangeHeader = line.substring(idx + 1).trim()
                    }
                }

                if (!path.startsWith("/t/")) { respondError(out, 404, "Not found"); return }
                val id = path.removePrefix("/t/").substringBefore('?')
                val rel = decodeHex(id) ?: run { respondError(out, 400, "Bad id"); return }

                val stream = opener(rel) ?: run { respondError(out, 404, "File unavailable"); return }
                val total = sizer(rel)

                var start = 0L
                var endInclusive = -1L
                var partial = false
                if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                    val spec = rangeHeader.removePrefix("bytes=").split(',')[0].trim()
                    val dash = spec.indexOf('-')
                    if (dash >= 0) {
                        val s = spec.substring(0, dash).trim()
                        val e = spec.substring(dash + 1).trim()
                        start = if (s.isEmpty()) 0L else (s.toLongOrNull() ?: 0L)
                        endInclusive = if (e.isEmpty()) -1L else (e.toLongOrNull() ?: -1L)
                        partial = start > 0 || endInclusive >= 0
                    }
                }

                val mime = mimeFor(rel)
                val sb = StringBuilder()

                if (partial && total > 0) {
                    if (start >= total) {
                        respondError(out, 416, "Range not satisfiable")
                        return
                    }
                    val end = if (endInclusive < 0) total - 1 else minOf(endInclusive, total - 1)
                    val length = end - start + 1
                    sb.append("HTTP/1.1 206 Partial Content\r\n")
                    sb.append("Content-Type: ").append(mime).append("\r\n")
                    sb.append("Content-Range: bytes ").append(start).append('-').append(end).append('/').append(total).append("\r\n")
                    sb.append("Content-Length: ").append(length).append("\r\n")
                    sb.append("Accept-Ranges: bytes\r\n")
                    sb.append("Connection: close\r\n\r\n")
                    out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
                    skipFully(stream, start)
                    copy(stream, out, length)
                } else {
                    sb.append("HTTP/1.1 200 OK\r\n")
                    sb.append("Content-Type: ").append(mime).append("\r\n")
                    if (total > 0) sb.append("Content-Length: ").append(total).append("\r\n")
                    sb.append("Accept-Ranges: bytes\r\n")
                    sb.append("Connection: close\r\n\r\n")
                    out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
                    if (method != "HEAD") copy(stream, out, -1)
                }
                out.flush()
            }
        } catch (e: Throwable) {
            // client disconnected — nothing to do
        }
    }

    private fun copy(stream: InputStream, out: OutputStream, limit: Long) {
        val buffer = ByteArray(64 * 1024)
        var remaining = limit
        while (remaining != 0L) {
            val toRead = if (remaining < 0) buffer.size
            else minOf(remaining, buffer.size.toLong()).toInt()
            val read = stream.read(buffer, 0, toRead)
            if (read < 0) break
            out.write(buffer, 0, read)
            if (remaining > 0) remaining -= read
        }
    }

    private fun skipFully(stream: InputStream, count: Long) {
        var remaining = count
        val buffer = ByteArray(64 * 1024)
        while (remaining > 0) {
            val skipped = stream.skip(remaining)
            if (skipped > 0) { remaining -= skipped; continue }
            val read = stream.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
            if (read < 0) break
            remaining -= read
        }
    }

    private fun respondError(out: OutputStream, code: Int, message: String) {
        val body = "$message\n"
        val head = "HTTP/1.1 $code $message\r\n" +
            "Content-Type: text/plain; charset=utf-8\r\n" +
            "Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\n" +
            "Connection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        out.write(body.toByteArray(Charsets.UTF_8))
        out.flush()
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        var prev = -1
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code)
                return if (prev == '\r'.code) sb.dropLast(1).toString() else sb.toString()
            sb.append(b.toChar())
            prev = b
            if (sb.length > 16384) return sb.toString()
        }
    }

    private fun decodeHex(id: String): String? = try {
        id.chunked(2).map { it.toInt(16).toByte() }.toByteArray().toString(Charsets.UTF_8)
    } catch (e: Exception) {
        null
    }

    private fun mimeFor(rel: String): String =
        when (rel.substringAfterLast('.', "").lowercase()) {
            "mp3" -> "audio/mpeg"
            "flac" -> "audio/flac"
            "ogg", "oga", "opus" -> "audio/ogg"
            "m4a", "aac" -> "audio/mp4"
            "wav" -> "audio/wav"
            "wma" -> "audio/x-ms-wma"
            "ape" -> "audio/ape"
            else -> "application/octet-stream"
        }
}
