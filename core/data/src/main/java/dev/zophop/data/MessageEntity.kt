package dev.zophop.data

import androidx.room.Entity
import androidx.room.PrimaryKey

object MessageStatus {
    const val QUEUED = "QUEUED"
    const val DELIVERED = "DELIVERED"
    const val EXPIRED = "EXPIRED"
    const val FAILED = "FAILED"
}

@Entity(tableName = "queued_messages")
data class MessageEntity(
    @PrimaryKey val id: String,
    val sender: String,
    val body: String?, // Cleared (null) upon delivery or expiry to protect sensitive OTP text
    val receivedAt: Long,
    val status: String, // QUEUED, DELIVERED, EXPIRED, FAILED
    val retryCount: Int = 0,
    val errorMessage: String? = null,
)
