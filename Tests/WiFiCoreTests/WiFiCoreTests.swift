import Foundation
import Testing
@testable import WiFiCore

private func ap(
    _ ssid: String?,
    _ bssid: String?,
    rssi: Int,
    channel: Int = 36,
    band: WiFiBand = .ghz5
) -> AccessPoint {
    AccessPoint(
        ssid: ssid, bssid: bssid, rssi: rssi, noise: -95, channel: channel, band: band,
        channelWidth: 80, security: .personal, securityLabel: "WPA2 Personal"
    )
}

@Suite struct SignalQualityTests {
    @Test(arguments: [
        (-30, SignalQuality.excellent), (-55, .excellent),
        (-56, .good), (-67, .good),
        (-68, .fair), (-77, .fair),
        (-78, .weak), (-100, .weak),
    ])
    func boundaries(rssi: Int, expected: SignalQuality) {
        #expect(SignalQuality(rssi: rssi) == expected)
    }

    @Test func barsAndSymbolValuesIncrease() {
        let ordered = SignalQuality.allCases.sorted()
        #expect(ordered.map(\.bars) == [0, 1, 2, 3])
        #expect(ordered.map(\.symbolVariableValue) == ordered.map(\.symbolVariableValue).sorted())
    }

    @Test func meterFractionClamps() {
        #expect(SignalQuality.meterFraction(rssi: -120) == 0)
        #expect(SignalQuality.meterFraction(rssi: -10) == 1)
        #expect(SignalQuality.meterFraction(rssi: -60) == 0.5)
    }

    @Test func snrNeedsANoiseFloor() {
        #expect(ap("a", "x", rssi: -50).snr == 45)
        let noNoise = AccessPoint(
            ssid: "a", bssid: "x", rssi: -50, noise: 0, channel: 1, band: .ghz2_4,
            channelWidth: 20, security: .open, securityLabel: "Open"
        )
        #expect(noNoise.snr == nil)
    }
}

@Suite struct GroupingTests {
    @Test func sameNameAccessPointsShareAGroupStrongestFirst() {
        let groups = NetworkGrouping.group(
            [
                ap("Office", "aa:00:00:00:00:01", rssi: -70),
                ap("Office", "aa:00:00:00:00:02", rssi: -48),
                ap("Office", "aa:00:00:00:00:03", rssi: -60, channel: 6, band: .ghz2_4),
                ap("Cafe", "bb:00:00:00:00:01", rssi: -40),
            ],
            currentSSID: nil, currentBSSID: nil, knownSSIDs: []
        )
        #expect(groups.map(\.ssid) == ["Cafe", "Office"])
        let office = try! #require(groups.first { $0.ssid == "Office" })
        #expect(office.accessPoints.map(\.rssi) == [-48, -60, -70])
        #expect(office.bands == [.ghz2_4, .ghz5])
    }

    @Test func joinedNetworkComesFirstAndMarksItsBSSID() {
        let groups = NetworkGrouping.group(
            [
                ap("Strong", "cc:00:00:00:00:01", rssi: -35),
                ap("Home", "dd:00:00:00:00:01", rssi: -75),
                ap("Home", "dd:00:00:00:00:02", rssi: -50),
            ],
            currentSSID: "Home", currentBSSID: "DD:0:0:0:0:1", knownSSIDs: ["Strong"]
        )
        #expect(groups.map(\.ssid) == ["Home", "Strong"])
        let home = groups[0]
        #expect(home.isCurrent && home.isKnown)
        #expect(home.accessPoints.filter(home.isCurrent).map(\.bssid) == ["dd:00:00:00:00:01"])
        #expect(groups[1].isKnown && !groups[1].isCurrent)
    }

    @Test func hiddenAndDuplicateAccessPointsAreDropped() {
        let input = [
            ap(nil, "ee:00:00:00:00:01", rssi: -50),
            ap("", "ee:00:00:00:00:02", rssi: -50),
            ap("Lab", "ff:00:00:00:00:01", rssi: -50),
            ap("Lab", "FF:00:00:00:00:01", rssi: -52),
        ]
        let groups = NetworkGrouping.group(input, currentSSID: nil, currentBSSID: nil, knownSSIDs: [])
        #expect(groups.count == 1)
        #expect(groups[0].accessPoints.count == 1)
        #expect(NetworkGrouping.hiddenCount(input) == 2)
    }

    @Test func ties() {
        let groups = NetworkGrouping.group(
            [ap("b", "1", rssi: -60), ap("a", "2", rssi: -60)],
            currentSSID: nil, currentBSSID: nil, knownSSIDs: []
        )
        #expect(groups.map(\.ssid) == ["a", "b"])
    }
}

@Suite struct DeepLinkTests {
    let token = "0123456789abcdef"

    @Test func connectRoundTrip() throws {
        let link = DeepLink.connect(ssid: "Café & Bar #2", bssid: "aa:bb:cc:dd:ee:ff")
        let url = link.url(token: token)
        #expect(try DeepLink.parse(url, expectedToken: token).get() == link)
    }

    @Test func connectWithoutBSSIDMeansStrongest() throws {
        let url = DeepLink.connect(ssid: "Home", bssid: nil).url(token: token)
        #expect(try DeepLink.parse(url, expectedToken: token).get() == .connect(ssid: "Home", bssid: nil))
    }

    @Test(arguments: [nil, "", "wrong", "0123456789abcdeF"])
    func connectRejectsBadTokens(_ bad: String?) {
        let url = DeepLink.connect(ssid: "Home", bssid: nil).url(token: bad)
        #expect(DeepLink.parse(url, expectedToken: token) == .failure(.badToken))
    }

    @Test func openNeedsNoToken() {
        #expect(DeepLink.parse(URL(string: "wifibar://open")!, expectedToken: token) == .success(.open))
    }

    @Test func otherURLsAreRejected() {
        #expect(DeepLink.parse(URL(string: "https://open")!, expectedToken: token) == .failure(.wrongScheme))
        #expect(DeepLink.parse(URL(string: "wifibar://forget")!, expectedToken: token) == .failure(.unknownAction("forget")))
        let noSSID = URL(string: "wifibar://connect?token=\(token)")!
        #expect(DeepLink.parse(noSSID, expectedToken: token) == .failure(.missingSSID))
    }

    @Test func tokensAreRandom() {
        #expect(DeepLink.makeToken() != DeepLink.makeToken())
    }
}

@Suite struct SnapshotStoreTests {
    @Test func roundTrip() throws {
        let dir = FileManager.default.temporaryDirectory.appending(path: UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: dir) }
        let store = SnapshotStore(url: dir.appending(path: "nested/snapshot.json"))
        let aps = [ap("Home", "aa:00:00:00:00:01", rssi: -50), ap("Home", "aa:00:00:00:00:02", rssi: -66)]
        let snapshot = WiFiSnapshot(
            date: Date(timeIntervalSince1970: 1_800_000_000),
            interfaceName: "en0",
            powerOn: true,
            locationAuthorized: true,
            link: LinkInfo(
                ssid: "Home", bssid: "aa:00:00:00:00:01", rssi: -50, noise: -95, transmitRate: 864,
                channel: 36, band: .ghz5, channelWidth: 80, security: .personal,
                securityLabel: "WPA2 Personal", phyMode: "802.11ax"
            ),
            groups: NetworkGrouping.group(aps, currentSSID: "Home", currentBSSID: "aa:00:00:00:00:01", knownSSIDs: []),
            hiddenCount: 1,
            linkToken: token
        )
        try store.write(snapshot)
        #expect(try store.read() == snapshot)
    }

    let token = "t"
}
