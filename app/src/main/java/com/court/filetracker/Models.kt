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
    val fileNo: String,               // e.g. "30174/2026"
    val dispatchDate: String,         // e.g. "18-09-26"
    val dispatchDatesCsv: String,
    val courtNo: String,              // e.g. "79"
    val serialNo: String,             // e.g. "DCL - 1"
    val status: String,               // e.g. "Dispatched", "Taken Up", "Received from Court"
    val storageLocation: String,      // e.g. "Listing Seat", "Shelf"
    val sentToChamber: Boolean = false,
    val judgeName: String = "",
    val remarks: String = "",
    val historyLog: String = "",
    val reportsOnRecord: String = "",     // JSON / Multiline reports: "Notice: Served (16-09-26)"
    val applicationsOnRecord: String = "" // CSV: "9/2026, 14/2025"
)

@Entity(
    tableName = "cause_list_records",
    indices = [Index(value = ["causeListDate", "courtNo", "serialNo"])]
)
data class CauseListRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val causeListDate: String,        // e.g. "18-09-26"
    val courtNo: String,              // e.g. "79"
    val serialNo: String,             // e.g. "1", "238.1"
    val listType: String,             // "DCL", "ACL", "Correction"
    val caseType: String,             // "NA528", "A482", "CRLA"
    val fileSerialNo: String,         // "30174"
    val fileYear: String,             // "2026"
    val fileNo: String,               // "30174/2026"
    val partyName: String = "",       // "RAM KISHAN VS STATE OF U.P."
    val petitionerCounsel: String = "",
    val respondentCounsel: String = "",
    val connectedCases: String = ""   // e.g. "CRLA/1965/1988 (Hanif vs State)"
)

data class FullDatabaseBackupPayload(
    val version: Int = 2,
    val exportTimestamp: String,
    val fileRecords: List<FileRecord>,
    val causeListRecords: List<CauseListRecord>
)
