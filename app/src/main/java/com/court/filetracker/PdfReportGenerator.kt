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

    private const val PAGE_WIDTH = 1008
    private const val PAGE_HEIGHT = 612

    fun generateMasterReport(context: Context, records: List<FileRecord>) {
        generatePdf(context, "MASTER DATABASE LEDGER WITH COMPLETE AUDIT TRAIL", records, isMaster = true)
    }

    fun generateSingleFileReport(context: Context, record: FileRecord) {
        generatePdf(context, "PARTICULAR CASE FILE HISTORY REPORT - ${record.fileNo}", listOf(record), isSingleFile = true)
    }

    fun generateDateCourtReport(context: Context, date: String, courtNo: String, records: List<FileRecord>) {
        generatePdf(context, "DAILY COURT DISPATCH REPORT - COURT NO. $courtNo ($date)", records, isDateCourt = true)
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

        val paint = Paint().apply { textSize = 9.5f; color = Color.BLACK; isAntiAlias = true }
        val titlePaint = Paint().apply { textSize = 14f; isFakeBoldText = true; color = Color.rgb(20, 40, 80); isAntiAlias = true }
        val headerPaint = Paint().apply { textSize = 10f; isFakeBoldText = true; color = Color.WHITE; isAntiAlias = true }
        val headerBgPaint = Paint().apply { color = Color.rgb(40, 60, 100); style = Paint.Style.FILL }
        val altRowBgPaint = Paint().apply { color = Color.rgb(245, 247, 250); style = Paint.Style.FILL }
        val borderPaint = Paint().apply { color = Color.LTGRAY; style = Paint.Style.STROKE; strokeWidth = 0.8f }

        var y = 40f
        canvas.drawText("ALLAHABAD HIGH COURT - FILE MOVEMENT & TRACKING SYSTEM", 35f, y, titlePaint)
        y += 18f
        canvas.drawText("Report Type: $reportTitle | Generated: ${SimpleDateFormat("dd-MM-yy", Locale.getDefault()).format(Date())}", 35f, y, paint)
        y += 12f
        canvas.drawLine(35f, y, 973f, y, borderPaint)
        y += 15f

        if (isSingleFile && records.isNotEmpty()) {
            val record = records.first()
            canvas.drawText("File Number: ${record.fileNo}", 35f, y, titlePaint)
            y += 18f
            canvas.drawText("Current Status: ${record.status}", 35f, y, paint)
            y += 16f
            canvas.drawText("Court No: ${record.courtNo} | Serial/List: ${record.serialNo}", 35f, y, paint)
            y += 16f
            canvas.drawText("Storage Location: ${record.storageLocation.ifEmpty { "N/A" }}", 35f, y, paint)
            y += 16f
            canvas.drawText("All Historical Dispatch Dates: ${record.dispatchDatesCsv}", 35f, y, paint)
            y += 16f
            canvas.drawText("Remarks: ${record.remarks.ifEmpty { "None" }}", 35f, y, paint)
            y += 20f
            canvas.drawLine(35f, y, 973f, y, borderPaint)
            y += 18f
            canvas.drawText("Complete Audit Stack Trace Log:", 35f, y, titlePaint)
            y += 18f

            record.historyLog.split("\n").filter { it.isNotBlank() }.forEach { line ->
                if (y > 560f) {
                    pdfDocument.finishPage(page)
                    page = pdfDocument.startPage(pageInfo)
                    canvas = page.canvas
                    y = 40f
                }
                canvas.drawText(line, 35f, y, paint)
                y += 15f
            }

        } else if (isMaster) {
            canvas.drawRect(35f, y, 973f, y + 20f, headerBgPaint)
            canvas.drawText("File No.", 40f, y + 14f, headerPaint)
            canvas.drawText("Active Status & Location", 160f, y + 14f, headerPaint)
            canvas.drawText("All Dispatch Dates", 380f, y + 14f, headerPaint)
            canvas.drawText("Complete Audit Stack Trace Log", 550f, y + 14f, headerPaint)
            y += 20f

            records.forEachIndexed { idx, record ->
                if (y > 540f) {
                    pdfDocument.finishPage(page)
                    page = pdfDocument.startPage(pageInfo)
                    canvas = page.canvas
                    y = 40f
                }

                val historyLines = record.historyLog.split("\n").filter { it.isNotBlank() }
                val startY = y

                canvas.drawText(record.fileNo, 40f, y + 15f, paint)
                val statusText = if (record.status == "Entry Deleted") "[DELETED]" else "${record.status} (${record.storageLocation.ifEmpty { "Court " + record.courtNo }})"
                canvas.drawText(statusText.take(28), 160f, y + 15f, paint)
                canvas.drawText(record.dispatchDatesCsv.take(22), 380f, y + 15f, paint)

                var lineY = y + 15f
                historyLines.forEach { line ->
                    if (lineY > 550f) {
                        pdfDocument.finishPage(page)
                        page = pdfDocument.startPage(pageInfo)
                        canvas = page.canvas
                        lineY = 40f
                    }
                    line.chunked(65).forEach { chunk ->
                        canvas.drawText(chunk, 550f, lineY, paint)
                        lineY += 13f
                    }
                }

                y = maxOf(y + 24f, lineY + 4f)
                if (idx % 2 == 1) canvas.drawRect(35f, startY, 973f, y, altRowBgPaint)
                canvas.drawLine(35f, y, 973f, y, borderPaint)
            }

        } else if (isDateCourt) {
            canvas.drawRect(35f, y, 973f, y + 20f, headerBgPaint)
            canvas.drawText("S.No", 40f, y + 14f, headerPaint)
            canvas.drawText("File No.", 90f, y + 14f, headerPaint)
            canvas.drawText("List Type & Serial No.", 240f, y + 14f, headerPaint)
            canvas.drawText("Current Status / Location", 450f, y + 14f, headerPaint)
            canvas.drawText("Remarks / Case Notes", 720f, y + 14f, headerPaint)
            y += 20f

            records.forEachIndexed { index, record ->
                if (y > 550f) {
                    pdfDocument.finishPage(page)
                    page = pdfDocument.startPage(pageInfo)
                    canvas = page.canvas
                    y = 40f
                }
                if (index % 2 == 1) canvas.drawRect(35f, y, 973f, y + 22f, altRowBgPaint)

                canvas.drawText("${index + 1}", 40f, y + 15f, paint)
                canvas.drawText(record.fileNo, 90f, y + 15f, paint)
                canvas.drawText(record.serialNo.ifEmpty { "N/A" }, 240f, y + 15f, paint)
                val locText = if (record.sentToChamber) "Chamber: ${record.judgeName}" else record.storageLocation.ifEmpty { record.status }
                canvas.drawText(locText.take(35), 450f, y + 15f, paint)
                canvas.drawText(record.remarks.take(38).ifEmpty { "-" }, 720f, y + 15f, paint)

                y += 22f
                canvas.drawLine(35f, y, 973f, y, borderPaint)
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
