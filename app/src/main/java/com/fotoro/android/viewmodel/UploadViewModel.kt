package com.fotoro.android.viewmodel

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fotoro.android.network.RetrofitClient
import com.fotoro.android.repository.FotoRepository
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch


class UploadViewModel : ViewModel() {

    private val repository =
        FotoRepository(RetrofitClient.fotoService)

    var status by mutableStateOf("")
        private set

    fun upload(uri: Uri, context: Context) {

        viewModelScope.launch {

            status = "Uploading..."

            val result = repository.uploadPhoto(uri, context)

            status =
                result.getOrElse { "Upload Failed" }
        }
    }
}