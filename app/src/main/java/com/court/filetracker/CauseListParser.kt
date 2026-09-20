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

        var defaultListType = when {
            rawText.contains("Correction Application List", ignoreCase = true) -> "Correction"
            rawText.contains("Additional", ignoreCase = true) || rawText.contains("Unlisted", ignoreCase = true) -> "ACL"
            else -> "DCL"
        }

        // Clean out recurring pagination lines
        val lines = rawText.lines()
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

        // Regex for Case numbers: Handles NA528/11656/2026, NA52811656//2026, A482-18661-2018, CRLA/1594/1983
        val caseRegex = Regex("(?i)\\b([A-Za-z0-9]+)[\\/\\-\\s]*(\\d{1,7})[\\/\\-]+(\\d{4})\\b")
        val correctionAppRegex = Regex("(?i)(\\d+)[\\/\\-](\\d{4}).*?in\\s*case\\s*([A-Za-z0-9]+)[\\-\\s\\/]+(\\d+)[\\-\\s\\/]+(\\d{4})")
        val withConnectedRegex = Regex("(?i)^(\\d+\\.\\d+)\\s+With\\s+([A-Za-z0-9]+)[\\/\\-\\s]*(\\d{1,7})[\\/\\-]+(\\d{4})")

        val statusFlags = setOf("DF", "PO", "LO", "TU", "LAFP", "WC", "AS", "FRESH")

        var idx = 0
        val totalLines = lines.size

        while (idx < totalLines) {
            val line = lines[idx]

            // Dynamically detect section header shifts
            if (line.contains("ADDITIONAL", ignoreCase = true)) defaultListType = "ACL"
            if (line.contains("FRESH LIST", ignoreCase = true) || line.contains("DAILY CAUSE LIST", ignoreCase = true)) defaultListType = "DCL"
            if (line.contains("Correction Application List", ignoreCase = true)) defaultListType = "Correction"

            // 1. Check for Connected Case line: e.g. "88.1 With NA528/21013/2025"
            val withMatch = withConnectedRegex.find(line)
            if (withMatch != null) {
                val subSerial = withMatch.groupValues[1]
                val cType = withMatch.groupValues[2].uppercase()
                val fSerial = withMatch.groupValues[3]
                val fYear = withMatch.groupValues[4]

                // Scan next 6 lines strictly for Party Name (P1 vs P2)
                val party = scanPartyName(lines, idx + 1, minOf(idx + 7, totalLines))

                records.add(
                    CauseListRecord(
                        causeListDate = date,
                        courtNo = courtNo,
                        serialNo = subSerial,
                        statusTag = "With",
                        listType = defaultListType,
                        caseType = cType,
                        fileSerialNo = fSerial,
                        fileYear = fYear,
                        fileNo = "$fSerial/$fYear",
                        partyName = party
                    )
                )
                idx++
                continue
            }

            // 2. Check for Serial Anchor (e.g. "1", "12 DF", "22 PO", "357 WC")
            val tokens = line.split("\\s+".toRegex()).filter { it.isNotBlank() }
            var candidateSerial: String? = null
            var candidateTag = ""
            var tokenOffset = 0

            if (tokens.isNotEmpty()) {
                val first = tokens[0]
                if (first.matches(Regex("^\\d+$")) && !first.contains("/")) {
                    candidateSerial = first
                    tokenOffset = 1
                    if (tokens.size > 1 && statusFlags.contains(tokens[1].uppercase())) {
                        candidateTag = tokens[1].uppercase()
                        tokenOffset = 2
                    }
                } else if (statusFlags.contains(first.uppercase()) && tokens.size > 1 && tokens[1].matches(Regex("^\\d+$"))) {
                    candidateTag = first.uppercase()
                    candidateSerial = tokens[1]
                    tokenOffset = 2
                }
            }

            if (candidateSerial != null) {
                val serial = candidateSerial
                val blockLines = mutableListOf<String>()

                val remainder = tokens.drop(tokenOffset).joinToString(" ").trim()
                if (remainder.isNotBlank()) blockLines.add(remainder)

                var lookahead = idx + 1
                while (lookahead < totalLines) {
                    val nextLine = lines[lookahead]
                    val nextTokens = nextLine.split("\\s+".toRegex()).filter { it.isNotBlank() }

                    // Stop if encountering next leading serial number or companion case
                    val isNextWith = withConnectedRegex.containsMatchIn(nextLine)
                    val isNextSerial = nextTokens.isNotEmpty() && (
                        (nextTokens[0].matches(Regex("^\\d+$")) && !nextLine.contains("/") && !nextLine.contains("Notice")) ||
                        (statusFlags.contains(nextTokens[0].uppercase()) && nextTokens.size > 1 && nextTokens[1].matches(Regex("^\\d+$")))
                    )

                    if (isNextWith || isNextSerial) break
                    blockLines.add(nextLine)
                    lookahead++
                }

                val blockText = blockLines.joinToString("\n")

                // Check for Correction List pattern
                val corrMatch = correctionAppRegex.find(blockText)
                if (corrMatch != null) {
                    val appNo = corrMatch.groupValues[1]
                    val appYear = corrMatch.groupValues[2]
                    val cType = corrMatch.groupValues[3].uppercase()
                    val fSerial = corrMatch.groupValues[4]
                    val fYear = corrMatch.groupValues[5]
                    val party = extractCleanParty(blockLines).ifBlank { "Correction App: $appNo/$appYear" }

                    records.add(
                        CauseListRecord(
                            causeListDate = date,
                            courtNo = courtNo,
                            serialNo = serial,
                            statusTag = candidateTag.ifEmpty { "Correction" },
                            listType = "Correction",
                            caseType = cType,
                            fileSerialNo = fSerial,
                            fileYear = fYear,
                            fileNo = "$fSerial/$fYear",
                            partyName = party
                        )
                    )
                } else {
                    // Standard Main Case
                    var foundType = ""
                    var foundFileSerial = ""
                    var foundFileYear = ""

                    for (bLine in blockLines) {
                        if (bLine.startsWith("Notice No", ignoreCase = true) ||
                            bLine.startsWith("TC No", ignoreCase = true) ||
                            bLine.startsWith("Crime No", ignoreCase = true)
                        ) continue

                        val m = caseRegex.find(bLine)
                        if (m != null) {
                            val ct = m.groupValues[1]
                            if (ct.length >= 2 && ct.any { it.isLetter() }) {
                                foundType = ct.uppercase()
                                foundFileSerial = m.groupValues[2]
                                foundFileYear = m.groupValues[3]
                                break
                            }
                        }
                    }

                    if (foundFileSerial.isNotBlank() && foundFileYear.isNotBlank()) {
                        val party = extractCleanParty(blockLines)

                        records.add(
                            CauseListRecord(
                                causeListDate = date,
                                courtNo = courtNo,
                                serialNo = serial,
                                statusTag = candidateTag,
                                listType = defaultListType,
                                caseType = foundType,
                                fileSerialNo = foundFileSerial,
                                fileYear = foundFileYear,
                                fileNo = "$foundFileSerial/$foundFileYear",
                                partyName = party
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

    private fun scanPartyName(lines: List<String>, start: Int, end: Int): String {
        val sub = lines.subList(start, end)
        return extractCleanParty(sub)
    }

    private fun extractCleanParty(lines: List<String>): String {
        val vsIndex = lines.indexOfFirst {
            it.trim().equals("VS", ignoreCase = true) ||
            it.contains(" VS ", ignoreCase = true) ||
            it.contains(" vs ", ignoreCase = true)
        }

        if (vsIndex != -1) {
            val targetLine = lines[vsIndex]
            if (targetLine.equals("VS", ignoreCase = true) || targetLine.equals("vs", ignoreCase = true)) {
                val p1 = lines.subList(0, vsIndex).filter { isCleanPartyToken(it) }.takeLast(2).joinToString(" ").trim()
                val p2 = lines.subList(vsIndex + 1, lines.size).filter { isCleanPartyToken(it) }.take(2).joinToString(" ").trim()
                return if (p1.isNotBlank()) "$p1 VS $p2" else "VS $p2"
            } else {
                val parts = targetLine.split(Regex("(?i)\\s+vs\\s+"))
                if (parts.size >= 2) {
                    val p1 = parts[0].trim()
                    val p2 = parts[1].trim()
                    return "$p1 VS $p2"
                }
            }
        }
        return ""
    }

    private fun isCleanPartyToken(line: String): Boolean {
        val l = line.trim().lowercase()
        if (l.isBlank()) return false
        if (l.startsWith("notice no") || l.startsWith("tc no") || l.startsWith("crime no")) return false
        if (l.startsWith("in case") || l.startsWith("case decided")) return false
        if (l.startsWith("details of cases") || l.startsWith("-details")) return false
        if (l.contains("advocate") || l.contains("g.a.") || l.contains("a.g.a.")) return false
        if (l.contains("police st.") || l.contains("district-")) return false
        return true
    }
}
