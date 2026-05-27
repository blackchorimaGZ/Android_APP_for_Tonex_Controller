package com.tonex.controller.data

object TonexParamMapper {

    /**
     * Map a MIDI CC to the ESP32 parameter index.
     * Some parameters (Modulation, Reverb, Delay) use different indices in the firmware
     * depending on the active sub-model type or sync settings.
     */
    fun ccToParamIndex(cc: Int, activeReverbType: ReverbType, activeModType: ModType, isTapeDelay: Boolean): Int {
        return when (cc) {
            // Amp
            MidiCcMap.AMP_GAIN -> 20       // TONEX_PARAM_MODEL_GAIN
            MidiCcMap.AMP_VOLUME -> 21     // TONEX_PARAM_MODEL_VOLUME
            MidiCcMap.AMP_MIX -> 22        // TONEX_PARAM_MODEX_MIX
            MidiCcMap.PRESENCE -> 34       // TONEX_PARAM_MODEL_PRESENCE
            MidiCcMap.DEPTH -> 35          // TONEX_PARAM_MODEL_DEPTH

            // Gate
            MidiCcMap.GATE_POWER -> 1      // TONEX_PARAM_NOISE_GATE_ENABLE
            MidiCcMap.GATE_POSITION -> 0   // TONEX_PARAM_NOISE_GATE_POST
            MidiCcMap.GATE_THRESHOLD -> 2  // TONEX_PARAM_NOISE_GATE_THRESHOLD
            MidiCcMap.GATE_RELEASE -> 3    // TONEX_PARAM_NOISE_GATE_RELEASE
            MidiCcMap.GATE_DEPTH -> 4      // TONEX_PARAM_NOISE_GATE_DEPTH

            // Comp
            MidiCcMap.COMP_POWER -> 6      // TONEX_PARAM_COMP_ENABLE
            MidiCcMap.COMP_POSITION -> 5   // TONEX_PARAM_COMP_POST
            MidiCcMap.COMP_THRESHOLD -> 7  // TONEX_PARAM_COMP_THRESHOLD
            MidiCcMap.COMP_GAIN -> 8       // TONEX_PARAM_COMP_MAKE_UP
            MidiCcMap.COMP_ATTACK -> 9     // TONEX_PARAM_COMP_ATTACK

            // EQ
            MidiCcMap.BASS_EQ -> 11        // TONEX_PARAM_EQ_BASS
            MidiCcMap.BASS_HZ -> 12        // TONEX_PARAM_EQ_BASS_FREQ
            MidiCcMap.MID_EQ -> 13         // TONEX_PARAM_EQ_MID
            MidiCcMap.MID_Q -> 14          // TONEX_PARAM_EQ_MIDQ
            MidiCcMap.MID_HZ -> 15         // TONEX_PARAM_EQ_MID_FREQ
            MidiCcMap.TREBLE_EQ -> 16      // TONEX_PARAM_EQ_TREBLE
            MidiCcMap.TREBLE_HZ -> 17      // TONEX_PARAM_EQ_TREBLE_FREQ
            MidiCcMap.EQ_POSITION -> 10    // TONEX_PARAM_EQ_POST

            // Modulation
            MidiCcMap.MOD_POWER -> 64      // TONEX_PARAM_MODULATION_ENABLE
            MidiCcMap.MOD_POSITION -> 63   // TONEX_PARAM_MODULATION_POST
            MidiCcMap.MOD_TYPE -> 65       // TONEX_PARAM_MODULATION_MODEL

            // Reverb
            MidiCcMap.REVERB_POWER -> 37    // TONEX_PARAM_REVERB_ENABLE
            MidiCcMap.REVERB_POSITION -> 36 // TONEX_PARAM_REVERB_POSITION
            MidiCcMap.REVERB_TYPE -> 38     // TONEX_PARAM_REVERB_MODEL

            // Delay
            MidiCcMap.DELAY_POWER -> 95     // TONEX_PARAM_DELAY_ENABLE
            MidiCcMap.DELAY_POSITION -> 94  // TONEX_PARAM_DELAY_POST
            MidiCcMap.DELAY_TYPE -> 96      // TONEX_PARAM_DELAY_MODEL

            // Globals
            MidiCcMap.GLOBAL_VOLUME -> 116  // TONEX_GLOBAL_MASTER_VOLUME
            MidiCcMap.INPUT_TRIM -> 111    // TONEX_GLOBAL_INPUT_TRIM
            MidiCcMap.BPM -> 110           // TONEX_GLOBAL_BPM
            MidiCcMap.CAB_BYPASS -> 112     // TONEX_GLOBAL_CABSIM_BYPASS
            MidiCcMap.TUNING_REF -> 114    // TONEX_GLOBAL_TUNING_REFERENCE

            // Reverb Time (59, 63, 67, 80, 71, 76)
            71, 59, 63, 67, 80, 76 -> {
                when (activeReverbType) {
                    ReverbType.SPRING_1 -> 39
                    ReverbType.SPRING_2 -> 43
                    ReverbType.SPRING_3 -> 47
                    ReverbType.SPRING_4 -> 51
                    ReverbType.ROOM -> 55
                    ReverbType.PLATE -> 59
                }
            }
            // Reverb Predelay (60, 64, 68, 81, 72, 77)
            72, 60, 64, 68, 81, 77 -> {
                when (activeReverbType) {
                    ReverbType.SPRING_1 -> 40
                    ReverbType.SPRING_2 -> 44
                    ReverbType.SPRING_3 -> 48
                    ReverbType.SPRING_4 -> 52
                    ReverbType.ROOM -> 56
                    ReverbType.PLATE -> 60
                }
            }
            // Reverb Color (61, 65, 69, 82, 73, 78)
            73, 61, 65, 69, 82, 78 -> {
                when (activeReverbType) {
                    ReverbType.SPRING_1 -> 41
                    ReverbType.SPRING_2 -> 45
                    ReverbType.SPRING_3 -> 49
                    ReverbType.SPRING_4 -> 53
                    ReverbType.ROOM -> 57
                    ReverbType.PLATE -> 61
                }
            }
            // Reverb Mix (62, 66, 70, 83, 74, 79)
            74, 62, 66, 70, 83, 79 -> {
                when (activeReverbType) {
                    ReverbType.SPRING_1 -> 42
                    ReverbType.SPRING_2 -> 46
                    ReverbType.SPRING_3 -> 50
                    ReverbType.SPRING_4 -> 54
                    ReverbType.ROOM -> 58
                    ReverbType.PLATE -> 62
                }
            }

            // Modulation Param0 (Rate) (35, 39, 44, 48, 53)
            35, 39, 44, 48, 53 -> {
                when (activeModType) {
                    ModType.CHORUS -> 68
                    ModType.TREMOLO -> 73
                    ModType.PHASER -> 79
                    ModType.FLANGER -> 84
                    ModType.ROTARY -> 90
                }
            }
            // Modulation Param1 (Depth/Shape) (36, 40, 45, 49, 54)
            36, 40, 45, 49, 54 -> {
                when (activeModType) {
                    ModType.CHORUS -> 69
                    ModType.TREMOLO -> 74
                    ModType.PHASER -> 80
                    ModType.FLANGER -> 85
                    ModType.ROTARY -> 91
                }
            }
            // Modulation Param2 (Level/Spread) (37, 41, 46, 50, 55, 112)
            37, 41, 46, 50, 55, 112 -> {
                when (activeModType) {
                    ModType.CHORUS -> 70
                    ModType.TREMOLO -> 75
                    ModType.PHASER -> 81
                    ModType.FLANGER -> 86
                    ModType.ROTARY -> 92
                }
            }
            // Modulation Param3 (Extra Level) (42, 51, 56, 113)
            42, 51, 56, 113 -> {
                when (activeModType) {
                    ModType.CHORUS -> 70
                    ModType.TREMOLO -> 76
                    ModType.PHASER -> 81
                    ModType.FLANGER -> 87
                    ModType.ROTARY -> 93
                }
            }

            // Delay parameters
            MidiCcMap.DIGITAL_DELAY_TIME, MidiCcMap.TAPE_DELAY_TIME -> {
                if (isTapeDelay) 105 else 99
            }
            MidiCcMap.DIGITAL_DELAY_FEEDBACK, MidiCcMap.TAPE_DELAY_FEEDBACK -> {
                if (isTapeDelay) 106 else 100
            }
            MidiCcMap.DIGITAL_DELAY_MIX, MidiCcMap.TAPE_DELAY_MIX -> {
                if (isTapeDelay) 108 else 102
            }
            MidiCcMap.DIGITAL_DELAY_SYNC, MidiCcMap.TAPE_DELAY_SYNC -> {
                if (isTapeDelay) 103 else 97
            }

            else -> -1
        }
    }

    /**
     * Map a firmware parameter index back to a MIDI CC number.
     */
    fun paramIndexToCc(index: Int): Int {
        return when (index) {
            // Amp
            20 -> MidiCcMap.AMP_GAIN
            21 -> MidiCcMap.AMP_VOLUME
            22 -> MidiCcMap.AMP_MIX
            34 -> MidiCcMap.PRESENCE
            35 -> MidiCcMap.DEPTH

            // Gate
            1 -> MidiCcMap.GATE_POWER
            0 -> MidiCcMap.GATE_POSITION
            2 -> MidiCcMap.GATE_THRESHOLD
            3 -> MidiCcMap.GATE_RELEASE
            4 -> MidiCcMap.GATE_DEPTH

            // Comp
            6 -> MidiCcMap.COMP_POWER
            5 -> MidiCcMap.COMP_POSITION
            7 -> MidiCcMap.COMP_THRESHOLD
            8 -> MidiCcMap.COMP_GAIN
            9 -> MidiCcMap.COMP_ATTACK

            // EQ
            11 -> MidiCcMap.BASS_EQ
            12 -> MidiCcMap.BASS_HZ
            13 -> MidiCcMap.MID_EQ
            14 -> MidiCcMap.MID_Q
            15 -> MidiCcMap.MID_HZ
            16 -> MidiCcMap.TREBLE_EQ
            17 -> MidiCcMap.TREBLE_HZ
            10 -> MidiCcMap.EQ_POSITION

            // Modulation
            64 -> MidiCcMap.MOD_POWER
            63 -> MidiCcMap.MOD_POSITION
            65 -> MidiCcMap.MOD_TYPE

            // Reverb
            37 -> MidiCcMap.REVERB_POWER
            36 -> MidiCcMap.REVERB_POSITION
            38 -> MidiCcMap.REVERB_TYPE

            // Delay
            95 -> MidiCcMap.DELAY_POWER
            94 -> MidiCcMap.DELAY_POSITION
            96 -> MidiCcMap.DELAY_TYPE

            // Globals
            116 -> MidiCcMap.GLOBAL_VOLUME
            111 -> MidiCcMap.INPUT_TRIM
            110 -> MidiCcMap.BPM
            112 -> MidiCcMap.CAB_BYPASS
            114 -> MidiCcMap.TUNING_REF

            // Reverb params (regardless of model type)
            39, 43, 47, 51, 55, 59 -> 71 // time
            40, 44, 48, 52, 56, 60 -> 72 // predelay
            41, 45, 49, 53, 57, 61 -> 73 // color
            42, 46, 50, 54, 58, 62 -> 74 // mix

            // Delay params
            97 -> MidiCcMap.DIGITAL_DELAY_SYNC
            99 -> MidiCcMap.DIGITAL_DELAY_TIME
            100 -> MidiCcMap.DIGITAL_DELAY_FEEDBACK
            102 -> MidiCcMap.DIGITAL_DELAY_MIX
            103 -> MidiCcMap.TAPE_DELAY_SYNC
            105 -> MidiCcMap.TAPE_DELAY_TIME
            106 -> MidiCcMap.TAPE_DELAY_FEEDBACK
            108 -> MidiCcMap.TAPE_DELAY_MIX

            // Modulation params (map to param0, param1, param2, param3)
            68, 73, 79, 84, 90 -> 35 // param0 (Rate)
            69, 74, 80, 85, 91 -> 36 // param1 (Depth)
            70, 75, 81, 86, 92 -> 37 // param2 (Level/Spread)
            76, 87, 93 -> 42 // param3 (Extra Level)

            else -> -1
        }
    }
}
