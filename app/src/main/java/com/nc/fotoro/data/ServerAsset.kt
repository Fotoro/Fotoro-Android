package com.nc.fotoro.data

import org.json.JSONObject

data class ServerAsset(
    val id: String,
    val name: String,
    val isVideo: Boolean,
    val mediaKind: String,
    val mimeType: String,
    val fileSize: Long,
    val capturedAt: String,
    val safeToDelete: Boolean,
    val verifiedCopies: Int,
    val thumbnailUrl: String,
    val detailUrl: String,
    val contentUrl: String,
    val needsAuth: Boolean,
) {
    val isDocument: Boolean get() = mediaKind == "document"
}

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
    val kind = optString("media_kind", "photo")
    return ServerAsset(getString("id"), optString("original_name", "Media"),
        kind == "video", kind, optString("mime_type"), optLong("file_size"),
        optString("captured_at", optString("imported_at")), optBoolean("safe_to_delete"),
        optInt("verified_storage_copies"), thumb.first,
        detail.first.ifBlank { content.first }, content.first,
        thumb.second || detail.second || content.second)
}
