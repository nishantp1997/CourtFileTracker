package com.court.filetracker

import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.canvas.parser.PdfCanvasProcessor
import com.itextpdf.kernel.pdf.canvas.parser.PdfTextExtractor
import com.itextpdf.kernel.pdf.canvas.parser.listener.LocationTextExtractionStrategy
import java.io.InputStream

object CauseListParser {

    fun parseCauseListPdf(
        inputStream: InputStream,
        targetCourtNo: String,
        targetDate: String
    ): List<CauseListRecord> {
        val fullTextBuilder = StringBuilder()

        try {
            val pdfReader = PdfReader(inputStream)
            val pdfDoc = PdfDocument(pdfReader)
            val totalPages = pdfDoc.numberOfPages

            for (i in 1..totalPages) {
                val page = pdfDoc.getPage(i)
                try {
                    val strategy = LocationTextExtractionStrategy()
                    val processor = PdfCanvasProcessor(strategy)
                    processor.processPageContent(page)
                    val text = strategy.resultantText
                    if (text.isNotBlank()) {
                        fullTextBuilder.append(text).append("\n")
                    } else {
                        fullTextBuilder.append(PdfTextExtractor.getTextFromPage(page)).append("\n")
                    }
                } catch (e: Exception) {
                    fullTextBuilder.append(PdfTextExtractor.getTextFromPage(page)).append("\n")
                }
            }

            pdfDoc.close()
            pdfReader.close()
        } catch (e: Exception) {
            e.printStackTrace()
            return emptyList()
        }

        return extractRecordsFromRawText(fullTextBuilder.toString(), targetCourtNo, targetDate)
    }

    private fun extractRecordsFromRawText(
        rawText: String,
        courtNo: String,
        date: String
    ): List<CauseListRecord> {
        val records = mutableListOf<CauseListRecord>()
        
        val defaultListType = when {
            rawText.contains("Correction Application List", ignoreCase = true) -> "Correction"
            rawText.contains("Additional", ignoreCase = true) || rawText.contains("Unlisted", ignoreCase = true) -> "ACL"
            else -> "DCL"
        }

        val lines = rawText.lines().map { it.trim() }
        var currentSerial = ""
        var currentListType = defaultListType
        var currentCaseType = ""
        var currentFileSerial = ""
        var currentFileYear = ""
        var currentParty = ""
        var currentConnected = mutableListOf<String>()
        var inPartyCapture = false

        fun flushCurrent() {
            if (currentSerial.isNotBlank() && currentFileSerial.isNotBlank() && currentFileYear.isNotBlank()) {
                records.add(
                    CauseListRecord(
                        causeListDate = date,
                        courtNo = courtNo,
                        serialNo = currentSerial,
                        listType = currentListType,
                        caseType = currentCaseType,
                        fileSerialNo = currentFileSerial,
                        fileYear = currentFileYear,
                        fileNo = "$currentFileSerial/$currentFileYear",
                        partyName = currentParty.trim().take(250),
                        connectedCases = currentConnected.joinToString(", ")
                    )
                )
            }
            currentSerial = ""
            currentListType = defaultListType
            currentCaseType = ""
            currentFileSerial = ""
            currentFileYear = ""
            currentParty = ""
            currentConnected = mutableListOf()
            inPartyCapture = false
        }

        val serialRegex = Regex("^(\\d+(\\.\\d+)?)\\s*(LO|PO|TU|LAFP|DF|WC)?$")
        val caseNumberRegex = Regex("([A-Za-z0-9]+)[\\/\\-]([0-9]+)[\\/\\-]([0-9]{4})")
        val connectedWithRegex = Regex("(\\d+\\.\\d+)?\\s*With\\s+([A-Za-z0-9\\/\\-]+)")
        val correctionAppRegex = Regex("(\\d+\\/\\d{4}).*?in case\\s*([A-Za-z0-9]+)[\\-\\s](\\d+)[\\-\\s](\\d{4})")

        for (idx in lines.indices) {
            val line = lines[idx]
            if (line.isBlank() || line.startsWith("Page ") || line.startsWith("Court No-") || line.contains("HON'BLE JUSTICE")) {
                continue
            }

            // Update List Section dynamically
            if (line.contains("ADDITIONAL", ignoreCase = true)) currentListType = "ACL"
            if (line.contains("FRESH LIST", ignoreCase = true) || line.contains("DAILY CAUSE LIST", ignoreCase = true)) currentListType = "DCL"
            if (line.contains("Correction Application List", ignoreCase = true)) currentListType = "Correction"

            // Check if line is a new Serial Number (e.g. "1", "229 PO", "238")
            val serialMatch = serialRegex.find(line)
            if (serialMatch != null && !line.contains("/")) {
                val candidateSerial = serialMatch.groupValues[1]
                if (candidateSerial.toIntOrNull() != null || candidateSerial.toDoubleOrNull() != null) {
                    flushCurrent()
                    currentSerial = candidateSerial
                    continue
                }
            }

            // Check for Correction Case Pattern
            val corrMatch = correctionAppRegex.find(line)
            if (corrMatch != null) {
                val appNo = corrMatch.groupValues[1]
                currentCaseType = corrMatch.groupValues[2]
                currentFileSerial = corrMatch.groupValues[3]
                currentFileYear = corrMatch.groupValues[4]
                currentListType = "Correction"
                currentParty = "Correction App: $appNo"
                continue
            }

            // Check for Connected Case
            val withMatch = connectedWithRegex.find(line)
            if (withMatch != null) {
                currentConnected.add(line)
                continue
            }

            // Check for Main Case Number (e.g. NA528/30174/2026 or CRLA/1886/1988)
            val caseMatch = caseNumberRegex.find(line)
            if (caseMatch != null && currentFileSerial.isBlank()) {
                currentCaseType = caseMatch.groupValues[1]
                currentFileSerial = caseMatch.groupValues[2]
                currentFileYear = caseMatch.groupValues[3]
                inPartyCapture = true
                continue
            }

            // Capture Party names (lines following the case number before notice/crime metadata)
            if (inPartyCapture) {
                if (line.startsWith("Notice No") || line.startsWith("TC No") || line.startsWith("Crime No")) {
                    inPartyCapture = false
                } else {
                    if (currentParty.length < 200) {
                        currentParty = if (currentParty.isBlank()) line else "$currentParty $line"
                    }
                }
            }
        }
        flushCurrent()

        return records
    }
}
