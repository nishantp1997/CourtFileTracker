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

    /**
     * Generate Master Ledger PDF with 100% Searchable Text Indexing
     */
    fun generateMasterReport(context: Context, records: List<FileRecord>) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (records.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "No records available to export!", Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }

                val timeStamp = SimpleDateFormat("dd-MM-yy_HHmm", Locale.getDefault()).format(Date())
                val fileName = "Master_Ledger_Report_$timeStamp.pdf"
                val cacheDir = File(context.cacheDir, "pdf_cache")
                if (!cacheDir.exists()) cacheDir.mkdirs()

                val pdfFile = File(cacheDir, fileName)
                val writer = PdfWriter(pdfFile)
                val pdfDoc = PdfDocument(writer)
                val document = Document(pdfDoc)

                // Standard Searchable Font
                val font = PdfFontFactory.createFont()

                // Header Title
                document.add(
                    Paragraph("ALLAHABAD HIGH COURT - MASTER FILE LEDGER")
                        .setFont(font)
                        .setFontSize(14f)
                        .setBold()
                        .setTextAlignment(TextAlignment.CENTER)
                )

                document.add(
                    Paragraph("Report Generated On: ${SimpleDateFormat("dd-MM-yyyy HH:mm", Locale.getDefault()).format(Date())} | Total Records: ${records.size}")
                        .setFont(font)
                        .setFontSize(9f)
                        .setTextAlignment(TextAlignment.CENTER)
                )

                document.add(Paragraph("\n"))

                // Table Layout: 5 Columns (File No, Court & Serial, Status & Location, History Log)
                val table = Table(UnitValue.createPercentArray(floatArrayOf(18f, 18f, 20f, 44f)))
                table.setWidth(UnitValue.createPercentValue(100f))

                // Table Headers
                val headers = listOf("File No.", "Court / Serial", "Active Status & Loc", "Audit Stack Trace Log")
                headers.forEach { header ->
                    table.addHeaderCell(
                        Paragraph(header)
                            .setFont(font)
                            .setFontSize(10f)
                            .setBold()
                            .setFontColor(DeviceRgb(255, 255, 255))
                            .setBackgroundColor(DeviceRgb(33, 150, 243))
                    )
                }

                // Table Body Rows
                records.forEach { record ->
                    // Column 1: File No. (Searchable)
                    table.addCell(
                        Paragraph(record.fileNo)
                            .setFont(font)
                            .setFontSize(9f)
                            .setBold()
                    )

                    // Column 2: Court & Serial No.
                    val courtSerialText = if (record.courtNo != "N/A") {
                        "Court No: ${record.courtNo}\nSerial: ${record.serialNo}"
                    } else if (record.sentToChamber) {
                        "Chamber: ${record.judgeName.ifEmpty { "Hon'ble Judge" }}"
                    } else {
                        "N/A"
                    }
                    table.addCell(
                        Paragraph(courtSerialText)
                            .setFont(font)
                            .setFontSize(8f)
                    )

                    // Column 3: Active Status & Storage Location
                    val statusText = "${record.status}${if (record.storageLocation.isNotBlank()) " (${record.storageLocation})" else ""}${if (record.remarks.isNotBlank()) "\nRemarks: ${record.remarks}" else ""}"
                    table.addCell(
                        Paragraph(statusText)
                            .setFont(font)
                            .setFontSize(8f)
                    )

                    // Column 4: History Log Stack Trace
                    table.addCell(
                        Paragraph(record.historyLog.ifEmpty { "No History Recorded" })
                            .setFont(font)
                            .setFontSize(7f)
                    )
                }

                document.add(table)
                document.close()

                openPdfFile(context, pdfFile)

            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "PDF Generation Error: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * Generate Single Case File Report PDF
     */
    fun generateSingleFileReport(context: Context, record: FileRecord) {
        generateMasterReport(context, listOf(record))
    }

    /**
     * Generate Date & Court Specific Report PDF
     */
    fun generateDateCourtReport(context: Context, date: String, courtNo: String, records: List<FileRecord>) {
        generateMasterReport(context, records)
    }

    private suspend fun openPdfFile(context: Context, file: File) {
        withContext(Dispatchers.Main) {
            try {
                val fileUri: Uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )

                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(fileUri, "application/pdf")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                val chooser = Intent.createChooser(intent, "Open Report PDF via:")
                context.startActivity(chooser)

            } catch (e: Exception) {
                Toast.makeText(context, "PDF generated at: ${file.absolutePath}", Toast.LENGTH_LONG).show()
            }
        }
    }
}
