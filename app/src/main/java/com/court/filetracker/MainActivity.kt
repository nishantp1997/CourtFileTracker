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
    var courtNo by remember { mutableStateOf("") }
    
    val listTypes = listOf("DCL", "ACL", "Correction List")
    var selectedListType by remember { mutableStateOf(listTypes[0]) }
    var isListTypeExpanded by remember { mutableStateOf(false) }
    var serialNoDigits by remember { mutableStateOf("") }
    var fileNo by remember { mutableStateOf("") }
    
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

        // Input Card (Morning Entry)
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(text = "☀️ Morning Dispatch Entry", fontWeight = FontWeight.Bold)

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = selectedDate,
                    onValueChange = { selectedDate = it },
                    label = { Text("Date (YYYY-MM-DD)") },
                    modifier = Modifier.fillMaxWidth()
                )

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

                Spacer(modifier = Modifier.height(10.dp))

                Button(
                    onClick = {
                        if (courtNo.isNotBlank() && serialNoDigits.isNotBlank() && fileNo.isNotBlank()) {
                            val formattedSerialNo = "$selectedListType - ${serialNoDigits.trim()}"
                            coroutineScope.launch {
                                dao.insertRecord(
                                    FileRecord(
                                        dispatchDate = selectedDate,
                                        courtNo = courtNo.trim(),
                                        serialNo = formattedSerialNo,
                                        fileNo = fileNo.trim(),
                                        status = "Dispatched"
                                    )
                                )
                                fileNo = ""
                                serialNoDigits = ""
                                Toast.makeText(context, "Record Saved!", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            Toast.makeText(context, "Please fill all fields", Toast.LENGTH_SHORT).show()
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
            label = { Text("Search File No, Court, Status, Person, or Location") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = if (searchQuery.isNotBlank()) "Search Results" else "Dispatches for $selectedDate",
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

    // Evening Status & Storage Location Dialog
    if (recordToUpdate != null) {
        StatusUpdateDialog(
            record = recordToUpdate!!,
            onDismiss = { recordToUpdate = null },
            onStatusSaved = { newStatus, storageLoc ->
                coroutineScope.launch {
                    dao.insertRecord(
                        recordToUpdate!!.copy(
                            status = newStatus,
                            storageLocation = storageLoc
                        )
                    )
                    recordToUpdate = null
                    Toast.makeText(context, "Updated: $newStatus", Toast.LENGTH_SHORT).show()
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

            Text(text = "Court No: ${record.courtNo} | ${record.serialNo}")
            
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
                    Text("Update Status / Location")
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
    
    // Pass Over Location States
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

    // Handed Back Location States
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

                Text("Select Status:", fontSize = 14.sp)
                
                listOf("Taken Up", "Pass Over", "Handed Back to Me").forEach { statusOption ->
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
                        Text(text = statusOption)
                    }
                }

                // Sub-options for Pass Over
                if (selectedStatus == "Pass Over") {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    Text("Pass Over Location:", fontWeight = FontWeight.Bold, fontSize = 14.sp)

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

                // Sub-options for Handed Back to Me
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
                        "Pass Over" -> when (passOverChoice) {
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
