// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

import Foundation
import Testing
@testable import ChaoxingCore

@Suite("Persistence regressions")
struct PersistenceRegressionTests {
    @Test func corruptedStateIsReportedAndPreserved() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let file = directory.appendingPathComponent("state.json")
        let corrupt = Data("{\"accounts\": [BROKEN".utf8)
        try corrupt.write(to: file)
        let store = ChaoxingPersistenceStore(customFileURL: file)
        do { _ = try await store.load(); Issue.record("Corruption must be reported") } catch {}
        #expect(try Data(contentsOf: file) == corrupt)
        do { try await store.save(ChaoxingAppStateSnapshot()); Issue.record("Save must not overwrite unreadable state") } catch {}
        #expect(try Data(contentsOf: file) == corrupt)
        let backup = try await store.archiveAndReset()
        #expect(try Data(contentsOf: backup) == corrupt)
        #expect(try await store.load().accounts.isEmpty)
    }

    @Test func stateAndRecoveryFilesAreOwnerOnly() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let file = directory.appendingPathComponent("state.json")
        let store = ChaoxingPersistenceStore(customFileURL: file)
        try await store.save(ChaoxingAppStateSnapshot())
        let permissions = try FileManager.default.attributesOfItem(atPath: file.path)[.posixPermissions] as? NSNumber
        #expect(permissions?.intValue == 0o600)
        // Legacy files must also be protected when archived for recovery.
        try FileManager.default.setAttributes([.posixPermissions: 0o644], ofItemAtPath: file.path)
        let backup = try await store.archiveAndReset()
        let backupPermissions = try FileManager.default.attributesOfItem(atPath: backup.path)[.posixPermissions] as? NSNumber
        #expect(backupPermissions?.intValue == 0o600)
    }

    @Test func missingFileIsNormalFirstRun() async throws {
        let file = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        #expect(try await ChaoxingPersistenceStore(customFileURL: file).load().accounts.isEmpty)
    }

    @Test func unreadableDirectoryIsNotTreatedAsFirstRun() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = ChaoxingPersistenceStore(customFileURL: directory)
        do { _ = try await store.load(); Issue.record("A directory is an invalid state file") } catch {}
        do { try await store.save(ChaoxingAppStateSnapshot()); Issue.record("Must not overwrite the directory") } catch {}
        var isDirectory: ObjCBool = false
        #expect(FileManager.default.fileExists(atPath: directory.path, isDirectory: &isDirectory))
        #expect(isDirectory.boolValue)
    }

    @Test func legacyCookieHostScopeIsRecoveredFromStoredFoundationDomain() throws {
        for (domain, hostOnly) in [("passport2.chaoxing.com", true), (".chaoxing.com", false), ("chaoxing.com", false)] {
            let raw = try JSONSerialization.data(withJSONObject: ["name": "sid", "value": "synthetic", "domain": domain, "path": "/"])
            let cookie = try JSONDecoder().decode(ChaoxingStoredCookie.self, from: raw)
            #expect(cookie.hostOnly == hostOnly)
        }
    }

    @Test func oldCookieSnapshotRemainsReadable() throws {
        let raw = Data("{\"name\":\"sid\",\"value\":\"synthetic\",\"domain\":\"chaoxing.com\",\"path\":\"/\"}".utf8)
        let cookie = try JSONDecoder().decode(ChaoxingStoredCookie.self, from: raw)
        #expect(cookie.name == "sid")
        #expect(cookie.expiresAt == nil)
    }

    @Test func historyKeepsLegacyCountsAndRedactsAccountNotesInPresetBackups() throws {
        let timestamp = Date(timeIntervalSince1970: 1_700_000_000)
        let old = ChaoxingSignHistoryEntry(timestamp: timestamp, activeId: 1, activityName: "Fixture", courseName: "Course",
            signTypeRaw: "0", successCount: 2, totalCount: 2, summary: "Private fixture account")
        let new = ChaoxingSignHistoryEntry(timestamp: timestamp, activeId: 2, activityName: "Fixture", courseName: "Course",
            signTypeRaw: "0", successCount: 0, alreadySignedCount: 1, totalCount: 2, summary: "Private fixture account")
        let state = ChaoxingAppStateSnapshot(signHistory: [old, new])
        let full = try ChaoxingAppStateSnapshot.decodedBackupData(state.encodedBackupData(includeAccounts: true))
        #expect(full.signHistory == [old, new])
        #expect(full.signHistory[0].alreadySignedCount == nil)
        #expect(full.signHistory[0].displayCounts.contains("旧版合计"))
        #expect(full.signHistory[1].displayCounts == ChaoxingSignCounts(dispositions: [.alreadySigned, .failed]).summary)
        let data = try state.encodedBackupData(includeAccounts: false)
        #expect(!String(decoding: data, as: UTF8.self).contains("Private fixture account"))
        let redacted = try ChaoxingAppStateSnapshot.decodedBackupData(data)
        #expect(redacted.signHistory.map(\.id) == [old.id, new.id])
        #expect(redacted.signHistory.map(\.displayCounts) == full.signHistory.map(\.displayCounts))
    }
}
