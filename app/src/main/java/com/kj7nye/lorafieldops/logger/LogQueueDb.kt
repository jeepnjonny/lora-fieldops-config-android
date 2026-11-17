package com.kj7nye.lorafieldops.logger

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import com.kj7nye.lorafieldops.model.CoverageEntry
import com.kj7nye.lorafieldops.model.CoverageEntryType
import kotlinx.coroutines.flow.Flow

/** One queued upload entry. Stored until the server has taken it, so it survives the app being killed. */
@Entity(tableName = "log_queue")
data class LogQueueRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val device: String,
    val type: String,
    val ts: Long,
    val packet: String?,
    val text: String?,
    val rssi: Int?,
    val snr: Double?,
)

@Dao
interface LogQueueDao {
    @Insert
    suspend fun insert(rows: List<LogQueueRow>)

    @Query("SELECT * FROM log_queue ORDER BY id LIMIT 1")
    suspend fun oldest(): LogQueueRow?

    @Query("SELECT * FROM log_queue WHERE device = :device ORDER BY id LIMIT :limit")
    suspend fun oldestForDevice(device: String, limit: Int): List<LogQueueRow>

    @Query("DELETE FROM log_queue WHERE id IN (:ids)")
    suspend fun deleteIds(ids: List<Long>)

    @Query("SELECT COUNT(*) FROM log_queue")
    fun countFlow(): Flow<Int>

    @Query("SELECT COUNT(*) FROM log_queue")
    suspend fun count(): Int
}

@Database(entities = [LogQueueRow::class], version = 1, exportSchema = false)
abstract class LogQueueDb : RoomDatabase() {
    abstract fun dao(): LogQueueDao

    companion object {
        @Volatile private var instance: LogQueueDb? = null

        fun get(context: Context): LogQueueDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, LogQueueDb::class.java, "coverage_log_queue.db")
                .build().also { instance = it }
        }
    }
}

/** [UploadQueue] on Room. SQLite caps bound parameters, so deletes go in chunks. */
class RoomUploadQueue(private val dao: LogQueueDao) : UploadQueue {
    override suspend fun nextBatch(limit: Int): List<QueuedEntry> {
        val first = dao.oldest() ?: return emptyList()
        return dao.oldestForDevice(first.device, limit).mapNotNull { it.toQueued() }
    }

    override suspend fun delete(ids: List<Long>) {
        ids.chunked(500).forEach { dao.deleteIds(it) }
    }
}

fun CoverageEntry.toRow(device: String) = LogQueueRow(
    device = device, type = type.wire, ts = ts, packet = packet, text = text, rssi = rssi, snr = snr,
)

private fun LogQueueRow.toQueued(): QueuedEntry? {
    val t = CoverageEntryType.fromWire(type) ?: return null
    return QueuedEntry(id, device, CoverageEntry(t, ts, packet, text, rssi, snr))
}
