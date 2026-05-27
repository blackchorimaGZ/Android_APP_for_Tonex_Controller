package com.tonex.controller.network

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.tonex.controller.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue

@SuppressLint("MissingPermission")
class TonexBleClient {

    companion object {
        private const val TAG = "TonexBleClient"
        private val MIDI_SERVICE_UUID = UUID.fromString("03b80e5a-ede8-4b33-a751-6ce34ec4c700")
        private val JSON_CHAR_UUID = UUID.fromString("7772e5db-3868-4112-a1a9-f2669d106bf4")
        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val RECONNECT_DELAY_MS = 3000L
    }

    enum class ConnectionState {
        DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING
    }

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState = _connectionState.asStateFlow()

    private val _tonexConnected = MutableStateFlow(false)
    val tonexConnected = _tonexConnected.asStateFlow()

    private var statusPollingJob: Job? = null

    private val _incomingMessages = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val incomingMessages = _incomingMessages.asSharedFlow()

    private val _stateUpdates = MutableSharedFlow<TonexState>(extraBufferCapacity = 16)
    val stateUpdates = _stateUpdates.asSharedFlow()

    private val _ccUpdates = MutableSharedFlow<CcMessage>(extraBufferCapacity = 64)
    val ccUpdates = _ccUpdates.asSharedFlow()

    private val _presetUpdates = MutableSharedFlow<Pair<Int, Int>>(extraBufferCapacity = 16)
    val presetUpdates = _presetUpdates.asSharedFlow()

    private val _presetNamesUpdates = MutableSharedFlow<List<String>>(extraBufferCapacity = 8)
    val presetNamesUpdates = _presetNamesUpdates.asSharedFlow()

    private val _presetSkinsArrayUpdates = MutableSharedFlow<List<Int>>(extraBufferCapacity = 8)
    val presetSkinsArrayUpdates = _presetSkinsArrayUpdates.asSharedFlow()

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var jsonCharacteristic: BluetoothGattCharacteristic? = null
    
    private var context: Context? = null
    private var targetDeviceName: String = ""
    private var isScanning = false
    private var shouldReconnect = true
    
    private val rxBuffer = ByteArrayOutputStream()
    private val writeQueue = ConcurrentLinkedQueue<ByteArray>()
    private var isWriting = false

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())

    private val paramRanges = mutableMapOf<Int, Pair<Double, Double>>()
    private var lastState = TonexState()

    fun connect(context: Context, targetName: String = "") {
        this.context = context.applicationContext
        this.targetDeviceName = targetName
        this.shouldReconnect = true
        
        val bluetoothManager = this.context!!.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        // Reset state to allow a fresh connection
        stopScanning()
        handler.removeCallbacksAndMessages(null)
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        jsonCharacteristic = null
        _tonexConnected.value = false
        stopStatusPolling()

        if (bluetoothAdapter == null || !bluetoothAdapter!!.isEnabled) {
            Log.e(TAG, "Bluetooth not available or disabled")
            _connectionState.value = ConnectionState.DISCONNECTED
            return
        }

        _connectionState.value = ConnectionState.CONNECTING
        startScanning()
    }

    fun disconnect() {
        shouldReconnect = false
        handler.removeCallbacksAndMessages(null)
        stopScanning()
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        jsonCharacteristic = null
        _tonexConnected.value = false
        stopStatusPolling()
        _connectionState.value = ConnectionState.DISCONNECTED
        Log.i(TAG, "Disconnected")
    }

    fun isConnected(): Boolean = _connectionState.value == ConnectionState.CONNECTED

    private fun startScanning() {
        val scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null) {
            Log.e(TAG, "Cannot start scan: bluetoothLeScanner is null")
            _connectionState.value = ConnectionState.DISCONNECTED
            return
        }
        if (isScanning) return
        isScanning = true

        Log.i(TAG, "Starting BLE scan for MIDI service...")
        
        val filter = ScanFilter.Builder()
            .setServiceUuid(android.os.ParcelUuid(MIDI_SERVICE_UUID))
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            scanner.startScan(listOf(filter), settings, scanCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting scan", e)
            isScanning = false
            _connectionState.value = ConnectionState.DISCONNECTED
            return
        }
        
        // Timeout scan after 15 seconds
        handler.postDelayed({
            if (isScanning && _connectionState.value == ConnectionState.CONNECTING) {
                Log.w(TAG, "Scan timeout, retrying...")
                stopScanning()
                scheduleReconnect()
            }
        }, 15000)
    }

    private fun stopScanning() {
        if (!isScanning) return
        isScanning = false
        try {
            val scanner = bluetoothAdapter?.bluetoothLeScanner
            if (scanner != null) {
                scanner.stopScan(scanCallback)
                Log.i(TAG, "BLE Scan stopped")
            } else {
                Log.w(TAG, "Could not stop scan: scanner is null")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping scan", e)
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name = result.scanRecord?.deviceName ?: device.name ?: ""
            Log.i(TAG, "Found device: $name (${device.address})")

            // If we have a target name filter, check it. Otherwise connect to any device advertising MIDI
            if (targetDeviceName.isEmpty() || name.contains(targetDeviceName, ignoreCase = true)) {
                stopScanning()
                connectToDevice(device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed with error code: $errorCode")
            scheduleReconnect()
        }
    }

    private fun connectToDevice(device: BluetoothDevice) {
        Log.i(TAG, "Connecting to GATT on device ${device.name ?: device.address}")
        bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Log.i(TAG, "GATT Connected. Requesting MTU change to 200...")
                    gatt.requestMtu(200)
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.i(TAG, "GATT Disconnected")
                    handleDisconnection()
                }
            } else {
                Log.e(TAG, "GATT error status: $status")
                handleDisconnection()
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.i(TAG, "MTU changed to $mtu, status: $status")
            Log.i(TAG, "Discovering services...")
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt.getService(MIDI_SERVICE_UUID)
                if (service != null) {
                    jsonCharacteristic = service.getCharacteristic(JSON_CHAR_UUID)
                    if (jsonCharacteristic != null) {
                        Log.i(TAG, "Found JSON characteristic. Enabling notifications...")
                        gatt.setCharacteristicNotification(jsonCharacteristic, true)
                        
                        val descriptor = jsonCharacteristic!!.getDescriptor(CCCD_UUID)
                        if (descriptor != null) {
                            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            gatt.writeDescriptor(descriptor)
                        } else {
                            Log.e(TAG, "CCCD descriptor not found")
                            handleDisconnection()
                        }
                    } else {
                        Log.e(TAG, "JSON Characteristic not found in MIDI Service")
                        handleDisconnection()
                    }
                } else {
                    Log.e(TAG, "MIDI Service not found")
                    handleDisconnection()
                }
            } else {
                Log.e(TAG, "Service discovery failed with status: $status")
                handleDisconnection()
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (descriptor.uuid == CCCD_UUID && status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "Notifications enabled successfully. Connected!")
                _connectionState.value = ConnectionState.CONNECTED
                
                // Trigger full sync
                scope.launch {
                    delay(500)
                    requestFullSync()
                }
                
                // Start polling modeller status after initial sync (5 seconds delay)
                scope.launch {
                    delay(5000)
                    startStatusPolling()
                }
            } else {
                Log.e(TAG, "Descriptor write failed with status: $status")
                handleDisconnection()
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == JSON_CHAR_UUID) {
                val value = characteristic.value ?: return
                rxBuffer.write(value)
                
                // Check if last byte is null-terminator
                if (value.isNotEmpty() && value[value.size - 1] == 0.toByte()) {
                    val message = String(rxBuffer.toByteArray(), Charsets.UTF_8).trimEnd('\u0000')
                    rxBuffer.reset()
                    Log.d(TAG, "Received JSON BLE: $message")
                    scope.launch {
                        _incomingMessages.emit(message)
                        parseMessage(message)
                    }
                }
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            isWriting = false
            processWriteQueue()
        }
    }

    private fun handleDisconnection() {
        jsonCharacteristic = null
        bluetoothGatt?.close()
        bluetoothGatt = null
        _tonexConnected.value = false
        stopStatusPolling()
        _connectionState.value = ConnectionState.DISCONNECTED
        scheduleReconnect()
    }

    private fun scheduleReconnect() {
        if (!shouldReconnect) return
        _connectionState.value = ConnectionState.RECONNECTING
        handler.postDelayed({
            if (shouldReconnect && _connectionState.value == ConnectionState.RECONNECTING) {
                Log.i(TAG, "Reconnecting BLE...")
                connect(context!!, targetDeviceName)
            }
        }, RECONNECT_DELAY_MS)
    }

    fun sendCc(cc: Int, value: Int) {
        val paramIndex = TonexParamMapper.ccToParamIndex(
            cc = cc,
            activeReverbType = lastState.reverb.type,
            activeModType = lastState.modulation.type,
            isTapeDelay = lastState.delay.type == DelayType.TAPE
        )

        if (paramIndex == -1) {
            Log.w(TAG, "Could not map CC $cc to parameter index. Skipping.")
            return
        }

        val (min, max) = if (paramRanges.containsKey(paramIndex)) {
            paramRanges[paramIndex]!!
        } else {
            when (paramIndex) {
                0, 1, 5, 6, 10, 30, 36, 37, 63, 64, 94, 95, 112 -> Pair(0.0, 1.0)
                38, 65, 96 -> Pair(0.0, 5.0)
                110 -> Pair(40.0, 240.0)
                else -> Pair(0.0, 10.0)
            }
        }

        val floatVal = min + (value / 127.0) * (max - min)

        val json = JSONObject().apply {
            put("CMD", "SETPARAM")
            put("INDEX", paramIndex)
            put("VALUE", floatVal)
        }
        sendRaw(json.toString())
        updateLocalStateWithCc(cc, value)
    }

    fun sendPresetChange(presetIndex: Int) {
        val json = JSONObject().apply {
            put("CMD", "SETPRESET")
            put("PRESET", presetIndex)
        }
        sendRaw(json.toString())
        lastState = lastState.copy(preset = presetIndex)
    }

    fun sendSkinChange(skinIndex: Int) {
        val json = JSONObject().apply {
            put("CMD", "SETSKIN")
            put("SKIN", skinIndex)
        }
        sendRaw(json.toString())
        lastState = lastState.copy(skinIndex = skinIndex)
    }

    fun requestFullSync() {
        scope.launch {
            Log.i(TAG, "Requesting full state sync...")
            sendRaw(JSONObject().apply { put("CMD", "GETMODELLERDATA") }.toString())
            delay(400)
            sendRaw(JSONObject().apply { put("CMD", "GETSYNCCOMPLETE") }.toString())
            delay(400)
            sendRaw(JSONObject().apply { put("CMD", "GETCONFIG") }.toString())
            delay(400)
            sendRaw(JSONObject().apply { put("CMD", "GETPRESET") }.toString())
            delay(400)
            sendRaw(JSONObject().apply { put("CMD", "GETPARAMS") }.toString())
            delay(400)
            sendRaw(JSONObject().apply { put("CMD", "GETPRESETNAMES") }.toString())
        }
    }

    fun sendRaw(message: String) {
        val gatt = bluetoothGatt ?: return
        val char = jsonCharacteristic ?: return
        
        // Append null-terminator to signal end of JSON to the ESP32
        val bytes = (message + "\u0000").toByteArray(Charsets.UTF_8)
        
        // Split into chunks of 180 bytes
        var offset = 0
        val chunkSize = 180
        while (offset < bytes.size) {
            val end = Math.min(bytes.size, offset + chunkSize)
            val chunk = bytes.copyOfRange(offset, end)
            writeQueue.add(chunk)
            offset = end
        }

        processWriteQueue()
    }

    private fun processWriteQueue() {
        val gatt = bluetoothGatt ?: return
        val char = jsonCharacteristic ?: return
        if (isWriting) return

        val chunk = writeQueue.poll() ?: return
        isWriting = true
        char.value = chunk
        char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        val success = gatt.writeCharacteristic(char)
        if (!success) {
            Log.e(TAG, "Failed to write characteristic")
            isWriting = false
        }
    }

    private suspend fun parseMessage(text: String) {
        try {
            val json = JSONObject(text)
            if (!json.has("CMD")) return

            when (json.getString("CMD")) {
                "GETPARAMS" -> {
                    if (json.has("PARAMS")) {
                        val paramsObj = json.getJSONObject("PARAMS")
                        val keys = paramsObj.keys()
                        while (keys.hasNext()) {
                            val keyStr = keys.next()
                            val index = keyStr.toIntOrNull() ?: continue
                            val param = paramsObj.getJSONObject(keyStr)
                            val value = param.getDouble("Val")
                            val min = param.optDouble("Min", 0.0)
                            val max = param.optDouble("Max", 10.0)
                            paramRanges[index] = Pair(min, max)

                            val cc = TonexParamMapper.paramIndexToCc(index)
                            if (cc != -1) {
                                val ccVal = if (max > min) {
                                    Math.round((value - min) / (max - min) * 127.0).toInt().coerceIn(0, 127)
                                } else 0
                                _ccUpdates.emit(CcMessage(cc, ccVal))
                                updateLocalStateWithCc(cc, ccVal)
                            }
                        }
                    }
                }

                "GETPRESET" -> {
                    val presetIndex = json.optInt("INDEX", 0)
                    val skinIndex = json.optInt("SKIN", 2)
                    lastState = lastState.copy(preset = presetIndex, skinIndex = skinIndex)
                    _presetUpdates.emit(Pair(presetIndex, skinIndex))
                }

                "GETPRESETNAMES" -> {
                    if (json.has("PRESET_NAMES")) {
                        val presetsObj = json.getJSONObject("PRESET_NAMES")
                        val namesMap = mutableMapOf<Int, String>()
                        val keys = presetsObj.keys()
                        while (keys.hasNext()) {
                            val keyStr = keys.next()
                            val index = keyStr.toIntOrNull() ?: continue
                            namesMap[index] = presetsObj.getString(keyStr)
                        }
                        val maxPresets = if (namesMap.isNotEmpty()) namesMap.keys.maxOrNull() ?: 19 else 19
                        val namesList = List(maxPresets + 1) { namesMap[it] ?: "Preset ${it + 1}" }
                        _presetNamesUpdates.emit(namesList)
                    }
                    if (json.has("PRESET_SKINS")) {
                        val skinsObj = json.getJSONObject("PRESET_SKINS")
                        val skinsMap = mutableMapOf<Int, Int>()
                        val keys = skinsObj.keys()
                        while (keys.hasNext()) {
                            val keyStr = keys.next()
                            val index = keyStr.toIntOrNull() ?: continue
                            skinsMap[index] = skinsObj.getInt(keyStr)
                        }
                        val maxPresets = if (skinsMap.isNotEmpty()) skinsMap.keys.maxOrNull() ?: 19 else 19
                        val skinsList = List(maxPresets + 1) { skinsMap[it] ?: 2 }
                        _presetSkinsArrayUpdates.emit(skinsList)
                    }
                }

                "GETMODELLERDATA" -> {
                    val modellerType = json.optInt("MODELLER_TYPE", 0)
                    _tonexConnected.value = modellerType != 0
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing message: $text", e)
        }
    }

    private fun updateLocalStateWithCc(cc: Int, value: Int) {
        lastState = when (cc) {
            MidiCcMap.REVERB_TYPE -> lastState.copy(reverb = lastState.reverb.copy(type = ReverbType.entries.getOrElse(value) { ReverbType.ROOM }))
            MidiCcMap.MOD_TYPE -> lastState.copy(modulation = lastState.modulation.copy(type = ModType.entries.getOrElse(value) { ModType.CHORUS }))
            MidiCcMap.DELAY_TYPE -> lastState.copy(delay = lastState.delay.copy(type = if (value == 0) DelayType.DIGITAL else DelayType.TAPE))
            else -> lastState
        }
    }

    private fun startStatusPolling() {
        stopStatusPolling()
        statusPollingJob = scope.launch {
            while (isActive && isConnected()) {
                sendRaw(JSONObject().apply { put("CMD", "GETMODELLERDATA") }.toString())
                delay(3000)
            }
        }
    }

    private fun stopStatusPolling() {
        statusPollingJob?.cancel()
        statusPollingJob = null
    }

    fun destroy() {
        disconnect()
        scope.cancel()
    }
}
