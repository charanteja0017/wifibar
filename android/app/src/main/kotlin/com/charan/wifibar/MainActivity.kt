package com.charan.wifibar

import android.content.ActivityNotFoundException
import android.content.Intent
import android.app.UiModeManager
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.viewModels
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.charan.wifibar.ui.AboutScreen
import com.charan.wifibar.ui.LocateScreen
import com.charan.wifibar.ui.NetworkListScreen
import com.charan.wifibar.ui.WiFiBarTheme
import com.charan.wifibar.wifi.PermissionState

class MainActivity : ComponentActivity() {
    private val model: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val wallpaper by model.useWallpaperColours.collectAsStateWithLifecycle()
            WiFiBarTheme(useWallpaperColours = wallpaper) { WiFiBarApp(model) }
        }
        handleDemo(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDemo(intent)
    }

    /**
     * Debug builds only, e.g. `adb shell am start -n com.charan.wifibar/.MainActivity --es demo sweep`:
     * - `--es demo sweep`: replay a recorded direction sweep.
     * - `--es demo sample`: made-up networks, for screenshots without real names or BSSIDs.
     * - `--es demo walk`: sample networks plus a recorded walk in Walk to find (debug asset `demo-walk.csv`).
     * - `--es demo about`: open About & credits.
     * - `--es theme light|dark|system`: this app's light or dark mode (Android's per-app night mode).
     */
    private fun handleDemo(intent: Intent?) {
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (!debuggable || intent == null) return
        when (intent.getStringExtra("theme")) {
            "light" -> getSystemService(UiModeManager::class.java).setApplicationNightMode(UiModeManager.MODE_NIGHT_NO)
            "dark" -> getSystemService(UiModeManager::class.java).setApplicationNightMode(UiModeManager.MODE_NIGHT_YES)
            "system" -> getSystemService(UiModeManager::class.java).setApplicationNightMode(UiModeManager.MODE_NIGHT_AUTO)
        }
        when (intent.getStringExtra("demo")) {
            "sweep" -> model.demoSweep()
            "sample" -> model.useSampleData()
            "walk" -> runCatching { assets.open("demo-walk.csv").bufferedReader().readText() }.getOrNull()?.let(model::demoWalk)
            "about" -> model.openAbout()
        }
    }

    @Composable
    private fun WiFiBarApp(model: MainViewModel) {
        val wifi by model.wifi.collectAsStateWithLifecycle()
        val permissions by model.permissions.collectAsStateWithLifecycle()
        val locator by model.locator.collectAsStateWithLifecycle()
        val showAbout by model.showAbout.collectAsStateWithLifecycle()
        val wallpaper by model.useWallpaperColours.collectAsStateWithLifecycle()
        val snackbar = remember { SnackbarHostState() }
        val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            model.refreshPermissions()
            model.rescan()
        }

        // Scan and read the link only while the app is on screen.
        LifecycleStartEffect(Unit) {
            model.start()
            locator?.start()
            onStopOrDispose {
                model.stop()
                locator?.stop()
            }
        }
        // Permissions or Location may have been changed in Settings.
        LifecycleResumeEffect(Unit) {
            model.refreshPermissions()
            onPauseOrDispose { }
        }
        LaunchedEffect(Unit) {
            model.messages.collect { snackbar.showSnackbar(it) }
        }
        BackHandler(enabled = locator != null && !showAbout) { model.closeLocator() }
        BackHandler(enabled = showAbout) { model.closeAbout() }

        val list = @Composable { modifier: Modifier ->
            androidx.compose.foundation.layout.Box(modifier) {
                NetworkListScreen(
                    wifi = wifi,
                    permissions = permissions,
                    snackbar = snackbar,
                    onRequestPermissions = { permissionLauncher.launch(PermissionState.requested) },
                    onOpenLocationSettings = { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) },
                    onOpenAppSettings = {
                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
                    },
                    onRescan = model::rescan,
                    onJoin = { startActivity(Intent(Settings.Panel.ACTION_WIFI)) },
                    onLocate = model::openLocator,
                    onAbout = model::openAbout,
                )
            }
        }
        val locate = @Composable { modifier: Modifier, closable: Boolean ->
            locator?.let { current ->
                androidx.compose.foundation.layout.Box(modifier) {
                    LocateScreen(
                        locator = current,
                        onClose = if (closable) model::closeLocator else null,
                        onOpenMaps = ::openMaps,
                        onOpenUrl = { url -> safeStart(Intent(Intent.ACTION_VIEW, Uri.parse(url))) },
                    )
                }
            }
        }

        if (showAbout) {
            AboutScreen(
                useWallpaperColours = wallpaper,
                onUseWallpaperColours = model::setUseWallpaperColours,
                onOpenUrl = { url -> safeStart(Intent(Intent.ACTION_VIEW, Uri.parse(url))) },
                onClose = model::closeAbout,
            )
            return
        }

        // Tablets: list and Locate side by side. Phones: Locate replaces the list, Back returns.
        BoxWithConstraints {
            when {
                locator == null -> list(Modifier)
                maxWidth >= 840.dp -> Row {
                    list(Modifier.weight(0.45f))
                    VerticalDivider(Modifier.fillMaxHeight())
                    locate(Modifier.weight(0.55f), true)
                }
                else -> locate(Modifier, true)
            }
        }
    }

    private fun openMaps(latitude: Double, longitude: Double, label: String) {
        safeStart(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$latitude,$longitude?q=$latitude,$longitude(${Uri.encode(label)})")))
    }

    /** Android 11+ hides other apps from queries, so just try and handle "no app". */
    private fun safeStart(intent: Intent) {
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            // Nothing installed that can open it.
        }
    }
}
