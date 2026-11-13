package com.badmanners.murglar.lib.sample.node

import com.badmanners.murglar.lib.core.model.node.NamedPath
import com.badmanners.murglar.lib.core.model.node.Node
import com.badmanners.murglar.lib.core.model.node.NodeParameters.PagingType.NON_PAGEABLE
import com.badmanners.murglar.lib.core.model.node.NodeType.NODE
import com.badmanners.murglar.lib.core.model.node.NodeType.TRACK
import com.badmanners.murglar.lib.core.model.node.Path
import com.badmanners.murglar.lib.core.node.BaseNodeResolver
import com.badmanners.murglar.lib.core.node.Directory
import com.badmanners.murglar.lib.core.node.Root
import com.badmanners.murglar.lib.core.node.Track
import com.badmanners.murglar.lib.sample.SampleMurglar
import com.badmanners.murglar.lib.sample.localization.SampleMessages
import com.badmanners.murglar.lib.sample.model.track.SampleTrack


class SampleNodeResolver(
    murglar: SampleMurglar,
    messages: SampleMessages
) : BaseNodeResolver<SampleMurglar, SampleMessages>(murglar, messages) {

    private companion object {
        const val TRACKS_FOLDER_NAME = "Треки"
    }

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
        Directory(
            pattern = "*/files-<dirId>",
            paging = NON_PAGEABLE,
            hasSubdirectories = false,
            contentNodeType = TRACK,
            nodeContentSupplier = ::listFiles
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
        listChildren("", parentPath)

    private suspend fun listDir(parentPath: Path, page: Int?, params: Map<String, String>): List<Node> {
        val rel = murglar.decodeId(params["dirId"]!!) ?: return emptyList()
        return listChildren(rel, parentPath)
    }

    private suspend fun listFiles(parentPath: Path, page: Int?, params: Map<String, String>): List<Node> {
        val rel = murglar.decodeId(params["dirId"]!!) ?: return emptyList()
        val (_, files) = murglar.listSaf(rel) ?: return emptyList()
        return files.map { murglar.buildTrack(it.relPath).convertSampleTrack(parentPath) }
    }

    private fun listChildren(rel: String, parentPath: Path): List<Node> {
        val (dirs, files) = murglar.listSaf(rel) ?: return emptyList()
        val nodes = dirs.map { subdirectoryNode("dir-${murglar.encodePath(it.relPath)}", it.name, parentPath) }
        return if (files.isEmpty())
            nodes
        else
            nodes + subdirectoryNode("files-${murglar.encodePath(rel)}", TRACKS_FOLDER_NAME, parentPath)
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
