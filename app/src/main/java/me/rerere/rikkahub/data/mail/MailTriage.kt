package me.rerere.rikkahub.data.mail

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId

/** Business days to wait for the user's reply before reminding. */
const val REPLY_DUE_BUSINESS_DAYS = 2

/**
 * [start] plus [days] working days at the same local time. A message that arrives on a
 * non-working day starts counting from the next working day.
 */
fun addBusinessDays(start: Instant, days: Int, workDays: Set<DayOfWeek>, zone: ZoneId): Instant {
    require(workDays.isNotEmpty()) { "workDays must not be empty" }
    var date = start.atZone(zone)
    var remaining = days
    while (remaining > 0) {
        date = date.plusDays(1)
        if (date.dayOfWeek in workDays) remaining--
    }
    return date.toInstant()
}

private val AUTOMATED_SENDER = Regex(
    "(^|[._+-])(no-?reply|do-?not-?reply|notifications?|alerts?|mailer-daemon|postmaster|bounce[s]?|news(letter)?)([._+-]|@)",
    RegexOption.IGNORE_CASE,
)

/** Cheap filter before asking a model: skips the user's own mail and obvious automated senders. */
fun isTriageCandidate(message: MailMessage, ownAddress: String): Boolean {
    val from = message.fromAddress
    if (from.isBlank() || from.equals(ownAddress, ignoreCase = true)) return false
    if (AUTOMATED_SENDER.containsMatchIn(from)) return false
    // Outlook's "Other" tab is newsletters and bulk mail; only Focused mail is triaged.
    return message.inferenceClassification != "other"
}

data class TriageDecision(val needsReply: Boolean, val reason: String)

fun triagePrompt(message: MailMessage, ownName: String, ownAddress: String): String = """
    You triage email for ${ownName.ifBlank { ownAddress }} <$ownAddress>.
    Decide whether this message needs a personal reply from them. A reply is needed when a real person
    asks them a question, requests a decision, action or confirmation, or the conversation clearly
    expects their response. No reply is needed for newsletters, notifications, receipts, automated
    messages, FYI updates, or messages where they are only CC'd for information.

    From: ${message.fromName} <${message.fromAddress}>
    To: ${message.to.joinToString(", ")}
    Subject: ${message.subject}
    Preview: ${message.preview.take(800)}

    Answer with JSON only: {"needs_reply": true or false, "reason": "<at most 12 words>"}
""".trimIndent()

private val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true }

/** Parses the model's answer; anything unreadable counts as "no reply needed". */
fun parseTriageDecision(text: String): TriageDecision {
    val start = text.indexOf('{')
    val end = text.lastIndexOf('}')
    if (start < 0 || end <= start) return TriageDecision(false, "unparseable triage answer")
    return runCatching {
        val obj = lenientJson.parseToJsonElement(text.substring(start, end + 1)).jsonObject
        TriageDecision(
            needsReply = obj["needs_reply"]?.jsonPrimitive?.let { it.booleanOrNull ?: it.contentOrNull.toBoolean() } ?: false,
            reason = obj["reason"]?.jsonPrimitive?.contentOrNull.orEmpty().take(120),
        )
    }.getOrElse { TriageDecision(false, "unparseable triage answer") }
}
