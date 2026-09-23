package com.court.filetracker

import org.jsoup.Jsoup
import org.jsoup.nodes.Document

data class CauseListRecord(
    val id: Long = 0,
    val causeListDate: String,
    val courtNo: String,
    val listType: String,
    val serialNo: String,
    val caseType: String,
    val fileNo: String,
    val partyName: String,
    val statusTag: String,
    val caseCin: String?,
    val fileSerialNo: String = "",
    val fileYear: String = "2026"
)

object WebCauseListParser {

    fun parseHtmlCauseList(
        htmlContent: String,
        fallbackCourtNo: String,
        fallbackDate: String
    ): List<CauseListRecord> {
        val records = mutableListOf<CauseListRecord>()
        val doc: Document = Jsoup.parse(htmlContent)

        val causeListContainer = doc.selectFirst("#CauseListDiv") ?: doc
        val table = causeListContainer.selectFirst("table.table-causelist")
            ?: causeListContainer.selectFirst("table")
            ?: return emptyList()

        val rows = table.select("tr")

        val containerHeaderText = causeListContainer.select(".card-header, thead, th").text()
        var currentListType = when {
            containerHeaderText.contains("Correction Application List", ignoreCase = true) -> "Correction"
            containerHeaderText.contains("Additional", ignoreCase = true) || containerHeaderText.contains("Unlisted", ignoreCase = true) -> "ACL"
            else -> "DCL"
        }

        var lastMainSerial = "0"
        var withCounter = 1

        val caseRegex = Regex("(?i)([A-Za-z0-9]+)[\\/\\-\\s]*(\\d{1,7})[\\/\\-]+(\\d{4})")

        for (row in rows) {
            val text = row.text().trim()

            if (row.select("th[colspan], td[colspan]").isNotEmpty()) {
                if (text.contains("Correction Application List", ignoreCase = true)) {
                    currentListType = "Correction"
                } else if (text.contains("Additional", ignoreCase = true) || text.contains("Unlisted", ignoreCase = true)) {
                    currentListType = "ACL"
                } else if (text.contains("Combined Cause List", ignoreCase = true) || text.contains("Fresh List", ignoreCase = true) || text.contains("Daily Cause List", ignoreCase = true)) {
                    currentListType = "DCL"
                }
            }

            if (row.select("p.text-dark").isNotEmpty() || 
                text.startsWith("TC No", ignoreCase = true) || 
                text.startsWith("Crime No", ignoreCase = true) ||
                text.startsWith("Details of Cases", ignoreCase = true)
            ) {
                continue
            }

            val cells = row.select("td")
            if (cells.isEmpty()) continue

            // Extract unique CIN from onclick attribute[cite: 1]
            val onclickAttr = row.select("[onclick]").attr("onclick")
            val extractedCin = if (onclickAttr.contains("viewCaseData")) {
                Regex("'([^']+)'").find(onclickAttr)?.groupValues?.get(1) ?: ""
            } else {
                ""
            }

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
                            partyName = cleanParty,
                            caseCin = extractedCin
                        )
                    )
                }
                continue
            }

            // Strip inner HTML tags (e.g., <br><span class="col-black">E-File</span>) to successfully read 5, 73, 75
            val rawSerialHtml = cells[0].html()
            val cleanSerialText = rawSerialHtml.replace(Regex("<[^>]*>"), "").trim()
            val candidateSerial = cleanSerialText.toIntOrNull()

            if (candidateSerial != null) {
                lastMainSerial = candidateSerial.toString()
                withCounter = 1

                val statusTag = if (cells.size >= 5) cells[1].text().trim() else ""
                val caseDetailCell = if (cells.size >= 5) cells[2] else cells[1]
                val partyCell = if (cells.size >= 5) cells[3] else cells[2]

                val caseDetailText = caseDetailCell.text().trim()
                val cleanParty = cleanPartyText(partyCell.text().trim())

                val isCorrectionList = currentListType.equals("Correction", true)
                
                // Explicitly parse Application Cases from Srl 238 onwards (e.g. Listing Application / Stay Vacation)[cite: 2]
                val appTypeMatch = Regex("\\(([^)]+)\\)").find(caseDetailText)
                val appNoMatch = Regex("(\\d+/[\\d]+)").find(caseDetailText)

                val match = caseRegex.find(caseDetailText)

                if (match != null) {
                    val cType = match.groupValues[1].uppercase()
                    val fSerial = match.groupValues[2]
                    val fYear = match.groupValues[3]

                    val finalCaseType = if (appTypeMatch != null && appNoMatch != null) {
                        "${appTypeMatch.groupValues[1]} #${appNoMatch.groupValues[1]} in $cType"
                    } else {
                        cType
                    }

                    records.add(
                        CauseListRecord(
                            causeListDate = fallbackDate,
                            courtNo = fallbackCourtNo,
                            serialNo = lastMainSerial,
                            statusTag = statusTag.ifEmpty { if (isCorrectionList) "Correction" else "" },
                            listType = currentListType,
                            caseType = finalCaseType,
                            fileSerialNo = fSerial,
                            fileYear = fYear,
                            fileNo = "$fSerial/$fYear",
                            partyName = cleanParty,
                            caseCin = extractedCin
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
