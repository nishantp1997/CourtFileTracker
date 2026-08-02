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
    private const val MARGIN_LEFT = 35f
    private const val MARGIN_RIGHT = 973f
    private const val PAGE_BOTTOM_LIMIT = 540f

    fun generateMasterReport(context: Context, rawRecords: List<FileRecord>) {
        val validRecords = rawRecords.filter { it.fileNo.isNotBlank() }
        generatePdf(context, "MASTER DATABASE LEDGER WITH COMPLETE AUDIT TRAIL", validRecords, isMaster = true)
    }

    fun generateSingleFileReport(context: Context, record: FileRecord) {
        if (record.fileNo.isBlank()) return
        generatePdf(context, "PARTICULAR CASE FILE HISTORY REPORT - ${record.fileNo}", listOf(record), isSingleFile = true)
    }

    fun generateDateCourtReport(context: Context, date: String, courtNo: String, rawRecords: List<FileRecord>) {
        val validRecords = rawRecords.filter { it.fileNo.isNotBlank() }
        generatePdf(context, "DAILY COURT DISPATCH REPORT - COURT NO. $courtNo ($date)", validRecords, isDateCourt = true)
    }

    private fun wrapText(text: String, paint: Paint, maxWidth: Float): List<String> {
        if (text.isBlank()) return listOf("")
        val words = text.split(" ")
        val lines = mutableListOf<String>()
        var currentLine = StringBuilder()

        for (word in words) {
            val testLine = if (currentLine.isEmpty()) word else "${currentLine} $word"
            if (paint.measureText(testLine) <= maxWidth) {
                currentLine.append(if (currentLine.isEmpty()) word else " $word")
            } else {
                if (currentLine.isNotEmpty()) {
                    lines.add(currentLine.toString())
                    currentLine = StringBuilder()
                }
                if (paint.measureText(word) > maxWidth) {
                    var chunk = ""
                    for (char in word) {
                        if (paint.measureText(chunk + char) <= maxWidth) {
                            chunk += char
                        } else {
                            lines.add(chunk)
                            chunk = char.toString()
                        }
                    }
                    if (chunk.isNotEmpty()) currentLine.append(chunk)
                } else {
                    currentLine.append(word)
                }
            }
        }
        if (currentLine.isNotEmpty()) {
            lines.add(currentLine.toString())
        }
        return lines
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

        val paint = Paint().apply { textSize = 9.0f; color = Color.BLACK; isAntiAlias = true }
        val boldPaint = Paint().apply { textSize = 9.0f; isFakeBoldText = true; color = Color.BLACK; isAntiAlias = true }
        val titlePaint = Paint().apply { textSize = 13.0f; isFakeBoldText = true; color = Color.rgb(20, 40, 80); isAntiAlias = true }
        val headerPaint = Paint().apply { textSize = 9.5f; isFakeBoldText = true; color = Color.WHITE; isAntiAlias = true }
        val headerBgPaint = Paint().apply { color = Color.rgb(40, 60, 100); style = Paint.Style.FILL }
        val altRowBgPaint = Paint().apply { color = Color.rgb(245, 247, 250); style = Paint.Style.FILL }
        val borderPaint = Paint().apply { color = Color.LTGRAY; style = Paint.Style.STROKE; strokeWidth = 0.8f }

        fun drawHeader(currentCanvas: android.graphics.Canvas, currentY: Float): Float {
            var y = currentY
            currentCanvas.drawText("ALLAHABAD HIGH COURT - FILE MOVEMENT & TRACKING SYSTEM", MARGIN_LEFT, y, titlePaint)
            y += 16f
            currentCanvas.drawText("Report Type: $reportTitle | Generated: ${SimpleDateFormat("dd-MM-yy HH:mm", Locale.getDefault()).format(Date())}", MARGIN_LEFT, y, paint)
            y += 10f
            currentCanvas.drawLine(MARGIN_LEFT, y, MARGIN_RIGHT, y, borderPaint)
            return y + 14f
        }

        fun drawTableHeader(currentCanvas: android.graphics.Canvas, currentY: Float): Float {
            currentCanvas.drawRect(MARGIN_LEFT, currentY, MARGIN_RIGHT, currentY + 22f, headerBgPaint)
            if (isMaster) {
                currentCanvas.drawText("File No.", 40f, currentY + 15f, headerPaint)
                currentCanvas.drawText("Active Status & Location", 160f, currentY + 15f, headerPaint)
                currentCanvas.drawText("All Dispatch Dates", 380f, currentY + 15f, headerPaint)
                currentCanvas.drawText("Complete Audit Stack Trace Log", 550f, currentY + 15f, headerPaint)
            } else if (isDateCourt) {
                currentCanvas.drawText("S.No", 40f, currentY + 15f, headerPaint)
                currentCanvas.drawText("File No.", 85f, currentY + 15f, headerPaint)
                currentCanvas.drawText("List Type & Serial No.", 210f, currentY + 15f, headerPaint)
                currentCanvas.drawText("Current Status / Storage Location", 410f, currentY + 15f, headerPaint)
                currentCanvas.drawText("Remarks / Case Notes", 700f, currentY + 15f, headerPaint)
            }
            return currentY + 22f
        }

        var y = drawHeader(canvas, 35f)

        if (isSingleFile && records.isNotEmpty()) {
            val record = records.first()
            canvas.drawText("File Number: ${record.fileNo}", MARGIN_LEFT, y, titlePaint)
            y += 18f
            canvas.drawText("Current Status: ${record.status}", MARGIN_LEFT, y, boldPaint)
            y += 15f
            canvas.drawText("Court No: ${record.courtNo} | Serial/List: ${record.serialNo}", MARGIN_LEFT, y, paint)
            y += 15f
            canvas.drawText("Storage Location: ${record.storageLocation.ifEmpty { "N/A" }}", MARGIN_LEFT, y, paint)
            y += 15f
            canvas.drawText("All Historical Dispatch Dates: ${record.dispatchDatesCsv}", MARGIN_LEFT, y, paint)
            y += 15f
            canvas.drawText("Remarks: ${record.remarks.ifEmpty { "None" }}", MARGIN_LEFT, y, paint)
            y += 18f
            canvas.drawLine(MARGIN_LEFT, y, MARGIN_RIGHT, y, borderPaint)
            y += 18f
            canvas.drawText("Complete Audit Stack Trace Log:", MARGIN_LEFT, y, titlePaint)
            y += 18f

            val traceLines = record.historyLog.split("\n").filter { it.isNotBlank() }
            traceLines.forEach { rawLine ->
                val wrappedLines = wrapText(rawLine, paint, PAGE_WIDTH - 80f)
                wrappedLines.forEach { line ->
                    if (y > PAGE_BOTTOM_LIMIT) {
                        pdfDocument.finishPage(page)
                        page = pdfDocument.startPage(pageInfo)
                        canvas = page.canvas
                        y = drawHeader(canvas, 35f)
                    }
                    canvas.drawText(line, MARGIN_LEFT, y, paint)
                    y += 14f
                }
            }

        } else if (isMaster) {
            y = drawTableHeader(canvas, y)

            records.forEachIndexed { idx, record ->
                val statusText = if (record.status == "Entry Deleted") "[DELETED]" else "${record.status} (${record.storageLocation.ifEmpty { "Court " + record.courtNo }})"
                
                val col1Lines = wrapText(record.fileNo, boldPaint, 110f)
                val col2Lines = wrapText(statusText, paint, 210f)
                val col3Lines = wrapText(record.dispatchDatesCsv, paint, 160f)
                
                val historyRaw = record.historyLog.split("\n").filter { it.isNotBlank() }
                val col4Lines = mutableListOf<String>()
                historyRaw.forEach { hLine ->
                    col4Lines.addAll(wrapText(hLine, paint, 415f))
                }

                val maxLines = maxOf(col1Lines.size, col2Lines.size, col3Lines.size, col4Lines.size, 1)
                val rowHeight = (maxLines * 13f) + 8f

                if (y + rowHeight > PAGE_BOTTOM_LIMIT) {
                    pdfDocument.finishPage(page)
                    page = pdfDocument.startPage(pageInfo)
                    canvas = page.canvas
                    y = drawHeader(canvas, 35f)
                    y = drawTableHeader(canvas, y)
                }

                val startY = y
                if (idx % 2 == 1) {
                    canvas.drawRect(MARGIN_LEFT, startY, MARGIN_RIGHT, startY + rowHeight, altRowBgPaint)
                }

                col1Lines.forEachIndexed { i, line ->
                    canvas.drawText(line, 40f, startY + 12f + (i * 13f), boldPaint)
                }

                col2Lines.forEachIndexed { i, line ->
                    canvas.drawText(line, 160f, startY + 12f + (i * 13f), paint)
                }

                col3Lines.forEachIndexed { i, line ->
                    canvas.drawText(line, 380f, startY + 12f + (i * 13f), paint)
                }

                col4Lines.forEachIndexed { i, line ->
                    canvas.drawText(line, 550f, startY + 12f + (i * 13f), paint)
                }

                y += rowHeight
                canvas.drawLine(MARGIN_LEFT, y, MARGIN_RIGHT, y, borderPaint)
            }

        } else if (isDateCourt) {
            y = drawTableHeader(canvas, y)

            records.forEachIndexed { index, record ->
                val serialText = record.serialNo.ifEmpty { "N/A" }
                val locText = if (record.sentToChamber) "Chamber: ${record.judgeName}" else record.storageLocation.ifEmpty { record.status }
                val remarksText = record.remarks.ifEmpty { "-" }

                val col1Lines = listOf("${index + 1}")
                val col2Lines = wrapText(record.fileNo, boldPaint, 115f)
                val col3Lines = wrapText(serialText, paint, 190f)
                val col4Lines = wrapText(locText, paint, 280f)
                val col5Lines = wrapText(remarksText, paint, 260f)

                val maxLines = maxOf(col2Lines.size, col3Lines.size, col4Lines.size, col5Lines.size, 1)
                val rowHeight = (maxLines * 13f) + 8f

                if (y + rowHeight > PAGE_BOTTOM_LIMIT) {
                    pdfDocument.finishPage(page)
                    page = pdfDocument.startPage(pageInfo)
                    canvas = page.canvas
                    y = drawHeader(canvas, 35f)
                    y = drawTableHeader(canvas, y)
                }

                val startY = y
                if (index % 2 == 1) {
                    canvas.drawRect(MARGIN_LEFT, startY, MARGIN_RIGHT, startY + rowHeight, altRowBgPaint)
                }

                canvas.drawText(col1Lines[0], 40f, startY + 12f, paint)

                col2Lines.forEachIndexed { i, line ->
                    canvas.drawText(line, 85f, startY + 12f + (i * 13f), boldPaint)
                }

                col3Lines.forEachIndexed { i, line ->
                    canvas.drawText(line, 210f, startY + 12f + (i * 13f), paint)
                }

                col4Lines.forEachIndexed { i, line ->
                    canvas.drawText(line, 410f, startY + 12f + (i * 13f), paint)
                }

                col5Lines.forEachIndexed { i, line ->
                    canvas.drawText(line, 700f, startY + 12f + (i * 13f), paint)
                }

                y += rowHeight
                canvas.drawLine(MARGIN_LEFT, y, MARGIN_RIGHT, y, borderPaint)
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
