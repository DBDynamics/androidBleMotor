package com.example.blemotor

import android.Manifest
import android.bluetooth.BluetoothGattService
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.blemotor.ui.theme.BleMotorTheme
import com.example.blemotor.viewmodel.BleDevice
import com.example.blemotor.viewmodel.BleViewModel

class MainActivity : ComponentActivity() {
    private val viewModel: BleViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BleMotorTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    BleApp(viewModel)
                }
            }
        }
    }
}

@Composable
fun BleApp(viewModel: BleViewModel) {
    val context = LocalContext.current
    val permissionsToRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
    } else {
        arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            Toast.makeText(context, "Permissions granted", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "Permissions denied", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(Unit) {
        val missingPermissions = permissionsToRequest.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missingPermissions.isNotEmpty()) {
            launcher.launch(permissionsToRequest)
        }
    }

    BleScreen(viewModel)
}

@Composable
fun BleScreen(viewModel: BleViewModel) {
    val scannedDevices = viewModel.scannedDevices
    val isScanning = viewModel.isScanning.value
    val connectionState = viewModel.connectionState.value
    val discoveredServices = viewModel.discoveredServices
    val sentPackets = viewModel.sentPackets.value
    val receivedPackets = viewModel.receivedPackets.value
    val verificationStatus = viewModel.verificationStatus.value
    val debugInfo = viewModel.debugInfo.value

    // Motor Control State
    // Motor 1 CMD
    val targetPosition1 = viewModel.targetPosition1.value
    val targetVelocity1 = viewModel.targetVelocity1.value
    val profileAccTime1 = viewModel.profileAccTime1.value
    val homingLevel1 = viewModel.homingLevel1.value
    val homingDir1 = viewModel.homingDir1.value
    val currentBase1 = viewModel.currentBase1.value
    val currentP1 = viewModel.currentP1.value
    val currentN1 = viewModel.currentN1.value
    val fastStopDec1 = viewModel.fastStopDec1.value

    // Motor 2 CMD
    val targetPosition2 = viewModel.targetPosition2.value
    val targetVelocity2 = viewModel.targetVelocity2.value
    val profileAccTime2 = viewModel.profileAccTime2.value
    val homingLevel2 = viewModel.homingLevel2.value
    val homingDir2 = viewModel.homingDir2.value
    val currentBase2 = viewModel.currentBase2.value
    val currentP2 = viewModel.currentP2.value
    val currentN2 = viewModel.currentN2.value
    val fastStopDec2 = viewModel.fastStopDec2.value

    // ST State (Received)
    // Motor 1 ST
    val statusWord1 = viewModel.statusWord1.value
    val actualPosition1 = viewModel.actualPosition1.value
    val actualVelocity1 = viewModel.actualVelocity1.value
    
    // Motor 2 ST
    val statusWord2 = viewModel.statusWord2.value
    val actualPosition2 = viewModel.actualPosition2.value
    val actualVelocity2 = viewModel.actualVelocity2.value
    
    // Global ST
    val force = viewModel.force.value


    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(text = "Status: $connectionState", style = MaterialTheme.typography.headlineSmall)
        
        Spacer(modifier = Modifier.height(16.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = {
                if (isScanning) {
                    viewModel.stopScan()
                } else {
                    viewModel.startScan()
                }
            }) {
                Text(text = if (isScanning) "Stop Scan" else "Start Scan")
            }
            
            Spacer(modifier = Modifier.width(16.dp))
            
            if (connectionState != "Disconnected") {
                Button(onClick = { viewModel.disconnect() }) {
                    Text("Disconnect")
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (connectionState == "Connected") {
            Column(modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
            ) {
            // Stats Card removed as requested

            // Motor Control Interface
            Text("Motor Control", style = MaterialTheme.typography.titleLarge)
            
            // Motor 1
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Text("Motor 1", style = MaterialTheme.typography.titleMedium)
                    
                    // Status (Read-Only)
                    Text("Status: SW=${statusWord1} Pos=${actualPosition1} Vel=${actualVelocity1}", 
                        style = MaterialTheme.typography.bodyMedium)

                    // Controls
                    // Row 1: Position & Velocity
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        OutlinedTextField(
                            value = targetPosition1.toString(),
                            onValueChange = { viewModel.setCmdValue({ viewModel.targetPosition1.value = it }, it.toIntOrNull() ?: 0) },
                            label = { Text("Target Pos") },
                            modifier = Modifier.weight(1f).padding(end = 4.dp)
                        )
                        OutlinedTextField(
                            value = targetVelocity1.toString(),
                            onValueChange = { viewModel.setCmdValue({ viewModel.targetVelocity1.value = it }, it.toIntOrNull() ?: 0) },
                            label = { Text("Target Vel") },
                            modifier = Modifier.weight(1f).padding(start = 4.dp)
                        )
                    }
                    
                    // Row 2: Profile Acc & Homing
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        OutlinedTextField(
                            value = profileAccTime1.toString(),
                            onValueChange = { viewModel.setCmdValue({ viewModel.profileAccTime1.value = it }, it.toIntOrNull() ?: 0) },
                            label = { Text("Acc Time") },
                            modifier = Modifier.weight(1f).padding(end = 4.dp)
                        )
                        OutlinedTextField(
                            value = homingLevel1.toString(),
                            onValueChange = { viewModel.setCmdValue({ viewModel.homingLevel1.value = it }, it.toIntOrNull() ?: 0) },
                            label = { Text("Homing Lvl") },
                            modifier = Modifier.weight(1f).padding(start = 4.dp)
                        )
                    }
                    
                    // Row 3: Homing Dir & Base
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        OutlinedTextField(
                            value = homingDir1.toString(),
                            onValueChange = { viewModel.setCmdValue({ viewModel.homingDir1.value = it }, it.toIntOrNull() ?: 0) },
                            label = { Text("Homing Dir") },
                            modifier = Modifier.weight(1f).padding(end = 4.dp)
                        )
                        OutlinedTextField(
                            value = currentBase1.toString(),
                            onValueChange = { viewModel.setCmdValue({ viewModel.currentBase1.value = it }, it.toIntOrNull() ?: 0) },
                            label = { Text("Curr Base") },
                            modifier = Modifier.weight(1f).padding(start = 4.dp)
                        )
                    }
                    
                    // Row 4: Current P/N & Fast Stop (Overlapped)
                    Text("Overlap Params (May be overwritten by Motor 2):", style = MaterialTheme.typography.labelSmall)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                         OutlinedTextField(
                            value = currentP1.toString(),
                            onValueChange = { viewModel.setCmdValue({ viewModel.currentP1.value = it }, it.toIntOrNull() ?: 0) },
                            label = { Text("Curr P") },
                            modifier = Modifier.weight(1f).padding(end = 2.dp)
                        )
                        OutlinedTextField(
                            value = currentN1.toString(),
                            onValueChange = { viewModel.setCmdValue({ viewModel.currentN1.value = it }, it.toIntOrNull() ?: 0) },
                            label = { Text("Curr N") },
                            modifier = Modifier.weight(1f).padding(horizontal = 2.dp)
                        )
                        OutlinedTextField(
                            value = fastStopDec1.toString(),
                            onValueChange = { viewModel.setCmdValue({ viewModel.fastStopDec1.value = it }, it.toIntOrNull() ?: 0) },
                            label = { Text("Fast Stop") },
                            modifier = Modifier.weight(1f).padding(start = 2.dp)
                        )
                    }

                    // Homing Button
                    Button(
                        onClick = { viewModel.setHomingMode(0) }, // Motor 1 ID = 0
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    ) {
                        Text("Home")
                    }
                }
            }
            
            // Motor 2
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Text("Motor 2", style = MaterialTheme.typography.titleMedium)
                    
                    // Status
                    Text("Status: SW=${statusWord2} Pos=${actualPosition2} Vel=${actualVelocity2}", 
                        style = MaterialTheme.typography.bodyMedium)

                    // Controls
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        OutlinedTextField(
                            value = targetPosition2.toString(),
                            onValueChange = { viewModel.setCmdValue({ viewModel.targetPosition2.value = it }, it.toIntOrNull() ?: 0) },
                            label = { Text("Target Pos") },
                            modifier = Modifier.weight(1f).padding(end = 4.dp)
                        )
                        OutlinedTextField(
                            value = targetVelocity2.toString(),
                            onValueChange = { viewModel.setCmdValue({ viewModel.targetVelocity2.value = it }, it.toIntOrNull() ?: 0) },
                            label = { Text("Target Vel") },
                            modifier = Modifier.weight(1f).padding(start = 4.dp)
                        )
                    }
                    
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                         OutlinedTextField(
                            value = profileAccTime2.toString(),
                            onValueChange = { viewModel.setCmdValue({ viewModel.profileAccTime2.value = it }, it.toIntOrNull() ?: 0) },
                            label = { Text("Acc Time") },
                            modifier = Modifier.weight(1f).padding(end = 4.dp)
                        )
                         // Padding/Spacer for alignment if needed, or add more controls
                         Spacer(modifier = Modifier.weight(1f))
                    }
                    
                     // Row 3: Homing & Base (Motor 2 specific)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        OutlinedTextField(
                            value = homingLevel2.toString(),
                            onValueChange = { viewModel.setCmdValue({ viewModel.homingLevel2.value = it }, it.toIntOrNull() ?: 0) },
                            label = { Text("Homing Lvl") },
                            modifier = Modifier.weight(1f).padding(end = 4.dp)
                        )
                        OutlinedTextField(
                            value = homingDir2.toString(),
                            onValueChange = { viewModel.setCmdValue({ viewModel.homingDir2.value = it }, it.toIntOrNull() ?: 0) },
                            label = { Text("Homing Dir") },
                            modifier = Modifier.weight(1f).padding(start = 4.dp)
                        )
                    }
                    
                    // Row 4: Base & P/N (Motor 2)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        OutlinedTextField(
                            value = currentBase2.toString(),
                            onValueChange = { viewModel.setCmdValue({ viewModel.currentBase2.value = it }, it.toIntOrNull() ?: 0) },
                            label = { Text("Curr Base") },
                            modifier = Modifier.weight(1f).padding(end = 2.dp)
                        )
                         OutlinedTextField(
                            value = currentP2.toString(),
                            onValueChange = { viewModel.setCmdValue({ viewModel.currentP2.value = it }, it.toIntOrNull() ?: 0) },
                            label = { Text("Curr P") },
                            modifier = Modifier.weight(1f).padding(horizontal = 2.dp)
                        )
                         OutlinedTextField(
                            value = currentN2.toString(),
                            onValueChange = { viewModel.setCmdValue({ viewModel.currentN2.value = it }, it.toIntOrNull() ?: 0) },
                            label = { Text("Curr N") },
                            modifier = Modifier.weight(1f).padding(start = 2.dp)
                        )
                    }
                    
                    // Row 5: Fast Stop
                    OutlinedTextField(
                        value = fastStopDec2.toString(),
                        onValueChange = { viewModel.setCmdValue({ viewModel.fastStopDec2.value = it }, it.toIntOrNull() ?: 0) },
                        label = { Text("Fast Stop") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Homing Button
                    Button(
                        onClick = { viewModel.setHomingMode(1) }, // Motor 2 ID = 1
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    ) {
                        Text("Home")
                    }
                }
            }
            
            // Global Status
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                 Column(modifier = Modifier.padding(8.dp)) {
                     Text("Global Status", style = MaterialTheme.typography.titleMedium)
                     Text("Force: $force", style = MaterialTheme.typography.bodyMedium)
                 }
            }
            
            Spacer(modifier = Modifier.height(16.dp))

            // Debug Info
            if (debugInfo.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Text("Debug Info", style = MaterialTheme.typography.titleMedium)
                        Text(text = debugInfo, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            
            // Text("Services:", style = MaterialTheme.typography.titleMedium)
            // ServiceList(discoveredServices) // Hidden to save space
            }
        } else {
            Column(modifier = Modifier.weight(1f)) {
                Text("Devices:", style = MaterialTheme.typography.titleMedium)
                DeviceList(devices = scannedDevices) { device ->
                    viewModel.connectToDevice(device.device)
                }
            }
        }
    }
}



@Composable
fun DeviceList(devices: List<BleDevice>, onDeviceClick: (BleDevice) -> Unit) {
    LazyColumn {
        items(devices) { device ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clickable { onDeviceClick(device) },
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(text = device.name ?: "Unknown Device", style = MaterialTheme.typography.bodyLarge)
                    Text(text = device.address, style = MaterialTheme.typography.bodyMedium)
                    Text(text = "RSSI: ${device.rssi}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
fun ServiceList(services: List<BluetoothGattService>) {
    LazyColumn {
        items(services) { service ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(text = "UUID: ${service.uuid}", style = MaterialTheme.typography.bodyMedium)
                    Text(text = "Type: ${if (service.type == BluetoothGattService.SERVICE_TYPE_PRIMARY) "Primary" else "Secondary"}", style = MaterialTheme.typography.bodySmall)
                    
                    if (service.characteristics.isNotEmpty()) {
                        Text(text = "Characteristics:", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
                        service.characteristics.forEach { char ->
                            Text(text = "- ${char.uuid}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }
        }
    }
}
