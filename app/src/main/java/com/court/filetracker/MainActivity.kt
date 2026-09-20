package com.court.filetracker

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.ViewGroup
import android.webkit.*
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    private var onPdfSelected: ((Uri) -> Unit)? = null
    private var onJsonSelected: ((Uri) -> Unit)? = null

    private val pdfPickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { onPdfSelected?.invoke(it) }
    }

    private val jsonPickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { onJsonSelected?.invoke(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val db = AppDatabase.getDatabase(this)
        val fileDao = db.fileRecordDao()
        val causeListDao = db.causeListDao()

        setContent {
            MaterialTheme {
                MainAppScreen(
                    fileDao = fileDao,
                    causeListDao = causeListDao,
                    onPickPdf = { callback ->
                        onPdfSelected = callback
                        pdfPickerLauncher.launch("application/pdf")
                    },
                    onPickJson = { callback ->
                        onJsonSelected = callback
                        jsonPickerLauncher.launch("*/*")
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppScreen(
    fileDao: FileRecordDao,
    causeListDao: CauseListDao,
    onPickPdf: ((Uri) -> Unit) -> Unit,
    onPickJson: ((Uri) -> Unit) -> Unit
) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val currentDate = remember { SimpleDateFormat("dd-MM-yy", Locale.getDefault()).format(Date()) }

    // Core Registration States
    var selectedMode by remember { mutableStateOf("Dispatched") }
    var dispatchDateInput by remember { mutableStateOf(currentDate) }
    var courtNoInput by remember { mutableStateOf("") }
    var listTypeInput by remember { mutableStateOf("DCL") }
    var serialNoInput by remember { mutableStateOf("") }
    var fileSerialInput by remember { mutableStateOf("") }
    var fileYearInput by remember { mutableStateOf("2026") }
    var remarksInput by remember { mutableStateOf("") }
    var judgeNameInput by remember { mutableStateOf("") }

    // Navigation Views: "MAIN", "ADD_CAUSE_LIST", "DISPATCH_CAUSE_LIST", "REPORTS_PANEL"
    var currentView by remember { mutableStateOf("MAIN") }

    // Meta-Data Modal State
    var targetFileForMetaData by remember { mutableStateOf<FileRecord?>(null) }

    // Flush Cause List Dialog State
    var showFlushDialog by remember { mutableStateOf(false) }

    // "Add Cause List" Web & Target States
    var addClCourtInput by remember { mutableStateOf("") }
    var addClDateInput by remember { mutableStateOf(currentDate) }
    var isClWebActive by remember { mutableStateOf(false) }

    // "Dispatch From Cause List" States
    var dispatchClDateInput by remember { mutableStateOf(currentDate) }
    var selectedClCourtChip by remember { mutableStateOf<String?>(null) }
    var clSearchQuery by remember { mutableStateOf("") }

    val allDbRecords by fileDao.getAllRecords().collectAsState(initial = emptyList())
    val courtsWithClForDate by causeListDao.getCourtsForDate(dispatchClDateInput).collectAsState(initial = emptyList())
    val activeCourtCases by causeListDao.getCasesForCourtAndDate(
        dispatchClDateInput,
        selectedClCourtChip ?: ""
    ).collectAsState(initial = emptyList())

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Court File Tracker Menu", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))

                    NavigationDrawerItem(
                        label = { Text("Main Registration") },
                        selected = currentView == "MAIN",
                        onClick = { currentView = "MAIN"; scope.launch { drawerState.close() } },
                        icon = { Icon(Icons.Default.Edit, contentDescription = null) }
                    )
                    NavigationDrawerItem(
                        label = { Text("Add Cause List (From Web)") },
                        selected = currentView == "ADD_CAUSE_LIST",
                        onClick = { currentView = "ADD_CAUSE_LIST"; isClWebActive = false; scope.launch { drawerState.close() } },
                        icon = { Icon(Icons.Default.AddCircle, contentDescription = null) }
                    )
                    NavigationDrawerItem(
                        label = { Text("Dispatch from Cause List") },
                        selected = currentView == "DISPATCH_CAUSE_LIST",
                        onClick = { currentView = "DISPATCH_CAUSE_LIST"; scope.launch { drawerState.close() } },
                        icon = { Icon(Icons.Default.List, contentDescription = null) }
                    )
                    NavigationDrawerItem(
                        label = { Text("PDF Reports Engine") },
                        selected = currentView == "REPORTS_PANEL",
                        onClick = { currentView = "REPORTS_PANEL"; scope.launch { drawerState.close() } },
                        icon = { Icon(Icons.Default.Share, contentDescription = null) }
                    )
                    NavigationDrawerItem(
                        label = { Text("Flush Cause List Data") },
                        selected = false,
                        onClick = { showFlushDialog = true; scope.launch { drawerState.close() } },
                        icon = { Icon(Icons.Default.Delete, contentDescription = null) }
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))

                    NavigationDrawerItem(
                        label = { Text("Share JSON Backup") },
                        selected = false,
                        onClick = {
                            scope.launch {
                                val files = fileDao.getAllRecords().first()
                                val cls = causeListDao.getAllCauseListRecords().first()
                                JsonBackupHelper.exportFullBackup(context, files, cls, shareDirectly = true)
                                drawerState.close()
                            }
                        },
                        icon = { Icon(Icons.Default.Share, contentDescription = null) }
                    )
                    NavigationDrawerItem(
                        label = { Text("Download JSON Backup") },
                        selected = false,
                        onClick = {
                            scope.launch {
                                val files = fileDao.getAllRecords().first()
                                val cls = causeListDao.getAllCauseListRecords().first()
                                JsonBackupHelper.exportFullBackup(context, files, cls, shareDirectly = false)
                                drawerState.close()
                            }
                        },
                        icon = { Icon(Icons.Default.ArrowDropDown, contentDescription = null) }
                    )
                    NavigationDrawerItem(
                        label = { Text("Import JSON Backup File") },
                        selected = false,
                        onClick = {
                            scope.launch { drawerState.close() }
                            onPickJson { uri ->
                                JsonBackupHelper.importFullBackup(context, uri, fileDao, causeListDao) {}
                            }
                        },
                        icon = { Icon(Icons.Default.Refresh, contentDescription = null) }
                    )
                }
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            when (currentView) {
                                "ADD_CAUSE_LIST" -> "Add Cause List Portal"
                                "DISPATCH_CAUSE_LIST" -> "Dispatch from Cause List"
                                "REPORTS_PANEL" -> "Searchable PDF Reports"
                                else -> "Allahabad High Court File Tracker"
                            },
                            fontSize = 16.sp
                        )
                    },
                    navigationIcon = {
                        if (currentView != "MAIN") {
                            IconButton(onClick = { currentView = "MAIN" }) {
                                Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                            }
                        } else {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(Icons.Default.Menu, contentDescription = "Menu")
                            }
                        }
                    }
                )
            }
        ) { padding ->
            Box(modifier = Modifier.padding(padding).fillMaxSize().padding(12.dp)) {

                // 1. ADD CAUSE LIST VIEW
                if (currentView == "ADD_CAUSE_LIST") {
                    if (!isClWebActive) {
                        Card(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text("Add Cause List to Local App", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                Spacer(modifier = Modifier.height(10.dp))
                                OutlinedTextField(
                                    value = addClCourtInput,
                                    onValueChange = { addClCourtInput = it },
                                    label = { Text("Enter Court Number *") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = addClDateInput,
                                    onValueChange = { addClDateInput = it },
                                    label = { Text("Enter Cause List Date (dd-MM-yy) *") },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Button(
                                    enabled = addClCourtInput.isNotBlank() && addClDateInput.isNotBlank(),
                                    onClick = { isClWebActive = true },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("OPEN CAUSE LIST PORTAL")
                                }
                            }
                        }
                    } else {
                        CauseListIngestionWebView(
                            courtNo = addClCourtInput.trim(),
                            date = addClDateInput.trim(),
                            causeListDao = causeListDao,
                            onClose = { isClWebActive = false }
                        )
                    }

                // 2. DISPATCH FROM CAUSE LIST VIEW
                } else if (currentView == "DISPATCH_CAUSE_LIST") {
                    Column(modifier = Modifier.fillMaxSize()) {
                        OutlinedTextField(
                            value = dispatchClDateInput,
                            onValueChange = {
                                dispatchClDateInput = it
                                selectedClCourtChip = null
                            },
                            label = { Text("Cause List Date (dd-MM-yy)") },
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Courts with Cause Lists on $dispatchClDateInput (${courtsWithClForDate.size}):", fontSize = 12.sp, fontWeight = FontWeight.Bold)

                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            courtsWithClForDate.forEach { court ->
                                FilterChip(
                                    selected = selectedClCourtChip == court,
                                    onClick = { selectedClCourtChip = if (selectedClCourtChip == court) null else court },
                                    label = { Text("Court $court") }
                                )
                            }
                        }

                        if (selectedClCourtChip == null) {
                            Text("Please select a Court Number chip above to inspect cause lists.", color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(top = 16.dp))
                        } else {
                            OutlinedTextField(
                                value = clSearchQuery,
                                onValueChange = { clSearchQuery = it },
                                label = { Text("Search by Serial No. or File No.") },
                                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
                            )

                            val filteredCases = activeCourtCases.filter {
                                if (clSearchQuery.isBlank()) true
                                else it.serialNo.contains(clSearchQuery, ignoreCase = true) ||
                                        it.fileNo.contains(clSearchQuery, ignoreCase = true) ||
                                        it.fileSerialNo.contains(clSearchQuery, ignoreCase = true) ||
                                        it.connectedCases.contains(clSearchQuery, ignoreCase = true)
                            }

                            Text("Cases in Court $selectedClCourtChip (${filteredCases.size}):", fontWeight = FontWeight.Bold, fontSize = 13.sp)

                            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f).padding(top = 6.dp)) {
                                items(filteredCases) { clRecord ->
                                    val matchedLocal = allDbRecords.firstOrNull { it.fileNo == clRecord.fileNo }

                                    Card(modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(3.dp)) {
                                        Column(modifier = Modifier.padding(12.dp)) {
                                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                                Text("Sr: ${clRecord.serialNo} (${clRecord.listType})", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                                Text(clRecord.fileNo, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                            }
                                            Text("${clRecord.caseType} | ${clRecord.partyName}", fontSize = 12.sp, maxLines = 2)

                                            if (clRecord.connectedCases.isNotBlank()) {
                                                Text("With: ${clRecord.connectedCases}", fontSize = 11.sp, color = Color(0xFF6A1B9A), fontWeight = FontWeight.SemiBold)
                                            }

                                            // Local Tracker Status Banner
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Surface(
                                                color = if (matchedLocal != null) Color(0xFFE8F5E9) else Color(0xFFFFF3E0),
                                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                                            ) {
                                                Column(modifier = Modifier.padding(6.dp)) {
                                                    if (matchedLocal != null) {
                                                        Text("✓ Local Tracker: Status '${matchedLocal.status}' | Loc: ${matchedLocal.storageLocation.ifEmpty { "None" }}", fontSize = 11.sp, color = Color(0xFF2E7D32), fontWeight = FontWeight.Bold)
                                                        if (matchedLocal.reportsOnRecord.isNotBlank()) Text("📑 Reports: ${matchedLocal.reportsOnRecord}", fontSize = 10.sp)
                                                        if (matchedLocal.applicationsOnRecord.isNotBlank()) Text("📋 Apps: ${matchedLocal.applicationsOnRecord}", fontSize = 10.sp)
                                                    } else {
                                                        Text("⚠️ File not yet registered in local tracker.", fontSize = 11.sp, color = Color(0xFFE65100))
                                                    }
                                                }
                                            }

                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.align(Alignment.End).padding(top = 4.dp)) {
                                                OutlinedButton(
                                                    onClick = {
                                                        scope.launch {
                                                            var target = fileDao.getRecordByFileNo(clRecord.fileNo)
                                                            if (target == null) {
                                                                val newRec = FileRecord(
                                                                    fileNo = clRecord.fileNo,
                                                                    dispatchDate = clRecord.causeListDate,
                                                                    dispatchDatesCsv = clRecord.causeListDate,
                                                                    courtNo = clRecord.courtNo,
                                                                    serialNo = "${clRecord.listType} - ${clRecord.serialNo}",
                                                                    status = "Cause List Identified",
                                                                    storageLocation = "",
                                                                    historyLog = "[${clRecord.causeListDate}] Sourced from Cause List Court ${clRecord.courtNo}"
                                                                )
                                                                val id = fileDao.insertOrUpdateRecord(newRec)
                                                                target = newRec.copy(id = id)
                                                            }
                                                            targetFileForMetaData = target
                                                        }
                                                    }
                                                ) {
                                                    Text("Add Meta-Data", fontSize = 11.sp)
                                                }

                                                Button(
                                                    onClick = {
                                                        fileSerialInput = clRecord.fileSerialNo
                                                        fileYearInput = clRecord.fileYear
                                                        courtNoInput = clRecord.courtNo
                                                        serialNoInput = clRecord.serialNo
                                                        listTypeInput = clRecord.listType
                                                        dispatchDateInput = clRecord.causeListDate
                                                        currentView = "MAIN"
                                                        Toast.makeText(context, "Dispatched form pre-filled for ${clRecord.fileNo}!", Toast.LENGTH_SHORT).show()
                                                    }
                                                ) {
                                                    Text("Direct Dispatch", fontSize = 11.sp)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                // 3. MAIN REGISTRATION VIEW
                } else {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Card(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text("Registration & Court Allocation", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)

                                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                    FilterChip(selected = selectedMode == "Dispatched", onClick = { selectedMode = "Dispatched" }, label = { Text("Dispatched") })
                                    FilterChip(selected = selectedMode == "Not Sent", onClick = { selectedMode = "Not Sent" }, label = { Text("Not Sent") })
                                    FilterChip(selected = selectedMode == "Chamber", onClick = { selectedMode = "Chamber" }, label = { Text("Chamber") })
                                }

                                OutlinedTextField(
                                    value = dispatchDateInput,
                                    onValueChange = { dispatchDateInput = it },
                                    label = { Text("Dispatch Date") },
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                                )

                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(
                                        value = fileSerialInput,
                                        onValueChange = { fileSerialInput = it },
                                        label = { Text("File Serial *") },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        modifier = Modifier.weight(1.2f)
                                    )
                                    OutlinedTextField(
                                        value = fileYearInput,
                                        onValueChange = { fileYearInput = it },
                                        label = { Text("File Year *") },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        modifier = Modifier.weight(0.8f)
                                    )
                                }

                                if (selectedMode == "Dispatched") {
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedTextField(
                                            value = courtNoInput,
                                            onValueChange = { courtNoInput = it },
                                            label = { Text("Court No") },
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                            modifier = Modifier.weight(1f)
                                        )
                                        OutlinedTextField(
                                            value = serialNoInput,
                                            onValueChange = { serialNoInput = it },
                                            label = { Text("Serial No") },
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                                        FilterChip(selected = listTypeInput == "DCL", onClick = { listTypeInput = "DCL" }, label = { Text("DCL") })
                                        FilterChip(selected = listTypeInput == "ACL", onClick = { listTypeInput = "ACL" }, label = { Text("ACL") })
                                        FilterChip(selected = listTypeInput == "Correction", onClick = { listTypeInput = "Correction" }, label = { Text("Correction") })
                                    }
                                }

                                OutlinedTextField(
                                    value = remarksInput,
                                    onValueChange = { remarksInput = it },
                                    label = { Text("Remarks") },
                                    modifier = Modifier.fillMaxWidth()
                                )

                                Button(
                                    onClick = {
                                        val serialInt = fileSerialInput.trim().toIntOrNull()
                                        val yearInt = fileYearInput.trim().toIntOrNull()
                                        if (serialInt == null || yearInt == null) {
                                            Toast.makeText(context, "Invalid File Serial/Year!", Toast.LENGTH_SHORT).show()
                                            return@Button
                                        }

                                        val formattedFileNo = "${fileSerialInput.trim().toInt()}/$yearInt"
                                        scope.launch {
                                            val existing = fileDao.getRecordByFileNo(formattedFileNo)
                                            val serialFormatted = "$listTypeInput - ${serialNoInput.trim()}"
                                            val logEntry = "[$dispatchDateInput] Dispatched to Court $courtNoInput | Serial: $serialFormatted"

                                            val rec = FileRecord(
                                                id = existing?.id ?: 0,
                                                fileNo = formattedFileNo,
                                                dispatchDate = dispatchDateInput,
                                                dispatchDatesCsv = if (existing == null) dispatchDateInput else "${existing.dispatchDatesCsv}, $dispatchDateInput",
                                                courtNo = courtNoInput.trim(),
                                                serialNo = serialFormatted,
                                                status = "Dispatched",
                                                storageLocation = "",
                                                remarks = remarksInput.trim(),
                                                historyLog = if (existing == null) logEntry else "${existing.historyLog}\n$logEntry",
                                                reportsOnRecord = existing?.reportsOnRecord ?: "",
                                                applicationsOnRecord = existing?.applicationsOnRecord ?: ""
                                            )
                                            fileDao.insertOrUpdateRecord(rec)
                                            fileSerialInput = ""
                                            serialNoInput = ""
                                            remarksInput = ""
                                            Toast.makeText(context, "Saved $formattedFileNo Successfully!", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                                ) {
                                    Text("SAVE RECORD")
                                }
                            }
                        }

                        Text("Recent Registered Files:", fontWeight = FontWeight.Bold)
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f).padding(top = 4.dp)) {
                            items(allDbRecords.take(15)) { record ->
                                Card(modifier = Modifier.fillMaxWidth()) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                            Text(record.fileNo, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                            Badge { Text(record.status) }
                                        }
                                        Text("Court: ${record.courtNo} | Serial: ${record.serialNo}", fontSize = 12.sp)
                                        if (record.reportsOnRecord.isNotBlank()) Text("📑 Reports: ${record.reportsOnRecord}", fontSize = 11.sp, color = Color(0xFF1565C0))
                                        if (record.applicationsOnRecord.isNotBlank()) Text("📋 Apps: ${record.applicationsOnRecord}", fontSize = 11.sp, color = Color(0xFF6A1B9A))

                                        Button(
                                            onClick = { targetFileForMetaData = record },
                                            modifier = Modifier.align(Alignment.End).padding(top = 4.dp)
                                        ) {
                                            Text("Add Case Meta-Data", fontSize = 11.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // 4. ADD CASE META-DATA MODAL
    targetFileForMetaData?.let { record ->
        AddCaseMetaDataDialog(
            record = record,
            onDismiss = { targetFileForMetaData = null },
            onSave = { updatedRecord ->
                scope.launch {
                    fileDao.insertOrUpdateRecord(updatedRecord)
                    targetFileForMetaData = null
                    Toast.makeText(context, "Meta-Data Saved for Perpetuity!", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    // 5. FLUSH CAUSE LIST DATA DIALOG
    if (showFlushDialog) {
        var cutoffDateInput by remember { mutableStateOf(currentDate) }

        AlertDialog(
            onDismissRequest = { showFlushDialog = false },
            title = { Text("Flush Ephemeral Cause List Data") },
            text = {
                Column {
                    Text("Enter cutoff date. All parsed Cause List PDFs on or before this date will be deleted.\n\nAll dispatched cases, locations, reports, and legacy data will remain unaffected.", fontSize = 12.sp)
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = cutoffDateInput,
                        onValueChange = { cutoffDateInput = it },
                        label = { Text("Cutoff Date (dd-MM-yy)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            val deletedCount = causeListDao.deleteCauseListsUpToDate(cutoffDateInput.trim())
                            showFlushDialog = false
                            Toast.makeText(context, "Purged $deletedCount Cause List rows!", Toast.LENGTH_LONG).show()
                        }
                    }
                ) {
                    Text("Confirm Flush")
                }
            },
            dismissButton = {
                TextButton(onClick = { showFlushDialog = false }) { Text("Cancel") }
            }
        )
    }
}

/**
 * Embedded Cause List Web View with PDF detection and Add Confirmation
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun CauseListIngestionWebView(
    courtNo: String,
    date: String,
    causeListDao: CauseListDao,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingPdfUrl by remember { mutableStateOf<String?>(null) }
    var isParsing by remember { mutableStateOf(false) }
    var webView: WebView? by remember { mutableStateOf(null) }

    BackHandler {
        if (webView?.canGoBack() == true) webView?.goBack() else onClose()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Court $courtNo | Date: $date", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Button(onClick = onClose, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)) {
                Text("Exit Portal", fontSize = 12.sp)
            }
        }

        if (isParsing) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text("Parsing Cause List PDF locally into SQLite...", fontSize = 11.sp, modifier = Modifier.padding(vertical = 4.dp))
        }

        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false

                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            val url = request?.url?.toString() ?: return false
                            if (url.endsWith(".pdf", ignoreCase = true) || url.contains(".pdf?", ignoreCase = true)) {
                                pendingPdfUrl = url
                                return true
                            }
                            return false
                        }
                    }

                    setDownloadListener { url, _, _, _, _ ->
                        if (url.contains("pdf", ignoreCase = true)) {
                            pendingPdfUrl = url
                        }
                    }

                    loadUrl("https://www.allahabadhighcourt.in/causelist/")
                    webView = this
                }
            },
            modifier = Modifier.fillMaxSize().weight(1f)
        )
    }

    pendingPdfUrl?.let { url ->
        AlertDialog(
            onDismissRequest = { pendingPdfUrl = null },
            title = { Text("Do You Wish to add this PDF?") },
            text = { Text("This will parse all serial numbers, case numbers, counsels, and connected cases for Court $courtNo on $date.", fontSize = 12.sp) },
            confirmButton = {
                Button(onClick = {
                    val pdfToParse = url
                    pendingPdfUrl = null
                    isParsing = true

                    scope.launch(Dispatchers.IO) {
                        try {
                            val conn = URL(pdfToParse).openConnection() as HttpURLConnection
                            conn.connectTimeout = 15000
                            conn.readTimeout = 15000
                            val stream = conn.inputStream
                            val parsedCases = CauseListParser.parseCauseListPdf(stream, courtNo, date)
                            stream.close()

                            if (parsedCases.isNotEmpty()) {
                                causeListDao.insertAll(parsedCases)
                                withContext(Dispatchers.Main) {
                                    isParsing = false
                                    Toast.makeText(context, "Successfully Imported ${parsedCases.size} Cases for Court $courtNo!", Toast.LENGTH_LONG).show()
                                }
                            } else {
                                withContext(Dispatchers.Main) {
                                    isParsing = false
                                    Toast.makeText(context, "Could not extract cases from PDF!", Toast.LENGTH_LONG).show()
                                }
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                            withContext(Dispatchers.Main) {
                                isParsing = false
                                Toast.makeText(context, "Download/Parse Error: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }) { Text("Yes") }
            },
            dismissButton = {
                TextButton(onClick = { pendingPdfUrl = null }) { Text("No") }
            }
        )
    }
}

/**
 * Case Meta-Data Attachment Dialog (Reports on Record & Applications)
 */
@Composable
fun AddCaseMetaDataDialog(
    record: FileRecord,
    onDismiss: () -> Unit,
    onSave: (FileRecord) -> Unit
) {
    var metaType by remember { mutableStateOf("REPORT") }
    var selectedReportOption by remember { mutableStateOf("Notice: Served") }
    var customReportText by remember { mutableStateOf("") }
    var reportDateInput by remember { mutableStateOf("") }

    var appNoInput by remember { mutableStateOf("") }
    var appYearInput by remember { mutableStateOf("2026") }

    val reportOptions = listOf("Notice: Served", "Notice: Unserved", "Compromise: Done", "Compromise: Not Done", "Mediation Report", "Other Report")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Case Meta-Data: ${record.fileNo}", fontSize = 15.sp) },
        text = {
            Column {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                    FilterChip(selected = metaType == "REPORT", onClick = { metaType = "REPORT" }, label = { Text("Keep Report") })
                    FilterChip(selected = metaType == "APPLICATION", onClick = { metaType = "APPLICATION" }, label = { Text("Add Application") })
                }

                Spacer(modifier = Modifier.height(10.dp))

                if (metaType == "REPORT") {
                    var dropdownExpanded by remember { mutableStateOf(false) }

                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = selectedReportOption,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Select Report Type") },
                            trailingIcon = {
                                IconButton(onClick = { dropdownExpanded = true }) {
                                    Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                        DropdownMenu(expanded = dropdownExpanded, onDismissRequest = { dropdownExpanded = false }) {
                            reportOptions.forEach { opt ->
                                DropdownMenuItem(text = { Text(opt) }, onClick = { selectedReportOption = opt; dropdownExpanded = false })
                            }
                        }
                    }

                    if (selectedReportOption == "Other Report") {
                        OutlinedTextField(
                            value = customReportText,
                            onValueChange = { customReportText = it },
                            label = { Text("Specify Report Description") },
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                        )
                    }

                    OutlinedTextField(
                        value = reportDateInput,
                        onValueChange = { reportDateInput = it },
                        label = { Text("Report Date (Optional e.g. 16-09-26)") },
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                    )
                } else {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = appNoInput,
                            onValueChange = { appNoInput = it },
                            label = { Text("App No (e.g. 9)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = appYearInput,
                            onValueChange = { appYearInput = it },
                            label = { Text("Year (e.g. 2026)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Text("Format: [App No]/[Year]", fontSize = 11.sp, color = Color.Gray, modifier = Modifier.padding(top = 4.dp))
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val currentDate = SimpleDateFormat("dd-MM-yy", Locale.getDefault()).format(Date())

                    if (metaType == "REPORT") {
                        val reportLabel = if (selectedReportOption == "Other Report") customReportText.trim() else selectedReportOption
                        val dateSuffix = if (reportDateInput.isNotBlank()) " (Date: ${reportDateInput.trim()})" else ""
                        val finalReportStr = "$reportLabel$dateSuffix"

                        val updatedReports = if (record.reportsOnRecord.isBlank()) finalReportStr else "${record.reportsOnRecord}\n$finalReportStr"
                        val updatedLog = "${record.historyLog}\n[$currentDate] Placed on Record -> $finalReportStr"

                        onSave(record.copy(reportsOnRecord = updatedReports, historyLog = updatedLog))
                    } else {
                        val formattedApp = "${appNoInput.trim()}/${appYearInput.trim()}"
                        val updatedApps = if (record.applicationsOnRecord.isBlank()) formattedApp else "${record.applicationsOnRecord}, $formattedApp"
                        val updatedLog = "${record.historyLog}\n[$currentDate] Application Tagged -> $formattedApp"

                        onSave(record.copy(applicationsOnRecord = updatedApps, historyLog = updatedLog))
                    }
                }
            ) {
                Text("Save Meta-Data")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
