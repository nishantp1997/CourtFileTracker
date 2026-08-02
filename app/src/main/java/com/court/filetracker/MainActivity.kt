package com.court.filetracker

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
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

    // Dispatched Fields
    var courtNo by remember { mutableStateOf("") }
    val listTypes = listOf("DCL", "ACL", "Correction List")
    var selectedListType by remember { mutableStateOf(listTypes[0]) }
    var isListTypeExpanded by remember { mutableStateOf(false) }
    var serialNoDigits by remember { mutableStateOf("") }
    
    // Shared Field
    var fileNo by remember { mutableStateOf("") }

    // Retained Storage Fields (Not Sent)
    var retainedChoice by remember { mutableStateOf("Shelf") }
    var retainedBundleNo by remember { mutableStateOf("") }
    var retainedPersonName by remember { mutableStateOf("") }

    var searchQuery by remember { mutableStateOf("") }
    var recordToUpdate by remember { mutableStateOf<FileRecord?>(null) }

    val recordsByDate by dao.getRecordsByDate(selectedDate).collectAsState(initial = emptyList())
    val searchResults by dao.searchRecords(searchQuery).collectAsState(initial = emptyList())

    Column(modifier = Modifier.padding(16.dp)) {
        Text(
            text = "Allahabad High Court - File Movement",
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Input Card
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

                            coroutineScope.launch {
                                dao.insertRecord(
                                    FileRecord(
                                        dispatchDate = selectedDate,
                                        courtNo = if (isDispatched) courtNo.trim() else "N/A",
                                        serialNo = formattedSerialNo,
                                        fileNo = fileNo.trim(),
                                        status = statusVal,
                                        storageLocation = locVal
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

        // Search Bar
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            label = { Text("Search File No, Court, Status, Location, Person") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = if (searchQuery.isNotBlank()) "Search Results" else "Records for $selectedDate",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
        )

        Spacer(modifier = Modifier.height(8.dp))

        val displayList = if (searchQuery.isNotBlank()) searchResults else recordsByDate

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f)
        ) {
            items(displayList) { record ->
                RecordItem(
                    record = record,
                    onUpdateStatusClick = { recordToUpdate = record },
                    onDelete = {
                        coroutineScope.launch {
                            dao.deleteRecord(record)
                        }
                    }
                )
            }
        }
    }

    if (recordToUpdate != null) {
        StatusUpdateDialog(
            record = recordToUpdate!!,
            onDismiss = { recordToUpdate = null },
            onStatusSaved = { updatedRecord ->
                coroutineScope.launch {
                    dao.insertRecord(updatedRecord)
                    recordToUpdate = null
                    Toast.makeText(context, "Record Updated!", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }
}

@Composable
fun RecordItem(
    record: FileRecord,
    onUpdateStatusClick: () -> Unit,
    onDelete: () -> Unit
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
                Text(
                    text = "File No: ${record.fileNo}",
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp
                )

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
                    Text("Update / Edit")
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
        "Not Sent to Court" -> Pair(Color(0xFFF8D7DA), Color(0xFF842029))
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusUpdateDialog(
    record: FileRecord,
    onDismiss: () -> Unit,
    onStatusSaved: (updatedRecord: FileRecord) -> Unit
) {
    var isDispatchedCategory by remember { mutableStateOf(record.status != "Not Sent to Court") }
    var selectedStatus by remember { mutableStateOf(record.status) }

    var courtNo by remember { mutableStateOf(if (record.courtNo != "N/A") record.courtNo else "") }

    var passOverChoice by remember { 
        mutableStateOf(
            when {
                record.storageLocation.startsWith("B-") -> "Bundle"
                record.storageLocation.startsWith("Person: ") -> "Person"
                else -> "Shelf"
            }
        ) 
    }
    var bundleNoDigits by remember { mutableStateOf(if (record.storageLocation.startsWith("B-")) record.storageLocation.removePrefix("B-") else "") }
    var personName by remember { mutableStateOf(if (record.storageLocation.startsWith("Person: ") && record.status != "Handed Back to Me") record.storageLocation.removePrefix("Person: ") else "") }

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
    var handedBackPersonName by remember { mutableStateOf(if (record.storageLocation.startsWith("Person: ") && record.status == "Handed Back to Me") record.storageLocation.removePrefix("Person: ") else "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit File Status & Location") },
        text = {
            Column {
                Text("File No: ${record.fileNo}", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text("Date: ${record.dispatchDate}", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)

                Spacer(modifier = Modifier.height(10.dp))

                Text("Category:", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = isDispatchedCategory,
                        onClick = { 
                            isDispatchedCategory = true 
                            if (selectedStatus == "Not Sent to Court") selectedStatus = "Dispatched"
                        },
                        label = { Text("Dispatched") }
                    )
                    FilterChip(
                        selected = !isDispatchedCategory,
                        onClick = { 
                            isDispatchedCategory = false 
                            selectedStat
