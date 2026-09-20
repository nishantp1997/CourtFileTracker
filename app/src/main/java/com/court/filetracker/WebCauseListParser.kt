package com.court.filetracker

import org.jsoup.Jsoup
import org.jsoup.nodes.Document

object WebCauseListParser {

    fun parseHtmlCauseList(
        htmlContent: String,
        fallbackCourtNo: String,
        fallbackDate: String
    ): List<CauseListRecord> {
        val records = mutableListOf<CauseListRecord>()
        val doc: Document = Jsoup.parse(htmlContent)

        // 1. Locate the container or main table
        val table = doc.selectFirst("#CauseListDiv table.table-causelist")
            ?: doc.selectFirst("table.table-causelist")
            ?: doc.selectFirst("table")
            ?: return emptyList()

        val rows = table.select("tr")

        // 2. Identify default cause list category
        var currentListType = when {
            htmlContent.contains("Correction Application List", ignoreCase = true) -> "Correction"[cite: 4, 5]
            htmlContent.contains("Additional", ignoreCase = true) || htmlContent.contains("Unlisted", ignoreCase = true) -> "ACL"
            else -> "DCL"
        }

        var lastMainSerial = "0"
        var withCounter = 1

        val caseRegex = Regex("(?i)([A-Za-z0-9]+)[\\/\\-\\s]*(\\d{1,7})[\\/\\-]+(\\d{4})")

        for (row in rows) {
            val text = row.text().trim()

            // Detect embedded sub-headers within the table
            if (row.select("th[colspan], td[colspan]").isNotEmpty()) {
                if (text.contains("Correction Application List", ignoreCase = true)) {
                    currentListType = "Correction"[cite: 5]
                } else if (text.contains("Additional", ignoreCase = true) || text.contains("Unlisted", ignoreCase = true)) {
                    currentListType = "ACL"
                } else if (text.contains("Combined Cause List", ignoreCase = true) || text.contains("Fresh List", ignoreCase = true)) {
                    currentListType = "DCL"[cite: 4]
                }
            }

            // Skip metadata details rows (TC No, Crime No, earlier filing logs)
            if (row.select("p.text-dark").isNotEmpty() || 
                text.startsWith("TC No", ignoreCase = true) || 
                text.startsWith("Crime No", ignoreCase = true) ||
                text.startsWith("Details of Cases", ignoreCase = true)[cite: 4, 5]
            ) {
                continue
            }

            val cells = row.select("td")
            if (cells.isEmpty()) continue

            // ----------------------------------------------------
            // CASE TYPE 1: Connected Companion Case ("with")
            // HTML pattern: <td colspan="2">with</td> <td>Case</td> <td>Party</td>
            // ----------------------------------------------------
            val isWithRow = cells.any { it.text().trim().equals("with", ignoreCase = true) }
            if (isWithRow) {
                val caseCellText = cells.getOrNull(1)?.text()?.trim() ?: ""
                val rawParty = cells.getOrNull(2)?.text()?.trim() ?: ""
                val cleanParty = cleanPartyText(rawParty)

                val match = caseRegex.find(caseCellText)
                if (match != null) {
                    val cType = match.groupValues[1].uppercase()
                    val fSerial = match.groupValues[2]
                    val fYear = match.groupValues[3]
                    val subSerial = "$lastMainSerial.$withCounter"
                    withCounter++

                    records.add(
                        CauseListRecord(
                            causeListDate = fallbackDate,
                            courtNo = fallbackCourtNo,
                            serialNo = subSerial,
                            statusTag = "With",
                            listType = currentListType,
                            caseType = cType,
                            fileSerialNo = fSerial,
                            fileYear = fYear,
                            fileNo = "$fSerial/$fYear",
                            partyName = cleanParty
                        )
                    )
                }
                continue
            }

            // ----------------------------------------------------
            // CASE TYPE 2: Leading Case or Correction Application Case
            // ----------------------------------------------------
            val firstCellText = cells[0].text().trim()
            val candidateSerial = firstCellText.toIntOrNull()

            if (candidateSerial != null) {
                lastMainSerial = candidateSerial.toString()
                withCounter = 1

                val statusTag = if (cells.size >= 5) cells[1].text().trim() else ""[cite: 4, 5]
                val caseDetailCell = if (cells.size >= 5) cells[2] else cells[1][cite: 4, 5]
                val partyCell = if (cells.size >= 5) cells[3] else cells[2][cite: 4, 5]

                val caseDetailText = caseDetailCell.text().trim()
                val cleanParty = cleanPartyText(partyCell.text().trim())

                // Check for Correction List structure (e.g. 1/2026 in case NA528-37465-2026)
                val isCorrection = caseDetailText.contains("in case", ignoreCase = true) || currentListType == "Correction"[cite: 5]
                val match = caseRegex.find(caseDetailText)

                if (match != null) {
                    val cType = match.groupValues[1].uppercase()
                    val fSerial = match.groupValues[2]
                    val fYear = match.groupValues[3]

                    records.add(
                        CauseListRecord(
                            causeListDate = fallbackDate,
                            courtNo = fallbackCourtNo,
                            serialNo = lastMainSerial,
                            statusTag = statusTag.ifEmpty { if (isCorrection) "Correction" else "" },
                            listType = if (isCorrection) "Correction" else currentListType,
                            caseType = cType,
                            fileSerialNo = fSerial,
                            fileYear = fYear,
                            fileNo = "$fSerial/$fYear",
                            partyName = cleanParty
                        )
                    )
                }
            }
        }

        return records
    }

    private fun cleanPartyText(rawParty: String): String {
        return rawParty
            .replace(Regex("(?i)\\s+vs\\s+"), " VS ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
