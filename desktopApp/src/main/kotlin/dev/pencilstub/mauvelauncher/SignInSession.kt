package dev.pencilstub.mauvelauncher

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.pencilstub.mauvelauncher.auth.MicrosoftLoginFlow
import dev.pencilstub.mauvelauncher.auth.MicrosoftLoginResult
import dev.pencilstub.mauvelauncher.auth.MinecraftAccount
import dev.pencilstub.mauvelauncher.auth.MinecraftAuth
import dev.pencilstub.mauvelauncher.auth.MinecraftAuthException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

// the signed-in account, and the sign-in in progress if there is one
class SignInSession(private val scope: CoroutineScope, private val auth: MinecraftAuth = MinecraftAuth()) {
    var account by mutableStateOf<MinecraftAccount?>(null)
        private set
    var inProgress by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)

    var loginPage by mutableStateOf<LoginPage?>(null)
        private set

    class LoginPage(val flow: MicrosoftLoginFlow) {
        internal val result = CompletableDeferred<MicrosoftLoginResult?>()

        fun close(result: MicrosoftLoginResult?) {
            this.result.complete(result)
        }
    }

    private var job: Job? = null

    fun signIn() {
        job?.cancel()
        error = null
        inProgress = true
        val attempt = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val flow = auth.beginLogin()
                val page = LoginPage(flow)
                loginPage = page
                val result = try {
                    page.result.await()
                } finally {
                    if (loginPage === page) loginPage = null
                }
                when (result) {
                    null -> {}
                    is MicrosoftLoginResult.Failure -> if (result.error != "access_denied") {
                        error = result.description ?: "Microsoft couldn't sign you in (${result.error}). Try again."
                    }
                    is MicrosoftLoginResult.Success -> account = auth.finishLogin(flow, result.code)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: MinecraftAuthException) {
                error = e.message
            } catch (e: Exception) {
                e.printStackTrace()
                error = "Something went wrong while signing in: ${e.message ?: e::class.simpleName}"
            } finally {
                if (job === coroutineContext[Job]) inProgress = false
            }
        }
        job = attempt
        attempt.start()
    }
}
