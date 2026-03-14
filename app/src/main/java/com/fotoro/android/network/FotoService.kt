package com.fotoro.android.network
import com.fotoro.android.model.UploadResponse
import okhttp3.MultipartBody
import retrofit2.Response
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part

interface FotoService {
    @Multipart
    @POST("upload")
    suspend fun uploadPhoto(
        @Part file: MultipartBody.Part
    ): Response<UploadResponse>
}
