package me.rerere.rikkahub.data.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

class MailTriageTest {
    private val zone = ZoneId.of("America/Edmonton")
    private val weekdays = GraphMailClient.WEEKDAYS

    private fun at(y: Int, m: Int, d: Int, h: Int = 10) = ZonedDateTime.of(y, m, d, h, 0, 0, 0, zone).toInstant()

    @Test fun `two business days skip the weekend`() {
        // Thursday 2026-10-08 -> Monday 2026-10-12
        assertEquals(at(2026, 10, 12), addBusinessDays(at(2026, 10, 8), 2, weekdays, zone))
        // Monday -> Wednesday
        assertEquals(at(2026, 10, 14), addBusinessDays(at(2026, 10, 12), 2, weekdays, zone))
    }

    @Test fun `weekend mail counts from the next working day`() {
        // Saturday 2026-10-10 -> Tuesday 2026-10-13
        assertEquals(at(2026, 10, 13), addBusinessDays(at(2026, 10, 10), 2, weekdays, zone))
    }

    @Test fun `custom working days are respected`() {
        val sixDays = weekdays + DayOfWeek.SATURDAY
        // Thursday -> Saturday when Saturday is a working day
        assertEquals(at(2026, 10, 10), addBusinessDays(at(2026, 10, 8), 2, sixDays, zone))
    }

    private fun message(from: String, classification: String = "focused") = MailMessage(
        id = "1", conversationId = "c", subject = "s", fromName = "", fromAddress = from, to = emptyList(),
        receivedAt = Instant.EPOCH, sentAt = null, isRead = false, preview = "", importance = "normal",
        flagStatus = "notFlagged", webLink = "", inferenceClassification = classification,
    )

    @Test fun `prefilter skips own, automated and other-tab mail`() {
        val own = "mark@prescott.rocks"
        assertTrue(isTriageCandidate(message("alice@example.com"), own))
        assertTrue(isTriageCandidate(message("alice@example.com", classification = ""), own))
        assertFalse(isTriageCandidate(message("Mark@Prescott.rocks"), own))
        assertFalse(isTriageCandidate(message("no-reply@service.com"), own))
        assertFalse(isTriageCandidate(message("noreply@service.com"), own))
        assertFalse(isTriageCandidate(message("notifications@github.com"), own))
        assertFalse(isTriageCandidate(message("newsletter@shop.com"), own))
        assertFalse(isTriageCandidate(message("alice@example.com", classification = "other"), own))
        // "news" inside a real name is not an automated sender.
        assertTrue(isTriageCandidate(message("newsom@example.com"), own))
    }

    @Test fun `triage answers are parsed leniently`() {
        assertEquals(TriageDecision(true, "asks for a decision"), parseTriageDecision("""{"needs_reply": true, "reason": "asks for a decision"}"""))
        assertTrue(parseTriageDecision("Sure:\n```json\n{\"needs_reply\": \"true\", \"reason\": \"question\"}\n```").needsReply)
        assertFalse(parseTriageDecision("""{"needs_reply": false, "reason": "newsletter"}""").needsReply)
        assertFalse(parseTriageDecision("I think so").needsReply)
        assertFalse(parseTriageDecision("{not json}").needsReply)
    }
}
