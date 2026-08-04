package com.court.filetracker

import android.content.Context
import android.net.Uri
import android.widget.Toast
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.canvas.parser.PdfTextExtractor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object PdfImportHelper {

    fun restoreDatabaseFromPdf(
        context: Context,
        pdfUri: Uri,
        dao: FileRecordDao,
        onComplete: () -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val inputStream = context.contentResolver.openInputStream(pdfUri)
                if (inputStream == null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Unable to read selected PDF file!", Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }

                val pdfReader = PdfReader(inputStream)
                val pdfDoc = PdfDocument(pdfReader)
                val totalPages = pdfDoc.numberOfPages

                val fullTextBuilder = StringBuilder()
                for (i in 1..totalPages) {
                    val page = pdfDoc.getPage(i)
                    val text = PdfTextExtractor.getTextFromPage(page)
                    fullTextBuilder.append(text).append("\n")
                }

                pdfDoc.close()
                pdfReader.close()
                inputStream.close()

                val extractedRecords = parsePdfContent(fullTextBuilder.toString())

                if (extractedRecords.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "No valid case records found in the selected PDF!", Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }

                dao.insertOrUpdateAll(extractedRecords)

                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        "Database Rebuilt Successfully! Restored ${extractedRecords.size} records.",
                        Toast.LENGTH_LONG
                    ).show()
                    onComplete()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "PDF Restoration Failed: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun parsePdfContent(rawText: String): List<FileRecord> {
        val records = mutableListOf<FileRecord>()
        val lines = rawText.split("\n")

        var currentFileNo = ""
        var currentStatus = "Dispatched"
        var currentCourtNo = "N/A"
        var currentSerialNo = ""
        var currentLocation = ""
        var currentJudge = ""
        var currentRemarks = ""
        val historyLines = mutableListOf<String>()

        val caseNoRegex = Regex("^(\\d{1,6}/\\d{4})")
        val dateRegex = Regex("(\\d{2}-\\d{2}-\\d{2})")

        fun commitCurrentRecord() {
            if (currentFileNo.isNotBlank()) {
                val fullHistory = historyLines.joinToString("\n")
                val datesInHistory = dateRegex.findAll(fullHistory)
                    .map { it.groupValues[1] }
                    .distinct()
                    .toList()

                val latestDate = datesInHistory.lastOrNull() ?: "01-01-26"
                val datesCsv = datesInHistory.joinToString(", ")

                val courtMatch = Regex("Court No:\\s*(\\d+)").find(fullHistory)
                if (courtMatch != null) {
                    currentCourtNo = courtMatch.groupValues[1]
                }

                val serialMatch = Regex("Serial:\\s*([A-Za-z0-9\\s\\-\\.]+?)(?=\\||\$)").find(fullHistory)
                if (serialMatch != null) {
                    currentSerialNo = serialMatch.groupValues[1].trim()
                }

                records.add(
                    FileRecord(
                        fileNo = currentFileNo,
                        dispatchDate = latestDate,
                        dispatchDatesCsv = datesCsv,
                        courtNo = currentCourtNo,
                        serialNo = currentSerialNo,
                        status = currentStatus,
                        storageLocation = currentLocation,
                        sentToChamber = currentStatus.contains("Chamber", ignoreCase = true) || currentJudge.isNotBlank(),
                        judgeName = currentJudge,
                        remarks = currentRemarks,
                        historyLog = fullHistory
                    )
                )
            }
        }

        for (line in lines) {
            val cleanLine = line.replace("|", "").trim()
            if (cleanLine.isBlank() || cleanLine.contains("ALLAHABAD HIGH COURT") || cleanLine.contains("Report Type:")) {
                continue
            }

            val caseMatch = caseNoRegex.find(cleanLine)
            if (caseMatch != null) {
                commitCurrentRecord()

                currentFileNo = caseMatch.groupValues[1]
                currentStatus = "Dispatched"
                currentCourtNo = "N/A"
                currentSerialNo = ""
                currentLocation = ""
                currentJudge = ""
                currentRemarks = ""
                historyLines.clear()
                continue
            }

            if (cleanLine.contains("Taken Up") || cleanLine.contains("Pass Over") || cleanLine.contains("Not Sent to Court") || cleanLine.contains("Received from Court") || cleanLine.contains("DELETED")) {
                if (cleanLine.contains("Taken Up")) currentStatus = "Taken Up"
                else if (cleanLine.contains("Pass Over")) currentStatus = "Pass Over"
                else if (cleanLine.contains("Not Sent to Court")) currentStatus = "Not Sent to Court"
                else if (cleanLine.contains("Received from Court")) currentStatus = "Received from Court"
                else if (cleanLine.contains("DELETED")) currentStatus = "Entry Deleted"

                val locMatch = Regex("\\(([^)]+)\\)").find(cleanLine)
                if (locMatch != null) {
                    currentLocation = locMatch.groupValues[1].trim()
                }
            }

            if (dateRegex.containsMatchIn(cleanLine)) {
                var formattedLine = cleanLine
                if (!formattedLine.startsWith("[")) formattedLine = "[$formattedLine"
                historyLines.add(formattedLine)
            }
        }

        commitCurrentRecord()
        return records
    }
}
