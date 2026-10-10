package com.charan.wifibar.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.charan.wifibar.R
import com.mikepenz.aboutlibraries.ui.compose.m3.LibrariesContainer
import com.mikepenz.aboutlibraries.ui.compose.android.produceLibraries

/** A paper the app's methods come from, with what it's used for. Titles as registered with Crossref. */
private data class Paper(val authors: String, val title: String, val venue: String, val doi: String, val usedFor: String)

private val papers = listOf(
    Paper(
        "Zhang et al.", "I am the antenna", "MobiCom 2011", "10.1145/2030613.2030626",
        "Your body blocks signal from behind: the basis of the direction sweep and of the body-shadow term in Walk to find.",
    ),
    Paper(
        "Della Rosa et al.", "Human-Induced Effects on RSS Ranging Measurements for Cooperative Positioning",
        "International Journal of Navigation and Observation, 2012", "10.1155/2012/959140",
        "How much hand grip and body orientation change the reading; sizes the body-shadow prior.",
    ),
    Paper(
        "Dressel & Kochenderfer", "Pseudo-bearing Measurements for Improved Localization of Radio Sources with Multirotor UAVs",
        "ICRA 2018", "10.1109/icra.2018.8460734",
        "Choosing each move by the information it gives; the arrow's information bonus.",
    ),
    Paper(
        "Vergassola et al.", "‘Infotaxis’ as a strategy for searching without gradients", "Nature, 2007", "10.1038/nature05464",
        "Searching by expected information. Tested in simulation and set aside: on its own it walked people away from the router.",
    ),
    Paper(
        "Zhuang et al.", "Fast WiFi access point localization and autonomous crowdsourcing", "UPINLBS 2014", "10.1109/upinlbs.2014.7033737",
        "Estimating the path-loss settings together with the access point's position.",
    ),
)

/** About & credits: who made it, the research it rests on, data sources, appearance, and every open-source licence. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    useWallpaperColours: Boolean,
    onUseWallpaperColours: (Boolean) -> Unit,
    onOpenUrl: (String) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
            ?.let { "Version ${it.versionName} (${it.longVersionCode})" } ?: ""
    }
    val libraries by produceLibraries(R.raw.aboutlibraries)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("About & credits") },
                navigationIcon = {
                    IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        LibrariesContainer(
            libraries = libraries,
            modifier = Modifier.fillMaxSize().padding(padding),
            header = {
                item {
                    Column(
                        Modifier.fillMaxWidth().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        BrandMark(size = 72.dp)
                        Text("WiFiBar", style = MaterialTheme.typography.headlineMedium)
                        if (version.isNotEmpty()) {
                            Text(version, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                item { Heading("Made by") }
                item { Body("Charan Teja Pampana") }
                item {
                    Text(
                        "charanteja0017.in",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.fillMaxWidth().clickable { onOpenUrl("https://charanteja0017.in") }
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                item { Heading("Built on research") }
                papers.forEach { paper ->
                    item {
                        Column(
                            Modifier.fillMaxWidth().clickable { onOpenUrl("https://doi.org/${paper.doi}") }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        ) {
                            Text(paper.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                            Text("${paper.authors}, ${paper.venue}", style = MaterialTheme.typography.bodySmall)
                            Text(paper.usedFor, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                item { Heading("Map data") }
                item {
                    Body(
                        "Access point locations come from WiGLE.net, only when you look one up with your own WiGLE account.",
                        Modifier.clickable { onOpenUrl("https://wigle.net") },
                    )
                }
                item { Heading("Appearance") }
                item {
                    Row(
                        Modifier.fillMaxWidth().clickable { onUseWallpaperColours(!useWallpaperColours) }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Use wallpaper colours", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "Android's own look, taken from your wallpaper. Off: WiFiBar's brand colours.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = useWallpaperColours, onCheckedChange = onUseWallpaperColours)
                    }
                }
                item { Heading("Open-source licences") }
            },
            divider = { HorizontalDivider() },
        )
    }
}

@Composable
private fun Heading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}

@Composable
private fun Body(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodyMedium, modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
}
