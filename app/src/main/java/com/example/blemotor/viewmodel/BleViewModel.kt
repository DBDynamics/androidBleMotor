package com.example.blemotor.viewmodel

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import java.util.UUID

data class BleDevice(
    val device: BluetoothDevice,
    val name: String?,
    val address: String,
    val rssi: Int
)

class BleViewModel(application: Application) : AndroidViewModel(application) {

    private val bluetoothManager = application.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter

    val scannedDevices = mutableStateListOf<BleDevice>()
    val isScanning = mutableStateOf(false)
    val connectionState = mutableStateOf("Disconnected")
    val discoveredServices = mutableStateListOf<BluetoothGattService>()

    // Motor Control State
    // Motor 1 CMD
    val targetPosition1 = mutableStateOf(0)   // Index 0
    val targetVelocity1 = mutableStateOf(1000)   // Index 1
    val profileAccTime1 = mutableStateOf(200)   // Index 2
    val homingLevel1 = mutableStateOf(1)      // Index 3
    val homingDir1 = mutableStateOf(1)        // Index 4
    val currentBase1 = mutableStateOf(1200)      // Index 5
    val currentP1 = mutableStateOf(3)         // Index 6 (Overlap with TP2)
    val currentN1 = mutableStateOf(3)         // Index 7 (Overlap with TV2)
    val fastStopDec1 = mutableStateOf(30)      // Index 8 (Overlap with Acc2)

    // Motor 2 CMD
    val targetPosition2 = mutableStateOf(0)   // Index 6
    val targetVelocity2 = mutableStateOf(1000)   // Index 7
    val profileAccTime2 = mutableStateOf(200)   // Index 8
    val homingLevel2 = mutableStateOf(1)      // Index 9
    val homingDir2 = mutableStateOf(1)        // Index 10
    val currentBase2 = mutableStateOf(800)      // Index 11
    val currentP2 = mutableStateOf(3)         // Index 12
    val currentN2 = mutableStateOf(3)         // Index 13
    val fastStopDec2 = mutableStateOf(30)      // Index 14

    // ST State (Received)
    // Motor 1 ST
    val statusWord1 = mutableStateOf(0)       // Index 0
    val actualPosition1 = mutableStateOf(0)   // Index 1
    val actualVelocity1 = mutableStateOf(0)   // Index 2
    
    // Motor 2 ST
    val statusWord2 = mutableStateOf(0)       // Index 3
    val actualPosition2 = mutableStateOf(0)   // Index 4
    val actualVelocity2 = mutableStateOf(0)   // Index 5
    
    // Global ST
    val force = mutableStateOf(0)             // Index 15

    // Statistics
    val sentPackets = mutableStateOf(0)
    val receivedPackets = mutableStateOf(0)
    val verificationStatus = mutableStateOf("Waiting...")

    // Debug Info
    val debugInfo = mutableStateOf("") 
    
    // Throttling State
    private var pendingSentPackets = 0
    private var pendingFailedPackets = 0 // Track failed writes
    private var pendingTimeoutPackets = 0 // Track timeouts
    private var pendingSkippedPackets = 0 // Track skipped packets due to congestion
    private var pendingReceivedPackets = 0
    private var pendingVerificationStatus = "Waiting..."
    private var pendingDebugInfo = ""
    private var pendingReceivedData: ByteArray? = null
    
    // Pending Motor Data (Buffer for UI update)
    private var pendingStatusWord1 = 0
    private var pendingActualPosition1 = 0
    private var pendingActualVelocity1 = 0
    private var pendingStatusWord2 = 0
    private var pendingActualPosition2 = 0
    private var pendingActualVelocity2 = 0
    private var pendingForce = 0
    
    private val uiStateLock = Any()
    
    // Write Flow Control
    private var writeContinuation: kotlinx.coroutines.CancellableContinuation<Boolean>? = null
    private val writeLock = Any()
    
    // Throttling Management
    private var lastPriorityRequestTime = 0L
    private val PRIORITY_COOLDOWN_MS = 10000L // 10 seconds cooldown
    
    private var currentMtu = 23 // Default BLE MTU
    private var currentTxPhy = 1 // 1M
    private var currentRxPhy = 1 // 1M

    private var sendJob: kotlinx.coroutines.Job? = null
    
    // Global Buffers (247 bytes)
    // Corresponds to BleCmdObj and BleStatusObj in README.md
    private val sendBuffer = ByteArray(247) { 0 }
    private val recvBuffer = ByteArray(247) { 0 }
    
    // SDO Logic
    data class SdoObj(
        val func: Int,
        val index: Int,
        val id: Int,
        val subId: Int,
        val sdo: Int
    )

    private val sdoQueue = ArrayDeque<SdoObj>(100)
    val valueSDO = IntArray(64)

    fun appendSdo(func: Int, index: Int, id: Int, subId: Int, sdo: Int) {
        synchronized(sdoQueue) {
            if (sdoQueue.size >= 100) {
                sdoQueue.poll() // Remove oldest if full, or we could reject
            }
            sdoQueue.add(SdoObj(func, index, id, subId, sdo))
        }
    }

    private fun popSdo(): SdoObj {
        synchronized(sdoQueue) {
            return sdoQueue.poll() ?: SdoObj(FuncFree, 0, 0, 0, 0)
        }
    }

    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    
    // Channel to signal reply received (Conflated to keep only latest)
    private val replyChannel = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)

    private var bluetoothGatt: BluetoothGatt? = null

    init {
        // Initialize send buffer with default values
        updateSendBuffer()
    }

    // Update sendBuffer from all UI states
    fun updateSendBuffer() {
        synchronized(sendBuffer) {
             val buffer = ByteBuffer.wrap(sendBuffer).order(ByteOrder.LITTLE_ENDIAN)
             
             // 1. Header (Func, Index, ID, SubID) - 4 bytes
             // Handled by startSendingLoop() from SDO Queue
             // buffer.putInt(0, 0)
             
             // 2. SDO - 4 bytes
             // Handled by startSendingLoop() from SDO Queue
             // buffer.putInt(4, 0)
             
             // 3. PDO Array - Starts at offset 8
             val pdoBaseOffset = 8
             
             // --- Motor 1 CMD ---
             buffer.putInt(pdoBaseOffset + (0 * 4), targetPosition1.value)
             buffer.putInt(pdoBaseOffset + (1 * 4), targetVelocity1.value)
             buffer.putInt(pdoBaseOffset + (2 * 4), profileAccTime1.value)
             buffer.putInt(pdoBaseOffset + (3 * 4), homingLevel1.value)
             buffer.putInt(pdoBaseOffset + (4 * 4), homingDir1.value)
             buffer.putInt(pdoBaseOffset + (5 * 4), currentBase1.value)
             // Indices 6, 7, 8 Overlap!
             // We write Motor 1 params first (if they were separate), but here we write Motor 2 params later which will overwrite them.
             // If you want Motor 1's CurrentP/N/Dec to take precedence, move them after Motor 2 writes.
             // Assuming Motor 2 Target Position/Velocity are more critical for standard operation, we let Motor 2 overwrite.
             buffer.putInt(pdoBaseOffset + (6 * 4), currentP1.value)
             buffer.putInt(pdoBaseOffset + (7 * 4), currentN1.value)
             buffer.putInt(pdoBaseOffset + (8 * 4), fastStopDec1.value)
             
             // --- Motor 2 CMD ---
             // These overwrite indices 6, 7, 8
             buffer.putInt(pdoBaseOffset + (6 * 4), targetPosition2.value)
             buffer.putInt(pdoBaseOffset + (7 * 4), targetVelocity2.value)
             buffer.putInt(pdoBaseOffset + (8 * 4), profileAccTime2.value)
             
             buffer.putInt(pdoBaseOffset + (9 * 4), homingLevel2.value)
             buffer.putInt(pdoBaseOffset + (10 * 4), homingDir2.value)
             buffer.putInt(pdoBaseOffset + (11 * 4), currentBase2.value)
             buffer.putInt(pdoBaseOffset + (12 * 4), currentP2.value)
             buffer.putInt(pdoBaseOffset + (13 * 4), currentN2.value)
             buffer.putInt(pdoBaseOffset + (14 * 4), fastStopDec2.value)
        }
    }
    
    // Helper to update state and buffer together
    fun setCmdValue(setter: (Int) -> Unit, value: Int) {
        setter(value)
        updateSendBuffer()
    }

    companion object {
        // Specific UUIDs for Simple Peripheral
        val SERVICE_UUID: UUID = UUID.fromString("0000FFE0-0000-1000-8000-00805F9B34FB")
        val CHARACTERISTIC_WRITE_UUID: UUID = UUID.fromString("0000FFE3-0000-1000-8000-00805F9B34FB") // FFE3 for Write
        val CHARACTERISTIC_NOTIFY_UUID: UUID = UUID.fromString("0000FFE4-0000-1000-8000-00805F9B34FB") // FFE4 for Notify
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
        
        const val TARGET_MTU = 247 // Aligned with Firmware: PDU 251 - 4 (L2CAP) = 247 MTU

        // Func Codes
        const val FuncRead = 0
        const val FuncWrite = 1
        const val FuncRead_OK = 2
        const val FuncWrite_OK = 3
        const val FuncOperation = 4
        const val FuncOperation_OK = 5
        const val FuncFree = 255

        // SDO Indices
        // const val BoardTypeIndex = 0
        const val DeviceIDIndex = 1
        const val ControlWordIndex = 2
        const val OperationModeIndex = 3
        const val StatusWordIndex = 4
        const val TargetCurrentIndex = 5
        const val ActualCurrentIndex = 6
        const val TargetVelocityIndex = 7
        const val ActualVelocityIndex = 8
        const val TargetPositionIndex = 9
        const val ActualPositionIndex = 10
        const val ProfileAccTimeIndex = 11
        const val InterpolationTargetPostionIndex = 12
        const val HomingModeIndex = 13
        const val HomingDirIndex = 14
        const val HomingLevelIndex = 15
        const val HomingOffsetIndex = 16
        const val CurrentBaseIndex = 17
        const val CurrentPIndex = 18
        const val CurrentNIndex = 19
        // const val RuntoKeepTimeIndex = 20
        // const val BoostTimeIndex = 21
        const val IoInIndex = 22
        // const val IoOutIndex = 23
        // const val EncoderOffsetIndex = 24
        // const val EncoderPolarityIndex = 25
        // const val EncoderValueIndex = 26
        const val FastStopDecIndex = 28
        // const val LedOptionIndex = 29
        const val SystemCounterIndex = 30
        // const val LedCounterIndex = 31

        // Operation Modes
        const val OPMODE_PWM = 0
        const val OPMODE_SVPWM = 1

        const val OPMODE_TORQUE = 10
        const val OPMODE_SYNC_TORQUE = 11

        const val OPMODE_VELOCITY = 20
        const val OPMODE_PROFILE_VELOCITY = 21
        const val OPMODE_INTERPOLATION_VELOCITY = 22
        const val OPMODE_PROFILE_VELOCITY_SYNC = 23
        const val OPMODE_INTERPOLATION_VELOCITY_SYNC = 24

        const val OPMODE_POSITION = 30
        const val OPMODE_PROFILE_POSITION = 31
        const val OPMODE_INTERPOLATION_POSITION = 32
        const val OPMODE_PROFILE_POSITION_SYNC = 33
        const val OPMODE_INTERPOLATION_POSITION_SYNC = 34
        const val OPMODE_POSITION_ENCODER = 35
        const val OPMODE_SENSOR_FLIP = 36

        const val OPMODE_HOMING = 40

        const val OPMODE_COS = 50
        const val OPMODE_ESTOP_PROFILE = 61
    }

    // Helper functions for SDO commands
    fun setHomingMode(id: Int) {
        appendSdo(
            func = Companion.FuncWrite,
            index = Companion.OperationModeIndex,
            id = id,
            subId = 0x0,
            sdo = Companion.OPMODE_HOMING
        )

        // Set TargetPosition and HomingLevel to 0 as required
        if (id == 0) {
            targetPosition1.value = 0
            homingLevel1.value = 0
        } else if (id == 1) {
            targetPosition2.value = 0
            homingLevel2.value = 0
        }
        updateSendBuffer()
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val existingDevice = scannedDevices.find { it.address == device.address }
            if (existingDevice == null) {
                scannedDevices.add(
                    BleDevice(
                        device = device,
                        name = device.name ?: "Unknown",
                        address = device.address,
                        rssi = result.rssi
                    )
                )
            } else {
                 val index = scannedDevices.indexOf(existingDevice)
                 scannedDevices[index] = existingDevice.copy(rssi = result.rssi, name = device.name ?: existingDevice.name)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e("BleViewModel", "Scan failed with error: $errorCode")
            isScanning.value = false
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.d("BleViewModel", "onConnectionStateChange: status=$status, newState=$newState")
            
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w("BleViewModel", "Error onConnectionStateChange: status=$status")
                // If we receive an error status, we should generally consider it a disconnection or failure
                // unexpected disconnect
            }

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connectionState.value = "Connected"
                Log.d("BleViewModel", "Connected to GATT server.")
                
                // Reset stats on new connection
                viewModelScope.launch(Dispatchers.Main) {
                    sentPackets.value = 0
                    receivedPackets.value = 0
                    verificationStatus.value = "Waiting..."
                    debugInfo.value = ""
                }
                
                synchronized(uiStateLock) {
                    currentMtu = 23 // Reset MTU
                    currentTxPhy = 1
                    currentRxPhy = 1
                    pendingSentPackets = 0
                    pendingFailedPackets = 0
                    pendingTimeoutPackets = 0
                    pendingSkippedPackets = 0
                    pendingReceivedPackets = 0
                    pendingVerificationStatus = "Waiting..."
                    pendingDebugInfo = ""
                    pendingReceivedData = null
                }

                // Step 1: Request MTU with a delay for stability
                viewModelScope.launch(Dispatchers.IO) {
                    kotlinx.coroutines.delay(600) // Wait 600ms before requesting MTU
                    if (connectionState.value == "Connected") {
                        val mtuResult = gatt.requestMtu(TARGET_MTU)
                        Log.d("BleViewModel", "Requesting MTU $TARGET_MTU: $mtuResult")
                        if (!mtuResult) {
                             // Fallback if MTU request fails locally
                             gatt.discoverServices()
                        }
                    }
                }

            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connectionState.value = "Disconnected"
                Log.d("BleViewModel", "Disconnected from GATT server.")
                stopSendingLoop()
                discoveredServices.clear()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.d("BleViewModel", "onMtuChanged: mtu=$mtu, status=$status")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                synchronized(uiStateLock) {
                    currentMtu = mtu
                }
                
                // Use coroutine to sequence the setup steps with delays to prevent stack flooding
                viewModelScope.launch(Dispatchers.IO) {
                    // Step 2: Request 2M PHY if supported (API 26+)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        Log.d("BleViewModel", "Requesting 2M PHY")
                        gatt.setPreferredPhy(
                            BluetoothDevice.PHY_LE_2M_MASK,
                            BluetoothDevice.PHY_LE_2M_MASK,
                            BluetoothDevice.PHY_OPTION_NO_PREFERRED
                        )
                        kotlinx.coroutines.delay(300) // Wait for PHY negotiation to start
                        // Read current PHY to confirm (might still be old, but good to check)
                        gatt.readPhy()
                    }
                    
                    kotlinx.coroutines.delay(300) // Wait before service discovery

                    // Step 3: Discover Services
                    Log.d("BleViewModel", "Starting Service Discovery")
                    gatt.discoverServices()
                }
            } else {
                Log.e("BleViewModel", "MTU Change Failed. Status: $status. Proceeding to discovery anyway.")
                gatt.discoverServices()
            }
            tryUpdateUi()
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d("BleViewModel", "Services discovered: ${gatt.services.size}")
                viewModelScope.launch(Dispatchers.Main) {
                    discoveredServices.clear()
                    discoveredServices.addAll(gatt.services)
                }
                
                // Step 4: Enable Notifications for FFE4 and Setup Write for FFE3
                val service = gatt.getService(SERVICE_UUID)
                if (service != null) {
                    val notifyChar = service.getCharacteristic(CHARACTERISTIC_NOTIFY_UUID)
                    val writeChar = service.getCharacteristic(CHARACTERISTIC_WRITE_UUID)
                    
                    if (notifyChar != null) {
                        enableNotifications(gatt, notifyChar)
                    } else {
                        Log.w("BleViewModel", "Notify Characteristic $CHARACTERISTIC_NOTIFY_UUID not found")
                    }

                    if (writeChar != null) {
                        writeCharacteristic = writeChar
                        
                        // Force WRITE_TYPE_NO_RESPONSE as requested
                        Log.d("BleViewModel", "Forcing WRITE_TYPE_NO_RESPONSE configuration")
                        writeChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                        
                        startSendingLoop()
                    } else {
                        Log.w("BleViewModel", "Write Characteristic $CHARACTERISTIC_WRITE_UUID not found")
                    }
                } else {
                    Log.w("BleViewModel", "Service $SERVICE_UUID not found")
                }

            } else {
                Log.w("BleViewModel", "onServicesDiscovered received: $status")
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            // For older Android versions or if newer callback not implemented/called
            // (Note: On API 33+, the system might call the new overload. We should handle both or rely on the system behavior.)
            // To be safe and compatible, we extract the value and delegate.
             @Suppress("DEPRECATION")
             val value = characteristic.value
             processCharacteristicData(characteristic, value)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
             processCharacteristicData(characteristic, value)
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            super.onCharacteristicWrite(gatt, characteristic, status)
            if (characteristic.uuid == CHARACTERISTIC_WRITE_UUID) {
                synchronized(writeLock) {
                    writeContinuation?.resume(status == BluetoothGatt.GATT_SUCCESS)
                    writeContinuation = null
                }
            }
        }

        override fun onPhyUpdate(gatt: BluetoothGatt, txPhy: Int, rxPhy: Int, status: Int) {
            super.onPhyUpdate(gatt, txPhy, rxPhy, status)
            Log.d("BleViewModel", "onPhyUpdate: txPhy=$txPhy, rxPhy=$rxPhy, status=$status")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                synchronized(uiStateLock) {
                    currentTxPhy = txPhy
                    currentRxPhy = rxPhy
                }
                tryUpdateUi()
            }
        }

        override fun onPhyRead(gatt: BluetoothGatt, txPhy: Int, rxPhy: Int, status: Int) {
            super.onPhyRead(gatt, txPhy, rxPhy, status)
            Log.d("BleViewModel", "onPhyRead: txPhy=$txPhy, rxPhy=$rxPhy, status=$status")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                synchronized(uiStateLock) {
                    currentTxPhy = txPhy
                    currentRxPhy = rxPhy
                }
                tryUpdateUi()
            }
        }
    }

    private var lastUiUpdateTime = 0L
    private val UI_UPDATE_INTERVAL_MS = 50L // Throttle UI updates to ~20 FPS

    private fun tryUpdateUi() {
        val currentTime = System.currentTimeMillis()
        var updateNeeded = false
        
        // Capture current state safely
        var sent = 0
        var received = 0
        var failed = 0
        var status = ""
        var debug = ""
        var data: ByteArray? = null
        
        // Local vars for parsed ST data
        var sw1 = 0
        var ap1 = 0
        var av1 = 0
        var sw2 = 0
        var ap2 = 0
        var av2 = 0
        var f = 0
        
        synchronized(uiStateLock) {
            if (currentTime - lastUiUpdateTime >= UI_UPDATE_INTERVAL_MS) {
                lastUiUpdateTime = currentTime
                updateNeeded = true
                
                sent = pendingSentPackets
                received = pendingReceivedPackets
                failed = pendingFailedPackets
                status = pendingVerificationStatus
                debug = pendingDebugInfo
                data = pendingReceivedData
                
                sw1 = pendingStatusWord1
                ap1 = pendingActualPosition1
                av1 = pendingActualVelocity1
                
                sw2 = pendingStatusWord2
                ap2 = pendingActualPosition2
                av2 = pendingActualVelocity2
                
                f = pendingForce
            }
        }

        if (updateNeeded) {
            viewModelScope.launch(Dispatchers.Main) {
                sentPackets.value = sent
                receivedPackets.value = received
                verificationStatus.value = status
                
                statusWord1.value = sw1
                actualPosition1.value = ap1
                actualVelocity1.value = av1
                
                statusWord2.value = sw2
                actualPosition2.value = ap2
                actualVelocity2.value = av2
                
                force.value = f
                
                // Append failure count to debug info if relevant
                var extraInfo = ""
                synchronized(uiStateLock) {
                    extraInfo += "\nMTU: $currentMtu | PHY: Tx=$currentTxPhy Rx=$currentRxPhy"
                    if (failed > 0) extraInfo += "\nFailed Sends: $failed"
                    if (pendingTimeoutPackets > 0) extraInfo += "\nTimeouts: $pendingTimeoutPackets"
                    if (pendingSkippedPackets > 0) extraInfo += "\nSkipped: $pendingSkippedPackets"
                }
                
                debugInfo.value = if (extraInfo.isNotEmpty()) "$debug$extraInfo" else debug
            }
        }
    }

    private fun processCharacteristicData(characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        if (characteristic.uuid == CHARACTERISTIC_NOTIFY_UUID) {
             synchronized(uiStateLock) {
                 pendingReceivedPackets += 1
                 
                 // Parse msgObj structure:
                 // Header (4 bytes) + SDO (4 bytes) + PDO[] (Starts at offset 8)
                 
                 // Need to read up to Index 15 (Force).
                 // Offset = 8 + 15*4 = 68 bytes.
                 // The message might be smaller if we only care about first few.
                 // But let's try to read as much as available.
                 
                 if (value.size >= 8) {
                     // Update global recvBuffer
                     synchronized(recvBuffer) {
                         val copyLen = minOf(value.size, 247)
                         System.arraycopy(value, 0, recvBuffer, 0, copyLen)
                     }

                     val buffer = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN)
                     val pdoBaseOffset = 8
                     
                     // Helper to read safely
                     fun getIntSafe(index: Int): Int {
                         val offset = pdoBaseOffset + (index * 4)
                         return if (offset + 4 <= value.size) buffer.getInt(offset) else 0
                     }
                     
                     // SDO Response Handling
                     val func = value[0].toInt() and 0xFF
                     val index = value[1].toInt() and 0xFF
                     val id = value[2].toInt() and 0xFF
                     // val subId = value[3].toInt() and 0xFF
                     val sdoVal = buffer.getInt(4) // Bytes 4-7

                     if (func == FuncRead_OK) {
                         if (id == 0) {
                             if (index < 32) {
                                 valueSDO[index] = sdoVal
                                 Log.d("BleViewModel", "SDO Read OK: ID=0 Index=$index Val=$sdoVal")
                             }
                         } else if (id == 1) {
                             if (index + 32 < valueSDO.size) {
                                 valueSDO[index + 32] = sdoVal
                                 Log.d("BleViewModel", "SDO Read OK: ID=1 Index=$index Val=$sdoVal")
                             }
                         }
                     }

                     // Motor 1 ST
                     val sw1 = getIntSafe(0) // Index 0: StatusWord
                     val ap1 = getIntSafe(1) // Index 1: ActualPosition
                     val av1 = getIntSafe(2) // Index 2: ActualVelocity
                     
                     // Motor 2 ST
                     val sw2 = getIntSafe(3) // Index 3: StatusWord1
                     val ap2 = getIntSafe(4) // Index 4: ActualPosition1
                     val av2 = getIntSafe(5) // Index 5: ActualVelocity1
                     
                     // Global
                     val f = getIntSafe(15) // Index 15: Force
                     
                     // Store parsed values to update UI later
                     pendingStatusWord1 = sw1
                     pendingActualPosition1 = ap1
                     pendingActualVelocity1 = av1
                     
                     pendingStatusWord2 = sw2
                     pendingActualPosition2 = ap2
                     pendingActualVelocity2 = av2
                     
                     pendingForce = f
                     
                     pendingVerificationStatus = "Parsed: AP1=$ap1, AP2=$ap2, F=$f"
                     pendingDebugInfo = "Last Received: SW1=$sw1 AP1=$ap1 AV1=$av1 | SW2=$sw2 AP2=$ap2 AV2=$av2 | F=$f"
                 } else {
                     pendingVerificationStatus = "Failed: Data too short (${value.size})"
                     pendingDebugInfo = "Data too short for msgObj header"
                 }
                 
                 pendingReceivedData = value
             }
             tryUpdateUi()
             
             // Signal that reply is received to trigger next send
             replyChannel.trySend(Unit)
        }
    }
    
    private fun startSendingLoop() {
        stopSendingLoop() // Stop any existing loop
        sendJob = viewModelScope.launch(Dispatchers.IO) {
            var baseByte = 0
            var lastSendTime = 0L
            var slowRttCount = 0 // Track consecutive slow packets
            
            // Log the write type being used for diagnosis
            val typeStr = if (writeCharacteristic?.writeType == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) 
                "NO_RESPONSE (Fast)" else "DEFAULT (With ACK)"
            Log.i("BleViewModel", "Starting Send Loop with mode: $typeStr")

            while (isActive && connectionState.value == "Connected" && writeCharacteristic != null) {
                // Check MTU but don't block. Just warn if low.
                if (currentMtu < 247) {
                    if (baseByte % 10 == 0) { // Log occasionally
                        Log.w("BleViewModel", "Current MTU ($currentMtu) is less than target 247. Performance may be degraded.")
                    }
                }

                // Drain old replies
                replyChannel.tryReceive()
                
                val loopStartTime = System.currentTimeMillis()
                
                // Calculate RTT
                if (lastSendTime > 0) {
                    val interval = loopStartTime - lastSendTime
                    Log.d("BleViewModel", "RTT: ${interval}ms")
                    
                    // Since we are now using a slow 1s interval, we don't need aggressive throttling countermeasures.
                    // System will naturally drop to low power mode, which is fine for 1Hz.
                }
                lastSendTime = loopStartTime

                try {
                    // Send data from global sendBuffer
                    // Calculate valid payload size: min(247, MTU - 3)
                    // standard overhead is 3 bytes (1 opcode + 2 handle)
                    val maxPayload = if (currentMtu > 3) currentMtu - 3 else 20
                    val payloadSize = minOf(247, maxPayload)
                    
                    val dataToSend = ByteArray(payloadSize)
                    synchronized(sendBuffer) {
                        // Inject SDO from Queue
                        val sdoObj = popSdo()
                        // Manual packing or use ByteBuffer
                        // Func, Index, ID, SubID
                        sendBuffer[0] = sdoObj.func.toByte()
                        sendBuffer[1] = sdoObj.index.toByte()
                        sendBuffer[2] = sdoObj.id.toByte()
                        sendBuffer[3] = sdoObj.subId.toByte()
                        // SDO value (Int) - Little Endian
                        val sdoVal = sdoObj.sdo
                        sendBuffer[4] = (sdoVal and 0xFF).toByte()
                        sendBuffer[5] = ((sdoVal shr 8) and 0xFF).toByte()
                        sendBuffer[6] = ((sdoVal shr 16) and 0xFF).toByte()
                        sendBuffer[7] = ((sdoVal shr 24) and 0xFF).toByte()

                        System.arraycopy(sendBuffer, 0, dataToSend, 0, payloadSize)
                    }
                    
                    val char = writeCharacteristic!!
                    var success = false
                    
                    try {
                        val writeType = char.writeType
                        
                        // If using WRITE_TYPE_NO_RESPONSE, we don't need to wait for onCharacteristicWrite
                        if (writeType == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) {
                            var initiated = false
                            try {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    val result = bluetoothGatt?.writeCharacteristic(char, dataToSend, writeType)
                                    initiated = (result == android.bluetooth.BluetoothStatusCodes.SUCCESS)
                                } else {
                                    @Suppress("DEPRECATION")
                                    char.value = dataToSend
                                    @Suppress("DEPRECATION")
                                    char.writeType = writeType
                                    @Suppress("DEPRECATION")
                                    initiated = bluetoothGatt?.writeCharacteristic(char) == true
                                }
                            } catch (e: Exception) {
                                Log.e("BleViewModel", "Exception initiating write", e)
                                initiated = false
                            }
                            success = initiated
                        } else {
                            // For WRITE_TYPE_DEFAULT, we MUST wait for the callback
                            // Increased timeout to 3000ms to handle connection interval updates or retransmissions
                            success = kotlinx.coroutines.withTimeout(3000) { 
                                 suspendCancellableCoroutine<Boolean> { cont ->
                                     synchronized(writeLock) {
                                         writeContinuation = cont
                                     }
                                     
                                     var initiated = false
                                     try {
                                         if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                             val result = bluetoothGatt?.writeCharacteristic(char, dataToSend, writeType)
                                             initiated = (result == android.bluetooth.BluetoothStatusCodes.SUCCESS)
                                         } else {
                                             @Suppress("DEPRECATION")
                                             char.value = dataToSend
                                             @Suppress("DEPRECATION")
                                             char.writeType = writeType
                                             @Suppress("DEPRECATION")
                                             initiated = bluetoothGatt?.writeCharacteristic(char) == true
                                         }
                                     } catch (e: Exception) {
                                         Log.e("BleViewModel", "Exception initiating write", e)
                                         initiated = false
                                     }
                                     
                                     if (!initiated) {
                                         synchronized(writeLock) {
                                             if (writeContinuation == cont) {
                                                 writeContinuation = null
                                             }
                                         }
                                         if (cont.isActive) {
                                             Log.w("BleViewModel", "Write initiation failed immediately.")
                                             // This usually means the stack is busy or MTU is too small for payload
                                             cont.resume(false)
                                         }
                                     }
                                 }
                            }
                        }
                    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                        Log.w("BleViewModel", "Write timed out")
                        synchronized(writeLock) {
                            writeContinuation = null
                        }
                        synchronized(uiStateLock) {
                            pendingTimeoutPackets += 1
                            pendingDebugInfo = "Last Error: Write Timeout"
                        }
                        success = false
                    }
                    
                    if (success) {
                        synchronized(uiStateLock) {
                            pendingSentPackets += 1
                            pendingDebugInfo = "Last Send: Success" // Clear error
                        }
                        // Send successful, now wait for Reply
                        try {
                            // Timeout set to 100ms to match the sending cycle
                            kotlinx.coroutines.withTimeout(100) {
                                replyChannel.receive()
                            }
                        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                            Log.w("BleViewModel", "Reply timed out")
                            synchronized(uiStateLock) {
                                pendingTimeoutPackets += 1
                                pendingDebugInfo = "Reply Timeout (MTU=$currentMtu)"
                            }
                        }
                    } else {
                        synchronized(uiStateLock) {
                            pendingFailedPackets += 1
                            if (pendingDebugInfo.isEmpty() || !pendingDebugInfo.startsWith("Last Error")) {
                                pendingDebugInfo = "Write Init Failed (Len=${dataToSend.size}, MTU=$currentMtu)"
                            }
                        }
                    }
                    
                    tryUpdateUi()
                    
                    // baseByte++ // No longer using incremental byte
                } catch (e: Exception) {
                    Log.e("BleViewModel", "Error sending data", e)
                }
                
                // Enforce 100ms interval (10Hz)
                val elapsed = System.currentTimeMillis() - loopStartTime
                if (elapsed < 100) {
                    kotlinx.coroutines.delay(100 - elapsed)
                }
            }
        }
    }

    private fun stopSendingLoop() {
        sendJob?.cancel()
        sendJob = null
    }
    
    @SuppressLint("MissingPermission")
    private fun enableNotifications(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        val success = gatt.setCharacteristicNotification(characteristic, true)
        if (success) {
            val descriptor = characteristic.getDescriptor(CCCD_UUID)
            if (descriptor != null) {
                // Determine value based on properties (Notify or Indicate)
                // The prompt says "Properties : Notify" -> Write 0x01 0x00
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                     gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                     @Suppress("DEPRECATION")
                     descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                     @Suppress("DEPRECATION")
                     gatt.writeDescriptor(descriptor)
                }
                Log.d("BleViewModel", "Enabling notifications for ${characteristic.uuid}")
            } else {
                 Log.e("BleViewModel", "CCCD Descriptor not found for ${characteristic.uuid}")
            }
        } else {
            Log.e("BleViewModel", "setCharacteristicNotification failed for ${characteristic.uuid}")
        }
    }

    @SuppressLint("MissingPermission")
    fun requestHighPriority() {
        val gatt = bluetoothGatt ?: return
        Log.d("BleViewModel", "Requesting CONNECTION_PRIORITY_HIGH")
        gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
    }

    @SuppressLint("MissingPermission")
    fun startScan() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            Log.e("BleViewModel", "Bluetooth not enabled")
            return
        }
        
        if (isScanning.value) return

        scannedDevices.clear()
        bluetoothAdapter.bluetoothLeScanner?.startScan(scanCallback)
        isScanning.value = true
        Log.d("BleViewModel", "Scan started")
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        isScanning.value = false
        Log.d("BleViewModel", "Scan stopped")
    }

    @SuppressLint("MissingPermission")
    fun connectToDevice(device: BluetoothDevice) {
        stopScan()
        bluetoothGatt?.close()
        // connectGatt with autoConnect=false is recommended for faster initial connection
        bluetoothGatt = device.connectGatt(getApplication(), false, gattCallback)
        connectionState.value = "Connecting..."
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        connectionState.value = "Disconnected"
        discoveredServices.clear()
    }

    override fun onCleared() {
        super.onCleared()
        disconnect()
    }
}
