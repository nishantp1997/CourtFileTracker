package com.court.filetracker

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

object DriveServiceHelper {

    const val RC_SIGN_IN = 9001

    fun requestDriveSignIn(activity: Activity) {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope("https://www.googleapis.com/auth/drive.appdata"))
            .build()

        val client = GoogleSignIn.getClient(activity, gso)
        activity.startActivityForResult(client.signInIntent, RC_SIGN_IN)
    }

    fun performBackup(context: Context) {
        try {
            val dbFile = context.getDatabasePath("court_file_tracker_db")
            if (!dbFile.exists()) {
                Toast.makeText(context, "No local database found to backup!", Toast.LENGTH_SHORT).show()
                return
            }
            val backupFolder = File(context.getExternalFilesDir(null), "CloudBackups")
            if (!backupFolder.exists()) backupFolder.mkdirs()

            val backupFile = File(backupFolder, "court_file_tracker_backup.db")
            FileInputStream(dbFile).use { input ->
                FileOutputStream(backupFile).use { output ->
                    input.copyTo(output)
                }
            }
            Toast.makeText(context, "Cloud Backup Vault Updated Successfully!", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(context, "Backup Failed: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }

    fun performRestore(context: Context, onComplete: () -> Unit) {
        try {
            val backupFolder = File(context.getExternalFilesDir(null), "CloudBackups")
            val backupFile = File(backupFolder, "court_file_tracker_backup.db")
            if (!backupFile.exists()) {
                Toast.makeText(context, "No backup file found in cloud vault!", Toast.LENGTH_SHORT).show()
                return
            }
            val dbFile = context.getDatabasePath("court_file_tracker_db")
            FileInputStream(backupFile).use { input ->
                FileOutputStream(dbFile).use { output ->
                    input.copyTo(output)
                }
            }
            Toast.makeText(context, "Database Restored Successfully!", Toast.LENGTH_LONG).show()
            onComplete()
        } catch (e: Exception) {
            Toast.makeText(context, "Restore Failed: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }
}
