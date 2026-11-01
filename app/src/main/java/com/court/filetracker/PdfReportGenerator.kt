package com.court.filetracker

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import com.itextpdf.kernel.colors.DeviceRgb
import com.itextpdf.kernel.font.PdfFontFactory
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.kernel.pdf.action.PdfAction
import com.itextpdf.layout.Document
import com.itextpdf.layout.borders.SolidBorder
import com.itextpdf.layout.element.Cell
import com.itextpdf.layout.element.Link
import com.itextpdf.layout.element.Paragraph
import com.itextpdf.layout.element.Table
import com.itextpdf.layout.properties.TextAlignment
import com.itextpdf.layout.properties.UnitValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object PdfReportGenerator {

    fun generateMasterReport(context: Context, records: List<FileRecord>) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (records.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "No records to export!", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                val timeStamp = SimpleDateFormat("dd-MM-yy_HHmm", Locale.getDefault()).format(Date())
                val fileName = "Master_Ledger_$timeStamp.pdf"
                val cacheDir = File(context.cacheDir, "pdf_cache").apply { if (!exists()) mkdirs() }
                val pdfFile = File(cacheDir, fileName)

                val writer = PdfWriter(pdfFile)
                val pdfDoc = PdfDocument(writer)
                val document = Document(pdfDoc)
                val font = PdfFontFactory.createFont()

                document.add(
                    Paragraph("ALLAHABAD HIGH COURT - MASTER CASE TRACKER LEDGER")
                        .setFont(font).setFontSize(14f).setBold().setTextAlignment(TextAlignment.CENTER)
                )
                document.add(
                    Paragraph("Generated: ${SimpleDateFormat("dd-MM-yyyy HH:mm", Locale.getDefault()).format(Date())} | Total Cases: ${records.size}")
                        .setFont(font).setFontSize(9f).setTextAlignment(TextAlignment.CENTER)
                )
                document.add(Paragraph("\n"))

                val table = Table(UnitValue.createPercentArray(floatArrayOf(15f, 15f, 25f, 45f)))
                table.setWidth(UnitValue.createPercentValue(100f))

                listOf("File No.", "Court / Serial", "Status, Loc & Meta-Data", "Complete History & Audit Trace").forEach {
                    table.addHeaderCell(
                        Paragraph(it).setFont(font).setFontSize(9f).setBold()
                            .setFontColor(DeviceRgb(255, 255, 255)).setBackgroundColor(DeviceRgb(33, 150, 243))
                    )
                }

                records.forEach { record ->
                    table.addCell(Paragraph(record.fileNo).setFont(font).setFontSize(9f).setBold())

                    val courtSerial = "Court: ${record.courtNo}\nSerial:${record.serialNo.ifEmpty { "N/A" }}"
                    table.addCell(Paragraph(courtSerial).setFont(font).setFontSize(8f))

                    val metaBuilder = StringBuilder()
                    metaBuilder.append("Status: ${record.status}\n")
                    if (record.storageLocation.isNotBlank()) metaBuilder.append("Loc: ${record.storageLocation}\n")
                    if (record.remarks.isNotBlank()) metaBuilder.append("Remarks: ${record.remarks}\n")
                    if (record.reportsOnRecord.isNotBlank()) metaBuilder.append("Reports: ${record.reportsOnRecord.replace("\n", ", ")}\n")
                    if (record.applicationsOnRecord.isNotBlank()) metaBuilder.append("Apps: ${record.applicationsOnRecord}\n")
                    table.addCell(Paragraph(metaBuilder.toString().trim()).setFont(font).setFontSize(8f))

                    table.addCell(Paragraph(record.historyLog.ifEmpty { "No log recorded." }).setFont(font).setFontSize(7f))
                }

                document.add(table)
                document.close()

                withContext(Dispatchers.Main) {
                    val fileUri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", pdfFile)
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(fileUri, "application/pdf")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(intent, "Open Master Ledger PDF:"))
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "PDF Export Failed: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    fun generateSingleFileReport(context: Context, record: FileRecord) {
        generateMasterReport(context, listOf(record))
    }

    fun generateDateCourtReport(context: Context, date: String, courtNo: String, records: List<FileRecord>) {
        generateMasterReport(context, records)
    }

    fun generateDispatchSharePdf(
        context: Context,
        selectedCases: List<CauseListRecord>,
        allDbRecords: List<FileRecord>,
        courtNo: String,
        date: String
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (selectedCases.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "No cases selected for export!", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                val timeStamp = SimpleDateFormat("dd-MM-yy_HHmm", Locale.getDefault()).format(Date())
                val fileName = "Dispatch_List_Court_${courtNo}_$timeStamp.pdf"
                val cacheDir = File(context.cacheDir, "pdf_cache").apply { if (!exists()) mkdirs() }
                val pdfFile = File(cacheDir, fileName)

                val writer = PdfWriter(pdfFile)
                val pdfDoc = PdfDocument(writer)
                val document = Document(pdfDoc)
                val font = PdfFontFactory.createFont()

                document.add(
                    Paragraph("ALLAHABAD HIGH COURT - DISPATCH BOARD")
                        .setFont(font).setFontSize(14f).setBold().setTextAlignment(TextAlignment.CENTER)
                )
                document.add(
                    Paragraph("Court No: $courtNo | Cause List Date: $date \vert{} Total Cases:${selectedCases.size}")
                        .setFont(font).setFontSize(10f).setTextAlignment(TextAlignment.CENTER)
                )
                document.add(Paragraph("\n"))

                for (clRecord in selectedCases) {
                    val matchedLocal = allDbRecords.firstOrNull { it.fileNo == clRecord.fileNo }

                    val cardTable = Table(UnitValue.createPercentValue(100f))
                    cardTable.setBorder(SolidBorder(DeviceRgb(189, 189, 189), 1f))
                    cardTable.setMarginBottom(10f)

                    val cardCell = Cell()
                    cardCell.setPadding(8f)

                    val headerTable = Table(UnitValue.createPercentArray(floatArrayOf(50f, 50f)))
                    headerTable.setWidth(UnitValue.createPercentValue(100f))

                    val srText = StringBuilder("Sr: ${clRecord.serialNo}")
                    if (clRecord.statusTag.isNotBlank()) {
                        srText.append("  [${clRecord.statusTag}]")
                    }
                    srText.append("  [${clRecord.listType}]")

                    val headerLeft = Paragraph(srText.toString())
                        .setFont(font).setFontSize(10f).setBold().setFontColor(DeviceRgb(25, 118, 210))
                    headerTable.addCell(Cell().add(headerLeft).setBorder(com.itextpdf.layout.borders.Border.NO_BORDER))

                    val caseNoParagraph = Paragraph().setTextAlignment(TextAlignment.RIGHT)
                    if (!clRecord.caseCin.isNullOrBlank()) {
                        val cinUrl = "https://www.allahabadhighcourt.in/apps/status_ccms/index.php/get_CaseDetails"
                        val link = Link(clRecord.fileNo, PdfAction.createURI(cinUrl))
                        link.setFont(font).setFontSize(10f).setBold().setFontColor(DeviceRgb(25, 118, 210)).setUnderline()
                        caseNoParagraph.add(link)
                    } else {
                        caseNoParagraph.add(Paragraph(clRecord.fileNo).setFont(font).setFontSize(10f).setBold().setFontColor(DeviceRgb(25, 118, 210)))
                    }
                    headerTable.addCell(Cell().add(caseNoParagraph).setBorder(com.itextpdf.layout.borders.Border.NO_BORDER))

                    cardCell.add(headerTable)

                    val cleanCaseType = extractCleanCaseType(clRecord.caseType)
                    val parts = clRecord.fileNo.split("/")
                    val caseNumber = if (parts.isNotEmpty()) parts[0].trim() else ""
                    val caseYear = if (parts.size > 1) parts[1].trim() else "2026"
                    val orderSheetUrl = "https://elegalix.allahabadhighcourt.in/elegalix/WebDownloadOrderSheet.do?h=A&t=$cleanCaseType&n=$caseNumber&y=$caseYear&m=C"

                    val partyParagraph = Paragraph()
                        .setFont(font).setFontSize(9f)
                        .add("${clRecord.caseType} \vert{}${clRecord.partyName}  ")

                    val orderSheetLink = Link("[Order Sheet]", PdfAction.createURI(orderSheetUrl))
                    orderSheetLink.setFont(font).setFontSize(8f).setBold().setFontColor(DeviceRgb(194, 24, 91)).setUnderline()
                    partyParagraph.add(orderSheetLink)

                    cardCell.add(partyParagraph)

                    val localInfoTable = Table(UnitValue.createPercentValue(100f))
                    val localCell = Cell().setPadding(6f)

                    if (matchedLocal != null) {
                        localCell.setBackgroundColor(DeviceRgb(232, 245, 233))
                        val statusLine = "Local Tracker Status: '${matchedLocal.status}' (Court:${matchedLocal.courtNo})"
                        localCell.add(Paragraph(statusLine).setFont(font).setFontSize(8f).setBold().setFontColor(DeviceRgb(46, 125, 50)))

                        if (matchedLocal.storageLocation.isNotBlank()) {
                            localCell.add(Paragraph("Location: ${matchedLocal.storageLocation}").setFont(font).setFontSize(8f).setBold().setFontColor(DeviceRgb(66, 66, 66)))
                        }
                        if (matchedLocal.remarks.isNotBlank()) {
                            localCell.add(Paragraph("Remarks: ${matchedLocal.remarks}").setFont(font).setFontSize(8f).setBold().setFontColor(DeviceRgb(194, 24, 91)))
                        }
                        if (matchedLocal.reportsOnRecord.isNotBlank()) {
                            localCell.add(Paragraph("Reports: ${matchedLocal.reportsOnRecord.replace("\n", ", ")}").setFont(font).setFontSize(8f).setFontColor(DeviceRgb(21, 101, 192)))
                        }
                        if (matchedLocal.applicationsOnRecord.isNotBlank()) {
                            localCell.add(Paragraph("Apps: ${matchedLocal.applicationsOnRecord}").setFont(font).setFontSize(8f).setFontColor(DeviceRgb(106, 27, 154)))
                        }
                        if (matchedLocal.historyLog.isNotBlank()) {
                            localCell.add(Paragraph("Notes / History:\n${matchedLocal.historyLog}").setFont(font).setFontSize(7f).setFontColor(DeviceRgb(97, 97, 97)))
                        }
                    } else {
                        localCell.setBackgroundColor(DeviceRgb(255, 243, 224))
                        localCell.add(Paragraph("File not yet registered in local tracker.").setFont(font).setFontSize(8f).setFontColor(DeviceRgb(230, 81, 0)))
                    }

                    localInfoTable.addCell(localCell)
                    cardCell.add(localInfoTable)

                    cardTable.addCell(cardCell)
                    document.add(cardTable)
                }

                document.close()

                withContext(Dispatchers.Main) {
                    val fileUri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", pdfFile)
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "application/pdf"
                        putExtra(Intent.EXTRA_STREAM, fileUri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(shareIntent, "Share Selected Cases PDF via:"))
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Share PDF Export Failed: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun extractCleanCaseType(rawCaseType: String): String {
        val inCaseIdx = rawCaseType.indexOf(" in ", ignoreCase = true)
        val extracted = if (inCaseIdx != -1) {
            rawCaseType.substring(inCaseIdx + 4).trim()
        } else {
            rawCaseType.trim()
        }
        val match = Regex("(?i)^([A-Za-z0-9]+)").find(extracted)
        return match?.groupValues?.get(1)?.uppercase() ?: extracted.uppercase()
    }
}
