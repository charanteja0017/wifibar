package com.charan.wifibar

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.charan.wifibar.core.Bssid
import com.charan.wifibar.locate.LocateTarget
import com.charan.wifibar.locate.Locator
import com.charan.wifibar.wifi.PermissionState
import com.charan.wifibar.wifi.WifiRepository
import com.charan.wifibar.wifi.WifiState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {
    val repository = WifiRepository(application)
    val wifi: StateFlow<WifiState> = repository.state

    private val _permissions = MutableStateFlow(PermissionState.read(application))
    val permissions: StateFlow<PermissionState> = _permissions.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages

    private var loop: Job? = null

    private val _locator = MutableStateFlow<Locator?>(null)
    /** The open Locate screen, if any; one access point at a time. */
    val locator: StateFlow<Locator?> = _locator.asStateFlow()

    fun openLocator(target: LocateTarget) {
        // The card and the list describe the same access point slightly differently (the card has no
        // channel width), so match on the BSSID: reopening it must not throw away a walk in progress.
        val open = _locator.value?.state?.value?.target
        if (open != null && Bssid.normalize(open.bssid) == Bssid.normalize(target.bssid)) return
        _locator.value?.stop()
        _locator.value = Locator(getApplication(), repository, viewModelScope, target).also { it.start() }
    }

    /** Debug builds: open Locate on the current network and replay the recorded 17:46 sweep. */
    fun demoSweep() {
        val target = repository.readLink()?.let(LocateTarget::of) ?: return
        openLocator(target)
        _locator.value?.replay(
            com.charan.wifibar.locate.SampleSweep.at1746,
            "Demo: replaying your 17:46 sweep. Directions are relative to how the tablet faced when the demo started.",
        )
    }

    fun closeLocator() {
        _locator.value?.stop()
        _locator.value = null
    }

    private val settings = application.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
    private val _useWallpaperColours = MutableStateFlow(settings.getBoolean(WALLPAPER_COLOURS, false))
    /** Off: the brand palette. On: Android's own look, the wallpaper's colours. */
    val useWallpaperColours: StateFlow<Boolean> = _useWallpaperColours.asStateFlow()

    fun setUseWallpaperColours(on: Boolean) {
        settings.edit().putBoolean(WALLPAPER_COLOURS, on).apply()
        _useWallpaperColours.value = on
    }

    private val _showAbout = MutableStateFlow(false)
    val showAbout: StateFlow<Boolean> = _showAbout.asStateFlow()
    fun openAbout() { _showAbout.value = true }
    fun closeAbout() { _showAbout.value = false }

    private companion object {
        const val WALLPAPER_COLOURS = "use_wallpaper_colours"
    }

    /** While the app is on screen: read the link every 2 s, and scan every 35 s (Android allows 4 per 2 min). */
    fun start() {
        refreshPermissions()
        repository.start()
        loop?.cancel()
        loop = viewModelScope.launch {
            var tick = 0
            while (isActive) {
                repository.readLink()
                if (tick % 17 == 0 && _permissions.value.canSeeNetworks) repository.startScan()
                tick++
                delay(2_000)
            }
        }
    }

    fun stop() {
        loop?.cancel()
        loop = null
        repository.stop()
    }

    fun refreshPermissions() {
        _permissions.value = PermissionState.read(getApplication())
    }

    /** Pull to refresh. */
    fun rescan() {
        if (!repository.startScan()) {
            _messages.tryEmit("Android allows apps 4 scans every 2 minutes. Showing the latest results it has.")
        }
        repository.readResults()
    }
}
