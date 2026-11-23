import Foundation

public struct AppUpdateVersion: Comparable, Sendable {
    private let numbers: [UInt64]
    public let prerelease: [String]

    public init(_ raw: String) throws {
        var value = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if value.hasPrefix("v") || value.hasPrefix("V") { value.removeFirst() }
        guard value.range(of: #"^[0-9]+(?:\.[0-9]+){0,2}(?:-[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?$"#, options: .regularExpression) != nil else {
            throw AppUpdateError.message("无法识别版本号")
        }
        let main = value.split(separator: "+", maxSplits: 1)[0].split(separator: "-", maxSplits: 1)
        let parts = main[0].split(separator: ".")
        let parsed = parts.compactMap { UInt64($0) }
        guard parsed.count == parts.count, parts.allSatisfy({ $0.count == 1 || !$0.hasPrefix("0") }) else {
            throw AppUpdateError.message("版本号格式不正确")
        }
        numbers = parsed + Array(repeating: 0, count: 3 - parsed.count)
        prerelease = main.count > 1 ? main[1].split(separator: ".").map(String.init) : []
        guard prerelease.allSatisfy({ !$0.allSatisfy(\.isNumber) || $0.count == 1 || !$0.hasPrefix("0") }) else {
            throw AppUpdateError.message("预发布版本号格式不正确")
        }
    }

    public static func < (lhs: Self, rhs: Self) -> Bool {
        for (a, b) in zip(lhs.numbers, rhs.numbers) where a != b { return a < b }
        if lhs.prerelease.isEmpty || rhs.prerelease.isEmpty { return !lhs.prerelease.isEmpty && rhs.prerelease.isEmpty }
        for (a, b) in zip(lhs.prerelease, rhs.prerelease) where a != b {
            let numericA = a.allSatisfy(\.isNumber)
            let numericB = b.allSatisfy(\.isNumber)
            if numericA != numericB { return numericA }
            if numericA && a.count != b.count { return a.count < b.count }
            return a < b
        }
        return lhs.prerelease.count < rhs.prerelease.count
    }
}

public enum AppUpdateError: LocalizedError {
    case message(String)
    public var errorDescription: String? { switch self { case .message(let text): return text } }
}

public enum AppUpdateSource {
    public static let repository = "1005WM/chaoxing"
    public static let mirrorLatestURL = ""
    public static let includePrerelease = false
    public static let currentVersion = "1.6.6"
    public static let iosStoreURL = ""
}

public struct AppUpdateAsset: Sendable {
    public let url: URL
    public let size: Int64
}

public struct AppUpdateRelease: Sendable {
    public let version: String
    public let notes: String
    public let publishedAt: String
    public let asset: AppUpdateAsset?
}

public actor AppUpdateService {
    private var cached: AppUpdateRelease?
    private var checkedAt: Date?

    public init() {}

    public func latest() async throws -> AppUpdateRelease {
        if let cached, let checkedAt, Date().timeIntervalSince(checkedAt) < 300 { return cached }
        if !AppUpdateSource.mirrorLatestURL.isEmpty {
            do {
                let object = try await document(AppUpdateSource.mirrorLatestURL, timeout: 2)
                let release = try parse(object, mirror: true)
                if release.asset != nil { cached = release; checkedAt = Date(); return release }
            } catch is CancellationError { throw CancellationError() }
            catch { /* Continue with the public release feed. */ }
        }
        let suffix = AppUpdateSource.includePrerelease ? "" : "/latest"
        let object: [String: Any]
        do {
            object = try await document("https://api.github.com/repos/\(AppUpdateSource.repository)/releases\(suffix)", timeout: 20)
        } catch is CancellationError { throw CancellationError() }
        catch {
            let metadata = try await document("https://github.com/\(AppUpdateSource.repository)/releases/latest/download/latest.json", timeout: 20)
            let release = try parse(metadata, mirror: true)
            cached = release
            checkedAt = Date()
            return release
        }
        let release = try parse(object, mirror: false)
        cached = release
        checkedAt = Date()
        return release
    }

    private func document(_ address: String, timeout: TimeInterval) async throws -> [String: Any] {
        guard let url = URL(string: address), url.scheme == "https", url.user == nil, url.password == nil else {
            throw AppUpdateError.message("更新地址必须使用 HTTPS")
        }
        let configuration = URLSessionConfiguration.ephemeral
        configuration.httpShouldSetCookies = false
        configuration.timeoutIntervalForRequest = timeout
        configuration.timeoutIntervalForResource = timeout
        let session = URLSession(configuration: configuration, delegate: UpdateHTTPSRedirectDelegate(), delegateQueue: nil)
        defer { session.invalidateAndCancel() }
        var request = URLRequest(url: url)
        request.setValue("Caochaoxing-update", forHTTPHeaderField: "User-Agent")
        request.setValue("application/vnd.github+json", forHTTPHeaderField: "Accept")
        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw AppUpdateError.message("更新源响应无效") }
        if http.statusCode == 403 || http.statusCode == 429 { throw AppUpdateError.message("更新源请求受限，请稍后重试") }
        if http.statusCode == 404 { throw AppUpdateError.message("仓库尚无正式发布版本") }
        guard (200..<300).contains(http.statusCode), data.count <= 1_000_000 else { throw AppUpdateError.message("读取更新信息失败") }
        let parsed = try JSONSerialization.jsonObject(with: data)
        if let rows = parsed as? [[String: Any]], let first = rows.first(where: { ($0["draft"] as? Bool) != true }) { return first }
        guard let object = parsed as? [String: Any] else { throw AppUpdateError.message("更新信息格式不正确") }
        return object
    }

    private func parse(_ object: [String: Any], mirror: Bool) throws -> AppUpdateRelease {
        guard let version = object[mirror ? "version" : "tag_name"] as? String else { throw AppUpdateError.message("更新信息缺少版本号") }
        let parsed = try AppUpdateVersion(version)
        guard AppUpdateSource.includePrerelease || ((object["prerelease"] as? Bool) != true && parsed.prerelease.isEmpty) else {
            throw AppUpdateError.message("更新源返回了预发布版本")
        }
        let assets = object["assets"] as? [[String: Any]] ?? []
        #if os(iOS)
        let platform = "ios"
        let extensions: [String] = []
        #else
        let platform = "macos"
        let extensions = [".dmg", ".pkg", ".app.zip", "macos.zip", "mac.zip"]
        #endif
        #if arch(arm64)
        let arch = "arm64"
        #else
        let arch = "x86_64"
        #endif
        let row = assets.first { row in
            if mirror {
                let assetArch = row["arch"] as? String ?? "universal"
                return row["platform"] as? String == platform && [arch, "universal", "all"].contains(assetArch)
            }
            let name = (row["name"] as? String ?? "").lowercased()
            let matchesArch = !name.contains("arm64") && !name.contains("x86_64") || name.contains(arch)
            return extensions.contains(where: { name.hasSuffix($0) }) && matchesArch
        }
        var asset: AppUpdateAsset?
        if let row, let address = row[mirror ? "url" : "browser_download_url"] as? String,
           let url = URL(string: address), url.scheme == "https" {
            #if os(iOS)
            if ["apps.apple.com", "testflight.apple.com"].contains(url.host ?? "") { asset = AppUpdateAsset(url: url, size: 0) }
            #else
            asset = AppUpdateAsset(url: url, size: (row["size"] as? NSNumber)?.int64Value ?? 0)
            #endif
        }
        #if os(iOS)
        if let url = URL(string: AppUpdateSource.iosStoreURL), url.scheme == "https",
           ["apps.apple.com", "testflight.apple.com"].contains(url.host ?? "") { asset = AppUpdateAsset(url: url, size: 0) }
        #endif
        return AppUpdateRelease(version: version, notes: object[mirror ? "notes" : "body"] as? String ?? "",
            publishedAt: object[mirror ? "publishedAt" : "published_at"] as? String ?? "", asset: asset)
    }
}

private final class UpdateHTTPSRedirectDelegate: NSObject, URLSessionTaskDelegate, Sendable {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        completionHandler(request.url?.scheme == "https" ? request : nil)
    }
}
