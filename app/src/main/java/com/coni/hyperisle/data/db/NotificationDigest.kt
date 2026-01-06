package com.coni.hyperisle.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "notification_digest",
    indices = [
        Index(value = ["postTime"]),
        Index(value = ["packageName", "postTime"])
    ]
)
data class NotificationDigestItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val title: String,
    val text: String,
    val postTime: Long,
    val type: String
)

@Dao
interface NotificationDigestDao {
    @Insert
    suspend fun insert(item: NotificationDigestItem)

    @Query("SELECT * FROM notification_digest WHERE postTime > :since ORDER BY postTime DESC")
    suspend fun getItemsSince(since: Long): List<NotificationDigestItem>

    @Query("SELECT * FROM notification_digest WHERE postTime > :since ORDER BY packageName, postTime DESC")
    fun getItemsSinceFlow(since: Long): Flow<List<NotificationDigestItem>>

    @Query("DELETE FROM notification_digest WHERE postTime < :before")
    suspend fun deleteOlderThan(before: Long)

    @Query("DELETE FROM notification_digest")
    suspend fun clearAll()

    @Query("SELECT COUNT(*) FROM notification_digest WHERE postTime > :since")
    suspend fun countSince(since: Long): Int

    @Query("""
        SELECT packageName, COUNT(*) as count 
        FROM notification_digest 
        WHERE postTime > :since 
        GROUP BY packageName 
        ORDER BY count DESC 
        LIMIT 3
    """)
    suspend fun getTop3AppsSince(since: Long): List<AppNotificationCount>

    @Query("SELECT * FROM notification_digest WHERE postTime > :since ORDER BY postTime DESC")
    fun getItemsSinceFlowOrdered(since: Long): Flow<List<NotificationDigestItem>>
}

data class AppNotificationCount(
    val packageName: String,
    val count: Int
)
