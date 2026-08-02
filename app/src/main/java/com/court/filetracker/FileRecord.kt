package com.court.filetracker

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

// Date normalization helper: converts 4-8-2026, 04-8-26, etc. to standardized DD-MM-YY
fun normalizeDate(input: String): String {
    val clean = input.trim().replace("/", "-")
    val parts = clean.split("-")
    if (parts.size != 3) return clean

    val day = parts[0].padStart(2, '0')
    val month = parts[1].padStart(2, '0')
    var year = parts[2]
    if (year.length == 4) year = year.substring(2)

    return "$day-$month-$year"
}

// Utility to normalize search tokens by trimming leading zeros from numeric strings
fun normalizeSearchQuery(query: String): String {
    val trimmed = query.trim()
    return if (trimmed.matches(Regex("^0+\\d+$"))) trimmed.replaceFirst(Regex("^0+"), "") else trimmed
}

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
    val storageLocation: String = "",   // Active Location (Shelf/Bundle/Person/Seat/Chamber)
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

    @Query("""
        SELECT * FROM file_records 
        WHERE (TRIM(dispatchDate) = TRIM(:date) OR dispatchDatesCsv LIKE '%' || TRIM(:date) || '%')
        ORDER BY id DESC
    """)
    fun getRecordsByDate(date: String): Flow<List<FileRecord>>

    @Query("""
        SELECT DISTINCT TRIM(courtNo) FROM file_records 
        WHERE (TRIM(dispatchDate) = TRIM(:date) OR dispatchDatesCsv LIKE '%' || TRIM(:date) || '%') 
        AND courtNo != 'N/A' 
        ORDER BY courtNo ASC
    """)
    fun getCourtsByDate(date: String): Flow<List<String>>

    @Query("""
        SELECT * FROM file_records 
        WHERE (TRIM(dispatchDate) = TRIM(:date) OR dispatchDatesCsv LIKE '%' || TRIM(:date) || '%') 
        AND (TRIM(courtNo) = TRIM(:courtNo) OR LTRIM(courtNo, '0') = LTRIM(:courtNo, '0'))
        ORDER BY id ASC
    """)
    fun getRecordsByDateAndCourt(date: String, courtNo: String): Flow<List<FileRecord>>

    @Query("SELECT * FROM file_records ORDER BY fileNo ASC")
    fun getAllRecords(): Flow<List<FileRecord>>

    // Strict Zero-Ignored Keyword Search across Case No, Court No, Serial No, Status & Remarks
    @Query("""
        SELECT * FROM file_records 
        WHERE fileNo LIKE '%' || :query || '%' 
           OR LTRIM(fileNo, '0') LIKE '%' || LTRIM(:query, '0') || '%'
           OR courtNo LIKE '%' || :query || '%' 
           OR LTRIM(courtNo, '0') LIKE '%' || LTRIM(:query, '0') || '%'
           OR serialNo LIKE '%' || :query || '%'
           OR LTRIM(serialNo, '0') LIKE '%' || LTRIM(:query, '0') || '%'
           OR status LIKE '%' || :query || '%' 
           OR storageLocation LIKE '%' || :query || '%' 
           OR judgeName LIKE '%' || :query || '%' 
           OR remarks LIKE '%' || :query || '%' 
        ORDER BY id DESC
    """)
    fun searchRecords(query: String): Flow<List<FileRecord>>
}

@Database(entities = [FileRecord::class], version = 11, exportSchema = false)
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
