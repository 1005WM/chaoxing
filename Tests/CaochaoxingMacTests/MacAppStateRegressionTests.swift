// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

#if os(macOS)
import Foundation
import Testing
import ChaoxingCore
@testable import CaochaoxingMac

private enum OfflineReply {
    case http(Int, String)
    case hold
}

private final class OfflineMacTransport: URLProtocol, @unchecked Sendable {
    private static let lock = NSLock()
    private nonisolated(unsafe) static var handler: @Sendable (URLRequest) -> OfflineReply = { _ in .http(503, "") }
    private nonisolated(unsafe) static var requests: [URLRequest] = []
    private nonisolated(unsafe) static var stoppedPaths: [String] = []

    static func reset(_ handler: @escaping @Sendable (URLRequest) -> OfflineReply) {
        lock.withLock {
            self.handler = handler
            requests = []
            stoppedPaths = []
        }
    }

    static func requests(named name: String) -> [URLRequest] {
        lock.withLock { requests.filter { $0.url?.lastPathComponent == name } }
    }

    static func stopped(_ name: String) -> Bool { lock.withLock { stoppedPaths.contains(name) } }
    static var requestCount: Int { lock.withLock { requests.count } }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        let reply = Self.lock.withLock {
            Self.requests.append(request)
            if request.url?.lastPathComponent == "backclazzdata" {
                return OfflineReply.http(200, #"{"result":1,"channelList":[]}"#)
            }
            return Self.handler(request)
        }
        guard case .http(let status, let body) = reply else { return }
        let response = HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: nil, headerFields: nil)!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(body.utf8))
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {
        Self.lock.withLock { Self.stoppedPaths.append(request.url?.lastPathComponent ?? "") }
    }
}

private func query(_ name: String, in request: URLRequest) -> String? {
    guard let url = request.url else { return nil }
    return URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems?.first { $0.name == name }?.value
}

private func sampleActivity(type: String = "0", status: Int = 1, endTime: Int64? = nil) -> ChaoxingSignActivity {
    ChaoxingSignActivity(id: 91, startTimeMs: nil, endTimeMs: endTime, userStatus: 0, otherId: type,
                         isLook: false, type: 2, activeType: 2, nameOne: "合成活动", status: status,
                         nameFour: "", courseId: 10, classId: 20, courseName: "合成课程", extContent: "")
}
private let activity = sampleActivity()

@MainActor
private final class MacFixture {
    let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    let state: MacAppState
    let store: ChaoxingPersistenceStore
    let file: URL
    let accounts: [ChaoxingAccountSession]

    init(phones: [String] = ["A", "B"],
         waitBetweenAccounts: @escaping @Sendable (Duration) async throws -> Void = { _ in },
         reply: @escaping @Sendable (URLRequest) -> OfflineReply = { _ in .http(503, "") }) async throws {
        OfflineMacTransport.reset(reply)
        file = directory.appendingPathComponent("state.json")
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        store = ChaoxingPersistenceStore(customFileURL: file)
        accounts = phones.enumerated().map { index, phone in
            ChaoxingAccountSession(phoneNumber: phone, encryptedPassword: "fixture", puid: index + 1,
                                   deviceCode: "fixture", isPrimary: index == 0)
        }
        try await store.save(ChaoxingAppStateSnapshot(accounts: accounts))
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [OfflineMacTransport.self]
        state = MacAppState(store: store, clientConfiguration: configuration, waitBetweenSignAccounts: waitBetweenAccounts)
        await state.restore()
    }

    deinit { try? FileManager.default.removeItem(at: directory) }

    func prepared(_ status: ChaoxingSignActivityStatus?) -> MacPreparedSignWorkspace {
        MacPreparedSignWorkspace(activityID: activity.id, sessionID: state.signSessionID, status: status, detail: nil)
    }

    func run(owner: UUID = UUID(), prepared: MacPreparedSignWorkspace?, phones: Set<String>,
             onCompletion: @escaping ([MacSignResult]) -> Void = { _ in }) -> Task<Void, Never>? {
        state.executeSign(owner: owner, activity: activity, prepared: prepared, location: nil,
                          code: "", qrCodeRaw: "", targetPhones: phones, onCompletion: onCompletion)
    }
}

private actor SuspendedDelay {
    private var continuation: CheckedContinuation<Void, Never>?
    var entered: Bool { continuation != nil }

    func sleep() async {
        await withCheckedContinuation { continuation = $0 }
    }

    func release() {
        continuation?.resume()
        continuation = nil
    }
}

@MainActor
private func eventually(_ predicate: () async -> Bool) async -> Bool {
    let deadline = ContinuousClock.now + .seconds(3)
    while ContinuousClock.now < deadline {
        if await predicate() { return true }
        await Task.yield()
    }
    return false
}

@Suite("macOS workspace regressions", .serialized)
@MainActor
struct MacAppStateRegressionTests {
    @Test func emptyUnknownExpiredAndObsoleteSelectionsNeverStartABatch() async throws {
        let f = try await MacFixture()
        let unavailable = await f.state.prepareSignWorkspace(for: activity)
        #expect(unavailable.status == nil)
        let count = OfflineMacTransport.requestCount
        #expect(f.run(prepared: nil, phones: ["A"]) == nil)
        #expect(f.run(prepared: unavailable, phones: ["A"]) == nil)
        #expect(f.run(prepared: f.prepared(.readyToSign), phones: []) == nil)
        #expect(f.run(prepared: f.prepared(.readyToSign), phones: ["A", "missing"]) == nil)
        #expect(f.run(prepared: f.prepared(.expired), phones: ["A"]) == nil)
        #expect(f.run(prepared: f.prepared(.alreadySigned), phones: ["A"]) == nil)
        for blocked in [sampleActivity(type: "-1"), sampleActivity(status: 0), sampleActivity(endTime: 1)] {
            #expect(!f.state.canExecuteSign(activity: blocked, prepared: f.prepared(.readyToSign), targetPhones: ["A"]))
        }
        let stale = MacPreparedSignWorkspace(activityID: 90, sessionID: f.state.signSessionID, status: .readyToSign, detail: nil)
        #expect(f.run(prepared: stale, phones: ["A"]) == nil)
        #expect(OfflineMacTransport.requestCount == count)
        let oldSession = f.prepared(.readyToSign)
        await f.state.switchAccount(f.accounts[1])
        #expect(f.run(prepared: oldSession, phones: ["B"]) == nil)
        #expect(f.state.signHistory.isEmpty)
    }

    @Test func fullBatchCountsAlreadySignedFromPrecheckAndServerSeparately() async throws {
        let f = try await MacFixture(phones: ["A", "B", "C", "D"], reply: { request in
            let user = query("uid", in: request)
            switch request.url?.lastPathComponent {
            case "preSign": return .http(200, user == "1" ? "signstatus=1" : "signstatus=0")
            case "analysis": return .http(200, "")
            case "stuSignajax":
                if user == "2" { return .http(200, "您已签到过了") }
                return user == "3" ? .http(200, "success") : .http(503, "")
            default: return .http(503, "")
            }
        })
        var results: [MacSignResult] = []
        let task = try #require(f.run(prepared: f.prepared(.readyToSign), phones: ["A", "B", "C", "D"]) { results = $0 })
        await task.value
        #expect(results.map(\.disposition) == [.alreadySigned, .alreadySigned, .accepted, .failed])
        #expect(OfflineMacTransport.requests(named: "stuSignajax").compactMap { query("uid", in: $0) } == ["2", "3", "4"])
        let entry = try #require(f.state.signHistory.first)
        #expect(entry.successCount == 1 && entry.alreadySignedCount == 2 && entry.totalCount == 4)
        let saved = try await f.store.load().signHistory
        #expect(saved.map(\.id) == [entry.id])
        #expect(saved.first?.displayCounts == entry.displayCounts && saved.first?.summary == entry.summary)
    }

    enum StopAction: CaseIterable, Sendable { case close, switchAccount, removeBackup, logout, shutdown }

    @Test(arguments: StopAction.allCases)
    func stoppingAnInflightRequestCancelsItsTransportAndSuppressesCompletion(_ action: StopAction) async throws {
        let f = try await MacFixture(reply: { request in
            request.url?.lastPathComponent == "preSign" ? .hold : .http(503, "")
        })
        let owner = UUID()
        var completed = false
        let task = try #require(f.run(owner: owner, prepared: f.prepared(.readyToSign), phones: ["A", "B"]) { _ in completed = true })
        try #require(await eventually { OfflineMacTransport.requests(named: "preSign").count == 1 })
        f.state.cancelSignBatch(owner: UUID())
        #expect(f.state.isSubmitting(owner: owner))
        switch action {
        case .close: f.state.cancelSignBatch(owner: owner)
        case .switchAccount: await f.state.switchAccount(f.accounts[1])
        case .removeBackup: await f.state.removeAccount(f.accounts[1])
        case .logout: await f.state.logoutCurrent()
        case .shutdown: f.state.shutdown()
        }
        await task.value
        #expect(await eventually { OfflineMacTransport.stopped("preSign") })
        #expect(!f.state.isSubmitting(owner: owner) && !completed)
        #expect(OfflineMacTransport.requests(named: "preSign").count == 1)
        #expect(OfflineMacTransport.requests(named: "stuSignajax").isEmpty)
        #expect(f.state.signHistory.isEmpty)
        #expect(try await f.store.load().signHistory.isEmpty)
    }

    @Test func cancelledDelayCannotStartAnotherAccountOrWriteHistory() async throws {
        let delay = SuspendedDelay()
        let f = try await MacFixture(waitBetweenAccounts: { _ in await delay.sleep() }, reply: { _ in .http(200, "signstatus=1") })
        var completed = false
        let task = try #require(f.run(prepared: f.prepared(.readyToSign), phones: ["A", "B"]) { _ in completed = true })
        try #require(await eventually { await delay.entered })
        await f.state.switchAccount(f.accounts[1])
        await delay.release()
        await task.value
        #expect(OfflineMacTransport.requests(named: "preSign").count == 1)
        #expect(f.state.signHistory.isEmpty && !completed)
        #expect(try await f.store.load().signHistory.isEmpty)
    }

    @Test func sessionChangeResumesManualWaitAndRejectsAnOldDialogResponse() async throws {
        let f = try await MacFixture()
        func challenge() -> MacCaptchaChallenge {
            let configuration = URLSessionConfiguration.ephemeral
            configuration.protocolClasses = [OfflineMacTransport.self]
            return MacCaptchaChallenge(
                client: ChaoxingHTTPClient(session: f.accounts[0], configuration: configuration), refererURL: "",
                accountName: "fixture", captcha: ChaoxingCaptchaData(captchaId: "", type: "", version: "", token: "",
                                                                    captchaKey: "", iv: "", shadeImageURL: "", cutoutImageURL: ""),
                shadeImageData: Data(), cutoutImageData: Data()
            )
        }
        let first = challenge()
        let oldWait = Task { try await f.state.waitForManualCaptcha(first) }
        try #require(await eventually { f.state.activeCaptchaChallenge?.id == first.id })
        await f.state.switchAccount(f.accounts[1])
        #expect(await (try? oldWait.value) == nil)
        let second = challenge()
        let newWait = Task { try await f.state.waitForManualCaptcha(second) }
        try #require(await eventually { f.state.activeCaptchaChallenge?.id == second.id })
        f.state.completeCaptchaChallenge(id: first.id, validate: "obsolete")
        #expect(f.state.activeCaptchaChallenge?.id == second.id)
        newWait.cancel()
        #expect(await (try? newWait.value) == nil)
        #expect(f.state.activeCaptchaChallenge == nil)
    }

    @Test func radarDistinguishesPartialFailureCompleteFailureAndAnEmptySuccess() async throws {
        let f = try await MacFixture(reply: { request in
            query("courseId", in: request) == "1"
                ? .http(200, #"{"data":{"activeList":[{"id":91,"type":2,"status":1,"userStatus":0}]}}"#)
                : .http(503, "")
        })
        f.state.courses = [1, 2].map { ChaoxingCourse(courseName: "课程\($0)", teacherName: "", courseId: $0, classId: 1, imageURL: "") }
        await f.state.scanPendingActivities()?.value
        #expect(f.state.pendingRadarActivities.map(\.id) == [91])
        #expect(f.state.radarFailedCourses == 1)
        #expect(f.state.radarScanMessage?.contains("另有 1 门查询失败") == true)
        f.state.starredCourseIds = [2]
        await f.state.scanPendingActivities()?.value
        #expect(f.state.pendingRadarActivities.isEmpty)
        #expect(f.state.radarScanMessage?.contains("均查询失败") == true)
        OfflineMacTransport.reset { _ in .http(200, #"{"data":{"activeList":[]}}"#) }
        await f.state.scanPendingActivities()?.value
        #expect(f.state.radarFailedCourses == 0)
        #expect(f.state.radarScanMessage?.contains("发现 0 个待签活动") == true)
    }

    @Test func switchingDuringRadarScanReleasesTheNextAccountsScanButton() async throws {
        let f = try await MacFixture(reply: { _ in .hold })
        let course = ChaoxingCourse(courseName: "合成", teacherName: "", courseId: 1, classId: 1, imageURL: "")
        f.state.courses = [course]
        let old = try #require(f.state.scanPendingActivities())
        try #require(await eventually { OfflineMacTransport.requests(named: "activelist").count == 1 })
        #expect(f.state.isScanningRadar)
        #expect(f.state.scanPendingActivities() == nil)
        await f.state.switchAccount(f.accounts[1])
        #expect(!f.state.isScanningRadar && f.state.radarScanMessage == nil)
        OfflineMacTransport.reset { _ in .http(200, #"{"data":{"activeList":[]}}"#) }
        f.state.courses = [course]
        await f.state.scanPendingActivities()?.value
        await old.value
        #expect(!f.state.isScanningRadar && f.state.radarFailedCourses == 0)
        #expect(f.state.radarScanMessage?.contains("发现 0 个待签活动") == true)
    }

    @Test func failedRemovalPreservesActiveAndBackupAccountsAndCanBeRetried() async throws {
        let f = try await MacFixture()
        let valid = try Data(contentsOf: f.file)
        // The valid snapshot can still be read, but an atomic rename cannot replace an immutable file.
        try FileManager.default.setAttributes([.immutable: true], ofItemAtPath: f.file.path)
        defer { try? FileManager.default.setAttributes([.immutable: false], ofItemAtPath: f.file.path) }
        for account in f.accounts.reversed() {
            await f.state.removeAccount(account)
            #expect(f.state.accounts.map(\.phoneNumber) == ["A", "B"] && f.state.activePhoneNumber == "A")
            #expect(f.state.logs.first?.isError == true && f.state.accountChangeError != nil)
            #expect(try Data(contentsOf: f.file) == valid)
        }
        try FileManager.default.setAttributes([.immutable: false], ofItemAtPath: f.file.path)
        await f.state.removeAccount(f.accounts[1])
        #expect(try await f.store.load().accounts.map(\.phoneNumber) == ["A"])
        await f.state.logoutCurrent()
        #expect(f.state.activeAccount == nil && f.state.accountChangeError == nil)
        #expect(try await f.store.load().accounts.isEmpty)
    }

    @Test func switchAndRemovalLogsMaskPhoneNumbersEvenWhenUsedAsDisplayNames() async throws {
        let f = try await MacFixture(phones: ["13800000001", "13900000002"])
        await f.state.switchAccount(f.accounts[1])
        await f.state.logoutCurrent()
        f.state.appendLog("fixture +86 138-0000-0001")
        let log = f.state.logs.map(\.message).joined(separator: "\n")
        #expect(!log.contains("13800000001") && !log.contains("13900000002") && !log.contains("138-0000-0001"))
        #expect(log.contains("139****0002") && log.contains("138****0001"))
    }
}
#endif
