package com.fotoro.android.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.fotoro.android.viewmodel.UploadViewModel
import androidx.lifecycle.viewmodel.compose.viewModel


@Composable
fun FotoroScreen(viewModel: UploadViewModel = viewModel()) {

    val context = LocalContext.current
    val status = viewModel.status

    val launcher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.GetContent()
        ) { uri ->

            uri?.let {
                viewModel.upload(it, context)
            }
        }

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {

        Button(
            onClick = { launcher.launch("image/*") }
        ) {
            Text("Select Photo")
        }

        Text(status)
    }
}
