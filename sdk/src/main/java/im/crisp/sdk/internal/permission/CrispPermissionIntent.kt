package im.crisp.sdk.internal.permission

import android.Manifest
import im.crisp.sdk.R

/**
 * Why a runtime permission is requested.
 * Drives the runtime permissions to ask for and the texts of the Rationale and Settings dialogs.
 */
internal enum class CrispPermissionIntent(
    val permissions: List<String>,
    val titleRes: Int,
    val messageRes: Int,
) {
    /** Photo or video capture to upload a file. */
    MEDIA_UPLOAD(
        permissions = listOf(Manifest.permission.CAMERA),
        titleRes = R.string.crisp_sdk_alert_permission_upload_title,
        messageRes = R.string.crisp_sdk_alert_permission_upload_message,
    ),

    /** Audio call and recording. */
    AUDIO_UPLOAD(
        permissions = listOf(Manifest.permission.RECORD_AUDIO),
        titleRes = R.string.crisp_sdk_alert_permission_audio_title,
        messageRes = R.string.crisp_sdk_alert_permission_audio_message,
    ),

    /** Video call, camera permission missing */
    VIDEO_STREAMING(
        permissions = listOf(Manifest.permission.CAMERA),
        titleRes = R.string.crisp_sdk_alert_permission_video_title,
        messageRes = R.string.crisp_sdk_alert_permission_video_message,
    ),

    /** Video call, both permissions missing */
    AUDIO_VIDEO_STREAMING(
        permissions = listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA),
        titleRes = R.string.crisp_sdk_alert_permission_audio_video_title,
        messageRes = R.string.crisp_sdk_alert_permission_audio_video_message,
    );

    companion object {
        /**
         * Intent of the Web client's request for the given runtime [permissions].
         */
        fun forWebRequest(permissions: List<String>): CrispPermissionIntent {
            val requestsAudio = permissions.contains(Manifest.permission.RECORD_AUDIO)
            val requestsVideo = permissions.contains(Manifest.permission.CAMERA)
            return when {
                requestsAudio && requestsVideo -> AUDIO_VIDEO_STREAMING
                requestsVideo -> VIDEO_STREAMING
                else -> AUDIO_UPLOAD
            }
        }
    }
}
