// swift-tools-version: 6.2
import Foundation
import PackageDescription

/// With only the Command Line Tools installed, SwiftPM's default build system intermittently leaves
/// the Swift Testing macro plugin out of the test target's emit-module job ("plugin for module
/// 'TestingMacros' not found"). Loading it explicitly fixes that. Xcode's toolchain doesn't need it.
let testSettings: [SwiftSetting] = {
    let commandLineTools = "/Library/Developer/CommandLineTools"
    let plugin = commandLineTools + "/usr/lib/swift/host/plugins/testing/libTestingMacros.dylib"
    let activeDeveloperDir = ProcessInfo.processInfo.environment["DEVELOPER_DIR"]
        ?? (try? FileManager.default.destinationOfSymbolicLink(atPath: "/var/db/xcode_select_link"))
    guard activeDeveloperDir == commandLineTools, FileManager.default.fileExists(atPath: plugin) else { return [] }
    return [.unsafeFlags(["-load-plugin-library", plugin])]
}()

let package = Package(
    name: "WiFiBar",
    platforms: [.macOS(.v26)],
    products: [
        .library(name: "WiFiCore", targets: ["WiFiCore"]),
        .executable(name: "WiFiBar", targets: ["WiFiBar"]),
        .executable(name: "WiFiBarWidget", targets: ["WiFiBarWidget"]),
    ],
    targets: [
        // Pure Swift models and logic, shared by the menu bar app and (later) the widget extension.
        .target(name: "WiFiCore"),
        // Built-in gyroscope/accelerometer via undocumented IOKit API (CoreMotion is unavailable on macOS).
        .target(
            name: "SPUMotion",
            linkerSettings: [.linkedFramework("IOKit"), .linkedFramework("CoreFoundation")]
        ),
        // Desktop widget views; also used by the app to render previews in debug builds.
        .target(name: "WiFiWidgetUI", dependencies: ["WiFiCore"]),
        // WidgetKit extension; scripts/bundle.sh wraps it as WiFiBar.app/Contents/PlugIns/WiFiBarWidget.appex.
        // App extensions must start in Foundation's NSExtensionMain (which then runs `@main`), as Xcode
        // links them; starting at `main` directly leaves the extension unconfigured and it crashes on launch.
        .executableTarget(
            name: "WiFiBarWidget",
            dependencies: ["WiFiCore", "WiFiWidgetUI"],
            linkerSettings: [.unsafeFlags(["-Xlinker", "-e", "-Xlinker", "_NSExtensionMain"])]
        ),
        .executableTarget(
            name: "WiFiBar",
            dependencies: ["WiFiCore", "SPUMotion", "WiFiWidgetUI"],
            linkerSettings: [
                .linkedFramework("CoreWLAN"),
                .linkedFramework("CoreLocation"),
                .linkedFramework("ServiceManagement"),
            ]
        ),
        .testTarget(name: "WiFiCoreTests", dependencies: ["WiFiCore"], swiftSettings: testSettings),
    ]
)
