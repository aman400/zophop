package dev.zophop.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity)

    @Update
    suspend fun updateMessage(message: MessageEntity)

    @Query("SELECT * FROM queued_messages WHERE id = :id LIMIT 1")
    suspend fun getMessageById(id: String): MessageEntity?

    @Query("SELECT * FROM queued_messages WHERE status IN ('QUEUED', 'FAILED') ORDER BY receivedAt ASC")
    suspend fun getPendingMessages(): List<MessageEntity>

    @Query("SELECT * FROM queued_messages ORDER BY receivedAt DESC LIMIT :limit")
    fun getRecentEventsFlow(limit: Int = 20): Flow<List<MessageEntity>>

    @Query("SELECT * FROM queued_messages ORDER BY receivedAt DESC")
    fun getAllEventsFlow(): Flow<List<MessageEntity>>

    @Query("DELETE FROM queued_messages WHERE id = :id")
    suspend fun deleteMessage(id: String)

    @Query("DELETE FROM queued_messages WHERE id NOT IN (SELECT id FROM queued_messages ORDER BY receivedAt DESC LIMIT :keepCount)")
    suspend fun purgeOldEvents(keepCount: Int = 50)

    @Query("DELETE FROM queued_messages WHERE receivedAt < :cutoffTimestamp")
    suspend fun deleteMessagesOlderThan(cutoffTimestamp: Long): Int

    @Query("DELETE FROM queued_messages")
    suspend fun clearAllEvents()
}
