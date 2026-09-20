package com.court.filetracker

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

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

fun stripLeadingZeros(input: String): String {
    val trimmed = input.trim()
    if (trimmed.isBlank()) return ""
    
    if (trimmed.contains("/")) {
        val parts = trimmed.split("/")
        if (parts.size == 2) {
            val caseNum = parts[0].replaceFirst(Regex("^0+"), "").ifEmpty { "0" }
            return "$caseNum/${parts[1]}"
        }
    }
    
    if (trimmed.matches(Regex("^0+\\d+(\\.\\d+)?$"))) {
        return trimmed.replaceFirst(Regex("^0+"), "")
    }
    
    return trimmed
}

fun normalizeSearchQuery(query: String): String {
    return stripLeadingZeros(query)
}

@Entity(
    tableName = "file_records",
    indices = [Index(value = ["fileNo"], unique = true)]
)
data class FileRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fileNo: String,                  
    val dispatchDate: String,            
    val dispatchDatesCsv: String = "",   
    val courtNo: String = "N/A",         
    val serialNo: String = "",           
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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateAll(records: List<FileRecord>)

    @Query("SELECT * FROM file_records WHERE fileNo = :fileNo LIMIT 1")
    suspend fun getRecordByFileNo(fileNo: String): FileRecord?

    @Query("""
        SELECT * FROM file_records 
        WHERE (TRIM(dispatchDate) = TRIM(:date) 
           OR dispatchDatesCsv LIKE '%' || TRIM(:date) || '%'
           OR historyLog LIKE '%[' || TRIM(:date) || ']%')
        ORDER BY id DESC
    """)
    fun getRecordsByDate(date: String): Flow<List<FileRecord>>

    @Query("""
        SELECT DISTINCT TRIM(courtNo) FROM file_records 
        WHERE (TRIM(dispatchDate) = TRIM(:date) 
           OR dispatchDatesCsv LIKE '%' || TRIM(:date) || '%'
           OR historyLog LIKE '%[' || TRIM(:date) || ']%') 
        AND courtNo != 'N/A' 
        ORDER BY courtNo ASC
    """)
    fun getCourtsByDate(date: String): Flow<List<String>>

    @Query("""
        SELECT * FROM file_records 
        WHERE (TRIM(dispatchDate) = TRIM(:date) 
           OR dispatchDatesCsv LIKE '%' || TRIM(:date) || '%'
           OR historyLog LIKE '%[' || TRIM(:date) || ']%') 
        AND (TRIM(courtNo) = TRIM(:courtNo) OR LTRIM(courtNo, '0') = LTRIM(:courtNo, '0'))
        ORDER BY id ASC
    """)
    fun getRecordsByDateAndCourt(date: String, courtNo: String): Flow<List<FileRecord>>

    @Query("SELECT * FROM file_records ORDER BY fileNo ASC")
    fun getAllRecords(): Flow<List<FileRecord>>

    @Query("""
        SELECT * FROM file_records 
        WHERE fileNo LIKE '%' || :query || '%' 
           OR LTRIM(fileNo, '0') LIKE '%' || :query || '%'
           OR courtNo LIKE '%' || :query || '%' 
           OR LTRIM(courtNo, '0') LIKE '%' || :query || '%'
           OR serialNo LIKE '%' || :query || '%' 
           OR status LIKE '%' || :query || '%' 
           OR storageLocation LIKE '%' || :query || '%' 
           OR judgeName LIKE '%' || :query || '%' 
           OR remarks LIKE '%' || :query || '%' 
        ORDER BY id DESC
    """)
    fun searchRecords(query: String): Flow<List<FileRecord>>
}

@Database(entities = [FileRecord::class], version = 18, exportSchema = false)
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
