package com.court.filetracker

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    private var onPdfSelected: ((Uri) -> Unit)? = null

    private val pdfPickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { onPdfSelected?.invoke(it) }
    }

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
                    onRestore = { DriveServiceHelper.performRestore(this, Runnable {}) },
                    onPickPdf = { callback ->
                        onPdfSelected = callback
                        pdfPickerLauncher.launch("application/pdf")
                    }
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
    onRestore: () -> Unit,
    onPickPdf: ((Uri) -> Unit) -> Unit
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
    
    var fileSerialInput by remember { mutableStateOf("") }
    var fileYearInput by remember { mutableStateOf("2026") }

    var remarksInput by remember { mutableStateOf("") }
    var judgeNameInput by remember { mutableStateOf("") }

    // Navigation Views
    var currentView by remember { mutableStateOf("MAIN") }
    var activeSearchOption by remember { mutableStateOf("NONE") }
    var searchDateInput by remember { mutableStateOf(currentDate) }
    var searchSelectedCourt by remember { mutableStateOf<String?>(null) }
    var searchFileNoInput by remember { mutableStateOf("") }

    // Option 5: Multi-Criteria Advanced Search States
    var searchCategory by remember { mutableStateOf("LOCATION") }
    var searchLocOption by remember { mutableStateOf("Listing Seat") }
    var searchCustomLocText by remember { mutableStateOf("") }
    var searchJudgeTextInput by remember { mutableStateOf("") }
    var searchRemarksTextInput by remember { mutableStateOf("") }
    var searchStatusOption by remember { mutableStateOf("Dispatched") }
    var searchDateInterlocator by remember { mutableStateOf("") }

    // Report Generator Input States
    var reportTargetFileNo by remember { mutableStateOf("") }
    var reportTargetDate by remember { mutableStateOf(currentDate) }
    var reportSelectedCourtChip by remember { mutableStateOf<String?>(null) }

    // Bulk Operations Mode States
    var bulkDateInput by remember { mutableStateOf(currentDate) }
    var bulkSelectedCourtChip by remember { mutableStateOf<String?>(null) }
    var bulkTargetStatus by remember { mutableStateOf("Taken Up") }
    var selectedFileIds by remember { mutableStateOf(setOf<Long>()) }
    var showBulkReceivedDialog by remember { mutableStateOf(false) }

    // Bulk Location Screen States
    var bulkLocationCategory by remember { mutableStateOf("PASS_OVER") }
    var bulkLocSelectedIds by remember { mutableStateOf(setOf<Long>()) }
    var showSetLocationDialog by remember { mutableStateOf(false) }

    // Dialog States
    var activeTraceRecord by remember { mutableStateOf<FileRecord?>(null) }
    var activeUpdateRecord by remember { mutableStateOf<FileRecord?>(null) }

    // Normalized Query Triggers
    val normalizedSearchDate = remember(searchDateInput) { normalizeDate(searchDateInput) }
    val normalizedBulkDate = remember(bulkDateInput) { normalizeDate(bulkDateInput) }
    val normalizedReportDate = remember(reportTargetDate) { normalizeDate(reportTargetDate) }
    val normalizedSearchFileNo = remember(searchFileNoInput) { normalizeSearchQuery(searchFileNoInput) }
    val normalizedInterlocatorDate = remember(searchDateInterlocator) { if (searchDateInterlocator.isBlank()) "" else normalizeDate(searchDateInterlocator) }

    fun getDispatchedCourtForDate(record: FileRecord, targetDate: String): String {
        val logLines = record.historyLog.split("\n")
        val dispatchLine = logLines.firstOrNull { line ->
            line.contains("[$targetDate]") && 
            (line.contains("Registered as 'Dispatched'") || line.contains("Dispatched to Court")) &&
            line.contains("Court No:")
        }
        
        if (dispatchLine != null) {
            val match = Regex("Court No:\\s*(\\d+)").find(dispatchLine)
            if (match != null) return stripLeadingZeros(match.groupValues[1])
        }
        
        if (record.dispatchDate == targetDate && record.courtNo != "N/A" && record.courtNo.isNotBlank()) {
            return stripLeadingZeros(record.courtNo)
        }
        
        return "N/A"
    }

    // Search Engine Data Flows
    val rawDateRecords by dao.getRecordsByDate(normalizedSearchDate).collectAsState(initial = emptyList())
    val searchCourtsList = remember(rawDateRecords, normalizedSearchDate) {
        rawDateRecords.map { getDispatchedCourtForDate(it, normalizedSearchDate) }
            .filter { it != "N/A" && it.isNotBlank() }
            .distinct()
            .sortedBy { it.toIntOrNull() ?: 999 }
    }
    val searchCourtFiles = remember(rawDateRecords, searchSelectedCourt, normalizedSearchDate) {
        if (searchSelectedCourt == null) emptyList()
        else rawDateRecords.filter { getDispatchedCourtForDate(it, normalizedSearchDate) == searchSelectedCourt }
    }

    // Bulk Operations Data Flows
    val rawBulkDateRecords by dao.getRecordsByDate(normalizedBulkDate).collectAsState(initial = emptyList())
    val bulkCourtsList = remember(rawBulkDateRecords, normalizedBulkDate) {
        rawBulkDateRecords.map { getDispatchedCourtForDate(it, normalizedBulkDate) }
            .filter { it != "N/A" && it.isNotBlank() }
            .distinct()
            .sortedBy { it.toIntOrNull() ?: 999 }
    }
    val bulkCourtFiles = remember(rawBulkDateRecords, bulkSelectedCourtChip, normalizedBulkDate) {
        if (bulkSelectedCourtChip == null) emptyList()
        else rawBulkDateRecords.filter { getDispatchedCourtForDate(it, normalizedBulkDate) == bulkSelectedCourtChip }
    }

    // PDF Reports Panel Data Flows
    val rawReportDateRecords by dao.getRecordsByDate(normalizedReportDate).collectAsState(initial = emptyList())
    val reportCourtsList = remember(rawReportDateRecords, normalizedReportDate) {
        rawReportDateRecords.map { getDispatchedCourtForDate(it, normalizedReportDate) }
            .filter { it != "N/A" && it.isNotBlank() }
            .distinct()
            .sortedBy { it.toIntOrNull() ?: 999 }
    }
    val reportCourtFiles = remember(rawReportDateRecords, reportSelectedCourtChip, normalizedReportDate) {
        if (reportSelectedCourtChip == null) emptyList()
        else rawReportDateRecords.filter { getDispatchedCourtForDate(it, normalizedReportDate) == reportSelectedCourtChip }
    }

    // Other Search Flows
    val fileNoSearchResults by dao.searchRecords(normalizedSearchFileNo).collectAsState(initial = emptyList())
    val allDbRecords by dao.getAllRecords().collectAsState(initial = emptyList())
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

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Court File Tracker Menu", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
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
                        label = { Text("PDF Reports Engine") },
                        selected = currentView == "REPORTS_PANEL",
                        onClick = { 
                            currentView = "REPORTS_PANEL"
                            reportSelectedCourtChip = null
                            scope.launch { drawerState.close() } 
                        },
                        icon = { Icon(Icons.Default.Share, contentDescription = null) }
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                    
                    NavigationDrawerItem(
                        label = { Text("Rebuild DB from PDF") },
                        selected = false,
                        onClick = {
                            scope.launch { drawerState.close() }
                            onPickPdf { uri ->
                                PdfImportHelper.restoreDatabaseFromPdf(context, uri, dao) {}
                            }
                        },
                        icon = { Icon(Icons.Default.Refresh, contentDescription = null) }
                    )
                    
                    NavigationDrawerItem(
                        label = { Text("Connect Google Drive") },
                        selected = false,
                        onClick = { onGoogleDriveLogin(); scope.launch { drawerState.close() } },
                        icon = { Icon(Icons.Default.AccountCircle, contentDescription = null) }
                    )
                    NavigationDrawerItem(
                        label = { Text("Google Drive Backup") },
                        selected = false,
                        onClick = { onBackup(); scope.launch { drawerState.close() } },
                        icon = { Icon(Icons.Default.ArrowForward, contentDescription = null) }
                    )
                    NavigationDrawerItem(
                        label = { Text("Restore Cloud Data") },
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
                                "SEARCH_MENU" -> "Search & Filter Engine"
                                "BULK" -> "Bulk Operations by Date & Court"
                                "BULK_LOCATION" -> "Bulk Location Management"
                                "REPORTS_PANEL" -> "PDF Reports Engine"
                                else -> "Allahabad High Court File Tracker"
                            },
                            fontSize = 16.sp
                        )
                    },
                    navigationIcon = {
                        if (currentView != "MAIN") {
                            IconButton(onClick = { 
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
            Column(modifier = Modifier.padding(padding).padding(12.dp)) {

                if (currentView == "SEARCH_MENU") {
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

                            // Optional Interlocator Date Field
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
                                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(advancedSearchResults) { record ->
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
                                                Text("Court: ${record.courtNo} | Serial: ${record.serialNo.ifEmpty { "N/A" }}", fontSize = 12.sp)
                                                if (record.judgeName.isNotBlank()) Text("Judge: ${record.judgeName}", fontSize = 12.sp, color = Color(0xFF9C27B0))
                                                if (record.storageLocation.isNotBlank()) Text("Location: ${record.storageLocation}", fontSize = 12.sp, color = Color.DarkGray)
                                                if (record.remarks.isNotBlank()) Text("Remarks: ${record.remarks}", fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)

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
                                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(searchCourtFiles) { record ->
                                        val courtForThisDate = getDispatchedCourtForDate(record, normalizedSearchDate)
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
                                                Text("Dispatched Court on $normalizedSearchDate: Court $courtForThisDate", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                                Text("Current Status: ${record.status} | Location: ${record.storageLocation.ifEmpty { "N/A" }}", fontSize = 12.sp)
                                                if (record.remarks.isNotBlank()) Text("Remarks: ${record.remarks}", fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)

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
                                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(fileNoSearchResults) { record ->
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

                        "CHAMBER" -> {
                            Text("All In Chamber Files (${chamberFiles.size}):", fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 4.dp))
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(chamberFiles) { record ->
                                    Card(
                                        modifier = Modifier.fillMaxWidth().clickable { activeTraceRecord = record },
                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                                    ) {
                                        Column(modifier = Modifier.padding(12.dp)) {
                                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                                Text("File: ${record.fileNo}", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                                Badge(containerColor = Color(0xFF9C27B0)) {
                                                    Text("Chamber: ${record.judgeName.ifEmpty { "Hon'ble Judge" }}", color = Color.White)
                                                }
                                            }
                                            Text("Last Date: ${record.dispatchDate}", fontSize = 12.sp)
                                            if (record.remarks.isNotBlank()) Text("Remarks: ${record.remarks}", fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)

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

                        "TAKEN_UP" -> {
                            Text("All Currently 'Taken Up' Files (${takenUpFiles.size}):", fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 4.dp))
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(takenUpFiles) { record ->
                                    Card(
                                        modifier = Modifier.fillMaxWidth().clickable { activeTraceRecord = record },
                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                                    ) {
                                        Column(modifier = Modifier.padding(12.dp)) {
                                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                                Text("File: ${record.fileNo}", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                                Badge(containerColor = MaterialTheme.colorScheme.primary) {
                                                    Text(record.status, color = Color.White)
                                                }
                                            }
                                            Text("Court No: ${record.courtNo} | Serial: ${record.serialNo}", fontSize = 12.sp)
                                            if (record.storageLocation.isNotBlank()) Text("Location: ${record.storageLocation}", fontSize = 12.sp, color = Color.DarkGray)
                                            if (record.remarks.isNotBlank()) Text("Remarks: ${record.remarks}", fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)

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

                        else -> {
                            Text("Please select a search filter option above.", fontSize = 13.sp, color = Color.Gray)
                        }
                    }

                } else if (currentView == "REPORTS_PANEL") {
                    Text("PDF Reports & Data Recovery:", fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 12.dp))

                    Card(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
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

                    // PDF RESTORE ACTION CARD
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
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

                    Card(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
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
                                Text(if (reportSelectedCourtChip == null) "SELECT A COURT CHIP ABOVE" else "EXPORT COURT $reportSelectedCourtChip DISPATCH PDF (${reportCourtFiles.size} FILES)")
                            }
                        }
                    }

                } else if (currentView == "BULK") {
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
                    }

                } else if (currentView == "BULK_LOCATION") {
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

                    Text(
                        "Unassigned Files (${bulkLocationFilteredFiles.size}):",
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )

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
                        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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

                } else {
                    // MAIN REGISTRATION SCREEN
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
                                            historyLog = updatedHistory
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
                    
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(recentTwoFiles) { record ->
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
    }

    // BULK OPERATIONS RECEIVED FROM COURT DIALOG WITH LOCATION & CHANGE AFFECTED DATE
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

    // SET BULK LOCATION DIALOG WITH DYNAMIC CHANGE AFFECTED DATE
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

    // DISPOSAL UPDATE MODAL WITH CHANGE AFFECTED DATE PROMPT
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
