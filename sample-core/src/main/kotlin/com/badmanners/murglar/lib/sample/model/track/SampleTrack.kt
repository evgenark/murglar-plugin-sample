
package com.badmanners.murglar.lib.sample.model.track

import com.badmanners.murglar.lib.core.model.node.NodeType
import com.badmanners.murglar.lib.core.model.track.BaseTrack
import com.badmanners.murglar.lib.core.model.track.source.Source
import com.badmanners.murglar.lib.core.utils.contract.Model


@Model
class SampleTrack(
    id: String,
    title: String,
    val filePath: String,
    artistNames: List<String>,
    sources: List<Source>,
    mediaId: String,
    durationMs: Long = 0L,
    override val nodeType: String
) : BaseTrack(
    id = id,
    title = title,
    subtitle = null,
    artistIds = emptyList(),
    artistNames = artistNames,
    albumId = null,
    albumName = null,
    albumReleaseDate = null,
    indexInAlbum = null,
    volumeNumber = null,
    durationMs = durationMs,
    genre = null,
    explicit = false,
    gain = null,
    peak = null,
    sources = sources,
    mediaId = mediaId,
    smallCoverUrl = null,
    bigCoverUrl = null,
    serviceUrl = "file://$filePath"
)
