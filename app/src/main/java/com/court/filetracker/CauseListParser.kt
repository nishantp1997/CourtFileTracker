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
            rawText.contains("Additional", ignoreCase = true) -> "ACL"
            else -> "DCL"
        }

        // Standard Case Matcher: e.g. NA528/30174/2026, A482/18661/2018, CRLA/1886/1988
        val caseRegex = Regex("([A-Za-z0-9]+)[\\/\\-](\\d{1,7})[\\/\\-](\\d{4})")

        // Matches lines starting with serial number: e.g. "1 LO", "238 PO", "238.1 With"
        val serialHeaderRegex = Regex("(?m)^\\s*(\\d+(\\.\\d+)?)\\s+(?:(LO|PO|TU|LAFP|DF|WC)\\s+)?(?:With\\s+)?([A-Za-z0-9]+[\\/\\-]\\d+[\\/\\-]\\d+)")
        val matches = serialHeaderRegex.findAll(rawText).toList()

        if (matches.isNotEmpty()) {
            for (i in matches.indices) {
                val currentMatch = matches[i]
                val serial = currentMatch.groupValues[1]
                val rawCaseStr = currentMatch.groupValues[4]

                val blockStart = currentMatch.range.first
                val blockEnd = if (i < matches.size - 1) matches[i + 1].range.first else rawText.length
                val blockText = rawText.substring(blockStart, blockEnd)

                val caseMatch = caseRegex.find(rawCaseStr)
                if (caseMatch != null) {
                    val caseType = caseMatch.groupValues[1]
                    val fileSerial = caseMatch.groupValues[2]
                    val fileYear = caseMatch.groupValues[3]
                    val formattedFileNo = "$fileSerial/$fileYear"

                    // Extract Party Names (Lines between Case Details and VS)
                    var party = ""
                    val vsMatch = Regex("(?i)\\bVS\\b").find(blockText)
                    if (vsMatch != null) {
                        val beforeVs = blockText.substring(0, vsMatch.range.first).lines()
                            .filter { it.isNotBlank() && !it.contains(serial) && !it.contains("Notice") }
                        val afterVs = blockText.substring(vsMatch.range.last).lines()
                            .filter { it.isNotBlank() && !it.contains("Crime") && !it.contains("TC No") }
                        val p1 = beforeVs.takeLast(2).joinToString(" ").trim()
                        val p2 = afterVs.take(2).joinToString(" ").trim()
                        party = if (p1.isNotBlank()) "$p1 VS $p2" else "VS $p2"
                    }

                    // Extract Connected Cases
                    val connectedCases = mutableListOf<String>()
                    val withMatches = Regex("(?m)^\\s*(\\d+\\.\\d+)\\s+With\\s+([A-Za-z0-9\\/\\-]+)").findAll(blockText)
                    for (wm in withMatches) {
                        connectedCases.add("${wm.groupValues[1]} With ${wm.groupValues[2]}")
                    }

                    records.add(
                        CauseListRecord(
                            causeListDate = date,
                            courtNo = courtNo,
                            serialNo = serial,
                            listType = defaultListType,
                            caseType = caseType,
                            fileSerialNo = fileSerial,
                            fileYear = fileYear,
                            fileNo = formattedFileNo,
                            partyName = party.take(250),
                            connectedCases = connectedCases.joinToString(", ")
                        )
                    )
                }
            }
        } else {
            // Fallback parser for Correction Application Lists
            val correctionRegex = Regex("(?m)^\\s*(\\d+)\\s*.*?(\\d+\\/\\d{4}).*?in case\\s*([A-Za-z0-9]+)[\\-\\s](\\d+)[\\-\\s](\\d{4})")
            val corrMatches = correctionRegex.findAll(rawText).toList()

            for (m in corrMatches) {
                val serial = m.groupValues[1]
                val appNo = m.groupValues[2]
                val caseType = m.groupValues[3]
                val fileSerial = m.groupValues[4]
                val fileYear = m.groupValues[5]

                records.add(
                    CauseListRecord(
                        causeListDate = date,
                        courtNo = courtNo,
                        serialNo = serial,
                        listType = "Correction",
                        caseType = caseType,
                        fileSerialNo = fileSerial,
                        fileYear = fileYear,
                        fileNo = "$fileSerial/$fileYear",
                        partyName = "Correction App: $appNo"
                    )
                )
            }
        }

        return records
    }
}
