package com.nc.fotoro.data

import org.json.JSONObject

data class ServerAsset(
    val id: String,
    val name: String,
    val isVideo: Boolean,
    val thumbnailUrl: String,
    val detailUrl: String,
    val contentUrl: String,
    val needsAuth: Boolean,
)

fun JSONObject.toServerAsset(server: PairedServer): ServerAsset {
    fun url(direct: String, relative: String): Pair<String, Boolean> {
        val signed = optString(direct)
        if (signed.startsWith("https://")) return signed to false
        val path = optString(relative)
        return (if (path.startsWith("/")) server.url + path else "") to true
    }
    val thumb = url("direct_thumbnail_url", "thumbnail_url")
    val detail = url("direct_detail_url", "detail_url")
    val content = url("direct_content_url", "content_url")
    return ServerAsset(getString("id"), optString("original_name", "Media"),
        optString("media_kind") == "video", thumb.first, detail.first.ifBlank { content.first },
        content.first, thumb.second || detail.second || content.second)
}
