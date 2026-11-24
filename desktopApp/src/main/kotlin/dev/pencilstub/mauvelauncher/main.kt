package dev.pencilstub.mauvelauncher

import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.jetbrains.JBR
import com.jetbrains.WindowDecorations.CustomTitleBar

private val isMacOs = System.getProperty("os.name").startsWith("Mac", ignoreCase = true)

fun main() = application {
    val windowState = rememberWindowState()
    val scope = rememberCoroutineScope()
    val session = remember { SignInSession(scope) }
    Window(
        onCloseRequest = ::exitApplication,
        state = windowState,
        title = "Mauve Launcher",
    ) {
        val titleBar = if (isMacOs) remember { drawContentUnderTitleBar(window) } else null
        App(
            topBarModifier = titleBar?.let { Modifier.windowDragArea(it) } ?: Modifier,
            // keep the tabs clear of the traffic lights
            topBarStartInset = when {
                !isMacOs || windowState.placement == WindowPlacement.Fullscreen -> 0.dp
                else -> titleBar?.leftInset?.dp ?: 80.dp
            },
            accountStatus = when {
                session.inProgress -> AccountStatus.SigningIn
                else -> session.account?.let { AccountStatus.SignedIn(it.name, session.faceOf(it)) } ?: AccountStatus.SignedOut
            },
            onSignInClick = session::signIn,
            signInError = session.error,
            onDismissSignInError = { session.error = null },
        )
    }

    session.loginPage?.let { page ->
        key(page) {
            SignInWindow(page.flow, onClose = page::close)
        }
    }
}


// Lets the app draw the whole window, top bar included, while the OS keeps drawing (and handling) the real traffic lights on top of it. Returns the JetBrains Runtime custom title bar, or null where it isn't available.
private fun drawContentUnderTitleBar(window: ComposeWindow): CustomTitleBar? {
    window.rootPane.putClientProperty("apple.awt.windowAppearance", "NSAppearanceNameDarkAqua")

    if (JBR.isWindowDecorationsSupported()) {
        val decorations = JBR.getWindowDecorations()
        val titleBar = decorations.createCustomTitleBar().apply { height = TopBarHeight.value }
        decorations.setCustomTitleBar(window, titleBar)
        return titleBar
    }

    window.rootPane.apply {
        putClientProperty("apple.awt.fullWindowContent", true)
        putClientProperty("apple.awt.transparentTitleBar", true)
        putClientProperty("apple.awt.windowTitleVisible", false)
    }
    return null
}

private fun Modifier.windowDragArea(titleBar: CustomTitleBar): Modifier = pointerInput(titleBar) {
    awaitPointerEventScope {
        var pressStartedInControl = false
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Main)
            val consumed = event.changes.any { it.isConsumed }
            if (event.type == PointerEventType.Press) pressStartedInControl = consumed
            titleBar.forceHitTest(consumed || pressStartedInControl)
            if (event.type == PointerEventType.Release) pressStartedInControl = false
        }
    }
}