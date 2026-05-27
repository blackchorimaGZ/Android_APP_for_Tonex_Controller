package com.tonex.controller.data

import com.tonex.controller.R

object SkinMapper {
    val skinDrawables = listOf(
        // Amps
        R.drawable.jcm,                // 0: AMP_SKIN_JCM
        R.drawable.slvrface,           // 1: AMP_SKIN_SILVERFACE
        R.drawable.tnxablk,            // 2: AMP_SKIN_TONEXAMPBLACK
        R.drawable.skin_5150,          // 3: AMP_SKIN_5150
        R.drawable.ampgchrm,           // 4: AMP_SKIN_AMPEGCHROME
        R.drawable.fndrtwdg,           // 5: AMP_SKIN_FENDERTWEEDBIG
        R.drawable.fndrhtrd,           // 6: AMP_SKIN_FENDERHOTROD
        R.drawable.msbogdul,           // 7: AMP_SKIN_MESABOOGIEDUAL
        R.drawable.elgntblu,           // 8: AMP_SKIN_ELEGANTBLUE
        R.drawable.mdnwhplx,           // 9: AMP_SKIN_MODERNWHITEPLEXI
        R.drawable.roljazz,            // 10: AMP_SKIN_ROLANDJAZZ
        R.drawable.orngr120,           // 11: AMP_SKIN_ORANGEOR120
        R.drawable.mdnbkplx,           // 12: AMP_SKIN_MODERNBLACKPLEXI
        R.drawable.fndrtwin,           // 13: AMP_SKIN_FENDERTWIN
        R.drawable.ba500,              // 14: AMP_SKIN_BA500
        R.drawable.msamkwd,            // 15: AMP_SKIN_MESAMARKWOOD
        R.drawable.mesamkv,            // 16: AMP_SKIN_MESAMARKV
        R.drawable.jtm,                // 17: AMP_SKIN_JTM
        R.drawable.jbdumble,           // 18: AMP_SKIN_JBDUMBLE1
        R.drawable.jetcity,            // 19: AMP_SKIN_JETCITY
        R.drawable.ac30,               // 20: AMP_SKIN_AC30
        R.drawable.evh,                // 21: AMP_SKIN_EVH
        R.drawable.tnxared,            // 22: AMP_SKIN_TONEXAMPRED
        R.drawable.friedman,           // 23: AMP_SKIN_FRIEDMANN
        R.drawable.supro,              // 24: AMP_SKIN_SUPRO
        R.drawable.diezel,             // 25: AMP_SKIN_DIEZEL
        R.drawable.whtmdrn,            // 26: AMP_SKIN_WHITEMODERN
        R.drawable.woodamp,            // 27: AMP_SKIN_WOODAMP

        // Pedals
        R.drawable.bigmuff,            // 28: PEDAL_SKIN_BIGMUFF
        R.drawable.bossblk,            // 29: PEDAL_SKIN_BOSSBLACK
        R.drawable.bossslvr,           // 30: PEDAL_SKIN_BOSSSILVER
        R.drawable.bossyel,            // 31: PEDAL_SKIN_BOSSYELLOW
        R.drawable.fuzzred,            // 32: PEDAL_SKIN_FUZZRED
        R.drawable.fuzzslvr,           // 33: PEDAL_SKIN_FUZZSILVER
        R.drawable.ibnzblue,           // 34: PEDAL_SKIN_IBANEZBLUE
        R.drawable.ibnzdblu,           // 35: PEDAL_SKIN_IBANEZDARKBLUE
        R.drawable.ibnzgrn,            // 36: PEDAL_SKIN_IBANEZGREEN
        R.drawable.ibnzred,            // 37: PEDAL_SKIN_IBANEZRED
        R.drawable.klongld,            // 38: PEDAL_SKIN_KLONGOLD
        R.drawable.lifepdl,            // 39: PEDAL_SKIN_LIFEPEDAL
        R.drawable.mngglry,            // 40: PEDAL_SKIN_MORNINGGLORY
        R.drawable.mxrdbbl,            // 41: PEDAL_SKIN_MXRDOUBLEBLACK
        R.drawable.mxrdblrd,           // 42: PEDAL_SKIN_MXRDOUBLERED
        R.drawable.mxrsngbk,           // 43: PEDAL_SKIN_MXRSINGLEBLACK
        R.drawable.mxrsnggd,           // 44: PEDAL_SKIN_MXRSINGLEGOLD
        R.drawable.mxrsnggn,           // 45: PEDAL_SKIN_MXRSINGLEGREEN
        R.drawable.mxrsgorg,           // 46: PEDAL_SKIN_MXRSINGLEORANGE
        R.drawable.mxrsngwh,           // 47: PEDAL_SKIN_MXRSINGLEWHITE
        R.drawable.mxrsngyl,           // 48: PEDAL_SKIN_MXRSINGLEYELLOW
        R.drawable.ratyell             // 49: PEDAL_SKIN_RATYELLOW
    )

    fun getDrawableForSkin(index: Int): Int {
        if (index in skinDrawables.indices) {
            return skinDrawables[index]
        }
        return R.drawable.tnxablk // Default skin
    }
}
