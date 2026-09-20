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

        // 1. Identify Default Document List Type
        val defaultListType = when {
            rawText.contains("Correction Application List", ignoreCase = true) -> "Correction"
            rawText.contains("Additional", ignoreCase = true) || rawText.contains("Unlisted", ignoreCase = true) -> "ACL"
            else -> "DCL"
        }

        // 2. Clean out recurring headers, pagination footers, and counsel column labels
        val cleanedLines = rawText.lines()
            .map { it.trim() }
            .filter { line ->
                !(line.startsWith("Page ") && line.contains("of")) &&
                !(line.startsWith("Court No-") && line.length < 20) &&
                !line.contains("HON'BLE JUSTICE", ignoreCase = true) &&
                !line.contains("Petitioner's Counsel", ignoreCase = true) &&
                !line.contains("Respondent's Counsel", ignoreCase = true) &&
                !line.contains("Petitioner Counsel", ignoreCase = true) &&
                !line.contains("Respondent Counsel", ignoreCase = true)
            }

        // Universal Case Number Matcher:
        // Handles: NA528/11656/2026, NA52811656//2026, A482-18661-2018, CRLA/1594/1983, A227/3728/2021
        val caseRegex = Regex("(?i)\\b([A-Za-z0-9]+)[\\/\\-\\s]*(\\d{1,7})[\\/\\-]+(\\d{4})\\b")
        val correctionAppRegex = Regex("(?i)(\\d+)[\\/\\-](\\d{4}).*?in\\s*case\\s*([A-Za-z0-9]+)[\\-\\s\\/]+(\\d+)[\\-\\s\\/]+(\\d{4})")
        val connectedWithRegex = Regex("(?i)(\\d+\\.\\d+)?\\s*With\\s+([A-Za-z0-9\\/\\-\\s]+)")

        val statusFlags = setOf("DF", "PO", "LO", "TU", "LAFP", "WC", "AS", "FRESH")

        var idx = 0
        val totalLines = cleanedLines.size

        while (idx < totalLines) {
            val line = cleanedLines[idx]
            val tokens = line.split("\\s+".toRegex()).filter { it.isNotBlank() }
            var candidateSerial: String? = null
            var tokenOffset = 0

            // Serial anchors can be anywhere from 1 to 2000+, with or without a status flag prefix
            if (tokens.isNotEmpty()) {
                val first = tokens[0]
                if (first.matches(Regex("^\\d+(\\.\\d+)?$")) && !first.contains("/")) {
                    candidateSerial = first
                    tokenOffset = 1
                } else if (statusFlags.contains(first.uppercase()) && tokens.size > 1 && tokens[1].matches(Regex("^\\d+(\\.\\d+)?$"))) {
                    candidateSerial = tokens[1]
                    tokenOffset = 2
                }
            }

            if (candidateSerial != null) {
                val serial = candidateSerial
                val blockLines = mutableListOf<String>()

                val remainingLine = tokens.drop(tokenOffset).joinToString(" ").trim()
                if (remainingLine.isNotBlank()) blockLines.add(remainingLine)

                // Accumulate all content until the next serial anchor is detected
                var lookahead = idx + 1
                while (lookahead < totalLines) {
                    val nextLine = cleanedLines[lookahead]
                    val nextTokens = nextLine.split("\\s+".toRegex()).filter { it.isNotBlank() }
                    val isNextSerial = nextTokens.isNotEmpty() && (
                        (nextTokens[0].matches(Regex("^\\d+(\\.\\d+)?$")) && !nextLine.contains("/") && !nextLine.contains("Notice")) ||
                        (statusFlags.contains(nextTokens[0].uppercase()) && nextTokens.size > 1 && nextTokens[1].matches(Regex("^\\d+(\\.\\d+)?$")))
                    )
                    if (isNextSerial) break
                    blockLines.add(nextLine)
                    lookahead++
                }

                val blockText = blockLines.joinToString("\n")

                // Mode 1: Correction Application List (e.g. 9/2026 in case A482-18661-2018)
                val corrMatch = correctionAppRegex.find(blockText)
                if (corrMatch != null) {
                    val appNo = corrMatch.groupValues[1]
                    val appYear = corrMatch.groupValues[2]
                    val caseType = corrMatch.groupValues[3].uppercase()
                    val fileSerial = corrMatch.groupValues[4]
                    val fileYear = corrMatch.groupValues[5]

                    var party = extractPartyFromBlock(blockLines)
                    if (party.isBlank()) party = "Correction App: $appNo/$appYear"

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
                            partyName = party.take(250),
                            connectedCases = ""
                        )
                    )
                } else {
                    // Mode 2: Standard Daily (DCL) or Additional (ACL) Case
                    var foundCaseType = ""
                    var foundFileSerial = ""
                    var foundFileYear = ""

                    for (bLine in blockLines) {
                        if (bLine.startsWith("Notice No", ignoreCase = true) ||
                            bLine.startsWith("TC No", ignoreCase = true) ||
                            bLine.startsWith("Crime No", ignoreCase = true)
                        ) continue

                        val cMatch = caseRegex.find(bLine)
                        if (cMatch != null) {
                            val ct = cMatch.groupValues[1]
                            if (ct.length >= 2 && ct.any { it.isLetter() }) {
                                foundCaseType = ct.uppercase()
                                foundFileSerial = cMatch.groupValues[2]
                                foundFileYear = cMatch.groupValues[3]
                                break
                            }
                        }
                    }

                    if (foundFileSerial.isNotBlank() && foundFileYear.isNotBlank()) {
                        // Extract Connected Cases ("With" companion files)
                        val connectedList = mutableListOf<String>()
                        connectedWithRegex.findAll(blockText).forEach { wm ->
                            val withCase = wm.groupValues[2].trim()
                            val subSerial = wm.groupValues[1].ifBlank { "" }
                            connectedList.add(if (subSerial.isNotBlank()) "$subSerial With $withCase" else "With $withCase")
                        }

                        val party = extractPartyFromBlock(blockLines)

                        records.add(
                            CauseListRecord(
                                causeListDate = date,
                                courtNo = courtNo,
                                serialNo = serial,
                                listType = defaultListType,
                                caseType = foundCaseType,
                                fileSerialNo = foundFileSerial,
                                fileYear = foundFileYear,
                                fileNo = "$foundFileSerial/$foundFileYear",
                                partyName = party.take(250),
                                connectedCases = connectedList.joinToString(", ")
                            )
                        )
                    }
                }

                idx = lookahead
                continue
            }
            idx++
        }

        return records
    }

    private fun extractPartyFromBlock(lines: List<String>): String {
        // Matches case-insensitive standalone "VS" or "vs"
        val vsIndex = lines.indexOfFirst { 
            it.trim().equals("VS", ignoreCase = true) || 
            it.contains(" VS ", ignoreCase = true) || 
            it.contains(" vs ", ignoreCase = true) 
        }

        if (vsIndex != -1) {
            val targetLine = lines[vsIndex]
            if (targetLine.equals("VS", ignoreCase = true) || targetLine.equals("vs", ignoreCase = true)) {
                val p1 = lines.subList(0, vsIndex).filter { isValidPartyLine(it) }.takeLast(2).joinToString(" ").trim()
                val p2 = lines.subList(vsIndex + 1, lines.size).filter { isValidPartyLine(it) }.take(2).joinToString(" ").trim()
                return if (p1.isNotBlank()) "$p1 VS $p2" else "VS $p2"
            } else {
                val splitRegex = Regex("(?i)\\s+vs\\s+")
                val parts = targetLine.split(splitRegex)
                if (parts.size >= 2) {
                    val p1 = parts[0].trim()
                    val p2 = parts[1].trim()
                    return "$p1 VS $p2"
                }
            }
        }
        return ""
    }

    private fun isValidPartyLine(line: String): Boolean {
        val l = line.trim().lowercase()
        if (l.isBlank()) return false
        if (l.startsWith("notice no") || l.startsWith("tc no") || l.startsWith("crime no")) return false
        if (l.startsWith("in case") || l.startsWith("case decided")) return false
        if (l.startsWith("details of cases") || l.startsWith("-details")) return false
        if (l.contains("advocate") || l.contains("g.a.") || l.contains("a.g.a.")) return false
        return true
    }
}
