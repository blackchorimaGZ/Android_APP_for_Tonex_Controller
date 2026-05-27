package com.tonex.controller.data

import kotlinx.serialization.Serializable

/**
 * Complete state of the Tonex One pedal, matching the WebSocket JSON structure
 * used by the Builty TonexOneController ESP32-S3 firmware.
 *
 * MIDI CC values are 0-127 integers. The ESP32 web UI sends/receives these
 * as JSON objects over async WebSockets.
 */

// ── Top-level state ──────────────────────────────────────────────────────────

@Serializable
data class TonexState(
    val preset: Int = 0,                    // 0-19, active preset index
    val presetNames: List<String> = List(20) { "Preset ${it + 1}" },
    val connected: Boolean = false,
    val pedalType: PedalType = PedalType.TONEX_ONE,
    val bypassed: Boolean = false,
    val slotA: Int = 0,                     // preset loaded in Slot A
    val slotB: Int = 1,                     // preset loaded in Slot B
    val skinIndex: Int = 2,                 // 0-49, active skin index (default 2 = TONEXAMPBLACK)

    // ── Amp Model ──
    val amp: AmpParams = AmpParams(),

    // ── Gate (Noise Reduction) ──
    val gate: GateParams = GateParams(),

    // ── Compressor ──
    val compressor: CompressorParams = CompressorParams(),

    // ── EQ ──
    val eq: EqParams = EqParams(),

    // ── Modulation ──
    val modulation: ModulationParams = ModulationParams(),

    // ── Delay ──
    val delay: DelayParams = DelayParams(),

    // ── Reverb ──
    val reverb: ReverbParams = ReverbParams(),

    // ── Globals ──
    val globals: GlobalParams = GlobalParams()
)

@Serializable
enum class PedalType { TONEX_ONE, TONEX_PEDAL, VALETON_GP5 }

// ── Amp parameters ──────────────────────────────────────────────────────────

@Serializable
data class AmpParams(
    val gain: Int = 64,           // CC 102
    val volume: Int = 100,        // CC 103
    val mix: Int = 127,           // CC 104 (dry/wet for DI captures)
    val presence: Int = 64,       // CC 106
    val depth: Int = 64           // CC 107
)

// ── Gate parameters ─────────────────────────────────────────────────────────

@Serializable
data class GateParams(
    val enabled: Boolean = false,  // CC 14 → 127/0
    val position: Int = 0,         // CC 13 → 0 = First, 127 = Post Amp
    val threshold: Int = 50,       // CC 15
    val release: Int = 50,         // CC 16
    val depth: Int = 100           // CC 17
)

// ── Compressor parameters ───────────────────────────────────────────────────

@Serializable
data class CompressorParams(
    val enabled: Boolean = false,  // CC 18 → 127/0
    val position: Int = 0,         // CC 22 → 0 = First, 127 = Post Amp
    val threshold: Int = 64,       // CC 19
    val gain: Int = 64,            // CC 20
    val attack: Int = 64           // CC 21
)

// ── EQ parameters ───────────────────────────────────────────────────────────

@Serializable
data class EqParams(
    val bass: Int = 64,            // CC 23
    val bassHz: Int = 64,          // CC 24
    val mid: Int = 64,             // CC 25
    val midQ: Int = 64,            // CC 26
    val midHz: Int = 64,           // CC 27
    val treble: Int = 64,          // CC 28
    val trebleHz: Int = 64,        // CC 29
    val position: Int = 0          // CC 30
)

// ── Modulation parameters ───────────────────────────────────────────────────

@Serializable
enum class ModType { CHORUS, TREMOLO, PHASER, FLANGER, ROTARY }

@Serializable
data class ModulationParams(
    val enabled: Boolean = false,  // CC 32 → 127/0
    val position: Int = 0,         // CC 31
    val type: ModType = ModType.CHORUS, // CC 33
    val param0: Int = 64,          // rate/speed (varies by type)
    val param1: Int = 64,          // depth/shape
    val param2: Int = 64,          // level/spread
    val param3: Int = 64,          // extra param
    val sync: Boolean = false      // sync to BPM
)

// ── Delay parameters ────────────────────────────────────────────────────────

@Serializable
enum class DelayType { DIGITAL, TAPE }

@Serializable
data class DelayParams(
    val enabled: Boolean = false,  // CC 2 → 127/0
    val position: Int = 0,         // CC 1
    val type: DelayType = DelayType.DIGITAL, // CC 3
    val sync: Boolean = false,     // CC 4 (digital) / CC 91 (tape)
    val time: Int = 64,            // CC 5 / CC 92
    val feedback: Int = 40,        // CC 6 / CC 93
    val mode: Int = 0,             // CC 7 / CC 94 → 0=Normal, 64=PingPong
    val mix: Int = 50              // CC 8 / CC 95
)

// ── Reverb parameters ───────────────────────────────────────────────────────

@Serializable
enum class ReverbType { SPRING_1, SPRING_2, SPRING_3, SPRING_4, ROOM, PLATE }

@Serializable
data class ReverbParams(
    val enabled: Boolean = false,  // CC 75 → 127/0
    val position: Int = 0,         // CC 84
    val type: ReverbType = ReverbType.ROOM, // CC 85
    val time: Int = 64,
    val predelay: Int = 30,
    val color: Int = 64,
    val mix: Int = 40
)

// ── Global parameters ───────────────────────────────────────────────────────

@Serializable
data class GlobalParams(
    val volume: Int = 100,         // CC 122
    val inputTrim: Int = 64,       // CC 116
    val bpm: Int = 120,            // CC 88 (mapped 40-240)
    val cabBypass: Boolean = false, // CC 117
    val tuningRef: Int = 64        // CC 119
)

// ── WebSocket message types ─────────────────────────────────────────────────

/**
 * Represents a single parameter change sent to/from the ESP32 via WebSocket.
 * The ESP32 web UI communicates using simple JSON messages like:
 *   { "cc": 102, "value": 80 }     → Set Amp Gain to 80
 *   { "preset": 5 }                → Switch to preset 5
 *   { "state": { ... } }           → Full state sync
 */
@Serializable
data class CcMessage(
    val cc: Int,
    val value: Int
)

@Serializable
data class PresetMessage(
    val preset: Int
)

/**
 * Maps human-readable parameter names to their MIDI CC numbers,
 * matching the Builty TonexOneController firmware.
 */
object MidiCcMap {
    // Delay
    const val DELAY_POSITION = 1
    const val DELAY_POWER = 2
    const val DELAY_TYPE = 3
    const val DIGITAL_DELAY_SYNC = 4
    const val DIGITAL_DELAY_TIME = 5
    const val DIGITAL_DELAY_FEEDBACK = 6
    const val DIGITAL_DELAY_MODE = 7
    const val DIGITAL_DELAY_MIX = 8

    // Gate
    const val GATE_POSITION = 13
    const val GATE_POWER = 14
    const val GATE_THRESHOLD = 15
    const val GATE_RELEASE = 16
    const val GATE_DEPTH = 17

    // Compressor
    const val COMP_POWER = 18
    const val COMP_THRESHOLD = 19
    const val COMP_GAIN = 20
    const val COMP_ATTACK = 21
    const val COMP_POSITION = 22

    // EQ
    const val BASS_EQ = 23
    const val BASS_HZ = 24
    const val MID_EQ = 25
    const val MID_Q = 26
    const val MID_HZ = 27
    const val TREBLE_EQ = 28
    const val TREBLE_HZ = 29
    const val EQ_POSITION = 30

    // Modulation
    const val MOD_POSITION = 31
    const val MOD_POWER = 32
    const val MOD_TYPE = 33

    // Reverb
    const val REVERB_POWER = 75
    const val REVERB_POSITION = 84
    const val REVERB_TYPE = 85

    // Amp
    const val AMP_GAIN = 102
    const val AMP_VOLUME = 103
    const val AMP_MIX = 104
    const val PRESENCE = 106
    const val DEPTH = 107

    // Globals
    const val INPUT_TRIM = 116
    const val CAB_BYPASS = 117
    const val TUNING_REF = 119
    const val GLOBAL_VOLUME = 122
    const val SELECT_PRESET = 127

    // Tape Delay
    const val TAPE_DELAY_SYNC = 91
    const val TAPE_DELAY_TIME = 92
    const val TAPE_DELAY_FEEDBACK = 93
    const val TAPE_DELAY_MODE = 94
    const val TAPE_DELAY_MIX = 95

    // BPM
    const val BPM = 88
    const val PRESET_DOWN = 86
    const val PRESET_UP = 87
}
