package me.rerere.rikkahub.data.mail

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class GraphMailParsingTest {
    private val sample = """
        {
          "id": "AAMk1",
          "conversationId": "conv-1",
          "subject": "Invoice 42",
          "from": {"emailAddress": {"name": "Alice Example", "address": "alice@example.com"}},
          "toRecipients": [
            {"emailAddress": {"name": "Mark", "address": "mark@prescott.rocks"}},
            {"emailAddress": {"name": "bob@example.com", "address": "bob@example.com"}}
          ],
          "receivedDateTime": "2026-10-09T15:04:05Z",
          "isRead": false,
          "bodyPreview": "Please review",
          "importance": "high",
          "flag": {"flagStatus": "flagged"}
        }
    """.trimIndent()

    @Test fun `graph message fields are parsed`() {
        val message = Json.parseToJsonElement(sample).jsonObject.toMailMessage()
        assertEquals("AAMk1", message.id)
        assertEquals("conv-1", message.conversationId)
        assertEquals("Alice Example", message.fromName)
        assertEquals("alice@example.com", message.fromAddress)
        assertEquals(listOf("Mark <mark@prescott.rocks>", "bob@example.com"), message.to)
        assertEquals(Instant.parse("2026-10-09T15:04:05Z"), message.receivedAt)
        assertNull(message.sentAt)
        assertFalse(message.isRead)
        assertEquals("flagged", message.flagStatus)
    }

    @Test fun `tool output omits defaults and keeps the essentials`() {
        val message = Json.parseToJsonElement(sample).jsonObject.toMailMessage()
        val out = buildJsonObject { putMessage(message) }
        assertEquals("Alice Example <alice@example.com>", out["from"]!!.jsonPrimitive.content)
        assertEquals("high", out["importance"]!!.jsonPrimitive.content)
        assertEquals("flagged", out["flag"]!!.jsonPrimitive.content)

        val plain = message.copy(importance = "normal", flagStatus = "notFlagged", preview = "")
        val plainOut = buildJsonObject { putMessage(plain) }
        assertTrue("importance" !in plainOut && "flag" !in plainOut && "preview" !in plainOut)
    }

    @Test fun `missing fields fall back to empty values`() {
        val message = Json.parseToJsonElement("""{"id":"x"}""").jsonObject.toMailMessage()
        assertEquals("", message.subject)
        assertEquals(emptyList<String>(), message.to)
        assertNull(message.receivedAt)
    }
}
