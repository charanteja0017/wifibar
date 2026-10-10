package com.charan.wifibar.ui

import android.os.SystemClock
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.charan.wifibar.core.AccessPoint
import com.charan.wifibar.core.LinkInfo
import com.charan.wifibar.core.NetworkGroup
import com.charan.wifibar.core.SignalQuality
import com.charan.wifibar.locate.LocateTarget
import com.charan.wifibar.wifi.LocationAccess
import com.charan.wifibar.wifi.PermissionState
import com.charan.wifibar.wifi.WifiState
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkListScreen(
    wifi: WifiState,
    permissions: PermissionState,
    snackbar: SnackbarHostState,
    onRequestPermissions: () -> Unit,
    onOpenLocationSettings: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onRescan: () -> Unit,
    onJoin: () -> Unit,
    onLocate: (LocateTarget) -> Unit,
    onAbout: () -> Unit,
) {
    var refreshing by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val expanded = rememberSaveable { mutableStateOf(setOf<String>()) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { BrandMark(Modifier.padding(start = 16.dp, end = 4.dp), size = 26.dp) },
                title = { Text("WiFiBar") },
                actions = {
                    IconButton(onClick = onRescan, enabled = permissions.canSeeNetworks) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Rescan")
                    }
                    Box {
                        IconButton(onClick = { menu = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("About & credits") },
                                leadingIcon = { Icon(Icons.Outlined.Info, contentDescription = null) },
                                onClick = { menu = false; onAbout() },
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = {
                refreshing = true
                onRescan()
                refreshing = false
            },
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            LazyColumn(
                contentPadding = PaddingValues(bottom = 24.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (!permissions.canSeeNetworks) {
                    item {
                        PermissionCard(permissions, onRequestPermissions, onOpenLocationSettings, onOpenAppSettings)
                    }
                }
                if (!wifi.wifiEnabled) {
                    item { MessageCard("Wi-Fi is off", "Turn on Wi-Fi to see networks around you.", "Wi-Fi settings", onJoin) }
                }
                wifi.link?.let { link -> item(key = "current") { CurrentNetworkCard(link, onLocate) } }

                if (permissions.canSeeNetworks) {
                    item(key = "header") {
                        Text(
                            "Networks nearby",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                        )
                    }
                    for (group in wifi.groups) {
                        val isExpanded = group.ssid in expanded.value
                        item(key = "g:" + group.ssid) {
                            GroupRow(group, isExpanded) {
                                expanded.value = if (isExpanded) expanded.value - group.ssid else expanded.value + group.ssid
                            }
                        }
                        if (isExpanded) {
                            items(group.accessPoints, key = { "ap:" + group.ssid + it.id }) { ap ->
                                AccessPointRow(group, ap, onJoin, onLocate)
                            }
                        }
                    }
                    item(key = "footer") { Footer(wifi) }
                }
            }
        }
    }
}

@Composable
private fun CurrentNetworkCard(link: LinkInfo, onLocate: (LocateTarget) -> Unit) {
    ElevatedCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                WifiGlyph(link.quality, size = 36.dp, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        link.ssid ?: "Connected",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${link.quality.label} · ${link.band.label} · Ch ${link.channel}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("${link.rssi}", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        " dBm",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
            }
            LinearProgressIndicator(
                progress = { SignalQuality.meterFraction(link.rssi).toFloat() },
                color = link.quality.color(),
                strokeCap = StrokeCap.Round,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Detail("Link speed", "${link.linkSpeedMbps} Mbps")
                Detail("Standard", link.wifiStandard)
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Box(Modifier.weight(1f)) { Detail("BSSID", link.bssid ?: "Hidden", monospace = true) }
                LocateTarget.of(link)?.let { target ->
                    FilledTonalButton(onClick = { onLocate(target) }) {
                        Icon(Icons.Filled.LocationOn, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Locate")
                    }
                }
            }
        }
    }
}

@Composable
private fun Detail(label: String, value: String, monospace: Boolean = false) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontFamily = if (monospace) FontFamily.Monospace else null)
    }
}

@Composable
private fun GroupRow(group: NetworkGroup, expanded: Boolean, onToggle: () -> Unit) {
    val count = group.accessPoints.size
    val bands = group.bands.filter { it.label != "Unknown band" }.map { it.label.removeSuffix(" GHz") }
    val supporting = if (count == 1) group.strongest.channelSummary
    else "$count access points · " + bands.dropLast(1).joinToString(", ").let { if (it.isEmpty()) "" else "$it & " } + bands.lastOrNull().orEmpty() + " GHz"
    ListItem(
        modifier = Modifier.clickable(onClick = onToggle),
        leadingContent = {
            WifiGlyph(group.strongest.quality, tint = if (group.isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
        },
        headlineContent = {
            Text(
                group.ssid,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = if (group.isCurrent) FontWeight.SemiBold else null,
            )
        },
        supportingContent = { Text(supporting, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (group.security.isSecured) {
                    Icon(Icons.Filled.Lock, contentDescription = "Secured", modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                }
                Text("${group.strongest.rssi} dBm", style = MaterialTheme.typography.labelLarge)
                Icon(
                    if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (expanded) "Hide access points" else "Show access points",
                )
            }
        },
    )
}

@Composable
private fun AccessPointRow(group: NetworkGroup, ap: AccessPoint, onJoin: () -> Unit, onLocate: (LocateTarget) -> Unit) {
    val current = group.isCurrent(ap)
    ListItem(
        modifier = Modifier.padding(start = 24.dp).clickable(enabled = !current, onClick = onJoin),
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        leadingContent = {
            if (current) Icon(Icons.Filled.Check, contentDescription = "Connected", tint = MaterialTheme.colorScheme.primary)
            else Spacer(Modifier.size(24.dp))
        },
        headlineContent = { Text(ap.channelSummary, style = MaterialTheme.typography.bodyMedium) },
        supportingContent = {
            Text(ap.bssid ?: "BSSID hidden", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${ap.rssi} dBm", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.width(8.dp))
                WifiGlyph(ap.quality, size = 20.dp)
                LocateTarget.of(group.ssid, ap)?.let { target ->
                    IconButton(onClick = { onLocate(target) }) {
                        Icon(Icons.Filled.LocationOn, contentDescription = "Locate this access point")
                    }
                }
            }
        },
    )
}

@Composable
private fun Footer(wifi: WifiState) {
    val now by produceState(SystemClock.elapsedRealtime()) {
        while (true) {
            delay(1_000)
            value = SystemClock.elapsedRealtime()
        }
    }
    val age = wifi.resultsAtElapsedMillis?.let { ((now - it) / 1000).coerceAtLeast(0) }
    val parts = buildList {
        add(if (age == null) "No scan results yet" else "Updated $age s ago")
        if (wifi.hiddenCount > 0) add("${wifi.hiddenCount} hidden networks not shown")
        add("Join opens Android’s Wi-Fi settings: apps can’t pick a specific access point.")
    }
    Text(
        parts.joinToString("\n"),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(16.dp),
    )
}

@Composable
private fun PermissionCard(
    permissions: PermissionState,
    onRequest: () -> Unit,
    onOpenLocationSettings: () -> Unit,
    onOpenAppSettings: () -> Unit,
) {
    when {
        permissions.location == LocationAccess.DENIED -> MessageCard(
            "Allow location access",
            "Android shows Wi-Fi network names and access points only to apps with precise location. WiFiBar never reads your location.",
            "Allow",
            onRequest,
        )
        permissions.location == LocationAccess.APPROXIMATE -> MessageCard(
            "Precise location needed",
            "With approximate location Android hides all Wi-Fi scan results. Switch WiFiBar to precise location in its settings.",
            "Open settings",
            onOpenAppSettings,
        )
        !permissions.locationServicesOn -> MessageCard(
            "Turn on Location",
            "Android returns no Wi-Fi scan results while Location is switched off for the whole device.",
            "Location settings",
            onOpenLocationSettings,
        )
    }
}

@Composable
private fun MessageCard(title: String, body: String, action: String, onAction: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (title.contains("Location", true) || title.contains("location")) Icons.Filled.LocationOn else Icons.Filled.Settings, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            Text(body, style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onAction) { Text(action) }
        }
    }
}
