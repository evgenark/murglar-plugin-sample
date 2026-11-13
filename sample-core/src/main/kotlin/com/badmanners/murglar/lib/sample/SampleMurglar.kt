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
        /**
         * Must be used only for [MediaId.build], don't pass it to the [BaseMurglar] constructor directly!
         */
        const val SERVICE_ID = "localfiles"

        const val MUSIC_DIR = "/storage/emulated/0/Music"

        val AUDIO_EXTENSIONS = setOf("mp3", "flac", "ogg", "oga", "m4a", "aac", "wav", "opus", "wma", "ape")

        private val MESSAGES = mapOf(
            DEFAULT to SampleDefaultMessages,
            RUSSIAN to SampleRussianMessages
        )
    }

    override val loginResolver = SampleLoginResolver(messages)

    override val nodeResolver = SampleNodeResolver(this, messages)

    override val murglarPreferences: List<Preference>
        get() = emptyList()

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


    //region local file helpers

    fun encodePath(path: String): String =
        path.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) }

    fun decodeId(id: String): String? = try {
        id.chunked(2).map { it.toInt(16).toByte() }.toByteArray().toString(Charsets.UTF_8)
    } catch (e: Exception) {
        null
    }

    fun isAudioFile(file: File) = AUDIO_EXTENSIONS.contains(file.extension.lowercase())

    fun buildTrack(file: File): SampleTrack {
        val id = encodePath(file.absolutePath)
        val artist = file.parentFile?.name ?: "Unknown"
        val source = Source(
            file.toURI().toString(), null, "Local file",
            Extension.UNKNOWN, Container.PROGRESSIVE, Bitrate.B_UNKNOWN, HIGH_QUALITY
        )
        return SampleTrack(
            id = id,
            title = file.nameWithoutExtension,
            filePath = file.absolutePath,
            artistNames = listOf(artist),
            sources = listOf(source),
            mediaId = MediaId.build(SERVICE_ID, id),
            nodeType = NodeType.TRACK
        )
    }

    fun getTrackById(id: String): SampleTrack? {
        val path = decodeId(id) ?: return null
        val file = File(path)
        return if (file.isFile) buildTrack(file) else null
    }

    fun listAudioChildren(dir: File): Pair<List<File>, List<File>>? {
        if (!dir.isDirectory)
            return null
        val children = dir.listFiles()?.filter { !it.name.startsWith(".") } ?: return null
        val dirs = children.filter { it.isDirectory }.sortedBy { it.name.lowercase() }
        val tracks = children.filter { it.isFile && isAudioFile(it) }.sortedBy { it.name.lowercase() }
        return dirs to tracks
    }
    //endregion
}
