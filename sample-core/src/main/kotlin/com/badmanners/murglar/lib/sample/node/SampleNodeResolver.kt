package com.badmanners.murglar.lib.sample.node

import com.badmanners.murglar.lib.core.model.node.NamedPath
import com.badmanners.murglar.lib.core.model.node.Node
import com.badmanners.murglar.lib.core.model.node.NodeParameters.PagingType.NON_PAGEABLE
import com.badmanners.murglar.lib.core.model.node.NodeType.NODE
import com.badmanners.murglar.lib.core.model.node.Path
import com.badmanners.murglar.lib.core.node.BaseNodeResolver
import com.badmanners.murglar.lib.core.node.Directory
import com.badmanners.murglar.lib.core.node.Root
import com.badmanners.murglar.lib.core.node.Track
import com.badmanners.murglar.lib.sample.SampleMurglar
import com.badmanners.murglar.lib.sample.localization.SampleMessages
import com.badmanners.murglar.lib.sample.model.track.SampleTrack
import java.io.File


class SampleNodeResolver(
    murglar: SampleMurglar,
    messages: SampleMessages
) : BaseNodeResolver<SampleMurglar, SampleMessages>(murglar, messages) {

    override val configurations = listOf(
        Root(
            pattern = "music",
            name = { messages.serviceName },
            paging = NON_PAGEABLE,
            hasSubdirectories = true,
            isOwn = true,
            contentNodeType = NODE,
            nodeContentSupplier = ::listRoot
        ),
        Directory(
            pattern = "*/dir-<dirId>",
            paging = NON_PAGEABLE,
            hasSubdirectories = true,
            contentNodeType = NODE,
            nodeContentSupplier = ::listDir
        ),
        Track(
            pattern = "*/track-<trackId>",
            relatedPaths = ::getTrackRelatedPaths,
            nodeSupplier = ::getTrack
        )
    )

    @Suppress("UNCHECKED_CAST")
    override suspend fun getTracksByMediaIds(mediaIds: List<String>): List<SampleTrack> =
        murglar.getTracksByMediaIds(mediaIds).convertSampleTracks(unmappedPath()) as List<SampleTrack>


    private suspend fun listRoot(parentPath: Path, page: Int?, params: Map<String, String>) =
        listChildren(File(SampleMurglar.MUSIC_DIR), parentPath)

    private suspend fun listDir(parentPath: Path, page: Int?, params: Map<String, String>): List<Node> {
        val dir = File(murglar.decodeId(params["dirId"]!!) ?: "")
        return listChildren(dir, parentPath)
    }

    private fun listChildren(dir: File, parentPath: Path): List<Node> {
        val (dirs, tracks) = murglar.listAudioChildren(dir) ?: return emptyList()
        return dirs.map { subdirectoryNode("dir-${murglar.encodePath(it.absolutePath)}", it.name, parentPath) } +
            tracks.map { murglar.buildTrack(it).convertSampleTrack(parentPath) }
    }

    private suspend fun getTrack(parentPath: Path, params: Map<String, String>): Node {
        val track = murglar.getTrackById(params["trackId"]!!)
            ?: error("File not found: ${params["trackId"]}")
        return track.convertSampleTrack(parentPath)
    }

    private fun getTrackRelatedPaths(node: Node): List<NamedPath> = emptyList()


    private fun SampleTrack.convertSampleTrack(parentPath: Path) = convert(::trackNodePath, parentPath)

    private fun List<SampleTrack>.convertSampleTracks(parentPath: Path) = convert(::trackNodePath, parentPath)

    private fun trackNodePath(parentPath: Path, track: SampleTrack): Path =
        parentPath.child("track-${track.id}")
}
