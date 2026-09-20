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
import com.google.gson.reflect.TypeToken
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

    /**
     * Share Database File via WhatsApp or Android System Share Sheet
     */
    fun shareDatabaseToWhatsApp(context: Context, records: List<FileRecord>) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (records.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "No records found in database to export!", Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }

                val gson = GsonBuilder().setPrettyPrinting().create()
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

    /**
     * Download / Save JSON Database File to Device Downloads Folder
     */
    fun downloadDatabaseJson(context: Context, records: List<FileRecord>) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (records.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "No records available to save!", Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }

                val gson = GsonBuilder().setPrettyPrinting().create()
                val jsonString = gson.toJson(records)

                val timeStamp = SimpleDateFormat("dd-MM-yy_HHmm", Locale.getDefault()).format(Date())
                val fileName = "court_tracker_backup_$timeStamp.json"

                var isSaved = false

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // Scoped Storage / MediaStore API for Android 10+
                    val contentValues = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                        put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    }

                    val resolver = context.contentResolver
                    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)

                    if (uri != null) {
                        resolver.openOutputStream(uri)?.use { output ->
                            output.write(jsonString.toByteArray())
                        }
                        isSaved = true
                    }
                } else {
                    // Legacy Storage for Android 9 and lower
                    val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    if (!downloadsDir.exists()) downloadsDir.mkdirs()

                    val targetFile = File(downloadsDir, fileName)
                    FileOutputStream(targetFile).use { output ->
                        output.write(jsonString.toByteArray())
                    }
                    isSaved = true
                }

                withContext(Dispatchers.Main) {
                    if (isSaved) {
                        Toast.makeText(
                            context,
                            "File Saved to Downloads!\n$fileName (${records.size} Records)",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        Toast.makeText(context, "Failed to save JSON backup!", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Download Error: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * Import JSON Backup File from Storage / File Picker
     */
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
