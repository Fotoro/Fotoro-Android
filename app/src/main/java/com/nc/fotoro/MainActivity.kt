package com.nc.fotoro

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.NoCredentialException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import androidx.lifecycle.lifecycleScope
import com.nc.fotoro.ui.FotoroApp
import com.nc.fotoro.ui.theme.FotoroAndroidTheme
import kotlinx.coroutines.launch
import java.security.MessageDigest

class MainActivity : ComponentActivity() {
    private val model: AppViewModel by viewModels()
    private val syncHandler = Handler(Looper.getMainLooper())
    private val changedMedia = Runnable {
        model.syncOnNewMedia()
    }
    private val mediaObserver = object : ContentObserver(syncHandler) {
        override fun onChange(selfChange: Boolean) {
            syncHandler.removeCallbacks(changedMedia)
            syncHandler.postDelayed(changedMedia, 5_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val selectedFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
                val readable = uris.filter { uri ->
                    runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                        .onFailure { model.reportError("Could not keep access to ${uri.lastPathSegment ?: "a selected file"}") }
                        .isSuccess
                }
                model.uploadSelectedFiles(readable)
            }
            val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
            val mediaPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
                model.refreshMedia()
                if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            FotoroAndroidTheme {
                FotoroApp(model,
                    onGoogleSignIn = ::signInWithGoogle,
                    onBrowserSignIn = { model.startBrowserSignIn { url ->
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    } },
                    onPickFiles = { selectedFiles.launch(arrayOf("*/*")) },
                    onScanPairingQr = { GmsBarcodeScanning.getClient(this).startScan()
                        .addOnSuccessListener { result -> result.rawValue?.let(model::pairFromQr)
                            ?: model.reportError("QR code is empty") }
                        .addOnFailureListener { error -> model.reportError(error.message ?: "Could not scan QR code") }
                    },
                    onMediaPermission = {
                        val permissions = if (Build.VERSION.SDK_INT >= 34) arrayOf(
                            Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO,
                            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
                        else if (Build.VERSION.SDK_INT >= 33) arrayOf(
                            Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
                        else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
                        mediaPermission.launch(permissions)
                    })
            }
        }
    }

    override fun onResume() {
        super.onResume()
        model.refreshMedia()
        model.onForeground()
    }

    override fun onStart() {
        super.onStart()
        contentResolver.registerContentObserver(MediaStore.Files.getContentUri("external"), true, mediaObserver)
    }

    override fun onStop() {
        contentResolver.unregisterContentObserver(mediaObserver)
        syncHandler.removeCallbacks(changedMedia)
        super.onStop()
    }

    private fun signInWithGoogle() {
        val clientId = BuildConfig.FOTORO_GOOGLE_WEB_CLIENT_ID
        if (clientId.isBlank()) {
            model.reportError("Google sign-in needs FOTORO_GOOGLE_WEB_CLIENT_ID in local.properties")
            return
        }
        lifecycleScope.launch {
            try {
                val option = GetSignInWithGoogleOption.Builder(clientId).build()
                val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
                val credential = CredentialManager.create(this@MainActivity).getCredential(this@MainActivity, request).credential
                val google = (credential as? CustomCredential)?.takeIf {
                    it.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                }?.let { GoogleIdTokenCredential.createFrom(it.data) }
                    ?: error("Google did not return an ID token")
                model.signIn(google.idToken)
            } catch (_: GetCredentialCancellationException) {
                model.reportError("Google sign-in was cancelled.")
            } catch (_: NoCredentialException) {
                model.reportError("No usable Google account was found. Check the account in Android settings and try again.")
            } catch (error: GetCredentialException) {
                val fingerprint = installedSigningSha1()
                model.reportError("Google sign-in failed (${error.javaClass.simpleName}). " +
                    "Check the Android OAuth client: package $packageName, installed APK SHA-1 $fingerprint. " +
                    "${error.message.orEmpty().take(180)}")
            } catch (error: Exception) {
                model.reportError(error.message ?: "Google sign-in was cancelled")
            }
        }
    }

    private fun installedSigningSha1(): String = runCatching {
        val info = packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val signer = info.signingInfo?.apkContentsSigners?.firstOrNull() ?: return@runCatching "unknown"
        MessageDigest.getInstance("SHA-1").digest(signer.toByteArray())
            .joinToString(":") { "%02X".format(it) }
    }.getOrDefault("unknown")
}
