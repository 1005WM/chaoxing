// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

import Foundation
import Darwin

public struct ChaoxingSignHistoryEntry: Codable, Sendable, Hashable, Identifiable {
    public let id: UUID
    public let timestamp: Date
    public let activeId: Int64
    public let activityName: String
    public let courseName: String
    public let signTypeRaw: String
    public let successCount: Int
    // Older entries combined previously signed and newly accepted accounts.
    public let alreadySignedCount: Int?
    public let totalCount: Int
    public private(set) var summary: String

    public var displayCounts: String {
        guard let alreadySignedCount else {
            return "旧版合计 \(successCount)/\(totalCount)（可能包含此前已签）"
        }
        return ChaoxingSignCounts(accepted: successCount, alreadySigned: alreadySignedCount, total: totalCount)?.summary
            ?? "历史记录的计数无效"
    }

    fileprivate var withoutAccountSummary: Self {
        var copy = self
        copy.summary = ""
        return copy
    }

    public init(
        id: UUID = UUID(),
        timestamp: Date = Date(),
        activeId: Int64,
        activityName: String,
        courseName: String,
        signTypeRaw: String,
        successCount: Int,
        alreadySignedCount: Int? = nil,
        totalCount: Int,
        summary: String
    ) {
        self.id = id
        self.timestamp = timestamp
        self.activeId = activeId
        self.activityName = activityName
        self.courseName = courseName
        self.signTypeRaw = signTypeRaw
        self.successCount = successCount
        self.alreadySignedCount = alreadySignedCount
        self.totalCount = totalCount
        self.summary = summary
    }
}

public struct ChaoxingAppStateSnapshot: Codable, Sendable {
    public var accounts: [ChaoxingAccountSession]
    public var locationPresets: [ChaoxingLocationPreset]
    public var selectedClientPreset: String
    public var customUserAgent: String
    public var customClientProfiles: [ChaoxingCustomClientProfile]
    public var selectedCustomClientId: UUID?
    public var enableLocationJitter: Bool
    public var batchSignDelayMs: Int
    public var enableImageObfuscation: Bool
    public var autoSolveCaptcha: Bool
    public var signHistory: [ChaoxingSignHistoryEntry]
    /// 课程绑定默认签到位置（key 为 String(courseId)，Issue #222）
    public var courseLocationBindings: [String: ChaoxingLocationPreset]
    /// 标星常用课程 ID（Issue #74）
    public var starredCourseIds: [Int64]

    public init(
        accounts: [ChaoxingAccountSession] = [],
        locationPresets: [ChaoxingLocationPreset] = [],
        selectedClientPreset: String = ChaoxingClientPreset.default.rawValue,
        customUserAgent: String = "",
        customClientProfiles: [ChaoxingCustomClientProfile] = [],
        selectedCustomClientId: UUID? = nil,
        enableLocationJitter: Bool = true,
        batchSignDelayMs: Int = 1200,
        enableImageObfuscation: Bool = true,
        autoSolveCaptcha: Bool = true,
        signHistory: [ChaoxingSignHistoryEntry] = [],
        courseLocationBindings: [String: ChaoxingLocationPreset] = [:],
        starredCourseIds: [Int64] = []
    ) {
        self.accounts = accounts
        self.locationPresets = locationPresets
        self.selectedClientPreset = selectedClientPreset
        self.customUserAgent = customUserAgent
        self.customClientProfiles = customClientProfiles
        self.selectedCustomClientId = selectedCustomClientId
        self.enableLocationJitter = enableLocationJitter
        self.batchSignDelayMs = batchSignDelayMs
        self.enableImageObfuscation = enableImageObfuscation
        self.autoSolveCaptcha = autoSolveCaptcha
        self.signHistory = signHistory
        self.courseLocationBindings = courseLocationBindings
        self.starredCourseIds = starredCourseIds
    }

    enum CodingKeys: String, CodingKey {
        case accounts
        case locationPresets
        case selectedClientPreset
        case customUserAgent
        case customClientProfiles
        case selectedCustomClientId
        case enableLocationJitter
        case batchSignDelayMs
        case enableImageObfuscation
        case autoSolveCaptcha
        case signHistory
        case courseLocationBindings
        case starredCourseIds
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        accounts = try container.decodeIfPresent([ChaoxingAccountSession].self, forKey: .accounts) ?? []
        locationPresets = try container.decodeIfPresent([ChaoxingLocationPreset].self, forKey: .locationPresets) ?? []
        selectedClientPreset = try container.decodeIfPresent(String.self, forKey: .selectedClientPreset)
            ?? ChaoxingClientPreset.default.rawValue
        customUserAgent = try container.decodeIfPresent(String.self, forKey: .customUserAgent) ?? ""
        customClientProfiles = try container.decodeIfPresent([ChaoxingCustomClientProfile].self, forKey: .customClientProfiles) ?? []
        selectedCustomClientId = try container.decodeIfPresent(UUID.self, forKey: .selectedCustomClientId)
        enableLocationJitter = try container.decodeIfPresent(Bool.self, forKey: .enableLocationJitter) ?? true
        batchSignDelayMs = try container.decodeIfPresent(Int.self, forKey: .batchSignDelayMs) ?? 1200
        enableImageObfuscation = try container.decodeIfPresent(Bool.self, forKey: .enableImageObfuscation) ?? true
        autoSolveCaptcha = try container.decodeIfPresent(Bool.self, forKey: .autoSolveCaptcha) ?? true
        signHistory = try container.decodeIfPresent([ChaoxingSignHistoryEntry].self, forKey: .signHistory) ?? []
        courseLocationBindings = try container.decodeIfPresent([String: ChaoxingLocationPreset].self, forKey: .courseLocationBindings) ?? [:]
        starredCourseIds = try container.decodeIfPresent([Int64].self, forKey: .starredCourseIds) ?? []
    }

    public func encodedBackupData(includeAccounts: Bool) throws -> Data {
        var copy = self
        if !includeAccounts {
            copy.accounts = []
            copy.signHistory = copy.signHistory.map(\.withoutAccountSummary)
        }
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        encoder.dateEncodingStrategy = .iso8601
        return try encoder.encode(copy)
    }

    public static func decodedBackupData(_ data: Data) throws -> ChaoxingAppStateSnapshot {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        return try decoder.decode(ChaoxingAppStateSnapshot.self, from: data)
    }
}

public actor ChaoxingPersistenceStore {
    private let fileURL: URL

    public init(directoryName: String = "Caochaoxing") {
        let baseDir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? FileManager.default.temporaryDirectory
        let appDir = baseDir.appendingPathComponent(directoryName, isDirectory: true)
        try? FileManager.default.createDirectory(at: appDir, withIntermediateDirectories: true)
        self.fileURL = appDir.appendingPathComponent("state.json")
    }

    public init(customFileURL: URL) {
        self.fileURL = customFileURL
    }

    public func load() throws -> ChaoxingAppStateSnapshot {
        let data: Data
        do { data = try Data(contentsOf: fileURL) }
        catch let error as CocoaError where error.code == .fileReadNoSuchFile {
            return ChaoxingAppStateSnapshot()
        }
        return try ChaoxingAppStateSnapshot.decodedBackupData(data)
    }

    public func save(_ snapshot: ChaoxingAppStateSnapshot) throws {
        // Do not turn a failed load into silent destruction of the original state.
        if FileManager.default.fileExists(atPath: fileURL.path) { _ = try load() }
        try write(snapshot)
    }

    /// Explicit recovery only: preserve the original bytes before replacing the active
    /// state. If copying or the atomic write fails, the original active file remains intact.
    public func archiveAndReset() throws -> URL {
        let backup = fileURL.deletingLastPathComponent()
            .appendingPathComponent("state.recovery-\(UUID().uuidString).json")
        try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: fileURL.path)
        try FileManager.default.copyItem(at: fileURL, to: backup)
        try write(ChaoxingAppStateSnapshot())
        return backup
    }

    private func write(_ snapshot: ChaoxingAppStateSnapshot) throws {
        let dir = fileURL.deletingLastPathComponent()
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let data = try snapshot.encodedBackupData(includeAccounts: true)
        // Create an owner-only staging file before writing any credential bytes.
        // A same-directory rename commits atomically without a world-readable window.
        let staging = dir.appendingPathComponent(".state-\(UUID().uuidString).tmp")
        var attributes: [FileAttributeKey: Any] = [.posixPermissions: 0o600]
        #if os(iOS)
        attributes[.protectionKey] = FileProtectionType.completeUntilFirstUserAuthentication
        #endif
        guard FileManager.default.createFile(atPath: staging.path, contents: nil, attributes: attributes) else {
            throw CocoaError(.fileWriteUnknown)
        }
        defer { try? FileManager.default.removeItem(at: staging) }
        let handle = try FileHandle(forWritingTo: staging)
        do {
            try handle.write(contentsOf: data)
            try handle.synchronize()
            try handle.close()
        } catch {
            try? handle.close()
            throw error
        }
        guard rename(staging.path, fileURL.path) == 0 else {
            throw POSIXError(POSIXErrorCode(rawValue: errno) ?? .EIO)
        }
    }
}
