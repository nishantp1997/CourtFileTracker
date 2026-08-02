package com.court.filetracker

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val db = AppDatabase.getDatabase(applicationContext)
        val dao = db.fileRecordDao()

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    CourtFileTrackerApp(dao)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourtFileTrackerApp(dao: FileRecordDao) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var selectedDate by remember { mutableStateOf(SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())) }
    var isDispatched by remember { mutableStateOf(true) }

    // Input fields
    var courtNo by remember { mutableStateOf("") }
    val listTypes = listOf("DCL", "ACL", "Correction List")
    var selectedListType by remember { mutableStateOf(listTypes[0]) }
    var isListTypeExpanded by remember { mutableStateOf(false) }
    var serialNoDigits by remember { mutableStateOf("") }
    var fileNo by remember { mutableStateOf("") }

    // Storage choices (Not Sent)
    var retainedChoice by remember { mutableStateOf("Shelf") }
    var retainedBundleNo by remember { mutableStateOf("") }
    var retainedPersonName by remember { mutableStateOf("") }

    // Search and navigation
    var searchQuery by remember { mutableStateOf("") }
    var searchDate by remember { mutableStateOf(SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())) }
    var activeSearchCourt by remember { mutableStateOf<String?>(null) }

    // Dialog state controllers
    var recordToUpdate by remember { mutableStateOf<FileRecord?>(null) }
    var recordToDelete by remember { mutableStateOf<FileRecord?>(null) }
    var recordForHistory by remember { mutableStateOf<FileRecord?>(null) }

    val courtsForSearchDate by dao.getCourtsByDate(searchDate).collectAsState(initial = emptyList())
    val courtSpecificRecords by dao.getRecordsByDateAndCourt(searchDate, activeSearchCourt ?: "").collectAsState(initial = emptyList())
    val generalSearchResults by dao.searchRecords(searchQuery).collectAsState(initial = emptyList())

    Column(modifier = Modifier.padding(16.dp)) {
        Text(
            text = "Allahabad High Court - File Movement",
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Morning Entry Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "Add File Record", fontWeight = FontWeight.Bold)
                    
                    SingleChoiceSegmentedButtonRow {
                        SegmentedButton(
                            selected = isDispatched,
                            onClick = { isDispatched = true },
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                        ) {
                            Text("Dispatched", fontSize = 12.sp)
                        }
                        SegmentedButton(
                            selected = !isDispatched,
                            onClick = { isDispatched = false },
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                        ) {
                            Text("Not Sent", fontSize = 12.sp)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = selectedDate,
                    onValueChange = { selectedDate = it },
                    label = { Text("Date (YYYY-MM-DD)") },
                    modifier = Modifier.fillMaxWidth()
                )

                if (isDispatched) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = courtNo,
                            onValueChange = { courtNo = it },
                            label = { Text("Court No.") },
                            modifier = Modifier.weight(1f)
                        )

                        ExposedDropdownMenuBox(
                            expanded = isListTypeExpanded,
                            onExpandedChange = { isListTypeExpanded = !isListTypeExpanded },
                            modifier = Modifier.weight(1.2f)
                        ) {
                            OutlinedTextField(
                                value = selectedListType,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("List Type") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = isListTypeExpanded) },
                                modifier = Modifier.menuAnchor()
                            )
                            ExposedDropdownMenu(
                                expanded = isListTypeExpanded,
                                onDismissRequest = { isListTypeExpanded = false }
                            ) {
                                listTypes.forEach { type ->
                                    DropdownMenuItem(
                                        text = { Text(type) },
                                        onClick = {
                                            selectedListType = type
                                            isListTypeExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = serialNoDigits,
                            onValueChange = { serialNoDigits = it },
                            label = { Text("Serial No.") },
                            placeholder = { Text("e.g. 15") },
                            modifier = Modifier.weight(1f)
                        )

                        OutlinedTextField(
                            value = fileNo,
                            onValueChange = { fileNo = it },
                            label = { Text("File No.") },
                            placeholder = { Text("e.g. 1234/2026") },
                            modifier = Modifier.weight(1.5f)
                        )
                    }
                } else {
                    OutlinedTextField(
                        value = fileNo,
                        onValueChange = { fileNo = it },
                        label = { Text("File No. (e.g. 1234/2026)") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(10.dp))
                    Text("Storage Place:", fontWeight = FontWeight.Bold, fontSize = 14.sp)

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        listOf("Shelf", "Bundle", "Person").forEach { loc ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(
                                    selected = (retainedChoice == loc),
                                    onClick = { retainedChoice = loc }
                                )
                                Text(loc, fontSize = 13.sp)
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                        }
                    }

                    if (retainedChoice == "Bundle") {
                        OutlinedTextField(
                            value = retainedBundleNo,
                            onValueChange = { retainedBundleNo = it },
                            label = { Text("Bundle No. (e.g. 1 for B-1)") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    if (retainedChoice == "Person") {
                        OutlinedTextField(
                            value = retainedPersonName,
                            onValueChange = { retainedPersonName = it },
                            label = { Text("Person's Name") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Button(
                    onClick = {
                        if (fileNo.isNotBlank()) {
                            val formattedSerialNo = if (isDispatched && serialNoDigits.isNotBlank()) "$selectedListType - ${serialNoDigits.trim()}" else ""
                            val statusVal = if (isDispatched) "Dispatched" else "Not Sent to Court"
                            
                            val locVal = if (!isDispatched) {
                                when (retainedChoice) {
                                    "Bundle" -> "B-${retainedBundleNo.trim()}"
                                    "Person" -> "Person: ${retainedPersonName.trim()}"
                                    else -> "Shelf"
                                }
                            } else ""

                            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
                            val initialHistory = if (isDispatched) {
                                "[$timestamp] Dispatched to Court ${courtNo.trim()} ($formattedSerialNo)"
                            } else {
                                "[$timestamp] Created as 'Not Sent to Court' (Storage: $locVal)"
                            }

                            coroutineScope.launch {
                                dao.insertRecord(
                                    FileRecord(
                                        dispatchDate = selectedDate,
                                        courtNo = if (isDispatched) courtNo.trim() else "N/A",
                                        serialNo = formattedSerialNo,
                                        fileNo = fileNo.trim(),
                                        status = statusVal,
                                        storageLocation = locVal,
                                        historyLog = initialHistory
                                    )
                                )
                                fileNo = ""
                                serialNoDigits = ""
                                retainedBundleNo = ""
                                retainedPersonName = ""
                                Toast.makeText(context, "Record Saved!", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            Toast.makeText(context, "Please enter File No.", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("Save Record")
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Search Section Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f))
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(text = "🔍 Search & Date Navigation", fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        label = { Text("Keyword Search") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        modifier = Modifier.weight(1f)
                    )

                    OutlinedTextField(
                        value = searchDate,
                        onValueChange = { 
                            searchDate = it 
                            activeSearchCourt = null
                        },
                        label = { Text("Filter Date") },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Search Results & Navigation Views
        if (searchQuery.isNotBlank()) {
            Text(text = "Search Results:", fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))

            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                items(generalSearchResults) { record ->
                    RecordItem(
                        record = record,
                        onUpdateStatusClick = { recordToUpdate = record },
                        onDelete = { recordToDelete = record },
                        onFileClick = { recordForHistory = record }
                    )
                }
            }
        } else if (activeSearchCourt == null) {
            Text(
                text = "Courts Dispatched On $searchDate (Tap to View Files):",
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))

            if (courtsForSearchDate.isEmpty()) {
                Text("No court dispatches recorded for this date.", color = MaterialTheme.colorScheme.outline)
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                    items(courtsForSearchDate) { court ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { activeSearchCourt = court },
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(text = "Court No. $court", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                Text(text = "View Dispatches ➔", color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { activeSearchCourt = null }) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                }
                Text(
                    text = "Dispatches for Court $activeSearchCourt on $searchDate",
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(8.dp))

            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                items(courtSpecificRecords) { record ->
                    RecordItem(
                        record = record,
                        onUpdateStatusClick = { recordToUpdate = record },
                        onDelete = { recordToDelete = record },
                        onFileClick = { recordForHistory = record }
                    )
                }
            }
        }
    }

    // Status & Disposal Dialog
    if (recordToUpdate != null) {
        StatusUpdateDialog(
            record = recordToUpdate!!,
            onDismiss = { recordToUpdate = null },
            onStatusSaved = { newStatus, storageLoc ->
                val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
                val newHistoryEntry = "[$timestamp] Disposal status changed to '$newStatus'" + 
                    if (storageLoc.isNotBlank()) " (Loc: $storageLoc)" else ""
                val updatedHistory = recordToUpdate!!.historyLog + "\n" + newHistoryEntry

                coroutineScope.launch {
                    dao.insertRecord(
                        recordToUpdate!!.copy(
                            status = newStatus,
                            storageLocation = storageLoc,
                            historyLog = updatedHistory
                        )
                    )
                    recordToUpdate = null
                    Toast.makeText(context, "Updated: $newStatus", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    // Soft Delete Confirmation Dialog
    if (recordToDelete != null) {
        AlertDialog(
            onDismissRequest = { recordToDelete = null },
            title = { Text("Confirm Soft Delete") },
            text = { Text("Are you sure you want to change the status of file '${recordToDelete!!.fileNo}' to 'Entry Deleted'?") },
            confirmButton = {
                Button(
                    onClick = {
                        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
                        val updatedHistory = recordToDelete!!.historyLog + "\n[$timestamp] Disposal status changed to 'Entry Deleted'"

                        coroutineScope.launch {
                            dao.insertRecord(
                                recordToDelete!!.copy(
                                    status = "Entry Deleted",
                                    historyLog = updatedHistory
                                )
                            )
                            recordToDelete = null
                            Toast.makeText(context, "Status set to 'Entry Deleted'", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Confirm Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { recordToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Audit History View Dialog
    if (recordForHistory != null) {
        AlertDialog(
            onDismissRequest = { recordForHistory = null },
            title = { Text("File History Trace: ${recordForHistory!!.fileNo}") },
            text = {
                Column {
                    if (recordForHistory!!.courtNo != "N/A") {
                        Text(text = "Court: ${recordForHistory!!.courtNo} | ${recordForHistory!!.serialNo}")
                    }
                    Text(text = "Date: ${recordForHistory!!.dispatchDate}")
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(text = "Audit History Stack Trace:", fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(6.dp))
                    
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(4.dp)
                    ) {
                        Text(
                            text = recordForHistory!!.historyLog.ifBlank { "No history recorded." },
                            fontSize = 13.sp,
                            modifier = Modifier.padding(8.dp),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClick = { recordForHistory = null }) {
                    Text("Close")
                }
            }
        )
    }
}

@Composable
fun RecordItem(
    record: FileRecord,
    onUpdateStatusClick: () -> Unit,
    onDelete: () -> Unit,
    onFileClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { onFileClick() }
                ) {
                    Text(
                        text = "File No: ${record.fileNo}",
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(Icons.Default.Info, contentDescription = "History", modifier = Modifier.size(16.dp))
                }

                StatusBadge(status = record.status)
            }

            Spacer(modifier = Modifier.height(4.dp))

            if (record.courtNo != "N/A") {
                Text(text = "Court No: ${record.courtNo} ${if (record.serialNo.isNotBlank()) "| ${record.serialNo}" else ""}")
            }
            
            if (record.storageLocation.isNotBlank()) {
                Text(
                    text = "📍 Location: ${record.storageLocation}",
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Text(text = "Date: ${record.dispatchDate}", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onUpdateStatusClick) {
                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Update Status")
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
fun StatusBadge(status: String) {
    val (bgColor, textColor) = when (status) {
        "Taken Up" -> Pair(Color(0xFFD1E7DD), Color(0xFF0F5132))
        "Pass Over" -> Pair(Color(0xFFFFF3CD), Color(0xFF664D03))
        "Handed Back to Me" -> Pair(Color(0xFFCFE2FF), Color(0xFF084298))
        "Not Sent to Court" -> Pair(Color(0xFFE2E3E5), Color(0xFF41464B))
        "Entry Deleted" -> Pair(Color(0xFFF8D7DA), Color(0xFF842029))
        else -> Pair(Color(0xFFE2E3E5), Color(0xFF41464B))
    }

    Box(
        modifier = Modifier
            .background(bgColor, shape = RoundedCornerShape(12.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = status,
            color = textColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun StatusUpdateDialog(
    record: FileRecord,
    onDismiss: () -> Unit,
    onStatusSaved: (status: String, storageLocation: String) -> Unit
) {
    var selectedStatus by remember { mutableStateOf(record.status) }
    
    var passOverChoice by remember { 
        mutableStateOf(
            when {
                record.storageLocation.startsWith("B-") -> "Bundle"
                record.storageLocation.startsWith("Person: ") -> "Person"
                else -> "Shelf"
            }
        ) 
    }
    var passOverBundleNo by remember { mutableStateOf(if (record.storageLocation.startsWith("B-")) record.storageLocation.removePrefix("B-") else "") }
    var passOverPersonName by remember { mutableStateOf(if (record.storageLocation.startsWith("Person: ")) record.storageLocation.removePrefix("Person: ") else "") }

    var handedBackChoice by remember { 
        mutableStateOf(
            when {
                record.storageLocation == "Listing Seat" -> "Listing Seat"
                record.storageLocation == "Compliance/Disposal Seat" -> "Compliance/Disposal Seat"
                record.storageLocation == "Shelf" -> "Shelf"
                record.storageLocation.startsWith("Person: ") -> "Person"
                else -> "Listing Seat"
            }
        ) 
    }
    var handedBackPersonName by remember { mutableStateOf(if (record.storageLocation.startsWith("Person: ")) record.storageLocation.removePrefix("Person: ") else "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Update Disposal & Location") },
        text = {
            Column {
                Text("File: ${record.fileNo}", fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(10.dp))

                Text("Select Disposal Category:", fontSize = 14.sp)
                
                val disposalCategories = listOf("Taken Up", "Pass Over", "Handed Back to Me", "Not Sent to Court", "Entry Deleted")
                disposalCategories.forEach { statusOption ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                    ) {
                        RadioButton(
                            selected = (selectedStatus == statusOption),
                            onClick = { selectedStatus = statusOption }
                        )
                        Text(
                            text = statusOption,
                            color = if (statusOption == "Entry Deleted") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                if (selectedStatus == "Pass Over" || selectedStatus == "Not Sent to Court") {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    Text("Storage Place:", fontWeight = FontWeight.Bold, fontSize = 14.sp)

                    listOf("Shelf", "Bundle", "Person").forEach { loc ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = (passOverChoice == loc),
                                onClick = { passOverChoice = loc }
                            )
                            Text(loc)
                        }
                    }

                    if (passOverChoice == "Bundle") {
                        OutlinedTextField(
                            value = passOverBundleNo,
                            onValueChange = { passOverBundleNo = it },
                            label = { Text("Bundle No. (e.g. 1 for B-1)") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    if (passOverChoice == "Person") {
                        OutlinedTextField(
                            value = passOverPersonName,
                            onValueChange = { passOverPersonName = it },
                            label = { Text("Enter Person's Name") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                if (selectedStatus == "Handed Back to Me") {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    Text("Handed Back Destination:", fontWeight = FontWeight.Bold, fontSize = 14.sp)

                    val destinations = listOf("Listing Seat", "Compliance/Disposal Seat", "Shelf", "Person")
                    destinations.forEach { dest ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = (handedBackChoice == dest),
                                onClick = { handedBackChoice = dest }
                            )
                            Text(dest)
                        }
                    }

                    if (handedBackChoice == "Person") {
                        OutlinedTextField(
                            value = handedBackPersonName,
                            onValueChange = { handedBackPersonName = it },
                            label = { Text("Enter Person's Name") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val finalLocation = when (selectedStatus) {
                        "Pass Over", "Not Sent to Court" -> when (passOverChoice) {
                            "Bundle" -> "B-${passOverBundleNo.trim()}"
                            "Person" -> "Person: ${passOverPersonName.trim()}"
                            else -> "Shelf"
                        }
                        "Handed Back to Me" -> if (handedBackChoice == "Person") "Person: ${handedBackPersonName.trim()}" else handedBackChoice
                        else -> ""
                    }
                    
                    onStatusSaved(selectedStatus, finalLocation)
                }
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
