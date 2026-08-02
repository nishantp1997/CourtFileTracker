package com.court.filetracker

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val db = AppDatabase.getDatabase(this)
        val dao = db.fileRecordDao()

        setContent {
            MaterialTheme {
                MainAppScreen(
                    dao = dao,
                    onGoogleDriveLogin = { DriveServiceHelper.requestDriveSignIn(this) },
                    onBackup = { DriveServiceHelper.performBackup(this) },
                    onRestore = { DriveServiceHelper.performRestore(this, Runnable {}) }
                )
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == DriveServiceHelper.RC_SIGN_IN) {
            Toast.makeText(this, "Google Drive Account Authenticated!", Toast.LENGTH_SHORT).show()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppScreen(
    dao: FileRecordDao,
    onGoogleDriveLogin: () -> Unit,
    onBackup: () -> Unit,
    onRestore: () -> Unit
) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val currentDate = remember { SimpleDateFormat("dd-MM-yy", Locale.getDefault()).format(Date()) }

    // Form States
    var selectedMode by remember { mutableStateOf("Dispatched") }
    var dispatchDateInput by remember { mutableStateOf(currentDate) }
    var courtNoInput by remember { mutableStateOf("") }
    var listTypeInput by remember { mutableStateOf("DCL") }
    var serialNoInput by remember { mutableStateOf("") }
    var fileNoInput by remember { mutableStateOf("") }
    var remarksInput by remember { mutableStateOf("") }
    var judgeNameInput by remember { mutableStateOf("") }
    var storageLocationInput by remember { mutableStateOf("") }

    // Dropdown State
    var locationDropdownExpanded by remember { mutableStateOf(false) }
    val defaultLocations = listOf("Shelf", "Bundle", "Person", "Seat", "Chamber")

    // Navigation Views
    var currentView by remember { mutableStateOf("MAIN") }
    var searchDateInput by remember { mutableStateOf(currentDate) }
    var searchSelectedCourt by remember { mutableStateOf<String?>(null) }
    var globalKeywordSearch by remember { mutableStateOf("") }

    // Report Generator Specific States
    var reportTargetFileNo by remember { mutableStateOf("") }
    var reportTargetDate by remember { mutableStateOf(currentDate) }
    var reportTargetCourtNo by remember { mutableStateOf("") }

    // Bulk Operations Mode (Locked & Preserved)
    var bulkDateInput by remember { mutableStateOf(currentDate) }
    var bulkCourtNo by remember { mutableStateOf("") }
    var bulkTargetStatus by remember { mutableStateOf("Taken Up") }
    var selectedFileIds by remember { mutableStateOf(setOf<Long>()) }

    // Dialog States
    var activeTraceRecord by remember { mutableStateOf<FileRecord?>(null) }
    var activeUpdateRecord by remember { mutableStateOf<FileRecord?>(null) }

    // Normalized Query Triggers
    val normalizedSearchDate = remember(searchDateInput) { normalizeDate(searchDateInput) }
    val normalizedBulkDate = remember(bulkDateInput) { normalizeDate(bulkDateInput) }
    val normalizedReportDate = remember(reportTargetDate) { normalizeDate(reportTargetDate) }
    val normalizedSearchKeyword = remember(globalKeywordSearch) { normalizeSearchQuery(globalKeywordSearch) }

    // Data Flows
    val recordsList by dao.getRecordsByDate(normalizeDate(dispatchDateInput)).collectAsState(initial = emptyList())
    val searchCourtsList by dao.getCourtsByDate(normalizedSearchDate).collectAsState(initial = emptyList())
    val searchCourtFiles by (searchSelectedCourt?.let { dao.getRecordsByDateAndCourt(normalizedSearchDate, it) } ?: dao.getRecordsByDate(normalizedSearchDate)).collectAsState(initial = emptyList())
    val bulkCourtFiles by (if (bulkCourtNo.isNotBlank()) dao.getRecordsByDateAndCourt(normalizedBulkDate, bulkCourtNo) else dao.getRecordsByDate(normalizedBulkDate)).collectAsState(initial = emptyList())
    val globalSearchResults by dao.searchRecords(normalizedSearchKeyword).collectAsState(initial = emptyList())
    val allRecords by dao.getAllRecords().collectAsState(initial = emptyList())

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Court File Tracker Menu", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                    NavigationDrawerItem(
                        label = { Text("🔍 Filter by Date & Court") },
                        selected = currentView == "SEARCH_DATE_COURT",
                        onClick = { currentView = "SEARCH_DATE_COURT"; scope.launch { drawerState.close() } },
                        icon = { Icon(Icons.Default.Search, contentDescription = null) }
                    )
                    NavigationDrawerItem(
                        label = { Text("⚡ Bulk Operations") },
                        selected = currentView == "BULK",
                        onClick = { currentView = "BULK"; scope.launch { drawerState.close() } },
                        icon = { Icon(Icons.Default.List, contentDescription = null) }
                    )
                    NavigationDrawerItem(
                        label = { Text("📄 PDF Reports Engine") },
                        selected = currentView == "REPORTS_PANEL",
                        onClick = { currentView = "REPORTS_PANEL"; scope.launch { drawerState.close() } },
                        icon = { Icon(Icons.Default.Share, contentDescription = null) }
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                    NavigationDrawerItem(
                        label = { Text("🔑 Connect Google Drive") },
                        selected = false,
                        onClick = { onGoogleDriveLogin(); scope.launch { drawerState.close() } },
                        icon = { Icon(Icons.Default.AccountCircle, contentDescription = null) }
                    )
                    NavigationDrawerItem(
                        label = { Text("☁️ Google Drive Backup") },
                        selected = false,
                        onClick = { onBackup(); scope.launch { drawerState.close() } },
                        icon = { Icon(Icons.Default.ArrowForward, contentDescription = null) }
                    )
                    NavigationDrawerItem(
                        label = { Text("📥 Restore Cloud Data") },
                        selected = false,
                        onClick = { onRestore(); scope.launch { drawerState.close() } },
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
                                "SEARCH_DATE_COURT" -> "🔍 Search by Date & Court"
                                "BULK" -> "⚡ Bulk Operations by Date & Court"
                                "REPORTS_PANEL" -> "📄 Landscape PDF Reports"
                                else -> "Allahabad High Court File Tracker"
                            },
                            fontSize = 16.sp
                        )
                    },
                    navigationIcon = {
                        if (currentView != "MAIN") {
                            IconButton(onClick = { currentView = "MAIN"; searchSelectedCourt = null; globalKeywordSearch = "" }) {
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
            Column(modifier = Modifier.padding(padding).padding(12.dp)) {

                if (currentView == "REPORTS_PANEL") {
                    Text("Select PDF Report Type (Legal Size Landscape):", fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 12.dp))

                    Card(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("1. Entire Database Report", fontWeight = FontWeight.Bold)
                            Text("Generates complete ledger with full audit stack traces.", fontSize = 12.sp, color = Color.Gray)
                            Button(
                                onClick = { PdfReportGenerator.generateMasterReport(context, allRecords) },
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                            ) {
                                Text("EXPORT MASTER LEDGER PDF")
                            }
                        }
                    }

                    Card(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("2. Particular Case File Report", fontWeight = FontWeight.Bold)
                            OutlinedTextField(
                                value = reportTargetFileNo,
                                onValueChange = { reportTargetFileNo = it },
                                label = { Text("File Number (e.g. 1234/2026)") },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Button(
                                onClick = {
                                    scope.launch {
                                        val rec = dao.getRecordByFileNo(reportTargetFileNo.trim())
                                        if (rec != null) {
                                            PdfReportGenerator.generateSingleFileReport(context, rec)
                                        } else {
                                            Toast.makeText(context, "File Not Found!", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                            ) {
                                Text("EXPORT SINGLE CASE FILE PDF")
                            }
                        }
                    }

                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("3. Date & Court Number Wise Report", fontWeight = FontWeight.Bold)
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(value = reportTargetDate, onValueChange = { reportTargetDate = it }, label = { Text("Date (e.g. 4-8-26 / 04-08-2026)") }, modifier = Modifier.weight(1f))
                                OutlinedTextField(
                                    value = reportTargetCourtNo,
                                    onValueChange = { reportTargetCourtNo = it },
                                    label = { Text("Court No") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            Button(
                                onClick = {
                                    scope.launch {
                                        dao.getRecordsByDateAndCourt(normalizedReportDate, reportTargetCourtNo.trim()).collect { recs ->
                                            PdfReportGenerator.generateDateCourtReport(context, normalizedReportDate, reportTargetCourtNo.trim(), recs)
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                            ) {
                                Text("EXPORT COURT DISPATCH PDF")
                            }
                        }
                    }

                } else if (currentView == "SEARCH_DATE_COURT") {
                    OutlinedTextField(
                        value = searchDateInput,
                        onValueChange = { searchDateInput = it; searchSelectedCourt = null },
                        label = { Text("Select Date (e.g. 4-8-26 or 04-08-2026)") },
                        leadingIcon = { Icon(Icons.Default.DateRange, contentDescription = null) },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                    )

                    OutlinedTextField(
                        value = globalKeywordSearch,
                        onValueChange = { globalKeywordSearch = it },
                        label = { Text("Or Keyword Search (File No, Court, Serial No, Status)") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                    )

                    if (globalKeywordSearch.isNotBlank()) {
                        Text("Keyword Results (${globalSearchResults.size}):", fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 4.dp))
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(globalSearchResults) { record ->
                                Card(
                                    modifier = Modifier.fillMaxWidth().clickable { activeTraceRecord = record },
                                    colors = CardDefaults.cardColors(containerColor = if (record.status == "Entry Deleted") Color(0xFFFFEBEE) else MaterialTheme.colorScheme.surface)
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                            Text("File No: ${record.fileNo}", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                            Badge(containerColor = if (record.status == "Entry Deleted") Color.Red else MaterialTheme.colorScheme.primary) {
                                                Text(record.status, color = Color.White)
                                            }
                                        }
                                        Text("All Dates: ${record.dispatchDatesCsv}", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                                        Text("Court: ${record.courtNo} | Serial: ${record.serialNo}", fontSize = 12.sp)
                                        if (record.storageLocation.isNotBlank()) Text("Location: ${record.storageLocation}", fontSize = 12.sp)
                                        if (record.remarks.isNotBlank()) Text("Remarks: ${record.remarks}", fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)

                                        Row(modifier = Modifier.align(Alignment.End).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            IconButton(onClick = { PdfReportGenerator.generateSingleFileReport(context, record) }) {
                                                Icon(Icons.Default.Share, contentDescription = "PDF Sheet")
                                            }
                                            Button(onClick = { activeUpdateRecord = record }) {
                                                Text("Update Status")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        Text("Active Courts for $normalizedSearchDate (${searchCourtsList.size}):", fontWeight = FontWeight.Bold)
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            searchCourtsList.forEach { court ->
                                FilterChip(
                                    selected = searchSelectedCourt == court,
                                    onClick = { searchSelectedCourt = if (searchSelectedCourt == court) null else court },
                                    label = { Text("Court $court") }
                                )
                            }
                        }

                        val targetTitle = if (searchSelectedCourt != null) "Files in Court $searchSelectedCourt on $normalizedSearchDate" else "All Files Dispatched on $normalizedSearchDate"
                        Text(targetTitle, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 4.dp))

                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(searchCourtFiles) { record ->
                                Card(
                                    modifier = Modifier.fillMaxWidth().clickable { activeTraceRecord = record },
                                    colors = CardDefaults.cardColors(containerColor = if (record.status == "Entry Deleted") Color(0xFFFFEBEE) else MaterialTheme.colorScheme.surface)
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                            Text("File: ${record.fileNo}", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                            Badge(containerColor = if (record.status == "Entry Deleted") Color.Red else MaterialTheme.colorScheme.primary) {
                                                Text(record.status, color = Color.White)
                                            }
                                        }
                                        Text("Court No: ${record.courtNo} | Serial: ${record.serialNo}", fontSize = 12.sp)
                                        if (record.storageLocation.isNotBlank()) Text("Location: ${record.storageLocation}", fontSize = 12.sp, color = Color.DarkGray)
                                        if (record.remarks.isNotBlank()) Text("Remarks: ${record.remarks}", fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)

                                        Row(modifier = Modifier.align(Alignment.End).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            IconButton(onClick = { PdfReportGenerator.generateSingleFileReport(context, record) }) {
                                                Icon(Icons.Default.Share, contentDescription = "PDF Sheet")
                                            }
                                            Button(onClick = { activeUpdateRecord = record }) {
                                                Text("Update Status")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                } else if (currentView == "BULK") {
                    // Bulk Operations Screen (STRICTLY PRESERVED - NO REPORT GENERATION TRIGGERS)
                    Text("Select Date & Court No to Bulk Update", fontWeight = FontWeight.Bold)

                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = bulkDateInput,
                            onValueChange = { bulkDateInput = it },
                            label = { Text("Dispatch Date (DD-MM-YY)") },
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = bulkCourtNo,
                            onValueChange = { bulkCourtNo = it },
                            label = { Text("Court No") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                        Button(onClick = { selectedFileIds = bulkCourtFiles.map { it.id }.toSet() }) { Text("Select All") }
                        Button(onClick = { selectedFileIds = emptySet() }) { Text("Clear All") }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                        FilterChip(selected = bulkTargetStatus == "Taken Up", onClick = { bulkTargetStatus = "Taken Up" }, label = { Text("Taken Up") })
                        FilterChip(selected = bulkTargetStatus == "Received from Court", onClick = { bulkTargetStatus = "Received from Court" }, label = { Text("Received") })
                        FilterChip(selected = bulkTargetStatus == "Pass Over", onClick = { bulkTargetStatus = "Pass Over" }, label = { Text("Pass Over") })
                    }

                    Button(
                        enabled = selectedFileIds.isNotEmpty(),
                        onClick = {
                            scope.launch {
                                val selectedRecords = bulkCourtFiles.filter { selectedFileIds.contains(it.id) }
                                val updatedList = selectedRecords.map { rec ->
                                    val logDetail = " | Court No: ${rec.courtNo} | Serial: ${rec.serialNo}"
                                    rec.copy(
                                        status = bulkTargetStatus,
                                        historyLog = "${rec.historyLog}\n[$normalizedBulkDate] Bulk Status changed to '$bulkTargetStatus'$logDetail"
                                    )
                                }
                                dao.insertOrUpdateAll(updatedList)
                                selectedFileIds = emptySet()
                                Toast.makeText(context, "${updatedList.size} Files Updated!", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                    ) {
                        Text("BATCH UPDATE ${selectedFileIds.size} FILES")
                    }

                    LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(bulkCourtFiles) { record ->
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Checkbox(
                                    checked = selectedFileIds.contains(record.id),
                                    onCheckedChange = { isChecked ->
                                        selectedFileIds = if (isChecked) selectedFileIds + record.id else selectedFileIds - record.id
                                    }
                                )
                                Text("${record.fileNo} (${record.serialNo}) - Status: ${record.status}")
                            }
                        }
                    }

                } else {
                    // MAIN REGISTRATION SCREEN
                    Card(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), elevation = CardDefaults.cardElevation(4.dp)) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("☀️ Registration / Re-Dispatch", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)

                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                FilterChip(selected = selectedMode == "Dispatched", onClick = { selectedMode = "Dispatched" }, label = { Text("Dispatched") })
                                FilterChip(selected = selectedMode == "Not Sent", onClick = { selectedMode = "Not Sent" }, label = { Text("Not Sent") })
                                FilterChip(selected = selectedMode == "Chamber", onClick = { selectedMode = "Chamber" }, label = { Text("Chamber") })
                            }

                            OutlinedTextField(
                                value = dispatchDateInput,
                                onValueChange = { dispatchDateInput = it },
                                label = { Text("Dispatch Date (DD-MM-YY) *") },
                                trailingIcon = {
                                    TextButton(onClick = { dispatchDateInput = currentDate }) {
                                        Text("Today", fontSize = 11.sp)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                            )

                            OutlinedTextField(
                                value = fileNoInput,
                                onValueChange = { fileNoInput = it },
                                label = { Text("File Number (e.g. 1234/2026) *") },
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                            )

                            if (selectedMode == "Dispatched") {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(
                                        value = courtNoInput,
                                        onValueChange = { if (it.isEmpty() || it.toIntOrNull() != null) courtNoInput = it },
                                        label = { Text("Court No (Integer) *") },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        modifier = Modifier.weight(1f)
                                    )
                                    OutlinedTextField(
                                        value = serialNoInput,
                                        onValueChange = { if (it.isEmpty() || it.toFloatOrNull() != null) serialNoInput = it },
                                        label = { Text("Serial No (Decimal) *") },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                        modifier = Modifier.weight(1f)
                                    )
                                }

                                Text("List Type *", fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilterChip(selected = listTypeInput == "DCL", onClick = { listTypeInput = "DCL" }, label = { Text("DCL") })
                                    FilterChip(selected = listTypeInput == "ACL", onClick = { listTypeInput = "ACL" }, label = { Text("ACL") })
                                    FilterChip(selected = listTypeInput == "Correction", onClick = { listTypeInput = "Correction" }, label = { Text("Correction") })
                                }

                            } else if (selectedMode == "Chamber") {
                                OutlinedTextField(value = judgeNameInput, onValueChange = { judgeNameInput = it }, label = { Text("Hon'ble Judge Name *") }, modifier = Modifier.fillMaxWidth())
                            } else {
                                Box(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                    OutlinedTextField(
                                        value = storageLocationInput,
                                        onValueChange = { storageLocationInput = it },
                                        label = { Text("Storage Location (Optional)") },
                                        trailingIcon = {
                                            IconButton(onClick = { locationDropdownExpanded = true }) {
                                                Icon(Icons.Default.ArrowDropDown, contentDescription = "Select Location")
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    DropdownMenu(
                                        expanded = locationDropdownExpanded,
                                        onDismissRequest = { locationDropdownExpanded = false }
                                    ) {
                                        defaultLocations.forEach { loc ->
                                            DropdownMenuItem(
                                                text = { Text(loc) },
                                                onClick = { storageLocationInput = loc; locationDropdownExpanded = false }
                                            )
                                        }
                                    }
                                }
                            }

                            OutlinedTextField(
                                value = remarksInput,
                                onValueChange = { remarksInput = it },
                                label = { Text("Remarks / Case Notes (Optional)") },
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                            )

                            Button(
                                onClick = {
                                    val fileNoRegex = Regex("^\\d+\\/\\d+$")
                                    if (!fileNoRegex.matches(fileNoInput.trim())) {
                                        Toast.makeText(context, "Invalid File No! Must be format: [Number]/[Year] (e.g. 1234/2026)", Toast.LENGTH_LONG).show()
                                        return@Button
                                    }

                                    val cleanDate = normalizeDate(dispatchDateInput)

                                    if (cleanDate.isBlank() || fileNoInput.isBlank()) {
                                        Toast.makeText(context, "Please fill all mandatory fields (*)", Toast.LENGTH_SHORT).show()
                                        return@Button
                                    }
                                    if (selectedMode == "Dispatched" && (courtNoInput.isBlank() || serialNoInput.isBlank())) {
                                        Toast.makeText(context, "Court No and Serial No are mandatory!", Toast.LENGTH_SHORT).show()
                                        return@Button
                                    }

                                    scope.launch {
                                        val existing = dao.getRecordByFileNo(fileNoInput.trim())
                                        val newStatus = if (selectedMode == "Dispatched") "Dispatched" else if (selectedMode == "Chamber") "Sent to Chamber" else "Not Sent to Court"
                                        val isChamber = selectedMode == "Chamber"
                                        val judge = if (isChamber) judgeNameInput else ""

                                        val existingCsv = existing?.dispatchDatesCsv ?: ""
                                        val updatedCsv = when {
                                            existingCsv.isBlank() -> cleanDate
                                            existingCsv.contains(cleanDate) -> existingCsv
                                            else -> "$existingCsv, $cleanDate"
                                        }

                                        val serialFormatted = "$listTypeInput - ${serialNoInput.trim()}"
                                        val dispatchDetails = if (selectedMode == "Dispatched") " | Court No: ${courtNoInput.trim()} | Serial: $serialFormatted" else ""
                                        val logRemark = if (remarksInput.isNotBlank()) " | Remarks: $remarksInput" else ""

                                        // Audit stack trace stores date only (time omitted)
                                        val entryLog = "[$cleanDate] Registered as '$newStatus'$dispatchDetails${if (isChamber) " (Judge: $judge)" else ""}$logRemark"
                                        val updatedHistory = if (existing != null) "${existing.historyLog}\n$entryLog" else entryLog

                                        val record = FileRecord(
                                            id = existing?.id ?: 0,
                                            fileNo = fileNoInput.trim(),
                                            dispatchDate = cleanDate,
                                            dispatchDatesCsv = updatedCsv,
                                            courtNo = if (selectedMode == "Dispatched") courtNoInput.trim() else "N/A",
                                            serialNo = if (selectedMode == "Dispatched") serialFormatted else "",
                                            status = newStatus,
                                            storageLocation = if (isChamber) "Chamber: $judge" else storageLocationInput.trim(),
                                            sentToChamber = isChamber,
                                            judgeName = judge,
                                            remarks = remarksInput.trim(),
                                            historyLog = updatedHistory
                                        )
                                        dao.insertOrUpdateRecord(record)
                                        fileNoInput = ""; courtNoInput = ""; serialNoInput = ""; remarksInput = ""; storageLocationInput = ""
                                        Toast.makeText(context, "Record Saved Successfully!", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                            ) {
                                Text("SAVE RECORD")
                            }
                        }
                    }

                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(recordsList) { record ->
                            Card(
                                modifier = Modifier.fillMaxWidth().clickable { activeTraceRecord = record },
                                colors = CardDefaults.cardColors(containerColor = if (record.status == "Entry Deleted") Color(0xFFFFEBEE) else MaterialTheme.colorScheme.surface)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text("File: ${record.fileNo}", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                        Badge(containerColor = if (record.status == "Entry Deleted") Color.Red else if (record.sentToChamber) Color(0xFF9C27B0) else MaterialTheme.colorScheme.primary) {
                                            Text(if (record.sentToChamber) "Chamber: ${record.judgeName}" else record.status, color = Color.White)
                                        }
                                    }
                                    if (record.courtNo != "N/A") Text("Court: ${record.courtNo} | ${record.serialNo}", fontSize = 12.sp)
                                    if (record.storageLocation.isNotBlank()) Text("Location: ${record.storageLocation}", fontSize = 12.sp, color = Color.DarkGray)
                                    if (record.remarks.isNotBlank()) Text("📝 ${record.remarks}", fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)

                                    Row(modifier = Modifier.align(Alignment.End).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        IconButton(onClick = { PdfReportGenerator.generateSingleFileReport(context, record) }) {
                                            Icon(Icons.Default.Share, contentDescription = "Case PDF Sheet")
                                        }
                                        Button(onClick = { activeUpdateRecord = record }) {
                                            Text("Update Status")
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

    // Disposal Update Modal
    val currentRecordForUpdate = activeUpdateRecord
    if (currentRecordForUpdate != null) {
        var newStatus by remember { mutableStateOf("Taken Up") }
        var locInput by remember { mutableStateOf("") }
        var remarksUpdate by remember { mutableStateOf(currentRecordForUpdate.remarks) }
        var deleteReason by remember { mutableStateOf("") }

        val isDeleteMode = newStatus == "Entry Deleted"
        val isSaveEnabled = !isDeleteMode || deleteReason.isNotBlank()

        AlertDialog(
            onDismissRequest = { activeUpdateRecord = null },
            title = { Text("Update Disposal: ${currentRecordForUpdate.fileNo}") },
            text = {
                Column {
                    Text("Select Target Status:")
                    val options = listOf("Taken Up", "Pass Over", "Received from Court", "Not Sent to Court", "Entry Deleted")
                    options.forEach { opt ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = newStatus == opt, onClick = { newStatus = opt })
                            Text(opt)
                        }
                    }

                    if (isDeleteMode) {
                        OutlinedTextField(
                            value = deleteReason,
                            onValueChange = { deleteReason = it },
                            label = { Text("Mandatory Reason for Deletion *") },
                            isError = deleteReason.isBlank(),
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                        )
                    } else {
                        OutlinedTextField(
                            value = locInput,
                            onValueChange = { locInput = it },
                            label = { Text("Location (Shelf/Bundle/Seat/Chamber)") },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                        )
                    }

                    OutlinedTextField(
                        value = remarksUpdate,
                        onValueChange = { remarksUpdate = it },
                        label = { Text("Remarks") },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = isSaveEnabled,
                    onClick = {
                        scope.launch {
                            val courtInfoLog = if (currentRecordForUpdate.courtNo != "N/A") " | Court No: ${currentRecordForUpdate.courtNo} | Serial: ${currentRecordForUpdate.serialNo}" else ""
                            // Audit log stores date only (time omitted)
                            val logEntry = "[$dispatchDateInput] Status changed to '$newStatus'$courtInfoLog ${if (isDeleteMode) "Reason: $deleteReason" else "Loc: $locInput"}"

                            val updated = currentRecordForUpdate.copy(
                                status = newStatus,
                                storageLocation = if (isDeleteMode) "DELETED" else locInput,
                                sentToChamber = false,
                                judgeName = "",
                                remarks = remarksUpdate,
                                historyLog = "${currentRecordForUpdate.historyLog}\n$logEntry"
                            )
                            dao.insertOrUpdateRecord(updated)
                            activeUpdateRecord = null
                        }
                    }
                ) { Text("Save Changes") }
            }
        )
    }

    // Audit Trace Dialog
    val currentRecordForTrace = activeTraceRecord
    if (currentRecordForTrace != null) {
        AlertDialog(
            onDismissRequest = { activeTraceRecord = null },
            title = { Text("Audit Stack Trace: ${currentRecordForTrace.fileNo}") },
            text = {
                LazyColumn(modifier = Modifier.height(250.dp)) {
                    item {
                        Text(
                            text = currentRecordForTrace.historyLog.ifEmpty { "No History Log" },
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClick = { activeTraceRecord = null }) { Text("Close") }
            }
        )
    }
}
