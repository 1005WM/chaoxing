// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

import Foundation
import Testing
@testable import ChaoxingCore

private struct MockReply: Sendable {
    var status = 200
    var headers: [String: String] = [:]
    var body = "{}"
    var error: URLError?
    var redirect: URL?
    var responseURL: URL?
}
private final class NetworkRequests: @unchecked Sendable {
    private let lock = NSLock()
    private var requests: [URLRequest] = []
    func record(_ request: URLRequest) { lock.withLock { requests.append(request) } }
    var all: [URLRequest] { lock.withLock { requests } }
}
private final class NetworkMockProtocol: URLProtocol, @unchecked Sendable {
    static let lock = NSLock()
    nonisolated(unsafe) static var handler: (@Sendable (URLRequest) -> MockReply)?
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        guard let handler = Self.lock.withLock({ Self.handler }) else {
            client?.urlProtocol(self, didFailWithError: URLError(.unsupportedURL)); return
        }
        DispatchQueue.global().async { [self] in
            let reply = handler(request)
            if let error = reply.error { client?.urlProtocol(self, didFailWithError: error); return }
            let response = HTTPURLResponse(url: reply.responseURL ?? request.url!, statusCode: reply.status, httpVersion: nil, headerFields: reply.headers)!
            if let redirect = reply.redirect {
                var next = request
                next.url = redirect
                client?.urlProtocol(self, wasRedirectedTo: next, redirectResponse: response)
                client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
                client?.urlProtocolDidFinishLoading(self)
                return
            }
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: Data(reply.body.utf8))
            client?.urlProtocolDidFinishLoading(self)
        }
    }

    override func stopLoading() {}
}

@Suite("Network regressions", .serialized)
struct NetworkRegressionTests {
    private func account(cookies: [ChaoxingStoredCookie] = []) -> ChaoxingAccountSession {
        ChaoxingAccountSession(phoneNumber: "synthetic", encryptedPassword: "FAKE", deviceCode: "FAKE", cookies: cookies)
    }
    private func client(cookies: [ChaoxingStoredCookie] = [], handler: @escaping @Sendable (URLRequest) -> MockReply) -> ChaoxingHTTPClient {
        NetworkMockProtocol.lock.withLock { NetworkMockProtocol.handler = handler }
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [NetworkMockProtocol.self]
        return ChaoxingHTTPClient(session: account(cookies: cookies), configuration: config)
    }

    @Test func courseRefreshAuthenticatesAtMostOnce() async throws {
        let log = NetworkRequests()
        let c = client { request in
            log.record(request)
            switch request.url!.path {
            case "/fanyalogin": return MockReply(body: "{\"status\":true}")
            case "/apis/login/userLogin4Uname.do": return MockReply(body: "{\"msg\":{\"uid\":1,\"puid\":1,\"fid\":1}}")
            default:
                let count = log.all.filter { $0.url?.path == "/mycourse/backclazzdata" }.count
                return count == 1 ? MockReply(status: 401) : MockReply(body: "{\"result\":0}")
            }
        }
        do { _ = try await ChaoxingCourseService.fetchCourses(client: c); Issue.record("Expired retry must fail") } catch {}
        #expect(log.all.filter { $0.url?.path == "/fanyalogin" }.count == 1)
        #expect(log.all.filter { $0.url?.path == "/mycourse/backclazzdata" }.count == 2)
    }

    @Test func multipartFilenameCannotInjectHeaders() async throws {
        let requests = NetworkRequests()
        let c = client { request in
            requests.record(request)
            return MockReply(body: "{\"_token\":\"synthetic\",\"objectId\":\"synthetic\"}")
        }
        for filename in ["bad\r\nX-Injected: yes.jpg", "bad\"name.jpg", "bad\\name.jpg"] {
            do {
                _ = try await ChaoxingCloudDriveService.uploadImage(client: c, imageData: Data([1]), filename: filename)
                Issue.record("Unsafe multipart filename must fail before any request")
            } catch {}
        }
        #expect(requests.all.isEmpty)
    }

    @Test func changingSchoolInvalidatesCloudToken() {
        let c = ChaoxingHTTPClient(session: account())
        c.storageCloudToken = "synthetic-old-school"
        c.updateConfiguredFid(c.session.configuredFid + 1)
        #expect(c.storageCloudToken == nil)
    }

    @Test func emptySessionClearsPreviousCookies() {
        let client = ChaoxingHTTPClient(session: account(cookies: [.init(name: "sid", value: "old", domain: "chaoxing.com")]))
        client.updateSession(account())
        #expect(client.exportCookies().isEmpty)
    }

    @Test func cookiesRespectDomainPathSecurityAndExpiry() async throws {
        let log = NetworkRequests()
        let c = client(cookies: [
            .init(name: "sid", value: "secret", domain: "mobilelearn.chaoxing.com", path: "/sign", isSecure: true, hostOnly: true),
            .init(name: "expired", value: "bad", domain: "chaoxing.com", expiresAt: .distantPast)
        ]) { request in log.record(request); return MockReply() }
        for raw in ["https://evilchaoxing.com/sign", "https://mobilelearn.chaoxing.com/signature", "https://mobilelearn.chaoxing.com/sign%2Fhidden", "https://sub.mobilelearn.chaoxing.com/sign", "http://mobilelearn.chaoxing.com/sign", "https://mobilelearn.chaoxing.com/sign/submit"] {
            _ = try await c.get(URL(string: raw)!)
        }
        #expect(log.all.dropLast().allSatisfy { $0.value(forHTTPHeaderField: "Cookie") == nil })
        #expect(log.all.last?.value(forHTTPHeaderField: "Cookie") == "sid=secret")
    }

    @Test func duplicateCookieNamesHaveIndependentScopes() {
        let c = ChaoxingHTTPClient(session: account(cookies: [
            .init(name: "sid", value: "one", domain: "one.chaoxing.com"),
            .init(name: "sid", value: "two", domain: "two.chaoxing.com")
        ]))
        c.mergeCookies([.init(name: "sid", value: "updated", domain: "one.chaoxing.com")])
        #expect(c.exportCookies().count == 2)
        #expect(c.exportCookies().first { $0.domain == "two.chaoxing.com" }?.value == "two")
    }

    @Test func serverDeletionRemovesCookie() async throws {
        let c = client(cookies: [.init(name: "sid", value: "old", domain: ".chaoxing.com")]) { _ in
            MockReply(headers: ["Set-Cookie": "sid=deleted; Domain=chaoxing.com; Max-Age=0; Path=/"])
        }
        _ = try await c.get(URL(string: "https://mobilelearn.chaoxing.com/test")!)
        #expect(c.exportCookies().isEmpty)
    }

    @Test func reloginRestoresSelectedSchoolCookie() async throws {
        let c = client { request in
            if request.url!.path == "/fanyalogin" {
                return MockReply(headers: ["Set-Cookie": "fid=1; Domain=chaoxing.com; Path=/"], body: "{\"status\":true}")
            }
            return MockReply(body: "{\"msg\":{\"uid\":1,\"puid\":1,\"fid\":1,\"unitConfigInfos\":[{\"fid\":2}]}}")
        }
        c.updateConfiguredFid(2)
        let updated = try await ChaoxingAuthService.reLogin(client: c)
        #expect(updated.configuredFid == 2)
        #expect(updated.cookies.first { $0.name == "fid" }?.value == "2")
    }

    @Test func uploadDoesNotRepeatAfterAmbiguousTransportFailure() async {
        let log = NetworkRequests()
        let c = client { request in
            log.record(request)
            if request.url!.path == "/api/token/uservalid" { return MockReply(body: "{\"_token\":\"synthetic\"}") }
            return MockReply(error: URLError(.timedOut))
        }
        do { _ = try await ChaoxingCloudDriveService.uploadImage(client: c, imageData: Data([0])); Issue.record("Expected upload failure") } catch {}
        #expect(log.all.filter { $0.url!.path == "/upload" }.count == 1)
    }

    @Test func redirectPolicyBlocksCredentialBoundaryCrossing() {
        let source = URL(string: "https://passport2.chaoxing.com/fanyalogin")!
        #expect(!ChaoxingCookiePolicy.allowsRedirect(from: source, to: URL(string: "https://evilchaoxing.com/capture")!, method: "GET"))
        #expect(!ChaoxingCookiePolicy.allowsRedirect(from: source, to: URL(string: "http://passport2.chaoxing.com/capture")!, method: "GET"))
        #expect(!ChaoxingCookiePolicy.allowsRedirect(from: source, to: URL(string: "https://i.chaoxing.com/capture")!, method: "POST"))
        #expect(ChaoxingCookiePolicy.allowsRedirect(from: source, to: URL(string: "https://i.chaoxing.com/home")!, method: "GET"))
    }

    @Test func invalidProfileCannotBecomeAuthenticatedSession() async {
        let c = client { _ in MockReply(body: "{\"msg\":{}}") }
        do { _ = try await ChaoxingAuthService.fetchUserProfile(client: c); Issue.record("Missing uid/puid must fail") } catch {}
    }

    @Test func uploadCancellationDoesNotRetry() async {
        let log = NetworkRequests()
        let c = client { request in
            log.record(request)
            if request.url!.path == "/api/token/uservalid" { return MockReply(body: "{\"_token\":\"synthetic\"}") }
            return MockReply(error: URLError(.cancelled))
        }
        do { _ = try await ChaoxingCloudDriveService.uploadImage(client: c, imageData: Data([0])); Issue.record("Expected cancellation") } catch {}
        #expect(log.all.filter { $0.url!.path == "/upload" }.count == 1)
    }

    @Test func uploadRetriesOnlyExplicitAuthorizationRejection() async throws {
        let log = NetworkRequests()
        let c = client { request in
            log.record(request)
            if request.url!.path == "/api/token/uservalid" { return MockReply(body: "{\"_token\":\"synthetic+a/b=\"}") }
            if log.all.filter({ $0.url!.path == "/upload" }).count == 1 { return MockReply(status: 401) }
            return MockReply(body: "{\"objectId\":\"synthetic-object\"}")
        }
        let object = try await ChaoxingCloudDriveService.uploadImage(client: c, imageData: Data([0]))
        #expect(object == "synthetic-object")
        #expect(log.all.filter { $0.url!.path == "/upload" }.count == 2)
        #expect(log.all.last?.url?.absoluteString.contains("synthetic%2Ba%2Fb%3D") == true)
    }

    @Test func foreignFinalResponseCannotOverwriteChaoxingCookies() async throws {
        let c = client(cookies: [.init(name: "sid", value: "original", domain: "chaoxing.com")]) { _ in
            MockReply(headers: ["Set-Cookie": "sid=foreign; Path=/"], responseURL: URL(string: "https://foreign.invalid/final")!)
        }
        _ = try await c.get(URL(string: "https://mobilelearn.chaoxing.com/test")!)
        #expect(c.exportCookies().first?.value == "original")
    }

    @Test func nativeRedirectDelegateRejectsUntrustedDestination() async throws {
        let log = NetworkRequests()
        let c = client(cookies: [.init(name: "sid", value: "original", domain: "chaoxing.com")]) { request in
            log.record(request)
            return MockReply(status: 302, redirect: URL(string: "https://evilchaoxing.com/capture")!)
        }
        let (_, response) = try await c.get(URL(string: "https://mobilelearn.chaoxing.com/start")!)
        #expect(response.statusCode == 302)
        #expect(log.all.count == 1)
    }

    @Test func trustedRedirectRebuildsCookiesAndDropsCrossOriginAuthorization() async throws {
        let log = NetworkRequests()
        let c = client(cookies: [
            .init(name: "host", value: "private", domain: "mobilelearn.chaoxing.com", hostOnly: true),
            .init(name: "shared", value: "allowed", domain: "chaoxing.com")
        ]) { request in
            log.record(request)
            if request.url!.path == "/start" {
                return MockReply(status: 302, headers: ["Set-Cookie": "redirect=intermediate; Path=/"], redirect: URL(string: "https://sso.chaoxing.com/final")!)
            }
            return MockReply()
        }
        _ = try await c.get(URL(string: "https://mobilelearn.chaoxing.com/start")!, headers: ["Authorization": "synthetic"])
        #expect(log.all.count == 2)
        #expect(log.all.last?.value(forHTTPHeaderField: "Cookie") == "shared=allowed")
        #expect(log.all.last?.value(forHTTPHeaderField: "Authorization") == nil)
        #expect(c.exportCookies().contains { $0.name == "redirect" && $0.domain == "mobilelearn.chaoxing.com" && $0.hostOnly })
    }

    @Test func staleResponseCannotRestoreClearedSession() async throws {
        let entered = DispatchSemaphore(value: 0)
        let released = DispatchSemaphore(value: 0)
        let c = client { _ in
            entered.signal()
            _ = released.wait(timeout: .now() + 3)
            return MockReply(headers: ["Set-Cookie": "sid=old-session; Domain=chaoxing.com; Path=/"])
        }
        let task = Task { try await c.get(URL(string: "https://mobilelearn.chaoxing.com/test")!) }
        let reached = await withCheckedContinuation { continuation in
            DispatchQueue.global().async { continuation.resume(returning: entered.wait(timeout: .now() + 3) == .success) }
        }
        #expect(reached)
        c.clearCookies()
        released.signal()
        do { _ = try await task.value; Issue.record("Stale session response must fail") } catch {}
        #expect(c.exportCookies().isEmpty)
    }

    @Test func responseStartedBeforeReloginCannotOverwriteFreshCookies() async throws {
        let entered = DispatchSemaphore(value: 0)
        let released = DispatchSemaphore(value: 0)
        let c = client { request in
            if request.url!.path == "/old" {
                entered.signal()
                _ = released.wait(timeout: .now() + 3)
                return MockReply(headers: ["Set-Cookie": "sid=old; Domain=chaoxing.com; Path=/"])
            }
            if request.url!.path == "/fanyalogin" {
                return MockReply(headers: ["Set-Cookie": "sid=fresh; Domain=chaoxing.com; Path=/"], body: "{\"status\":true}")
            }
            return MockReply(body: "{\"msg\":{\"uid\":1,\"puid\":1,\"fid\":1}}")
        }
        let task = Task { try await c.get(URL(string: "https://mobilelearn.chaoxing.com/old")!) }
        let reached = await withCheckedContinuation { continuation in
            DispatchQueue.global().async { continuation.resume(returning: entered.wait(timeout: .now() + 3) == .success) }
        }
        #expect(reached)
        _ = try await ChaoxingAuthService.reLogin(client: c)
        released.signal()
        do { _ = try await task.value; Issue.record("Pre-login response must not update refreshed session") } catch {}
        #expect(c.exportCookies().first { $0.name == "sid" }?.value == "fresh")
    }

    @Test func cancellationOfOneWaiterKeepsOtherAuthenticationAlive() async throws {
        let log = NetworkRequests()
        let entered = DispatchSemaphore(value: 0), released = DispatchSemaphore(value: 0)
        let c = client { request in
            log.record(request)
            if request.url!.path == "/fanyalogin" {
                entered.signal(); _ = released.wait(timeout: .now() + 3)
                return MockReply(body: "{\"status\":true}")
            }
            return MockReply(body: "{\"msg\":{\"uid\":1,\"puid\":1,\"fid\":1}}")
        }
        let first = Task { try await ChaoxingAuthService.reLogin(client: c) }
        let reached = await withCheckedContinuation { continuation in
            DispatchQueue.global().async { continuation.resume(returning: entered.wait(timeout: .now() + 3) == .success) }
        }
        #expect(reached)
        let second = Task { try await ChaoxingAuthService.reLogin(client: c) }
        try await Task.sleep(for: .milliseconds(30))
        first.cancel()
        await #expect(throws: CancellationError.self) { try await first.value }
        released.signal()
        #expect(try await second.value.uid == 1)
        #expect(log.all.filter { $0.url!.path == "/fanyalogin" }.count == 1)
    }

    @Test func clearingCookiesCancelsRefreshWithoutRestoringCredentials() async throws {
        let log = NetworkRequests()
        let entered = DispatchSemaphore(value: 0), released = DispatchSemaphore(value: 0)
        let c = client { request in
            log.record(request)
            if request.url!.path == "/fanyalogin" {
                entered.signal(); _ = released.wait(timeout: .now() + 3)
                return MockReply(headers: ["Set-Cookie": "sid=stale; Domain=chaoxing.com; Path=/"], body: "{\"status\":true}")
            }
            return MockReply(body: "{\"msg\":{\"uid\":1,\"puid\":1,\"fid\":1}}")
        }
        let caller = Task { try await ChaoxingAuthService.reLogin(client: c) }
        let reached = await withCheckedContinuation { continuation in
            DispatchQueue.global().async { continuation.resume(returning: entered.wait(timeout: .now() + 3) == .success) }
        }
        #expect(reached)
        c.clearCookies()
        await #expect(throws: CancellationError.self) { try await caller.value }
        released.signal()
        try await Task.sleep(for: .milliseconds(30))
        #expect(c.exportCookies().isEmpty)
        #expect(log.all.filter { $0.url!.path.contains("userLogin4Uname") }.isEmpty)
    }

    @Test func disposedClientCannotStartAnotherLoginOrRequest() async throws {
        let log = NetworkRequests()
        let c = client { request in log.record(request); return MockReply(body: "{\"status\":true}") }
        c.invalidate()
        await #expect(throws: CancellationError.self) { try await ChaoxingAuthService.reLogin(client: c) }
        await #expect(throws: CancellationError.self) { try await c.get(URL(string: "https://mobilelearn.chaoxing.com/test")!) }
        #expect(log.all.isEmpty)
    }

    @Test func simultaneousExpiredRequestsShareOneRelogin() async throws {
        let log = NetworkRequests()
        let c = client { request in
            log.record(request)
            if request.url!.path == "/fanyalogin" {
                Thread.sleep(forTimeInterval: 0.05)
                return MockReply(headers: ["Set-Cookie": "sid=fresh; Domain=chaoxing.com; Path=/"], body: "{\"status\":true}")
            }
            return MockReply(body: "{\"msg\":{\"uid\":1,\"puid\":1,\"fid\":1}}")
        }
        try await withThrowingTaskGroup(of: ChaoxingAccountSession.self) { group in
            for _ in 0..<20 { group.addTask { try await ChaoxingAuthService.reLogin(client: c) } }
            for try await result in group { #expect(result.uid == 1) }
        }
        #expect(log.all.filter { $0.url!.path == "/fanyalogin" }.count == 1)
    }

    @Test func profileCommitPreservesMetadataAndRejectsChangedIdentity() throws {
        let c = ChaoxingHTTPClient(session: account())
        let before = c.session
        c.updateTags(["new-tag"])
        let profile = ChaoxingUserProfile(uid: 1, puid: 1, fid: 1, name: "synthetic", uname: "synthetic", avatarURL: "", phoneNumber: "synthetic", imEncryptedPassword: "", clientId: nil, schools: [.init(fid: 1, name: "synthetic")])
        let result = try c.applyAuthenticatedProfile(profile, matching: before)
        #expect(result.tags == ["new-tag"])
        let other = ChaoxingAccountSession(phoneNumber: "other-synthetic", encryptedPassword: "FAKE", deviceCode: "FAKE")
        c.updateSession(other)
        #expect(throws: ChaoxingError.self) { try c.applyAuthenticatedProfile(profile, matching: before) }
        #expect(c.session.phoneNumber == "other-synthetic")
        #expect(c.session.uid == 0)
    }

    @Test func concurrentSessionReadsAndCookieWritesRemainSafe() {
        let c = ChaoxingHTTPClient(session: account())
        DispatchQueue.concurrentPerform(iterations: 2) { worker in
            if worker == 0 { for i in 0..<10000 { c.updateConfiguredFid(i) } }
            else { for _ in 0..<10000 { let snapshot = c.session; #expect(snapshot.configuredFid >= 0); _ = snapshot.cookies.count } }
        }
        #expect(c.session.configuredFid == 9999)
    }
}

// Independent cancellation reproduction from the protocol cross-review.
private final class LoginGate: @unchecked Sendable {
    let entered = DispatchSemaphore(value: 0)
    let released = DispatchSemaphore(value: 0)
    let lock = NSLock()
    var paths: [String] = []
    func record(_ path: String) { lock.withLock { paths.append(path) } }
    var profileRequests: Int { lock.withLock { paths.filter { $0.contains("userLogin4Uname") }.count } }
}
private final class CancellationProtocol: URLProtocol, @unchecked Sendable {
    static let gate = LoginGate()
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        DispatchQueue.global().async { [self] in
            let path = request.url!.path
            Self.gate.record(path)
            if path == "/fanyalogin" {
                Self.gate.entered.signal()
                _ = Self.gate.released.wait(timeout: .now() + 5)
            }
            let body = path == "/fanyalogin" ? "{\"status\":true}" : "{\"msg\":{\"uid\":1,\"puid\":1,\"fid\":1}}"
            client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: Data(body.utf8))
            client?.urlProtocolDidFinishLoading(self)
        }
    }
    override func stopLoading() {}
}
struct AuthenticationCancellationRegressionTests {
    @Test func cancelledOnlyReloginWaiterMustStopProfileRequest() async throws {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [CancellationProtocol.self]
        let client = ChaoxingHTTPClient(session: .init(phoneNumber: "synthetic", encryptedPassword: "synthetic", deviceCode: "synthetic"), configuration: configuration)
        let caller = Task { try await ChaoxingAuthService.reLogin(client: client) }
        let entered = await withCheckedContinuation { continuation in
            DispatchQueue.global().async { continuation.resume(returning: CancellationProtocol.gate.entered.wait(timeout: .now() + 3) == .success) }
        }
        #expect(entered)
        caller.cancel()
        CancellationProtocol.gate.released.signal()
        await #expect(throws: CancellationError.self) { try await caller.value }
        try await Task.sleep(for: .milliseconds(30))
        #expect(CancellationProtocol.gate.profileRequests == 0)
    }
}
