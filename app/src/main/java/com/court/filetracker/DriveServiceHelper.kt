package com.court.filetracker

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.api.client.extensions.android.http.AndroidHttp
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.FileContent
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale

object DriveServiceHelper {

    const val RC_SIGN_IN = 1001
    private const val BACKUP_FILE_NAME = "court_file_tracker_backup.db"

    /**
     * Request Google Drive Sign-In Permission
     */
    fun requestDriveSignIn(activity: Activity) {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(DriveScopes.DRIVE_FILE), Scope(DriveScopes.DRIVE_APPDATA))
            .build()

        val googleSignInClient = GoogleSignIn.getClient(activity, gso)
        activity.startActivityForResult(googleSignInClient.signInIntent, RC_SIGN_IN)
    }

    /**
     * Obtains the Drive API service client for the currently signed-in Google account
     */
    private fun getDriveService(context: Context): Drive? {
        val account = GoogleSignIn.getLastSignedInAccount(context) ?: return null
        val credential = GoogleAccountCredential.usingOAuth2(
            context, Collections.singleton(DriveScopes.DRIVE_FILE)
        )
        credential.selectedAccount = account.account

        return Drive.Builder(
            AndroidHttp.newCompatibleTransport(),
            GsonFactory.getDefaultInstance(),
            credential
        ).setApplicationName("Court File Tracker").build()
    }

    /**
     * Uploads the SQLite Room Database to Google Drive
     */
    fun performBackup(context: Context) {
        val driveService = getDriveService(context)
        if (driveService == null) {
            Toast.makeText(context, "Please sign in to Google Drive first!", Toast.LENGTH_LONG).show()
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Ensure room database checkpoints/flushesWAL mode changes
                val db = AppDatabase.getDatabase(context)
                db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").close()

                val dbFile = context.getDatabasePath("court_file_tracker_db")
                if (!dbFile.exists()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Local Database file not found!", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                // Check if a backup file already exists on Drive
                val queryResults = driveService.files().list()
                    .setQ("name = '$BACKUP_FILE_NAME' and trashed = false")
                    .setSpaces("drive")
                    .execute()

                val fileMetadata = com.google.api.services.drive.model.File().apply {
                    name = BACKUP_FILE_NAME
                }
                val mediaContent = FileContent("application/x-sqlite3", dbFile)

                val existingFiles = queryResults.files
                if (existingFiles.isNullOrEmpty()) {
                    // Create new backup file
                    driveService.files().create(fileMetadata, mediaContent).execute()
                } else {
                    // Update existing backup file
                    val existingFileId = existingFiles[0].id
                    driveService.files().update(existingFileId, null, mediaContent).execute()
                }

                withContext(Dispatchers.Main) {
                    val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                    Toast.makeText(context, "Cloud Backup Successful ($timeStr)!", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Backup Failed: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * Downloads the Database Backup from Google Drive and Restores it locally
     */
    fun performRestore(context: Context, onRestoreComplete: Runnable) {
        val driveService = getDriveService(context)
        if (driveService == null) {
            Toast.makeText(context, "Please sign in to Google Drive first!", Toast.LENGTH_LONG).show()
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val queryResults = driveService.files().list()
                    .setQ("name = '$BACKUP_FILE_NAME' and trashed = false")
                    .setSpaces("drive")
                    .execute()

                val files = queryResults.files
                if (files.isNullOrEmpty()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "No Cloud Backup file found on Google Drive!", Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }

                val driveFileId = files[0].id
                val inputStream: InputStream = driveService.files().get(driveFileId).executeMediaAsInputStream()

                // Close database instance before overwriting file
                AppDatabase.getDatabase(context).close()

                val dbFile = context.getDatabasePath("court_file_tracker_db")
                val walFile = context.getDatabasePath("court_file_tracker_db-wal")
                val shmFile = context.getDatabasePath("court_file_tracker_db-shm")

                // Remove temp WAL files to prevent room cached state conflicts
                if (walFile.exists()) walFile.delete()
                if (shmFile.exists()) shmFile.delete()

                // Overwrite the local SQLite database file
                FileOutputStream(dbFile).use { output ->
                    inputStream.copyTo(output)
                }

                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Cloud Data Restored Successfully! Restarting app UI...", Toast.LENGTH_LONG).show()
                    onRestoreComplete.run()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Restore Failed: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
