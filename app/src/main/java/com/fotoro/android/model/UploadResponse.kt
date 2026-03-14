package com.fotoro.android.model

data class UploadResponse(
    val filename: String,
    val size: Int,
    val hash: String?
)
