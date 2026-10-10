import Foundation
import Testing
@testable import WiFiCore

@Suite struct DistanceEstimatorTests {
    @Test func referenceFollowsFreeSpaceLossPerBand() {
        // 20 dBm − FSPL(1 m) − 10 dB: ≈ −30 dBm at 2.4 GHz, ≈ −37 dBm at 5 GHz.
        #expect(abs(DistanceEstimator.referenceRSSI(band: .ghz2_4) - -30.2) < 0.1)
        #expect(abs(DistanceEstimator.referenceRSSI(band: .ghz5) - -37.3) < 0.1)
        #expect(DistanceEstimator.referenceRSSI(band: .ghz6) < DistanceEstimator.referenceRSSI(band: .ghz5))
    }

    @Test func weakerSignalMeansFartherAway() {
        let near = DistanceEstimator.estimate(rssi: -45, band: .ghz5, environment: .indoors)
        let far = DistanceEstimator.estimate(rssi: -75, band: .ghz5, environment: .indoors)
        #expect(near.meters < far.meters)
    }

    @Test func knownPoint() {
        // −60 dBm at 5 GHz indoors: 10^((−37.26 + 60) / 30) ≈ 5.7 m.
        let estimate = DistanceEstimator.estimate(rssi: -60, band: .ghz5, environment: .indoors)
        #expect(abs(estimate.meters - 5.7) < 0.1)
        #expect(estimate.low < estimate.meters && estimate.meters < estimate.high)
    }

    @Test func openSpaceReachesFartherForTheSameReading() {
        let indoors = DistanceEstimator.estimate(rssi: -70, band: .ghz2_4, environment: .indoors)
        let open = DistanceEstimator.estimate(rssi: -70, band: .ghz2_4, environment: .openSpace)
        #expect(open.meters > indoors.meters)
    }

    @Test func veryStrongSignalIsClampedNotZero() {
        let estimate = DistanceEstimator.estimate(rssi: -10, band: .ghz2_4, environment: .indoors)
        #expect(estimate.meters == 0.3)
        #expect(DistanceEstimator.format(estimate.meters) == "< 1 m")
    }

    @Test func rangeIsOmittedWhenRightNextToTheRouter() {
        let close = DistanceEstimator.estimate(rssi: -36, band: .ghz5, environment: .indoors)
        #expect(DistanceEstimator.format(close.meters) == "< 1 m")
        #expect(DistanceEstimator.formatRange(close) == nil)
        let far = DistanceEstimator.estimate(rssi: -60, band: .ghz5, environment: .indoors)
        // 10^(22.74/35) ≈ 4.47 m to 10^(22.74/26) ≈ 7.49 m
        #expect(DistanceEstimator.formatRange(far) == "4–7 m")
    }

    @Test(arguments: [(0.4, "< 1 m"), (5.7, "≈ 6 m"), (9.4, "≈ 9 m"), (23.0, "≈ 25 m"), (38.9, "≈ 40 m")])
    func formatting(meters: Double, expected: String) {
        #expect(DistanceEstimator.format(meters) == expected)
    }
}

@Suite struct SignalTrackerTests {
    private func feed(_ values: [Int], into tracker: inout SignalTracker) -> [Bool] {
        let start = Date(timeIntervalSince1970: 0)
        return values.enumerated().map { index, rssi in
            tracker.add(SignalSample(date: start.addingTimeInterval(Double(index)), rssi: rssi))
        }
    }

    @Test func peakAndNewBestSignals() {
        var tracker = SignalTracker()
        let newBest = feed([-70, -72, -65, -65, -60], into: &tracker)
        #expect(newBest == [false, false, true, false, true])
        #expect(tracker.peak?.rssi == -60)
    }

    @Test func trendNeedsEnoughReadings() {
        var tracker = SignalTracker()
        _ = feed([-80, -70, -60, -50, -40], into: &tracker)
        #expect(tracker.trend == .unknown)
    }

    @Test func trendUsesAveragesNotSingleReadings() {
        var stronger = SignalTracker()
        _ = feed([-80, -79, -81, -74, -73, -75], into: &stronger)
        #expect(stronger.trend == .stronger)

        var weaker = SignalTracker()
        _ = feed([-60, -61, -59, -66, -65, -67], into: &weaker)
        #expect(weaker.trend == .weaker)

        // One noisy spike doesn't flip the trend.
        var steady = SignalTracker()
        _ = feed([-70, -70, -70, -70, -64, -70], into: &steady)
        #expect(steady.trend == .steady)
    }

    @Test func oldReadingsFallOutOfTheWindow() {
        var tracker = SignalTracker(window: 10)
        let start = Date(timeIntervalSince1970: 0)
        tracker.add(SignalSample(date: start, rssi: -50))
        tracker.add(SignalSample(date: start.addingTimeInterval(11), rssi: -60))
        #expect(tracker.samples.map(\.rssi) == [-60])
        // The best reading is kept even after its sample ages out.
        #expect(tracker.peak?.rssi == -50)
    }

    @Test func resetClearsEverything() {
        var tracker = SignalTracker()
        _ = feed([-50, -60], into: &tracker)
        tracker.reset()
        #expect(tracker.samples.isEmpty && tracker.peak == nil)
    }
}

@Suite struct WiGLETests {
    // Shape of WiGLE's NetSearchResponse / WiFiNetwork (from api.wigle.net/swagger.json).
    let sample = """
    {"success":true,"totalResults":2,"first":1,"last":2,"resultCount":2,"results":[
      {"trilat":37.3349,"trilong":-122.009,"ssid":"Neighbour","netid":"AA:BB:CC:DD:EE:00","lastupdt":"2025-01-02T03:04:05.000Z",
       "road":"Main St","housenumber":"1","city":"Cupertino","region":"CA","country":"US","channel":36,"qos":2},
      {"trilat":37.3318,"trilong":-122.0312,"ssid":"Home","netid":"AA:BB:CC:DD:EE:0F","lastupdt":"2024-05-12T10:22:33.000Z",
       "road":"Infinite Loop","housenumber":"1","city":"Cupertino","region":"CA","country":"US","channel":6,"qos":3}
    ],"searchAfter":"x"}
    """

    @Test func searchURLAndAuth() {
        let url = WiGLEClient.searchURL(bssid: "aa:bb:cc:dd:ee:f")
        #expect(url.absoluteString == "https://api.wigle.net/api/v2/network/search?netid=aa:bb:cc:dd:ee:0f")
        #expect(WiGLECredentials(name: "AID123", token: "secret").authorizationHeader == "Basic QUlEMTIzOnNlY3JldA==")
    }

    @Test func picksTheExactBSSID() throws {
        let results = try WiGLEClient.decode(Data(sample.utf8), status: 200)
        let match = try #require(WiGLEClient.bestMatch(in: results, bssid: "aa:bb:cc:dd:ee:0f"))
        #expect(match.ssid == "Home")
        #expect(match.trilat == 37.3318)
        #expect(match.address == "1 Infinite Loop, Cupertino, CA, US")
        #expect(match.lastUpdated == "2024-05-12")
    }

    @Test func noExactMatchMeansNotFound() throws {
        let results = try WiGLEClient.decode(Data(sample.utf8), status: 200)
        #expect(WiGLEClient.bestMatch(in: results, bssid: "11:22:33:44:55:66") == nil)
        let empty = try WiGLEClient.decode(Data(#"{"success":true,"totalResults":0,"results":[]}"#.utf8), status: 200)
        #expect(empty.isEmpty)
    }

    @Test func errors() {
        #expect(throws: WiGLEError.unauthorized) { try WiGLEClient.decode(Data(), status: 401) }
        #expect(throws: WiGLEError.rateLimited) { try WiGLEClient.decode(Data(), status: 429) }
        #expect(throws: WiGLEError.rateLimited) {
            try WiGLEClient.decode(Data(#"{"success":false,"message":"too many queries today"}"#.utf8), status: 200)
        }
        #expect(throws: WiGLEError.failed("WiGLE sent a response this app couldn’t read.")) {
            try WiGLEClient.decode(Data("<html>".utf8), status: 200)
        }
    }
}
