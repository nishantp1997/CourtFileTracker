package com.court.filetracker

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.core.content.FileProvider
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object JsonBackupHelper {

    fun exportFullBackup(
        context: Context,
        fileRecords: List<FileRecord>,
        causeListRecords: List<CauseListRecord>,
        shareDirectly: Boolean
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val timeStamp = SimpleDateFormat("dd-MM-yy_HHmm", Locale.getDefault()).format(Date())
                val payload = FullDatabaseBackupPayload(
                    exportTimestamp = timeStamp,
                    fileRecords = fileRecords,
                    causeListRecords = causeListRecords
                )

                val gson = GsonBuilder().setPrettyPrinting().create()
                val jsonString = gson.toJson(payload)
                val fileName = "court_tracker_full_backup_$timeStamp.json"

                if (shareDirectly) {
                    val cacheDir = File(context.cacheDir, "backups").apply { if (!exists()) mkdirs() }
                    val backupFile = File(cacheDir, fileName).apply { writeText(jsonString) }
                    val fileUri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", backupFile)

                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "application/json"
                        putExtra(Intent.EXTRA_STREAM, fileUri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }

                    withContext(Dispatchers.Main) {
                        context.startActivity(Intent.createChooser(shareIntent, "Share JSON Backup via:"))
                    }
                } else {
                    var isSaved = false
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val contentValues = ContentValues().apply {
                            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                            put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                        }
                        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                        if (uri != null) {
                            context.contentResolver.openOutputStream(uri)?.use { it.write(jsonString.toByteArray()) }
                            isSaved = true
                        }
                    } else {
                        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                        if (!downloadsDir.exists()) downloadsDir.mkdirs()
                        val targetFile = File(downloadsDir, fileName)
                        FileOutputStream(targetFile).use { it.write(jsonString.toByteArray()) }
                        isSaved = true
                    }

                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, if (isSaved) "Backup saved to Downloads: $fileName" else "Save failed", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Export Error: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    fun importFullBackup(
        context: Context,
        fileUri: Uri,
        fileDao: FileRecordDao,
        causeListDao: CauseListDao,
        onComplete: () -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val jsonString = context.contentResolver.openInputStream(fileUri)?.bufferedReader()?.use { it.readText() }
                if (jsonString.isNullOrBlank()) {
                    withContext(Dispatchers.Main) { Toast.makeText(context, "File is empty!", Toast.LENGTH_SHORT).show() }
                    return@launch
                }

                val gson = Gson()
                val payload = gson.fromJson(jsonString, FullDatabaseBackupPayload::class.java)

                // Smart Upsert: Merging File Records and Metadata without loss
                payload.fileRecords.forEach { imported ->
                    val local = fileDao.getRecordByFileNo(imported.fileNo)
                    if (local != null) {
                        val mergedReports = listOf(local.reportsOnRecord, imported.reportsOnRecord)
                            .filter { it.isNotBlank() }.distinct().joinToString("\n")
                        val mergedApps = listOf(local.applicationsOnRecord, imported.applicationsOnRecord)
                            .filter { it.isNotBlank() }.distinct().joinToString(", ")
                        val mergedHistory = if (!local.historyLog.contains(imported.historyLog)) {
                            "${local.historyLog}\n${imported.historyLog}".trim()
                        } else local.historyLog

                        fileDao.insertOrUpdateRecord(
                            local.copy(
                                reportsOnRecord = mergedReports,
                                applicationsOnRecord = mergedApps,
                                historyLog = mergedHistory,
                                storageLocation = imported.storageLocation.ifBlank { local.storageLocation },
                                status = imported.status
                            )
                        )
                    } else {
                        fileDao.insertOrUpdateRecord(imported.copy(id = 0))
                    }
                }

                // Insert Cause List Records
                if (!payload.causeListRecords.isNullOrEmpty()) {
                    causeListDao.insertAll(payload.causeListRecords.map { it.copy(id = 0) })
                }

                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Smart Upsert Completed: Restored ${payload.fileRecords.size} Files!", Toast.LENGTH_LONG).show()
                    onComplete()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Import Failed: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
