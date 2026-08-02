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

    val currentDate = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date()) }

    // Form States
    var selectedMode by remember { mutableStateOf("Dispatched") }
    var courtNoInput by remember { mutableStateOf("") }
    var listTypeInput by remember { mutableStateOf("DCL") }
    var serialNoInput by remember { mutableStateOf("") }
    var fileNoInput by remember { mutableStateOf("") }
    var remarksInput by remember { mutableStateOf("") }
    var judgeNameInput by remember { mutableStateOf("") }
    var storageLocationInput by remember { mutableStateOf("Shelf") }

    // Search & Filter
    var searchQuery by remember { mutableStateOf("") }
    var filterDate by remember { mutableStateOf(currentDate) }

    // Bulk Mode
    var isBulkMode by remember { mutableStateOf(false) }

    // Dialog States
    var activeTraceRecord by remember { mutableStateOf<FileRecord?>(null) }
    var activeUpdateRecord by remember { mutableStateOf<FileRecord?>(null) }

    val recordsFlow = if (searchQuery.isNotBlank()) dao.searchRecords(searchQuery) else dao.getRecordsByDate(filterDate)
    val recordsList by recordsFlow.collectAsState(initial = emptyList())
    val allRecords by dao.getAllRecords().collectAsState(initial = emptyList())

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Court File Tracker Menu", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                    NavigationDrawerItem(
                        label = { Text("⚡ Bulk Operations") },
                        selected = isBulkMode,
                        onClick = { isBulkMode = !isBulkMode; scope.launch { drawerState.close() } },
                        icon = { Icon(Icons.Default.List, contentDescription = null) }
                    )
                    NavigationDrawerItem(
                        label = { Text("📄 Master PDF Report") },
                        selected = false,
                        onClick = {
                            PdfReportGenerator.generateAndShareReport(context, "Master Database Ledger", allRecords, isMasterReport = true)
                            scope.launch { drawerState.close() }
                        },
                        icon = { Icon(Icons.Default.Share, contentDescription = null) }
                    )
                    NavigationDrawerItem(
                        label = { Text("📄 Taken Up Files PDF") },
                        selected = false,
                        onClick = {
                            scope.launch {
                                dao.getTakenUpRecords().collect { takenUp ->
                                    PdfReportGenerator.generateAndShareReport(context, "All Taken Up Files", takenUp)
                                }
                            }
                            scope.launch { drawerState.close() }
                        },
                        icon = { Icon(Icons.Default.CheckCircle, contentDescription = null) }
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
                    title = { Text("Allahabad High Court File Tracker", fontSize = 16.sp) },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "Menu")
                        }
                    }
                )
            }
        ) { padding ->
            Column(modifier = Modifier.padding(padding).padding(12.dp)) {

                // Registration Card
                Card(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), elevation = CardDefaults.cardElevation(4.dp)) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("☀️ Registration / Re-Dispatch", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            FilterChip(selected = selectedMode == "Dispatched", onClick = { selectedMode = "Dispatched" }, label = { Text("Dispatched") })
                            FilterChip(selected = selectedMode == "Not Sent", onClick = { selectedMode = "Not Sent" }, label = { Text("Not Sent") })
                            FilterChip(selected = selectedMode == "Chamber", onClick = { selectedMode = "Chamber" }, label = { Text("Chamber") })
                        }

                        OutlinedTextField(value = fileNoInput, onValueChange = { fileNoInput = it }, label = { Text("File Number (e.g. 1234/2026)") }, modifier = Modifier.fillMaxWidth())

                        if (selectedMode == "Dispatched") {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(value = courtNoInput, onValueChange = { courtNoInput = it }, label = { Text("Court No") }, modifier = Modifier.weight(1f))
                                OutlinedTextField(value = serialNoInput, onValueChange = { serialNoInput = it }, label = { Text("Serial No") }, modifier = Modifier.weight(1f))
                            }
                        } else if (selectedMode == "Chamber") {
                            OutlinedTextField(value = judgeNameInput, onValueChange = { judgeNameInput = it }, label = { Text("Hon'ble Judge Name") }, modifier = Modifier.fillMaxWidth())
                        } else {
                            OutlinedTextField(value = storageLocationInput, onValueChange = { storageLocationInput = it }, label = { Text("Storage Location (Shelf/Bundle/Person)") }, modifier = Modifier.fillMaxWidth())
                        }

                        OutlinedTextField(value = remarksInput, onValueChange = { remarksInput = it }, label = { Text("Remarks / Case Notes") }, modifier = Modifier.fillMaxWidth())

                        Button(
                            onClick = {
                                if (fileNoInput.isBlank()) return@Button
                                scope.launch {
                                    val existing = dao.getRecordByFileNo(fileNoInput)
                                    val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                                    val newStatus = if (selectedMode == "Dispatched") "Dispatched" else if (selectedMode == "Chamber") "Sent to Chamber" else "Not Sent to Court"
                                    val isChamber = selectedMode == "Chamber"
                                    val judge = if (isChamber) judgeNameInput else ""

                                    val entryLog = "[$filterDate $time] Registered as '$newStatus' ${if (isChamber) "($judge)" else ""} | Remarks: $remarksInput"
                                    val updatedHistory = if (existing != null) "${existing.historyLog}\n$entryLog" else entryLog

                                    val record = FileRecord(
                                        id = existing?.id ?: 0,
                                        fileNo = fileNoInput,
                                        dispatchDate = filterDate,
                                        courtNo = if (selectedMode == "Dispatched") courtNoInput else "N/A",
                                        serialNo = if (selectedMode == "Dispatched") "$listTypeInput - $serialNoInput" else "",
                                        status = newStatus,
                                        storageLocation = if (isChamber) "Chamber: $judge" else storageLocationInput,
                                        sentToChamber = isChamber,
                                        judgeName = judge,
                                        remarks = remarksInput,
                                        historyLog = updatedHistory
                                    )
                                    dao.insertOrUpdateRecord(record)
                                    fileNoInput = ""; courtNoInput = ""; serialNoInput = ""; remarksInput = ""
                                    Toast.makeText(context, "Record Saved!", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                        ) {
                            Text("SAVE / UPDATE RECORD")
                        }
                    }
                }

                // Search Bar
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text("🔍 Search File, Court, Status, Location") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                )

                // List Feed
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

                                Button(
                                    onClick = { activeUpdateRecord = record },
                                    modifier = Modifier.align(Alignment.End).padding(top = 4.dp)
                                ) {
                                    Text("Update Status")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Status Update Modal Dialog
    val currentRecordForUpdate = activeUpdateRecord
    if (currentRecordForUpdate != null) {
        var newStatus by remember { mutableStateOf("Taken Up") }
        var locInput by remember { mutableStateOf("") }
        var remarksUpdate by remember { mutableStateOf(currentRecordForUpdate.remarks) }
        var deleteReason by remember { mutableStateOf("") }

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
                    if (newStatus == "Entry Deleted") {
                        OutlinedTextField(value = deleteReason, onValueChange = { deleteReason = it }, label = { Text("Mandatory Reason for Deletion") })
                    } else {
                        OutlinedTextField(value = locInput, onValueChange = { locInput = it }, label = { Text("Location (Shelf/Bundle/Seat/Chamber)") })
                    }
                    OutlinedTextField(value = remarksUpdate, onValueChange = { remarksUpdate = it }, label = { Text("Remarks") })
                }
            },
            confirmButton = {
                Button(onClick = {
                    scope.launch {
                        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                        val logEntry = "[$filterDate $time] Status changed to '$newStatus' (Loc: $locInput) ${if (deleteReason.isNotBlank()) "Reason: $deleteReason" else ""}"

                        // Chamber Flag Auto-Reset Rule applied: sentToChamber = false, judgeName = ""
                        val updated = currentRecordForUpdate.copy(
                            status = newStatus,
                            storageLocation = locInput,
                            sentToChamber = false,
                            judgeName = "",
                            remarks = remarksUpdate,
                            historyLog = "${currentRecordForUpdate.historyLog}\n$logEntry"
                        )
                        dao.insertOrUpdateRecord(updated)
                        activeUpdateRecord = null
                    }
                }) { Text("Save Changes") }
            }
        )
    }

    // Audit Stack Trace Modal Dialog
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
