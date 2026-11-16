package dev.pencilstub.mauvelauncher

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import com.jetbrains.cef.JCefAppConfig
import dev.pencilstub.mauvelauncher.auth.MicrosoftLoginFlow
import dev.pencilstub.mauvelauncher.auth.MicrosoftLoginResult
import org.cef.CefApp
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.browser.CefRendering
import org.cef.handler.CefRequestHandlerAdapter
import org.cef.network.CefRequest
import java.awt.Dimension
import java.awt.Taskbar
import java.io.File
import javax.swing.SwingUtilities

@Composable
fun SignInWindow(flow: MicrosoftLoginFlow, onClose: (MicrosoftLoginResult?) -> Unit) {
    val currentOnClose by rememberUpdatedState(onClose)
    Window(
        onCloseRequest = { currentOnClose(null) },
        state = rememberWindowState(size = DpSize(1000.dp, 700.dp), position = WindowPosition(Alignment.Center)),
        title = "Sign in to Minecraft",
    ) {
        LaunchedEffect(Unit) {
            window.minimumSize = Dimension(500, 500)
            if (Taskbar.isTaskbarSupported() && Taskbar.getTaskbar().isSupported(Taskbar.Feature.USER_ATTENTION)) {
                Taskbar.getTaskbar().requestUserAttention(true, true)
            }
        }

        val browser = remember(flow) {
            val client = WebView.app.createClient()
            // stop at the redirect back from microsoft's pages. it carries the result so there's nothing to show
            client.addRequestHandler(object : CefRequestHandlerAdapter() {
                override fun onBeforeBrowse(
                    browser: CefBrowser?, frame: CefFrame?, request: CefRequest, userGesture: Boolean, isRedirect: Boolean,
                ): Boolean {
                    val result = flow.resultOf(request.url) ?: return false
                    SwingUtilities.invokeLater { currentOnClose(result) }
                    return true
                }
            })
            client.createBrowser(flow.authorizationUrl, CefRendering.DEFAULT, false)
        }
        DisposableEffect(browser) {
            onDispose { browser.client.dispose() }
        }

        SwingPanel(factory = { browser.uiComponent }, modifier = Modifier.fillMaxSize())
    }
}

private object WebView {
    val app: CefApp by lazy {
        val config = JCefAppConfig.getInstance()
        check(CefApp.startup(config.appArgs)) { "Couldn't start the webview (JCEF)" }
        val settings = config.cefSettings.apply {
            // Chromium's profile (cookies etc.) kept apart from other apps that embed it
            cache_path = appDataDir().resolve("WebView").absolutePath
        }
        CefApp.getInstance(config.appArgs, settings)
    }

    private fun appDataDir(): File {
        val home = System.getProperty("user.home")
        val os = System.getProperty("os.name")
        return when {
            os.startsWith("Mac", ignoreCase = true) -> File(home, "Library/Application Support/Mauve Launcher")
            os.startsWith("Windows", ignoreCase = true) ->
                File(System.getenv("APPDATA") ?: "$home/AppData/Roaming", "Mauve Launcher")
            else -> File(System.getenv("XDG_DATA_HOME") ?: "$home/.local/share", "mauve-launcher")
        }
    }
}
