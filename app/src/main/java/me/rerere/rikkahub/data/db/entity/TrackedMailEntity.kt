package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** An email the reply tracker decided needs the user's reply. */
@Entity(
    tableName = "tracked_mail",
    indices = [Index(value = ["account_id", "status"])],
)
data class TrackedMailEntity(
    @PrimaryKey
    @ColumnInfo(name = "message_id")
    val messageId: String,
    @ColumnInfo(name = "account_id")
    val accountId: String,
    @ColumnInfo(name = "conversation_id")
    val conversationId: String,
    @ColumnInfo(name = "subject")
    val subject: String,
    @ColumnInfo(name = "from_name")
    val fromName: String,
    @ColumnInfo(name = "from_address")
    val fromAddress: String,
    @ColumnInfo(name = "received_at")
    val receivedAt: Long,
    @ColumnInfo(name = "due_at")
    val dueAt: Long,
    @ColumnInfo(name = "reason")
    val reason: String,
    /** One of [TrackedMailStatus]. */
    @ColumnInfo(name = "status")
    val status: String = TrackedMailStatus.AWAITING,
    @ColumnInfo(name = "reminded_at")
    val remindedAt: Long? = null,
    @ColumnInfo(name = "resolved_at")
    val resolvedAt: Long? = null,
)

object TrackedMailStatus {
    /** Waiting for the user's reply. */
    const val AWAITING = "awaiting"
    /** The user replied in the conversation. */
    const val REPLIED = "replied"
    /** Handled without a tracked reply: marked handled, flag cleared in Outlook, or the message is gone. */
    const val DISMISSED = "dismissed"
}
