package com.court.filetracker

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object JsonBackupHelper {

    fun shareDatabaseToWhatsApp(context: Context, records: List<FileRecord>) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val gson = Gson()
                val jsonString = gson.toJson(records)

                val timeStamp = SimpleDateFormat("dd-MM-yy_HHmm", Locale.getDefault()).format(Date())
                val fileName = "court_tracker_backup_$timeStamp.json"
                val cacheDir = File(context.cacheDir, "backups")
                if (!cacheDir.exists()) cacheDir.mkdirs()

                val backupFile = File(cacheDir, fileName)
                backupFile.writeText(jsonString)

                val fileUri: Uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    backupFile
                )

                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/json"
                    putExtra(Intent.EXTRA_STREAM, fileUri)
                    setPackage("com.whatsapp")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                withContext(Dispatchers.Main) {
                    try {
                        context.startActivity(shareIntent)
                    } catch (e: Exception) {
                        val chooserIntent = Intent.createChooser(
                            Intent(Intent.ACTION_SEND).apply {
                                type = "application/json"
                                putExtra(Intent.EXTRA_STREAM, fileUri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            },
                            "Send Database Backup via:"
                        )
                        context.startActivity(chooserIntent)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Export Failed: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    fun importDatabaseFromJson(
        context: Context,
        fileUri: Uri,
        dao: FileRecordDao,
        onComplete: () -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val inputStream = context.contentResolver.openInputStream(fileUri)
                val jsonString = inputStream?.bufferedReader()?.use { it.readText() }

                if (jsonString.isNullOrBlank()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Failed to read backup file!", Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }

                val gson = Gson()
                val listType = object : TypeToken<List<FileRecord>>() {}.type
                val importedRecords: List<FileRecord> = gson.fromJson(jsonString, listType)

                if (importedRecords.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Backup file contains no records!", Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }

                dao.insertOrUpdateAll(importedRecords)

                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        "Successfully imported ${importedRecords.size} records!",
                        Toast.LENGTH_LONG
                    ).show()
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
