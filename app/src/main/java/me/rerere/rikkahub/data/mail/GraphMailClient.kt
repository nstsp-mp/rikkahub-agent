package me.rerere.rikkahub.data.mail

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import me.rerere.common.http.await
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.DayOfWeek
import java.time.Instant

internal const val GRAPH_BASE = "https://graph.microsoft.com/v1.0"

private const val MESSAGE_FIELDS =
    "id,conversationId,subject,from,toRecipients,receivedDateTime,sentDateTime,isRead,bodyPreview,importance,flag,webLink"

data class MailMessage(
    val id: String,
    val conversationId: String,
    val subject: String,
    val fromName: String,
    val fromAddress: String,
    val to: List<String>,
    val receivedAt: Instant?,
    val sentAt: Instant?,
    val isRead: Boolean,
    val preview: String,
    val importance: String,
    val flagStatus: String,
    val webLink: String,
)

data class MailboxSettings(val workDays: Set<DayOfWeek>)

class GraphApiException(val status: Int, message: String) : IOException(message)

/** Thin Microsoft Graph mail client; every call runs as the signed-in user (delegated scopes). */
class GraphMailClient(
    private val okHttpClient: OkHttpClient,
    private val auth: MailAuthManager,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun listMessages(
        account: MailAccount,
        folder: String = "inbox",
        top: Int = 20,
        unreadOnly: Boolean = false,
        since: Instant? = null,
    ): List<MailMessage> {
        val filters = buildList {
            since?.let { add("receivedDateTime ge $it") }
            if (unreadOnly) add("isRead eq false")
        }
        val url = graphUrl("me/mailFolders/${folder.pathSafe()}/messages")
            .addQueryParameter("\$top", top.toString())
            .addQueryParameter("\$select", MESSAGE_FIELDS)
            .addQueryParameter("\$orderby", "receivedDateTime desc")
            .apply { if (filters.isNotEmpty()) addQueryParameter("\$filter", filters.joinToString(" and ")) }
            .build()
        return get(account, url).messages()
    }

    /** Full-mailbox keyword search (KQL, e.g. `from:alice invoice`). Graph returns relevance order. */
    suspend fun search(account: MailAccount, query: String, top: Int = 20, folder: String? = null): List<MailMessage> {
        val path = folder?.let { "me/mailFolders/${it.pathSafe()}/messages" } ?: "me/messages"
        val url = graphUrl(path)
            .addQueryParameter("\$search", "\"${query.replace("\"", "")}\"")
            .addQueryParameter("\$top", top.toString())
            .addQueryParameter("\$select", MESSAGE_FIELDS)
            .build()
        return get(account, url).messages()
    }

    /** The message with its body as plain text, truncated to [maxBodyChars]. */
    suspend fun getMessage(account: MailAccount, id: String, maxBodyChars: Int = 8_000): JsonObject {
        val url = graphUrl("me/messages/${id.pathSafe()}")
            .addQueryParameter("\$select", "$MESSAGE_FIELDS,ccRecipients,body")
            .build()
        val obj = get(account, url, mapOf("Prefer" to "outlook.body-content-type=\"text\""))
        val message = obj.toMailMessage()
        val body = obj["body"]?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull.orEmpty().trim()
        return buildJsonObject {
            putMessage(message)
            put("cc", JsonArray(obj.recipients("ccRecipients").map { kotlinx.serialization.json.JsonPrimitive(it) }))
            put("body", body.take(maxBodyChars))
            if (body.length > maxBodyChars) put("body_truncated", true)
        }
    }

    /** Whether the user sent anything in [conversationId] after [after]. */
    suspend fun repliedInConversation(account: MailAccount, conversationId: String, after: Instant): Boolean {
        val url = graphUrl("me/mailFolders/sentitems/messages")
            .addQueryParameter("\$filter", "conversationId eq '${conversationId.replace("'", "''")}'")
            .addQueryParameter("\$select", "id,sentDateTime")
            .addQueryParameter("\$top", "50")
            .build()
        return get(account, url).messages().any { sent -> sent.sentAt?.isAfter(after) == true }
    }

    suspend fun setFlag(account: MailAccount, id: String, due: Instant?) {
        val flag = buildJsonObject {
            put("flagStatus", "flagged")
            if (due != null) {
                putJsonObject("startDateTime") { put("dateTime", Instant.now().toString()); put("timeZone", "UTC") }
                putJsonObject("dueDateTime") { put("dateTime", due.toString()); put("timeZone", "UTC") }
            }
        }
        patchFlag(account, id, flag)
    }

    suspend fun completeFlag(account: MailAccount, id: String) =
        patchFlag(account, id, buildJsonObject { put("flagStatus", "complete") })

    suspend fun mailboxSettings(account: MailAccount): MailboxSettings {
        val obj = get(account, graphUrl("me/mailboxSettings").addQueryParameter("\$select", "workingHours").build())
        val days = obj["workingHours"]?.jsonObject?.get("daysOfWeek")?.jsonArray
            ?.mapNotNull { day -> runCatching { DayOfWeek.valueOf(day.jsonPrimitive.content.uppercase()) }.getOrNull() }
            ?.toSet()
        return MailboxSettings(days?.takeIf { it.isNotEmpty() } ?: WEEKDAYS)
    }

    private suspend fun patchFlag(account: MailAccount, id: String, flag: JsonObject) {
        send(
            account,
            Request.Builder()
                .url(graphUrl("me/messages/${id.pathSafe()}").build())
                .patch(buildJsonObject { put("flag", flag) }.toString().toRequestBody(JSON_TYPE))
        )
    }

    private suspend fun get(account: MailAccount, url: HttpUrl, headers: Map<String, String> = emptyMap()): JsonObject =
        json.parseToJsonElement(
            send(account, Request.Builder().url(url).get().apply { headers.forEach { (k, v) -> header(k, v) } })
        ).jsonObject

    private suspend fun send(account: MailAccount, builder: Request.Builder): String {
        val token = auth.accessToken(account)
        val response = okHttpClient.newCall(builder.header("Authorization", "Bearer $token").build()).await()
        return withContext(Dispatchers.IO) {
            response.use {
                val text = it.body.string()
                if (!it.isSuccessful) {
                    val detail = runCatching {
                        json.parseToJsonElement(text).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
                    }.getOrNull()
                    throw GraphApiException(it.code, "Microsoft Graph HTTP ${it.code}${detail?.let { d -> ": $d" }.orEmpty()}")
                }
                text
            }
        }
    }

    private fun JsonObject.messages(): List<MailMessage> =
        this["value"]?.jsonArray?.map { it.jsonObject.toMailMessage() }.orEmpty()

    private fun graphUrl(path: String): HttpUrl.Builder = "$GRAPH_BASE/$path".toHttpUrl().newBuilder()

    private fun String.pathSafe(): String {
        require(isNotBlank() && none { it == '/' || it == '?' || it == '#' }) { "Invalid mail id or folder: $this" }
        return this
    }

    companion object {
        val WEEKDAYS = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
        private val JSON_TYPE = "application/json".toMediaType()
    }
}

internal fun JsonObject.toMailMessage(): MailMessage {
    fun str(name: String) = this[name]?.jsonPrimitive?.contentOrNull.orEmpty()
    fun time(name: String) = this[name]?.jsonPrimitive?.contentOrNull?.let { runCatching { Instant.parse(it) }.getOrNull() }
    val from = this["from"]?.jsonObject?.get("emailAddress")?.jsonObject
    return MailMessage(
        id = str("id"),
        conversationId = str("conversationId"),
        subject = str("subject"),
        fromName = from?.get("name")?.jsonPrimitive?.contentOrNull.orEmpty(),
        fromAddress = from?.get("address")?.jsonPrimitive?.contentOrNull.orEmpty(),
        to = recipients("toRecipients"),
        receivedAt = time("receivedDateTime"),
        sentAt = time("sentDateTime"),
        isRead = this["isRead"]?.jsonPrimitive?.booleanOrNull ?: false,
        preview = str("bodyPreview"),
        importance = str("importance"),
        flagStatus = this["flag"]?.jsonObject?.get("flagStatus")?.jsonPrimitive?.contentOrNull.orEmpty(),
        webLink = str("webLink"),
    )
}

private fun JsonObject.recipients(field: String): List<String> =
    this[field]?.jsonArray?.mapNotNull { recipient ->
        recipient.jsonObject["emailAddress"]?.jsonObject?.let { email ->
            val name = email["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val address = email["address"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (name.isBlank() || name == address) address else "$name <$address>"
        }
    }.orEmpty()

internal fun kotlinx.serialization.json.JsonObjectBuilder.putMessage(message: MailMessage) {
    put("id", message.id)
    put("subject", message.subject)
    put("from", if (message.fromName.isBlank()) message.fromAddress else "${message.fromName} <${message.fromAddress}>")
    if (message.to.isNotEmpty()) put("to", message.to.joinToString(", "))
    message.receivedAt?.let { put("received", it.toString()) }
    put("read", message.isRead)
    if (message.importance.isNotBlank() && message.importance != "normal") put("importance", message.importance)
    if (message.flagStatus.isNotBlank() && message.flagStatus != "notFlagged") put("flag", message.flagStatus)
    if (message.preview.isNotBlank()) put("preview", message.preview.take(300))
}
