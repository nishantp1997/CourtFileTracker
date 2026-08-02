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

    // Legal Size Landscape Dimensions in Points (1008 x 612)
    private const val PAGE_WIDTH = 1008
    private const val PAGE_HEIGHT = 612

    fun generateMasterReport(context: Context, records: List<FileRecord>) {
        generatePdf(context, "MASTER DATABASE LEDGER WITH AUDIT TRAIL", records, isMaster = true)
    }

    fun generateSingleFileReport(context: Context, record: FileRecord) {
        generatePdf(context, "CASE FILE HISTORY REPORT - ${record.fileNo}", listOf(record), isSingleFile = true)
    }

    fun generateDateCourtReport(context: Context, date: String, courtNo: String, records: List<FileRecord>) {
        generatePdf(context, "DISPATCH REPORT - COURT NO. $courtNo ON DATE: $date", records, isDateCourt = true)
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
        val pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, 1).create()
        var page = pdfDocument.startPage(pageInfo)
        var canvas = page.canvas

        val paint = Paint().apply { textSize = 10f; color = Color.BLACK }
        val titlePaint = Paint().apply { textSize = 15f; isFakeBoldText = true; color = Color.BLACK }
        val headerPaint = Paint().apply { textSize = 10f; isFakeBoldText = true; color = Color.DKGRAY }
        val borderPaint = Paint().apply { color = Color.LTGRAY; style = Paint.Style.STROKE; strokeWidth = 1f }

        var y = 45f
        canvas.drawText("ALLAHABAD HIGH COURT - FILE MOVEMENT & CASE TRACKING SYSTEM", 40f, y, titlePaint)
        y += 20f
        canvas.drawText("Report: $reportTitle | Generated: ${SimpleDateFormat("dd-MM-yy HH:mm", Locale.getDefault()).format(Date())}", 40f, y, paint)
        y += 15f
        canvas.drawLine(40f, y, 968f, y, borderPaint)
        y += 20f

        if (isSingleFile && records.isNotEmpty()) {
            val record = records.first()
            canvas.drawText("File Number: ${record.fileNo}", 40f, y, headerPaint)
            y += 18f
            canvas.drawText("Current Status: ${record.status}", 40f, y, paint)
            y += 18f
            canvas.drawText("Active Location: ${record.storageLocation}", 40f, y, paint)
            y += 18f
            canvas.drawText("Remarks: ${record.remarks}", 40f, y, paint)
            y += 22f
            canvas.drawLine(40f, y, 968f, y, borderPaint)
            y += 20f
            canvas.drawText("Complete Audit Stack Trace & Dispatch History:", 40f, y, headerPaint)
            y += 18f

            record.historyLog.split("\n").filter { it.isNotBlank() }.forEach { line ->
                if (y > 550f) {
                    pdfDocument.finishPage(page)
                    page = pdfDocument.startPage(pageInfo)
                    canvas = page.canvas
                    y = 45f
                }
                canvas.drawText(line, 40f, y, paint)
                y += 16f
            }

        } else if (isMaster) {
            canvas.drawText("File No.", 40f, y, headerPaint)
            canvas.drawText("Active Status / Location", 180f, y, headerPaint)
            canvas.drawText("All Historical Dispatch Dates", 400f, y, headerPaint)
            canvas.drawText("Complete Audit Stack Trace", 650f, y, headerPaint)
            y += 15f
            canvas.drawLine(40f, y, 968f, y, borderPaint)
            y += 18f

            records.forEach { record ->
                if (y > 550f) {
                    pdfDocument.finishPage(page)
                    page = pdfDocument.startPage(pageInfo)
                    canvas = page.canvas
                    y = 45f
                }
                canvas.drawText(record.fileNo, 40f, y, paint)
                val statusText = "${record.status} (${record.storageLocation.ifEmpty { "Court " + record.courtNo }})"
                canvas.drawText(statusText.take(30), 180f, y, paint)
                canvas.drawText(record.dispatchDatesCsv, 400f, y, paint)

                val historyLines = record.historyLog.split("\n").filter { it.isNotBlank() }
                var lineY = y
                historyLines.forEach { line ->
                    if (lineY > 550f) {
                        pdfDocument.finishPage(page)
                        page = pdfDocument.startPage(pageInfo)
                        canvas = page.canvas
                        lineY = 45f
                    }
                    canvas.drawText(line.take(55), 650f, lineY, paint)
                    lineY += 14f
                }
                y = maxOf(y + 22f, lineY + 6f)
                canvas.drawLine(40f, y - 6f, 968f, y - 6f, borderPaint)
            }

        } else {
            // Date & Court-Wise Report
            canvas.drawText("S.No", 40f, y, headerPaint)
            canvas.drawText("File No.", 80f, y, headerPaint)
            canvas.drawText("Serial No.", 200f, y, headerPaint)
            canvas.drawText("Status / Location", 340f, y, headerPaint)
            canvas.drawText("Remarks", 560f, y, headerPaint)
            canvas.drawText("Audit Stack Trace Log", 750f, y, headerPaint)
            y += 15f
            canvas.drawLine(40f, y, 968f, y, borderPaint)
            y += 18f

            records.forEachIndexed { index, record ->
                if (y > 550f) {
                    pdfDocument.finishPage(page)
                    page = pdfDocument.startPage(pageInfo)
                    canvas = page.canvas
                    y = 45f
                }
                canvas.drawText("${index + 1}", 40f, y, paint)
                canvas.drawText(record.fileNo, 80f, y, paint)
                canvas.drawText(record.serialNo, 200f, y, paint)
                val locText = if (record.sentToChamber) "Chamber: ${record.judgeName}" else record.storageLocation.ifEmpty { record.status }
                canvas.drawText(locText.take(28), 340f, y, paint)
                canvas.drawText(record.remarks.take(25), 560f, y, paint)

                val lastLog = record.historyLog.split("\n").lastOrNull { it.isNotBlank() } ?: ""
                canvas.drawText(lastLog.take(35), 750f, y, paint)
                y += 20f
                canvas.drawLine(40f, y - 6f, 968f, y - 6f, borderPaint)
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
