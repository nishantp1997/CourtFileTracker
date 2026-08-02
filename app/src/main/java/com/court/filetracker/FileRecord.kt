package com.court.filetracker

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "file_records",
    indices = [Index(value = ["fileNo"], unique = true)]
)
data class FileRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fileNo: String,                  // Unique Key (e.g. 1234/2026)
    val dispatchDate: String,            // Latest active dispatch date (DD-MM-YY)
    val dispatchDatesCsv: String = "",   // All historical dispatch dates (CSV format)
    val courtNo: String = "N/A",         // Active Court No. (Integer string)
    val serialNo: String = "",           // List Type & Decimal Serial No. (e.g. DCL - 15.5)
    val status: String = "Dispatched",   // Active Status
    val storageLocation: String = "",   // Active Location (Shelf/Bundle/Person/Seat)
    val sentToChamber: Boolean = false,  // Active Chamber Flag
    val judgeName: String = "",          // Hon'ble Judge Name
    val remarks: String = "",            // Case Notes
    val historyLog: String = ""          // Complete Audit Stack Trace
)

@Dao
interface FileRecordDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateRecord(record: FileRecord)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateAll(records: List<FileRecord>)

    @Query("SELECT * FROM file_records WHERE fileNo = :fileNo LIMIT 1")
    suspend fun getRecordByFileNo(fileNo: String): FileRecord?

    @Query("SELECT * FROM file_records WHERE dispatchDatesCsv LIKE '%' || :date || '%' AND status != 'Entry Deleted' ORDER BY id DESC")
    fun getRecordsByDate(date: String): Flow<List<FileRecord>>

    @Query("SELECT DISTINCT courtNo FROM file_records WHERE dispatchDatesCsv LIKE '%' || :date || '%' AND status != 'Entry Deleted' AND courtNo != 'N/A' ORDER BY courtNo ASC")
    fun getCourtsByDate(date: String): Flow<List<String>>

    @Query("SELECT * FROM file_records WHERE dispatchDatesCsv LIKE '%' || :date || '%' AND courtNo = :courtNo AND status != 'Entry Deleted' ORDER BY id ASC")
    fun getRecordsByDateAndCourt(date: String, courtNo: String): Flow<List<FileRecord>>

    @Query("SELECT * FROM file_records ORDER BY fileNo ASC")
    fun getAllRecords(): Flow<List<FileRecord>>

    @Query("SELECT * FROM file_records WHERE fileNo LIKE '%' || :query || '%' OR courtNo LIKE '%' || :query || '%' OR status LIKE '%' || :query || '%' OR storageLocation LIKE '%' || :query || '%' OR judgeName LIKE '%' || :query || '%' OR remarks LIKE '%' || :query || '%' ORDER BY id DESC")
    fun searchRecords(query: String): Flow<List<FileRecord>>
}

@Database(entities = [FileRecord::class], version = 6, exportSchema = false)
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
