/*
 * Velune Web Radio — RadioPreferences
 * DataStore preference keys for Icecast connection settings.
 */

package com.nikhil.yt.radio

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

val RadioHostKey      = stringPreferencesKey("radio_host")
val RadioPortKey      = intPreferencesKey("radio_port")
val RadioMountKey     = stringPreferencesKey("radio_mount")
val RadioPasswordKey  = stringPreferencesKey("radio_password")
val RadioBitrateKey   = intPreferencesKey("radio_bitrate")
val RadioEnabledKey   = booleanPreferencesKey("radio_enabled")

object RadioDefaults {
    const val HOST     = ""
    const val PORT     = 8000
    const val MOUNT    = "/velune"
    const val PASSWORD = ""
    const val BITRATE  = 128_000
}
