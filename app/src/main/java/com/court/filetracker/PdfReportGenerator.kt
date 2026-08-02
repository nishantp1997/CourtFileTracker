package com.court.filetracker

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object PdfReportGenerator {

    // REPORT TYPE 1: Master Database Ledger
    fun generateMasterReport(context: Context, records: List<FileRecord>) {
        generatePdf(context, "MASTER DATABASE LEDGER", records, isMaster = true)
    }

    // REPORT TYPE 2: Single File Case Sheet History
    fun generateSingleFileReport(context: Context, record: FileRecord) {
        generatePdf(context, "CASE SHEET HISTORY - ${record.fileNo}", listOf(record), isSingleFile = true)
    }

    // REPORT TYPE 3: Date & Court-Wise Dispatch List
    fun generateDateCourtReport(context: Context, date: String, courtNo: String, records: List<FileRecord>) {
        generatePdf(context, "DISPATCH LIST - COURT $courtNo ($date)", records, isDateCourt = true)
    }

    private fun generatePdf(
        context: Context,
        reportTitle: String,
        records: List<FileRecord>,
        isMaster: Boolean = false,
        isSingleFile: Boolean = false,
        isDateCourt: Boolean = false
    ) {
        val pdfDocument = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create() // A4
        var page = pdfDocument.startPage(pageInfo)
        var canvas = page.canvas

        val paint = Paint().apply { textSize = 9f; color = Color.BLACK }
        val titlePaint = Paint().apply { textSize = 13f; isFakeBoldText = true; color = Color.BLACK }
        val headerPaint = Paint().apply { textSize = 9f; isFakeBoldText = true; color = Color.DKGRAY }
        val borderPaint = Paint().apply { color = Color.LTGRAY; style = Paint.Style.STROKE; strokeWidth = 1f }

        var y = 40f
        canvas.drawText("ALLAHABAD HIGH COURT - FILE TRACKER REPORT", 40f, y, titlePaint)
        y += 18f
        canvas.drawText("Report: $reportTitle | Generated: ${SimpleDateFormat("dd-MM-yy HH:mm", Locale.getDefault()).format(Date())}", 40f, y, paint)
        y += 15f
        canvas.drawLine(40f, y, 555f, y, borderPaint)
        y += 15f

        if (isSingleFile && records.isNotEmpty()) {
            val record = records.first()
            canvas.drawText("File Number: ${record.fileNo}", 40f, y, headerPaint)
            y += 15f
            canvas.drawText("Current Status: ${record.status}", 40f, y, paint)
            y += 15f
            canvas.drawText("Last Storage Location: ${record.storageLocation}", 40f, y, paint)
            y += 15f
            canvas.drawText("Remarks: ${record.remarks}", 40f, y, paint)
            y += 20f
            canvas.drawLine(40f, y, 555f, y, borderPaint)
            y += 15f
            canvas.drawText("Complete Movement Stack Trace History:", 40f, y, headerPaint)
            y += 15f

            record.historyLog.split("\n").filter { it.isNotBlank() }.forEach { line ->
                if (y > 780f) {
                    pdfDocument.finishPage(page)
                    page = pdfDocument.startPage(pageInfo)
                    canvas = page.canvas
                    y = 40f
                }
                canvas.drawText(line, 40f, y, paint)
                y += 14f
            }

        } else if (isMaster) {
            canvas.drawText("File No.", 40f, y, headerPaint)
            canvas.drawText("Status / Location", 150f, y, headerPaint)
            canvas.drawText("Audit Stack Trace", 310f, y, headerPaint)
            y += 12f
            canvas.drawLine(40f, y, 555f, y, borderPaint)
            y += 15f

            records.forEach { record ->
                if (y > 780f) {
                    pdfDocument.finishPage(page)
                    page = pdfDocument.startPage(pageInfo)
                    canvas = page.canvas
                    y = 40f
                }
                canvas.drawText(record.fileNo, 40f, y, paint)
                val statusText = "${record.status} (${record.storageLocation.ifEmpty { "Court " + record.courtNo }})"
                canvas.drawText(statusText.take(28), 150f, y, paint)

                val historyLines = record.historyLog.split("\n").filter { it.isNotBlank() }
                var lineY = y
                historyLines.forEach { line ->
                    if (lineY > 780f) {
                        pdfDocument.finishPage(page)
                        page = pdfDocument.startPage(pageInfo)
                        canvas = page.canvas
                        lineY = 40f
                    }
                    canvas.drawText(line.take(45), 310f, lineY, paint)
                    lineY += 12f
                }
                y = maxOf(y + 18f, lineY + 4f)
                canvas.drawLine(40f, y - 4f, 555f, y - 4f, borderPaint)
            }

        } else {
            // Date & Court-Wise Table
            canvas.drawText("S.No", 40f, y, headerPaint)
            canvas.drawText("File No.", 75f, y, headerPaint)
            canvas.drawText("Serial No.", 160f, y, headerPaint)
            canvas.drawText("Status / Location", 260f, y, headerPaint)
            canvas.drawText("Remarks", 410f, y, headerPaint)
            y += 12f
            canvas.drawLine(40f, y, 555f, y, borderPaint)
            y += 15f

            records.forEachIndexed { index, record ->
                if (y > 780f) {
                    pdfDocument.finishPage(page)
                    page = pdfDocument.startPage(pageInfo)
                    canvas = page.canvas
                    y = 40f
                }
                canvas.drawText("${index + 1}", 40f, y, paint)
                canvas.drawText(record.fileNo, 75f, y, paint)
                canvas.drawText(record.serialNo, 160f, y, paint)
                val locText = if (record.sentToChamber) "Chamber: ${record.judgeName}" else record.storageLocation.ifEmpty { record.status }
                canvas.drawText(locText.take(24), 260f, y, paint)
                canvas.drawText(record.remarks.take(22), 410f, y, paint)
                y += 16f
                canvas.drawLine(40f, y - 4f, 555f, y - 4f, borderPaint)
            }
        }

        pdfDocument.finishPage(page)

        val file = File(context.cacheDir, "Court_Report_${System.currentTimeMillis()}.pdf")
        pdfDocument.writeTo(FileOutputStream(file))
        pdfDocument.close()

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(shareIntent, "Share Court PDF Report"))
    }
}
