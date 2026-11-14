package dev.pencilstub.mauvelauncher

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Mauve Launcher KMP test",
    ) {
        App()
    }
}