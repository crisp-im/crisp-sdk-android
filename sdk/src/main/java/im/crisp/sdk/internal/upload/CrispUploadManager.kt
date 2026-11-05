package im.crisp.sdk.internal.upload

import android.content.Context
import android.net.Uri
import android.webkit.ValueCallback
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import com.google.android.material.bottomsheet.BottomSheetDialog
import im.crisp.sdk.internal.permission.CrispPermissionManager
import im.crisp.sdk.internal.permission.CrispPermissionIntent
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal class CrispUploadManager(
    private val context: Context,
    private val permissionManager: CrispPermissionManager,
) {
    companion object {
        private const val CACHE_SUBFOLDER = "im.crisp.sdk"

        /**
         * Cleans up all files stored in the crisp cache subfolder.
         */
        fun clearCache(context: Context) {
            runCatching {
                val dir = File(context.cacheDir, CACHE_SUBFOLDER)
                if (dir.exists()) {
                    dir.listFiles()?.forEach { it.delete() }
                }
            }
        }
    }

    private var activeCallback: ValueCallback<Array<out Uri?>?>? = null
    private var pendingCameraUri: Uri? = null
    private var pendingCameraFile: File? = null

    private var pickMultipleVisualMediaLauncher: ActivityResultLauncher<PickVisualMediaRequest>? = null
    private var openMultipleDocumentsLauncher: ActivityResultLauncher<Array<String>>? = null
    private var takePictureLauncher: ActivityResultLauncher<Uri>? = null
    private var captureVideoLauncher: ActivityResultLauncher<Uri>? = null

    private var activeDialog: BottomSheetDialog? = null

    fun registerLaunchers(activity: ComponentActivity) {
        unregisterLaunchers()

        val keyPrefix = "im.crisp.sdk.internal.upload.CrispUploadManager"

        val multipleUrisCallback: (List<Uri>) -> Unit = { uris ->
            if (uris.isNotEmpty()) {
                handleResult(uris.toTypedArray())
            } else {
                cancelActiveCallback()
            }
        }

        pickMultipleVisualMediaLauncher = activity.activityResultRegistry.register(
            "$keyPrefix.PickMultipleVisualMedia",
            ActivityResultContracts.PickMultipleVisualMedia(),
            multipleUrisCallback,
        )

        openMultipleDocumentsLauncher = activity.activityResultRegistry.register(
            "$keyPrefix.OpenMultipleDocuments",
            ActivityResultContracts.OpenMultipleDocuments(),
            multipleUrisCallback,
        )

        val cameraCaptureCallback: (Boolean) -> Unit = { success ->
            val uri = pendingCameraUri
            if (success && (uri != null)) {
                clearPendingCameraMedia(false)
                handleResult(arrayOf(uri))
            } else {
                clearPendingCameraMedia(true)
                cancelActiveCallback()
            }
        }

        takePictureLauncher = activity.activityResultRegistry.register(
            "$keyPrefix.TakePicture",
            ActivityResultContracts.TakePicture(),
            cameraCaptureCallback,
        )

        captureVideoLauncher = activity.activityResultRegistry.register(
            "$keyPrefix.CaptureVideo",
            ActivityResultContracts.CaptureVideo(),
            cameraCaptureCallback,
        )
    }

    fun unregisterLaunchers() {
        pickMultipleVisualMediaLauncher?.unregister()
        pickMultipleVisualMediaLauncher = null
        openMultipleDocumentsLauncher?.unregister()
        openMultipleDocumentsLauncher = null
        takePictureLauncher?.unregister()
        takePictureLauncher = null
        captureVideoLauncher?.unregister()
        captureVideoLauncher = null
    }

    fun dismiss() {
        activeDialog?.dismiss()
        activeDialog = null
        clearPendingCameraMedia(true)
        cancelActiveCallback()
    }

    fun showChooser(filePathCallback: ValueCallback<Array<out Uri?>?>?) {
        // If there was an existing callback, cancel it first
        cancelActiveCallback()

        activeCallback = filePathCallback

        val bottomSheet = CrispUploadBottomSheet(context) { action ->
            when (action) {
                CrispUploadAction.LIBRARY -> launchPhotoLibrary()
                CrispUploadAction.PHOTO -> launchCameraPhoto()
                CrispUploadAction.VIDEO -> launchCameraVideo()
                CrispUploadAction.FILES -> launchFiles()
            }
        }

        bottomSheet.setOnCancelListener {
            activeDialog = null
            cancelActiveCallback()
        }

        bottomSheet.setOnDismissListener {
            activeDialog = null
        }

        activeDialog = bottomSheet
        bottomSheet.show()
    }

    private fun launchPhotoLibrary() {
        val launcher = pickMultipleVisualMediaLauncher
        if (launcher != null) {
            val request = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
            launcher.launch(request)
        } else {
            cancelActiveCallback()
        }
    }

    private fun launchFiles() {
        val launcher = openMultipleDocumentsLauncher
        if (launcher != null) {
            launcher.launch(arrayOf("*/*"))
        } else {
            cancelActiveCallback()
        }
    }

    private fun launchCameraPhoto() {
        checkPermissionAndLaunchCamera {
            launchCameraMedia(
                suffix = ".jpg",
                prefix = "IMG_",
                launcher = takePictureLauncher,
            )
        }
    }

    private fun launchCameraVideo() {
        checkPermissionAndLaunchCamera {
            launchCameraMedia(
                suffix = ".mp4",
                prefix = "VID_",
                launcher = captureVideoLauncher,
            )
        }
    }

    private fun checkPermissionAndLaunchCamera(onPermissionGranted: () -> Unit) {
        permissionManager.requestNativePermissions(
            intent = CrispPermissionIntent.MEDIA_UPLOAD,
        ) { isGranted ->
            when {
                // Chooser was dismissed while the permission flow was running
                activeCallback == null -> Unit
                isGranted -> onPermissionGranted()
                else -> cancelActiveCallback()
            }
        }
    }

    private fun launchCameraMedia(
        suffix: String,
        prefix: String,
        launcher: ActivityResultLauncher<Uri>?,
    ) {
        if (launcher == null) {
            cancelActiveCallback()
            return
        }

        val file = createTempMediaFile(suffix = suffix, prefix = prefix)
        if (file == null) {
            cancelActiveCallback()
            return
        }

        val authority = "${context.packageName}.im.crisp.sdk.fileprovider"
        val uri = runCatching {
            FileProvider.getUriForFile(context, authority, file)
        }.getOrNull()

        if (uri == null) {
            file.delete()
            cancelActiveCallback()
            return
        }

        pendingCameraFile = file
        pendingCameraUri = uri

        launcher.launch(uri)
    }

    private fun createTempMediaFile(suffix: String, prefix: String): File? {
        return runCatching {
            val storageDir = File(context.cacheDir, CACHE_SUBFOLDER).apply {
                if (!exists()) mkdirs()
            }
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            File.createTempFile("$prefix${timeStamp}_", suffix, storageDir)
        }.getOrNull()
    }

    private fun handleResult(uris: Array<out Uri?>?) {
        val callback = activeCallback
        activeCallback = null
        callback?.onReceiveValue(uris)
    }

    private fun cancelActiveCallback() {
        val callback = activeCallback
        activeCallback = null
        callback?.onReceiveValue(null)
    }

    private fun clearPendingCameraMedia(delete: Boolean) {
        if (delete) pendingCameraFile?.delete()
        pendingCameraFile = null
        pendingCameraUri = null
    }
}
