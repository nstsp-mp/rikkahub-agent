package me.rerere.rikkahub.data.mail

import kotlinx.serialization.Serializable

/** A signed-in Microsoft 365 mailbox. Tokens never leave [MailCredentialStore]. */
@Serializable
data class MailAccount(
    val id: String,
    val email: String,
    val displayName: String = "",
    val tenant: String,
    val clientId: String,
    val accessToken: String = "",
    val refreshToken: String = "",
    val expiresAt: Long = 0L,
) {
    override fun toString(): String =
        "MailAccount(id=$id, email=$email, tenant=$tenant, clientId=$clientId, expiresAt=$expiresAt)"
}

@Serializable
internal data class MailAccountState(
    val accounts: List<MailAccount> = emptyList(),
)

/**
 * The Entra app registration used for sign-in: a public client (no secret) with the loopback
 * redirect [MAIL_OAUTH_REDIRECT_URI] and delegated Graph scopes only.
 */
object MailAuthConfig {
    // prescott.rocks tenant and its "RikkaHub Mail" app registration. Neither value is a secret.
    const val DEFAULT_TENANT = "4cac7742-6cf4-4fa2-b8cf-8b5ca6a7c200"
    const val DEFAULT_CLIENT_ID = "444daa1b-9594-4f0f-abb4-47a1bed4c76b"

    const val SCOPES = "offline_access User.Read Mail.ReadWrite MailboxSettings.Read"

    fun authorizeUrl(tenant: String) = "https://login.microsoftonline.com/$tenant/oauth2/v2.0/authorize"
    fun tokenUrl(tenant: String) = "https://login.microsoftonline.com/$tenant/oauth2/v2.0/token"
}

internal const val MAIL_OAUTH_CALLBACK_PORT = 52_135
internal const val MAIL_OAUTH_CALLBACK_PATH = "/mail/callback"
internal const val MAIL_OAUTH_REDIRECT_URI = "http://127.0.0.1:$MAIL_OAUTH_CALLBACK_PORT$MAIL_OAUTH_CALLBACK_PATH"
