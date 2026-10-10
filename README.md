# WiFiBar

Wi-Fi signal strength for every nearby access point, and help finding the router itself.

- **Mac app** (Apple silicon, macOS 26 or later): a menu bar panel in the style of Apple's own Wi-Fi menu, plus a desktop widget.
- **Android app** (Android 12 or later, phones and tablets): the same network list, plus *Walk to find*, which guides you to the physical router as you walk.

Both list every network with its signal (bars and dBm), and every access point (BSSID) separately when several share a name.

## Contents

- [Mac app](#mac-app)
- [Android app](#android-app)
- [How Walk to find works](#how-walk-to-find-works)
- [Branding](#branding)
- [Limitations](#limitations)
- [Credits](#credits)
- [Licence](#licence)

## Mac app

It is built only from system parts: SwiftUI and AppKit controls, SF Symbols, system colours, and a Liquid Glass panel.

### Build and run

These need only the Command Line Tools; Xcode isn't required.

```sh
scripts/install.sh         # build, install to ~/Applications/WiFiBar.app and launch (needed for the widget)
scripts/bundle.sh          # build only → build/WiFiBar.app (menu bar app works from here; the widget doesn't)
swift test                 # unit tests
```

- **Location access is required.** macOS reveals Wi-Fi names and BSSIDs only to apps with Location access. WiFiBar asks on first launch and never reads your location.
- **Signing.** `bundle.sh` signs with a `WiFiBar Local` code-signing certificate from your login keychain when one exists, so the Location grant survives rebuilds. Without it the app is signed ad hoc, and macOS may ask again after a rebuild.
- **Debug builds** (`scripts/bundle.sh debug`) add `wifibar://debug-render[?expand]`. It saves the panel as `panel-light.png` and `panel-dark.png` in `~/Library/Application Support/WiFiBar/`. Glass and vibrancy don't render this way, so check colours on screen.
- **About WiFiBar** (in the panel) opens the standard About window, which shows `Resources/Credits.rtf`.

### Layout

| Path | What |
|---|---|
| `Sources/WiFiCore` | Models, RSSI → signal quality, grouping by network name, direction sweep maths, `wifibar://` links, snapshot store. No CoreWLAN, so the widget can reuse it. |
| `Sources/WiFiBar/WiFiService.swift` | All CoreWLAN calls (scan, join, power), run on their own serial queue. |
| `Sources/WiFiBar/AppModel.swift` | State, scan timing, joining, deep links. |
| `Sources/WiFiBar/StatusItemController.swift` | Menu bar item and the borderless glass panel. |
| `Sources/WiFiBar/Views` | SwiftUI panel content and the Locate window. |
| `Sources/SPUMotion` | Reads the MacBook's motion sensor for the direction sweep (private IOKit HID API). |
| `Sources/WiFiBarWidget`, `Sources/WiFiWidgetUI` | The desktop widget. |

### How it behaves

- **Scanning:** every 15 s while the panel is open. In the background it does at most one full scan every 5 minutes, plus CoreWLAN events. Full scans briefly take the radio off-channel.
- **Joining a specific AP:**
  - It scans that name again and joins the exact BSSID you picked.
  - macOS may settle on, or later roam to, another AP of the same network, usually when the chosen one is weak. The panel tells you when that happens.
  - There is no public API to lock a BSSID.
- **Credentials:**
  - Enterprise (802.1X) networks join with the credentials macOS already has.
  - Personal (WPA) networks need the password. WiFiBar reads the saved one from the keychain; macOS asks you to allow that. If you don't, it asks you to type the password.
- **`wifibar://connect` links:** they must carry the token the app writes into `snapshot.json`, so other apps and web pages can't make the Mac join a network.
- **`wifibar://open`:** shows the panel.

### Finding a router (Locate)

To open it, click **Locate** on the current-network card, or the viewfinder icon on any access point row. You can also right-click a row and choose **Locate…**.

- **Live signal:** a large dBm reading, Stronger/Weaker/Steady, a 2-minute chart, and your best reading so far. You can turn on a sound for each new best. Walk toward the peak.
  - For the access point you're joined to, it reads every second from your connection.
  - For any other access point it relies on scans. macOS answers most scans from its cache and only really rescans about every 10–15 s, so only those fresh readings are plotted. Join the network if you want readings every second.
- **Estimated distance:** a log-distance path-loss estimate, d = 10^((RSSI₁ₘ − RSSI) / 10n), with a range.
  - RSSI₁ₘ is a typical 20 dBm router minus free-space loss at 1 m and about 10 dB of client losses.
  - n is 2.6–3.5 indoors and 1.8–2.3 in open space.
  - It's a rough guide, not a measurement.
- **Direction sweep:** hold the Mac in front of you and turn slowly in a full circle (about 30 s). Your body blocks signal from behind, so the reading peaks when you face the router. The motion sensor tracks how far you've turned; it is only reachable through a private API, so this part couldn't ship in the Mac App Store.
- **Map (WiGLE):** with a free wigle.net account's API name and token, the app looks up the BSSID with `GET api.wigle.net/api/v2/network/search?netid=…` and shows the result on a map.
  - The token is stored in your login keychain.
  - It only works for access points someone has already mapped, and doing a lookup sends the BSSID to wigle.net.

### Desktop widget

**Adding it:** right-click the desktop, choose **Edit Widgets…**, search for "WiFiBar", and add **Wi-Fi Signal**. It comes in three sizes:
- **Small:** the current network, its dBm and quality.
- **Medium:** the same plus the four strongest nearby networks.
- **Large:** networks grouped by name with their access points underneath. Tap one to join it.

**How it works:**
- **The extension:** `Sources/WiFiBarWidget` is a WidgetKit extension that `scripts/bundle.sh` wraps as `WiFiBar.app/Contents/PlugIns/WiFiBarWidget.appex`.
- **Data:** the menu bar app does all the scanning and writes `~/Library/Application Support/WiFiBar/snapshot.json`. The sandboxed widget reads that file through a read-only temporary-exception entitlement for that folder. An App Group would need an Apple developer team.
- **Refreshes:** the app asks WidgetKit to reload when the network, access point, signal (in 3 dB steps) or nearby list changes. A different network or access point triggers a reload right away; anything else waits at least a minute. As a fallback the widget refreshes itself every 15 minutes. macOS rations widget reloads, so the widget isn't live like the menu.
- **Taps:** they open `wifibar://` links carrying the app's token.

**What it takes to build this without Xcode:**
- **Entry point:** the extension must start in Foundation's `NSExtensionMain` (`-Xlinker -e -Xlinker _NSExtensionMain`), as Xcode links it. Starting at `main` crashes on launch, in `ExtensionMain.shared`.
- **Location:** macOS's widget service only accepts third-party widgets from an Applications folder. From the build folder it logs "shouldn't cache it" and ignores the widget, so use `scripts/install.sh`.
- **Debug preview:** debug builds render the three sizes with `wifibar://debug-widget-render` (`widget-*.png` in Application Support).

### Toolchain notes (macOS 27 SDK with Command Line Tools only)

- SwiftUI's `@State` is now a macro whose plugin ships only with Xcode. The views use `ViewState`, an alias for the `State` property wrapper.
- SwiftPM's default build system intermittently drops the Swift Testing macro plugin. `Package.swift` loads it explicitly, but only when the Command Line Tools are the active toolchain.

## Android app

Kotlin and Jetpack Compose (Material 3), in `android/`. The Wi-Fi logic, the router search and its simulator live in a plain Kotlin/JVM module (`android/core`), so they run as ordinary unit tests without a device.

### What it does

- **Network list:** every network grouped by name, strongest first, with your current network on top; each access point with its band, channel, width and security. Tablets show the list and Locate side by side.
- **Join** opens Android's Wi-Fi panel: Android apps can't switch the system's Wi-Fi to a chosen BSSID.
- **Locate:** live signal, a 2-minute chart, estimated distance, a WiGLE map lookup (the token is encrypted with an Android Keystore key and excluded from backups), and a direction sweep:
  - At Android's default rate, you face 8 directions in turn and hold each for about 4 readings.
  - When readings arrive every 1.2 s or faster (see [Faster readings](#faster-readings-optional)), it becomes the Mac's continuous turn with a live plot.
- **Walk to find:** walk holding the device in front of you and follow the arrow. A heading-up mini-map shows your path, the readings, where the router most likely is, and how sure the app is ("x% sure it's within 2 m"). It says stop at 80%, or when the signal is very strong. See [How Walk to find works](#how-walk-to-find-works).
- **About & credits** (⋮ menu): research papers, data sources, open-source licences (generated at build time by AboutLibraries), and a switch between the brand colours and your wallpaper colours.

### Build and run

Needs JDK 17 and the Android SDK.

```sh
cd android
./gradlew :core:test :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

- `android/gradle.properties` pins the Gradle daemon to a Homebrew JDK 17 (`org.gradle.java.home`). Change or remove that line on other machines.
- `android/local.properties` (not in git) holds `sdk.dir`; Android Studio writes it for you.
- Debug builds can replay a recorded sweep: `adb shell am start -n com.charan.wifibar/.MainActivity --es demo sweep`.

### Permissions

- **Location** (precise) and, on Android 13+, **Nearby Wi-Fi devices**: Android shows network names, BSSIDs and scan results only with these. The app never reads your location.
- **Physical activity** is declared only so the app can log Android's own step detector next to its own step counter, for comparison. The app doesn't ask for it, and Walk to find doesn't need it.

### Faster readings (optional)

Android refreshes the connected network's signal about every 3 s (measured on a Xiaomi Pad 8), and scans of other networks are limited to 4 per 2 minutes. A system API, `WifiManager.setLinkLayerStatsPollingInterval`, changes the refresh; apps can't call it, but the adb shell can. The app includes a small entry point for that:

```sh
adb shell 'CLASSPATH=$(pm path com.charan.wifibar | cut -d: -f2) app_process /system/bin com.charan.wifibar.shell.FastPoll 1000'
adb shell dumpsys wifi | grep -i PollRssiInterval     # check it took
adb shell 'CLASSPATH=$(pm path com.charan.wifibar | cut -d: -f2) app_process /system/bin com.charan.wifibar.shell.FastPoll 0'   # undo
```

It changes a system setting until the device restarts. The app learns the actual refresh rate from the readings, and Locate shows it.

### Recorded walks

Each Walk to find session is saved as CSV in the app's private storage (newest 20, excluded from backups): acceleration, steps, and each reading with where you were and which way you faced. On a debug build:

```sh
adb shell run-as com.charan.wifibar ls files/walks
adb shell run-as com.charan.wifibar cat files/walks/<name>.csv
```

Nine real walks are in `android/core/src/test/resources/walks` for replay tests.

### Layout

| Path | What |
|---|---|
| `android/core/src/main/kotlin/…/core` | Models, signal quality, grouping, distance, direction sweep, step counter, `RouterSearch` (Walk to find), WiGLE. |
| `android/core/src/test/kotlin/…/core` | Unit tests, the opt-in `SearchBenchmark` simulator, and `WalkReplay` for recorded walks. |
| `android/app/src/main/kotlin/…/wifi` | Scans, the current link, permissions. |
| `android/app/src/main/kotlin/…/locate` | `Locator` (Locate, sweep, Walk to find), sensors, walk recording. |
| `android/app/src/main/kotlin/…/ui` | Compose screens, the brand theme and the mini-map. |
| `android/app/src/main/kotlin/…/shell/FastPoll.kt` | The adb-only faster-readings helper. |

## How Walk to find works

- **Where you are:** steps come from the accelerometer (Android's step detector reported no steps with the tablet held in front of you), heading from the gyroscope, and each step moves you 0.7 m that way.
- **Where the router is:** for every 1 m cell within 15 m of the middle of your walk, the app scores how well "the router is here" explains every reading:

  rssi = P − 10·n·log₁₀(√(d² + h²)) − A·behind(angle) + noise

  - h = 1 m allows for a router mounted higher than the device.
  - `behind` models your body blocking signal from behind (A is fitted per cell).
  - P is fitted per cell; n is averaged over 2.0–3.5.
  - The noise is Gaussian (σ = 5 dB) plus a 15% chance of a wild reading. Older readings count as noisier, because step counting drifts.
- **Which way to walk:** of 8 directions, the one that most reduces the expected distance to the router after the next reading, with a bonus for the information that reading would bring.
- **When to stop:** at least 80% likely that the router is within 2 m, or about 3 s of readings averaging −32 dBm or stronger.

**Measured in simulation** (`SearchBenchmark`: 150 searches, a world deliberately unlike the model, jumps in the signal as often as measured on real walks):

| | Stopped within 2.5 m | Stopped too far away | No stop in 3 min | Median time |
|---|---|---|---|---|
| First version, a reading every 3 s | 66% | 12% | 22% | 94 s |
| Current, a reading every 3 s | 90% | 1% | 8% | 70 s |
| Current, a reading every 1 s | 96% | 3% | 0% | 48 s |

Run it with:

```sh
cd android
./gradlew :core:test -Pbench -Pruns=150 -Pintervals=3,1 -Pharsh=0.10 --tests '*SearchBenchmark*' -i
```

These are simulation results; accuracy on real walks with a known router position hasn't been measured yet.

## Branding

The logo is plotted, not drawn: it's the app's own direction-sweep smoothing (a circular Gaussian, σ = 25°) aimed at a dot, the router. See `Branding/BRAND.md`.

- `python3 Branding/make_brand.py` regenerates the icons and brand sheet in `Branding/out/` (needs matplotlib, numpy and Pillow).
- `Branding/make_android_colors.mjs` regenerates the Android colour schemes (`android/app/…/ui/BrandColors.kt`) from the brand palette with Google's material-color-utilities; usage is at the top of the file.

## Limitations

- **iPhone isn't supported:** iOS doesn't let ordinary apps scan nearby networks.
- **One combined signal per network:** neither macOS nor Android gives apps per-antenna data, so direction comes from your body's shadow and from walking, not from the antennas.
- **Walls and floors:** Walk to find doesn't model walls, and a router on another floor will confuse it.
- **Wi-Fi RTT** (distance measurement on supported phones and routers) isn't used yet.
- **Mac App Store:** the Mac direction sweep uses a private API, so a store version would have to leave it out.

## Credits

Made by Charan Teja Pampana, [charanteja0017.in](https://charanteja0017.in).

Research the router finding builds on:
- Zhang et al., "I am the antenna", MobiCom 2011, [doi:10.1145/2030613.2030626](https://doi.org/10.1145/2030613.2030626): body shadowing for direction.
- Della Rosa et al., "Human-Induced Effects on RSS Ranging Measurements for Cooperative Positioning", IJNO 2012, [doi:10.1155/2012/959140](https://doi.org/10.1155/2012/959140).
- Dressel & Kochenderfer, "Pseudo-bearing Measurements for Improved Localization of Radio Sources with Multirotor UAVs", ICRA 2018, [doi:10.1109/icra.2018.8460734](https://doi.org/10.1109/icra.2018.8460734).
- Vergassola et al., "'Infotaxis' as a strategy for searching without gradients", Nature 2007, [doi:10.1038/nature05464](https://doi.org/10.1038/nature05464).
- Zhuang et al., "Fast WiFi access point localization and autonomous crowdsourcing", UPINLBS 2014, [doi:10.1109/upinlbs.2014.7033737](https://doi.org/10.1109/upinlbs.2014.7033737).

Access point locations come from [WiGLE.net](https://wigle.net) when you look one up with your own account.

## Licence

[MIT](LICENSE).
