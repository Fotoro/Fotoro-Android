package com.fotoro.android.repository

import android.content.Context
import android.net.Uri
import com.fotoro.android.utils.FileUtils
import com.fotoro.android.network.FotoService
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody


class FotoRepository(
    private val service: FotoService
) {

    suspend fun uploadPhoto(uri: Uri, context: Context): Result<String> {

        val file = FileUtils.uriToFile(uri, context)
            ?: return Result.failure(Exception("File error"))

        val requestFile =
            file.asRequestBody("image/jpeg".toMediaType())

        val part =
            MultipartBody.Part.createFormData(
                "file",
                file.name,
                requestFile
            )

        val response = service.uploadPhoto(part)

        return if (response.isSuccessful) {
            Result.success("Uploaded: ${response.body()?.filename}")
        } else {
            Result.failure(Exception("Upload failed"))
        }
    }
}
