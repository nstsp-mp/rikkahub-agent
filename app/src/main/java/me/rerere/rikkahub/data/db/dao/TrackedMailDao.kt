package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.TrackedMailEntity

@Dao
interface TrackedMailDao {
    /** Keeps an existing row, so re-triaging a message never resets its status or reminder. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: TrackedMailEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun replace(entity: TrackedMailEntity)

    @Query("SELECT * FROM tracked_mail WHERE message_id = :messageId")
    suspend fun get(messageId: String): TrackedMailEntity?

    @Query("SELECT * FROM tracked_mail WHERE account_id = :accountId AND status = 'awaiting' ORDER BY due_at ASC")
    suspend fun awaiting(accountId: String): List<TrackedMailEntity>

    @Query("SELECT * FROM tracked_mail WHERE status = 'awaiting' ORDER BY due_at ASC")
    fun observeAwaiting(): Flow<List<TrackedMailEntity>>

    @Query("UPDATE tracked_mail SET status = :status, resolved_at = :resolvedAt WHERE message_id = :messageId")
    suspend fun resolve(messageId: String, status: String, resolvedAt: Long)

    @Query("UPDATE tracked_mail SET reminded_at = :remindedAt WHERE message_id = :messageId")
    suspend fun markReminded(messageId: String, remindedAt: Long)

    @Query("DELETE FROM tracked_mail WHERE status != 'awaiting' AND resolved_at < :before")
    suspend fun deleteResolvedBefore(before: Long)

    @Query("DELETE FROM tracked_mail WHERE account_id = :accountId")
    suspend fun deleteAccount(accountId: String)
}
