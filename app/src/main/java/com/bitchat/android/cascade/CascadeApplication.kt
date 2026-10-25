package com.bitchat.android.cascade

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class CascadeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        com.bitchat.android.mesh.PowerManager.getInstance(this).start()
        com.bitchat.android.ui.debug.DebugPreferenceManager.init(this)
        com.bitchat.android.ui.theme.ThemePreferenceManager.init(this)
        // Do not initialize upstream Nostr, Tor, location channels, or chat services.
    }
}
