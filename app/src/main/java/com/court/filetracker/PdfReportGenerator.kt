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
import com.itextpdf.layout.Document
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
                    // Col 1: File No
                    table.addCell(Paragraph(record.fileNo).setFont(font).setFontSize(9f).setBold())

                    // Col 2: Court & Serial
                    val courtSerial = "Court: ${record.courtNo}\nSerial: ${record.serialNo.ifEmpty { "N/A" }}"
                    table.addCell(Paragraph(courtSerial).setFont(font).setFontSize(8f))

                    // Col 3: Status, Location, Meta-Data
                    val metaBuilder = StringBuilder()
                    metaBuilder.append("Status: ${record.status}\n")
                    if (record.storageLocation.isNotBlank()) metaBuilder.append("Loc: ${record.storageLocation}\n")
                    if (record.remarks.isNotBlank()) metaBuilder.append("Remarks: ${record.remarks}\n")
                    if (record.reportsOnRecord.isNotBlank()) metaBuilder.append("📑 Reports: ${record.reportsOnRecord.replace("\n", ", ")}\n")
                    if (record.applicationsOnRecord.isNotBlank()) metaBuilder.append("📋 Apps: ${record.applicationsOnRecord}\n")
                    table.addCell(Paragraph(metaBuilder.toString().trim()).setFont(font).setFontSize(8f))

                    // Col 4: Audit Stack Trace
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
}
