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
import java.io.File


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

        /**
         * SAF tree granted to Murglar ("save path" setting).
         * If your tree differs, change both constants below.
         */
        const val TREE_URI = "content://com.android.externalstorage.documents/tree/primary%3AMusic"
        private const val TREE_PREFIX = "primary:Music"

        val AUDIO_EXTENSIONS = setOf("mp3", "flac", "ogg", "oga", "m4a", "aac", "wav", "opus", "wma", "ape")

        private val MESSAGES = mapOf(
            DEFAULT to SampleDefaultMessages,
            RUSSIAN to SampleRussianMessages
        )
    }

    override val loginResolver = SampleLoginResolver(messages)

    override val nodeResolver = SampleNodeResolver(this, messages)

    override val murglarPreferences: List<Preference>
        get() = listOf(
            ActionPreference(
                id = "diagnostics",
                title = "Diagnostics",
                summary = "Tests SAF access to the Music folder",
                action = { notifications.longNotify(buildDiagnosticsReport()) },
                needConfirmation = false
            )
        )

    override val possibleFormats: List<SourceFormat>
        get() = listOf(
            SourceFormat(Extension.UNKNOWN, Bitrate.B_UNKNOWN, HIGH_QUALITY, AVAILABLE)
        )

    override suspend fun onCreate() {
    }

    override suspend fun resolveSourceForUrl(track: SampleTrack, source: Source): Source = source

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

            while (moveNext.invoke(cursor) as Boolean) {
                val documentId = getString.invoke(cursor, 0) as? String ?: continue
                val name = getString.invoke(cursor, 1) as? String ?: continue
                if (name.startsWith(".")) continue
                val mime = getString.invoke(cursor, 2) as? String
                val isDir = mime == "vnd.android.document/directory"
                val bytes = if (isNull.invoke(cursor, 3) as Boolean) 0L else getLong.invoke(cursor, 3) as Long

                // documentId внутри дерева имеет вид "primary:Music/<путь>"
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

    fun buildTrack(rel: String): SampleTrack {
        val name = rel.substringAfterLast('/')
        val parent = rel.substringBeforeLast('/', "")
        val artist = if (parent.isEmpty()) "Music" else parent.substringAfterLast('/')
        return SampleTrack(
            id = encodePath(rel),
            title = name.substringBeforeLast('.'),
            filePath = rel,
            artistNames = listOf(artist),
            sources = listOf(
                Source(
                    contentUriFor(rel) ?: rel, null, "Local file",
                    Extension.UNKNOWN, Container.PROGRESSIVE, Bitrate.B_UNKNOWN, HIGH_QUALITY
                )
            ),
            mediaId = MediaId.build(SERVICE_ID, encodePath(rel)),
            nodeType = NodeType.TRACK
        )
    }

    fun getTrackById(id: String): SampleTrack? {
        val rel = decodeId(id) ?: return null
        return buildTrack(rel)
    }

    private fun buildDiagnosticsReport(): String {
        val sb = StringBuilder()

        sb.append("context: ${if (context != null) "ok" else "NULL"}")
        sb.append(" | resolver: ${if (contentResolver() != null) "ok" else "NULL"}")

        val root = listSaf("")
        if (root == null)
            sb.append(" | ROOT LIST FAILED (tree unavailable?)")
        else {
            sb.append(" | Music: dirs=${root.first.size} files=${root.second.size}")
            if (root.first.isNotEmpty()) {
                val sub = listSaf(root.first[0].relPath)
                if (sub == null)
                    sb.append(" | ${root.first[0].name}: LIST FAILED")
                else
                    sb.append(" | ${root.first[0].name}: dirs=${sub.first.size} files=${sub.second.size}")
            }
            if (root.second.isNotEmpty())
                sb.append(" | sample url: ${contentUriFor(root.second[0].relPath)}")
        }

        val reportPath = try {
            val pkg = Class.forName("android.app.ActivityThread")
                .getMethod("currentPackageName").invoke(null) as String
            val dir = File("/storage/emulated/0/Android/data/$pkg/files")
            dir.mkdirs()
            val file = File(dir, "diagnostics.txt")
            file.writeText(sb.toString())
            file.absolutePath
        } catch (e: Exception) {
            "report write failed: ${e.message}"
        }
        sb.append(" | FULL REPORT: $reportPath")
        return sb.toString()
    }
    //endregion
}
