package com.nc.fotoro.data

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class LocalAsset(
    val id: Long,
    val uri: Uri,
    val stableId: String,
    val name: String,
    val mimeType: String,
    val size: Long,
    val capturedAtMillis: Long,
    val isVideo: Boolean,
)

class MediaCatalog(private val context: Context) {
    suspend fun count(): Int = withContext(Dispatchers.IO) {
        if (!hasAccess()) return@withContext 0
        context.contentResolver.query(MediaStore.Files.getContentUri("external"),
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?)",
            arrayOf(MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
                MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString()), null
        )?.use { it.count } ?: 0
    }

    fun generation(): Pair<String, Long>? = try {
        MediaStore.getVersion(context, MediaStore.VOLUME_EXTERNAL) to
            MediaStore.getGeneration(context, MediaStore.VOLUME_EXTERNAL)
    } catch (_: Exception) { null }

    fun hasAccess(): Boolean {
        val permissions = if (Build.VERSION.SDK_INT >= 33) {
            listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO) +
                if (Build.VERSION.SDK_INT >= 34) listOf(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) else emptyList()
        } else listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        return permissions.any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    }

    suspend fun list(limit: Int = Int.MAX_VALUE): List<LocalAsset> = withContext(Dispatchers.IO) {
        if (!hasAccess()) return@withContext emptyList()
        val uri = MediaStore.Files.getContentUri("external")
        val columns = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.Files.FileColumns.MEDIA_TYPE)
        val imageType = MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE
        val videoType = MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
        val result = ArrayList<LocalAsset>(minOf(limit, 512))
        context.contentResolver.query(uri, columns,
            "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?)",
            arrayOf(imageType.toString(), videoType.toString()),
            "${MediaStore.MediaColumns.DATE_TAKEN} DESC, ${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val mimeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val addedColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            val takenColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
            val modifiedColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            val typeColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
            while (cursor.moveToNext() && result.size < limit) {
                val id = cursor.getLong(idColumn)
                val size = cursor.getLong(sizeColumn)
                if (size <= 0) continue
                val video = cursor.getInt(typeColumn) == videoType
                val contentUri = ContentUris.withAppendedId(
                    if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                val modified = cursor.getLong(modifiedColumn)
                val taken = cursor.getLong(takenColumn).takeIf { it > 0 }
                    ?: (modified.takeIf { it > 0 } ?: cursor.getLong(addedColumn).takeIf { it > 0 } ?: 0L) * 1000
                result += LocalAsset(id, contentUri, "android:$id:$size:$modified",
                    cursor.getString(nameColumn) ?: "media-$id", cursor.getString(mimeColumn) ?: if (video) "video/mp4" else "image/jpeg",
                    size, taken, video)
            }
        }
        result
    }

    suspend fun sha256(asset: LocalAsset): String = withContext(Dispatchers.IO) {
        val digest = MessageDigest.getInstance("SHA-256")
        context.contentResolver.openInputStream(asset.uri)?.use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        } ?: error("This photo is no longer accessible")
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    suspend fun uploadChunks(asset: LocalAsset, startOffset: Long,
                             onChunk: suspend (Long, ByteArray, Int) -> Unit) = withContext(Dispatchers.IO) {
        context.contentResolver.openFileDescriptor(asset.uri, "r")?.use { descriptor ->
            java.io.FileInputStream(descriptor.fileDescriptor).use { stream ->
                if (startOffset > 0) {
                    try { stream.channel.position(startOffset) }
                    catch (_: Exception) {
                        // Some Storage Access Framework providers expose pipes rather than seekable files.
                        var remaining = startOffset
                        val discard = ByteArray(64 * 1024)
                        while (remaining > 0) {
                            val count = stream.read(discard, 0, minOf(remaining, discard.size.toLong()).toInt())
                            if (count < 0) error("Media changed while resuming upload")
                            remaining -= count
                        }
                    }
                }
                val buffer = ByteArray(4 * 1024 * 1024)
                var offset = startOffset
                while (offset < asset.size) {
                    val target = minOf(buffer.size.toLong(), asset.size - offset).toInt()
                    var read = 0
                    while (read < target) {
                        val count = stream.read(buffer, read, target - read)
                        if (count < 0) error("Media changed while uploading")
                        read += count
                    }
                    onChunk(offset, buffer, read)
                    offset += read
                }
            }
        } ?: error("This photo is no longer accessible")
    }
}
