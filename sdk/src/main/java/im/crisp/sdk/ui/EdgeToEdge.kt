package im.crisp.sdk.ui

/**
 * Which edges of an embedded chatbox are drawn behind a system bar: [top] for the status bar, [bottom] for
 * the navigation bar.
 *
 * The chatbox extends behind the bar of an enabled edge and keeps its content clear of it. On a disabled edge, it
 * does not account for the bar, so your layout must keep the chatbox away from it.
 *
 * Choose it from what surrounds the chatbox, when its `Activity` is edge-to-edge (with `enableEdgeToEdge()`, and
 * always when targeting Android 15 or later):
 *
 * | The chatbox is | Value |
 * |---|---|
 * | Fullscreen | [ALL] |
 * | Below a top app bar | [BOTTOM] |
 * | Above a bottom navigation bar | [TOP] |
 * | Between both, or the `Activity` is not edge-to-edge | [NONE] |
 *
 * The keyboard and the left and right insets are handled whatever the value, and modal bottom sheets handle
 * their insets themselves.
 *
 * It is passed by the `edgeToEdge` parameter of [CrispChatbox] and of [CrispFragment.newInstance], by the
 * `app:crisp_sdk_edge_to_edge` attribute (`none`, `top`, `bottom` or `all`) of [CrispChatboxView] and
 * [CrispFragment], or by [CrispChatboxView.enableEdgeToEdge]:
 * ```
 * // Below a top app bar
 * Scaffold(topBar = { TopAppBar(title = { Text("Support") }) }) { innerPadding ->
 *     CrispChatbox(
 *         websiteId = "YOUR_WEBSITE_ID",
 *         modifier = Modifier
 *             .fillMaxSize()
 *             .padding(top = innerPadding.calculateTopPadding()),
 *         edgeToEdge = EdgeToEdge.BOTTOM
 *     )
 * }
 * ```
 *
 * The test app has samples [fullscreen](https://github.com/crisp-im/crisp-sdk-android/blob/beta/app/src/main/java/im/crisp/catalog/FullscreenComposeActivity.kt),
 * [below a top app bar](https://github.com/crisp-im/crisp-sdk-android/blob/beta/app/src/main/java/im/crisp/catalog/AppBarComposeActivity.kt)
 * and [above a bottom navigation bar](https://github.com/crisp-im/crisp-sdk-android/blob/beta/app/src/main/res/layout/activity_view_xml.xml).
 */
data class EdgeToEdge(
    val top: Boolean = false,
    val bottom: Boolean = false,
) {
    companion object {
        val NONE = EdgeToEdge(top = false, bottom = false);
        val TOP = EdgeToEdge(top = true, bottom = false);
        val BOTTOM = EdgeToEdge(top = false, bottom = true);
        val ALL = EdgeToEdge(top = true, bottom = true);

        internal const val NONE_ATTR_VALUE = 0
        internal const val TOP_ATTR_VALUE = 1
        internal const val BOTTOM_ATTR_VALUE = 2
        internal const val ALL_ATTR_VALUE = 3

        internal fun fromFlags(flags: Int): EdgeToEdge = EdgeToEdge(
            top = (flags and TOP_ATTR_VALUE) != 0,
            bottom = (flags and BOTTOM_ATTR_VALUE) != 0
        )
    }

    internal fun toFlags(): Int = (if (top) TOP_ATTR_VALUE else 0) or (if (bottom) BOTTOM_ATTR_VALUE else 0)
}