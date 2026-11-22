// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

import Foundation
import Testing
@testable import ChaoxingCore
@testable import IOSAppStateSupport

private actor ResponseGate<Value: Sendable> {
    private var pending: [String: CheckedContinuation<Value, Error>] = [:]
    func response(_ key: String) async throws -> Value {
        try await withCheckedThrowingContinuation { pending[key] = $0 }
    }
    func waitForRequest(_ key: String) async {
        while pending[key] == nil { await Task.yield() }
    }
    func succeed(_ key: String, _ value: Value) { pending.removeValue(forKey: key)?.resume(returning: value) }
}

private actor IMRecoveryProbe {
    var configurations = 0
    var fetchedTokens: [String] = []
    let alwaysFails: Bool
    init(alwaysFails: Bool = false) { self.alwaysFails = alwaysFails }
    func configuration() -> ChaoxingEasemobIMConfig {
        configurations += 1
        return ChaoxingEasemobIMConfig(accessToken: "fresh", uuid: "synthetic", username: "A")
    }
    func groups(_ config: ChaoxingEasemobIMConfig) throws -> [ChaoxingIMGroup] {
        fetchedTokens.append(config.accessToken)
        if config.accessToken == "expired" || alwaysFails { throw ChaoxingError.sessionExpired("synthetic expired token") }
        return [ChaoxingIMGroup(id: "synthetic", name: "Synthetic")]
    }
}

@MainActor
private final class BatchProgressProbe {
    var results: [IOSBatchSignResult] = []
}

@Suite("iOS state offline regressions")
@MainActor
struct IOSStateRegressionTests {
    private func account(_ phone: String, primary: Bool = false, password: String = "synthetic") -> ChaoxingAccountSession {
        ChaoxingAccountSession(phoneNumber: phone, encryptedPassword: password, name: phone, deviceCode: "synthetic-device", isPrimary: primary)
    }
    private func store() -> ChaoxingPersistenceStore {
        ChaoxingPersistenceStore(customFileURL: FileManager.default.temporaryDirectory.appendingPathComponent("Caochaoxing-tests-\(UUID())/state.json"))
    }
    private var offline: IOSAppDependencies {
        IOSAppDependencies(
            login: { _, _, _, _ in throw ChaoxingError.invalidInput("Unexpected network dependency") },
            fetchCourses: { _ in [] },
            fetchIMConfig: { _ in throw ChaoxingError.invalidInput("Unexpected network dependency") },
            fetchGroups: { _, _ in [] },
            fetchActivities: { _, _ in throw ChaoxingError.networkError(statusCode: nil, message: "synthetic offline failure") },
            fetchDetail: { _, _, _, _ in Issue.record("Unexpected activity detail request"); throw ChaoxingError.invalidInput("offline") },
            prepareSign: { _, _ in .readyToSign },
            sleep: { _ in try Task.checkCancellation() }
        )
    }
    private var activity: ChaoxingSignActivity {
        ChaoxingSignActivity(id: 101, startTimeMs: nil, endTimeMs: nil, userStatus: 0, otherId: "2", isLook: false, type: 2, activeType: 2, nameOne: "Synthetic", status: 1, nameFour: "", courseId: 10, classId: 20, courseName: "Synthetic", extContent: "")
    }

    @Test func replacingPrimaryLoginInvalidatesOldSessionOperations() async throws {
        var dependencies = offline
        dependencies.login = { phone, _, _, _ in
            ChaoxingAccountSession(phoneNumber: phone, encryptedPassword: "new", deviceCode: "new", isPrimary: true)
        }
        let state = IOSAppState(store: store(), dependencies: dependencies, autoLoad: false)
        state.accounts = [account("A", primary: true)]
        state.activePhoneNumber = "A"
        let oldToken = state.sessionToken
        try await state.loginPrimary(phoneNumber: "A", password: "synthetic")
        #expect(!state.isCurrent(oldToken))
    }

    @Test func completedOrExpiredActivitiesNeverSubmit() async throws {
        for status in [ChaoxingSignActivityStatus.alreadySigned, .expired] {
            var dependencies = offline
            dependencies.prepareSign = { _, _ in status }
            let state = IOSAppState(store: store(), dependencies: dependencies, autoLoad: false)
            state.accounts = [account("A", primary: true)]
            state.activePhoneNumber = "A"
            let results = try await state.executeSignBatch(activity: activity, selectedPhones: ["A"]) { _, _, _, _ in
                Issue.record("Completed or expired activity must not submit")
                return ChaoxingSignOutcome(success: true, message: "unexpected")
            }
            #expect(results.count == 1)
            #expect(results.first?.disposition == (status == .alreadySigned ? .alreadySigned : .failed))
            #expect(results.first?.success == false)
        }
    }

    @Test func mixedOutcomesCountOnlyNewlyAcceptedResponses() async throws {
        let state = IOSAppState(store: store(), dependencies: offline, autoLoad: false)
        state.accounts = [account("new", primary: true), account("prior"), account("unknown")]
        state.activePhoneNumber = "new"
        let results = try await state.executeSignBatch(activity: activity, selectedPhones: ["new", "prior", "unknown"]) { _, account, _, _ in
            switch account.phoneNumber {
            case "new": return ChaoxingSignOutcome(disposition: .accepted, message: "fixture")
            case "prior": throw ChaoxingError.alreadySigned()
            default: throw ChaoxingError.parseFailed("fixture")
            }
        }
        let counts = ChaoxingSignCounts(dispositions: results.map(\.disposition))
        #expect(counts.accepted == 1)
        #expect(counts.alreadySigned == 1)
        #expect(counts.failed == 1)
        #expect(counts.total == 3)
    }

    @Test func locallyClosedActivityStopsBeforePreparingAnyAccount() async {
        var dependencies = offline
        dependencies.prepareSign = { _, _ in
            Issue.record("Closed activities must not start a request")
            return .readyToSign
        }
        let state = IOSAppState(store: store(), dependencies: dependencies, autoLoad: false)
        state.accounts = [account("A", primary: true)]
        state.activePhoneNumber = "A"
        let closed = ChaoxingSignActivity(id: 101, startTimeMs: nil, endTimeMs: 1, userStatus: 0,
            otherId: "0", isLook: false, type: 2, activeType: 2, nameOne: "Fixture", status: 1, nameFour: "",
            courseId: 10, classId: 20, courseName: "Fixture", extContent: "")
        do {
            _ = try await state.executeSignBatch(activity: closed, selectedPhones: ["A"]) { _, _, _, _ in
                Issue.record("Closed activities must not submit")
                return ChaoxingSignOutcome(success: false, message: "fixture")
            }
            Issue.record("Closed activity accepted")
        } catch ChaoxingError.signExpired {} catch { Issue.record("Unexpected error: \(error)") }
    }

    @Test func invalidActivityNeverReachesPreparationOrSubmission() async throws {
        var dependencies = offline
        dependencies.prepareSign = { _, _ in Issue.record("Invalid target must not prepare"); return .readyToSign }
        let state = IOSAppState(store: store(), dependencies: dependencies, autoLoad: false)
        state.accounts = [account("A", primary: true)]
        state.activePhoneNumber = "A"
        let invalid = ChaoxingSignActivity(id: 0, startTimeMs: nil, endTimeMs: nil, userStatus: 0, otherId: "0", isLook: false, type: 2, activeType: 2, nameOne: "Synthetic", status: 1, nameFour: "", courseId: 10, classId: 20, courseName: "Synthetic", extContent: "")
        do {
            _ = try await state.executeSignBatch(activity: invalid, selectedPhones: ["A"]) { _, _, _, _ in
                Issue.record("Invalid target must not submit"); return ChaoxingSignOutcome(success: true, message: "synthetic")
            }
            Issue.record("Invalid activity must be rejected")
        } catch {}
    }

    @Test func delayedLoginCannotRestoreAnAccountAfterLogout() async throws {
        let gate = ResponseGate<ChaoxingAccountSession>()
        var dependencies = offline
        dependencies.login = { phone, _, _, _ in try await gate.response(phone) }
        let state = IOSAppState(store: store(), dependencies: dependencies, autoLoad: false)
        state.accounts = [account("A", primary: true)]
        state.activePhoneNumber = "A"
        let adding = Task { try await state.addOtherAccount(phoneNumber: "B", password: "synthetic") }
        await gate.waitForRequest("B")
        await state.logoutAll()
        await gate.succeed("B", account("B"))
        do { try await adding.value; Issue.record("Late login must be cancelled") } catch is CancellationError { }
        #expect(state.accounts.isEmpty)
        #expect(state.activeAccount == nil)
    }

    @Test func oldCourseResponseCannotOverwriteSwitchedAccount() async throws {
        let gate = ResponseGate<[ChaoxingCourse]>()
        var dependencies = offline
        dependencies.fetchCourses = { try await gate.response($0.session.phoneNumber) }
        let state = IOSAppState(store: store(), dependencies: dependencies, autoLoad: false)
        let a = account("A", primary: true), b = account("B")
        state.accounts = [a, b]
        state.activePhoneNumber = "A"
        let old = Task { await state.refreshCourses() }
        await gate.waitForRequest("A")
        let switched = Task { await state.switchActiveAccount(to: b) }
        await gate.waitForRequest("B")
        let courseB = ChaoxingCourse(courseName: "B-course", teacherName: "", courseId: 2, classId: 2, imageURL: "")
        await gate.succeed("B", [courseB])
        await switched.value
        await gate.succeed("A", [])
        await old.value
        #expect(state.activePhoneNumber == "B")
        #expect(state.courses == [courseB])
        #expect(!state.isLoading)
    }

    @Test func emptySelectionNeverRunsSignOperation() async throws {
        let state = IOSAppState(store: store(), dependencies: offline, autoLoad: false)
        state.accounts = [account("A", primary: true)]
        state.activePhoneNumber = "A"
        #expect(state.selectedAccounts(phones: []).isEmpty)
        do {
            _ = try await state.executeSignBatch(activity: activity, selectedPhones: []) { _, _, _, _ in
                Issue.record("No account was selected")
                return ChaoxingSignOutcome(success: true, message: "unexpected")
            }
            Issue.record("Empty batch must fail")
        } catch let error as ChaoxingError {
            guard case .invalidInput = error else { Issue.record("Expected input validation"); return }
        }
    }

    @Test func logoutStopsRemainingBatchAfterSuspendedDelay() async throws {
        let delay = ResponseGate<Void>()
        var dependencies = offline
        dependencies.sleep = { _ in try await delay.response("delay") }
        let state = IOSAppState(store: store(), dependencies: dependencies, autoLoad: false)
        state.accounts = [account("A", primary: true), account("B")]
        state.activePhoneNumber = "A"
        state.batchSignDelayMs = 1200
        let batch = Task {
            try await state.executeSignBatch(activity: activity, selectedPhones: ["A", "B"]) { _, account, _, _ in
                #expect(account.phoneNumber == "A", "Second account must never submit after logout")
                return ChaoxingSignOutcome(success: true, message: "synthetic")
            }
        }
        await delay.waitForRequest("delay")
        await state.logoutAll()
        await delay.succeed("delay", ())
        do { _ = try await batch.value; Issue.record("Invalidated batch must cancel") } catch is CancellationError { }
    }

    @Test func cancellingParentStopsRemainingAccountsAndPreservesCompletedResults() async throws {
        let delay = ResponseGate<Void>()
        let progress = BatchProgressProbe()
        var dependencies = offline
        // Deliberately ignores cancellation until released, exercising the post-await guard.
        dependencies.sleep = { _ in try await delay.response("delay") }
        let state = IOSAppState(store: store(), dependencies: dependencies, autoLoad: false)
        state.accounts = [account("A", primary: true), account("B")]
        state.activePhoneNumber = "A"
        state.batchSignDelayMs = 1200
        let parent = Task {
            let batch = Task {
                try await state.executeSignBatch(activity: activity, selectedPhones: ["A", "B"], onProgress: { progress.results = $0 }) { _, account, _, _ in
                    #expect(account.phoneNumber == "A", "Cancellation must prevent the next account's submission")
                    return ChaoxingSignOutcome(success: true, message: "completed-before-cancellation")
                }
            }
            return try await withTaskCancellationHandler {
                try await batch.value
            } onCancel: {
                batch.cancel()
            }
        }
        await delay.waitForRequest("delay")
        #expect(progress.results.map(\.phoneNumber) == ["A"])
        parent.cancel()
        await delay.succeed("delay", ())
        do { _ = try await parent.value; Issue.record("Cancelled parent must cancel its batch") } catch is CancellationError { }
        #expect(progress.results.count == 1)
        #expect(progress.results.first?.success == true)
        #expect(progress.results.first?.message == "completed-before-cancellation")
        #expect(state.activePhoneNumber == "A", "This tests task cancellation without invalidating the session")
    }

    @Test func captchaRequestsQueueAndIgnoreLateDuplicateCompletion() async throws {
        let state = IOSAppState(store: store(), dependencies: offline, autoLoad: false)
        let a = account("A", primary: true)
        state.accounts = [a]
        let client = state.client(for: a)
        let first = Task { try await state.requestCaptchaValidation(client: client, refererURL: "synthetic:first") }
        while state.captchaChallenge == nil { await Task.yield() }
        let firstID = state.captchaChallenge!.id
        state.captchaSheetDidAppear(id: firstID)
        let second = Task { try await state.requestCaptchaValidation(client: client, refererURL: "synthetic:second") }
        await Task.yield()
        #expect(state.captchaChallenge?.id == firstID)
        state.completeCaptcha(id: firstID, result: .failure(CancellationError()))
        state.completeCaptcha(id: firstID, result: .success("late-success"))
        do { _ = try await first.value; Issue.record("First request was cancelled") } catch is CancellationError { }
        #expect(state.captchaChallenge == nil)
        state.captchaSheetDidDismiss()
        while state.captchaChallenge == nil { await Task.yield() }
        let secondID = state.captchaChallenge!.id
        state.captchaSheetDidAppear(id: secondID)
        #expect(secondID != firstID)
        state.completeCaptcha(id: secondID, result: .success("second-result"))
        #expect(try await second.value == "second-result")
        state.captchaSheetDidDismiss()
        #expect(state.captchaChallenge == nil)
    }

    @Test func backupRestoresPreferencesHistoryAndUpdatesMatchingSession() async throws {
        let source = IOSAppState(store: store(), dependencies: offline, autoLoad: false)
        source.accounts = [account("A", primary: true, password: "new-synthetic")]
        source.customUserAgent = "Synthetic-UA"
        source.selectedClientPreset = .xueZaiXiDian
        source.signHistory = [ChaoxingSignHistoryEntry(activeId: 1, activityName: "Synthetic", courseName: "Synthetic", signTypeRaw: "2", successCount: 1, totalCount: 1, summary: "Synthetic")]
        let backup = try source.exportBackupData(includeAccounts: true)
        let restored = IOSAppState(store: store(), dependencies: offline, autoLoad: false)
        restored.accounts = [account("A", primary: true, password: "old-synthetic")]
        _ = restored.client(for: restored.accounts[0])
        try await restored.importBackupData(backup)
        #expect(restored.customUserAgent == "Synthetic-UA")
        #expect(restored.selectedClientPreset == .xueZaiXiDian)
        #expect(restored.signHistory.count == 1)
        #expect(restored.accounts.count == 1)
        #expect(restored.activeClient?.session.encryptedPassword == "new-synthetic")
        try await restored.importBackupData(backup)
        #expect(restored.signHistory.count == 1)
    }

    @Test func reauthenticatingPrimaryPreservesPrimaryStatus() async throws {
        var dependencies = offline
        let replacement = account("A")
        dependencies.login = { _, _, _, _ in replacement }
        let state = IOSAppState(store: store(), dependencies: dependencies, autoLoad: false)
        var primary = account("A", primary: true)
        primary.tags = ["synthetic-group"]
        state.accounts = [primary]
        try await state.addOtherAccount(phoneNumber: "A", password: "synthetic")
        #expect(state.accounts.first?.isPrimary == true)
        #expect(state.activeClient?.session.isPrimary == true)
        #expect(state.accounts.first?.tags == ["synthetic-group"])
    }
    @Test func qrResolutionClearsOldPayloadAndRejectsLateResponses() async throws {
        let gate = ResponseGate<(ChaoxingSignActivity, ChaoxingSignDetailMetadata)>()
        let resolution = IOSQRCodeResolution()
        func pair(_ id: Int64) -> (ChaoxingSignActivity, ChaoxingSignDetailMetadata) {
            let activity = ChaoxingSignActivity(id: id, startTimeMs: nil, endTimeMs: nil, userStatus: 0, otherId: "2", isLook: false, type: 2, activeType: 2, nameOne: "Synthetic", status: 1, nameFour: "", courseId: id, classId: 1, courseName: "Synthetic", extContent: "")
            let detail = ChaoxingSignDetailMetadata(activeId: id, signType: .qrCode, title: "Synthetic", isCaptchaRequired: false, isFaceRequired: false, isPositionRequired: false, isPhotoRequired: false, isRefreshQRCode: false, numberCount: nil, targetLocation: nil, signOutInfo: ChaoxingSignOutInfo(signInId: nil, signOutId: nil, signOutPublishTimeMs: nil, classId: 1, courseId: id))
            return (activity, detail)
        }
        let old = Task { try await resolution.resolve("SIGNIN:id=101&c=synthetic&enc=old") { try await gate.response(String($0.aid!)) } }
        await gate.waitForRequest("101")
        let newest = Task { try await resolution.resolve("SIGNIN:id=202&c=synthetic&enc=new") { try await gate.response(String($0.aid!)) } }
        await gate.waitForRequest("202")
        #expect(resolution.parsed == nil)
        #expect(resolution.activity == nil)
        #expect(resolution.isResolving)
        await gate.succeed("202", pair(202))
        try await newest.value
        await gate.succeed("101", pair(101))
        do { try await old.value; Issue.record("Superseded QR must not commit") } catch is CancellationError { }
        #expect(resolution.parsed?.aid == 202)
        #expect(resolution.activity?.id == 202)
        #expect(resolution.detail?.activeId == 202)
        #expect(!resolution.isResolving)
    }

    @Test func failedImportLeavesExistingPreferencesAndAccountsUntouched() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("Caochaoxing-test-directory-\(UUID())")
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        // A directory cannot be overwritten as the JSON state file.
        let state = IOSAppState(store: ChaoxingPersistenceStore(customFileURL: directory), dependencies: offline, autoLoad: false)
        state.customUserAgent = "keep-original"
        state.accounts = [account("A", primary: true, password: "keep-credential")]
        let incoming = ChaoxingAppStateSnapshot(accounts: [account("A", password: "replace-credential")], customUserAgent: "replace-UA")
        do {
            try await state.importBackupData(incoming.encodedBackupData(includeAccounts: true))
            Issue.record("Persistence failure must be reported")
        } catch { }
        #expect(state.customUserAgent == "keep-original")
        #expect(state.accounts.first?.encryptedPassword == "keep-credential")
        #expect(!state.isRestoring)
    }

    @Test func corruptionBlocksSavesUntilExplicitArchivalReset() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("Caochaoxing-test-recovery-\(UUID())")
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let file = directory.appendingPathComponent("state.json")
        let corrupt = Data("not-a-valid-backup".utf8)
        try corrupt.write(to: file)
        let state = IOSAppState(store: ChaoxingPersistenceStore(customFileURL: file), dependencies: offline, autoLoad: false)
        await state.loadFromDisk()
        #expect(state.persistenceBlocked)
        #expect(await state.saveToDisk() == false)
        #expect(try Data(contentsOf: file) == corrupt)
        await state.recoverLocalState()
        #expect(!state.persistenceBlocked)
        #expect(!state.isRestoring)
        #expect(state.accounts.isEmpty)
        let files = try FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil)
        #expect(files.contains { $0 != file && (try? Data(contentsOf: $0)) == corrupt })
    }

    @Test func cancellingCaptchaBeforePresentationDoesNotBlockNextRequest() async throws {
        let state = IOSAppState(store: store(), dependencies: offline, autoLoad: false)
        let a = account("A", primary: true)
        state.accounts = [a]
        let client = state.client(for: a)
        let cancelled = Task { try await state.requestCaptchaValidation(client: client, refererURL: "synthetic:first") }
        while state.captchaChallenge == nil { await Task.yield() }
        cancelled.cancel()
        do { _ = try await cancelled.value; Issue.record("Cancellation must resume the waiter") } catch is CancellationError { }
        #expect(state.captchaChallenge == nil)
        let next = Task { try await state.requestCaptchaValidation(client: client, refererURL: "synthetic:next") }
        while state.captchaChallenge == nil { await Task.yield() }
        state.completeCaptcha(id: state.captchaChallenge!.id, result: .success("ok"))
        #expect(try await next.value == "ok")
        #expect(state.captchaChallenge == nil)
    }

    @Test func radarFailureDoesNotMasqueradeAsNoPendingActivities() async {
        let state = IOSAppState(store: store(), dependencies: offline, autoLoad: false)
        state.accounts = [account("A", primary: true)]
        state.courses = [ChaoxingCourse(courseName: "Synthetic", teacherName: "", courseId: 1, classId: 1, imageURL: "")]
        state.pendingRadarActivities = [activity]
        await state.scanPendingActivities()
        #expect(state.pendingRadarActivities == [activity])
        #expect(state.errorMessage?.contains("扫描失败") == true)
        #expect(!state.isScanningRadar)
    }

    @Test func invalidDeepLinkIdentifiersNeverRequestMetadata() async {
        let state = IOSAppState(store: store(), dependencies: offline, autoLoad: false)
        state.accounts = [account("A", primary: true)]
        for value in ["caochaoxing://sign?activeId=0", "caochaoxing://sign?activeId=1&courseId=-2", "caochaoxing://sign?activeId=1&classId=invalid"] {
            await state.handleIncomingURL(URL(string: value)!)
            #expect(state.deepLinkActivity == nil)
            #expect(state.errorMessage != nil)
        }
    }

    @Test func expiredIMTokenIsRefreshedOnceAndGroupsRecover() async {
        let probe = IMRecoveryProbe()
        var dependencies = offline
        dependencies.fetchIMConfig = { _ in await probe.configuration() }
        dependencies.fetchGroups = { _, config in try await probe.groups(config) }
        let state = IOSAppState(store: store(), dependencies: dependencies, autoLoad: false)
        state.accounts = [account("A", primary: true)]
        state.imConfig = ChaoxingEasemobIMConfig(accessToken: "expired", uuid: "synthetic", username: "A")
        await state.refreshGroups()
        #expect(await probe.configurations == 1)
        #expect(await probe.fetchedTokens == ["expired", "fresh"])
        #expect(state.groups.first?.id == "synthetic")
        #expect(state.errorMessage == nil)
    }

    @Test func repeatedIMFailureStopsAfterOneRetryAndClearsCache() async {
        let probe = IMRecoveryProbe(alwaysFails: true)
        var dependencies = offline
        dependencies.fetchIMConfig = { _ in await probe.configuration() }
        dependencies.fetchGroups = { _, config in try await probe.groups(config) }
        let state = IOSAppState(store: store(), dependencies: dependencies, autoLoad: false)
        state.accounts = [account("A", primary: true)]
        state.imConfig = ChaoxingEasemobIMConfig(accessToken: "expired", uuid: "synthetic", username: "A")
        await state.refreshGroups()
        #expect(await probe.configurations == 1)
        #expect(await probe.fetchedTokens.count == 2)
        #expect(state.imConfig == nil)
        #expect(state.errorMessage != nil)
    }

    @Test func coldLoginPreservesPendingQRDeepLinkWithoutSubmitting() async throws {
        var dependencies = offline
        let session = account("A", primary: true)
        dependencies.login = { _, _, _, _ in session }
        let state = IOSAppState(store: store(), dependencies: dependencies, autoLoad: false)
        let payload = "SIGNIN:id=101&c=synthetic&enc=synthetic"
        var url = URLComponents()
        url.scheme = "caochaoxing"
        url.host = "qr"
        url.queryItems = [URLQueryItem(name: "data", value: payload)]
        await state.handleIncomingURL(url.url!)
        #expect(state.pendingQRCodeInput == nil)
        try await state.loginPrimary(phoneNumber: "A", password: "synthetic")
        #expect(state.pendingQRCodeInput == payload)
        #expect(state.selectedTab == 2)
        #expect(state.signHistory.isEmpty)
    }

}

extension IOSStateRegressionTests {
    @Test func activityListDistinguishesFailureRetryEmptyAndCachedResults() async {
        let gate = ResponseGate<Result<[ChaoxingSignActivity], ChaoxingError>>()
        var dependencies = offline
        dependencies.fetchActivities = { _, _ in try await gate.response("activities").get() }
        let state = IOSAppState(store: store(), dependencies: dependencies, autoLoad: false)
        state.accounts = [account("A", primary: true)]
        let course = ChaoxingCourse(courseName: "Synthetic", teacherName: "", courseId: 10, classId: 20, imageURL: "")
        let scope = IOSCourseActivityList.Scope(session: state.sessionToken, courseID: course.id)
        let list = IOSCourseActivityList()
        let error = ChaoxingError.parseFailed("Synthetic response")

        func load(_ response: Result<[ChaoxingSignActivity], ChaoxingError>, previous: [ChaoxingSignActivity]?) async {
            let task = Task { await list.load(course, session: scope.session, in: state) }
            await gate.waitForRequest("activities")
            #expect(list.phase(for: scope) == .loading(previous: previous))
            await gate.succeed("activities", response)
            await task.value
        }

        await load(.failure(error), previous: nil)
        #expect(list.phase(for: scope) == .failed(message: error.localizedDescription, previous: nil))
        await load(.success([]), previous: nil)
        #expect(list.phase(for: scope) == .loaded([]))
        await load(.failure(error), previous: [])
        #expect(list.phase(for: scope) == .failed(message: error.localizedDescription, previous: []))
        await load(.success([activity]), previous: [])
        await load(.failure(error), previous: [activity])
        #expect(list.phase(for: scope) == .failed(message: error.localizedDescription, previous: [activity]))
        #expect(state.errorMessage == nil)
    }

    @Test func activityListRejectsOldAccountsAndCoursesWhileRefreshing() async {
        let gate = ResponseGate<[ChaoxingSignActivity]>()
        var dependencies = offline
        dependencies.fetchActivities = { client, _ in try await gate.response(client.session.phoneNumber) }
        let state = IOSAppState(store: store(), dependencies: dependencies, autoLoad: false)
        let a = account("A", primary: true), b = account("B")
        state.accounts = [a, b]
        state.activePhoneNumber = a.phoneNumber
        let course = ChaoxingCourse(courseName: "Shared", teacherName: "", courseId: 10, classId: 20, imageURL: "")
        let list = IOSCourseActivityList()
        let scopeA = IOSCourseActivityList.Scope(session: state.sessionToken, courseID: course.id)
        let initial = Task { await list.load(course, session: scopeA.session, in: state) }
        await gate.waitForRequest("A")
        await gate.succeed("A", [activity])
        await initial.value
        #expect(list.phase(for: scopeA) == .loaded([activity]))
        #expect(list.phase(for: .init(session: scopeA.session, courseID: "other")) == .loading(previous: nil))

        let delayed = Task { await list.load(course, session: scopeA.session, in: state) }
        await gate.waitForRequest("A")
        await state.switchActiveAccount(to: b)
        let scopeB = IOSCourseActivityList.Scope(session: state.sessionToken, courseID: course.id)
        #expect(list.phase(for: scopeB) == .loading(previous: nil))
        let current = Task { await list.load(course, session: scopeB.session, in: state) }
        await gate.waitForRequest("B")
        await gate.succeed("B", [])
        await current.value
        await gate.succeed("A", [activity])
        await delayed.value
        await list.load(course, session: scopeA.session, in: state)
        #expect(list.phase(for: scopeB) == .loaded([]))
    }

    @Test func slowActivityRefreshCannotReplaceANewerResultForTheSameCourse() async throws {
        let (requests, calls) = AsyncStream<CheckedContinuation<[ChaoxingSignActivity], Error>>.makeStream()
        var incoming = requests.makeAsyncIterator()
        var dependencies = offline
        dependencies.fetchActivities = { _, _ in try await withCheckedThrowingContinuation { calls.yield($0) } }
        let state = IOSAppState(store: store(), dependencies: dependencies, autoLoad: false)
        state.accounts = [account("A", primary: true)]
        let course = ChaoxingCourse(courseName: "Shared", teacherName: "", courseId: 10, classId: 20, imageURL: "")
        let scope = IOSCourseActivityList.Scope(session: state.sessionToken, courseID: course.id)
        let list = IOSCourseActivityList()
        let first = Task { await list.load(course, session: scope.session, in: state) }
        let older = try #require(await incoming.next())
        let second = Task { await list.load(course, session: scope.session, in: state) }
        let newer = try #require(await incoming.next())
        newer.resume(returning: [activity])
        await second.value
        older.resume(throwing: ChaoxingError.parseFailed("Superseded response"))
        await first.value
        #expect(list.phase(for: scope) == .loaded([activity]))
        calls.finish()
    }
}
