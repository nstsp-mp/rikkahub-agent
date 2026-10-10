package me.rerere.rikkahub.data.mail

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.rerere.common.http.await
import me.rerere.oauth.CustomTabsOAuthAuthorizationLauncher
import me.rerere.oauth.OAuthHttpClient
import me.rerere.oauth.OAuthLoopbackCallbackServer
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.time.Duration.Companion.minutes

private const val TAG = "MailAuthManager"
private const val TOKEN_REFRESH_LEEWAY_MS = 120_000L

sealed interface MailAuthStatus {
    data object Idle : MailAuthStatus
    data object Waiting : MailAuthStatus
    data class Error(val message: String) : MailAuthStatus
}

/** Microsoft identity sign-in (auth code + PKCE over a loopback redirect) and token refresh. */
class MailAuthManager(
    private val context: Context,
    private val appScope: CoroutineScope,
    private val okHttpClient: OkHttpClient,
    json: Json,
) {
    private val store = MailCredentialStore(context, json)
    private val oauth = OAuthHttpClient(okHttpClient)
    private val callbackServer = OAuthLoopbackCallbackServer(
        port = MAIL_OAUTH_CALLBACK_PORT,
        callbackPath = MAIL_OAUTH_CALLBACK_PATH,
    )
    private val tokenMutex = Mutex()
    private val parser = Json { ignoreUnknownKeys = true }

    private val _accounts = MutableStateFlow(store.read().accounts)
    val accounts: StateFlow<List<MailAccount>> = _accounts.asStateFlow()

    private val _status = MutableStateFlow<MailAuthStatus>(MailAuthStatus.Idle)
    val status: StateFlow<MailAuthStatus> = _status.asStateFlow()

    fun startLogin(
        tenant: String = MailAuthConfig.DEFAULT_TENANT,
        clientId: String = MailAuthConfig.DEFAULT_CLIENT_ID,
    ) {
        if (_status.value == MailAuthStatus.Waiting) return
        if (clientId.isBlank()) {
            _status.value = MailAuthStatus.Error("No Microsoft app registration is configured for mail sign-in.")
            return
        }
        _status.value = MailAuthStatus.Waiting
        appScope.launch(Dispatchers.IO) {
            try {
                val account = authorize(tenant, clientId)
                saveAccount(account)
                _status.value = MailAuthStatus.Idle
            } catch (e: Exception) {
                Log.w(TAG, "Mail sign-in failed: ${e.message}")
                _status.value = MailAuthStatus.Error(e.message ?: "Mail sign-in failed")
            }
        }
    }

    fun clearError() {
        if (_status.value is MailAuthStatus.Error) _status.value = MailAuthStatus.Idle
    }

    fun signOut(accountId: String) {
        saveAccounts(_accounts.value.filterNot { it.id == accountId })
    }

    /** A valid access token for [account], refreshed when close to expiry. */
    suspend fun accessToken(account: MailAccount): String = tokenMutex.withLock {
        val current = _accounts.value.firstOrNull { it.id == account.id }
            ?: error("Mail account ${account.email} is no longer signed in")
        if (current.accessToken.isNotBlank() && System.currentTimeMillis() < current.expiresAt - TOKEN_REFRESH_LEEWAY_MS) {
            return current.accessToken
        }
        check(current.refreshToken.isNotBlank()) { "Sign in to ${current.email} again in Settings → Mail" }
        val token = try {
            oauth.refreshToken(
                OAuthHttpClient.RefreshTokenRequest(
                    tokenEndpoint = MailAuthConfig.tokenUrl(current.tenant),
                    clientId = current.clientId,
                    refreshToken = current.refreshToken,
                    scope = MailAuthConfig.SCOPES,
                )
            )
        } catch (e: Exception) {
            throw IllegalStateException("Mail sign-in for ${current.email} expired. Sign in again in Settings → Mail.", e)
        }
        val refreshed = current.copy(
            accessToken = token.accessToken,
            refreshToken = token.refreshToken ?: current.refreshToken,
            expiresAt = expiry(token.expiresIn),
        )
        saveAccount(refreshed)
        refreshed.accessToken
    }

    private suspend fun authorize(tenant: String, clientId: String): MailAccount {
        val pkce = oauth.generatePkce()
        val state = oauth.generateState()
        val session = callbackServer.openSession(context, state)
        try {
            val url = oauth.buildAuthorizationUrl(
                OAuthHttpClient.AuthorizationRequest(
                    authorizationEndpoint = MailAuthConfig.authorizeUrl(tenant),
                    clientId = clientId,
                    redirectUri = session.redirectUri,
                    pkce = pkce,
                    state = state,
                    scope = MailAuthConfig.SCOPES,
                    additionalParameters = mapOf("prompt" to "select_account"),
                )
            )
            withContext(Dispatchers.Main) { CustomTabsOAuthAuthorizationLauncher.launch(context, url) }
            val callback = session.awaitCallback(5.minutes) ?: error("Mail sign-in timed out")
            callback.error?.let { error(callback.errorDescription?.let { d -> "$it: $d" } ?: it) }
            val code = callback.code ?: error("Mail sign-in returned no authorization code")
            val token = oauth.exchangeAuthorizationCode(
                OAuthHttpClient.AuthorizationCodeTokenRequest(
                    tokenEndpoint = MailAuthConfig.tokenUrl(tenant),
                    clientId = clientId,
                    code = code,
                    codeVerifier = pkce.verifier,
                    redirectUri = session.redirectUri,
                    additionalParameters = mapOf("scope" to MailAuthConfig.SCOPES),
                )
            )
            val me = fetchProfile(token.accessToken)
            return MailAccount(
                id = me.id,
                email = me.email,
                displayName = me.displayName,
                tenant = tenant,
                clientId = clientId,
                accessToken = token.accessToken,
                refreshToken = token.refreshToken.orEmpty(),
                expiresAt = expiry(token.expiresIn),
            )
        } finally {
            withContext(NonCancellable) { session.close() }
        }
    }

    private data class Profile(val id: String, val email: String, val displayName: String)

    private suspend fun fetchProfile(accessToken: String): Profile {
        val response = okHttpClient.newCall(
            Request.Builder()
                .url("$GRAPH_BASE/me?\$select=id,displayName,mail,userPrincipalName")
                .header("Authorization", "Bearer $accessToken")
                .build()
        ).await()
        val body = response.use { r ->
            val text = r.body.string()
            check(r.isSuccessful) { "Graph /me failed: HTTP ${r.code}" }
            text
        }
        val obj = parser.parseToJsonElement(body).jsonObject
        fun field(name: String) = obj[name]?.jsonPrimitive?.contentOrNull
        return Profile(
            id = field("id") ?: error("Graph /me returned no id"),
            email = field("mail") ?: field("userPrincipalName").orEmpty(),
            displayName = field("displayName").orEmpty(),
        )
    }

    private fun saveAccount(account: MailAccount) {
        saveAccounts(_accounts.value.filterNot { it.id == account.id } + account)
    }

    @Synchronized
    private fun saveAccounts(accounts: List<MailAccount>) {
        store.write(MailAccountState(accounts))
        _accounts.value = accounts
    }

    private fun expiry(expiresIn: Long?): Long =
        System.currentTimeMillis() + (expiresIn ?: 3600L) * 1000L
}
