package im.crisp.sdk.ui

import android.content.res.Configuration
import android.os.Build
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animate
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import im.crisp.sdk.R
import im.crisp.sdk.api.CrispChatboxController
import im.crisp.sdk.api.CrispConfig
import im.crisp.sdk.api.Presentation
import im.crisp.sdk.internal.environment.findActivity
import im.crisp.sdk.internal.environment.findDialogWindow
import im.crisp.sdk.internal.views.CrispSheetDrag
import im.crisp.sdk.internal.views.CrispSheetDragHost
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

// The slide in progress from which the chatbox WebView is created
private const val SLIDE_IN_THRESHOLD = 0.99f

// The standard MotionScheme of the Material 3 sheet, not accessible
// defaultSpatialSpec(): showing the sheet, and settling it once released
private fun <T> sheetSpatialSpring() = spring<T>(dampingRatio = 0.9f, stiffness = 700f)

// fastEffectsSpec(): hiding the sheet
private fun <T> sheetFastEffectsSpring() = spring<T>(dampingRatio = 1f, stiffness = 3800f)

// defaultEffectsSpec(): fading the scrim
private fun <T> sheetEffectsSpring() = spring<T>(dampingRatio = 1f, stiffness = 1600f)

/**
 * The chatbox in a modal bottom sheet, see the overload taking a [CrispConfig].
 */
@Composable
fun CrispBottomSheetModal(
    websiteId: String,
    modifier: Modifier = Modifier,
    tokenId: String? = null,
    controller: CrispChatboxController = rememberCrispChatboxController(),
    onDismissRequest: () -> Unit
) {
    CrispBottomSheetModal(
        config = CrispConfig(websiteId = websiteId, tokenId = tokenId),
        modifier = modifier,
        controller = controller,
        onDismissRequest = onDismissRequest,
    )
}

/**
 * The chatbox in a modal bottom sheet, which can be swiped down to dismiss it, as a Material 3 `ModalBottomSheet`.
 *
 * @param onDismissRequest called once the sheet is hidden: closed from the chatbox, swiped down, or dismissed by
 * a back press or a tap outside of it. As for a Material 3 `ModalBottomSheet`, remove this sheet from the
 * composition then: otherwise its dialog stays shown, hidden but still catching touches.
 */
@Composable
fun CrispBottomSheetModal(
    config: CrispConfig,
    modifier: Modifier = Modifier,
    controller: CrispChatboxController = rememberCrispChatboxController(),
    onDismissRequest: () -> Unit
) {
    val currentOnDismissRequest by rememberUpdatedState(onDismissRequest)
    // Shown once composed, so that it slides in
    val sheetVisibility = remember { MutableTransitionState(false) }
    // Swiped out of the screen, dismissed without sliding out
    var isSwipedOut by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        sheetVisibility.targetState = true
    }

    // Once slid out, as the Material 3 sheet once hidden
    LaunchedEffect(sheetVisibility) {
        snapshotFlow { sheetVisibility.targetState }.first { it }
        snapshotFlow { !sheetVisibility.targetState && sheetVisibility.isIdle }.first { it }
        currentOnDismissRequest()
    }

    fun dismiss() {
        if (!isSwipedOut) {
            sheetVisibility.targetState = false
        }
    }

    Dialog(
        onDismissRequest = { dismiss() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        val content = LocalView.current
        val density = LocalDensity.current.density
        val scope = rememberCoroutineScope()

        // Swipe down on the chatbox: the whole dialog content moves as a view, outside of Compose,
        // as moving the AndroidView in Compose lays out the WebView again on every frame.
        // The scrim is moved back.
        var sheetOffset by remember { mutableFloatStateOf(0f) }
        // As the Material 3 sheet, the scrim fades out while the sheet would be dismissed if released
        var isScrimVisible by remember { mutableStateOf(true) }
        // Where the sheet is hidden, its top at the bottom of the dialog
        var sheetHeight by remember { mutableIntStateOf(0) }
        val sheetDragHost = remember(content, density, scope) {
            object : CrispSheetDragHost {
                private var settleJob: Job? = null

                override fun onDragStart(): Float {
                    settleJob?.cancel()
                    settleJob = null
                    isSwipedOut = false
                    return content.translationY
                }

                override fun onDrag(offsetY: Float) {
                    content.translationY = offsetY
                    sheetOffset = offsetY
                    isScrimVisible = !CrispSheetDrag.shouldDismissCompose(
                        offsetY,
                        velocityY = 0f,
                        density = density
                    )
                }

                // As the Material 3 sheet settling once released, from where and how fast it was released
                override fun onRelease(
                    offsetY: Float,
                    velocityX: Float,
                    velocityY: Float,
                    onSettled: () -> Unit
                ) {
                    val isDismissed =
                        CrispSheetDrag.shouldDismissCompose(offsetY, velocityY, density)
                    isScrimVisible = !isDismissed
                    isSwipedOut = isDismissed
                    settleJob = scope.launch {
                        animate(
                            initialValue = offsetY,
                            targetValue = if (isDismissed) sheetHeight.toFloat() else 0f,
                            initialVelocity = velocityY,
                            animationSpec = sheetSpatialSpring()
                        ) { value, _ ->
                            content.translationY = value
                            sheetOffset = value
                        }
                        if (isDismissed) currentOnDismissRequest() else onSettled()
                    }
                }
            }
        }
        // As the Material 3 sheet: over the scrim, the status bar is light
        // (from the dialog window, which has the focus).
        // Once it fades out, the status bar of the activity behind is restored.
        val isScrimShown = sheetVisibility.targetState && isScrimVisible
        val isActivityStatusBarLight = remember(content) {
            content.context.findActivity()?.window?.let { activityWindow ->
                WindowCompat.getInsetsController(
                    activityWindow,
                    activityWindow.decorView
                ).isAppearanceLightStatusBars
            } ?: false
        }

        val dialogWindow = remember(content) { content.findDialogWindow() }
        // Once, before the first frame (as a SideEffect), so that the window does not animate
        DisposableEffect(dialogWindow) {
            dialogWindow?.let { window ->
                // EdgeToEdge
                WindowCompat.setDecorFitsSystemWindows(window, false)
                // Allow drawing into cutout areas
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    window.attributes = window.attributes.apply {
                        layoutInDisplayCutoutMode =
                            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    }
                }
                // Render our own scrim for smooth fade
                window.setDimAmount(0f)
                // Animated by the scrim and the sheet only, as the Material 3 sheet: the window itself would slide
                // and fade in with the scrim and the sheet from Android 14 (from the platform dialog animation)
                window.setWindowAnimations(0)

                // Applies appearance to the navigation bar according to dark mode.
                // For Android < 8, we apply a scrim on navigationBar instead
                val isDarkMode =
                    (content.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
                WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightNavigationBars = !isDarkMode
            }
            onDispose {}
        }
        SideEffect {
            dialogWindow?.let { window ->
                WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars =
                    !isScrimShown && isActivityStatusBarLight
            }
        }

        // Scrim + Container Box
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.BottomCenter
        ) {
            // Scrim, as the Material 3 sheet: fades in and out when it would be shown or hidden once the sheet settles,
            // which includes while it is dragged far enough to be dismissed
            val scrimAlpha by animateFloatAsState(
                targetValue = if (isScrimShown) 1f else 0f,
                animationSpec = sheetEffectsSpring()
            )
            val scrimDescription = stringResource(R.string.crisp_sdk_action_cancel)
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    // Stays in place while the sheet is dragged down
                    .graphicsLayer { translationY = -sheetOffset }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { dismiss() }
                    // As the Material 3 scrim: described, and read after the sheet
                    .semantics(mergeDescendants = true) {
                        contentDescription = scrimDescription
                        traversalIndex = 1f
                    }
            ) {
                drawRect(color = Color.Black.copy(alpha = 0.32f), alpha = scrimAlpha)
            }

            // Bottom sheet sliding content
            // As the Material 3 sheet showing and hiding
            AnimatedVisibility(
                visibleState = sheetVisibility,
                enter = slideInVertically(
                    initialOffsetY = { it },
                    animationSpec = sheetSpatialSpring()
                ),
                exit = slideOutVertically(
                    targetOffsetY = { it },
                    animationSpec = sheetFastEffectsSpring()
                )
            ) {
                // Progress of the slide in, as it runs the same spring on the same transition
                val slideInProgress by transition.animateFloat(
                    transitionSpec = { sheetSpatialSpring() },
                    label = "slideInProgress"
                ) { if (it == EnterExitState.Visible) 1f else 0f }
                // Nearly slid in (about 20px away): the WebView created then barely stalls the sheet,
                // much earlier than once the spring settles
                val isSlidingIn by remember {
                    derivedStateOf { slideInProgress < SLIDE_IN_THRESHOLD }
                }

                Surface(
                    modifier = modifier.fillMaxSize()
                        // Beside the navigation bar when it is on a side, as the Fragment sheet
                        .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal))
                        // Centered, and limited in width as the Fragment sheet
                        .wrapContentWidth()
                        .widthIn(max = dimensionResource(R.dimen.crisp_sdk_chatbox_modal_max_width))
                        .statusBarsPadding()
                        .displayCutoutPadding()
                        .padding(top = 10.dp)
                        .onSizeChanged { sheetHeight = it.height },
                    shape = RoundedCornerShape(
                        topStart = dimensionResource(R.dimen.crisp_sdk_chatbox_radius_top),
                        topEnd = dimensionResource(R.dimen.crisp_sdk_chatbox_radius_top)
                    ),
                    color = colorResource(R.color.crisp_sdk_chatbox_background)
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.BottomCenter
                    ) {
                        CrispChatboxContent(
                            // Configs created by integrators are not presented as a sheet
                            config = config.copy(presentation = Presentation.sheet),
                            modifier = Modifier.fillMaxSize(),
                            controller = controller,
                            edgeToEdge = EdgeToEdge.BOTTOM,
                            onClose = { dismiss() },
                            sheetDragHost = sheetDragHost,
                            isAnimatingIn = isSlidingIn
                        )

                        // For Android < 8, we apply a scrim on navigationBar because buttons are always light
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .windowInsetsBottomHeight(WindowInsets.navigationBars)
                                    .background(colorResource(R.color.crisp_sdk_bottomsheet_navigation_bar_color))
                                    .align(Alignment.BottomStart)
                            )
                        }
                    }
                }
            }
        }
    }
}