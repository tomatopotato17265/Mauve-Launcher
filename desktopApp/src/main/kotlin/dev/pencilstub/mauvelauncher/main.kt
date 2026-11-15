package dev.pencilstub.mauvelauncher

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

private val isMacOs = System.getProperty("os.name").startsWith("Mac", ignoreCase = true)
private val MacTitleBarHeight = 32.dp

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Mauve Launcher KMP test",
    ) {
        if (isMacOs) {
            // applied during the first composition, before the window is shown, so there's no flash of the opaque title bar
            remember {
                window.rootPane.apply {
                    putClientProperty("apple.awt.fullWindowContent", true)
                    putClientProperty("apple.awt.transparentTitleBar", true)
                    putClientProperty("apple.awt.windowTitleVisible", false)
                }
            }
        }
        App(contentPadding = if (isMacOs) PaddingValues(top = MacTitleBarHeight) else PaddingValues())
    }
}