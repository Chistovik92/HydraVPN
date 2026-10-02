import XCTest
@testable import HydraKit

final class UpdatesTests: XCTestCase {
    private let sha = String(repeating: "c", count: 64)

    func testParsesReleaseAndPicksIpa() throws {
        let json = """
        {"tag_name":"v0.7.1","html_url":"https://github.com/Chistovik92/HydraVPN/releases/tag/v0.7.1","assets":[
          {"name":"Hydra-ios-0.7.1-unsigned.ipa","size":1234,"digest":"sha256:\(sha)","browser_download_url":"https://github.com/x/Hydra-ios-0.7.1-unsigned.ipa"},
          {"name":"Hydra-full-0.7.1.apk","size":1,"browser_download_url":"https://github.com/x/a.apk"},
          {"name":"evil.ipa","size":1,"browser_download_url":"https://evil.example/evil.ipa"}]}
        """
        let info = try UpdateFeed.parse(Data(json.utf8))
        XCTAssertEqual(info.version, "0.7.1")
        XCTAssertEqual(info.assets.count, 2)   // чужой хост отброшен
        let ipa = try XCTUnwrap(UpdateFeed.pickIOS(info))
        XCTAssertEqual(ipa.name, "Hydra-ios-0.7.1-unsigned.ipa")
        XCTAssertEqual(ipa.sha256, sha)
        XCTAssertEqual(ipa.size, 1234)
    }

    func testVersionsAndTrust() {
        XCTAssertTrue(UpdateFeed.isNewer("0.7.1", than: "0.7.0"))
        XCTAssertTrue(UpdateFeed.isNewer("0.7.0.1", than: "0.7.0"))
        XCTAssertFalse(UpdateFeed.isNewer("0.7.0", than: "0.7.0-stub"))
        XCTAssertFalse(UpdateFeed.isNewer("0.6.27", than: "0.7.0"))
        XCTAssertTrue(UpdateFeed.trusted(URL(string: "https://release-assets.githubusercontent.com/x")!))
        XCTAssertFalse(UpdateFeed.trusted(URL(string: "http://github.com/x")!))
        XCTAssertFalse(UpdateFeed.trusted(URL(string: "https://github.com.evil.example/x")!))
    }
}
