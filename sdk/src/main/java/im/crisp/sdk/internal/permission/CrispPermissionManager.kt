package im.crisp.sdk.internal.permission

import android.Manifest
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.webkit.PermissionRequest
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import im.crisp.sdk.internal.environment.Level
import im.crisp.sdk.internal.environment.findActivity
import im.crisp.sdk.internal.environment.log

internal class CrispPermissionManager(private val context: Context) {
    companion object {
        private const val LOG_TAG = "CrispPermission"

        private val ALLOWED_WEB_PERMISSIONS = setOf(
            PermissionRequest.RESOURCE_AUDIO_CAPTURE,
            PermissionRequest.RESOURCE_VIDEO_CAPTURE,
        )

        private val REQUIRED_MANIFEST_PERMISSIONS = mapOf(
            PermissionRequest.RESOURCE_AUDIO_CAPTURE to listOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.MODIFY_AUDIO_SETTINGS,
            ),
            PermissionRequest.RESOURCE_VIDEO_CAPTURE to listOf(
                Manifest.permission.CAMERA,
            ),
        )

        private val REQUIRED_SYSTEM_FEATURES = mapOf(
            PermissionRequest.RESOURCE_VIDEO_CAPTURE to listOf(
                PackageManager.FEATURE_CAMERA_ANY,
            ),
        )

        private val RUNTIME_PERMISSIONS = mapOf(
            PermissionRequest.RESOURCE_AUDIO_CAPTURE to Manifest.permission.RECORD_AUDIO,
            PermissionRequest.RESOURCE_VIDEO_CAPTURE to Manifest.permission.CAMERA,
        )
    }

    private data class Permission(
        val resource: String,
        val isAvailable: Boolean,
        val isGranted: Boolean,
    )

    /**
     * A permission flow in progress. Either answers a Web [PermissionRequest] or a native request
     * made through [requestNativePermissions]. Both share the same launchers, dialogs and denial handling.
     */
    private class Session(
        val webRequest: PermissionRequest? = null,
        /** Why the permissions are requested, from the ones still pending. */
        val intent: (pendingPermissions: List<String>) -> CrispPermissionIntent,
        /** Runtime permissions still to be granted, evaluated against the current state. */
        val pendingPermissions: () -> List<String>,
        /** Delivers the outcome, evaluated against the current state. */
        val onFinished: () -> Unit,
    )

    private val manifestPermissions: Set<String> by lazy {
        getManifestPermissions(context)
    }

    private var requestMultiplePermissionsLauncher: ActivityResultLauncher<Array<String>>? = null
    private var appSettingsLauncher: ActivityResultLauncher<Intent>? = null

    private var activeSession: Session? = null
    private var activeDialog: Dialog? = null
    private var systemPermissionCallback: (() -> Unit)? = null

    fun registerLaunchers(activity: ComponentActivity) {
        unregisterLaunchers()

        val keyPrefix = "im.crisp.sdk.internal.permission.CrispPermissionManager"

        requestMultiplePermissionsLauncher = activity.activityResultRegistry.register(
            "$keyPrefix.RequestMultiplePermissions",
            ActivityResultContracts.RequestMultiplePermissions(),
        ) {
            val callback = systemPermissionCallback
            systemPermissionCallback = null
            if (callback != null) {
                callback.invoke()
            } else {
                finishActiveSession()
            }
        }

        appSettingsLauncher = activity.activityResultRegistry.register(
            "$keyPrefix.AppSettings",
            ActivityResultContracts.StartActivityForResult(),
        ) {
            finishActiveSession()
        }
    }

    fun unregisterLaunchers() {
        requestMultiplePermissionsLauncher?.unregister()
        requestMultiplePermissionsLauncher = null
        appSettingsLauncher?.unregister()
        appSettingsLauncher = null
    }

    fun dismiss() {
        dismissActiveDialog()
        systemPermissionCallback = null
        finishActiveSession()
    }

    /**
     * Answers a permission request coming from the Web client.
     */
    fun requestWebPermissions(request: PermissionRequest) {
        val hasAllowedPermission = request.resources.any { ALLOWED_WEB_PERMISSIONS.contains(it) }
        if (!hasAllowedPermission) {
            request.deny()
            return
        }

        val statuses = request.resources.map { checkPermission(it) }
        logMissingPrerequisites(statuses)

        if (statuses.none { it.isAvailable }) {
            request.deny()
            return
        }

        startSession(
            Session(
                webRequest = request,
                intent = CrispPermissionIntent::forWebRequest,
                pendingPermissions = {
                    request.resources.map { checkPermission(it) }
                        .filter { it.isAvailable && !it.isGranted }
                        .mapNotNull { RUNTIME_PERMISSIONS[it.resource] }
                },
                onFinished = { resolveResources(request, request.resources.map { checkPermission(it) }) },
            ),
        )
    }

    /**
     * Makes sure the runtime permissions needed by a native feature are granted,
     * asking the user (Rationale, system prompt, then Settings if permanently denied) when they are not.
     *
     * Permissions that are not declared in the host app manifest are ignored, as the system does not
     * require them to be granted.
     *
     * @param intent Why the permissions are requested: determines both the permissions and the dialogs' texts.
     * @param onResult Called once with `true` if all the permissions are granted, `false` otherwise.
     */
    fun requestNativePermissions(
        intent: CrispPermissionIntent,
        onResult: (isGranted: Boolean) -> Unit,
    ) {
        val pendingPermissions = {
            intent.permissions.filter { manifestPermissions.contains(it) && !isRuntimePermissionGranted(it) }
        }
        startSession(
            Session(
                intent = { intent },
                pendingPermissions = pendingPermissions,
                onFinished = { onResult(pendingPermissions().isEmpty()) },
            ),
        )
    }

    fun onPermissionRequestCanceled(request: PermissionRequest?) {
        if (request != null && activeSession?.webRequest == request) {
            dismissActiveDialog()
            systemPermissionCallback = null
            activeSession = null
        }
    }

    private fun startSession(session: Session) {
        // A new flow replaces the previous one, which is answered with its current state
        if (activeSession != null) {
            dismissActiveDialog()
            systemPermissionCallback = null
            finishActiveSession()
        }
        activeSession = session

        processResolution(session)
    }

    private fun processResolution(session: Session) {
        val pendingPermissions = session.pendingPermissions()
        if (pendingPermissions.isEmpty()) {
            finish(session)
            return
        }

        val activity = context.findActivity()
        if (activity == null || activity.isFinishing || activity.isDestroyed) {
            finish(session)
            return
        }

        val shouldShowRationale = pendingPermissions.any { permission ->
            ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
        }

        when {
            // Case 1: User denied once previously (not permanently denied) -> Show Rationale dialog
            shouldShowRationale -> {
                showRationaleDialog(activity, pendingPermissions, session)
            }
            // Case 2: First time or "Ask every time" / "Only this time" -> Ask System directly
            else -> {
                requestSystemPermission(
                    permissions = pendingPermissions,
                    onResult = {
                        val ungrantedPermissions = session.pendingPermissions()
                        val activity = context.findActivity()

                        if (ungrantedPermissions.isNotEmpty() &&
                            activity != null && !activity.isFinishing && !activity.isDestroyed) {
                            val isPermanentlyDenied = ungrantedPermissions.any { permission ->
                                !ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
                            }
                            if (isPermanentlyDenied) {
                                // Show Settings dialog now that system prompt was launched and returned permanently denied
                                showSettingsDialog(activity, ungrantedPermissions, session)
                                return@requestSystemPermission
                            }
                        }

                        finish(session)
                    },
                    fallback = { finish(session) },
                )
            }
        }
    }

    private fun finishActiveSession() {
        activeSession?.let { finish(it) }
    }

    private fun finish(session: Session) {
        if (session !== activeSession) return
        activeSession = null
        session.onFinished()
    }

    private fun resolveResources(
        request: PermissionRequest,
        statuses: List<Permission>,
    ) {
        val grantedResources = statuses
            .filter { it.isGranted }
            .map { it.resource }
            .toTypedArray()

        if (grantedResources.isNotEmpty()) {
            request.grant(grantedResources)
        } else {
            request.deny()
        }
    }

    private fun requestSystemPermission(
        permissions: List<String>,
        onResult: (() -> Unit)? = null,
        fallback: () -> Unit,
    ) {
        val launcher = requestMultiplePermissionsLauncher
        if (launcher != null) {
            systemPermissionCallback = onResult
            runCatching {
                launcher.launch(permissions.toTypedArray())
            }.onFailure {
                systemPermissionCallback = null
                fallback.invoke()
            }
        } else {
            fallback.invoke()
        }
    }

    private fun launchAppSettings() {
        val launcher = appSettingsLauncher
        if (launcher != null) {
            runCatching {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", context.packageName, null)
                }
                launcher.launch(intent)
            }.onFailure {
                finishActiveSession()
            }
        } else {
            finishActiveSession()
        }
    }

    private fun showRationaleDialog(
        activity: Activity,
        pendingPermissions: List<String>,
        session: Session,
    ) {
        dismissActiveDialog()
        activeDialog = CrispPermissionDialog.showRationaleDialog(
            activity = activity,
            intent = session.intent(pendingPermissions),
            onRequestSystemPermission = {
                activeDialog = null
                requestSystemPermission(pendingPermissions) { finish(session) }
            },
            onCancel = {
                activeDialog = null
                finish(session)
            },
        )
    }

    private fun showSettingsDialog(
        activity: Activity,
        pendingPermissions: List<String>,
        session: Session,
    ) {
        dismissActiveDialog()
        activeDialog = CrispPermissionDialog.showSettingsDialog(
            activity = activity,
            intent = session.intent(pendingPermissions),
            onOpenSettings = {
                activeDialog = null
                launchAppSettings()
            },
            onCancel = {
                activeDialog = null
                finish(session)
            },
        )
    }

    private fun dismissActiveDialog() {
        runCatching { activeDialog?.dismiss() }
        activeDialog = null
    }

    private fun checkPermission(resource: String): Permission {
        val isAllowed = ALLOWED_WEB_PERMISSIONS.contains(resource)

        val requiredPermissions = REQUIRED_MANIFEST_PERMISSIONS[resource] ?: emptyList()
        val hasManifestPermissions = requiredPermissions.all { manifestPermissions.contains(it) }

        val requiredFeatures = REQUIRED_SYSTEM_FEATURES[resource] ?: emptyList()
        val hasFeatures = requiredFeatures.all { context.packageManager.hasSystemFeature(it) }

        val isAvailable = isAllowed && hasManifestPermissions && hasFeatures

        val runtimePermission = RUNTIME_PERMISSIONS[resource]
        val isGranted = isAvailable && (runtimePermission != null) && isRuntimePermissionGranted(runtimePermission)

        return Permission(
            resource = resource,
            isAvailable = isAvailable,
            isGranted = isGranted,
        )
    }

    private fun isRuntimePermissionGranted(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun getManifestPermissions(context: Context): Set<String> {
        return runCatching {
            val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()),
                ).requestedPermissions
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_PERMISSIONS,
                ).requestedPermissions
            }
            permissions?.toSet().orEmpty()
        }.getOrDefault(emptySet())
    }

    private fun logMissingPrerequisites(statuses: List<Permission>) {
        for ((resource, isAvailable) in statuses) {
            if (!isAvailable) {
                when (resource) {
                    PermissionRequest.RESOURCE_AUDIO_CAPTURE -> {
                        log(
                            Level.ERROR,
                            LOG_TAG,
                            "Cannot grant android.webkit.resource.AUDIO_CAPTURE permission: " +
                                    "android.permission.RECORD_AUDIO and android.permission.MODIFY_AUDIO_SETTINGS " +
                                    "must be declared in AndroidManifest.xml",
                        )
                    }
                    PermissionRequest.RESOURCE_VIDEO_CAPTURE -> {
                        val missingFeatures = REQUIRED_SYSTEM_FEATURES[resource]?.filterNot {
                            context.packageManager.hasSystemFeature(it)
                        } ?: emptyList()
                        if (missingFeatures.isNotEmpty()) {
                            log(
                                Level.ERROR,
                                LOG_TAG,
                                "Cannot grant android.webkit.resource.VIDEO_CAPTURE permission: " +
                                        "camera feature is not available on this device",
                            )
                        } else {
                            log(
                                Level.ERROR,
                                LOG_TAG,
                                "Cannot grant android.webkit.resource.VIDEO_CAPTURE permission: " +
                                        "android.permission.CAMERA must be declared in AndroidManifest.xml",
                            )
                        }
                    }
                }
            }
        }
    }
}
