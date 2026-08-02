package com.court.filetracker

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
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

    fun generateAndShareReport(context: Context, reportTitle: String, records: List<FileRecord>, isMasterReport: Boolean = false) {
        val pdfDocument = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create() // A4 Size
        var page = pdfDocument.startPage(pageInfo)
        var canvas = page.canvas

        val paint = Paint().apply {
            textSize = 10f
            color = Color.BLACK
        }
        val titlePaint = Paint().apply {
            textSize = 14f
            isFakeBoldText = true
            color = Color.BLACK
        }
        val headerPaint = Paint().apply {
            textSize = 10f
            isFakeBoldText = true
            color = Color.DKGRAY
        }

        var y = 40f
        canvas.drawText("ALLAHABAD HIGH COURT - FILE TRACKER REPORT", 40f, y, titlePaint)
        y += 20f
        canvas.drawText("Report: $reportTitle | Generated: ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())}", 40f, y, paint)
        y += 20f
        canvas.drawLine(40f, y, 555f, y, paint)
        y += 15f

        if (isMasterReport) {
            canvas.drawText("File No.", 40f, y, headerPaint)
            canvas.drawText("Status / Location", 140f, y, headerPaint)
            canvas.drawText("Audit Stack Trace History", 300f, y, headerPaint)
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
                canvas.drawText(statusText.take(25), 140f, y, paint)

                val historyLines = record.historyLog.split("\n").filter { it.isNotBlank() }
                var lineY = y
                historyLines.forEach { line ->
                    if (lineY > 780f) {
                        pdfDocument.finishPage(page)
                        page = pdfDocument.startPage(pageInfo)
                        canvas = page.canvas
                        lineY = 40f
                    }
                    canvas.drawText(line.take(45), 300f, lineY, paint)
                    lineY += 12f
                }
                y = maxOf(y + 20f, lineY + 5f)
                canvas.drawLine(40f, y - 5f, 555f, y - 5f, Paint().apply { color = Color.LTGRAY })
            }
        } else {
            canvas.drawText("S.No", 40f, y, headerPaint)
            canvas.drawText("File No.", 80f, y, headerPaint)
            canvas.drawText("Court No.", 180f, y, headerPaint)
            canvas.drawText("Status / Location", 260f, y, headerPaint)
            canvas.drawText("Remarks", 420f, y, headerPaint)
            y += 15f

            records.forEachIndexed { index, record ->
                if (y > 780f) {
                    pdfDocument.finishPage(page)
                    page = pdfDocument.startPage(pageInfo)
                    canvas = page.canvas
                    y = 40f
                }
                canvas.drawText("${index + 1}", 40f, y, paint)
                canvas.drawText(record.fileNo, 80f, y, paint)
                canvas.drawText(record.courtNo, 180f, y, paint)
                val locText = if (record.sentToChamber) "Chamber: ${record.judgeName}" else record.storageLocation.ifEmpty { record.status }
                canvas.drawText(locText.take(22), 260f, y, paint)
                canvas.drawText(record.remarks.take(20), 420f, y, paint)
                y += 18f
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

