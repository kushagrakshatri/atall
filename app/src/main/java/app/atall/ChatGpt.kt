package app.atall

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import androidx.browser.customtabs.CustomTabsIntent
import app.atall.chatgpt.AesGcmCipher
import app.atall.chatgpt.AuthFailure
import app.atall.chatgpt.AuthStatus
import app.atall.chatgpt.Callback
import app.atall.chatgpt.ChatGptAuth
import app.atall.chatgpt.ChatGptException
import app.atall.chatgpt.CredentialStore
import app.atall.chatgpt.FileCredentialStore
import app.atall.chatgpt.LoopbackServer
import app.atall.chatgpt.ResponsesClient
import app.atall.chatgpt.ReturnToApp
import app.atall.chatgpt.SignInMode
import app.atall.chatgpt.SiwcConfig
import app.atall.chatgpt.Vault
import app.atall.ui.SignInUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Sign in with ChatGPT for Atall: one [ChatGptAuth] per process, tokens in an AES-GCM file under
 * noBackupFilesDir keyed by a non-exportable Android Keystore key. Tokens never reach logs or UI state.
 */
class ChatGpt(private val context: Context, private val scope: CoroutineScope) {
    val auth = ChatGptAuth(SiwcConfig(appName = APP_NAME), SafeStore(File(context.noBackupFilesDir, "chatgpt.vault")))
    val client: ResponsesClient by lazy { ResponsesClient(auth.config, ChatGptAuth.defaultHttp(), auth) }

    private val _status = MutableStateFlow(AuthStatus("signed_out", "", null, false, "", emptyList()))
    val status: StateFlow<AuthStatus> = _status
    private val _signIn = MutableStateFlow<SignInUi>(SignInUi.Idle)
    val signIn: StateFlow<SignInUi> = _signIn
    private var signInJob: Job? = null

    /** The client when a sign-in exists (plan permission is checked per call), else null. */
    fun responsesOrNull(): ResponsesClient? = if (_status.value.state == "signed_in") client else null

    fun refresh() = scope.launch(Dispatchers.IO) {
        _status.value = runCatching { auth.check() }.getOrElse { runCatching { auth.status() }.getOrDefault(_status.value) }
    }

    /** Opens OpenAI's sign-in page in a Custom Tab and waits for the loopback callback. The person signs in; we never do. */
    fun startSignIn(activity: Activity) {
        if (signInJob?.isActive == true) return
        _signIn.value = SignInUi.Waiting
        signInJob = scope.launch(Dispatchers.IO) {
            _signIn.value = try {
                LoopbackServer(APP_NAME, returnUrl = { port ->
                    ReturnToApp.intentUrl(context.packageName, SCHEME, "http://127.0.0.1:$port/auth/done")
                }).use { lb ->
                    val attempt = auth.beginSignIn(SignInMode.DEFAULT, lb.redirectUri)
                    lb.start(attempt)
                    val url = auth.authorizeUrl(attempt) // never logged
                    withContext(Dispatchers.Main) { openBrowser(activity, url) }
                    val cb = try {
                        runInterruptible { lb.await(10 * 60_000L) }
                    } catch (e: CancellationException) {
                        lb.cancel(); throw e
                    }
                    when (cb) {
                        is Callback.Success -> { auth.completeSignIn(attempt, cb); SignInUi.Done }
                        is Callback.Failed -> SignInUi.Failed(DIDNT_FINISH)
                        else -> SignInUi.Failed(DIDNT_FINISH)
                    }
                }
            } catch (e: CancellationException) {
                SignInUi.Idle
            } catch (e: ChatGptException) {
                Log.w(TAG, "sign-in failed: ${e.code}")
                SignInUi.Failed(if (e is AuthFailure && e.code == "account_mismatch") "That's a different ChatGPT account. Sign out first to switch." else DIDNT_FINISH)
            } catch (e: Exception) {
                Log.w(TAG, "sign-in failed: ${e.javaClass.simpleName}")
                SignInUi.Failed(DIDNT_FINISH)
            }
            _status.value = runCatching { auth.status() }.getOrDefault(_status.value)
        }
    }

    fun cancelSignIn() { signInJob?.cancel(); _signIn.value = SignInUi.Idle }

    fun signOut() = scope.launch(Dispatchers.IO) {
        runCatching { auth.signOut() }
        _status.value = runCatching { auth.status() }.getOrDefault(_status.value)
        _signIn.value = SignInUi.Idle
    }

    private fun openBrowser(activity: Activity, url: String) {
        val uri = Uri.parse(url)
        try {
            CustomTabsIntent.Builder().setShowTitle(true).setUrlBarHidingEnabled(false).build().launchUrl(activity, uri)
        } catch (_: ActivityNotFoundException) {
            try { activity.startActivity(Intent(Intent.ACTION_VIEW, uri)) } catch (_: ActivityNotFoundException) {
                _signIn.value = SignInUi.Failed("Signing in needs a web browser.")
            }
        }
    }

    companion object {
        const val APP_NAME = "Atall"
        const val SCHEME = "atall-signin"
        const val MANAGE_USAGE_URL = SiwcConfig.MANAGE_USAGE_URL
        private const val TAG = "AtallChatGpt"
        private const val KEY_ALIAS = "atall-chatgpt-vault"
        private const val DIDNT_FINISH = "Sign-in didn't finish. Try again when you're ready."

        private fun keystoreKey(): SecretKey {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(
                    KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .setRandomizedEncryptionRequired(true)
                        .build(),
                )
            }.generateKey()
        }
    }

    /** Keystore-encrypted vault; an unreadable vault (key lost after a reset) counts as signed out. */
    private class SafeStore(private val file: File) : CredentialStore {
        private val inner = FileCredentialStore(file, AesGcmCipher({ keystoreKey() }, keystoreManagedIv = true))
        override fun load(): Vault = try { inner.load() } catch (e: Exception) {
            Log.w(TAG, "vault unreadable; starting signed out (${e.javaClass.simpleName})")
            file.delete(); Vault()
        }
        override fun save(vault: Vault) = inner.save(vault)
    }
}
