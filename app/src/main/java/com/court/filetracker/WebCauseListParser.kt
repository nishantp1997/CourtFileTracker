package com.court.filetracker

import org.jsoup.Jsoup
import org.jsoup.nodes.Element

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
    fun parseHtmlCauseList(html: String, courtNo: String, causeListDate: String): List<CauseListRecord> {
        val records = mutableListOf<CauseListRecord>()
        try {
            val doc = Jsoup.parse(html)
            val causeListDiv = doc.select("#CauseListDiv").first() ?: doc
            val rows = causeListDiv.select("tr")

            var currentListType = "Combined"

            for (row in rows) {
                val headerText = row.select("th").text()
                if (headerText.isNotBlank() && !headerText.contains("Sr.No.", ignoreCase = true)) {
                    currentListType = headerText
                }

                val cols = row.select("td")
                if (cols.size >= 5) {
                    val rawSerialCell = cols[0].html()
                    val cleanSerialText = rawSerialCell.replace(Regex("<[^>]*>"), "").trim()
                    val serialNo = Regex("^\\d+").find(cleanSerialText)?.value ?: cleanSerialText

                    if (serialNo.isBlank() || serialNo.toIntOrNull() == null) continue

                    val statusTag = cols[1].text().trim()
                    val caseDetailCol = cols[2]
                    
                    val caseLink = caseDetailCol.select("a.btn-link, a").first()
                    val fileNo = caseLink?.text()?.trim() ?: ""

                    var cin: String? = null
                    val onClickAttr = caseLink?.attr("onclick") ?: ""
                    val cinMatch = Regex("viewCaseData\\('([^']+)'\\)").find(onClickAttr)
                    if (cinMatch != null) {
                        cin = cinMatch.groupValues[1]
                    }

                    val partyName = cols[3].text().trim()

                    var fileSerial = ""
                    var fileYear = "2026"
                    if (fileNo.contains("/")) {
                        val parts = fileNo.split("/")
                        fileSerial = parts[0].replace(Regex("[^\\d]"), "")
                        fileYear = parts.getOrNull(1)?.replace(Regex("[^\\d]"), "") ?: "2026"
                    }

                    if (fileNo.isNotBlank()) {
                        records.add(
                            CauseListRecord(
                                causeListDate = causeListDate,
                                courtNo = courtNo,
                                listType = currentListType,
                                serialNo = serialNo,
                                caseType = "Criminal",
                                fileNo = fileNo,
                                partyName = partyName,
                                statusTag = statusTag,
                                caseCin = cin,
                                fileSerialNo = fileSerial,
                                fileYear = fileYear
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return records
    }
}
