package com.fotoro.android.utils

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

object FileUtils {

    fun uriToFile(uri: Uri, context: Context): File? {

        val input =
            context.contentResolver.openInputStream(uri) ?: return null

        val file =
            File(context.cacheDir, "temp_${System.currentTimeMillis()}.jpg")

        FileOutputStream(file).use { output ->
            input.copyTo(output)
        }

        return file
    }
}
