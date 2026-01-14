package com.ne_bknn.adbinstaller.state

import android.content.Context

class PairingStateStore(appContext: Context) {
    private val prefs = appContext.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isPaired(): Boolean = prefs.getBoolean(KEY_PAIRED, false)

    fun setPaired(paired: Boolean) {
        prefs.edit().putBoolean(KEY_PAIRED, paired).apply()
    }

    private companion object {
        const val PREFS = "adbinstaller_state"
        const val KEY_PAIRED = "paired"
    }
}


