package com.tonex.controller.network

import android.util.Log
import com.tonex.controller.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Manages WebSocket connection to the TonexOneController ESP32-S3.
 *
 * Communication protocol uses uppercase "CMD" keys:
 *   - GETPARAMS: requests all parameter values, mins, maxes
 *   - GETPRESET: requests current preset index and skin index
 *   - GETPRESETNAMES: requests the names of all presets
 *   - GETCONFIG: requests system configuration
 *   - SETPRESET: switches active preset
 *   - SETPARAM: modifies parameter values
 *   - SETSKIN: modifies active skin index
 */
class TonexWebSocketClient {

    companion object {
        private const val TAG = "TonexWSClient"
        private const val RECONNECT_DELAY_MS = 2000L
        private const val MAX_RECONNECT_DELAY_MS = 30000L
        private const val PING_INTERVAL_MS = 10000L
        private const val CONNECTION_TIMEOUT_S = 10L
    }

    // ── Connection state ────────────────────────────────────────────────────

    enum class ConnectionState {
        DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING
    }

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState = _connectionState.asStateFlow()

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

    // ── Internal state ──────────────────────────────────────────────────────

    private var webSocket: WebSocket? = null
    private var serverUrl: String = ""
    private var reconnectAttempts = 0
    private var shouldReconnect = true
    private var reconnectJob: Job? = null
    private var pollingJob: Job? = null

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Cache to track min/max for parameter scaling
    private val paramRanges = mutableMapOf<Int, Pair<Double, Double>>()

    // Local state tracking to know active sub-models when mapping CCs
    private var lastState = TonexState()

    private val client = OkHttpClient.Builder()
        .connectTimeout(CONNECTION_TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)       // no read timeout for WS
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    // ── Public API ──────────────────────────────────────────────────────────

    /**
     * Connect to the ESP32 WebSocket server.
     */
    fun connect(host: String, port: Int = 80) {
        shouldReconnect = true
        reconnectAttempts = 0
        serverUrl = "ws://$host:$port/ws"

        Log.i(TAG, "Connecting to $serverUrl")
        _connectionState.value = ConnectionState.CONNECTING
        doConnect()
    }

    /**
     * Gracefully disconnect and stop reconnect attempts.
     */
    fun disconnect() {
        shouldReconnect = false
        pollingJob?.cancel()
        reconnectJob?.cancel()
        webSocket?.close(1000, "User disconnect")
        webSocket = null
        _connectionState.value = ConnectionState.DISCONNECTED
        Log.i(TAG, "Disconnected")
    }

    /**
     * Send a MIDI CC change to the ESP32 (translated to SETPARAM).
     */
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

        // Get the min and max for this parameter index. Fall back to standard defaults if not synced yet.
        val (min, max) = if (paramRanges.containsKey(paramIndex)) {
            paramRanges[paramIndex]!!
        } else {
            // Default ranges for common parameters
            when (paramIndex) {
                0, 1, 5, 6, 10, 30, 36, 37, 63, 64, 94, 95, 112 -> Pair(0.0, 1.0) // switches/positions
                38, 65, 96 -> Pair(0.0, 5.0) // models/types
                110 -> Pair(40.0, 240.0) // BPM
                else -> Pair(0.0, 10.0) // standard knobs (Gain, Volume, etc.)
            }
        }

        val floatVal = min + (value / 127.0) * (max - min)

        val json = JSONObject().apply {
            put("CMD", "SETPARAM")
            put("INDEX", paramIndex)
            put("VALUE", floatVal)
        }
        sendRaw(json.toString())

        // Update local cache and state representation
        updateLocalStateWithCc(cc, value)
    }

    /**
     * Send a preset change command.
     */
    fun sendPresetChange(presetIndex: Int) {
        val json = JSONObject().apply {
            put("CMD", "SETPRESET")
            put("PRESET", presetIndex)
        }
        sendRaw(json.toString())
        lastState = lastState.copy(preset = presetIndex)
    }

    /**
     * Send a skin change command.
     */
    fun sendSkinChange(skinIndex: Int) {
        val json = JSONObject().apply {
            put("CMD", "SETSKIN")
            put("SKIN", skinIndex)
        }
        sendRaw(json.toString())
        lastState = lastState.copy(skinIndex = skinIndex)
    }

    /**
     * Request full state sync from the ESP32.
     */
    fun requestFullSync() {
        scope.launch {
            Log.i(TAG, "Requesting full state sync...")
            sendRaw(JSONObject().apply { put("CMD", "GETMODELLERDATA") }.toString())
            delay(300)
            sendRaw(JSONObject().apply { put("CMD", "GETSYNCCOMPLETE") }.toString())
            delay(300)
            sendRaw(JSONObject().apply { put("CMD", "GETCONFIG") }.toString())
            delay(300)
            sendRaw(JSONObject().apply { put("CMD", "GETPRESET") }.toString())
            delay(300)
            sendRaw(JSONObject().apply { put("CMD", "GETPARAMS") }.toString())
            delay(300)
            sendRaw(JSONObject().apply { put("CMD", "GETPRESETNAMES") }.toString())
        }
    }

    /**
     * Send raw JSON string to the WebSocket.
     */
    fun sendRaw(message: String) {
        val ws = webSocket
        if (ws != null && _connectionState.value == ConnectionState.CONNECTED) {
            val sent = ws.send(message)
            if (!sent) {
                Log.w(TAG, "Failed to send message: $message")
            } else {
                Log.d(TAG, "Sent: $message")
            }
        } else {
            Log.w(TAG, "Cannot send, not connected. Message: $message")
        }
    }

    /**
     * Check if currently connected.
     */
    fun isConnected(): Boolean = _connectionState.value == ConnectionState.CONNECTED

    // ── Internal connection logic ───────────────────────────────────────────

    private fun doConnect() {
        synchronized(this) {
            pollingJob?.cancel()
            reconnectJob?.cancel()
            try {
                webSocket?.close(1000, "Reconnecting")
            } catch (e: Exception) {
                Log.e(TAG, "Error closing previous websocket: ${e.message}")
            }
            webSocket = null
        }

        val request = Request.Builder()
            .url(serverUrl)
            .build()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket connected to $serverUrl")
                synchronized(this@TonexWebSocketClient) {
                    _connectionState.value = ConnectionState.CONNECTED
                    reconnectAttempts = 0
                }

                // Request full state sync upon connection
                scope.launch {
                    delay(500) // Small delay to stabilize
                    requestFullSync()
                }

                // Start polling for changes
                startPolling()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "Received: $text")
                scope.launch {
                    _incomingMessages.emit(text)
                    parseMessage(text)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closing: $code / $reason")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closed: $code / $reason")
                synchronized(this@TonexWebSocketClient) {
                    _connectionState.value = ConnectionState.DISCONNECTED
                }
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}", t)
                synchronized(this@TonexWebSocketClient) {
                    _connectionState.value = ConnectionState.DISCONNECTED
                }
                scheduleReconnect()
            }
        }

        synchronized(this) {
            webSocket = client.newWebSocket(request, listener)
        }
    }

    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob = scope.launch {
            // Wait for initial sync to complete
            delay(2000)
            while (isConnected() && shouldReconnect) {
                sendRaw(JSONObject().apply { put("CMD", "GETCHANGES") }.toString())
                delay(2000) // 2s polling
            }
        }
    }

    private fun scheduleReconnect() {
        if (!shouldReconnect) return

        synchronized(this) {
            // If already scheduling or active, don't schedule another
            if (reconnectJob?.isActive == true) {
                Log.d(TAG, "Reconnect job already active, skipping scheduleReconnect")
                return
            }

            reconnectJob = scope.launch {
                val delayMs = (RECONNECT_DELAY_MS * (1 shl reconnectAttempts.coerceAtMost(4)))
                    .coerceAtMost(MAX_RECONNECT_DELAY_MS)
                reconnectAttempts++

                Log.i(TAG, "Scheduling reconnect in ${delayMs}ms (attempt $reconnectAttempts)")
                _connectionState.value = ConnectionState.RECONNECTING

                delay(delayMs)

                if (shouldReconnect) {
                    _connectionState.value = ConnectionState.CONNECTING
                    doConnect()
                }
            }
        }
    }

    // ── Message parsing ─────────────────────────────────────────────────────

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

    /**
     * Clean up resources.
     */
    fun destroy() {
        disconnect()
        scope.cancel()
    }
}
