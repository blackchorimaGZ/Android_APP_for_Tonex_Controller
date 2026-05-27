package com.tonex.controller

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.AndroidViewModel
import android.app.Application
import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import com.tonex.controller.data.*
import com.tonex.controller.network.TonexWebSocketClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.atan2
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.platform.LocalConfiguration
import android.content.res.Configuration
import androidx.compose.ui.graphics.lerp


class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TonexTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    TonexControllerApp()
                }
            }
        }
    }
}

// ── Theme Definition ────────────────────────────────────────────────────────
@Composable
fun TonexTheme(content: @Composable () -> Unit) {
    val darkColorScheme = darkColorScheme(
        primary = Color(0xFFFF9800), // Vintage orange glow
        secondary = Color(0xFF00E5FF), // Digital cyan
        background = Color(0xFF121212), // Deep charcoal
        surface = Color(0xFF1E1E1E), // Brushed metal
        onPrimary = Color.Black,
        onSecondary = Color.Black,
        onBackground = Color(0xFFE0E0E0),
        onSurface = Color(0xFFE0E0E0)
    )
    MaterialTheme(
        colorScheme = darkColorScheme,
        content = content
    )
}

// ── ViewModel ───────────────────────────────────────────────────────────────
class TonexViewModel(application: Application) : AndroidViewModel(application) {
    private val client = TonexWebSocketClient()
    private val context = application.applicationContext
    private val prefs = context.getSharedPreferences("tonex_controller_prefs", Context.MODE_PRIVATE)

    private val _connectionState = MutableStateFlow(TonexWebSocketClient.ConnectionState.DISCONNECTED)
    val connectionState = _connectionState.asStateFlow()

    private val _tonexState = MutableStateFlow(TonexState())
    val tonexState: StateFlow<TonexState> = _tonexState.asStateFlow()

    var hostIp by mutableStateOf("192.168.4.1")
        private set

    var appLanguage by mutableStateOf(AppLanguage.EN)
        private set

    val presetSkins = mutableStateListOf<Int>().apply {
        for (i in 0..19) {
            val isCustomized = prefs.getBoolean("preset_skin_customized_$i", false)
            val skin = if (isCustomized) {
                prefs.getInt("preset_skin_$i", i % SkinMapper.skinDrawables.size)
            } else {
                i % SkinMapper.skinDrawables.size
            }
            add(skin)
        }
    }

    val presetDescriptions = mutableStateListOf<String>().apply {
        for (i in 0..19) {
            add(prefs.getString("preset_desc_$i", "Preset Description...") ?: "Preset Description...")
        }
    }

    init {
        // Load saved IP (defaults to 192.168.4.1)
        hostIp = prefs.getString("saved_host_ip", "192.168.4.1") ?: "192.168.4.1"
        val savedLang = prefs.getString("saved_app_lang", "EN") ?: "EN"
        appLanguage = try { AppLanguage.valueOf(savedLang) } catch(e: Exception) { AppLanguage.EN }

        // Collect connection state changes
        viewModelScope.launch {
            client.connectionState.collect { state ->
                _connectionState.value = state
                _tonexState.value = _tonexState.value.copy(connected = state == TonexWebSocketClient.ConnectionState.CONNECTED)
            }
        }

        // Collect CC parameter updates
        viewModelScope.launch {
            client.ccUpdates.collect { ccMsg ->
                updateCcState(ccMsg.cc, ccMsg.value)
            }
        }

        // Collect preset index and skin changes (from ESP32 footswitch or WebSocket GETPRESET)
        viewModelScope.launch {
            client.presetUpdates.collect { (presetIndex, skinIndex) ->
                _tonexState.value = _tonexState.value.copy(
                    preset = presetIndex,
                    skinIndex = skinIndex
                )
                if (presetIndex in 0..19) {
                    val isCustomized = prefs.getBoolean("preset_skin_customized_$presetIndex", false)
                    val resolvedSkin = if (isCustomized) {
                        prefs.getInt("preset_skin_$presetIndex", presetIndex % SkinMapper.skinDrawables.size)
                    } else if (skinIndex != 0) {
                        prefs.edit().putBoolean("preset_skin_customized_$presetIndex", true).apply()
                        skinIndex
                    } else {
                        presetIndex % SkinMapper.skinDrawables.size
                    }
                    presetSkins[presetIndex] = resolvedSkin
                    saveSkinToPrefs(presetIndex, resolvedSkin, isUserAction = false)
                }
            }
        }

        // Collect preset name updates
        viewModelScope.launch {
            client.presetNamesUpdates.collect { names ->
                _tonexState.value = _tonexState.value.copy(presetNames = names)
            }
        }

        // Collect all skins array
        viewModelScope.launch {
            client.presetSkinsArrayUpdates.collect { skins ->
                skins.forEachIndexed { index, skinId ->
                    if (index < presetSkins.size) {
                        val isCustomized = prefs.getBoolean("preset_skin_customized_$index", false)
                        val resolvedSkin = if (isCustomized) {
                            prefs.getInt("preset_skin_$index", index % SkinMapper.skinDrawables.size)
                        } else if (skinId != 0) {
                            prefs.edit().putBoolean("preset_skin_customized_$index", true).apply()
                            skinId
                        } else {
                            index % SkinMapper.skinDrawables.size
                        }
                        presetSkins[index] = resolvedSkin
                        saveSkinToPrefs(index, resolvedSkin, isUserAction = false)
                    }
                }
            }
        }

        // Auto-connect on startup
        viewModelScope.launch {
            connect()
        }
    }

    private fun saveSkinToPrefs(preset: Int, skinIndex: Int, isUserAction: Boolean = false) {
        if (preset in 0..19) {
            val editor = prefs.edit()
            editor.putInt("preset_skin_$preset", skinIndex)
            if (isUserAction) {
                editor.putBoolean("preset_skin_customized_$preset", true)
            }
            editor.apply()
        }
    }

    fun setPresetDescription(preset: Int, desc: String) {
        if (preset in 0..19) {
            presetDescriptions[preset] = desc
            prefs.edit().putString("preset_desc_$preset", desc).apply()
        }
    }

    fun updateHost(ip: String) {
        hostIp = ip
    }

    fun connect() {
        // Sanitize: replace spaces with dots, trim whitespace
        val sanitized = hostIp.trim().replace(" ", ".").replace("..", ".")
        val ipToConnect = sanitized.ifBlank { "192.168.4.1" }
        hostIp = ipToConnect
        // Save to prefs
        prefs.edit().putString("saved_host_ip", hostIp).apply()
        client.connect(hostIp)
    }

    fun setLanguage(lang: AppLanguage) {
        appLanguage = lang
        prefs.edit().putString("saved_app_lang", lang.name).apply()
    }

    fun disconnect() {
        client.disconnect()
    }

    fun setPreset(index: Int) {
        client.sendPresetChange(index)
        _tonexState.value = _tonexState.value.copy(preset = index)
    }

    fun setSkin(skinIndex: Int) {
        client.sendSkinChange(skinIndex)
        _tonexState.value = _tonexState.value.copy(skinIndex = skinIndex)
        val activePreset = _tonexState.value.preset
        if (activePreset in 0..19) {
            presetSkins[activePreset] = skinIndex
            saveSkinToPrefs(activePreset, skinIndex, isUserAction = true)
        }
    }

    fun resetAllSkins() {
        viewModelScope.launch {
            for (i in 0..19) {
                val sequentialSkin = i % SkinMapper.skinDrawables.size
                presetSkins[i] = sequentialSkin
                prefs.edit()
                    .putInt("preset_skin_$i", sequentialSkin)
                    .putBoolean("preset_skin_customized_$i", false)
                    .apply()
                if (client.connectionState.value == TonexWebSocketClient.ConnectionState.CONNECTED) {
                    client.sendPresetChange(i)
                    kotlinx.coroutines.delay(120)
                    client.sendSkinChange(sequentialSkin)
                    kotlinx.coroutines.delay(120)
                }
            }
            if (client.connectionState.value == TonexWebSocketClient.ConnectionState.CONNECTED) {
                client.sendPresetChange(0)
            }
        }
    }

    fun setCc(cc: Int, value: Int) {
        client.sendCc(cc, value)
        updateCcState(cc, value)
    }

    fun toggleBypass() {
        val next = !_tonexState.value.bypassed
        setCc(MidiCcMap.SELECT_PRESET, if (next) 127 else _tonexState.value.preset)
        _tonexState.value = _tonexState.value.copy(bypassed = next)
    }

    fun toggleBlock(block: BlockType) {
        val state = _tonexState.value
        when(block) {
            BlockType.GATE -> setCc(MidiCcMap.GATE_POWER, if (state.gate.enabled) 0 else 127)
            BlockType.COMPRESSOR -> setCc(MidiCcMap.COMP_POWER, if (state.compressor.enabled) 0 else 127)
            BlockType.CAB -> setCc(MidiCcMap.CAB_BYPASS, if (!state.globals.cabBypass) 127 else 0)
            BlockType.MODULATION -> setCc(MidiCcMap.MOD_POWER, if (state.modulation.enabled) 0 else 127)
            BlockType.DELAY -> setCc(MidiCcMap.DELAY_POWER, if (state.delay.enabled) 0 else 127)
            BlockType.REVERB -> setCc(MidiCcMap.REVERB_POWER, if (state.reverb.enabled) 0 else 127)
            BlockType.AMP, BlockType.EQ, BlockType.GLOBALS -> {} // No bypass toggle
        }
    }

    private fun updateCcState(cc: Int, value: Int) {
        val current = _tonexState.value
        _tonexState.value = when (cc) {
            // Amp
            MidiCcMap.AMP_GAIN -> current.copy(amp = current.amp.copy(gain = value))
            MidiCcMap.AMP_VOLUME -> current.copy(amp = current.amp.copy(volume = value))
            MidiCcMap.AMP_MIX -> current.copy(amp = current.amp.copy(mix = value))
            MidiCcMap.PRESENCE -> current.copy(amp = current.amp.copy(presence = value))
            MidiCcMap.DEPTH -> current.copy(amp = current.amp.copy(depth = value))

            // Gate
            MidiCcMap.GATE_POWER -> current.copy(gate = current.gate.copy(enabled = value >= 64))
            MidiCcMap.GATE_POSITION -> current.copy(gate = current.gate.copy(position = value))
            MidiCcMap.GATE_THRESHOLD -> current.copy(gate = current.gate.copy(threshold = value))
            MidiCcMap.GATE_RELEASE -> current.copy(gate = current.gate.copy(release = value))
            MidiCcMap.GATE_DEPTH -> current.copy(gate = current.gate.copy(depth = value))

            // Comp
            MidiCcMap.COMP_POWER -> current.copy(compressor = current.compressor.copy(enabled = value >= 64))
            MidiCcMap.COMP_POSITION -> current.copy(compressor = current.compressor.copy(position = value))
            MidiCcMap.COMP_THRESHOLD -> current.copy(compressor = current.compressor.copy(threshold = value))
            MidiCcMap.COMP_GAIN -> current.copy(compressor = current.compressor.copy(gain = value))
            MidiCcMap.COMP_ATTACK -> current.copy(compressor = current.compressor.copy(attack = value))

            // EQ
            MidiCcMap.BASS_EQ -> current.copy(eq = current.eq.copy(bass = value))
            MidiCcMap.BASS_HZ -> current.copy(eq = current.eq.copy(bassHz = value))
            MidiCcMap.MID_EQ -> current.copy(eq = current.eq.copy(mid = value))
            MidiCcMap.MID_Q -> current.copy(eq = current.eq.copy(midQ = value))
            MidiCcMap.MID_HZ -> current.copy(eq = current.eq.copy(midHz = value))
            MidiCcMap.TREBLE_EQ -> current.copy(eq = current.eq.copy(treble = value))
            MidiCcMap.TREBLE_HZ -> current.copy(eq = current.eq.copy(trebleHz = value))
            MidiCcMap.EQ_POSITION -> current.copy(eq = current.eq.copy(position = value))

            // Modulation
            MidiCcMap.MOD_POWER -> current.copy(modulation = current.modulation.copy(enabled = value >= 64))
            MidiCcMap.MOD_POSITION -> current.copy(modulation = current.modulation.copy(position = value))
            MidiCcMap.MOD_TYPE -> current.copy(modulation = current.modulation.copy(type = ModType.entries.getOrElse(value) { ModType.CHORUS }))
            35, 39, 44, 48, 53 -> current.copy(modulation = current.modulation.copy(param0 = value))
            36, 40, 45, 49, 54 -> current.copy(modulation = current.modulation.copy(param1 = value))
            37, 41, 46, 50, 55, 112 -> current.copy(modulation = current.modulation.copy(param2 = value))
            42, 51, 56, 113 -> current.copy(modulation = current.modulation.copy(param3 = value))

            // Delay
            MidiCcMap.DELAY_POWER -> current.copy(delay = current.delay.copy(enabled = value >= 64))
            MidiCcMap.DELAY_POSITION -> current.copy(delay = current.delay.copy(position = value))
            MidiCcMap.DELAY_TYPE -> current.copy(delay = current.delay.copy(type = if (value == 0) DelayType.DIGITAL else DelayType.TAPE))
            MidiCcMap.DIGITAL_DELAY_TIME, MidiCcMap.TAPE_DELAY_TIME -> current.copy(delay = current.delay.copy(time = value))
            MidiCcMap.DIGITAL_DELAY_FEEDBACK, MidiCcMap.TAPE_DELAY_FEEDBACK -> current.copy(delay = current.delay.copy(feedback = value))
            MidiCcMap.DIGITAL_DELAY_MIX, MidiCcMap.TAPE_DELAY_MIX -> current.copy(delay = current.delay.copy(mix = value))
            MidiCcMap.DIGITAL_DELAY_SYNC, MidiCcMap.TAPE_DELAY_SYNC -> current.copy(delay = current.delay.copy(sync = value >= 64))

            // Reverb
            MidiCcMap.REVERB_POWER -> current.copy(reverb = current.reverb.copy(enabled = value >= 64))
            MidiCcMap.REVERB_POSITION -> current.copy(reverb = current.reverb.copy(position = value))
            MidiCcMap.REVERB_TYPE -> current.copy(reverb = current.reverb.copy(type = ReverbType.entries.getOrElse(value) { ReverbType.ROOM }))
            71, 59, 63, 67, 80, 76 -> current.copy(reverb = current.reverb.copy(time = value))
            72, 60, 64, 68, 81, 77 -> current.copy(reverb = current.reverb.copy(predelay = value))
            73, 61, 65, 69, 82, 78 -> current.copy(reverb = current.reverb.copy(color = value))
            74, 62, 66, 70, 83, 79 -> current.copy(reverb = current.reverb.copy(mix = value))

            // Globals
            MidiCcMap.GLOBAL_VOLUME -> current.copy(globals = current.globals.copy(volume = value))
            MidiCcMap.INPUT_TRIM -> current.copy(globals = current.globals.copy(inputTrim = value))
            MidiCcMap.BPM -> current.copy(globals = current.globals.copy(bpm = value))
            MidiCcMap.CAB_BYPASS -> current.copy(globals = current.globals.copy(cabBypass = value >= 64))
            MidiCcMap.TUNING_REF -> current.copy(globals = current.globals.copy(tuningRef = value))

            else -> current
        }
    }

    override fun onCleared() {
        super.onCleared()
        client.destroy()
    }
}

// ── Screen Selection ────────────────────────────────────────────────────────
enum class BlockType(val label: String, val color: Color) {
    AMP("AMP MODEL", Color(0xFFFF9800)),
    GATE("NOISE GATE", Color(0xFF2196F3)),
    COMPRESSOR("COMPRESSOR", Color(0xFFFFEB3B)),
    CAB("CABINET SIM", Color(0xFF8BC34A)),
    EQ("EQUALIZER", Color(0xFF4CAF50)),
    MODULATION("MODULATION", Color(0xFF9C27B0)),
    DELAY("DELAY", Color(0xFF00E5FF)),
    REVERB("REVERB", Color(0xFFE91E63)),
    GLOBALS("GLOBAL SETTINGS", Color(0xFF9E9E9E))
}

// ── Custom Analog Circular Knob Composable ──────────────────────────────────
@Composable
fun TonexKnob(
    label: String,
    value: Int, // 0-127
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    valueDisplayFormatter: (Int) -> String = { it.toString() }
) {
    var dragStartValue by remember { mutableStateOf(0) }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.padding(8.dp)
    ) {
        Text(
            text = label.uppercase(),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Gray,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
        Spacer(modifier = Modifier.height(4.dp))

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(72.dp)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { dragStartValue = value },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            // Use vertical drag movement to simulate knob rotation
                            val delta = -(dragAmount.y / 2f).toInt()
                            val newValue = (dragStartValue + delta).coerceIn(0, 127)
                            if (newValue != value) {
                                onValueChange(newValue)
                                dragStartValue = newValue // keep updating anchor point during continuous drag
                            }
                        }
                    )
                }
        ) {
            val animatedSweep by animateFloatAsState(
                targetValue = (value.toFloat() / 127f) * 280f,
                label = "sweepAngle"
            )

            Canvas(modifier = Modifier.fillMaxSize().padding(6.dp)) {
                // Draw background metal rim
                drawCircle(
                    color = Color(0xFF2A2A2A),
                    radius = size.minDimension / 2f
                )

                // Draw standard notch track background
                drawArc(
                    color = Color(0xFF3E3E3E),
                    startAngle = 130f,
                    sweepAngle = 280f,
                    useCenter = false,
                    style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round)
                )

                // Draw active progress glow arc
                drawArc(
                    color = color,
                    startAngle = 130f,
                    sweepAngle = animatedSweep,
                    useCenter = false,
                    style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round)
                )

                // Calculate selector line position
                val angleRad = ((130f + animatedSweep) * PI / 180f).toFloat()
                val radius = (size.minDimension / 2f) - 10.dp.toPx()
                val centerOffset = Offset(size.width / 2f, size.height / 2f)
                val lineEnd = Offset(
                    x = centerOffset.x + radius * kotlin.math.cos(angleRad),
                    y = centerOffset.y + radius * kotlin.math.sin(angleRad)
                )

                // Draw pointing indicator notch
                drawLine(
                    color = Color.White,
                    start = centerOffset,
                    end = lineEnd,
                    strokeWidth = 3.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }

            // Central Value text overlay
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = valueDisplayFormatter(value),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = Color.White
                )
            }
        }
    }
}

// ── Main UI Layout ──────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun TonexControllerApp(viewModel: TonexViewModel = viewModel()) {
    val state by viewModel.tonexState.collectAsState()
    val connState by viewModel.connectionState.collectAsState()

    var currentScreen by remember { mutableStateOf(Screen.Splash) }

    when (currentScreen) {
        Screen.Splash -> {
            SplashScreen(onDismiss = { currentScreen = Screen.Main })
        }
        Screen.Main -> {
            var selectedSettingsBlock by remember { mutableStateOf<BlockType>(BlockType.AMP) }
    var isInterfaceLocked by remember { mutableStateOf(false) }
    var lastTapTimes by remember { mutableStateOf(listOf<Long>()) }
    val onTapTempo = {
        val now = System.currentTimeMillis()
        val updatedTaps = lastTapTimes.filter { now - it < 2000 } + now
        if (updatedTaps.size >= 2) {
            val intervals = updatedTaps.zipWithNext { a, b -> b - a }
            val avgInterval = intervals.average()
            val tappedBpm = (60000 / avgInterval).toInt()
            val clampedBpm = tappedBpm.coerceIn(40, 240)
            val ccVal = ((clampedBpm - 40) * 127 / (240 - 40)).coerceIn(0, 127)
            viewModel.setCc(MidiCcMap.BPM, ccVal)
        }
        lastTapTimes = updatedTaps
    }
    var showPresetDialog by remember { mutableStateOf(false) }
    var presetSearchQuery by remember { mutableStateOf("") }
    var isEditorOpen by remember { mutableStateOf(false) }
    var showInfoDialog by remember { mutableStateOf(false) }

    val isConnected = connState == TonexWebSocketClient.ConnectionState.CONNECTED

    // Pager for preset carousel matching the Waveshare 4.3B screen
    val pagerState = rememberPagerState(
        initialPage = state.preset,
        pageCount = { 20 }
    )

    var isUpdatingFromNetwork by remember { mutableStateOf(false) }

    // Sync Pager from ESP32 State
    LaunchedEffect(state.preset) {
        if (state.preset in 0..19 && pagerState.currentPage != state.preset) {
            isUpdatingFromNetwork = true
            try {
                pagerState.scrollToPage(state.preset)
            } finally {
                kotlinx.coroutines.delay(350)
                isUpdatingFromNetwork = false
            }
        }
    }
// Sync Pager swipes to ESP32 Preset Changes (with debounce)
    LaunchedEffect(pagerState.currentPage) {
        if (!isUpdatingFromNetwork && state.preset != pagerState.currentPage && isConnected) {
            kotlinx.coroutines.delay(400) // Debounce rapid swipes
            viewModel.setPreset(pagerState.currentPage)
        }
    }
    Box(modifier = Modifier.fillMaxSize()) {
        val keyboardController = LocalSoftwareKeyboardController.current
        if (!isConnected) {
            val scrollState = rememberScrollState()
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF0F0F0F))
                    .verticalScroll(scrollState)
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth(0.9f)
                        .wrapContentHeight(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, Color(0xFF333333))
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = "Logo",
                            tint = Color(0xFFD1A60C),
                            modifier = Modifier.size(56.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "TONEX MOBILE",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFD1A60C)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = LanguageManager.get(StringKey.CONNECT_HELP, viewModel.appLanguage),
                            fontSize = 12.sp,
                            color = Color.Gray
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                        
                        OutlinedTextField(
                            value = viewModel.hostIp,
                            onValueChange = { viewModel.updateHost(it) },
                            label = { Text(LanguageManager.get(StringKey.IP_ADDRESS, viewModel.appLanguage), fontSize = 10.sp) },
                            placeholder = { Text("192.168.4.1", color = Color.Gray) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Uri,
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(
                                onDone = {
                                    keyboardController?.hide()
                                    viewModel.connect()
                                }
                            ),
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = Color.White),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFFD1A60C),
                                unfocusedBorderColor = Color.Gray,
                                focusedLabelColor = Color(0xFFD1A60C),
                                unfocusedLabelColor = Color.Gray
                            )
                        )
                        
                        Spacer(modifier = Modifier.height(16.dp))

                        // Connection State indicator
                        val stateColor = when (connState) {
                            TonexWebSocketClient.ConnectionState.CONNECTED -> Color(0xFF2E7D32)
                            TonexWebSocketClient.ConnectionState.CONNECTING -> Color(0xFFD1A60C)
                            TonexWebSocketClient.ConnectionState.RECONNECTING -> Color(0xFFFF9800)
                            TonexWebSocketClient.ConnectionState.DISCONNECTED -> Color(0xFFC33030)
                        }
                        val stateText = when (connState) {
                            TonexWebSocketClient.ConnectionState.CONNECTED -> LanguageManager.get(StringKey.CONNECTED, viewModel.appLanguage)
                            TonexWebSocketClient.ConnectionState.CONNECTING -> LanguageManager.get(StringKey.CONNECTING, viewModel.appLanguage)
                            TonexWebSocketClient.ConnectionState.RECONNECTING -> LanguageManager.get(StringKey.RECONNECTING, viewModel.appLanguage)
                            TonexWebSocketClient.ConnectionState.DISCONNECTED -> LanguageManager.get(StringKey.DISCONNECTED, viewModel.appLanguage)
                        }
                        
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(stateColor.copy(alpha = 0.15f))
                                .border(1.dp, stateColor, RoundedCornerShape(8.dp))
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(androidx.compose.foundation.shape.CircleShape)
                                    .background(stateColor)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stateText,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = stateColor
                            )
                        }

                        Spacer(modifier = Modifier.height(20.dp))
                        
                        Button(
                            onClick = {
                                keyboardController?.hide()
                                viewModel.connect()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFD1A60C),
                                contentColor = Color.Black
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(LanguageManager.get(StringKey.CONNECT, viewModel.appLanguage), fontWeight = FontWeight.Bold)
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = if (viewModel.appLanguage == AppLanguage.EN) "Language / Idioma" else "Idioma / Language",
                                fontSize = 11.sp,
                                color = Color.LightGray,
                                fontWeight = FontWeight.Bold
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                LanguageFlagButton(
                                    lang = AppLanguage.EN,
                                    isSelected = viewModel.appLanguage == AppLanguage.EN,
                                    onClick = { viewModel.setLanguage(AppLanguage.EN) }
                                )
                                LanguageFlagButton(
                                    lang = AppLanguage.ES,
                                    isSelected = viewModel.appLanguage == AppLanguage.ES,
                                    onClick = { viewModel.setLanguage(AppLanguage.ES) }
                                )
                            }
                        }
                    }
                }
            }
        } else if (!isEditorOpen) {
            // --- MAIN VIEW: Logo/Config + Waveshare Bezel + Bottom Chain (full screen) ---
                val scaledBpm = (40 + (state.globals.bpm * (240 - 40) / 127))
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = 0.dp, bottom = 4.dp, start = 8.dp, end = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // 1. TOP LOGO & COLLAPSIBLE CONFIG ROW
                    var configExpanded by remember { mutableStateOf(false) }
                    
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF161616), RoundedCornerShape(8.dp))
                            .padding(horizontal = 12.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = "TONEX MOBILE",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.LightGray,
                                letterSpacing = 1.sp
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            val badgeColor = when (connState) {
                                TonexWebSocketClient.ConnectionState.CONNECTED -> Color(0xFF2E7D32)
                                TonexWebSocketClient.ConnectionState.CONNECTING -> Color(0xFFD1A60C)
                                TonexWebSocketClient.ConnectionState.RECONNECTING -> Color(0xFFFF9800)
                                TonexWebSocketClient.ConnectionState.DISCONNECTED -> Color(0xFFC33030)
                            }
                            val badgeText = when (connState) {
                                TonexWebSocketClient.ConnectionState.CONNECTED -> LanguageManager.get(StringKey.CONNECTED, viewModel.appLanguage)
                                TonexWebSocketClient.ConnectionState.CONNECTING -> LanguageManager.get(StringKey.CONNECTING, viewModel.appLanguage)
                                TonexWebSocketClient.ConnectionState.RECONNECTING -> LanguageManager.get(StringKey.RECONNECTING, viewModel.appLanguage)
                                TonexWebSocketClient.ConnectionState.DISCONNECTED -> LanguageManager.get(StringKey.DISCONNECTED, viewModel.appLanguage)
                            }
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(badgeColor)
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = badgeText,
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                            Spacer(modifier = Modifier.weight(1f))
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 4.dp)
                            ) {
                                LanguageFlagButton(
                                    lang = AppLanguage.EN,
                                    isSelected = viewModel.appLanguage == AppLanguage.EN,
                                    onClick = { viewModel.setLanguage(AppLanguage.EN) }
                                )
                                LanguageFlagButton(
                                    lang = AppLanguage.ES,
                                    isSelected = viewModel.appLanguage == AppLanguage.ES,
                                    onClick = { viewModel.setLanguage(AppLanguage.ES) }
                                )
                            }
                            Spacer(modifier = Modifier.weight(1f))
                            Text(
                                text = "Smythbuilt",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Black,
                                color = Color(0xFF00E5FF), // Digital Cyan
                                letterSpacing = 0.5.sp
                            )
                            Text(
                                text = " / ",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = "Blackchorima",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Black,
                                color = Color(0xFFFF2E93), // Neon Pink/Magenta
                                letterSpacing = 0.5.sp
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = { isInterfaceLocked = !isInterfaceLocked },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = if (isInterfaceLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                                    contentDescription = "Lock Controls",
                                    tint = if (isInterfaceLocked) Color(0xFFD1A60C) else Color.Gray,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                            IconButton(
                                onClick = { showInfoDialog = true },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Info,
                                    contentDescription = "Skin Information",
                                    tint = Color.Gray,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                            IconButton(
                                onClick = { configExpanded = !configExpanded },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = if (configExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    contentDescription = "Config Toggle",
                                    tint = Color.Gray
                                )
                            }
                        }
                    }

                    AnimatedVisibility(
                        visible = configExpanded,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Column(modifier = Modifier.padding(10.dp).fillMaxWidth()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    OutlinedTextField(
                                        value = viewModel.hostIp,
                                        onValueChange = { viewModel.updateHost(it) },
                                        label = { Text(LanguageManager.get(StringKey.IP_ADDRESS, viewModel.appLanguage), fontSize = 9.sp) },
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(
                                            keyboardType = KeyboardType.Uri,
                                            imeAction = ImeAction.Done
                                        ),
                                        keyboardActions = KeyboardActions(
                                            onDone = {
                                                keyboardController?.hide()
                                                viewModel.connect()
                                            }
                                        ),
                                        modifier = Modifier.weight(1.5f),
                                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, color = Color.White),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = Color(0xFFD1A60C),
                                            unfocusedBorderColor = Color.Gray,
                                            focusedLabelColor = Color(0xFFD1A60C),
                                            unfocusedLabelColor = Color.Gray
                                        )
                                    )
                                    
                                    Button(
                                        onClick = {
                                            viewModel.connect()
                                            keyboardController?.hide()
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD1A60C)),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.weight(1.0f).height(40.dp)
                                    ) {
                                        Text(text = LanguageManager.get(StringKey.CONNECT, viewModel.appLanguage), fontSize = 11.sp, color = Color.Black, fontWeight = FontWeight.Bold)
                                    }

                                    Button(
                                        onClick = {
                                            viewModel.disconnect()
                                            keyboardController?.hide()
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.weight(1.0f).height(40.dp)
                                    ) {
                                        Text(text = LanguageManager.get(StringKey.DISCONNECT, viewModel.appLanguage), fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold)
                                    }
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Button(
                                    onClick = { viewModel.resetAllSkins() },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5A1A2A)),
                                    border = BorderStroke(1.dp, Color(0xFFFF2E93)),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.fillMaxWidth().height(36.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = "Reset Skins",
                                        tint = Color.White,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = LanguageManager.get(StringKey.RESET_SKINS, viewModel.appLanguage),
                                        fontSize = 11.sp,
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }

                    // 2. WAVESHARE BEZEL
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F0F0F)),
                        border = BorderStroke(2.dp, Color(0xFF333333)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color(0xFF16161A))
                        ) {
                            // Central screen animations & colors declared first for top bar usage
                            val beatDurationMs = (60000 / scaledBpm).toLong()
                            val infiniteTransition = rememberInfiniteTransition(label = "beat")
                            val beatProgress by infiniteTransition.animateFloat(
                                initialValue = 0f,
                                targetValue = 1f,
                                animationSpec = infiniteRepeatable(
                                    animation = tween(
                                        durationMillis = (beatDurationMs / 2).toInt().coerceIn(100, 1000),
                                        easing = LinearEasing
                                    ),
                                    repeatMode = RepeatMode.Reverse
                                ),
                                label = "beatProgress"
                            )
                            val beatPulseColor = lerp(Color(0xFF1A1A1E), Color(0xFF5A1A2A), beatProgress)
                            val beatBorderColor = lerp(Color(0xFF2D2D35), Color(0xFFFF2E93), beatProgress)

                            val connectionIndicatorColor = when (connState) {
                                TonexWebSocketClient.ConnectionState.CONNECTED -> Color(0xFF00E676)
                                TonexWebSocketClient.ConnectionState.CONNECTING -> Color(0xFFD1A60C)
                                TonexWebSocketClient.ConnectionState.RECONNECTING -> Color(0xFFFF9800)
                                TonexWebSocketClient.ConnectionState.DISCONNECTED -> Color(0xFFC33030)
                            }

                            // Top panel bar (Redesigned with premium modern tech look)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(44.dp)
                                    .background(Color(0xFF1E1E24))
                                    .border(BorderStroke(1.dp, Color(0xFF2D2D35)))
                                    .padding(horizontal = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Left Section: Brand & Connected dot
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(androidx.compose.foundation.shape.CircleShape)
                                            .background(connectionIndicatorColor) // Dynamic connection indicator
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "TONEX",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Black,
                                        color = Color.White,
                                        letterSpacing = 1.sp
                                    )
                                }

                                // Center Section: Preset Name display
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0F0F12)),
                                    border = BorderStroke(1.dp, Color(0xFF2D2D35)),
                                    shape = RoundedCornerShape(4.dp),
                                    modifier = Modifier
                                        .weight(1.5f)
                                        .clickable { showPresetDialog = true }
                                ) {
                                    Text(
                                        text = state.presetNames.getOrElse(pagerState.currentPage) { "Preset ${pagerState.currentPage + 1}" }.uppercase(),
                                        color = Color(0xFFD1A60C),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace,
                                        textAlign = TextAlign.Center,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp, horizontal = 8.dp)
                                    )
                                }

                                // Right Section: Bank Badge & TAP BPM Button
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.End,
                                    modifier = Modifier.weight(1.5f)
                                ) {
                                    // Bank Chip
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(Color(0xFF141417))
                                            .border(1.dp, Color(0xFF2D2D35), RoundedCornerShape(4.dp))
                                            .padding(horizontal = 6.dp, vertical = 3.dp)
                                    ) {
                                        Text(
                                            text = "BNK ${(pagerState.currentPage / 3) + 1}",
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = FontFamily.Monospace,
                                            color = Color.LightGray
                                        )
                                    }
                                    
                                    Spacer(modifier = Modifier.width(6.dp))
                                    
                                    // TAP BPM Chip
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(beatPulseColor)
                                            .border(1.dp, beatBorderColor, RoundedCornerShape(4.dp))
                                            .clickable { onTapTempo() }
                                            .padding(horizontal = 6.dp, vertical = 3.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(6.dp)
                                                .clip(androidx.compose.foundation.shape.CircleShape)
                                                .background(if (beatProgress > 0.5f) Color(0xFFFF2E93) else Color(0xFF5A1A2A))
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "$scaledBpm BPM",
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Black,
                                            fontFamily = FontFamily.Monospace,
                                            color = if (beatProgress > 0.5f) Color(0xFFFF2E93) else Color.White
                                        )
                                    }
                                }
                            }

                            // Central row with carousel taking full horizontal space
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .background(Color(0xFF131317))
                                    .padding(8.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(
                                    onClick = {
                                        if (pagerState.currentPage > 0) {
                                            viewModel.setPreset(pagerState.currentPage - 1)
                                        }
                                    },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ChevronLeft,
                                        contentDescription = "Prev",
                                        tint = Color(0xFFD1A60C),
                                        modifier = Modifier.size(28.dp)
                                    )
                                }

                                HorizontalPager(
                                    state = pagerState,
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Color(0xFF08080A))
                                        .border(1.5.dp, Color(0xFF2D2D35), RoundedCornerShape(10.dp))
                                ) { page ->
                                    val skinIdx = viewModel.presetSkins.getOrElse(page) { 2 }
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .combinedClickable(
                                                onClick = {
                                                    showPresetDialog = true
                                                },
                                                onLongClick = {
                                                    selectedSettingsBlock = BlockType.AMP
                                                    isEditorOpen = true
                                                }
                                            )
                                    ) {
                                        Image(
                                            painter = painterResource(id = SkinMapper.getDrawableForSkin(skinIdx)),
                                            contentDescription = "Skin",
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .background(
                                                    Brush.verticalGradient(
                                                        colors = listOf(
                                                            Color.Transparent,
                                                            Color.Black.copy(alpha = 0.5f)
                                                        )
                                                    )
                                                )
                                        )
                                    }
                                }

                                IconButton(
                                    onClick = {
                                        if (pagerState.currentPage < 19) {
                                            viewModel.setPreset(pagerState.currentPage + 1)
                                        }
                                    },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ChevronRight,
                                        contentDescription = "Next",
                                        tint = Color(0xFFD1A60C),
                                        modifier = Modifier.size(28.dp)
                                    )
                                }
                            }
                        }
                    }



                    // 4. BOTTOM EFFECT CHAIN
                    val blocks = listOf(
                        BlockType.GATE to state.gate.enabled,
                        BlockType.COMPRESSOR to state.compressor.enabled,
                        BlockType.AMP to true,
                        BlockType.CAB to !state.globals.cabBypass,
                        BlockType.EQ to true,
                        BlockType.MODULATION to state.modulation.enabled,
                        BlockType.DELAY to state.delay.enabled,
                        BlockType.REVERB to state.reverb.enabled,
                        BlockType.GLOBALS to true
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF161616), RoundedCornerShape(12.dp))
                            .border(1.dp, Color(0xFF2D2D2D), RoundedCornerShape(12.dp))
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        blocks.forEach { (block, isEnabled) ->
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(32.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        if (isEnabled) block.color.copy(alpha = 0.12f) else Color(0xFF202020)
                                    )
                                    .border(
                                        width = 1.dp,
                                        color = if (isEnabled) block.color.copy(alpha = 0.6f) else Color(0xFF2D2D2D),
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                    .combinedClickable(
                                        onClick = {
                                            viewModel.toggleBlock(block)
                                        },
                                        onLongClick = {
                                            selectedSettingsBlock = block
                                            isEditorOpen = true
                                        }
                                    )
                                    .padding(0.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxSize(),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.SpaceEvenly
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .clip(RoundedCornerShape(3.dp))
                                            .background(if (isEnabled) block.color else Color.DarkGray)
                                    )
                                    Text(
                                        text = when(block) {
                                            BlockType.GATE -> "GATE"
                                            BlockType.COMPRESSOR -> "COMP"
                                            BlockType.AMP -> "AMP"
                                            BlockType.CAB -> "CAB"
                                            BlockType.EQ -> "EQ"
                                            BlockType.MODULATION -> "MOD"
                                            BlockType.DELAY -> "DLY"
                                            BlockType.REVERB -> "RVB"
                                            BlockType.GLOBALS -> "GLB"
                                        },
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isEnabled) Color.White else Color.Gray,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                }
        } else {
            // --- FULL SCREEN EDITOR VIEW ---
            val activeTab = selectedSettingsBlock
            val isPowerable = activeTab in listOf(BlockType.GATE, BlockType.COMPRESSOR, BlockType.CAB, BlockType.MODULATION, BlockType.DELAY, BlockType.REVERB)
            val isBlockEnabled = when (activeTab) {
                BlockType.GATE -> state.gate.enabled
                BlockType.COMPRESSOR -> state.compressor.enabled
                BlockType.CAB -> !state.globals.cabBypass
                BlockType.MODULATION -> state.modulation.enabled
                BlockType.DELAY -> state.delay.enabled
                BlockType.REVERB -> state.reverb.enabled
                else -> true
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF141414))
                    .border(2.dp, activeTab.color.copy(alpha = 0.5f))
                    .padding(16.dp)
            ) {
                // Header: Block Title, Bypass switch, and Close X button
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (activeTab == BlockType.AMP) "${activeTab.label} - ${getSkinName(state.skinIndex).uppercase()}" else activeTab.label,
                            color = activeTab.color,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Black
                        )
                        if (isPowerable) {
                            Spacer(modifier = Modifier.width(12.dp))
                            Switch(
                                checked = isBlockEnabled,
                                onCheckedChange = { checked ->
                                    when (activeTab) {
                                        BlockType.GATE -> viewModel.setCc(MidiCcMap.GATE_POWER, if (checked) 127 else 0)
                                        BlockType.COMPRESSOR -> viewModel.setCc(MidiCcMap.COMP_POWER, if (checked) 127 else 0)
                                        BlockType.CAB -> viewModel.setCc(MidiCcMap.CAB_BYPASS, if (checked) 0 else 127)
                                        BlockType.MODULATION -> viewModel.setCc(MidiCcMap.MOD_POWER, if (checked) 127 else 0)
                                        BlockType.DELAY -> viewModel.setCc(MidiCcMap.DELAY_POWER, if (checked) 127 else 0)
                                        BlockType.REVERB -> viewModel.setCc(MidiCcMap.REVERB_POWER, if (checked) 127 else 0)
                                        else -> {}
                                    }
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = activeTab.color,
                                    checkedTrackColor = activeTab.color.copy(alpha = 0.4f)
                                ),
                                modifier = Modifier.scale(0.8f)
                            )
                        }
                    }

                    IconButton(
                        onClick = { isEditorOpen = false },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = Color.LightGray,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                HorizontalDivider(color = Color(0xFF262626), thickness = 1.dp, modifier = Modifier.padding(bottom = 12.dp))

                // Sliders list
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    when (activeTab) {
                        BlockType.GATE -> GateSliders(state, viewModel)
                        BlockType.COMPRESSOR -> CompressorSliders(state, viewModel)
                        BlockType.AMP -> AmpSliders(state, viewModel)
                        BlockType.CAB -> CabinetSliders(state, viewModel)
                        BlockType.EQ -> EqSliders(state, viewModel)
                        BlockType.MODULATION -> ModulationSliders(state, viewModel)
                        BlockType.DELAY -> DelaySliders(state, viewModel)
                        BlockType.REVERB -> ReverbSliders(state, viewModel)
                        BlockType.GLOBALS -> GlobalSliders(state, viewModel)
                    }
                }
            }
        }

        // --- STAGE PERFORMANCE LOCK OVERLAY ---
        if (isInterfaceLocked) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.88f))
                    .clickable(enabled = true, onClick = {}),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = "Locked",
                        tint = Color(0xFFD1A60C),
                        modifier = Modifier.size(72.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = LanguageManager.get(StringKey.STAGE_MODE_LOCKED, viewModel.appLanguage),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = LanguageManager.get(StringKey.LOCK_DESC, viewModel.appLanguage),
                        fontSize = 12.sp,
                        color = Color.Gray
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(
                        onClick = { isInterfaceLocked = false },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E1E1E)),
                        border = BorderStroke(1.dp, Color(0xFFD1A60C)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.LockOpen, "Unlock", tint = Color(0xFFD1A60C))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(LanguageManager.get(StringKey.UNLOCK_CONTROLS, viewModel.appLanguage), color = Color.White)
                    }
                }
            }
        }
    }
    if (showPresetDialog) {
            AlertDialog(
                onDismissRequest = { showPresetDialog = false },
                title = { Text(LanguageManager.get(StringKey.SELECT_PRESET, viewModel.appLanguage), fontWeight = FontWeight.Bold) },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = presetSearchQuery,
                            onValueChange = { presetSearchQuery = it },
                            label = { Text(LanguageManager.get(StringKey.SEARCH_PRESET, viewModel.appLanguage), fontSize = 10.sp) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                            textStyle = LocalTextStyle.current.copy(fontSize = 12.sp)
                        )
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            state.presetNames.forEachIndexed { index, name ->
                                if (presetSearchQuery.isEmpty() || name.contains(presetSearchQuery, ignoreCase = true)) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                viewModel.setPreset(index)
                                                showPresetDialog = false
                                                presetSearchQuery = ""
                                            }
                                            .padding(vertical = 12.dp, horizontal = 8.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = String.format("%02d.  %s", index + 1, name.uppercase()),
                                            color = if (index == state.preset) MaterialTheme.colorScheme.primary else Color.White,
                                            fontWeight = if (index == state.preset) FontWeight.Bold else FontWeight.Normal
                                        )
                                        if (index == state.preset) {
                                            Icon(Icons.Default.Check, "Selected", tint = MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                    HorizontalDivider(color = Color(0xFF2A2A2A))
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showPresetDialog = false }) {
                        Text(LanguageManager.get(StringKey.CLOSE, viewModel.appLanguage))
                    }
                },
                containerColor = Color(0xFF1E1E1E),
                textContentColor = Color.White,
                titleContentColor = Color.White
            )
        }

        if (showInfoDialog) {
            AlertDialog(
                onDismissRequest = { showInfoDialog = false },
                title = { Text(LanguageManager.get(StringKey.SKINS_INFO_TITLE, viewModel.appLanguage), fontWeight = FontWeight.Bold) },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = LanguageManager.get(StringKey.WHY_SKINS_MISMATCH_TITLE, viewModel.appLanguage),
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFD1A60C),
                            fontSize = 13.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = LanguageManager.get(StringKey.WHY_SKINS_MISMATCH_DESC, viewModel.appLanguage),
                            fontSize = 11.sp,
                            color = Color.LightGray
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = LanguageManager.get(StringKey.HOW_TO_CUSTOMIZE_TITLE, viewModel.appLanguage),
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF00E5FF),
                            fontSize = 13.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = LanguageManager.get(StringKey.HOW_TO_CUSTOMIZE_DESC, viewModel.appLanguage),
                            fontSize = 11.sp,
                            color = Color.LightGray
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showInfoDialog = false }) {
                        Text(LanguageManager.get(StringKey.OK, viewModel.appLanguage))
                    }
                },
                containerColor = Color(0xFF1E1E1E),
                textContentColor = Color.White,
                titleContentColor = Color.White
            )
        }
    }
}
}

// ── CUSTOM SLIDER COMPOSABLES ───────────────────────────────────────────────

@Composable
fun TonexSlider(
    label: String,
    value: Int,
    range: ClosedRange<Int> = 0..127,
    onValueChange: (Int) -> Unit,
    color: Color,
    displayFormatter: (Int) -> String = { it.toString() }
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label.uppercase(),
                color = Color.LightGray,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = displayFormatter(value),
                color = color,
                fontSize = 13.sp,
                fontWeight = FontWeight.Black
            )
        }
        Spacer(modifier = Modifier.height(2.dp))
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.toInt()) },
            valueRange = range.start.toFloat()..range.endInclusive.toFloat(),
            colors = SliderDefaults.colors(
                thumbColor = color,
                activeTrackColor = color,
                inactiveTrackColor = Color(0xFF222222)
            ),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
fun TonexSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    color: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label.uppercase(),
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = color,
                checkedTrackColor = color.copy(alpha = 0.3f),
                uncheckedThumbColor = Color.DarkGray,
                uncheckedTrackColor = Color.Black
            )
        )
    }
}

// ── SLIDER PAGES FOR TABS ────────────────────────────────────────────────────

@Composable
fun GateSliders(state: TonexState, viewModel: TonexViewModel) {
    val color = BlockType.GATE.color
    val lang = viewModel.appLanguage
    Column(modifier = Modifier.fillMaxWidth()) {
        TonexSwitchRow(LanguageManager.get(StringKey.NOISE_GATE_POWER, lang), state.gate.enabled, { viewModel.setCc(MidiCcMap.GATE_POWER, if (it) 127 else 0) }, color)
        TonexSlider(LanguageManager.get(StringKey.THRESHOLD, lang), state.gate.threshold, onValueChange = { viewModel.setCc(MidiCcMap.GATE_THRESHOLD, it) }, color = color)
        TonexSlider(LanguageManager.get(StringKey.RELEASE, lang), state.gate.release, onValueChange = { viewModel.setCc(MidiCcMap.GATE_RELEASE, it) }, color = color)
        TonexSlider(LanguageManager.get(StringKey.DEPTH, lang), state.gate.depth, onValueChange = { viewModel.setCc(MidiCcMap.GATE_DEPTH, it) }, color = color)
        
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(LanguageManager.get(StringKey.POSITION, lang), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.LightGray)
            Row {
                Button(
                    onClick = { viewModel.setCc(MidiCcMap.GATE_POSITION, 0) },
                    colors = ButtonDefaults.buttonColors(containerColor = if (state.gate.position == 0) color else Color.DarkGray),
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.padding(end = 4.dp)
                ) {
                    Text(LanguageManager.get(StringKey.PRE_AMP, lang), fontSize = 10.sp, color = Color.White)
                }
                Button(
                    onClick = { viewModel.setCc(MidiCcMap.GATE_POSITION, 127) },
                    colors = ButtonDefaults.buttonColors(containerColor = if (state.gate.position == 127) color else Color.DarkGray),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(LanguageManager.get(StringKey.POST_AMP, lang), fontSize = 10.sp, color = Color.White)
                }
            }
        }
    }
}

@Composable
fun CompressorSliders(state: TonexState, viewModel: TonexViewModel) {
    val color = BlockType.COMPRESSOR.color
    val lang = viewModel.appLanguage
    Column(modifier = Modifier.fillMaxWidth()) {
        TonexSwitchRow(LanguageManager.get(StringKey.COMPRESSOR_POWER, lang), state.compressor.enabled, { viewModel.setCc(MidiCcMap.COMP_POWER, if (it) 127 else 0) }, color)
        TonexSlider(LanguageManager.get(StringKey.THRESHOLD, lang), state.compressor.threshold, onValueChange = { viewModel.setCc(MidiCcMap.COMP_THRESHOLD, it) }, color = color)
        TonexSlider(LanguageManager.get(StringKey.GAIN, lang), state.compressor.gain, onValueChange = { viewModel.setCc(MidiCcMap.COMP_GAIN, it) }, color = color)
        TonexSlider(LanguageManager.get(StringKey.ATTACK, lang), state.compressor.attack, onValueChange = { viewModel.setCc(MidiCcMap.COMP_ATTACK, it) }, color = color)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(LanguageManager.get(StringKey.POSITION, lang), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.LightGray)
            Row {
                Button(
                    onClick = { viewModel.setCc(MidiCcMap.COMP_POSITION, 0) },
                    colors = ButtonDefaults.buttonColors(containerColor = if (state.compressor.position == 0) color else Color.DarkGray),
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.padding(end = 4.dp)
                ) {
                    Text(LanguageManager.get(StringKey.PRE_AMP, lang), fontSize = 10.sp, color = Color.White)
                }
                Button(
                    onClick = { viewModel.setCc(MidiCcMap.COMP_POSITION, 127) },
                    colors = ButtonDefaults.buttonColors(containerColor = if (state.compressor.position == 127) color else Color.DarkGray),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(LanguageManager.get(StringKey.POST_AMP, lang), fontSize = 10.sp, color = Color.White)
                }
            }
        }
    }
}

@Composable
fun AmpSliders(state: TonexState, viewModel: TonexViewModel) {
    val color = BlockType.AMP.color
    val lang = viewModel.appLanguage
    Column(modifier = Modifier.fillMaxWidth()) {
        TonexSlider(LanguageManager.get(StringKey.GAIN, lang), state.amp.gain, onValueChange = { viewModel.setCc(MidiCcMap.AMP_GAIN, it) }, color = color)
        TonexSlider(LanguageManager.get(StringKey.VOLUME, lang), state.amp.volume, onValueChange = { viewModel.setCc(MidiCcMap.AMP_VOLUME, it) }, color = color)
        TonexSlider(LanguageManager.get(StringKey.PRESENCE, lang), state.amp.presence, onValueChange = { viewModel.setCc(MidiCcMap.PRESENCE, it) }, color = color)
        TonexSlider(LanguageManager.get(StringKey.DEPTH, lang), state.amp.depth, onValueChange = { viewModel.setCc(MidiCcMap.DEPTH, it) }, color = color)
        TonexSlider(LanguageManager.get(StringKey.MIX, lang), state.amp.mix, onValueChange = { viewModel.setCc(MidiCcMap.AMP_MIX, it) }, color = color)

        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = LanguageManager.get(StringKey.AMP_PEDAL_SKIN_SELECTOR, lang),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Gray,
            modifier = Modifier.padding(bottom = 6.dp)
        )

        LazyRow(
            modifier = Modifier.fillMaxWidth().height(80.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(50) { index ->
                val isSelected = state.skinIndex == index
                Card(
                    modifier = Modifier
                        .width(120.dp)
                        .fillMaxHeight()
                        .clickable { viewModel.setSkin(index) },
                    border = BorderStroke(
                        width = if (isSelected) 2.dp else 1.dp,
                        color = if (isSelected) color else Color(0xFF2A2A2A)
                    ),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isSelected) Color(0xFF2A2A2A) else Color(0xFF1E1E1E)
                    ),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        Image(
                            painter = painterResource(id = SkinMapper.getDrawableForSkin(index)),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                            alpha = if (isSelected) 1f else 0.5f
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.verticalGradient(
                                        colors = listOf(
                                            Color.Black.copy(alpha = 0.2f),
                                            Color.Black.copy(alpha = 0.7f)
                                        )
                                    )
                                )
                        )
                        Text(
                            text = getSkinName(index),
                            color = if (isSelected) Color.White else Color.LightGray,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun CabinetSliders(state: TonexState, viewModel: TonexViewModel) {
    val color = BlockType.CAB.color
    val lang = viewModel.appLanguage
    Column(modifier = Modifier.fillMaxWidth()) {
        TonexSwitchRow(LanguageManager.get(StringKey.CAB_SIM_BYPASS, lang), !state.globals.cabBypass, { viewModel.setCc(MidiCcMap.CAB_BYPASS, if (it) 0 else 127) }, color)
        
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
                .background(Color(0xFF1E1E1E), RoundedCornerShape(8.dp))
                .border(1.dp, color.copy(alpha = 0.3f), RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Default.Speaker,
                    contentDescription = "Cabinet Icon",
                    tint = if (!state.globals.cabBypass) color else Color.Gray,
                    modifier = Modifier.size(48.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (!state.globals.cabBypass) LanguageManager.get(StringKey.CAB_ACTIVE_TEXT, lang) else LanguageManager.get(StringKey.CAB_BYPASSED_TEXT, lang),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (!state.globals.cabBypass) Color.White else Color.Gray
                )
            }
        }
    }
}

@Composable
fun EqSliders(state: TonexState, viewModel: TonexViewModel) {
    val color = BlockType.EQ.color
    val lang = viewModel.appLanguage
    Column(modifier = Modifier.fillMaxWidth()) {
        TonexSlider(LanguageManager.get(StringKey.BASS, lang), state.eq.bass, onValueChange = { viewModel.setCc(MidiCcMap.BASS_EQ, it) }, color = color)
        TonexSlider(LanguageManager.get(StringKey.BASS_HZ, lang), state.eq.bassHz, onValueChange = { viewModel.setCc(MidiCcMap.BASS_HZ, it) }, color = color)
        TonexSlider(LanguageManager.get(StringKey.MID, lang), state.eq.mid, onValueChange = { viewModel.setCc(MidiCcMap.MID_EQ, it) }, color = color)
        TonexSlider(LanguageManager.get(StringKey.MID_Q, lang), state.eq.midQ, onValueChange = { viewModel.setCc(MidiCcMap.MID_Q, it) }, color = color)
        TonexSlider(LanguageManager.get(StringKey.MID_HZ, lang), state.eq.midHz, onValueChange = { viewModel.setCc(MidiCcMap.MID_HZ, it) }, color = color)
        TonexSlider(LanguageManager.get(StringKey.TREBLE, lang), state.eq.treble, onValueChange = { viewModel.setCc(MidiCcMap.TREBLE_EQ, it) }, color = color)
        TonexSlider(LanguageManager.get(StringKey.TREB_HZ, lang), state.eq.trebleHz, onValueChange = { viewModel.setCc(MidiCcMap.TREBLE_HZ, it) }, color = color)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(LanguageManager.get(StringKey.POSITION, lang), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.LightGray)
            Row {
                Button(
                    onClick = { viewModel.setCc(MidiCcMap.EQ_POSITION, 0) },
                    colors = ButtonDefaults.buttonColors(containerColor = if (state.eq.position == 0) color else Color.DarkGray),
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.padding(end = 4.dp)
                ) {
                    Text(LanguageManager.get(StringKey.PRE, lang), fontSize = 10.sp, color = Color.White)
                }
                Button(
                    onClick = { viewModel.setCc(MidiCcMap.EQ_POSITION, 127) },
                    colors = ButtonDefaults.buttonColors(containerColor = if (state.eq.position == 127) color else Color.DarkGray),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(LanguageManager.get(StringKey.POST, lang), fontSize = 10.sp, color = Color.White)
                }
            }
        }
    }
}

@Composable
fun ModulationSliders(state: TonexState, viewModel: TonexViewModel) {
    val color = BlockType.MODULATION.color
    val lang = viewModel.appLanguage
    Column(modifier = Modifier.fillMaxWidth()) {
        TonexSwitchRow(LanguageManager.get(StringKey.MODULATION_POWER, lang), state.modulation.enabled, { viewModel.setCc(MidiCcMap.MOD_POWER, if (it) 127 else 0) }, color)
        
        TonexSlider(LanguageManager.get(StringKey.RATE, lang), state.modulation.param0, onValueChange = { viewModel.setCc(35, it) }, color = color)
        TonexSlider(LanguageManager.get(StringKey.DEPTH, lang), state.modulation.param1, onValueChange = { viewModel.setCc(36, it) }, color = color)
        TonexSlider(LanguageManager.get(StringKey.LEVEL, lang), state.modulation.param2, onValueChange = { viewModel.setCc(37, it) }, color = color)

        Spacer(modifier = Modifier.height(10.dp))
        Text(LanguageManager.get(StringKey.MODULATION_TYPE, lang), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.LightGray)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(vertical = 8.dp)
        ) {
            ModType.entries.forEach { type ->
                val isSelected = state.modulation.type == type
                Button(
                    onClick = { viewModel.setCc(MidiCcMap.MOD_TYPE, type.ordinal) },
                    colors = ButtonDefaults.buttonColors(containerColor = if (isSelected) color else Color.DarkGray),
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.padding(end = 4.dp)
                ) {
                    Text(type.name, fontSize = 9.sp, color = Color.White)
                }
            }
        }
    }
}

@Composable
fun DelaySliders(state: TonexState, viewModel: TonexViewModel) {
    val color = BlockType.DELAY.color
    val lang = viewModel.appLanguage
    val isTape = state.delay.type == DelayType.TAPE
    Column(modifier = Modifier.fillMaxWidth()) {
        TonexSwitchRow(LanguageManager.get(StringKey.DELAY_POWER, lang), state.delay.enabled, { viewModel.setCc(MidiCcMap.DELAY_POWER, if (it) 127 else 0) }, color)
        
        TonexSlider(
            LanguageManager.get(StringKey.TIME, lang),
            state.delay.time,
            onValueChange = { viewModel.setCc(if (isTape) MidiCcMap.TAPE_DELAY_TIME else MidiCcMap.DIGITAL_DELAY_TIME, it) },
            color = color
        )
        TonexSlider(
            LanguageManager.get(StringKey.FEEDBACK, lang),
            state.delay.feedback,
            onValueChange = { viewModel.setCc(if (isTape) MidiCcMap.TAPE_DELAY_FEEDBACK else MidiCcMap.DIGITAL_DELAY_FEEDBACK, it) },
            color = color
        )
        TonexSlider(
            LanguageManager.get(StringKey.MIX, lang),
            state.delay.mix,
            onValueChange = { viewModel.setCc(if (isTape) MidiCcMap.TAPE_DELAY_MIX else MidiCcMap.DIGITAL_DELAY_MIX, it) },
            color = color
        )

        Spacer(modifier = Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(LanguageManager.get(StringKey.DELAY_MODE, lang), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.LightGray)
            Row {
                Button(
                    onClick = { viewModel.setCc(MidiCcMap.DELAY_TYPE, 0) },
                    colors = ButtonDefaults.buttonColors(containerColor = if (!isTape) color else Color.DarkGray),
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.padding(end = 4.dp)
                ) {
                    Text("DIGITAL", fontSize = 10.sp, color = Color.White)
                }
                Button(
                    onClick = { viewModel.setCc(MidiCcMap.DELAY_TYPE, 1) },
                    colors = ButtonDefaults.buttonColors(containerColor = if (isTape) color else Color.DarkGray),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text("TAPE", fontSize = 10.sp, color = Color.White)
                }
            }
        }
    }
}

@Composable
fun ReverbSliders(state: TonexState, viewModel: TonexViewModel) {
    val color = BlockType.REVERB.color
    val lang = viewModel.appLanguage
    Column(modifier = Modifier.fillMaxWidth()) {
        TonexSwitchRow(LanguageManager.get(StringKey.REVERB_POWER, lang), state.reverb.enabled, { viewModel.setCc(MidiCcMap.REVERB_POWER, if (it) 127 else 0) }, color)
        
        TonexSlider(LanguageManager.get(StringKey.TIME, lang), state.reverb.time, onValueChange = { viewModel.setCc(71, it) }, color = color)
        TonexSlider(LanguageManager.get(StringKey.PREDELAY, lang), state.reverb.predelay, onValueChange = { viewModel.setCc(72, it) }, color = color)
        TonexSlider(LanguageManager.get(StringKey.COLOR, lang), state.reverb.color, onValueChange = { viewModel.setCc(73, it) }, color = color)
        TonexSlider(LanguageManager.get(StringKey.MIX, lang), state.reverb.mix, onValueChange = { viewModel.setCc(74, it) }, color = color)

        Spacer(modifier = Modifier.height(10.dp))
        Text(LanguageManager.get(StringKey.REVERB_TYPE, lang), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.LightGray)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(vertical = 8.dp)
        ) {
            ReverbType.entries.forEach { type ->
                val isSelected = state.reverb.type == type
                Button(
                    onClick = { viewModel.setCc(MidiCcMap.REVERB_TYPE, type.ordinal) },
                    colors = ButtonDefaults.buttonColors(containerColor = if (isSelected) color else Color.DarkGray),
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.padding(end = 4.dp)
                ) {
                    Text(type.name, fontSize = 9.sp, color = Color.White)
                }
            }
        }
    }
}

@Composable
fun GlobalSliders(state: TonexState, viewModel: TonexViewModel) {
    val color = BlockType.GLOBALS.color
    val lang = viewModel.appLanguage
    val scaledBpm = (40 + (state.globals.bpm * (240 - 40) / 127))
    Column(modifier = Modifier.fillMaxWidth()) {
        TonexSlider(LanguageManager.get(StringKey.GLOBAL_VOL, lang), state.globals.volume, onValueChange = { viewModel.setCc(MidiCcMap.GLOBAL_VOLUME, it) }, color = color)
        TonexSlider(LanguageManager.get(StringKey.INPUT_TRIM, lang), state.globals.inputTrim, onValueChange = { viewModel.setCc(MidiCcMap.INPUT_TRIM, it) }, color = color)
        TonexSlider(
            "BPM",
            state.globals.bpm,
            onValueChange = { viewModel.setCc(MidiCcMap.BPM, it) },
            color = color,
            displayFormatter = { scaledBpm.toString() }
        )
        
        Spacer(modifier = Modifier.height(10.dp))
        TonexSwitchRow(LanguageManager.get(StringKey.CAB_SIM_BYPASS_GLOBAL, lang), state.globals.cabBypass, { viewModel.setCc(MidiCcMap.CAB_BYPASS, if (it) 127 else 0) }, color)
        
        Spacer(modifier = Modifier.height(20.dp))
        HorizontalDivider(color = Color(0xFF262626), thickness = 1.dp)
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = LanguageManager.get(StringKey.INFO, lang),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = color
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = LanguageManager.get(StringKey.DEVELOPED_BY_TEXT, lang),
            fontSize = 11.sp,
            color = Color.Gray,
            lineHeight = 16.sp
        )
    }
}

fun getSkinName(index: Int): String {
    return when (index) {
        0 -> "Marshall JCM"
        1 -> "Fender Silverface"
        2 -> "Tonex Amp Black"
        3 -> "Peavey 5150"
        4 -> "Ampeg Chrome"
        5 -> "Fender Tweed Big"
        6 -> "Fender Hot Rod"
        7 -> "Mesa Boogie Dual"
        8 -> "Elegant Blue"
        9 -> "Modern White Plexi"
        10 -> "Roland Jazz"
        11 -> "Orange OR120"
        12 -> "Modern Black Plexi"
        13 -> "Fender Twin"
        14 -> "Ampeg BA500"
        15 -> "Mesa Mark Wood"
        16 -> "Mesa Mark V"
        17 -> "Marshall JTM"
        18 -> "JB Dumble"
        19 -> "Jet City"
        20 -> "Vox AC30"
        21 -> "EVH 5150 III"
        22 -> "Tonex Amp Red"
        23 -> "Friedman"
        24 -> "Supro"
        25 -> "Diezel"
        26 -> "White Modern"
        27 -> "Wood Amp"
        28 -> "Big Muff"
        29 -> "Boss Black"
        30 -> "Boss Silver"
        31 -> "Boss Yellow"
        32 -> "Fuzz Red"
        33 -> "Fuzz Silver"
        34 -> "Ibanez Blue"
        35 -> "Ibanez Dark Blue"
        36 -> "Ibanez Green"
        37 -> "Ibanez Red"
        38 -> "Klon Gold"
        39 -> "Life Pedal"
        40 -> "Morning Glory"
        41 -> "MXR Double Black"
        42 -> "MXR Double Red"
        43 -> "MXR Single Black"
        44 -> "MXR Single Gold"
        45 -> "MXR Single Green"
        46 -> "MXR Single Orange"
        47 -> "MXR Single White"
        48 -> "MXR Single Yellow"
        49 -> "Rat Yellow"
        else -> "Unknown Skin"
    }
}

enum class Screen {
    Splash,
    Main
}

@Composable
fun SplashScreen(onDismiss: () -> Unit) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0C0C0C))
            .clickable { onDismiss() }
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(0.95f),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Tune,
                    contentDescription = "Logo",
                    tint = Color(0xFFD1A60C),
                    modifier = Modifier.size(56.dp)
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "TONEX MOBILE",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Black,
                    color = Color.White,
                    letterSpacing = 2.sp
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "SYSTEM CONTROLLER",
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Gray,
                    letterSpacing = 3.sp
                )
            }

            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(90.dp)
                    .background(Color(0xFF262626))
            )

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "Android application developed by Blackchorima\nand based on the Tonex One Controller project by Builty",
                    fontSize = 11.sp,
                    color = Color.LightGray,
                    textAlign = TextAlign.Center,
                    lineHeight = 16.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "TAP SCREEN TO START / TOCAR PARA EMPEZAR",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFD1A60C),
                    modifier = Modifier.alpha(alpha)
                )
            }
        }
    }
}

@Composable
fun SpanishFlag(modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Box(modifier = Modifier.fillMaxWidth().weight(1f).background(Color(0xFFC60B1E)))
        Box(modifier = Modifier.fillMaxWidth().weight(2f).background(Color(0xFFF1BF00)))
        Box(modifier = Modifier.fillMaxWidth().weight(1f).background(Color(0xFFC60B1E)))
    }
}

@Composable
fun UkFlag(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.background(Color(0xFF012169))
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidthDiag = size.height * 0.15f
            drawLine(Color.White, Offset(0f, 0f), Offset(size.width, size.height), strokeWidth = strokeWidthDiag)
            drawLine(Color.White, Offset(size.width, 0f), Offset(0f, size.height), strokeWidth = strokeWidthDiag)
            
            val strokeWidthRedDiag = size.height * 0.06f
            drawLine(Color(0xFFC60B1E), Offset(0f, 0f), Offset(size.width, size.height), strokeWidth = strokeWidthRedDiag)
            drawLine(Color(0xFFC60B1E), Offset(size.width, 0f), Offset(0f, size.height), strokeWidth = strokeWidthRedDiag)

            val strokeWidthCross = size.height * 0.25f
            drawLine(Color.White, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), strokeWidth = strokeWidthCross)
            drawLine(Color.White, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = strokeWidthCross)

            val strokeWidthRedCross = size.height * 0.15f
            drawLine(Color(0xFFC60B1E), Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), strokeWidth = strokeWidthRedCross)
            drawLine(Color(0xFFC60B1E), Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = strokeWidthRedCross)
        }
    }
}

@Composable
fun LanguageFlagButton(
    lang: AppLanguage,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(width = 36.dp, height = 22.dp)
            .clip(RoundedCornerShape(3.dp))
            .border(
                width = if (isSelected) 1.5.dp else 1.dp,
                color = if (isSelected) Color(0xFFD1A60C) else Color(0xFF333333),
                shape = RoundedCornerShape(3.dp)
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .alpha(if (isSelected) 1f else 0.4f)
        ) {
            if (lang == AppLanguage.EN) {
                UkFlag(modifier = Modifier.fillMaxSize())
            } else {
                SpanishFlag(modifier = Modifier.fillMaxSize())
            }
        }
        
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.35f))
        )
        
        Text(
            text = lang.name,
            fontSize = 9.sp,
            fontWeight = FontWeight.Black,
            color = Color.White,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 0.5.sp,
            textAlign = TextAlign.Center,
            style = TextStyle(
                platformStyle = PlatformTextStyle(
                    includeFontPadding = false
                ),
                lineHeight = 9.sp
            ),
            modifier = Modifier.align(Alignment.Center)
        )
    }
}
