package com.court.filetracker

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.util.Base64
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
import androidx.core.content.FileProvider
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
                    dao = fileDao,
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

fun normalizeDate(input: String): String = input.trim()
fun normalizeSearchQuery(input: String): String = input.trim()
fun stripLeadingZeros(input: String): String = input.trim().trimStart('0').ifEmpty { "0" }

/**
 * True Historical Multi-Date Dispatch Tracking Engine
 * Scans the immutable audit log for dispatch events matching [targetDate].
 * Even if a file's current status is "Not Sent to Court" or moved elsewhere today,
 * if it was dispatched on targetDate, it will correctly appear.
 */
fun getDispatchedCourtsForDate(record: FileRecord, targetDate: String): Set<String> {
    val courtsFound = mutableSetOf<String>()
    val targetTag = "[$targetDate]"
    val logLines = record.historyLog.split("\n")

    for (line in logLines) {
        if (line.contains(targetTag)) {
            if (line.contains("Registered as 'Dispatched'") ||
                line.contains("Dispatched to Court") ||
                line.contains("Dispatched via Cause List") ||
                line.contains("Court No:") ||
                line.contains("Court:")
            ) {
                val match = Regex("(?i)Court(?:\\s*No)?:?\\s*(\\d+)").find(line)
                if (match != null) {
                    courtsFound.add(stripLeadingZeros(match.groupValues[1]))
                }
            }
        }
    }

    // Fallback check against dispatchDatesCsv if logged dates exist
    if (courtsFound.isEmpty() && record.dispatchDatesCsv.split(",").map { it.trim() }.contains(targetDate)) {
        if (record.courtNo != "N/A" && record.courtNo.isNotBlank()) {
            courtsFound.add(stripLeadingZeros(record.courtNo))
        }
    }

    return courtsFound
}

fun wasDispatchedToCourtOnDate(record: FileRecord, targetDate: String, targetCourt: String): Boolean {
    val cleanCourt = stripLeadingZeros(targetCourt)
    return getDispatchedCourtsForDate(record, targetDate).contains(cleanCourt)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppScreen(
    dao: FileRecordDao,
    causeListDao: CauseListDao,
    onPickPdf: ((Uri) -> Unit) -> Unit,
    onPickJson: ((Uri) -> Unit) -> Unit
) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val currentDate = remember { SimpleDateFormat("dd-MM-yy", Locale.getDefault()).format(Date()) }

    var currentView by remember { mutableStateOf("MAIN") }
    var isDrawerLocked by remember { mutableStateOf(false) }

    var selectedMode by remember { mutableStateOf("Dispatched") }
    var dispatchDateInput by remember { mutableStateOf(currentDate) }
    var courtNoInput by remember { mutableStateOf("") }
    var listTypeInput by remember { mutableStateOf("DCL") }
    var serialNoInput by remember { mutableStateOf("") }
    var fileSerialInput by remember { mutableStateOf("") }
    var fileYearInput by remember { mutableStateOf("2026") }
    var remarksInput by remember { mutableStateOf("") }
    var judgeNameInput by remember { mutableStateOf("") }

    var activeSearchOption by remember { mutableStateOf("NONE") }
    var searchDateInput by remember { mutableStateOf(currentDate) }
    var searchSelectedCourt by remember { mutableStateOf<String?>(null) }
    var searchFileNoInput by remember { mutableStateOf("") }
    var searchCategory by remember { mutableStateOf("LOCATION") }
    var searchLocOption by remember { mutableStateOf("Listing Seat") }
    var searchCustomLocText by remember { mutableStateOf("") }
    var searchJudgeTextInput by remember { mutableStateOf("") }
    var searchRemarksTextInput by remember { mutableStateOf("") }
    var searchStatusOption by remember { mutableStateOf("Dispatched") }
    var searchDateInterlocator by remember { mutableStateOf("") }

    var bulkDateInput by remember { mutableStateOf(currentDate) }
    var bulkSelectedCourtChip by remember { mutableStateOf<String?>(null) }
    var bulkTargetStatus by remember { mutableStateOf("Taken Up") }
    var selectedFileIds by remember { mutableStateOf(setOf<Long>()) }
    var showBulkReceivedDialog by remember { mutableStateOf(false) }

    var bulkLocationCategory by remember { mutableStateOf("PASS_OVER") }
    var bulkLocSelectedIds by remember { mutableStateOf(setOf<Long>()) }
    var showSetLocationDialog by remember { mutableStateOf(false) }

    var reportTargetFileNo by remember { mutableStateOf("") }
    var reportTargetDate by remember { mutableStateOf(currentDate) }
    var reportSelectedCourtChip by remember { mutableStateOf<String?>(null) }

    var addClCourtInput by remember { mutableStateOf("") }
    var addClDateInput by remember { mutableStateOf(currentDate) }
    var isClWebActive by remember { mutableStateOf(false) }
    var dispatchClDateInput by remember { mutableStateOf(currentDate) }
    var selectedClCourtChip by remember { mutableStateOf<String?>(null) }
    var clSearchQuery by remember { mutableStateOf("") }

    var activeTraceRecord by remember { mutableStateOf<FileRecord?>(null) }
    var activeUpdateRecord by remember { mutableStateOf<FileRecord?>(null) }
    var targetFileForMetaData by remember { mutableStateOf<FileRecord?>(null) }
    var showFlushDialog by remember { mutableStateOf(false) }

    val normalizedSearchDate = remember(searchDateInput) { normalizeDate(searchDateInput) }
    val normalizedBulkDate = remember(bulkDateInput) { normalizeDate(bulkDateInput) }
    val normalizedReportDate = remember(reportTargetDate) { normalizeDate(reportTargetDate) }
    val normalizedSearchFileNo = remember(searchFileNoInput) { normalizeSearchQuery(searchFileNoInput) }
    val normalizedInterlocatorDate = remember(searchDateInterlocator) { if (searchDateInterlocator.isBlank()) "" else normalizeDate(searchDateInterlocator) }

    LaunchedEffect(Unit) {
        scope.launch(Dispatchers.IO) {
            val records = dao.getAllRecords().first()
            val dirty = records.filter { it.status == "Cause List Identified" }
            if (dirty.isNotEmpty()) {
                val sanitized = dirty.map {
                    it.copy(
                        dispatchDate = "",
                        dispatchDatesCsv = "",
                        courtNo = "N/A",
                        serialNo = "",
                        status = "Unassigned"
                    )
                }
                dao.insertOrUpdateAll(sanitized)
            }
        }
    }

    val allDbRecords by dao.getAllRecords().collectAsState(initial = emptyList())

    val searchCourtsList = remember(allDbRecords, normalizedSearchDate) {
        allDbRecords.flatMap { getDispatchedCourtsForDate(it, normalizedSearchDate) }
            .distinct()
            .sortedBy { it.toIntOrNull() ?: 999 }
    }
    val searchCourtFiles = remember(allDbRecords, searchSelectedCourt, normalizedSearchDate) {
        if (searchSelectedCourt == null) emptyList()
        else allDbRecords.filter { wasDispatchedToCourtOnDate(it, normalizedSearchDate, searchSelectedCourt!!) }
    }

    val bulkCourtsList = remember(allDbRecords, normalizedBulkDate) {
        allDbRecords.flatMap { getDispatchedCourtsForDate(it, normalizedBulkDate) }
            .distinct()
            .sortedBy { it.toIntOrNull() ?: 999 }
    }
    val bulkCourtFiles = remember(allDbRecords, bulkSelectedCourtChip, normalizedBulkDate) {
        if (bulkSelectedCourtChip == null) emptyList()
        else allDbRecords.filter { wasDispatchedToCourtOnDate(it, normalizedBulkDate, bulkSelectedCourtChip!!) }
    }

    val reportCourtsList = remember(allDbRecords, normalizedReportDate) {
        allDbRecords.flatMap { getDispatchedCourtsForDate(it, normalizedReportDate) }
            .distinct()
            .sortedBy { it.toIntOrNull() ?: 999 }
    }
    val reportCourtFiles = remember(allDbRecords, reportSelectedCourtChip, normalizedReportDate) {
        if (reportSelectedCourtChip == null) emptyList()
        else allDbRecords.filter { wasDispatchedToCourtOnDate(it, normalizedReportDate, reportSelectedCourtChip!!) }
    }

    val fileNoSearchResults by dao.searchRecords(normalizedSearchFileNo).collectAsState(initial = emptyList())
    val chamberFiles = remember(allDbRecords) { allDbRecords.filter { it.sentToChamber || it.status.contains("Chamber", ignoreCase = true) } }
    val takenUpFiles = remember(allDbRecords) { allDbRecords.filter { it.status == "Taken Up" } }

    val advancedSearchResults = remember(
        allDbRecords, searchCategory, searchLocOption, searchCustomLocText, 
        searchJudgeTextInput, searchRemarksTextInput, searchStatusOption, normalizedInterlocatorDate
    ) {
        allDbRecords.filter { rec ->
            val matchesCategory = when (searchCategory) {
                "LOCATION" -> {
                    val targetLoc = if (searchLocOption == "Other") searchCustomLocText.trim() else searchLocOption
                    if (targetLoc.isBlank()) false
                    else rec.storageLocation.contains(targetLoc, ignoreCase = true)
                }
                "JUDGE" -> {
                    val query = searchJudgeTextInput.trim()
                    if (query.isBlank()) false
                    else rec.judgeName.contains(query, ignoreCase = true) || rec.historyLog.contains(query, ignoreCase = true)
                }
                "REMARKS" -> {
                    val query = searchRemarksTextInput.trim()
                    if (query.isBlank()) false
                    else rec.remarks.contains(query, ignoreCase = true) || rec.historyLog.contains(query, ignoreCase = true)
                }
                "STATUS" -> rec.status == searchStatusOption
                else -> false
            }

            if (!matchesCategory) return@filter false

            if (normalizedInterlocatorDate.isNotBlank()) {
                val targetDateTag = "[$normalizedInterlocatorDate]"
                val hasLogEntryOnDate = rec.historyLog.split("\n").any { line ->
                    if (!line.contains(targetDateTag)) return@any false
                    when (searchCategory) {
                        "LOCATION" -> {
                            val locVal = if (searchLocOption == "Other") searchCustomLocText.trim() else searchLocOption
                            line.contains("Loc:", ignoreCase = true) && line.contains(locVal, ignoreCase = true)
                        }
                        "JUDGE" -> line.contains("Judge:", ignoreCase = true) && line.contains(searchJudgeTextInput.trim(), ignoreCase = true)
                        "REMARKS" -> line.contains("Remarks:", ignoreCase = true) && line.contains(searchRemarksTextInput.trim(), ignoreCase = true)
                        "STATUS" -> line.contains("Status changed to '$searchStatusOption'", ignoreCase = true) || line.contains("Registered as '$searchStatusOption'", ignoreCase = true)
                        else -> false
                    }
                }
                hasLogEntryOnDate || (rec.dispatchDate == normalizedInterlocatorDate)
            } else {
                true
            }
        }
    }

    val bulkLocationFilteredFiles = remember(allDbRecords, bulkLocationCategory) {
        allDbRecords.filter { rec ->
            val isLocEmpty = rec.storageLocation.isBlank() || rec.storageLocation.trim().equals("N/A", ignoreCase = true)
            val matchesStatus = when (bulkLocationCategory) {
                "PASS_OVER" -> rec.status == "Pass Over"
                "NOT_SENT" -> rec.status == "Not Sent to Court"
                "RECEIVED" -> rec.status == "Received from Court"
                else -> false
            }
            matchesStatus && isLocEmpty
        }
    }

    val courtsWithClForDate by causeListDao.getCourtsForDate(dispatchClDateInput).collectAsState(initial = emptyList())
    val activeCourtCases by causeListDao.getCasesForCourtAndDate(
        dispatchClDateInput,
        selectedClCourtChip ?: ""
    ).collectAsState(initial = emptyList())

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = !isDrawerLocked,
        drawerContent = {
            ModalDrawerSheet {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Tracker Pro Menu", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                    NavigationDrawerItem(
                        label = { Text("Filter / Search Records") },
                        selected = currentView == "SEARCH_MENU",
                        onClick = { 
                            currentView = "SEARCH_MENU"
                            activeSearchOption = "NONE"
                            searchSelectedCourt = null
                            scope.launch { drawerState.close() } 
                        },
                        icon = { Icon(Icons.Default.Search, contentDescription = null) }
                    )
                    NavigationDrawerItem(
                        label = { Text("Bulk Operations") },
                        selected = currentView == "BULK",
                        onClick = { 
                            currentView = "BULK"
                            bulkSelectedCourtChip = null
                            scope.launch { drawerState.close() } 
                        },
                        icon = { Icon(Icons.Default.List, contentDescription = null) }
                    )
                    NavigationDrawerItem(
                        label = { Text("Bulk Location") },
                        selected = currentView == "BULK_LOCATION",
                        onClick = {
                            currentView = "BULK_LOCATION"
                            bulkLocSelectedIds = emptySet()
                            scope.launch { drawerState.close() }
                        },
                        icon = { Icon(Icons.Default.Place, contentDescription = null) }
                    )
                    NavigationDrawerItem(
                        label = { Text("Cause List Portal") },
                        selected = currentView == "CAUSE_LIST_PORTAL",
                        onClick = {
                            currentView = "CAUSE_LIST_PORTAL"
                            scope.launch { drawerState.close() }
                        },
                        icon = { Icon(Icons.Default.Info, contentDescription = null) }
                    )
                    NavigationDrawerItem(
                        label = { Text("Add Cause List (From Web)") },
                        selected = currentView == "ADD_CAUSE_LIST",
                        onClick = { 
                            currentView = "ADD_CAUSE_LIST"
                            isClWebActive = false
                            scope.launch { drawerState.close() } 
                        },
                        icon = { Icon(Icons.Default.AddCircle, contentDescription = null) }
                    )
                    NavigationDrawerItem(
                        label = { Text("Dispatch from Cause List") },
                        selected = currentView == "DISPATCH_CAUSE_LIST",
                        onClick = { 
                            currentView = "DISPATCH_CAUSE_LIST"
                            scope.launch { drawerState.close() } 
                        },
                        icon = { Icon(Icons.Default.CheckCircle, contentDescription = null) }
                    )
                    NavigationDrawerItem(
                        label = { Text("PDF Reports Engine") },
                        selected = currentView == "REPORTS_PANEL",
                        onClick = { 
                            currentView = "REPORTS_PANEL"
                            reportSelectedCourtChip = null
                            scope.launch { drawerState.close() } 
                        },
                        icon = { Icon(Icons.Default.Share, contentDescription = null) }
                    )
                    NavigationDrawerItem(
                        label = { Text("Flush Cause List Data") },
                        selected = false,
                        onClick = { showFlushDialog = true; scope.launch { drawerState.close() } },
                        icon = { Icon(Icons.Default.Delete, contentDescription = null) }
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                    
                    NavigationDrawerItem(
                        label = { Text("Share JSON Backup") },
                        selected = false,
                        onClick = {
                            scope.launch {
                                val allRecords = dao.getAllRecords().first()
                                val cls = causeListDao.getAllCauseListRecords().first()
                                JsonBackupHelper.exportFullBackup(context, allRecords, cls, shareDirectly = true)
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
                                val allRecords = dao.getAllRecords().first()
                                val cls = causeListDao.getAllCauseListRecords().first()
                                JsonBackupHelper.exportFullBackup(context, allRecords, cls, shareDirectly = false)
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
                                JsonBackupHelper.importFullBackup(context, uri, dao, causeListDao) {}
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
                                "SEARCH_MENU" -> "Search & Filter Engine"
                                "BULK" -> "Bulk Operations by Date & Court"
                                "BULK_LOCATION" -> "Bulk Location Management"
                                "CAUSE_LIST_PORTAL" -> "Cause List with Case Status"
                                "ADD_CAUSE_LIST" -> "Add Cause List Portal"
                                "DISPATCH_CAUSE_LIST" -> "Dispatch from Cause List"
                                "REPORTS_PANEL" -> "PDF Reports Engine"
                                else -> "Tracker Pro"
                            },
                            fontSize = 16.sp
                        )
                    },
                    navigationIcon = {
                        if (currentView != "MAIN") {
                            IconButton(onClick = { 
                                isDrawerLocked = false
                                currentView = "MAIN"
                                activeSearchOption = "NONE"
                                searchSelectedCourt = null
                            }) {
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
            Box(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .then(if (currentView == "ADD_CAUSE_LIST" || currentView == "CAUSE_LIST_PORTAL") Modifier else Modifier.padding(12.dp))
            ) {
                when (currentView) {
                    "CAUSE_LIST_PORTAL" -> {
                        CauseListStatusWebViewContent(
                            onNavigateBack = { 
                                isDrawerLocked = false
                                currentView = "MAIN" 
                            },
                            onDisableDrawerGestures = { locked -> isDrawerLocked = locked }
                        )
                    }

"ADD_CAUSE_LIST" -> {
    if (!isClWebActive) {
        Card(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Add Cause List to Tracker", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = addClCourtInput,
                    onValueChange = { addClCourtInput = it },
                    label = { Text("Court Number *") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedTextField(
                    value = addClDateInput,
                    onValueChange = { addClDateInput = it },
                    label = { Text("Cause List Date (dd-MM-yy) *") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))
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
            onClose = { 
                isDrawerLocked = false
                isClWebActive = false 
            },
            onDisableDrawerGestures = { locked -> isDrawerLocked = locked }
        )
    }
}

                  "DISPATCH_CAUSE_LIST" -> {
                        // State for comma-separated serial number filtering
                        var serialFilterInput by remember { mutableStateOf("") }
                        var selectedDispatchFileIds by remember { mutableStateOf(setOf<Long>()) }

                        Column(modifier = Modifier.fillMaxSize()) {
                            OutlinedTextField(
                                value = dispatchClDateInput,
                                onValueChange = { 
                                    dispatchClDateInput = it 
                                    selectedClCourtChip = null
                                    selectedDispatchFileIds = emptySet()
                                },
                                label = { Text("Cause List Date (dd-MM-yy)") },
                                modifier = Modifier.fillMaxWidth()
                            )

                            Text("Available Courts with Cause Lists (${courtsWithClForDate.size}):", fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                courtsWithClForDate.forEach { court ->
                                    FilterChip(
                                        selected = selectedClCourtChip == court,
                                        onClick = { 
                                            selectedClCourtChip = if (selectedClCourtChip == court) null else court
                                            selectedDispatchFileIds = emptySet()
                                        },
                                        label = { Text("Court $court") }
                                    )
                                }
                            }

                            if (selectedClCourtChip != null) {
                                Spacer(modifier = Modifier.height(4.dp))
                                OutlinedTextField(
                                    value = serialFilterInput,
                                    onValueChange = { serialFilterInput = it },
                                    label = { Text("Serial Filter (Comma separated)") },
                                    leadingIcon = { Icon(Icons.Default.List, contentDescription = null) },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(modifier = Modifier.height(4.dp))

                                OutlinedTextField(
                                    value = clSearchQuery,
                                    onValueChange = { clSearchQuery = it },
                                    label = { Text("Search Party Name or File No.") },
                                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                                    modifier = Modifier.fillMaxWidth()
                                )

                                // Parse comma-separated serial numbers from input
                                val targetSerials = remember(serialFilterInput) {
                                    serialFilterInput.split(",")
                                        .map { it.trim() }
                                        .filter { it.isNotBlank() }
                                }

                                // Filter logic applying exact match, connected sub-cases (e.g. 139.1), and all corrections
                                val filteredCases = activeCourtCases.filter { clRecord ->
                                    val matchesTextQuery = if (clSearchQuery.isBlank()) true else {
                                        clRecord.fileNo.contains(clSearchQuery, ignoreCase = true) ||
                                        clRecord.partyName.contains(clSearchQuery, ignoreCase = true) ||
                                        clRecord.serialNo.contains(clSearchQuery, ignoreCase = true)
                                    }

                                    if (!matchesTextQuery) return@filter false

                                    if (targetSerials.isEmpty()) {
                                        true
                                    } else {
                                        val isCorrection = clRecord.listType.equals("Correction", ignoreCase = true)
                                        val cleanRecordSerial = stripLeadingZeros(clRecord.serialNo)

                                        isCorrection || targetSerials.any { enteredSr ->
                                            val cleanEntered = stripLeadingZeros(enteredSr)
                                            cleanRecordSerial == cleanEntered || 
                                            cleanRecordSerial.startsWith("$cleanEntered.") || 
                                            cleanRecordSerial.startsWith("$cleanEntered/")
                                        }
                                    }
                                }

                                // Bulk Dispatch Action Row
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Button(
                                            onClick = { selectedDispatchFileIds = filteredCases.map { it.id }.toSet() },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                        ) {
                                            Text("Select All (${filteredCases.size})", fontSize = 11.sp)
                                        }
                                        OutlinedButton(
                                            onClick = { selectedDispatchFileIds = emptySet() },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                        ) {
                                            Text("Clear", fontSize = 11.sp)
                                        }
                                    }

                                    Button(
                                        enabled = selectedDispatchFileIds.isNotEmpty(),
                                        onClick = {
                                            scope.launch {
                                                val selectedItems = filteredCases.filter { selectedDispatchFileIds.contains(it.id) }
                                                val cleanDate = normalizeDate(dispatchClDateInput)
                                                val targetCourt = selectedClCourtChip ?: ""

                                                val recordsToSave = mutableListOf<FileRecord>()

                                                for (clRecord in selectedItems) {
                                                    val cleanSerialVal = stripLeadingZeros(clRecord.serialNo)
                                                    val serialFormatted = "${clRecord.listType} - $cleanSerialVal"

                                                    val existing = dao.getRecordByFileNo(clRecord.fileNo)
                                                    val existingCsv = existing?.dispatchDatesCsv ?: ""
                                                    val updatedCsv = when {
                                                        existingCsv.isBlank() -> cleanDate
                                                        existingCsv.contains(cleanDate) -> existingCsv
                                                        else -> "$existingCsv, $cleanDate"
                                                    }

                                                    val dispatchDetails = " | Court No: $targetCourt | Serial: $serialFormatted"
                                                    val entryLog = "[$cleanDate] Dispatched via Cause List$dispatchDetails"
                                                    val updatedHistory = if (existing != null) "${existing.historyLog}\n$entryLog" else entryLog

                                                    val record = FileRecord(
                                                        id = existing?.id ?: 0,
                                                        fileNo = clRecord.fileNo,
                                                        dispatchDate = cleanDate,
                                                        dispatchDatesCsv = updatedCsv,
                                                        courtNo = targetCourt,
                                                        serialNo = serialFormatted,
                                                        status = "Dispatched",
                                                        storageLocation = "",
                                                        sentToChamber = false,
                                                        judgeName = "",
                                                        remarks = existing?.remarks ?: "",
                                                        historyLog = updatedHistory,
                                                        reportsOnRecord = existing?.reportsOnRecord ?: "",
                                                        applicationsOnRecord = existing?.applicationsOnRecord ?: ""
                                                    )
                                                    recordsToSave.add(record)
                                                }

                                                dao.insertOrUpdateAll(recordsToSave)
                                                selectedDispatchFileIds = emptySet()
                                                Toast.makeText(context, "Successfully Dispatched ${recordsToSave.size} Cases to Court $targetCourt!", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                                    ) {
                                        Text("Bulk Dispatch (${selectedDispatchFileIds.size})", fontSize = 11.sp)
                                    }
                                }

                                Column(modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
                                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                        items(filteredCases) { clRecord ->
                                            val matchedLocal = allDbRecords.firstOrNull { it.fileNo == clRecord.fileNo }
                                            val isSelected = selectedDispatchFileIds.contains(clRecord.id)

                                            Card(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable {
                                                        selectedDispatchFileIds = if (isSelected) {
                                                            selectedDispatchFileIds - clRecord.id
                                                        } else {
                                                            selectedDispatchFileIds + clRecord.id
                                                        }
                                                    },
                                                colors = CardDefaults.cardColors(
                                                    containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
                                                ),
                                                elevation = CardDefaults.cardElevation(3.dp)
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(10.dp).fillMaxWidth(),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Checkbox(
                                                        checked = isSelected,
                                                        onCheckedChange = { checked ->
                                                            selectedDispatchFileIds = if (checked) {
                                                                selectedDispatchFileIds + clRecord.id
                                                            } else {
                                                                selectedDispatchFileIds - clRecord.id
                                                            }
                                                        }
                                                    )
                                                    Column(modifier = Modifier.weight(1f).padding(start = 6.dp)) {
                                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                                                Text("Sr: ${clRecord.serialNo}", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = MaterialTheme.colorScheme.primary)
                                                                if (clRecord.statusTag.isNotBlank()) {
                                                                    Badge(containerColor = MaterialTheme.colorScheme.secondaryContainer) {
                                                                        Text(clRecord.statusTag, color = MaterialTheme.colorScheme.onSecondaryContainer)
                                                                    }
                                                                }
                                                                Badge { Text(clRecord.listType) }
                                                            }
                                                            Text(clRecord.fileNo, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                                        }

                                                        Text("${clRecord.caseType} | ${clRecord.partyName}", fontSize = 12.sp, maxLines = 2, modifier = Modifier.padding(vertical = 2.dp))

                                                        // Complete Local Tracker Status, Location, Remarks & Metadata Summary Panel
                                                        Surface(
                                                            color = if (matchedLocal != null) Color(0xFFE8F5E9) else Color(0xFFFFF3E0),
                                                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                                                        ) {
                                                            Column(modifier = Modifier.padding(6.dp)) {
                                                                if (matchedLocal != null) {
                                                                    Text("✓ Local Tracker Status: '${matchedLocal.status}' (Court: ${matchedLocal.courtNo})", fontSize = 11.sp, color = Color(0xFF2E7D32), fontWeight = FontWeight.Bold)
                                                                    if (matchedLocal.storageLocation.isNotBlank()) {
                                                                        Text("📍 Location: ${matchedLocal.storageLocation}", fontSize = 11.sp, color = Color.DarkGray, fontWeight = FontWeight.SemiBold)
                                                                    }
                                                                    if (matchedLocal.remarks.isNotBlank()) {
                                                                        Text("📝 Remarks: ${matchedLocal.remarks}", fontSize = 11.sp, color = Color(0xFFC2185B), fontWeight = FontWeight.SemiBold)
                                                                    }
                                                                    if (matchedLocal.reportsOnRecord.isNotBlank()) {
                                                                        Text("📑 Reports: ${matchedLocal.reportsOnRecord.replace("\n", ", ")}", fontSize = 10.sp, color = Color(0xFF1565C0))
                                                                    }
                                                                    if (matchedLocal.applicationsOnRecord.isNotBlank()) {
                                                                        Text("📋 Apps: ${matchedLocal.applicationsOnRecord}", fontSize = 10.sp, color = Color(0xFF6A1B9A))
                                                                    }
                                                                } else {
                                                                    Text("⚠️ File not yet registered in local tracker.", fontSize = 11.sp, color = Color(0xFFE65100))
                                                                }
                                                            }
                                                        }

                                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.align(Alignment.End).padding(top = 4.dp)) {
                                                            OutlinedButton(
                                                                onClick = {
                                                                    scope.launch {
                                                                        val existing = dao.getRecordByFileNo(clRecord.fileNo)
                                                                        val target = existing ?: FileRecord(
                                                                            id = 0,
                                                                            fileNo = clRecord.fileNo,
                                                                            dispatchDate = "",
                                                                            dispatchDatesCsv = "",
                                                                            courtNo = "N/A",
                                                                            serialNo = "",
                                                                            status = "Unassigned",
                                                                            storageLocation = "",
                                                                            historyLog = ""
                                                                        )
                                                                        targetFileForMetaData = target
                                                                    }
                                                                },
                                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
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
                                                                    Toast.makeText(context, "Direct Dispatch Loaded: ${clRecord.fileNo}", Toast.LENGTH_SHORT).show()
                                                                },
                                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
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
                            }
                        }
                    }

                  
                    "SEARCH_MENU" -> {
                        Column(modifier = Modifier.fillMaxSize()) {
                            Text("Select Search Method:", fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))

                            Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    FilterChip(
                                        selected = activeSearchOption == "DATE",
                                        onClick = { activeSearchOption = "DATE"; searchSelectedCourt = null },
                                        label = { Text("1. By Date", fontSize = 11.sp) }
                                    )
                                    FilterChip(
                                        selected = activeSearchOption == "FILE_NO",
                                        onClick = { activeSearchOption = "FILE_NO" },
                                        label = { Text("2. By File No", fontSize = 11.sp) }
                                    )
                                    FilterChip(
                                        selected = activeSearchOption == "CHAMBER",
                                        onClick = { activeSearchOption = "CHAMBER" },
                                        label = { Text("3. In Chamber", fontSize = 11.sp) }
                                    )
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    FilterChip(
                                        selected = activeSearchOption == "TAKEN_UP",
                                        onClick = { activeSearchOption = "TAKEN_UP" },
                                        label = { Text("4. Taken Up", fontSize = 11.sp) }
                                    )
                                    FilterChip(
                                        selected = activeSearchOption == "ADVANCED",
                                        onClick = { activeSearchOption = "ADVANCED" },
                                        label = { Text("5. Multi-Criteria Search", fontSize = 11.sp) }
                                    )
                                }
                            }

                            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                            when (activeSearchOption) {
                                "ADVANCED" -> {
                                    var categoryDropdownExpanded by remember { mutableStateOf(false) }
                                    var locDropdownExpanded by remember { mutableStateOf(false) }
                                    var statusDropdownExpanded by remember { mutableStateOf(false) }

                                    val categoryOptions = listOf(
                                        "LOCATION" to "1. Storage Location",
                                        "JUDGE" to "2. Hon'ble Judge Name",
                                        "REMARKS" to "3. Remarks / Case Notes",
                                        "STATUS" to "4. Current Status"
                                    )

                                    Text("Select Search By Category:", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                        OutlinedTextField(
                                            value = categoryOptions.first { it.first == searchCategory }.second,
                                            onValueChange = {},
                                            readOnly = true,
                                            trailingIcon = {
                                                IconButton(onClick = { categoryDropdownExpanded = true }) {
                                                    Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                                                }
                                            },
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        DropdownMenu(
                                            expanded = categoryDropdownExpanded,
                                            onDismissRequest = { categoryDropdownExpanded = false }
                                        ) {
                                            categoryOptions.forEach { pair ->
                                                DropdownMenuItem(
                                                    text = { Text(pair.second) },
                                                    onClick = {
                                                        searchCategory = pair.first
                                                        categoryDropdownExpanded = false
                                                    }
                                                )
                                            }
                                        }
                                    }

                                    when (searchCategory) {
                                        "LOCATION" -> {
                                            val locOptions = listOf("Listing Seat", "Disposal/Compliance Seat", "Shelf", "Other")
                                            Box(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                                OutlinedTextField(
                                                    value = searchLocOption,
                                                    onValueChange = {},
                                                    label = { Text("Select Storage Location Option") },
                                                    readOnly = true,
                                                    trailingIcon = {
                                                        IconButton(onClick = { locDropdownExpanded = true }) {
                                                            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                                                        }
                                                    },
                                                    modifier = Modifier.fillMaxWidth()
                                                )
                                                DropdownMenu(
                                                    expanded = locDropdownExpanded,
                                                    onDismissRequest = { locDropdownExpanded = false }
                                                ) {
                                                    locOptions.forEach { opt ->
                                                        DropdownMenuItem(
                                                            text = { Text(opt) },
                                                            onClick = {
                                                                searchLocOption = opt
                                                                locDropdownExpanded = false
                                                            }
                                                        )
                                                    }
                                                }
                                            }

                                            if (searchLocOption == "Other") {
                                                OutlinedTextField(
                                                    value = searchCustomLocText,
                                                    onValueChange = { searchCustomLocText = it },
                                                    label = { Text("Enter Custom Location (e.g. Bundle No.)") },
                                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                                                )
                                            }
                                        }

                                        "JUDGE" -> {
                                            OutlinedTextField(
                                                value = searchJudgeTextInput,
                                                onValueChange = { searchJudgeTextInput = it },
                                                label = { Text("Enter Hon'ble Judge Name") },
                                                leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                                            )
                                        }

                                        "REMARKS" -> {
                                            OutlinedTextField(
                                                value = searchRemarksTextInput,
                                                onValueChange = { searchRemarksTextInput = it },
                                                label = { Text("Enter Remarks / Case Notes Keyword") },
                                                leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                                            )
                                        }

                                        "STATUS" -> {
                                            val statusOptions = listOf("Dispatched", "Taken Up", "Pass Over", "Received from Court", "Not Sent to Court", "Entry Deleted")
                                            Box(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                                OutlinedTextField(
                                                    value = searchStatusOption,
                                                    onValueChange = {},
                                                    label = { Text("Select Status Option") },
                                                    readOnly = true,
                                                    trailingIcon = {
                                                        IconButton(onClick = { statusDropdownExpanded = true }) {
                                                            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                                                        }
                                                    },
                                                    modifier = Modifier.fillMaxWidth()
                                                )
                                                DropdownMenu(
                                                    expanded = statusDropdownExpanded,
                                                    onDismissRequest = { statusDropdownExpanded = false }
                                                ) {
                                                    statusOptions.forEach { opt ->
                                                        DropdownMenuItem(
                                                            text = { Text(opt) },
                                                            onClick = {
                                                                searchStatusOption = opt
                                                                statusDropdownExpanded = false
                                                            }
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    OutlinedTextField(
                                        value = searchDateInterlocator,
                                        onValueChange = { searchDateInterlocator = it },
                                        label = { Text("Filter by Update Date (Optional, e.g. 04-08-26)") },
                                        leadingIcon = { Icon(Icons.Default.DateRange, contentDescription = null) },
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                                    )

                                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                                    Text("Matching Search Results (${advancedSearchResults.size}):", fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 4.dp))

                                    if (advancedSearchResults.isEmpty()) {
                                        Text("No files found matching the selected search criteria.", fontSize = 12.sp, color = Color.Gray, modifier = Modifier.padding(top = 12.dp))
                                    } else {
                                        Column(modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
                                            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                                items(advancedSearchResults) { record ->
                                                    CaseCardWithMeta(
                                                        record = record,
                                                        onClick = { activeTraceRecord = record },
                                                        onUpdate = { activeUpdateRecord = record },
                                                        onAddMeta = { targetFileForMetaData = record }
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }

                                "DATE" -> {
                                    OutlinedTextField(
                                        value = searchDateInput,
                                        onValueChange = { searchDateInput = it; searchSelectedCourt = null },
                                        label = { Text("Enter Date") },
                                        leadingIcon = { Icon(Icons.Default.DateRange, contentDescription = null) },
                                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                                    )

                                    Text("Dispatched Courts on $normalizedSearchDate (${searchCourtsList.size}):", fontWeight = FontWeight.Bold)
                                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        searchCourtsList.forEach { court ->
                                            FilterChip(
                                                selected = searchSelectedCourt == court,
                                                onClick = { searchSelectedCourt = if (searchSelectedCourt == court) null else court },
                                                label = { Text("Court $court") }
                                            )
                                        }
                                    }

                                    if (searchSelectedCourt == null) {
                                        Text("Please select a Court Number chip above to view case files.", fontSize = 12.sp, color = Color.Gray, modifier = Modifier.padding(top = 16.dp))
                                    } else {
                                        Text("Files Dispatched to Court $searchSelectedCourt on $normalizedSearchDate (${searchCourtFiles.size}):", fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 4.dp))
                                        Column(modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
                                            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                                items(searchCourtFiles) { record ->
                                                    CaseCardWithMeta(
                                                        record = record,
                                                        onClick = { activeTraceRecord = record },
                                                        onUpdate = { activeUpdateRecord = record },
                                                        onAddMeta = { targetFileForMetaData = record }
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }

                                "FILE_NO" -> {
                                    OutlinedTextField(
                                        value = searchFileNoInput,
                                        onValueChange = { searchFileNoInput = it },
                                        label = { Text("Enter File Number (e.g. 123/2026)") },
                                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                                    )

                                    if (searchFileNoInput.isBlank()) {
                                        Text("Please enter a file number above to search.", fontSize = 12.sp, color = Color.Gray, modifier = Modifier.padding(top = 16.dp))
                                    } else {
                                        Text("Matching Files (${fileNoSearchResults.size}):", fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 4.dp))
                                        Column(modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
                                            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                                items(fileNoSearchResults) { record ->
                                                    CaseCardWithMeta(
                                                        record = record,
                                                        onClick = { activeTraceRecord = record },
                                                        onUpdate = { activeUpdateRecord = record },
                                                        onAddMeta = { targetFileForMetaData = record }
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }

                                "CHAMBER" -> {
                                    Text("All In Chamber Files (${chamberFiles.size}):", fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 4.dp))
                                    Column(modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
                                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                            items(chamberFiles) { record ->
                                                CaseCardWithMeta(
                                                    record = record,
                                                    onClick = { activeTraceRecord = record },
                                                    onUpdate = { activeUpdateRecord = record },
                                                    onAddMeta = { targetFileForMetaData = record }
                                                )
                                            }
                                        }
                                    }
                                }

                                "TAKEN_UP" -> {
                                    Text("All Currently 'Taken Up' Files (${takenUpFiles.size}):", fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 4.dp))
                                    Column(modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
                                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                            items(takenUpFiles) { record ->
                                                CaseCardWithMeta(
                                                    record = record,
                                                    onClick = { activeTraceRecord = record },
                                                    onUpdate = { activeUpdateRecord = record },
                                                    onAddMeta = { targetFileForMetaData = record }
                                                )
                                            }
                                        }
                                    }
                                }

                                else -> {
                                    Text("Please select a search filter option above.", fontSize = 13.sp, color = Color.Gray)
                                }
                            }
                        }
                    }

                    "REPORTS_PANEL" -> {
                        LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            item { Text("PDF Reports & Data Recovery:", fontWeight = FontWeight.Bold, fontSize = 15.sp) }

                            item {
                                Card(modifier = Modifier.fillMaxWidth()) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Text("1. Export Master Database PDF", fontWeight = FontWeight.Bold)
                                        Button(
                                            onClick = {
                                                scope.launch {
                                                    val snapshot = dao.getAllRecords().first()
                                                    PdfReportGenerator.generateMasterReport(context, snapshot)
                                                }
                                            },
                                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                                        ) {
                                            Text("EXPORT MASTER LEDGER PDF")
                                        }
                                    }
                                }
                            }

                            item {
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Text("2. Rebuild Database from Master PDF", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSecondaryContainer)
                                        Text("Restores all cases and audit logs directly from an exported Master Ledger PDF file.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSecondaryContainer)
                                        Button(
                                            onClick = {
                                                onPickPdf { uri ->
                                                    PdfImportHelper.restoreDatabaseFromPdf(context, uri, dao) {}
                                                }
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                                        ) {
                                            Text("IMPORT MASTER PDF & REBUILD DB")
                                        }
                                    }
                                }
                            }

                            item {
                                Card(modifier = Modifier.fillMaxWidth()) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Text("3. Particular Case File Report", fontWeight = FontWeight.Bold)
                                        OutlinedTextField(
                                            value = reportTargetFileNo,
                                            onValueChange = { reportTargetFileNo = it },
                                            label = { Text("File Number") },
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        Button(
                                            onClick = {
                                                scope.launch {
                                                    val cleanTarget = stripLeadingZeros(reportTargetFileNo)
                                                    val rec = dao.getRecordByFileNo(cleanTarget)
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
                            }

                            item {
                                Card(modifier = Modifier.fillMaxWidth()) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Text("4. Date & Court Number Wise Report", fontWeight = FontWeight.Bold)
                                        OutlinedTextField(
                                            value = reportTargetDate,
                                            onValueChange = { 
                                                reportTargetDate = it 
                                                reportSelectedCourtChip = null
                                            },
                                            label = { Text("Enter Target Date") },
                                            leadingIcon = { Icon(Icons.Default.DateRange, contentDescription = null) },
                                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                                        )

                                        Text("Dispatched Courts on $normalizedReportDate (${reportCourtsList.size}):", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            reportCourtsList.forEach { court ->
                                                FilterChip(
                                                    selected = reportSelectedCourtChip == court,
                                                    onClick = { reportSelectedCourtChip = if (reportSelectedCourtChip == court) null else court },
                                                    label = { Text("Court $court") }
                                                )
                                            }
                                        }

                                        Button(
                                            enabled = reportSelectedCourtChip != null,
                                            onClick = {
                                                scope.launch {
                                                    val selectedCourt = reportSelectedCourtChip!!
                                                    PdfReportGenerator.generateDateCourtReport(context, normalizedReportDate, selectedCourt, reportCourtFiles)
                                                }
                                            },
                                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                                        ) {
                                            Text(if (reportSelectedCourtChip == null) "SELECT A COURT CHIP ABOVE" else "EXPORT COURT $reportSelectedCourtChip DISPATCH PDF")
                                        }
                                    }
                                }
                            }
                        }
                    }

                    "BULK" -> {
                        Column(modifier = Modifier.fillMaxSize()) {
                            Text("Bulk Operations", fontWeight = FontWeight.Bold)

                            OutlinedTextField(
                                value = bulkDateInput,
                                onValueChange = { 
                                    bulkDateInput = it 
                                    bulkSelectedCourtChip = null
                                    selectedFileIds = emptySet()
                                },
                                label = { Text("Enter Dispatch Date") },
                                leadingIcon = { Icon(Icons.Default.DateRange, contentDescription = null) },
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                            )

                            Text("Dispatched Courts on $normalizedBulkDate (${bulkCourtsList.size}):", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                bulkCourtsList.forEach { court ->
                                    FilterChip(
                                        selected = bulkSelectedCourtChip == court,
                                        onClick = { 
                                            bulkSelectedCourtChip = if (bulkSelectedCourtChip == court) null else court
                                            selectedFileIds = emptySet()
                                        },
                                        label = { Text("Court $court") }
                                    )
                                }
                            }

                            if (bulkSelectedCourtChip == null) {
                                Text("Please select a Court Number chip above to proceed with bulk updates.", fontSize = 12.sp, color = Color.Gray, modifier = Modifier.padding(top = 16.dp))
                            } else {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
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
                                        if (bulkTargetStatus == "Received from Court") {
                                            showBulkReceivedDialog = true
                                        } else {
                                            scope.launch {
                                                val selectedRecords = bulkCourtFiles.filter { selectedFileIds.contains(it.id) }
                                                val updatedList = selectedRecords.map { rec ->
                                                    rec.copy(
                                                        status = bulkTargetStatus,
                                                        storageLocation = "",
                                                        historyLog = "${rec.historyLog}\n[$normalizedBulkDate] Bulk Status changed to '$bulkTargetStatus'"
                                                    )
                                                }
                                                dao.insertOrUpdateAll(updatedList)
                                                selectedFileIds = emptySet()
                                                Toast.makeText(context, "${updatedList.size} Files Updated!", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                                ) {
                                    Text("BATCH UPDATE ${selectedFileIds.size} FILES IN COURT $bulkSelectedCourtChip")
                                }

                                Column(modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
                                    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
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
                                }
                            }
                        }
                    }

                    "BULK_LOCATION" -> {
                        Column(modifier = Modifier.fillMaxSize()) {
                            Text("Select Unassigned Category:", fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))

                            Row(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                FilterChip(
                                    selected = bulkLocationCategory == "PASS_OVER",
                                    onClick = { bulkLocationCategory = "PASS_OVER"; bulkLocSelectedIds = emptySet() },
                                    label = { Text("1. Pass Over Files", fontSize = 11.sp) }
                                )
                                FilterChip(
                                    selected = bulkLocationCategory == "NOT_SENT",
                                    onClick = { bulkLocationCategory = "NOT_SENT"; bulkLocSelectedIds = emptySet() },
                                    label = { Text("2. Not Sent Files", fontSize = 11.sp) }
                                )
                                FilterChip(
                                    selected = bulkLocationCategory == "RECEIVED",
                                    onClick = { bulkLocationCategory = "RECEIVED"; bulkLocSelectedIds = emptySet() },
                                    label = { Text("3. Received Files", fontSize = 11.sp) }
                                )
                            }

                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { bulkLocSelectedIds = bulkLocationFilteredFiles.map { it.id }.toSet() }) {
                                        Text("Select All", fontSize = 12.sp)
                                    }
                                    Button(onClick = { bulkLocSelectedIds = emptySet() }) {
                                        Text("Clear All", fontSize = 12.sp)
                                    }
                                }

                                Button(
                                    enabled = bulkLocSelectedIds.isNotEmpty(),
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                    onClick = { showSetLocationDialog = true }
                                ) {
                                    Text("Set Location (${bulkLocSelectedIds.size})", fontSize = 12.sp)
                                }
                            }

                            Text("Unassigned Files (${bulkLocationFilteredFiles.size}):", fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 4.dp))

                            if (bulkLocationFilteredFiles.isEmpty()) {
                                Text(
                                    "No files found with status '${
                                        when (bulkLocationCategory) {
                                            "PASS_OVER" -> "Pass Over"
                                            "NOT_SENT" -> "Not Sent to Court"
                                            else -> "Received from Court"
                                        }
                                    }' having an empty storage location.",
                                    fontSize = 12.sp,
                                    color = Color.Gray,
                                    modifier = Modifier.padding(top = 16.dp)
                                )
                            } else {
                                Column(modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
                                    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                                        items(bulkLocationFilteredFiles) { record ->
                                            Card(
                                                modifier = Modifier.fillMaxWidth().clickable {
                                                    bulkLocSelectedIds = if (bulkLocSelectedIds.contains(record.id)) {
                                                        bulkLocSelectedIds - record.id
                                                    } else {
                                                        bulkLocSelectedIds + record.id
                                                    }
                                                },
                                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                                            ) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    modifier = Modifier.padding(8.dp).fillMaxWidth()
                                                ) {
                                                    Checkbox(
                                                        checked = bulkLocSelectedIds.contains(record.id),
                                                        onCheckedChange = { isChecked ->
                                                            bulkLocSelectedIds = if (isChecked) {
                                                                bulkLocSelectedIds + record.id
                                                            } else {
                                                                bulkLocSelectedIds - record.id
                                                            }
                                                        }
                                                    )
                                                    Column(modifier = Modifier.padding(start = 8.dp)) {
                                                        Text("File No: ${record.fileNo}", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                                        Text("Status: ${record.status} | Court: ${record.courtNo} | Serial: ${record.serialNo.ifEmpty { "N/A" }}", fontSize = 12.sp)
                                                        if (record.remarks.isNotBlank()) {
                                                            Text("Remarks: ${record.remarks}", fontSize = 11.sp, color = MaterialTheme.colorScheme.secondary)
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

                    else -> {
                        // MAIN REGISTRATION SCREEN
                        Column(modifier = Modifier.fillMaxSize()) {
                            Card(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), elevation = CardDefaults.cardElevation(4.dp)) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text("Registration / Re-Dispatch", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)

                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        FilterChip(selected = selectedMode == "Dispatched", onClick = { selectedMode = "Dispatched" }, label = { Text("Dispatched") })
                                        FilterChip(selected = selectedMode == "Not Sent", onClick = { selectedMode = "Not Sent" }, label = { Text("Not Sent") })
                                        FilterChip(selected = selectedMode == "Chamber", onClick = { selectedMode = "Chamber" }, label = { Text("Chamber") })
                                    }

                                    OutlinedTextField(
                                        value = dispatchDateInput,
                                        onValueChange = { dispatchDateInput = it },
                                        label = { Text("Dispatch Date") },
                                        trailingIcon = {
                                            TextButton(onClick = { dispatchDateInput = currentDate }) {
                                                Text("Today", fontSize = 11.sp)
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                                    )

                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedTextField(
                                            value = fileSerialInput,
                                            onValueChange = { fileSerialInput = it },
                                            label = { Text("File Serial No. *") },
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
                                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                                modifier = Modifier.weight(1f)
                                            )
                                        }

                                        Text("List Type", fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            FilterChip(selected = listTypeInput == "DCL", onClick = { listTypeInput = "DCL" }, label = { Text("DCL") })
                                            FilterChip(selected = listTypeInput == "ACL", onClick = { listTypeInput = "ACL" }, label = { Text("ACL") })
                                            FilterChip(selected = listTypeInput == "Correction", onClick = { listTypeInput = "Correction" }, label = { Text("Correction") })
                                        }
                                    } else if (selectedMode == "Chamber") {
                                        OutlinedTextField(
                                            value = judgeNameInput, 
                                            onValueChange = { judgeNameInput = it }, 
                                            label = { Text("Hon'ble Judge Name") }, 
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }

                                    OutlinedTextField(
                                        value = remarksInput,
                                        onValueChange = { remarksInput = it },
                                        label = { Text("Remarks") },
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                                    )

                                    Button(
                                        onClick = {
                                            val serialInt = fileSerialInput.trim().toIntOrNull()
                                            val yearInt = fileYearInput.trim().toIntOrNull()

                                            if (serialInt == null || serialInt <= 0) {
                                                Toast.makeText(context, "Invalid File Serial Number!", Toast.LENGTH_LONG).show()
                                                return@Button
                                            }

                                            if (yearInt == null || yearInt < 1970 || yearInt > 2026) {
                                                Toast.makeText(context, "Invalid File Year!", Toast.LENGTH_LONG).show()
                                                return@Button
                                            }

                                            val cleanSerial = stripLeadingZeros(fileSerialInput)
                                            val cleanYear = fileYearInput.trim()
                                            val formattedFileNo = "$cleanSerial/$cleanYear"
                                            val cleanDate = normalizeDate(dispatchDateInput)

                                            if (cleanDate.isBlank()) {
                                                Toast.makeText(context, "Please enter a valid Dispatch Date", Toast.LENGTH_SHORT).show()
                                                return@Button
                                            }
                                            if (selectedMode == "Dispatched" && (courtNoInput.isBlank() || serialNoInput.isBlank())) {
                                                Toast.makeText(context, "Court No and Serial No are required", Toast.LENGTH_SHORT).show()
                                                return@Button
                                            }

                                            scope.launch {
                                                val existing = dao.getRecordByFileNo(formattedFileNo)
                                                val newStatus = if (selectedMode == "Dispatched") "Dispatched" else if (selectedMode == "Chamber") "Sent to Chamber" else "Not Sent to Court"
                                                val isChamber = selectedMode == "Chamber"
                                                val isDispatched = selectedMode == "Dispatched"
                                                val judge = if (isChamber) judgeNameInput.trim() else ""

                                                val existingCsv = existing?.dispatchDatesCsv ?: ""
                                                val updatedCsv = when {
                                                    existingCsv.isBlank() -> cleanDate
                                                    existingCsv.contains(cleanDate) -> existingCsv
                                                    else -> "$existingCsv, $cleanDate"
                                                }

                                                val cleanCourtNo = if (isDispatched) stripLeadingZeros(courtNoInput) else "N/A"
                                                val cleanSerialVal = if (isDispatched) stripLeadingZeros(serialNoInput) else ""
                                                val serialFormatted = if (isDispatched) "$listTypeInput - $cleanSerialVal" else ""

                                                val dispatchDetails = if (isDispatched) " | Court No: $cleanCourtNo | Serial: $serialFormatted" else ""
                                                val logRemark = if (remarksInput.isNotBlank()) " | Remarks: ${remarksInput.trim()}" else ""

                                                val entryLog = "[$cleanDate] Registered as '$newStatus'$dispatchDetails${if (isChamber) " (Judge: $judge)" else ""}$logRemark"
                                                val updatedHistory = if (existing != null) "${existing.historyLog}\n$entryLog" else entryLog

                                                val record = FileRecord(
                                                    id = existing?.id ?: 0,
                                                    fileNo = formattedFileNo,
                                                    dispatchDate = cleanDate,
                                                    dispatchDatesCsv = updatedCsv,
                                                    courtNo = if (isChamber) "N/A" else cleanCourtNo,
                                                    serialNo = if (isChamber) "" else serialFormatted,
                                                    status = newStatus,
                                                    storageLocation = "",
                                                    sentToChamber = isChamber,
                                                    judgeName = if (isDispatched) "" else judge,
                                                    remarks = remarksInput.trim(),
                                                    historyLog = updatedHistory,
                                                    reportsOnRecord = existing?.reportsOnRecord ?: "",
                                                    applicationsOnRecord = existing?.applicationsOnRecord ?: ""
                                                )
                                                dao.insertOrUpdateRecord(record)

                                                fileSerialInput = ""
                                                serialNoInput = ""
                                                remarksInput = ""
                                                
                                                Toast.makeText(context, "Record Saved Successfully ($formattedFileNo)!", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                                    ) {
                                        Text("SAVE RECORD")
                                    }
                                }
                            }

                            val recentTwoFiles = remember(allDbRecords) { allDbRecords.sortedByDescending { it.id }.take(2) }
                            Text("Last 2 Registered / Updated Files:", fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 4.dp))
                            
                            Column(modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
                                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                    items(recentTwoFiles) { record ->
                                        CaseCardWithMeta(
                                            record = record,
                                            onClick = { activeTraceRecord = record },
                                            onUpdate = { activeUpdateRecord = record },
                                            onAddMeta = { targetFileForMetaData = record }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // BULK RECEIVED FROM COURT DIALOG
    if (showBulkReceivedDialog) {
        var selectedLocation by remember { mutableStateOf("Listing Seat") }
        var dropdownExpanded by remember { mutableStateOf(false) }
        var bulkChangeAffectedDate by remember { mutableStateOf(bulkDateInput) }
        val receivedOptions = listOf("Listing Seat", "Disposal/Compliance Seat", "Shelf")

        AlertDialog(
            onDismissRequest = { showBulkReceivedDialog = false },
            title = { Text("Bulk Operation: Received from Court") },
            text = {
                Column {
                    Text("Specify details for updating ${selectedFileIds.size} files to 'Received from Court':")

                    Box(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        OutlinedTextField(
                            value = selectedLocation,
                            onValueChange = { selectedLocation = it },
                            label = { Text("Select Target Location *") },
                            readOnly = true,
                            trailingIcon = {
                                IconButton(onClick = { dropdownExpanded = true }) {
                                    Icon(Icons.Default.ArrowDropDown, contentDescription = "Dropdown")
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                        DropdownMenu(
                            expanded = dropdownExpanded,
                            onDismissRequest = { dropdownExpanded = false }
                        ) {
                            receivedOptions.forEach { opt ->
                                DropdownMenuItem(
                                    text = { Text(opt) },
                                    onClick = {
                                        selectedLocation = opt
                                        dropdownExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    OutlinedTextField(
                        value = bulkChangeAffectedDate,
                        onValueChange = { bulkChangeAffectedDate = it },
                        label = { Text("Change Affected Date *") },
                        leadingIcon = { Icon(Icons.Default.DateRange, contentDescription = null) },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = selectedLocation.isNotBlank() && bulkChangeAffectedDate.isNotBlank(),
                    onClick = {
                        scope.launch {
                            val cleanDate = normalizeDate(bulkChangeAffectedDate)
                            val selectedRecords = bulkCourtFiles.filter { selectedFileIds.contains(it.id) }

                            val updatedList = selectedRecords.map { rec ->
                                rec.copy(
                                    status = "Received from Court",
                                    storageLocation = selectedLocation,
                                    historyLog = "${rec.historyLog}\n[$cleanDate] Bulk Status changed to 'Received from Court' | Loc: $selectedLocation"
                                )
                            }
                            dao.insertOrUpdateAll(updatedList)
                            selectedFileIds = emptySet()
                            showBulkReceivedDialog = false
                            Toast.makeText(context, "${updatedList.size} Files Marked Received ($selectedLocation) on $cleanDate!", Toast.LENGTH_SHORT).show()
                        }
                    }
                ) {
                    Text("Confirm Bulk Status Update")
                }
            },
            dismissButton = {
                TextButton(onClick = { showBulkReceivedDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // SET BULK LOCATION DIALOG
    if (showSetLocationDialog) {
        var inputLocText by remember { mutableStateOf("") }
        var changeAffectedDate by remember { mutableStateOf(currentDate) }
        var dropdownExpanded by remember { mutableStateOf(false) }
        val receivedOptions = listOf("Listing Seat", "Disposal/Compliance Seat", "Shelf")

        if (bulkLocationCategory == "RECEIVED" && inputLocText.isEmpty()) {
            inputLocText = "Listing Seat"
        }

        AlertDialog(
            onDismissRequest = { showSetLocationDialog = false },
            title = { Text("Set Storage Location (${bulkLocSelectedIds.size} Files)") },
            text = {
                Column {
                    Text("Specify the location to assign to all selected files:")

                    if (bulkLocationCategory == "RECEIVED") {
                        Box(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                            OutlinedTextField(
                                value = inputLocText,
                                onValueChange = { inputLocText = it },
                                label = { Text("Select Target Seat / Shelf *") },
                                readOnly = true,
                                trailingIcon = {
                                    IconButton(onClick = { dropdownExpanded = true }) {
                                        Icon(Icons.Default.ArrowDropDown, contentDescription = "Dropdown")
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                            DropdownMenu(
                                expanded = dropdownExpanded,
                                onDismissRequest = { dropdownExpanded = false }
                            ) {
                                receivedOptions.forEach { opt ->
                                    DropdownMenuItem(
                                        text = { Text(opt) },
                                        onClick = {
                                            inputLocText = opt
                                            dropdownExpanded = false
                                        }
                                    )
                                }
                            }
                        }

                        OutlinedTextField(
                            value = changeAffectedDate,
                            onValueChange = { changeAffectedDate = it },
                            label = { Text("Change Affected Date *") },
                            leadingIcon = { Icon(Icons.Default.DateRange, contentDescription = null) },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                        )
                    } else {
                        OutlinedTextField(
                            value = inputLocText,
                            onValueChange = { inputLocText = it },
                            label = { Text("Enter Location (e.g. Bundle No., Shelf, Person)") },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = inputLocText.isNotBlank() && (bulkLocationCategory != "RECEIVED" || changeAffectedDate.isNotBlank()),
                    onClick = {
                        scope.launch {
                            val cleanDate = normalizeDate(changeAffectedDate)
                            val selectedTargets = bulkLocationFilteredFiles.filter { bulkLocSelectedIds.contains(it.id) }
                            val updated = selectedTargets.map { rec ->
                                val dateTag = if (bulkLocationCategory == "RECEIVED") cleanDate else currentDate
                                val logEntry = "[$dateTag] Bulk Location updated to '$inputLocText'"
                                rec.copy(
                                    storageLocation = inputLocText.trim(),
                                    historyLog = "${rec.historyLog}\n$logEntry"
                                )
                            }
                            dao.insertOrUpdateAll(updated)
                            bulkLocSelectedIds = emptySet()
                            showSetLocationDialog = false
                            Toast.makeText(context, "${updated.size} Files Location Updated!", Toast.LENGTH_SHORT).show()
                        }
                    }
                ) {
                    Text("Save Location")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSetLocationDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // DISPOSAL UPDATE MODAL
    val currentRecordForUpdate = activeUpdateRecord
    if (currentRecordForUpdate != null) {
        var newStatus by remember { mutableStateOf(currentRecordForUpdate.status.ifEmpty { "Taken Up" }) }
        var locInput by remember { mutableStateOf(currentRecordForUpdate.storageLocation) }
        var changeAffectedDate by remember { mutableStateOf(currentDate) }
        var remarksUpdate by remember { mutableStateOf(currentRecordForUpdate.remarks) }
        var deleteReason by remember { mutableStateOf("") }
        var validationError by remember { mutableStateOf<String?>(null) }

        var receivedDropdownExpanded by remember { mutableStateOf(false) }
        val receivedLocationOptions = listOf("Listing Seat", "Disposal/Compliance Seat", "Shelf")

        val isDeleteMode = newStatus == "Entry Deleted"
        val isReceivedMode = newStatus == "Received from Court"
        val requiresChangeAffectedDate = isReceivedMode || 
            (currentRecordForUpdate.status == "Received from Court" && locInput != currentRecordForUpdate.storageLocation)

        val isCourtStatus = newStatus == "Pass Over" || newStatus == "Taken Up" || isReceivedMode
        val isCourtInfoMissing = currentRecordForUpdate.courtNo.isBlank() || 
                                currentRecordForUpdate.courtNo == "N/A" || 
                                currentRecordForUpdate.serialNo.isBlank()

        AlertDialog(
            onDismissRequest = { activeUpdateRecord = null },
            title = { Text("Update Disposal: ${currentRecordForUpdate.fileNo}") },
            text = {
                Column {
                    Text("Select Target Status:")
                    val options = listOf("Taken Up", "Pass Over", "Received from Court", "Not Sent to Court", "Entry Deleted")
                    options.forEach { opt ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = newStatus == opt,
                                onClick = {
                                    newStatus = opt
                                    locInput = if (opt == "Received from Court") "Listing Seat" else ""
                                    validationError = null
                                }
                            )
                            Text(opt)
                        }
                    }

                    if (isCourtStatus && isCourtInfoMissing) {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                        ) {
                            Text(
                                text = "⚠️ Cannot update to '$newStatus': File has no Court Number/Serial Number recorded in database. Please Re-Dispatch first.",
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }

                    if (isReceivedMode) {
                        Box(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                            OutlinedTextField(
                                value = locInput,
                                onValueChange = { locInput = it },
                                label = { Text("Select Storage Location *") },
                                readOnly = true,
                                trailingIcon = {
                                    IconButton(onClick = { receivedDropdownExpanded = true }) {
                                        Icon(Icons.Default.ArrowDropDown, contentDescription = "Select Received Location")
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                            DropdownMenu(
                                expanded = receivedDropdownExpanded,
                                onDismissRequest = { receivedDropdownExpanded = false }
                            ) {
                                receivedLocationOptions.forEach { option ->
                                    DropdownMenuItem(
                                        text = { Text(option) },
                                        onClick = {
                                            locInput = option
                                            receivedDropdownExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    } else if (isDeleteMode) {
                        OutlinedTextField(
                            value = deleteReason,
                            onValueChange = { deleteReason = it },
                            label = { Text("Reason for Deletion") },
                            isError = deleteReason.isBlank(),
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                        )
                    } else {
                        OutlinedTextField(
                            value = locInput,
                            onValueChange = { locInput = it },
                            label = { Text("Location") },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                        )
                    }

                    if (requiresChangeAffectedDate) {
                        OutlinedTextField(
                            value = changeAffectedDate,
                            onValueChange = { changeAffectedDate = it },
                            label = { Text("Change Affected Date *") },
                            leadingIcon = { Icon(Icons.Default.DateRange, contentDescription = null) },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                        )
                    }

                    OutlinedTextField(
                        value = remarksUpdate,
                        onValueChange = { remarksUpdate = it },
                        label = { Text("Remarks") },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )

                    if (validationError != null) {
                        Text(
                            text = validationError!!,
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = (!isDeleteMode || deleteReason.isNotBlank()),
                    onClick = {
                        if (isCourtStatus && isCourtInfoMissing) {
                            validationError = "Status change blocked! Court Number and Serial Number required."
                            Toast.makeText(context, "Cannot change status without Court Number & Serial Number!", Toast.LENGTH_LONG).show()
                            return@Button
                        }

                        if (isReceivedMode && locInput.isBlank()) {
                            validationError = "Please select one of the three storage locations!"
                            Toast.makeText(context, "Select Listing Seat, Disposal/Compliance Seat, or Shelf", Toast.LENGTH_SHORT).show()
                            return@Button
                        }

                        val effectiveDate = if (requiresChangeAffectedDate) normalizeDate(changeAffectedDate) else normalizeDate(dispatchDateInput)

                        if (requiresChangeAffectedDate && effectiveDate.isBlank()) {
                            validationError = "Change Affected Date is required!"
                            return@Button
                        }

                        scope.launch {
                            val logEntry = "[$effectiveDate] Status changed to '$newStatus' ${if (isDeleteMode) "Reason: $deleteReason" else "Loc: $locInput"}"

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
                            Toast.makeText(context, "Status Updated Successfully!", Toast.LENGTH_SHORT).show()
                        }
                    }
                ) { Text("Save Changes") }
            },
            dismissButton = {
                TextButton(onClick = { activeUpdateRecord = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // AUDIT TRACE DIALOG
    val currentRecordForTrace = activeTraceRecord
    if (currentRecordForTrace != null) {
        AlertDialog(
            onDismissRequest = { activeTraceRecord = null },
            title = { Text("Audit Stack Trace: ${currentRecordForTrace.fileNo}") },
            text = {
                LazyColumn(modifier = Modifier.height(250.dp)) {
                    item {
                        Text(
                            text = currentRecordForTrace.historyLog.ifEmpty { "No History Log Recorded" },
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

    // META-DATA DIALOG
    targetFileForMetaData?.let { record ->
        AddCaseMetaDataDialog(
            record = record,
            onDismiss = { targetFileForMetaData = null },
            onSave = { updatedRecord ->
                scope.launch {
                    dao.insertOrUpdateRecord(updatedRecord)
                    targetFileForMetaData = null
                    Toast.makeText(context, "Meta-Data Saved for Perpetuity!", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    // FLUSH DIALOG
    if (showFlushDialog) {
        var cutoffDateInput by remember { mutableStateOf(currentDate) }
        AlertDialog(
            onDismissRequest = { showFlushDialog = false },
            title = { Text("Flush Ephemeral Cause List Data") },
            text = {
                Column {
                    Text("Delete parsed Cause List PDFs on or before date.\n\nDispatched cases, locations, reports, and legacy data remain untouched.", fontSize = 12.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = cutoffDateInput,
                        onValueChange = { cutoffDateInput = it },
                        label = { Text("Cutoff Date (dd-MM-yy)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    scope.launch {
                        val deletedCount = causeListDao.deleteCauseListsUpToDate(cutoffDateInput.trim())
                        showFlushDialog = false
                        Toast.makeText(context, "Flushed $deletedCount Cause List rows!", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("Confirm Flush") }
            },
            dismissButton = { TextButton(onClick = { showFlushDialog = false }) { Text("Cancel") } }
        )
    }
}

@Composable
fun CaseCardWithMeta(
    record: FileRecord,
    onClick: () -> Unit,
    onUpdate: () -> Unit,
    onAddMeta: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = if (record.status == "Entry Deleted") Color(0xFFFFEBEE) else MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("File No: ${record.fileNo}", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Badge(containerColor = if (record.status == "Entry Deleted") Color.Red else MaterialTheme.colorScheme.primary) {
                    Text(record.status, color = Color.White)
                }
            }
            Text("Court: ${record.courtNo} | Serial: ${record.serialNo.ifEmpty { "N/A" }}", fontSize = 12.sp)
            if (record.storageLocation.isNotBlank()) Text("📍 Location: ${record.storageLocation}", fontSize = 12.sp, color = Color.DarkGray, fontWeight = FontWeight.SemiBold)
            if (record.remarks.isNotBlank()) Text("📝 Remarks: ${record.remarks}", fontSize = 11.sp, color = Color(0xFFC2185B), fontWeight = FontWeight.SemiBold)
            if (record.reportsOnRecord.isNotBlank()) Text("📑 Reports: ${record.reportsOnRecord.replace("\n", ", ")}", fontSize = 11.sp, color = Color(0xFF1565C0))
            if (record.applicationsOnRecord.isNotBlank()) Text("📋 Apps: ${record.applicationsOnRecord}", fontSize = 11.sp, color = Color(0xFF6A1B9A))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.align(Alignment.End).padding(top = 4.dp)) {
                OutlinedButton(onClick = onAddMeta, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                    Text("Meta-Data", fontSize = 11.sp)
                }
                Button(onClick = onUpdate, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                    Text("Update Status", fontSize = 11.sp)
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun CauseListStatusWebViewContent(
    onNavigateBack: () -> Unit,
    onDisableDrawerGestures: (Boolean) -> Unit
) {
    var webView: WebView? by remember { mutableStateOf(null) }
    var isLoading by remember { mutableStateOf(false) }

    var isSearchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var activeMatchIndex by remember { mutableStateOf(0) }
    var totalMatches by remember { mutableStateOf(0) }

    val context = LocalContext.current

    DisposableEffect(Unit) {
        onDisableDrawerGestures(true)
        onDispose {
            onDisableDrawerGestures(false)
        }
    }

    BackHandler {
        if (isSearchActive) {
            isSearchActive = false
            webView?.clearMatches()
        } else if (webView?.canGoBack() == true) {
            webView?.goBack()
        } else {
            onDisableDrawerGestures(false)
            onNavigateBack()
        }
    }

    // Helper to open external links (like "View" order sheets) directly in Chrome / system browser
    fun openInExternalBrowser(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "No web browser found to open link", Toast.LENGTH_SHORT).show()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Button(
                        onClick = { 
                            if (webView?.canGoBack() == true) webView?.goBack() 
                            else {
                                onDisableDrawerGestures(false)
                                onNavigateBack()
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Back", fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = { webView?.reload() },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Reload", modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Reload", fontSize = 12.sp)
                    }
                    IconButton(onClick = {
                        isSearchActive = !isSearchActive
                        if (!isSearchActive) {
                            webView?.clearMatches()
                            searchQuery = ""
                            totalMatches = 0
                            activeMatchIndex = 0
                        }
                    }) {
                        Icon(Icons.Default.Search, contentDescription = "Find")
                    }
                }
                OutlinedButton(
                    onClick = {
                        onDisableDrawerGestures(false)
                        onNavigateBack()
                    },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Text("Exit Portal", fontSize = 12.sp)
                }
            }
        }

        if (isSearchActive) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                elevation = CardDefaults.cardElevation(4.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { query ->
                            searchQuery = query
                            if (query.isNotBlank()) webView?.findAllAsync(query)
                            else {
                                webView?.clearMatches()
                                totalMatches = 0
                                activeMatchIndex = 0
                            }
                        },
                        label = { Text("Find in page...", fontSize = 12.sp) },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )

                    if (totalMatches > 0) {
                        Text(text = "${activeMatchIndex + 1}/$totalMatches", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }

                    IconButton(enabled = totalMatches > 0, onClick = { webView?.findNext(false) }) {
                        Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Previous")
                    }
                    IconButton(enabled = totalMatches > 0, onClick = { webView?.findNext(true) }) {
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Next")
                    }
                    IconButton(onClick = {
                        isSearchActive = false
                        webView?.clearMatches()
                        searchQuery = ""
                        totalMatches = 0
                        activeMatchIndex = 0
                    }) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }
            }
        }

        if (isLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        Box(modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )

                        isVerticalScrollBarEnabled = true
                        isHorizontalScrollBarEnabled = true
                        isScrollbarFadingEnabled = false
                        scrollBarStyle = WebView.SCROLLBARS_INSIDE_OVERLAY
                        overScrollMode = WebView.OVER_SCROLL_IF_CONTENT_SCROLLS

                        val cookieManager = CookieManager.getInstance()
                        cookieManager.setAcceptCookie(true)
                        cookieManager.setAcceptThirdPartyCookies(this, true)

                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            databaseEnabled = true
                            
                            useWideViewPort = true
                            loadWithOverviewMode = true
                            setSupportZoom(true)
                            builtInZoomControls = true
                            displayZoomControls = false
                            
                            javaScriptCanOpenWindowsAutomatically = true
                            setSupportMultipleWindows(true)
                            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                            
                            userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
                        }

                        setFindListener { activeIndex, matchCount, _ ->
                            activeMatchIndex = activeIndex
                            totalMatches = matchCount
                        }

                        webChromeClient = object : WebChromeClient() {
                            override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?): Boolean {
                                // Intercept popup/new-tab requests (like clicking "View" judgments) and open in external browser (Chrome)
                                val transport = resultMsg?.obj as? WebView.WebViewTransport
                                val hitTestResult = view?.hitTestResult
                                hitTestResult?.extra?.let { url ->
                                    openInExternalBrowser(url)
                                }
                                return false
                            }

                            override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                                Toast.makeText(ctx, message ?: "Alert", Toast.LENGTH_SHORT).show()
                                result?.confirm()
                                return true
                            }

                            override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                                result?.confirm()
                                return true
                            }
                        }

                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                isLoading = true
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                isLoading = false
                                view?.evaluateJavascript(
                                    """
                                    (function() {
                                        try {
                                            var meta = document.querySelector('meta[name="viewport"]');
                                            if (!meta) {
                                                meta = document.createElement('meta');
                                                meta.name = 'viewport';
                                                document.head.appendChild(meta);
                                            }
                                            meta.content = 'width=1280, initial-scale=0.5, maximum-scale=3.0, user-scalable=yes';
                                        } catch (e) {}
                                    })();
                                    """.trimIndent(), null
                                )
                            }

                            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                val url = request?.url.toString()
                                // If the link is attempting an external popup / view action, open it in Chrome
                                if (url.contains("order", ignoreCase = true) || url.contains("judgment", ignoreCase = true) || url.contains("popup", ignoreCase = true)) {
                                    openInExternalBrowser(url)
                                    return true
                                }
                                view?.loadUrl(url)
                                return true
                            }

                            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: android.net.http.SslError?) {
                                handler?.proceed()
                            }
                        }

                        loadUrl("https://www.allahabadhighcourt.in/apps/status_ccms/index.php/causelist")
                        webView = this
                    }
                },
                modifier = Modifier.fillMaxWidth().fillMaxHeight()
            )
        }
    }
}

/**
 * In-App Web View for "Add Cause List From Web":
 * Upgraded with desktop viewport mode, touch response, and locked drawer gestures.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun CauseListIngestionWebView(
    courtNo: String,
    date: String,
    causeListDao: CauseListDao,
    onClose: () -> Unit,
    onDisableDrawerGestures: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var webView: WebView? by remember { mutableStateOf(null) }
    var detectedHtmlToImport by remember { mutableStateOf<String?>(null) }
    var isImporting by remember { mutableStateOf(false) }

    var lastHandledSignature by remember { mutableStateOf<String?>(null) }
    var hasPromptBeenShownForCurrentView by remember { mutableStateOf(false) }

    // Disable navigation drawer swipe gestures while viewing the ingestion web view
    DisposableEffect(Unit) {
        onDisableDrawerGestures(true)
        onDispose {
            onDisableDrawerGestures(false)
        }
    }

    BackHandler {
        if (webView?.canGoBack() == true) {
            webView?.goBack()
        } else {
            onDisableDrawerGestures(false)
            onClose()
        }
    }

    class WebAppInterface {
        @JavascriptInterface
        fun onCauseListRendered(tableSignature: String, html: String) {
            scope.launch(Dispatchers.Main) {
                if (!isImporting && 
                    !hasPromptBeenShownForCurrentView && 
                    tableSignature != lastHandledSignature && 
                    detectedHtmlToImport == null
                ) {
                    lastHandledSignature = tableSignature
                    hasPromptBeenShownForCurrentView = true
                    detectedHtmlToImport = html
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Surface(
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Court: $courtNo | Date: $date", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text("CCMS Portal Ingestion", fontSize = 11.sp, color = MaterialTheme.colorScheme.secondary)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(
                        onClick = {
                            isImporting = true
                            webView?.evaluateJavascript(
                                "(function() { return document.documentElement.outerHTML; })();"
                            ) { rawHtmlJson ->
                                scope.launch(Dispatchers.IO) {
                                    try {
                                        val unescaped = org.json.JSONTokener(rawHtmlJson).nextValue().toString()
                                        val parsed = WebCauseListParser.parseHtmlCauseList(unescaped, courtNo, date)
                                        if (parsed.isNotEmpty()) {
                                            causeListDao.insertAll(parsed)
                                            withContext(Dispatchers.Main) {
                                                isImporting = false
                                                Toast.makeText(context, "Successfully Imported ${parsed.size} Cases (${parsed.firstOrNull()?.listType ?: ""})!", Toast.LENGTH_LONG).show()
                                            }
                                        } else {
                                            withContext(Dispatchers.Main) {
                                                isImporting = false
                                                Toast.makeText(context, "No active cause list table found on screen.", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    } catch (e: Exception) {
                                        withContext(Dispatchers.Main) {
                                            isImporting = false
                                            Toast.makeText(context, "Parse Error: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                                        }
                                    }
                                }
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text("Import Visible", fontSize = 11.sp)
                    }

                    OutlinedButton(
                        onClick = {
                            onDisableDrawerGestures(false)
                            onClose()
                        },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text("Exit", fontSize = 11.sp)
                    }
                }
            }
        }

        if (isImporting) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        Column(modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )

                        isVerticalScrollBarEnabled = true
                        isHorizontalScrollBarEnabled = true
                        isScrollbarFadingEnabled = false
                        scrollBarStyle = WebView.SCROLLBARS_INSIDE_OVERLAY
                        overScrollMode = WebView.OVER_SCROLL_IF_CONTENT_SCROLLS

                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            databaseEnabled = true
                            
                            // Desktop Viewport Configuration
                            useWideViewPort = true
                            loadWithOverviewMode = true
                            setSupportZoom(true)
                            builtInZoomControls = true
                            displayZoomControls = false
                            
                            javaScriptCanOpenWindowsAutomatically = true
                            setSupportMultipleWindows(false)
                            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                            
                            // Standard Desktop User Agent to avoid mobile responsive truncation
                            userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
                        }

                        addJavascriptInterface(WebAppInterface(), "AndroidBridge")

                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                hasPromptBeenShownForCurrentView = false
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                view?.evaluateJavascript(
                                    """
                                    (function() {
                                        try {
                                            var meta = document.querySelector('meta[name="viewport"]');
                                            if (!meta) {
                                                meta = document.createElement('meta');
                                                meta.name = 'viewport';
                                                document.head.appendChild(meta);
                                            }
                                            meta.content = 'width=1280, initial-scale=0.5, maximum-scale=3.0, user-scalable=yes';

                                            var lastSignature = '';
                                            function checkTable() {
                                                var div = document.getElementById('CauseListDiv');
                                                if (div && div.style.display !== 'none') {
                                                    var table = div.querySelector('table.table-causelist');
                                                    if (table && table.rows.length > 2) {
                                                        var currentSignature = table.rows.length + '_' + table.rows[1].innerText;
                                                        if (currentSignature !== lastSignature) {
                                                            lastSignature = currentSignature;
                                                            AndroidBridge.onCauseListRendered(currentSignature, document.documentElement.outerHTML);
                                                        }
                                                    }
                                                }
                                            }
                                            var target = document.getElementById('CauseListDiv');
                                            if (target) {
                                                var observer = new MutationObserver(function(mutations) {
                                                    checkTable();
                                                });
                                                observer.observe(target, { attributes: true, childList: true, subtree: true });
                                            }
                                            setInterval(checkTable, 2500);
                                        } catch (e) {}
                                    })();
                                    """.trimIndent(), null
                                )
                            }
                        }

                        loadUrl("https://www.allahabadhighcourt.in/apps/status_ccms/index.php/causelist")
                        webView = this
                    }
                },
                modifier = Modifier.fillMaxWidth().fillMaxHeight()
            )
        }
    }

    detectedHtmlToImport?.let { html ->
        AlertDialog(
            onDismissRequest = { 
                detectedHtmlToImport = null 
            },
            title = { Text("Cause List Detected!") },
            text = { Text("A cause list is open on screen. Do you wish to import its cases and companion files for Court $courtNo ($date)?") },
            confirmButton = {
                Button(
                    onClick = {
                        val contentToParse = html
                        detectedHtmlToImport = null
                        isImporting = true
                        scope.launch(Dispatchers.IO) {
                            try {
                                val parsedRecords = WebCauseListParser.parseHtmlCauseList(contentToParse, courtNo, date)
                                if (parsedRecords.isNotEmpty()) {
                                    causeListDao.insertAll(parsedRecords)
                                    val detectedType = parsedRecords.firstOrNull()?.listType ?: "DCL"
                                    withContext(Dispatchers.Main) {
                                        isImporting = false
                                        Toast.makeText(context, "Imported ${parsedRecords.size} Cases as '$detectedType'!", Toast.LENGTH_LONG).show()
                                    }
                                } else {
                                    withContext(Dispatchers.Main) {
                                        isImporting = false
                                        Toast.makeText(context, "No rows could be extracted from this view.", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) {
                                    isImporting = false
                                    Toast.makeText(context, "Error: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    }
                ) {
                    Text("Import Now")
                }
            },
            dismissButton = {
                TextButton(onClick = { 
                    detectedHtmlToImport = null 
                }) {
                    Text("Dismiss")
                }
            }
        )
    }
}



/**
 * Add Case Meta-Data Attachment Dialog
 * - Unselected by default: User must make an explicit selection
 */
@Composable
fun AddCaseMetaDataDialog(
    record: FileRecord,
    onDismiss: () -> Unit,
    onSave: (FileRecord) -> Unit
) {
    var metaType by remember { mutableStateOf("REPORT") }
    var selectedReportOption by remember { mutableStateOf("") }
    var customReportText by remember { mutableStateOf("") }
    var reportDateInput by remember { mutableStateOf("") }
    
    var appNoInput by remember { mutableStateOf("") }
    var appYearInput by remember { mutableStateOf("2026") }

    val reportOptions = listOf(
        "Notice: Served",
        "Notice: Unserved",
        "Compromise: Done",
        "Compromise: Not Done",
        "Mediation Report",
        "Other Report"
    )

    val isReportValid = if (selectedReportOption == "Other Report") {
        customReportText.isNotBlank()
    } else {
        selectedReportOption.isNotBlank()
    }

    val isAppValid = appNoInput.isNotBlank() && appYearInput.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Case Meta-Data: ${record.fileNo}", fontSize = 15.sp) },
        text = {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceAround
                ) {
                    FilterChip(
                        selected = metaType == "REPORT",
                        onClick = { metaType = "REPORT" },
                        label = { Text("Keep Report") }
                    )
                    FilterChip(
                        selected = metaType == "APPLICATION",
                        onClick = { metaType = "APPLICATION" },
                        label = { Text("Add Application") }
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))

                if (metaType == "REPORT") {
                    var dropdownExpanded by remember { mutableStateOf(false) }
                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = selectedReportOption.ifEmpty { "Choose Report Type..." },
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Select Report Type *") },
                            trailingIcon = {
                                IconButton(onClick = { dropdownExpanded = true }) {
                                    Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                        DropdownMenu(
                            expanded = dropdownExpanded,
                            onDismissRequest = { dropdownExpanded = false }
                        ) {
                            reportOptions.forEach { opt ->
                                DropdownMenuItem(
                                    text = { Text(opt) },
                                    onClick = {
                                        selectedReportOption = opt
                                        dropdownExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    if (selectedReportOption == "Other Report") {
                        OutlinedTextField(
                            value = customReportText,
                            onValueChange = { customReportText = it },
                            label = { Text("Specify Report Description *") },
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                        )
                    }

                    OutlinedTextField(
                        value = reportDateInput,
                        onValueChange = { reportDateInput = it },
                        label = { Text("Report Date (Optional, e.g. 21-09-26)") },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                    )
                } else {
                    OutlinedTextField(
                        value = appNoInput,
                        onValueChange = { appNoInput = it },
                        label = { Text("App No (e.g. 9)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = appYearInput,
                        onValueChange = { appYearInput = it },
                        label = { Text("Year (e.g. 2026)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "Format: [App No]/[Year]",
                        fontSize = 11.sp,
                        color = Color.Gray,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = (metaType == "REPORT" && isReportValid) || (metaType == "APPLICATION" && isAppValid),
                onClick = {
                    val currentDate = SimpleDateFormat("dd-MM-yy", Locale.getDefault()).format(Date())
                    if (metaType == "REPORT") {
                        val label = if (selectedReportOption == "Other Report") customReportText.trim() else selectedReportOption
                        val suffix = if (reportDateInput.isNotBlank()) " (Date: ${reportDateInput.trim()})" else ""
                        val str = "$label$suffix"
                        val updatedReports = if (record.reportsOnRecord.isBlank()) str else "${record.reportsOnRecord}\n$str"
                        val log = "${record.historyLog}\n[$currentDate] Placed on Record -> $str"
                        onSave(record.copy(reportsOnRecord = updatedReports, historyLog = log))
                    } else {
                        val app = "${appNoInput.trim()}/${appYearInput.trim()}"
                        val updatedApps = if (record.applicationsOnRecord.isBlank()) app else "${record.applicationsOnRecord}, $app"
                        val log = "${record.historyLog}\n[$currentDate] Application Tagged -> $app"
                        onSave(record.copy(applicationsOnRecord = updatedApps, historyLog = log))
                    }
                }
            ) {
                Text("Save Meta-Data")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
