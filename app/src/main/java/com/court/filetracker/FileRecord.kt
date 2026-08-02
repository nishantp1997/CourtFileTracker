package com.court.filetracker

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import android.content.Context
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "file_records",
    indices = [Index(value = ["fileNo"], unique = true)]
)
data class FileRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fileNo: String,
    val dispatchDate: String,
    val courtNo: String,
    val serialNo: String,
    val status: String = "Dispatched",
    val storageLocation: String = "",
    val sentToChamber: Boolean = false,
    val judgeName: String = "",
    val remarks: String = "",
    val historyLog: String = ""
)


@Dao
interface FileRecordDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateRecord(record: FileRecord)

    @Query("SELECT * FROM file_records WHERE fileNo = :fileNo LIMIT 1")
    suspend fun getRecordByFileNo(fileNo: String): FileRecord?

    @Query("SELECT * FROM file_records WHERE dispatchDate = :date AND status != 'Entry Deleted' ORDER BY id DESC")
    fun getRecordsByDate(date: String): Flow<List<FileRecord>>

    @Query("SELECT DISTINCT courtNo FROM file_records WHERE dispatchDate = :date AND status != 'Entry Deleted' AND courtNo != 'N/A' ORDER BY courtNo ASC")
    fun getCourtsByDate(date: String): Flow<List<String>>

    @Query("SELECT * FROM file_records WHERE dispatchDate = :date AND courtNo = :courtNo AND status != 'Entry Deleted' ORDER BY id ASC")
    fun getRecordsByDateAndCourt(date: String, courtNo: String): Flow<List<FileRecord>>

    @Query("SELECT * FROM file_records WHERE status = 'Taken Up' ORDER BY dispatchDate DESC")
    fun getTakenUpRecords(): Flow<List<FileRecord>>

    @Query("SELECT * FROM file_records WHERE historyLog LIKE '%' || :searchPattern || '%' ORDER BY dispatchDate DESC")
    fun getHistoricalDispatches(searchPattern: String): Flow<List<FileRecord>>

    @Query("SELECT * FROM file_records ORDER BY fileNo ASC")
    fun getAllRecords(): Flow<List<FileRecord>>

    @Query("SELECT * FROM file_records WHERE fileNo LIKE '%' || :query || '%' OR courtNo LIKE '%' || :query || '%' OR status LIKE '%' || :query || '%' OR storageLocation LIKE '%' || :query || '%' OR judgeName LIKE '%' || :query || '%' OR remarks LIKE '%' || :query || '%' ORDER BY dispatchDate DESC")
    fun searchRecords(query: String): Flow<List<FileRecord>>
}

@Database(entities = [FileRecord::class], version = 4, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun fileRecordDao(): FileRecordDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "court_file_tracker_db"
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
