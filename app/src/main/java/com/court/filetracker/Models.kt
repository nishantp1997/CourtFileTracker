package com.court.filetracker

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "file_records",
    indices = [Index(value = ["fileNo"], unique = true)]
)
data class FileRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val fileNo: String,                  // e.g. "11000/2026"
    val dispatchDate: String,            // e.g. "21-09-26"
    val dispatchDatesCsv: String,
    val courtNo: String,                 // e.g. "80"
    val serialNo: String,                // e.g. "DCL - 88"
    val status: String,                  // e.g. "Dispatched", "Taken Up", "Received from Court"
    val storageLocation: String,         // e.g. "Listing Seat", "Shelf"
    val sentToChamber: Boolean = false,
    val judgeName: String = "",
    val remarks: String = "",
    val historyLog: String = "",
    val reportsOnRecord: String = "",      // Reports kept on record
    val applicationsOnRecord: String = "" // Applications [App No]/[Year]
)

@Entity(
    tableName = "cause_list_records",
    indices = [Index(value = ["causeListDate", "courtNo", "serialNo"])]
)
data class CauseListRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val causeListDate: String,           // e.g. "21-09-26"
    val courtNo: String,                 // e.g. "80"
    val serialNo: String,                // e.g. "88", "88.1"
    val statusTag: String = "",          // "PO", "WC", "DF", "LO", "TU", "LAFP", etc.
    val listType: String,                // "DCL", "ACL", "Correction"
    val caseType: String,                // "NA528", "A482", "CRLA"
    val fileSerialNo: String,            // "11000"
    val fileYear: String,                // "2026"
    val fileNo: String,                  // "11000/2026"
    val partyName: String = "",          // "P1 VS P2"
    val caseCin: String? = ""            // Nullable type prevents Gson deserialization crashes on old backups
)

data class FullDatabaseBackupPayload(
    val version: Int = 2,
    val exportTimestamp: String,
    val fileRecords: List<FileRecord>,
    val causeListRecords: List<CauseListRecord>
)
