package com.court.filetracker

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface FileRecordDao {
    @Query("SELECT * FROM file_records ORDER BY id DESC")
    fun getAllRecords(): Flow<List<FileRecord>>

    @Query("SELECT * FROM file_records WHERE fileNo = :fileNo LIMIT 1")
    suspend fun getRecordByFileNo(fileNo: String): FileRecord?

    @Query("SELECT * FROM file_records WHERE dispatchDatesCsv LIKE '%' || :date || '%' OR dispatchDate = :date")
    fun getRecordsByDate(date: String): Flow<List<FileRecord>>

    @Query("SELECT * FROM file_records WHERE fileNo LIKE '%' || :query || '%'")
    fun searchRecords(query: String): Flow<List<FileRecord>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateRecord(record: FileRecord): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateAll(records: List<FileRecord>)
}

@Dao
interface CauseListDao {
    @Query("SELECT DISTINCT courtNo FROM cause_list_records WHERE causeListDate = :date ORDER BY CAST(courtNo AS INTEGER) ASC")
    fun getCourtsForDate(date: String): Flow<List<String>>

    @Query("SELECT * FROM cause_list_records WHERE causeListDate = :date AND courtNo = :courtNo ORDER BY id ASC")
    fun getCasesForCourtAndDate(date: String, courtNo: String): Flow<List<CauseListRecord>>

    @Query("SELECT * FROM cause_list_records ORDER BY id DESC")
    fun getAllCauseListRecords(): Flow<List<CauseListRecord>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(records: List<CauseListRecord>)

    @Query("DELETE FROM cause_list_records WHERE causeListDate <= :cutoffDate")
    suspend fun deleteCauseListsUpToDate(cutoffDate: String): Int

    @Query("DELETE FROM cause_list_records WHERE causeListDate = :date AND courtNo = :courtNo")
    suspend fun deleteForDateAndCourt(date: String, courtNo: String): Int
}

@Database(entities = [FileRecord::class, CauseListRecord::class], version = 4, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun fileRecordDao(): FileRecordDao
    abstract fun causeListDao(): CauseListDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "court_file_tracker.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
