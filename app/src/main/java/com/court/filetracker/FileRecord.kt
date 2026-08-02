package com.court.filetracker

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "file_records")
data class FileRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val dispatchDate: String, 
    val courtNo: String,      
    val serialNo: String,     
    val fileNo: String,
    val status: String = "Dispatched", // Dispatched, Taken Up, Pass Over, Handed Back to Me, Not Sent to Court, Entry Deleted
    val storageLocation: String = "",
    val historyLog: String = ""        // Audit history stack trace
)

@Dao
interface FileRecordDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecord(record: FileRecord)

    @Delete
    suspend fun deleteRecord(record: FileRecord)

    @Query("SELECT * FROM file_records WHERE dispatchDate = :date ORDER BY courtNo ASC")
    fun getRecordsByDate(date: String): Flow<List<FileRecord>>

    @Query("SELECT DISTINCT courtNo FROM file_records WHERE dispatchDate = :date AND status != 'Entry Deleted' ORDER BY courtNo ASC")
    fun getCourtsByDate(date: String): Flow<List<String>>

    @Query("SELECT * FROM file_records WHERE dispatchDate = :date AND courtNo = :courtNo ORDER BY id ASC")
    fun getRecordsByDateAndCourt(date: String, courtNo: String): Flow<List<FileRecord>>

    @Query("SELECT * FROM file_records WHERE fileNo LIKE '%' || :query || '%' OR courtNo LIKE '%' || :query || '%' OR status LIKE '%' || :query || '%' OR storageLocation LIKE '%' || :query || '%' ORDER BY dispatchDate DESC")
    fun searchRecords(query: String): Flow<List<FileRecord>>
}

@Database(entities = [FileRecord::class], version = 2, exportSchema = false)
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
