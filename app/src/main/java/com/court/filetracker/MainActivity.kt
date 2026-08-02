package com.court.filetracker

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
                    onBackup = { DriveServiceHelper.performBackup(this) },
                    onRestore = { DriveServiceHelper.performRestore(this) {} }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppScreen(dao: FileRecordDao, onBackup: () -> Unit, onRestore: () -> Unit) {
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
    var storageLocationInput by remember { mutableStateOf("Shelf") }

    // Navigation & View States
    var currentView by remember { mutableStateOf("MAIN") } // MAIN, SEARCH_DATE_COURT, BULK
    var searchDateInput by remember { mutableStateOf(currentDate) }
    var searchSelectedCourt by remember { mutableStateOf<String?>(null) }
    var globalKeywordSearch by remember { mutableStateOf("") }

    // Bulk Mode States
    var bulkCourtNo by remember { mutableStateOf("") }
    var bulkTargetStatus by remember { mutableStateOf("Taken Up") }
    var selectedFileIds by remember { mutableStateOf(setOf<Long>()) }

    // Dialog States
    var activeTraceRecord by remember { mutableStateOf<FileRecord?>(null) }
    var activeUpdateRecord by remember { mutableStateOf<FileRecord?>(null) }

    // Data Flows
    val recordsList by dao.getRecordsByDate(dispatchDateInput).collectAsState(initial = emptyList())
    val searchCourtsList by dao.getCourtsByDate(searchDateInput).collectAsState(initial = emptyList())
    val searchCourtFiles by (searchSelectedCourt?.let { dao.getRecordsByDateAndCourt(searchDateInput, it) } ?: dao.getRecordsByDate(searchDateInput)).collectAsState(initial = emptyList())
    val globalSearchResults by dao.searchRecords(globalKeywordSearch).collectAsState(initial = emptyList())
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
                        label = { Text("📄 Master Database PDF Report") },
                        selected = false,
                        onClick = {
                            PdfReportGenerator.generateMasterReport(context, allRecords)
                            scope.launch { drawerState.close() }
                        },
                        icon = { Icon(Icons.Default.Share, contentDescription = null) }
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
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
                                "BULK" -> "⚡ Bulk Court Operations"
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

                if (currentView == "SEARCH_DATE_COURT") {
                    // STRUCTURED SEARCH VIEW: Filter by Date -> Select Court -> View Files
                    OutlinedTextField(
                        value = searchDateInput,
                        onValueChange = { searchDateInput = it; searchSelectedCourt = null },
                        label = { Text("Select Date (DD-MM-YY)") },
                        leadingIcon = { Icon(Icons.Default.DateRange, contentDescription = null) },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                    )

                    OutlinedTextField(
                        value = globalKeywordSearch,
                        onValueChange = { globalKeywordSearch = it },
                        label = { Text("Or Keyword Search (File No, Status, Remark)") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                    )

                    if (globalKeywordSearch.isNotBlank()) {
                        Text("Keyword Results (${globalSearchResults.size}):", fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 4.dp))
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(globalSearchResults) { record ->
                                Card(modifier = Modifier.fillMaxWidth().clickable { activeTraceRecord = record }) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                            Text("File No: ${record.fileNo}", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                            IconButton(onClick = { PdfReportGenerator.generateSingleFileReport(context, record) }) {
                                                Icon(Icons.Default.Share, contentDescription = "Single Case PDF")
                                            }
                                        }
                                        Text("Date: ${record.dispatchDate} | Court: ${record.courtNo} (${record.serialNo})", fontSize = 12.sp)
                                        Text("Status: ${record.status} | Location: ${record.storageLocation}", fontSize = 12.sp)
                                        if (record.remarks.isNotBlank()) Text("Remarks: ${record.remarks}", fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)
                                    }
                                }
                            }
                        }
                    } else {
                        Text("Active Courts for $searchDateInput (${searchCourtsList.size}):", fontWeight = FontWeight.Bold)
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            searchCourtsList.forEach { court ->
                                FilterChip(
                                    selected = searchSelectedCourt == court,
                                    onClick = { searchSelectedCourt = if (searchSelectedCourt == court) null else court },
                                    label = { Text("Court $court") }
                                )
                            }
                        }

                        if (searchSelectedCourt != null) {
                            Button(
                                onClick = { PdfReportGenerator.generateDateCourtReport(context, searchDateInput, searchSelectedCourt!!, searchCourtFiles) },
                                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                            ) {
                                Text("📄 Export Court ${searchSelectedCourt!!} PDF Report")
                            }
                        }

                        val targetTitle = if (searchSelectedCourt != null) "Files in Court $searchSelectedCourt on $searchDateInput" else "All Files Dispatched on $searchDateInput"
                        Text(targetTitle, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 4.dp))

                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(searchCourtFiles) { record ->
                                Card(modifier = Modifier.fillMaxWidth().clickable { activeTraceRecord = record }) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                            Text("File: ${record.fileNo}", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                            Badge { Text(record.status) }
                                        }
                                        Text("Court No: ${record.courtNo} | Serial: ${record.serialNo}", fontSize = 12.sp)
                                        if (record.storageLocation.isNotBlank()) Text("Location: ${record.storageLocation}", fontSize = 12.sp, color = Color.DarkGray)
                                        if (record.remarks.isNotBlank()) Text("Remarks: ${record.remarks}", fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)
                                    }
                                }
                            }
                        }
                    }

                } else if (currentView == "BULK") {
                    // FUNCTIONAL BULK OPERATIONS VIEW
                    Text("Select Court & Update All Files", fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = bulkCourtNo,
                        onValueChange = { bulkCourtNo = it },
                        label = { Text("Target Court No.") },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                    )

                    val bulkFiles = recordsList.filter { it.courtNo == bulkCourtNo && it.courtNo != "N/A" }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                        Button(onClick = { selectedFileIds = bulkFiles.map { it.id }.toSet() }) { Text("Select All") }
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
                                val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                                val selectedRecords = bulkFiles.filter { selectedFileIds.contains(it.id) }
                                val updatedList = selectedRecords.map { rec ->
                                    rec.copy(
                                        status = bulkTargetStatus,
                                        historyLog = "${rec.historyLog}\n[$dispatchDateInput $time] Bulk Status changed to '$bulkTargetStatus'"
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
                        items(bulkFiles) { record ->
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
                    // Main Registration & Listing Screen
                    Card(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), elevation = CardDefaults.cardElevation(4.dp)) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("☀️ Registration / Re-Dispatch", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)

                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                FilterChip(selected = selectedMode == "Dispatched", onClick = { selectedMode = "Dispatched" }, label = { Text("Dispatched") })
                                FilterChip(selected = selectedMode == "Not Sent", onClick = { selectedMode = "Not Sent" }, label = { Text("Not Sent") })
                                FilterChip(selected = selectedMode == "Chamber", onClick = { selectedMode = "Chamber" }, label = { Text("Chamber") })
                            }

                            // Mandatory Dispatch Date
                            OutlinedTextField(
                                value = dispatchDateInput,
                                onValueChange = { dispatchDateInput = it },
                                label = { Text("Dispatch Date (DD-MM-YY) *") },
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                            )

                            // Mandatory File Number with Regex Rule [Number]/[Year]
                            OutlinedTextField(
                                value = fileNoInput,
                                onValueChange = { fileNoInput = it },
                                label = { Text("File Number (e.g. 1234/2026) *") },
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                            )

                            if (selectedMode == "Dispatched") {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(value = courtNoInput, onValueChange = { courtNoInput = it }, label = { Text("Court No *") }, modifier = Modifier.weight(1f))
                                    OutlinedTextField(value = serialNoInput, onValueChange = { serialNoInput = it }, label = { Text("Serial No *") }, modifier = Modifier.weight(1f))
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
                                OutlinedTextField(value = storageLocationInput, onValueChange = { storageLocationInput = it }, label = { Text("Storage Location (Shelf/Bundle/Person) *") }, modifier = Modifier.fillMaxWidth())
                            }

                            // Optional Remarks
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

                                    if (dispatchDateInput.isBlank() || fileNoInput.isBlank()) {
                                        Toast.makeText(context, "Please fill all mandatory fields (*)", Toast.LENGTH_SHORT).show()
                                        return@Button
                                    }
                                    if (selectedMode == "Dispatched" && (courtNoInput.isBlank() || serialNoInput.isBlank())) {
                                        Toast.makeText(context, "Court No and Serial No are mandatory!", Toast.LENGTH_SHORT).show()
                                        return@Button
                                    }
                                    if (selectedMode == "Chamber" && judgeNameInput.isBlank()) {
                                        Toast.makeText(context, "Judge Name is mandatory!", Toast.LENGTH_SHORT).show()
                                        return@Button
                                    }

                                    scope.launch {
                                        val existing = dao.getRecordByFileNo(fileNoInput)
                                        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                                        val newStatus = if (selectedMode == "Dispatched") "Dispatched" else if (selectedMode == "Chamber") "Sent to Chamber" else "Not Sent to Court"
                                        val isChamber = selectedMode == "Chamber"
                                        val judge = if (isChamber) judgeNameInput else ""

                                        val logRemark = if (remarksInput.isNotBlank()) " | Remarks: $remarksInput" else ""
                                        val entryLog = "[$dispatchDateInput $time] Registered as '$newStatus' ${if (isChamber) "($judge)" else ""}$logRemark"
                                        val updatedHistory = if (existing != null) "${existing.historyLog}\n$entryLog" else entryLog

                                        val record = FileRecord(
                                            id = existing?.id ?: 0,
                                            fileNo = fileNoInput,
                                            dispatchDate = dispatchDateInput,
                                            courtNo = if (selectedMode == "Dispatched") courtNoInput else "N/A",
                                            serialNo = if (selectedMode == "Dispatched") "$listTypeInput - $serialNoInput" else "",
                                            status = newStatus,
                                            storageLocation = if (isChamber) "Chamber: $judge" else storageLocationInput,
                                            sentToChamber = isChamber,
                                            judgeName = judge,
                                            remarks = remarksInput, // No default string
                                            historyLog = updatedHistory
                                        )
                                        dao.insertOrUpdateRecord(record)
                                        fileNoInput = ""; courtNoInput = ""; serialNoInput = ""; remarksInput = ""
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
                            Card(modifier = Modifier.fillMaxWidth().clickable { activeTraceRecord = record }) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text("File: ${record.fileNo}", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                        Badge(containerColor = if (record.sentToChamber) Color(0xFF9C27B0) else MaterialTheme.colorScheme.primary) {
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

    // Disposal Update Modal (Mandatory Delete Reason Enforcement)
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
                    enabled = isSaveEnabled, // Disables button if delete reason is empty
                    onClick = {
                        scope.launch {
                            val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                            val logEntry = "[$dispatchDateInput $time] Status changed to '$newStatus' ${if (isDeleteMode) "Reason: $deleteReason" else "Loc: $locInput"}"

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
