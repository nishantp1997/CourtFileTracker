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
        generatePdf(context, "DAILY COURT DISPATCH REPORT - COURT NO. $courtNo ($date)", validRecords, dateContext = date, isDateCourt = true)
    }

    // Dynamic Multi-Line Text Wrapper
    private fun wrapText(text: String, paint: Paint, maxWidth: Float): List<String> {
        if (text.isBlank()) return emptyList()
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
        return lines.ifEmpty { listOf("") }
    }

    // Helper: Extracts historical serial number logged on targetDate
    private fun getHistoricalSerialNo(record: FileRecord, targetDate: String): String {
        val logLines = record.historyLog.split("\n")
        val dateLine = logLines.lastOrNull { it.contains("[$targetDate]") && it.contains("Serial:") }
        if (dateLine != null) {
            val match = Regex("Serial:\\s*([^|]+)").find(dateLine)
            if (match != null) return match.groupValues[1].trim()
        }
        return record.serialNo.ifEmpty { "N/A" }
    }

    private fun generatePdf(
        context: Context,
        reportTitle: String,
        records: List<FileRecord>,
        dateContext: String = "",
        isMaster: Boolean = false,
        isSingleFile: Boolean = false,
        isDateCourt: Boolean = false
    ) {
        if (records.isEmpty() && !isSingleFile) return

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

        var y = drawHeader(canvas, 35f)

        if (isSingleFile && records.isNotEmpty()) {
            val record = records.first()
            canvas.drawText("File Number: ${record.fileNo}", MARGIN_LEFT, y, titlePaint)
            y += 18f
            canvas.drawText("Current Status: ${record.status}", MARGIN_LEFT, y, boldPaint)
            y += 15f
            if (record.courtNo.isNotBlank() && record.courtNo != "N/A") {
                canvas.drawText("Court No: ${record.courtNo} | Serial/List: ${record.serialNo}", MARGIN_LEFT, y, paint)
                y += 15f
            }
            if (record.storageLocation.isNotBlank()) {
                canvas.drawText("Storage Location: ${record.storageLocation}", MARGIN_LEFT, y, paint)
                y += 15f
            }
            if (record.dispatchDatesCsv.isNotBlank()) {
                canvas.drawText("All Historical Dispatch Dates: ${record.dispatchDatesCsv}", MARGIN_LEFT, y, paint)
                y += 15f
            }
            if (record.remarks.isNotBlank()) {
                canvas.drawText("Remarks: ${record.remarks}", MARGIN_LEFT, y, paint)
                y += 15f
            }
            y += 5f
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
            val hasStatus = records.any { (if (it.status == "Entry Deleted") "[DELETED]" else "${it.status} (${it.storageLocation.ifEmpty { "Court " + it.courtNo }})").isNotBlank() }
            val hasDates = records.any { it.dispatchDatesCsv.isNotBlank() }
            val hasHistory = records.any { it.historyLog.isNotBlank() }

            val totalUsableWidth = MARGIN_RIGHT - MARGIN_LEFT
            val flexWidth = totalUsableWidth - 120f
            var activeColsCount = 0
            if (hasStatus) activeColsCount++
            if (hasDates) activeColsCount++
            if (hasHistory) activeColsCount++

            val perColWidth = if (activeColsCount > 0) flexWidth / activeColsCount else flexWidth

            var statusX = 0f
            var datesX = 0f
            var historyX = 0f
            var currX = 40f + 120f

            if (hasStatus) { statusX = currX; currX += perColWidth }
            if (hasDates) { datesX = currX; currX += perColWidth }
            if (hasHistory) { historyX = currX }

            fun drawMasterTableHeader(currentCanvas: android.graphics.Canvas, currentY: Float): Float {
                currentCanvas.drawRect(MARGIN_LEFT, currentY, MARGIN_RIGHT, currentY + 22f, headerBgPaint)
                currentCanvas.drawText("File No.", 40f, currentY + 15f, headerPaint)
                if (hasStatus) currentCanvas.drawText("Active Status & Location", statusX, currentY + 15f, headerPaint)
                if (hasDates) currentCanvas.drawText("All Dispatch Dates", datesX, currentY + 15f, headerPaint)
                if (hasHistory) currentCanvas.drawText("Complete Audit Stack Trace Log", historyX, currentY + 15f, headerPaint)
                return currentY + 22f
            }

            y = drawMasterTableHeader(canvas, y)

            records.forEachIndexed { idx, record ->
                val statusText = if (record.status == "Entry Deleted") "[DELETED]" else "${record.status} (${record.storageLocation.ifEmpty { "Court " + record.courtNo }})"

                val col1Lines = wrapText(record.fileNo, boldPaint, 110f)
                val col2Lines = if (hasStatus) wrapText(statusText, paint, perColWidth - 15f) else emptyList()
                val col3Lines = if (hasDates) wrapText(record.dispatchDatesCsv, paint, perColWidth - 15f) else emptyList()

                val col4Lines = mutableListOf<String>()
                if (hasHistory) {
                    val historyRaw = record.historyLog.split("\n").filter { it.isNotBlank() }
                    historyRaw.forEach { hLine ->
                        col4Lines.addAll(wrapText(hLine, paint, perColWidth - 15f))
                    }
                }

                val maxLines = maxOf(col1Lines.size, col2Lines.size, col3Lines.size, col4Lines.size, 1)
                val rowHeight = (maxLines * 13f) + 8f

                if (y + rowHeight > PAGE_BOTTOM_LIMIT) {
                    pdfDocument.finishPage(page)
                    page = pdfDocument.startPage(pageInfo)
                    canvas = page.canvas
                    y = drawHeader(canvas, 35f)
                    y = drawMasterTableHeader(canvas, y)
                }

                val startY = y
                if (idx % 2 == 1) {
                    canvas.drawRect(MARGIN_LEFT, startY, MARGIN_RIGHT, startY + rowHeight, altRowBgPaint)
                }

                col1Lines.forEachIndexed { i, line -> canvas.drawText(line, 40f, startY + 12f + (i * 13f), boldPaint) }
                if (hasStatus) col2Lines.forEachIndexed { i, line -> canvas.drawText(line, statusX, startY + 12f + (i * 13f), paint) }
                if (hasDates) col3Lines.forEachIndexed { i, line -> canvas.drawText(line, datesX, startY + 12f + (i * 13f), paint) }
                if (hasHistory) col4Lines.forEachIndexed { i, line -> canvas.drawText(line, historyX, startY + 12f + (i * 13f), paint) }

                y += rowHeight
                canvas.drawLine(MARGIN_LEFT, y, MARGIN_RIGHT, y, borderPaint)
            }

        } else if (isDateCourt) {
            val hasSerial = true
            val hasLoc = true
            val hasRemarks = records.any { it.remarks.isNotBlank() }

            val fixedWidth = 45f + 120f
            val availableWidth = (MARGIN_RIGHT - MARGIN_LEFT) - fixedWidth

            var activeDynamicCols = 2 // List Serial & Location always shown
            if (hasRemarks) activeDynamicCols++

            val dynamicColWidth = availableWidth / activeDynamicCols

            var serialX = MARGIN_LEFT + fixedWidth
            var locX = serialX + dynamicColWidth
            var remarksX = locX + dynamicColWidth

            fun drawDateCourtTableHeader(currentCanvas: android.graphics.Canvas, currentY: Float): Float {
                currentCanvas.drawRect(MARGIN_LEFT, currentY, MARGIN_RIGHT, currentY + 22f, headerBgPaint)
                currentCanvas.drawText("S.No", 40f, currentY + 15f, headerPaint)
                currentCanvas.drawText("File No.", 85f, currentY + 15f, headerPaint)
                currentCanvas.drawText("List Type & Serial No.", serialX, currentY + 15f, headerPaint)
                currentCanvas.drawText("Status / Storage Location", locX, currentY + 15f, headerPaint)
                if (hasRemarks) currentCanvas.drawText("Remarks / Case Notes", remarksX, currentY + 15f, headerPaint)
                return currentY + 22f
            }

            y = drawDateCourtTableHeader(canvas, y)

            records.forEachIndexed { index, record ->
                val serialText = getHistoricalSerialNo(record, dateContext)
                val locText = if (record.sentToChamber) "Chamber: ${record.judgeName}" else if (record.status == "Entry Deleted") "[DELETED]" else record.storageLocation.ifEmpty { record.status }
                val remarksText = record.remarks

                val col1Lines = listOf("${index + 1}")
                val col2Lines = wrapText(record.fileNo, boldPaint, 110f)
                val col3Lines = wrapText(serialText, paint, dynamicColWidth - 15f)
                val col4Lines = wrapText(locText, paint, dynamicColWidth - 15f)
                val col5Lines = if (hasRemarks) wrapText(remarksText, paint, dynamicColWidth - 15f) else emptyList()

                val maxLines = maxOf(col2Lines.size, col3Lines.size, col4Lines.size, col5Lines.size, 1)
                val rowHeight = (maxLines * 13f) + 8f

                if (y + rowHeight > PAGE_BOTTOM_LIMIT) {
                    pdfDocument.finishPage(page)
                    page = pdfDocument.startPage(pageInfo)
                    canvas = page.canvas
                    y = drawHeader(canvas, 35f)
                    y = drawDateCourtTableHeader(canvas, y)
                }

                val startY = y
                if (index % 2 == 1) {
                    canvas.drawRect(MARGIN_LEFT, startY, MARGIN_RIGHT, startY + rowHeight, altRowBgPaint)
                }

                canvas.drawText(col1Lines[0], 40f, startY + 12f, paint)
                col2Lines.forEachIndexed { i, line -> canvas.drawText(line, 85f, startY + 12f + (i * 13f), boldPaint) }
                col3Lines.forEachIndexed { i, line -> canvas.drawText(line, serialX, startY + 12f + (i * 13f), paint) }
                col4Lines.forEachIndexed { i, line -> canvas.drawText(line, locX, startY + 12f + (i * 13f), paint) }
                if (hasRemarks) col5Lines.forEachIndexed { i, line -> canvas.drawText(line, remarksX, startY + 12f + (i * 13f), paint) }

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
