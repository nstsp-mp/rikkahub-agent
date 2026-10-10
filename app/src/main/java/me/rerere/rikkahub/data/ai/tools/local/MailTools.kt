package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.mail.GraphMailClient
import me.rerere.rikkahub.data.mail.MailAccount
import me.rerere.rikkahub.data.mail.MailAuthManager
import me.rerere.rikkahub.data.mail.MailMessage
import me.rerere.rikkahub.data.mail.MailTracker
import me.rerere.rikkahub.data.mail.putMessage
import java.time.Duration
import java.time.Instant

private fun stringProp(description: String) = buildJsonObject {
    put("type", "string")
    put("description", description)
}

private fun intProp(description: String) = buildJsonObject {
    put("type", "integer")
    put("description", description)
}

private val ACCOUNT_PROP = stringProp("Mailbox email address. Omit to use the first signed-in mailbox.")

private fun JsonElement.str(name: String) = jsonObject[name]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
private fun JsonElement.int(name: String) = jsonObject[name]?.jsonPrimitive?.intOrNull

private fun List<MailMessage>.toJsonArray() = buildJsonArray { forEach { addJsonObject { putMessage(it) } } }

/**
 * Runs [block] for the requested mailbox, turning sign-in and Graph failures into a JSON error the
 * model can relay instead of a thrown exception.
 */
private suspend fun withMailbox(
    auth: MailAuthManager,
    input: JsonElement,
    block: suspend (MailAccount) -> JsonObject,
): List<UIMessagePart> {
    val accounts = auth.accounts.value
    val requested = input.str("account")
    val account = when {
        accounts.isEmpty() -> null
        requested == null -> accounts.first()
        else -> accounts.firstOrNull { it.email.equals(requested, ignoreCase = true) }
    }
    val payload = if (account == null) {
        buildJsonObject {
            put("error", "mail_not_signed_in")
            put(
                "detail",
                if (accounts.isEmpty()) "No mailbox is signed in. Ask the user to sign in under Settings → Mail."
                else "No signed-in mailbox matches $requested. Signed in: ${accounts.joinToString { it.email }}",
            )
        }
    } else {
        try {
            block(account)
        } catch (e: Exception) {
            buildJsonObject {
                put("error", "mail_request_failed")
                put("detail", e.message ?: e::class.java.simpleName)
            }
        }
    }
    return listOf(UIMessagePart.Text(payload.toString()))
}

fun mailListTool(auth: MailAuthManager, client: GraphMailClient): Tool = Tool(
    name = "mail_list",
    description = """
        List recent email in the user's Microsoft 365 mailbox, newest first. Returns id, sender, subject,
        received time (UTC), read state, importance, flag and a short preview. Use mail_read for the full body.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("folder", stringProp("Well-known folder: inbox (default), sentitems, drafts, archive, junkemail, deleteditems"))
                put("unread_only", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Only unread messages")
                })
                put("since_hours", intProp("Only messages received in the last N hours"))
                put("limit", intProp("Max messages, default 15, max 50"))
                put("account", ACCOUNT_PROP)
            }
        )
    },
    execute = { input ->
        withMailbox(auth, input) { account ->
            val sinceHours = input.int("since_hours")
            val messages = client.listMessages(
                account = account,
                folder = input.str("folder") ?: "inbox",
                top = (input.int("limit") ?: 15).coerceIn(1, 50),
                unreadOnly = input.jsonObject["unread_only"]?.jsonPrimitive?.booleanOrNull ?: false,
                since = sinceHours?.let { Instant.now().minus(Duration.ofHours(it.coerceAtLeast(1).toLong())) },
            )
            buildJsonObject {
                put("mailbox", account.email)
                put("messages", messages.toJsonArray())
            }
        }
    }
)

fun mailSearchTool(auth: MailAuthManager, client: GraphMailClient): Tool = Tool(
    name = "mail_search",
    description = """
        Search the user's Microsoft 365 mailbox. Accepts keywords and KQL such as from:alice, to:bob,
        subject:invoice, hasattachments:true, received>=2026-10-01. Results are by relevance, not date.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("query", stringProp("Search keywords or KQL"))
                put("folder", stringProp("Optional well-known folder to limit the search, e.g. inbox or sentitems"))
                put("limit", intProp("Max messages, default 15, max 50"))
                put("account", ACCOUNT_PROP)
            },
            required = listOf("query"),
        )
    },
    execute = { input ->
        withMailbox(auth, input) { account ->
            val query = input.str("query") ?: error("query is required")
            val messages = client.search(account, query, (input.int("limit") ?: 15).coerceIn(1, 50), input.str("folder"))
            buildJsonObject {
                put("mailbox", account.email)
                put("messages", messages.toJsonArray())
            }
        }
    }
)

fun mailReadTool(auth: MailAuthManager, client: GraphMailClient): Tool = Tool(
    name = "mail_read",
    description = "Read one email from the user's Microsoft 365 mailbox, including the plain-text body, by id from mail_list or mail_search.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("id", stringProp("Message id"))
                put("account", ACCOUNT_PROP)
            },
            required = listOf("id"),
        )
    },
    execute = { input ->
        withMailbox(auth, input) { account ->
            client.getMessage(account, input.str("id") ?: error("id is required"))
        }
    }
)

fun mailCheckReplyTool(auth: MailAuthManager, client: GraphMailClient): Tool = Tool(
    name = "mail_check_reply",
    description = """
        Check whether a person has replied to the user's most recent email to them. Finds the latest message
        the user sent to that person, then any messages from them received afterwards.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("person", stringProp("The person's email address (preferred) or name"))
                put("account", ACCOUNT_PROP)
            },
            required = listOf("person"),
        )
    },
    execute = { input ->
        withMailbox(auth, input) { account ->
            val person = input.str("person") ?: error("person is required")
            val lastSent = client.search(account, "to:$person", top = 10, folder = "sentitems")
                .filter { it.sentAt != null }
                .maxByOrNull { it.sentAt!! }
            buildJsonObject {
                put("mailbox", account.email)
                if (lastSent == null) {
                    put("last_sent", null as String?)
                    put("note", "No sent email to $person was found.")
                } else {
                    val replies = client.search(account, "from:$person", top = 15)
                        .filter { it.receivedAt?.isAfter(lastSent.sentAt) == true }
                        .sortedBy { it.receivedAt }
                    put("last_sent", buildJsonObject {
                        put("id", lastSent.id)
                        put("subject", lastSent.subject)
                        put("to", lastSent.to.joinToString(", "))
                        put("sent", lastSent.sentAt.toString())
                    })
                    put("replied", replies.isNotEmpty())
                    put("replies", replies.toJsonArray())
                }
            }
        }
    }
)

fun mailNeedingReplyTool(tracker: MailTracker): Tool = Tool(
    name = "mail_needing_reply",
    description = """
        List emails the reply tracker is waiting on the user to answer, oldest due date first, with sender,
        subject, why it needs a reply, received and due times (UTC), and whether it is overdue. Tracking is
        automatic when reply tracking is on in Settings → Mail.
    """.trimIndent().replace("\n", " "),
    parameters = { InputSchema.Obj(properties = buildJsonObject {}) },
    execute = {
        val now = System.currentTimeMillis()
        val items = tracker.awaiting()
        val status = tracker.status.value
        val payload = buildJsonObject {
            put("tracking_enabled", status.enabled)
            if (status.lastRunAt > 0) put("last_checked", Instant.ofEpochMilli(status.lastRunAt).toString())
            put("count", items.size)
            put("items", buildJsonArray {
                items.forEach { item ->
                    addJsonObject {
                        put("id", item.messageId)
                        put("from", if (item.fromName.isBlank()) item.fromAddress else "${item.fromName} <${item.fromAddress}>")
                        put("subject", item.subject)
                        if (item.reason.isNotBlank()) put("why", item.reason)
                        put("received", Instant.ofEpochMilli(item.receivedAt).toString())
                        put("due", Instant.ofEpochMilli(item.dueAt).toString())
                        put("overdue", item.dueAt <= now)
                    }
                }
            })
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)

fun mailMarkHandledTool(tracker: MailTracker): Tool = Tool(
    name = "mail_mark_handled",
    description = "Stop tracking an email as needing a reply (for example it was handled another way). Marks its Outlook flag complete.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject { put("id", stringProp("Message id from mail_needing_reply")) },
            required = listOf("id"),
        )
    },
    execute = { input ->
        val id = input.str("id") ?: error("id is required")
        val entity = tracker.markHandled(id)
        val payload = buildJsonObject {
            if (entity == null) {
                put("error", "not_tracked")
                put("detail", "That message is not being tracked.")
            } else {
                put("ok", true)
                put("subject", entity.subject)
            }
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)

fun mailTrackTool(auth: MailAuthManager, tracker: MailTracker): Tool = Tool(
    name = "mail_track",
    description = """
        Start tracking an email as needing the user's reply, when they ask to be reminded about it. It gets an
        Outlook follow-up flag due in two business days and a reminder if it is still unanswered by then.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("id", stringProp("Message id from mail_list or mail_search"))
                put("reason", stringProp("Short note on what reply is needed"))
                put("account", ACCOUNT_PROP)
            },
            required = listOf("id"),
        )
    },
    execute = { input ->
        withMailbox(auth, input) { account ->
            val entity = tracker.track(account, input.str("id") ?: error("id is required"), input.str("reason") ?: "Tracked on request")
            buildJsonObject {
                put("ok", true)
                put("subject", entity.subject)
                put("due", Instant.ofEpochMilli(entity.dueAt).toString())
            }
        }
    }
)