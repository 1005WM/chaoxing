// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.
// Contains Swift adaptations of ChaoxingSignFaker protocol logic.
// Upstream copyright (c) 2025-2026 aquamarine5 and contributors.
// Adapted for Apple platforms; notices updated 2026-11-14.
// See THIRD_PARTY_NOTICES.md for attribution and changes.

import Foundation

enum ChaoxingCookiePolicy {
    static func domain(_ value: String) -> String { String(value.lowercased().drop(while: { $0 == "." })) }
    static func domainMatches(host: String, domain: String) -> Bool { host == domain || host.hasSuffix("." + domain) }
    static func isTrusted(_ url: URL) -> Bool {
        guard let host = url.host?.lowercased() else { return false }
        return domainMatches(host: host, domain: "chaoxing.com")
    }
    private static func key(_ cookie: ChaoxingStoredCookie) -> String { "\(cookie.name)\n\(domain(cookie.domain))\n\(cookie.path)" }
    private static func valid(_ cookie: ChaoxingStoredCookie) -> Bool {
        let forbiddenName = CharacterSet(charactersIn: "()<>@,;:\\\"/[]?={} \t\r\n")
        return !cookie.name.isEmpty && cookie.name.rangeOfCharacter(from: forbiddenName) == nil
            && cookie.value.rangeOfCharacter(from: .controlCharacters) == nil && !cookie.value.contains(";")
            && domainMatches(host: domain(cookie.domain), domain: "chaoxing.com")
            && cookie.path.hasPrefix("/") && cookie.path.rangeOfCharacter(from: .controlCharacters) == nil
    }
    static func merging(_ existing: [ChaoxingStoredCookie], with incoming: [ChaoxingStoredCookie], now: Date = Date()) -> [ChaoxingStoredCookie] {
        var map: [String: ChaoxingStoredCookie] = [:]
        for var cookie in existing + incoming where valid(cookie) {
            cookie.domain = domain(cookie.domain)
            if let expiry = cookie.expiresAt, expiry <= now { map.removeValue(forKey: key(cookie)) }
            else { map[key(cookie)] = cookie }
        }
        return map.values.sorted { key($0) < key($1) }
    }
    static func header(_ cookies: [ChaoxingStoredCookie], for url: URL, now: Date = Date()) -> String? {
        guard isTrusted(url), url.scheme?.lowercased() == "https", let host = url.host?.lowercased() else { return nil }
        let encodedPath = url.path(percentEncoded: true)
        let requestPath = encodedPath.isEmpty ? "/" : encodedPath
        let matched = cookies.filter { cookie in
            let domain = domain(cookie.domain)
            let pathMatches = requestPath == cookie.path || (requestPath.hasPrefix(cookie.path) && (cookie.path.hasSuffix("/") || requestPath.dropFirst(cookie.path.count).first == "/"))
            return valid(cookie) && (cookie.hostOnly ? host == domain : domainMatches(host: host, domain: domain))
                && pathMatches && (cookie.expiresAt.map { $0 > now } ?? true)
                && (!cookie.isSecure || url.scheme?.lowercased() == "https")
        }.sorted { $0.path.count == $1.path.count ? key($0) < key($1) : $0.path.count > $1.path.count }
        return matched.isEmpty ? nil : matched.map { "\($0.name)=\($0.value)" }.joined(separator: "; ")
    }
    static func allowsRedirect(from source: URL, to destination: URL, method: String) -> Bool {
        guard destination.scheme?.lowercased() == "https", destination.user == nil, destination.password == nil,
              isTrusted(source) == isTrusted(destination) else { return false }
        let sameOrigin = source.scheme?.lowercased() == destination.scheme?.lowercased()
            && source.host?.lowercased() == destination.host?.lowercased() && (source.port ?? 443) == (destination.port ?? 443)
        return sameOrigin || method == "GET" || method == "HEAD"
    }
}

private final class ChaoxingRedirectHandler: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
    weak var client: ChaoxingHTTPClient?
    let generation: UInt64
    init(client: ChaoxingHTTPClient, generation: UInt64) { self.client = client; self.generation = generation }
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping @Sendable (URLRequest?) -> Void) {
        completionHandler(client?.redirectedRequest(response: response, request: request, generation: generation))
    }
}

public final class ChaoxingHTTPClient: @unchecked Sendable {
    private var _session: ChaoxingAccountSession
    public var session: ChaoxingAccountSession { lock.withLock { _session } }
    private var generation: UInt64 = 0
    private var isInvalidated = false
    private struct RefreshOperation {
        let id: UUID
        let task: Task<Void, Never>
        var waiters: [UUID: CheckedContinuation<ChaoxingAccountSession, Error>]
    }
    private var refreshTask: RefreshOperation?
    private var _userAgent: String
    private var _storageCloudToken: String?

    private let urlSession: URLSession
    private let lock = NSLock()
    private var cookies: [ChaoxingStoredCookie]

    public var userAgent: String {
        get { lock.withLock { _userAgent } }
        set { lock.withLock { _userAgent = newValue } }
    }

    public var storageCloudToken: String? {
        get { lock.withLock { _storageCloudToken } }
        set { lock.withLock { _storageCloudToken = newValue } }
    }

    public init(session: ChaoxingAccountSession, userAgent: String = ChaoxingClientPreset.default.userAgent, configuration: URLSessionConfiguration = .ephemeral) {
        self._session = session
        self._userAgent = userAgent
        self.cookies = ChaoxingCookiePolicy.merging([], with: session.cookies)
        self._session.cookies = self.cookies

        let config = configuration.copy() as! URLSessionConfiguration
        config.httpShouldSetCookies = false
        config.httpCookieAcceptPolicy = .never
        config.httpCookieStorage = nil
        config.timeoutIntervalForRequest = 20
        config.timeoutIntervalForResource = 60
        self.urlSession = URLSession(configuration: config)
    }

    deinit { urlSession.invalidateAndCancel() }

    /// Permanently retire a client removed from the account pool. Unlike clearing
    /// cookies, disposal must also prevent a retained reference from logging in again.
    public func invalidate() {
        let cancelled = lock.withLock {
            isInvalidated = true
            generation &+= 1
            _storageCloudToken = nil
            cookies = []
            _session.cookies = []
            let invalidated = refreshTask
            refreshTask = nil
            return invalidated
        }
        Self.cancelRefresh(cancelled)
        urlSession.invalidateAndCancel()
    }

    public func updateSession(_ updated: ChaoxingAccountSession) {
        let cancelled = lock.withLock { () -> RefreshOperation? in
            var invalidated: RefreshOperation?
            if _session.phoneNumber != updated.phoneNumber || _session.encryptedPassword != updated.encryptedPassword || _session.deviceCode != updated.deviceCode || cookies != updated.cookies {
                generation &+= 1
                _storageCloudToken = nil
                invalidated = refreshTask
                refreshTask = nil
            }
            _session = updated
            cookies = ChaoxingCookiePolicy.merging([], with: updated.cookies)
            _session.cookies = cookies
            return invalidated
        }
        Self.cancelRefresh(cancelled)
    }
    public func updateTags(_ tags: [String]) {
        lock.withLock { _session.tags = tags }
    }

    func applyAuthenticatedProfile(_ profile: ChaoxingUserProfile, matching expected: ChaoxingAccountSession, generation expectedGeneration: UInt64? = nil) throws -> ChaoxingAccountSession {
        try lock.withLock {
            try Task.checkCancellation()
            guard !isInvalidated, expectedGeneration.map({ $0 == generation }) ?? true,
                  _session.phoneNumber == expected.phoneNumber,
                  _session.encryptedPassword == expected.encryptedPassword,
                  _session.deviceCode == expected.deviceCode else {
                throw ChaoxingError.sessionExpired("账号会话已变更，请重试")
            }
            _session.name = profile.name
            _session.uid = profile.uid
            _session.puid = profile.puid
            _session.schools = profile.schools
            if !profile.schools.contains(where: { $0.fid == _session.configuredFid }) {
                _session.configuredFid = profile.schools.first?.fid ?? profile.fid
            }
            _session.avatarURL = profile.avatarURL
            _session.imEncryptedPassword = profile.imEncryptedPassword
            _session.clientId = profile.clientId
            _session.lastLoginAt = Date()
            _storageCloudToken = nil
            cookies.removeAll { $0.name == "fid" }
            cookies.append(.init(name: "fid", value: String(_session.configuredFid), domain: "chaoxing.com", isSecure: true))
            _session.cookies = cookies
            return _session
        }
    }

    public func updateConfiguredFid(_ fid: Int) {
        let cancelled = lock.withLock { () -> RefreshOperation? in
            var invalidated: RefreshOperation?
            if _session.configuredFid != fid {
                _storageCloudToken = nil
                generation &+= 1
                invalidated = refreshTask
                refreshTask = nil
            }
            _session.configuredFid = fid
            cookies.removeAll { $0.name == "fid" }
            cookies.append(.init(name: "fid", value: String(fid), domain: "chaoxing.com", isSecure: true))
            _session.cookies = cookies
            return invalidated
        }
        Self.cancelRefresh(cancelled)
    }
    public func exportCookies() -> [ChaoxingStoredCookie] { lock.withLock { ChaoxingCookiePolicy.merging([], with: cookies) } }
    public func replaceCookies(_ newCookies: [ChaoxingStoredCookie]) {
        let cancelled = lock.withLock {
            generation &+= 1
            _storageCloudToken = nil
            cookies = ChaoxingCookiePolicy.merging([], with: newCookies)
            _session.cookies = cookies
            let invalidated = refreshTask
            refreshTask = nil
            return invalidated
        }
        Self.cancelRefresh(cancelled)
    }
    public func mergeCookies(_ incoming: [ChaoxingStoredCookie]) {
        lock.withLock {
            cookies = ChaoxingCookiePolicy.merging(cookies, with: incoming)
            _session.cookies = cookies
        }
    }
    public func clearCookies() { replaceCookies([]) }

    // Each caller has its own continuation: cancellation is prompt, while a refresh
    // needed by other callers remains alive. The final waiter cancels the transport.
    func reauthenticate(_ operation: @escaping @Sendable () async throws -> ChaoxingAccountSession) async throws -> ChaoxingAccountSession {
        let waiterID = UUID()
        let result = try await withTaskCancellationHandler {
            try Task.checkCancellation()
            return try await withCheckedThrowingContinuation { continuation in
                let wasCancelled = lock.withLock {
                    guard !Task.isCancelled, !isInvalidated else { return true }
                    if refreshTask != nil {
                        refreshTask!.waiters[waiterID] = continuation
                        return false
                    }
                    generation &+= 1
                    _storageCloudToken = nil
                    let refreshID = UUID()
                    let task = Task {
                        let result: Result<ChaoxingAccountSession, Error>
                        do {
                            try Task.checkCancellation()
                            let session = try await operation()
                            try Task.checkCancellation()
                            result = .success(session)
                        } catch { result = .failure(error) }
                        self.finishRefresh(refreshID, result: result)
                    }
                    refreshTask = RefreshOperation(id: refreshID, task: task, waiters: [waiterID: continuation])
                    return false
                }
                if wasCancelled { continuation.resume(throwing: CancellationError()) }
            }
        } onCancel: {
            self.cancelRefreshWaiter(waiterID)
        }
        try Task.checkCancellation()
        return result
    }

    private func finishRefresh(_ id: UUID, result: Result<ChaoxingAccountSession, Error>) {
        let waiters = lock.withLock { () -> [CheckedContinuation<ChaoxingAccountSession, Error>] in
            guard let refresh = refreshTask, refresh.id == id else { return [] }
            generation &+= 1
            refreshTask = nil
            return Array(refresh.waiters.values)
        }
        for waiter in waiters { waiter.resume(with: result) }
    }

    private func cancelRefreshWaiter(_ id: UUID) {
        let cancelled = lock.withLock { () -> (Task<Void, Never>?, CheckedContinuation<ChaoxingAccountSession, Error>?) in
            guard var refresh = refreshTask, let waiter = refresh.waiters.removeValue(forKey: id) else { return (nil, nil) }
            if refresh.waiters.isEmpty {
                generation &+= 1
                refreshTask = nil
                return (refresh.task, waiter)
            }
            refreshTask = refresh
            return (nil, waiter)
        }
        cancelled.0?.cancel()
        cancelled.1?.resume(throwing: CancellationError())
    }

    private static func cancelRefresh(_ refresh: RefreshOperation?) {
        guard let refresh else { return }
        refresh.task.cancel()
        for waiter in refresh.waiters.values { waiter.resume(throwing: CancellationError()) }
    }

    func authenticationContext() -> (session: ChaoxingAccountSession, generation: UInt64) {
        lock.withLock { (_session, generation) }
    }

    public func get(_ url: URL, headers: [String: String] = [:]) async throws -> (Data, HTTPURLResponse) {
        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        return try await execute(request, extraHeaders: headers)
    }

    public func postFormEncoded(_ url: URL, encodedBody: String, headers: [String: String] = [:]) async throws -> (Data, HTTPURLResponse) {
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.httpBody = Data(encodedBody.utf8)
        request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        return try await execute(request, extraHeaders: headers)
    }

    public func postForm(_ url: URL, items: [(String, String)], headers: [String: String] = [:]) async throws -> (Data, HTTPURLResponse) {
        let encoded = items.map { key, value in
            let k = Self.formPercentEncode(key)
            let v = Self.formPercentEncode(value)
            return "\(k)=\(v)"
        }.joined(separator: "&")
        return try await postFormEncoded(url, encodedBody: encoded, headers: headers)
    }

    public func postRaw(_ url: URL, body: Data, contentType: String, headers: [String: String] = [:]) async throws -> (Data, HTTPURLResponse) {
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.httpBody = body
        request.setValue(contentType, forHTTPHeaderField: "Content-Type")
        return try await execute(request, extraHeaders: headers)
    }

    public func execute(_ request: URLRequest, extraHeaders: [String: String] = [:]) async throws -> (Data, HTTPURLResponse) {
        try Task.checkCancellation()
        var req = request
        for (key, value) in extraHeaders { req.setValue(value, forHTTPHeaderField: key) }
        let requestGeneration = try lock.withLock {
            guard !isInvalidated else { throw CancellationError() }
            if req.value(forHTTPHeaderField: "User-Agent") == nil { req.setValue(_userAgent, forHTTPHeaderField: "User-Agent") }
            req.setValue(req.url.flatMap { ChaoxingCookiePolicy.header(cookies, for: $0) }, forHTTPHeaderField: "Cookie")
            return generation
        }
        let (data, response) = try await urlSession.data(for: req, delegate: ChaoxingRedirectHandler(client: self, generation: requestGeneration))
        try Task.checkCancellation()
        guard let httpResponse = response as? HTTPURLResponse else {
            throw ChaoxingError.networkError(statusCode: nil, message: "无效的 HTTP 响应")
        }
        guard lock.withLock({ generation == requestGeneration }) else { throw ChaoxingError.sessionExpired("账号会话已变更，请重试") }
        receiveCookies(httpResponse, generation: requestGeneration)
        return (data, httpResponse)
    }

    private func receiveCookies(_ response: HTTPURLResponse, generation expectedGeneration: UInt64) {
        guard let url = response.url, ChaoxingCookiePolicy.isTrusted(url), url.scheme?.lowercased() == "https" else { return }
        let headers = response.allHeaderFields.reduce(into: [String: String]()) { result, pair in
            if let key = pair.key as? String, let value = pair.value as? String { result[key] = value }
        }
        let incoming = HTTPCookie.cookies(withResponseHeaderFields: headers, for: url).compactMap { cookie -> ChaoxingStoredCookie? in
            let domain = ChaoxingCookiePolicy.domain(cookie.domain)
            guard let host = url.host?.lowercased(), ChaoxingCookiePolicy.domainMatches(host: host, domain: domain) else { return nil }
            return ChaoxingStoredCookie(name: cookie.name, value: cookie.value, domain: domain, path: cookie.path,
                                        isSecure: cookie.isSecure, expiresAt: cookie.expiresDate, hostOnly: !cookie.domain.hasPrefix("."))
        }
        guard !incoming.isEmpty else { return }
        lock.withLock {
            guard generation == expectedGeneration else { return }
            let isLogin = url.host?.lowercased() == "passport2.chaoxing.com" && url.path == "/fanyalogin" && (200..<300).contains(response.statusCode)
            cookies = ChaoxingCookiePolicy.merging(isLogin ? [] : cookies, with: incoming)
            _session.cookies = cookies
        }
    }

    fileprivate func redirectedRequest(response: HTTPURLResponse, request: URLRequest, generation expectedGeneration: UInt64) -> URLRequest? {
        guard let source = response.url, let destination = request.url,
              ChaoxingCookiePolicy.allowsRedirect(from: source, to: destination, method: request.httpMethod ?? "GET") else { return nil }
        receiveCookies(response, generation: expectedGeneration)
        return lock.withLock {
            guard generation == expectedGeneration else { return nil }
            var next = request
            next.setValue(nil, forHTTPHeaderField: "Cookie")
            if source.host?.lowercased() != destination.host?.lowercased() || source.port != destination.port {
                next.setValue(nil, forHTTPHeaderField: "Authorization")
                next.setValue(nil, forHTTPHeaderField: "Proxy-Authorization")
            }
            next.setValue(ChaoxingCookiePolicy.header(cookies, for: destination), forHTTPHeaderField: "Cookie")
            return next
        }
    }

    public static func formPercentEncode(_ string: String) -> String {
        var allowed = CharacterSet.alphanumerics
        allowed.insert(charactersIn: "-._~")
        return string.addingPercentEncoding(withAllowedCharacters: allowed) ?? string
    }
}
