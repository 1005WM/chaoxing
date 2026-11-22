// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

import Foundation
import ChaoxingCore
#if canImport(UIKit)
import UIKit
#endif

/// Injectable service abstraction for `IOSCourseStudyController`, enabling deterministic offline host tests.
public struct IOSCourseStudyDependencies: Sendable {
    public var fetchCourseChapters: @Sendable (ChaoxingHTTPClient, Int64, Int, Int64) async throws -> (cpi: Int64, chapters: [ChaoxingChapterNode])
    public var fetchChapterContent: @Sendable (ChaoxingHTTPClient, Int64, Int, String, Int64) async throws -> ChaoxingChapterContent
    public var sendVideoLog: @Sendable (ChaoxingHTTPClient, ChaoxingVideoTask, Int, ChaoxingVideoDragEvent) async throws -> ChaoxingVideoLogResponse
    public var completeDocumentTask: @Sendable (ChaoxingHTTPClient, ChaoxingChapterAttachment) async throws -> Bool
    public var fetchLiveInfo: @Sendable (ChaoxingHTTPClient, ChaoxingLiveTask) async throws -> ChaoxingLiveInfo
    public var prepareLiveReplaySession: @Sendable (ChaoxingHTTPClient, ChaoxingLiveTask) async throws -> ChaoxingLiveSessionContext
    public var sendLiveHeartbeat: @Sendable (ChaoxingHTTPClient, ChaoxingLiveTask, ChaoxingLiveSessionContext, Bool, Int) async throws -> ChaoxingLiveHeartbeatResponse
    public var sleepMs: @Sendable (Int) async throws -> Void
    public var nowMs: @Sendable () -> Int64

    public init(
        fetchCourseChapters: @escaping @Sendable (ChaoxingHTTPClient, Int64, Int, Int64) async throws -> (cpi: Int64, chapters: [ChaoxingChapterNode]),
        fetchChapterContent: @escaping @Sendable (ChaoxingHTTPClient, Int64, Int, String, Int64) async throws -> ChaoxingChapterContent,
        sendVideoLog: @escaping @Sendable (ChaoxingHTTPClient, ChaoxingVideoTask, Int, ChaoxingVideoDragEvent) async throws -> ChaoxingVideoLogResponse,
        completeDocumentTask: @escaping @Sendable (ChaoxingHTTPClient, ChaoxingChapterAttachment) async throws -> Bool,
        fetchLiveInfo: @escaping @Sendable (ChaoxingHTTPClient, ChaoxingLiveTask) async throws -> ChaoxingLiveInfo = { _, task in
            ChaoxingLiveInfo(
                resolvedLiveId: task.liveId,
                title: task.title,
                liveStatus: task.liveStatus,
                ifReview: task.ifReview,
                duration: task.duration,
                playbackTimelongMs: task.playbackTimelongMs,
                percentValue: task.percentValue,
                streamName: task.streamName,
                vdoid: task.vdoid,
                m3u8URL: task.playURL,
                mp4URL: task.fallbackPlayURL
            )
        },
        prepareLiveReplaySession: @escaping @Sendable (ChaoxingHTTPClient, ChaoxingLiveTask) async throws -> ChaoxingLiveSessionContext = { _, task in
            ChaoxingLiveSessionContext(
                pageURL: task.webPageURL ?? "",
                shortLiveId: task.shortLiveId,
                uInfo: "",
                streamName: task.streamName,
                vdoid: task.vdoid,
                duration: task.duration,
                playURL: task.playURL
            )
        },
        sendLiveHeartbeat: @escaping @Sendable (ChaoxingHTTPClient, ChaoxingLiveTask, ChaoxingLiveSessionContext, Bool, Int) async throws -> ChaoxingLiveHeartbeatResponse = { _, task, _, _, _ in
            ChaoxingLiveHeartbeatResponse(result: 1, accepted: true, duration: task.duration, liveStatus: task.liveStatus)
        },
        sleepMs: @escaping @Sendable (Int) async throws -> Void = { try await Task.sleep(for: .milliseconds($0)) },
        nowMs: @escaping @Sendable () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) }
    ) {
        self.fetchCourseChapters = fetchCourseChapters
        self.fetchChapterContent = fetchChapterContent
        self.sendVideoLog = sendVideoLog
        self.completeDocumentTask = completeDocumentTask
        self.fetchLiveInfo = fetchLiveInfo
        self.prepareLiveReplaySession = prepareLiveReplaySession
        self.sendLiveHeartbeat = sendLiveHeartbeat
        self.sleepMs = sleepMs
        self.nowMs = nowMs
    }

    public static let live = IOSCourseStudyDependencies(
        fetchCourseChapters: { client, courseId, classId, knownCpi in
            try await ChaoxingCourseStudyService.fetchCourseChapters(
                client: client,
                courseId: courseId,
                classId: classId,
                knownCpi: knownCpi
            )
        },
        fetchChapterContent: { client, courseId, classId, chapterId, cpi in
            try await ChaoxingCourseStudyService.fetchChapterContent(
                client: client,
                courseId: courseId,
                classId: classId,
                chapterId: chapterId,
                cpi: cpi
            )
        },
        sendVideoLog: { client, task, playingTimeSec, event in
            try await ChaoxingCourseStudyService.sendVideoLog(
                client: client,
                task: task,
                playingTimeSec: playingTimeSec,
                event: event
            )
        },
        completeDocumentTask: { client, attachment in
            try await ChaoxingCourseStudyService.completeDocumentTask(client: client, task: attachment)
        },
        fetchLiveInfo: { client, task in
            try await ChaoxingCourseStudyService.fetchLiveInfo(client: client, task: task)
        },
        prepareLiveReplaySession: { client, task in
            try await ChaoxingCourseStudyService.prepareLiveReplaySession(client: client, task: task)
        },
        sendLiveHeartbeat: { client, task, session, isStart, watchMomentSec in
            try await ChaoxingCourseStudyService.sendLiveHeartbeat(
                client: client,
                task: task,
                session: session,
                isStart: isStart,
                watchMomentSec: watchMomentSec
            )
        }
    )
}

public struct IOSStudyLogEntry: Identifiable, Sendable, Equatable {
    public let id: Int64
    public let timeLabel: String
    public let message: String
    public let isError: Bool

    public init(id: Int64, timeLabel: String, message: String, isError: Bool = false) {
        self.id = id
        self.timeLabel = timeLabel
        self.message = message
        self.isError = isError
    }
}

public struct IOSCourseChaptersCache: Sendable, Equatable {
    public let cpi: Int64
    public let chapters: [ChaoxingChapterNode]

    public init(cpi: Int64, chapters: [ChaoxingChapterNode]) {
        self.cpi = cpi
        self.chapters = chapters
    }
}

public struct IOSBatchWatchWorker: Identifiable, Sendable, Equatable {
    public let id: String
    public let courseKey: String
    public let courseName: String
    public let chapterKey: String
    public let chapterTitle: String
    public let title: String
    public var duration: Int
    public var confirmedSeconds: Int
    public var displaySeconds: Int
    public let speedMultiplier: Double
    public let requiresFaceCapture: Bool
    public var nextReportCountdownSec: Int
    public var statusText: String
    public let isLiveReplay: Bool
    public let webPageURL: String?
    public var streamURLs: [String]
    public let screenshotURL: String?
    public var isSkipping: Bool

    public init(
        id: String,
        courseKey: String,
        courseName: String,
        chapterKey: String,
        chapterTitle: String,
        title: String,
        duration: Int,
        confirmedSeconds: Int,
        displaySeconds: Int,
        speedMultiplier: Double,
        requiresFaceCapture: Bool = false,
        nextReportCountdownSec: Int = 0,
        statusText: String = "",
        isLiveReplay: Bool = false,
        webPageURL: String? = nil,
        streamURLs: [String] = [],
        screenshotURL: String? = nil,
        isSkipping: Bool = false
    ) {
        self.id = id
        self.courseKey = courseKey
        self.courseName = courseName
        self.chapterKey = chapterKey
        self.chapterTitle = chapterTitle
        self.title = title
        self.duration = duration
        self.confirmedSeconds = confirmedSeconds
        self.displaySeconds = displaySeconds
        self.speedMultiplier = speedMultiplier
        self.requiresFaceCapture = requiresFaceCapture
        self.nextReportCountdownSec = nextReportCountdownSec
        self.statusText = statusText
        self.isLiveReplay = isLiveReplay
        self.webPageURL = webPageURL
        self.streamURLs = streamURLs
        self.screenshotURL = screenshotURL
        self.isSkipping = isSkipping
    }

    public var progressFraction: Double {
        let denom = max(1, duration)
        return min(1.0, max(0.0, Double(displaySeconds) / Double(denom)))
    }
}

struct IOSStudyPlaybackPage: Equatable {
    let courseID: String
    let id: UUID

    init(courseID: String, id: UUID = UUID()) {
        self.courseID = courseID
        self.id = id
    }
}

@MainActor
@Observable
public final class IOSCourseStudyController {
    private static let timeFormatter: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "HH:mm:ss"
        return f
    }()

    private static let chapterScanConcurrency = 6
    private static let maxScanPasses = 2
    private static let default403CooldownSec = 60
    private static let maxLogEntries = 80

    private let clientProvider: @MainActor () -> ChaoxingHTTPClient?
    private let dependencies: IOSCourseStudyDependencies

    private var logSequence: Int64 = 0
    private var accountGeneration: UInt64 = 0
    private var watchGeneration: UInt64 = 0
    private var visiblePlaybackPage: IOSStudyPlaybackPage?
    private var watchTask: Task<Void, Never>?
    private var chapterLoadTasks: [String: Task<Void, Never>] = [:]
    private var contentLoadTasks: [String: Task<Void, Never>] = [:]
    private var activeWorkerTasks: [String: Task<WorkerOutcome, Never>] = [:]
    private var skippedTaskIds: Set<String> = []

    public private(set) var activeAccountPhone: String?
    public private(set) var chaptersByCourse: [String: IOSCourseChaptersCache] = [:]
    public private(set) var loadingChapterCourseIds: Set<String> = []
    public private(set) var chapterErrorsByCourse: [String: String] = [:]
    public private(set) var contentByChapterKey: [String: ChaoxingChapterContent] = [:]
    public private(set) var loadingChapterKeys: Set<String> = []
    public private(set) var completingDocumentIds: Set<String> = []

    // User preferences
    public var onlyPendingChapters: Bool = false
    public var maxConcurrentWatches: Int = 3 {
        didSet {
            let clamped = min(5, max(1, maxConcurrentWatches))
            if maxConcurrentWatches != clamped { maxConcurrentWatches = clamped }
        }
    }
    /// Default OFF: only watches the currently selected course unless the user opts into cross-course watching.
    public var watchAllCoursesAutomatically: Bool = false
    public var autoCompleteDocuments: Bool = true
    /// Toggleable live muted inline AVPlayer previews on iOS/iPadOS worker tiles.
    public var showLiveVideoPreviews: Bool = true {
        didSet {
            if !showLiveVideoPreviews { stopForUnavailablePlayback() }
        }
    }

    // Live watch session telemetry
    public private(set) var isWatching: Bool = false
    public private(set) var activeWatchCourseId: String?
    public private(set) var watchScopeLabel: String = ""
    public private(set) var scanProgress: String = ""
    public private(set) var cooldownRemainingSec: Int = 0
    public private(set) var queueCount: Int = 0
    public private(set) var completedCount: Int = 0
    public private(set) var completedDocsCount: Int = 0
    public private(set) var skippedCount: Int = 0
    public private(set) var totalDiscoveredCount: Int = 0
    public private(set) var activeWorkers: [IOSBatchWatchWorker] = []
    public private(set) var logs: [IOSStudyLogEntry] = []

    private enum BatchPayload: Sendable {
        case video(ChaoxingVideoTask)
        case live(ChaoxingLiveTask)

        var id: String {
            switch self {
            case .video(let v): return v.id
            case .live(let l): return l.id
            }
        }

        var displayName: String {
            switch self {
            case .video(let v): return v.name
            case .live(let l): return l.title
            }
        }
    }

    private struct QueuedItem: Sendable {
        let course: ChaoxingCourse
        let chapter: ChaoxingChapterNode
        let chapterKey: String
        let payload: BatchPayload
    }

    private enum WorkerOutcome: Sendable {
        case passed
        case skipped
        case rateLimited(restSeconds: Int?)
        case failed(String)
    }

    public init(
        clientProvider: @escaping @MainActor () -> ChaoxingHTTPClient?,
        dependencies: IOSCourseStudyDependencies = .live
    ) {
        self.clientProvider = clientProvider
        self.dependencies = dependencies
    }

    public static func chapterKey(for chapter: ChaoxingChapterNode) -> String {
        "\(chapter.courseId)_\(chapter.classId)_\(chapter.id)"
    }

    public static func formatTime(_ seconds: Int) -> String {
        let clamped = max(0, seconds)
        let hours = clamped / 3600
        let mins = (clamped % 3600) / 60
        let secs = clamped % 60
        if hours > 0 {
            return String(format: "%d:%02d:%02d", hours, mins, secs)
        }
        return String(format: "%02d:%02d", mins, secs)
    }

    public static func formatRate(_ rate: Double) -> String {
        rate >= 1.99 ? "2x" : "1x"
    }

    // MARK: - Account Isolation

    /// Cancels all active study tasks and clears cached chapters/content whenever the active account changes or signs out.
    public func onAccountChanged(phoneNumber: String?) {
        let shouldReset = activeAccountPhone != phoneNumber
            || (phoneNumber == nil && (!chaptersByCourse.isEmpty || isWatching || !contentByChapterKey.isEmpty))
        guard shouldReset else { return }

        accountGeneration &+= 1
        watchGeneration &+= 1
        visiblePlaybackPage = nil
        cancelAllStudyTasks()
        activeAccountPhone = phoneNumber
        chaptersByCourse.removeAll()
        loadingChapterCourseIds.removeAll()
        chapterErrorsByCourse.removeAll()
        contentByChapterKey.removeAll()
        loadingChapterKeys.removeAll()
        completingDocumentIds.removeAll()
        isWatching = false
        activeWatchCourseId = nil
        watchScopeLabel = ""
        scanProgress = ""
        cooldownRemainingSec = 0
        queueCount = 0
        completedCount = 0
        completedDocsCount = 0
        skippedCount = 0
        totalDiscoveredCount = 0
        activeWorkers = []
        logs = []
        skippedTaskIds.removeAll()
        updateIdleTimer()
    }

    public func clearLogs() {
        logs.removeAll()
    }

    func setPlaybackPage(_ page: IOSStudyPlaybackPage, visible: Bool) {
        if visible {
            guard visiblePlaybackPage != page else { return }
            visiblePlaybackPage = page
        } else {
            guard visiblePlaybackPage == page else { return }
            visiblePlaybackPage = nil
        }
        stopForUnavailablePlayback()
    }

    func canStartWatch(from page: IOSStudyPlaybackPage) -> Bool {
        showLiveVideoPreviews && visiblePlaybackPage == page
    }

    func playbackBecameUnavailable(on page: IOSStudyPlaybackPage) {
        if visiblePlaybackPage == page { stopForUnavailablePlayback() }
    }

    private func stopForUnavailablePlayback() {
        guard isWatching else { return }
        stopWatch(logStopped: false)
        appendLog("播放画面已离开或不可用，本次同步已停止。")
    }

    // MARK: - Chapters & Content Loading

    public func loadChapters(for course: ChaoxingCourse, forceRefresh: Bool = false) {
        let courseKey = course.id
        if !forceRefresh && chaptersByCourse[courseKey] != nil { return }
        guard let client = clientProvider() else { return }
        let expectedGen = accountGeneration

        chapterLoadTasks[courseKey]?.cancel()
        chapterLoadTasks[courseKey] = Task { [weak self] in
            guard let self else { return }
            self.loadingChapterCourseIds.insert(courseKey)
            self.chapterErrorsByCourse.removeValue(forKey: courseKey)
            defer {
                if self.accountGeneration == expectedGen {
                    self.loadingChapterCourseIds.remove(courseKey)
                    self.chapterLoadTasks.removeValue(forKey: courseKey)
                }
            }
            do {
                let fetched = try await self.dependencies.fetchCourseChapters(
                    client,
                    course.courseId,
                    course.classId,
                    course.cpi ?? 0
                )
                try Task.checkCancellation()
                guard self.accountGeneration == expectedGen else { return }
                self.chaptersByCourse[courseKey] = IOSCourseChaptersCache(cpi: fetched.cpi, chapters: fetched.chapters)
                self.chapterErrorsByCourse.removeValue(forKey: courseKey)
            } catch is CancellationError {
                return
            } catch {
                guard self.accountGeneration == expectedGen else { return }
                self.chapterErrorsByCourse[courseKey] = error.localizedDescription
                self.appendLog("「\(course.courseName)」章节加载失败：\(error.localizedDescription)", isError: true)
            }
        }
    }

    public func loadChapterContent(for course: ChaoxingCourse, chapter: ChaoxingChapterNode, forceRefresh: Bool = false) {
        let key = Self.chapterKey(for: chapter)
        if !forceRefresh && contentByChapterKey[key] != nil { return }
        guard let client = clientProvider() else { return }
        let expectedGen = accountGeneration
        let knownCpi = chaptersByCourse[course.id]?.cpi ?? course.cpi ?? 0

        contentLoadTasks[key]?.cancel()
        contentLoadTasks[key] = Task { [weak self] in
            guard let self else { return }
            self.loadingChapterKeys.insert(key)
            defer {
                if self.accountGeneration == expectedGen {
                    self.loadingChapterKeys.remove(key)
                    self.contentLoadTasks.removeValue(forKey: key)
                }
            }
            do {
                let content = try await self.dependencies.fetchChapterContent(
                    client,
                    chapter.courseId,
                    chapter.classId,
                    chapter.id,
                    knownCpi
                )
                try Task.checkCancellation()
                guard self.accountGeneration == expectedGen else { return }
                self.contentByChapterKey[key] = content
            } catch is CancellationError {
                return
            } catch {
                guard self.accountGeneration == expectedGen else { return }
                self.appendLog("「\(chapter.title)」任务点读取失败：\(error.localizedDescription)", isError: true)
            }
        }
    }

    public func completeDocument(courseKey: String, chapter: ChaoxingChapterNode, attachment: ChaoxingChapterAttachment) {
        guard attachment.canCompleteAsDocument, let client = clientProvider() else { return }
        guard !completingDocumentIds.contains(attachment.id) else { return }
        let expectedGen = accountGeneration
        let key = Self.chapterKey(for: chapter)

        completingDocumentIds.insert(attachment.id)
        Task { [weak self] in
            guard let self else { return }
            defer {
                if self.accountGeneration == expectedGen {
                    self.completingDocumentIds.remove(attachment.id)
                }
            }
            do {
                let ok = try await self.dependencies.completeDocumentTask(client, attachment)
                try Task.checkCancellation()
                guard self.accountGeneration == expectedGen else { return }
                if ok {
                    self.markAttachmentPassedLocally(courseKey: courseKey, chapterKey: key, chapterId: chapter.id, attachmentId: attachment.id)
                    self.appendLog("📄 文档已完成：\(attachment.name)")
                } else {
                    self.appendLog("📄 文档上报未通过：\(attachment.name)", isError: true)
                }
            } catch is CancellationError {
                return
            } catch {
                guard self.accountGeneration == expectedGen else { return }
                self.appendLog("📄 文档完成失败（\(attachment.name)）：\(error.localizedDescription)", isError: true)
            }
        }
    }

    // MARK: - Single & Multi-Way Parallel Watch Entrypoints

    @discardableResult
    func startSingleVideoWatch(course: ChaoxingCourse, chapter: ChaoxingChapterNode, video: ChaoxingVideoTask, from page: IOSStudyPlaybackPage) -> Task<Void, Never>? {
        guard page.courseID == course.id, canStartWatch(from: page),
              video.videoPlayURL?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty == false else { return nil }
        guard let client = clientProvider() else {
            appendLog("当前未登录，无法启动视频挂机", isError: true)
            return nil
        }
        guard !video.isPassed else {
            appendLog("视频「\(video.name)」已完成，无需重复挂机")
            return nil
        }
        guard video.isReportableTask else {
            appendLog("视频「\(video.name)」缺少上报凭证或不是任务点", isError: true)
            return nil
        }
        if video.requiresFaceCapture {
            appendLog("⚠️ 提示：「\(video.name)」启用了章节人脸抓拍，可能需要学习通客户端配合核验", isError: true)
        }

        stopWatch(logStopped: false)
        let expectedGen = watchGeneration
        isWatching = true
        activeWatchCourseId = course.id
        watchScopeLabel = "单视频：\(video.name)"
        scanProgress = ""
        cooldownRemainingSec = 0
        queueCount = 0
        completedCount = 0
        completedDocsCount = 0
        skippedCount = 0
        totalDiscoveredCount = 1
        activeWorkers = []
        updateIdleTimer()
        appendLog("🎬 开始合法挂机单视频：\(video.name)（\(Self.formatRate(video.allowedMaxRate))）")

        let item = QueuedItem(course: course, chapter: chapter, chapterKey: Self.chapterKey(for: chapter), payload: .video(video))
        let task = Task { [weak self] in
            guard let self else { return }
            defer { self.finishWatchSession(expectedGen: expectedGen) }
            let outcome = await self.runLegalVideoWorker(client: client, item: item, video: video, expectedGen: expectedGen)
            guard self.watchGeneration == expectedGen else { return }
            switch outcome {
            case .passed:
                self.completedCount = 1
                self.markVideoPassedLocally(courseKey: course.id, chapterKey: item.chapterKey, chapterId: chapter.id, videoId: video.id, endSec: video.effectiveClipEnd)
                self.appendLog("✅ 视频已通过：\(video.name)")
            case .skipped:
                self.skippedCount = 1
            case .rateLimited:
                self.skippedCount = 1
                self.appendLog("⚠️ 触发服务端 403 限流，请稍后再试：\(video.name)", isError: true)
            case .failed(let reason):
                self.skippedCount = 1
                self.appendLog("❌ 视频挂机失败（\(video.name)）：\(reason)", isError: true)
            }
        }
        watchTask = task
        return task
    }

    @discardableResult
    func startSingleLiveWatch(course: ChaoxingCourse, chapter: ChaoxingChapterNode, live: ChaoxingLiveTask, from page: IOSStudyPlaybackPage) -> Task<Void, Never>? {
        guard page.courseID == course.id, canStartWatch(from: page), !live.allPlayURLs.isEmpty else { return nil }
        guard let client = clientProvider() else {
            appendLog("当前未登录，无法启动直播回放挂机", isError: true)
            return nil
        }
        guard !live.isPassed else {
            appendLog("直播回放「\(live.title)」已完成，无需重复挂机")
            return nil
        }
        guard live.isReportableTask else {
            appendLog("直播回放「\(live.title)」当前不可上报（\(live.statusDescription)）", isError: true)
            return nil
        }

        stopWatch(logStopped: false)
        let expectedGen = watchGeneration
        isWatching = true
        activeWatchCourseId = course.id
        watchScopeLabel = "单场直播回放：\(live.title)"
        scanProgress = ""
        cooldownRemainingSec = 0
        queueCount = 0
        completedCount = 0
        completedDocsCount = 0
        skippedCount = 0
        totalDiscoveredCount = 1
        activeWorkers = []
        updateIdleTimer()
        appendLog("📺 开始合法挂机直播回放：\(live.title)（zhibo.chaoxing.com 30s 真实时钟心跳）")

        let item = QueuedItem(course: course, chapter: chapter, chapterKey: Self.chapterKey(for: chapter), payload: .live(live))
        let task = Task { [weak self] in
            guard let self else { return }
            defer { self.finishWatchSession(expectedGen: expectedGen) }
            let outcome = await self.runLegalLiveWorker(client: client, item: item, live: live, expectedGen: expectedGen)
            guard self.watchGeneration == expectedGen else { return }
            switch outcome {
            case .passed:
                self.completedCount = 1
                self.markLivePassedLocally(courseKey: course.id, chapterKey: item.chapterKey, chapterId: chapter.id, liveId: live.id, durationSec: live.duration)
                self.appendLog("✅ 直播回放已通过：\(live.title)")
            case .skipped:
                self.skippedCount = 1
            case .rateLimited:
                self.skippedCount = 1
                self.appendLog("⚠️ 直播回放触发 403 限流，请稍后再试：\(live.title)", isError: true)
            case .failed(let reason):
                self.skippedCount = 1
                self.appendLog("❌ 直播回放挂机失败（\(live.title)）：\(reason)", isError: true)
            }
        }
        watchTask = task
        return task
    }

    /// Streams discovered unfinished videos, audios, and external `zhibo.chaoxing.com` live replays
    /// immediately into up to `maxConcurrentWatches` parallel workers.
    @discardableResult
    func startWatch(primaryCourse: ChaoxingCourse, allCourses: [ChaoxingCourse], from page: IOSStudyPlaybackPage) -> Task<Void, Never>? {
        guard page.courseID == primaryCourse.id, canStartWatch(from: page) else { return nil }
        guard let client = clientProvider() else {
            appendLog("当前未登录，无法开始看课", isError: true)
            return nil
        }

        stopWatch(logStopped: false)
        let expectedGen = watchGeneration
        let includeOthers = watchAllCoursesAutomatically
        let targetCourses: [ChaoxingCourse] = includeOthers
            ? ([primaryCourse] + allCourses.filter { $0.id != primaryCourse.id })
            : [primaryCourse]
        let configuredConcurrency = min(5, max(1, maxConcurrentWatches))
        let autoDocs = autoCompleteDocuments
        let scopeLabel = (includeOthers && targetCourses.count > 1)
            ? "全部课程 (\(targetCourses.count) 门)"
            : "当前课程：\(primaryCourse.courseName)"

        isWatching = true
        activeWatchCourseId = primaryCourse.id
        watchScopeLabel = scopeLabel
        scanProgress = "快速扫描 \(scopeLabel)…"
        cooldownRemainingSec = 0
        queueCount = 0
        completedCount = 0
        completedDocsCount = 0
        skippedCount = 0
        totalDiscoveredCount = 0
        activeWorkers = []
        updateIdleTimer()
        appendLog("🎬 启动同时看课：\(scopeLabel)（最多 \(configuredConcurrency) 路并行\(autoDocs ? "，顺便完成文档" : "")）")

        let task = Task { [weak self] in
            guard let self else { return }
            defer { self.finishWatchSession(expectedGen: expectedGen) }

            var handledIds: Set<String> = []
            var completedDocIds: Set<String> = []
            var effectiveConcurrency = configuredConcurrency
            var cooldownUntilMs: Int64 = 0

            for pass in 1...Self.maxScanPasses {
                guard !Task.isCancelled, self.watchGeneration == expectedGen else { return }
                var pendingQueue: [QueuedItem] = []
                var discoveredInPass = 0
                var scanningDone = false
                let passPrefix = pass > 1 ? "第 \(pass) 轮复查 · " : ""

                // Fast producer task: 6-way bounded chapter scanning, skipping confirmed-completed chapters (`pendingJobCount == 0`).
                let producer = Task { @MainActor [weak self] in
                    guard let self else { return }
                    for (cIdx, course) in targetCourses.enumerated() {
                        if Task.isCancelled || self.watchGeneration != expectedGen { return }
                        self.scanProgress = "\(passPrefix)读取章节 \(cIdx + 1)/\(targetCourses.count)：\(course.courseName)"
                        let chapterCache: IOSCourseChaptersCache
                        do {
                            let knownCpi = self.chaptersByCourse[course.id]?.cpi ?? course.cpi ?? 0
                            let fetched = try await self.dependencies.fetchCourseChapters(client, course.courseId, course.classId, knownCpi)
                            try Task.checkCancellation()
                            guard self.watchGeneration == expectedGen else { return }
                            chapterCache = IOSCourseChaptersCache(cpi: fetched.cpi, chapters: fetched.chapters)
                            self.chaptersByCourse[course.id] = chapterCache
                            self.chapterErrorsByCourse.removeValue(forKey: course.id)
                        } catch is CancellationError {
                            return
                        } catch {
                            if self.watchGeneration == expectedGen {
                                self.appendLog("[\(course.courseName)] 读取章节失败：\(error.localizedDescription)", isError: true)
                            }
                            continue
                        }

                        let candidates = chapterCache.chapters.filter { ($0.pendingJobCount ?? 1) > 0 }
                        if candidates.isEmpty {
                            continue
                        }

                        var offset = 0
                        while offset < candidates.count && !Task.isCancelled && self.watchGeneration == expectedGen {
                            let end = min(candidates.count, offset + Self.chapterScanConcurrency)
                            let slice = Array(candidates[offset..<end])
                            self.scanProgress = "\(passPrefix)[\(course.courseName)] 扫描 \(end)/\(candidates.count) 节…"

                            let fetchedSlice = await withTaskGroup(of: (Int, ChaoxingChapterNode, ChaoxingChapterContent?).self) { group in
                                for (idx, ch) in slice.enumerated() {
                                    group.addTask { [deps = self.dependencies] in
                                        let content = try? await deps.fetchChapterContent(client, ch.courseId, ch.classId, ch.id, chapterCache.cpi)
                                        return (idx, ch, content)
                                    }
                                }
                                var collected: [(Int, ChaoxingChapterNode, ChaoxingChapterContent?)] = []
                                for await entry in group { collected.append(entry) }
                                return collected.sorted { $0.0 < $1.0 }
                            }

                            guard !Task.isCancelled, self.watchGeneration == expectedGen else { return }
                            for (_, ch, contentOpt) in fetchedSlice {
                                guard let content = contentOpt else { continue }
                                let chKey = Self.chapterKey(for: ch)
                                self.contentByChapterKey[chKey] = content

                                // 1. Enqueue playable videos/audios first.
                                for video in content.videos {
                                    guard video.isReportableTask, !video.isPassed, !handledIds.contains(video.id) else { continue }
                                    if video.requiresFaceCapture {
                                        handledIds.insert(video.id)
                                        self.skippedCount += 1
                                        self.appendLog("⚠️ 跳过人脸抓拍视频：[\(course.courseName)] \(video.name)", isError: true)
                                        continue
                                    }
                                    handledIds.insert(video.id)
                                    pendingQueue.append(QueuedItem(course: course, chapter: ch, chapterKey: chKey, payload: .video(video)))
                                    discoveredInPass += 1
                                    self.totalDiscoveredCount += 1
                                    self.queueCount = pendingQueue.count
                                }

                                // 2. Enqueue external `zhibo.chaoxing.com` live replays (supports full parallel playback!).
                                for live in content.liveTasks {
                                    guard live.isReportableTask, !live.isPassed, !handledIds.contains(live.id) else { continue }
                                    handledIds.insert(live.id)
                                    pendingQueue.append(QueuedItem(course: course, chapter: ch, chapterKey: chKey, payload: .live(live)))
                                    discoveredInPass += 1
                                    self.totalDiscoveredCount += 1
                                    self.queueCount = pendingQueue.count
                                }

                                // 3. Optionally auto-complete eligible PDF/DOC/PPT document task points.
                                if autoDocs {
                                    for doc in content.otherAttachments where doc.canCompleteAsDocument && !completedDocIds.contains(doc.id) {
                                        guard self.isCurrentWatch(expectedGen) else { return }
                                        completedDocIds.insert(doc.id)
                                        if let ok = try? await self.dependencies.completeDocumentTask(client, doc), ok, self.watchGeneration == expectedGen {
                                            self.completedDocsCount += 1
                                            self.markAttachmentPassedLocally(courseKey: course.id, chapterKey: chKey, chapterId: ch.id, attachmentId: doc.id)
                                            self.appendLog("📄 已自动完成文档：[\(course.courseName)] \(doc.name)")
                                        }
                                    }
                                }
                            }
                            offset = end
                        }
                    }
                    scanningDone = true
                    if self.watchGeneration == expectedGen {
                        self.scanProgress = ""
                    }
                }
                defer { producer.cancel() }

                // Streaming parallel scheduler loop.
                while !Task.isCancelled && self.watchGeneration == expectedGen {
                    let now = self.dependencies.nowMs()
                    let remCooldown = cooldownUntilMs > now ? Int(ceil(Double(cooldownUntilMs - now) / 1000.0)) : 0
                    self.cooldownRemainingSec = remCooldown
                    let canLaunchNow = remCooldown <= 0

                    while canLaunchNow
                        && self.activeWorkerTasks.count < effectiveConcurrency
                        && !pendingQueue.isEmpty
                        && !Task.isCancelled
                        && self.watchGeneration == expectedGen
                    {
                        let item = pendingQueue.removeFirst()
                        self.queueCount = pendingQueue.count
                        let itemId = item.payload.id

                        let workerTask = Task<WorkerOutcome, Never> { [weak self] in
                            guard let self else { return .skipped }
                            let outcome: WorkerOutcome
                            switch item.payload {
                            case .video(let v):
                                outcome = await self.runLegalVideoWorker(client: client, item: item, video: v, expectedGen: expectedGen)
                            case .live(let live):
                                outcome = await self.runLegalLiveWorker(client: client, item: item, live: live, expectedGen: expectedGen)
                            }

                            guard self.watchGeneration == expectedGen else { return .skipped }
                            self.activeWorkerTasks.removeValue(forKey: itemId)
                            switch outcome {
                            case .passed:
                                self.completedCount += 1
                                switch item.payload {
                                case .video(let v):
                                    self.markVideoPassedLocally(courseKey: item.course.id, chapterKey: item.chapterKey, chapterId: item.chapter.id, videoId: v.id, endSec: v.effectiveClipEnd)
                                    self.appendLog("✅ 视频已通过 (\(self.completedCount)/\(self.totalDiscoveredCount))：[\(item.course.courseName)] \(v.name)")
                                case .live(let live):
                                    self.markLivePassedLocally(courseKey: item.course.id, chapterKey: item.chapterKey, chapterId: item.chapter.id, liveId: live.id, durationSec: live.duration)
                                    self.appendLog("✅ 直播回放已通过 (\(self.completedCount)/\(self.totalDiscoveredCount))：[\(item.course.courseName)] \(live.title)")
                                }
                            case .skipped:
                                self.skippedCount += 1
                            case .rateLimited(let restSeconds):
                                if effectiveConcurrency > 1 {
                                    effectiveConcurrency -= 1
                                    self.appendLog("⚠️ 服务端 403 限流，自动降并发至 \(effectiveConcurrency) 路并将 [\(item.payload.displayName)] 放回队尾", isError: true)
                                }
                                let waitSec = min(600, max(Self.default403CooldownSec, restSeconds ?? Self.default403CooldownSec))
                                let targetUntil = self.dependencies.nowMs() + Int64(waitSec) * 1000
                                cooldownUntilMs = max(cooldownUntilMs, targetUntil)
                                self.cooldownRemainingSec = waitSec
                                self.appendLog("⚠️ 冷却 \(waitSec) 秒后继续补位队列", isError: true)
                                pendingQueue.append(item)
                                self.queueCount = pendingQueue.count
                            case .failed(let reason):
                                self.skippedCount += 1
                                self.appendLog("❌ 跳过异常任务 [\(item.payload.displayName)]：\(reason)", isError: true)
                            }
                            return outcome
                        }
                        self.activeWorkerTasks[itemId] = workerTask
                    }

                    if scanningDone && pendingQueue.isEmpty && self.activeWorkerTasks.isEmpty {
                        break
                    }
                    try? await self.dependencies.sleepMs(100)
                    await Task.yield()
                }

                if discoveredInPass == 0 {
                    break
                }
            }

            if !Task.isCancelled && self.watchGeneration == expectedGen {
                if self.totalDiscoveredCount == 0 && self.completedDocsCount == 0 {
                    self.appendLog("[\(scopeLabel)] 未发现待完成的视频、直播回放或文档任务点（全部已通过或仅剩测验 ✅）")
                } else {
                    let docPart = self.completedDocsCount > 0 ? "，自动完成文档 \(self.completedDocsCount)" : ""
                    let skipPart = self.skippedCount > 0 ? "，跳过/异常 \(self.skippedCount)" : ""
                    self.appendLog("🎉 [\(scopeLabel)] 同步看课结束：视频/直播通过 \(self.completedCount)\(docPart)\(skipPart)")
                }
            }
        }
        watchTask = task
        return task
    }

    public func skipWorker(taskId: String) {
        skippedTaskIds.insert(taskId)
        updateWorker(id: taskId) { worker in
            worker.isSkipping = true
            worker.statusText = "正在跳过…"
        }
        if let task = activeWorkerTasks[taskId] {
            task.cancel()
        }
        if let worker = activeWorkers.first(where: { $0.id == taskId }) {
            appendLog("⏭️ 已手动跳过：[\(worker.courseName)] \(worker.title)")
        }
    }

    public func stopWatch(logStopped: Bool = true) {
        let wasWatching = isWatching || watchTask != nil
        watchGeneration &+= 1
        watchTask?.cancel()
        watchTask = nil
        for (_, task) in activeWorkerTasks {
            task.cancel()
        }
        activeWorkerTasks.removeAll()
        skippedTaskIds.removeAll()
        isWatching = false
        activeWatchCourseId = nil
        scanProgress = ""
        cooldownRemainingSec = 0
        queueCount = 0
        activeWorkers = []
        updateIdleTimer()
        if logStopped && wasWatching {
            appendLog("⏹️ 已手动停止看课")
        }
    }

    // MARK: - Worker Loops

    private func isCurrentWatch(_ expectedGeneration: UInt64) -> Bool {
        isWatching && !Task.isCancelled && watchGeneration == expectedGeneration
    }

    private func runLegalVideoWorker(
        client: ChaoxingHTTPClient,
        item: QueuedItem,
        video: ChaoxingVideoTask,
        expectedGen: UInt64
    ) async -> WorkerOutcome {
        guard isCurrentWatch(expectedGen) else { return .skipped }
        guard video.videoPlayURL?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty == false else {
            return .failed("没有可播放的视频地址")
        }
        let clipStart = video.effectiveClipStart
        let clipEnd = video.effectiveClipEnd
        guard clipEnd > clipStart else {
            return .failed("视频时长为 0")
        }

        let headSec = video.headOffsetMs / 1000
        var reportedSec = (!video.isPassed && headSec > clipStart && headSec < max(clipStart + 1, clipEnd - 3)) ? headSec : clipStart
        let intervalSec = min(60, max(15, video.reportTimeInterval))
        let legalRate = video.allowedMaxRate
        var completedPasses = 0
        let maxPasses = 2
        var consecutiveErrors = 0

        upsertWorker(
            IOSBatchWatchWorker(
                id: video.id,
                courseKey: item.course.id,
                courseName: item.course.courseName,
                chapterKey: item.chapterKey,
                chapterTitle: item.chapter.title,
                title: video.name,
                duration: clipEnd,
                confirmedSeconds: reportedSec,
                displaySeconds: reportedSec,
                speedMultiplier: legalRate,
                requiresFaceCapture: video.requiresFaceCapture,
                nextReportCountdownSec: intervalSec,
                statusText: "发送起播心跳 play(3)…",
                isLiveReplay: false,
                webPageURL: nil,
                streamURLs: video.videoPlayURL.flatMap { $0.isEmpty ? nil : [$0] } ?? [],
                screenshotURL: video.screenshotURL
            )
        )
        defer {
            if watchGeneration == expectedGen {
                removeWorker(id: video.id)
                skippedTaskIds.remove(video.id)
            }
        }

        do {
            let startRes = try await dependencies.sendVideoLog(client, video, reportedSec, .play)
            if startRes.isPassed { return .passed }
        } catch is CancellationError {
            return .skipped
        } catch ChaoxingError.networkError(let code, let msg) where code == 403 {
            return .rateLimited(restSeconds: extractRestSeconds(from: msg))
        } catch {
            return .failed(error.localizedDescription)
        }

        while !Task.isCancelled && watchGeneration == expectedGen {
            if skippedTaskIds.contains(video.id) { return .skipped }
            let remainingVideoSec = max(1, clipEnd - reportedSec)
            let stepRealSec = min(intervalSec, max(1, Int(ceil(Double(remainingVideoSec) / legalRate))))

            for sec in 1...stepRealSec {
                if Task.isCancelled || watchGeneration != expectedGen || skippedTaskIds.contains(video.id) {
                    return .skipped
                }
                do {
                    try await dependencies.sleepMs(1_000)
                    await Task.yield()
                } catch {
                    return .skipped
                }
                guard isCurrentWatch(expectedGen) else { return .skipped }
                let visualSec = min(clipEnd, reportedSec + Int(floor(Double(sec) * legalRate)))
                updateWorker(id: video.id) { w in
                    w.displaySeconds = visualSec
                    w.confirmedSeconds = reportedSec
                    w.nextReportCountdownSec = max(0, stepRealSec - sec)
                    w.statusText = "合法播放中 (\(Self.formatRate(legalRate)))"
                }
            }

            let nextSec = min(clipEnd, reportedSec + Int(ceil(Double(stepRealSec) * legalRate)))
            let isAtEnd = nextSec >= clipEnd
            let event: ChaoxingVideoDragEvent = isAtEnd ? .ended : .playing

            updateWorker(id: video.id) { w in
                w.displaySeconds = nextSec
                w.nextReportCountdownSec = 0
                w.statusText = isAtEnd ? "发送完播确认…" : "上报播放心跳…"
            }

            do {
                let res = try await dependencies.sendVideoLog(client, video, nextSec, event)
                guard isCurrentWatch(expectedGen) else { return .skipped }
                consecutiveErrors = 0
                reportedSec = nextSec
                updateWorker(id: video.id) { w in
                    w.confirmedSeconds = reportedSec
                    w.displaySeconds = reportedSec
                    w.nextReportCountdownSec = intervalSec
                    w.statusText = "已确认 \(Self.formatTime(reportedSec)) / \(Self.formatTime(clipEnd))"
                }
                if res.isPassed { return .passed }
                if res.videoTimeLimit || res.hasJobLimit {
                    return .failed("触发服务端学习时长或任务点上限")
                }
                if isAtEnd {
                    completedPasses += 1
                    if completedPasses >= maxPasses {
                        return .failed("已完整播放 \(maxPasses) 遍但服务端尚未标记通过")
                    }
                    reportedSec = clipStart
                    _ = try? await dependencies.sendVideoLog(client, video, reportedSec, .play)
                }
            } catch is CancellationError {
                return .skipped
            } catch ChaoxingError.networkError(let code, let msg) where code == 403 {
                return .rateLimited(restSeconds: extractRestSeconds(from: msg))
            } catch {
                guard isCurrentWatch(expectedGen) else { return .skipped }
                consecutiveErrors += 1
                appendLog("⚠️ [\(video.name)] 心跳重试 (\(consecutiveErrors)/3)：\(error.localizedDescription)", isError: true)
                if consecutiveErrors >= 3 {
                    return .failed(error.localizedDescription)
                }
                try? await dependencies.sleepMs(5_000)
            }
        }
        return .skipped
    }

    private func runLegalLiveWorker(
        client: ChaoxingHTTPClient,
        item: QueuedItem,
        live: ChaoxingLiveTask,
        expectedGen: UInt64
    ) async -> WorkerOutcome {
        guard isCurrentWatch(expectedGen) else { return .skipped }
        guard !live.allPlayURLs.isEmpty else { return .failed("没有可播放的回放地址") }
        upsertWorker(
            IOSBatchWatchWorker(
                id: live.id,
                courseKey: item.course.id,
                courseName: item.course.courseName,
                chapterKey: item.chapterKey,
                chapterTitle: item.chapter.title,
                title: live.title,
                duration: max(1, live.duration),
                confirmedSeconds: live.watchedSeconds,
                displaySeconds: live.watchedSeconds,
                speedMultiplier: 1.0,
                requiresFaceCapture: false,
                nextReportCountdownSec: 29,
                statusText: "关联课程并初始化直播回放…",
                isLiveReplay: true,
                webPageURL: live.webPageURL,
                streamURLs: live.allPlayURLs,
                screenshotURL: nil
            )
        )
        defer {
            if watchGeneration == expectedGen {
                removeWorker(id: live.id)
                skippedTaskIds.remove(live.id)
            }
        }

        var session: ChaoxingLiveSessionContext
        do {
            session = try await dependencies.prepareLiveReplaySession(client, live)
        } catch is CancellationError {
            return .skipped
        } catch {
            return .failed("初始化直播回放失败：\(error.localizedDescription)")
        }
        guard !Task.isCancelled, watchGeneration == expectedGen, !skippedTaskIds.contains(live.id) else {
            return .skipped
        }

        let refreshedInfo = try? await dependencies.fetchLiveInfo(client, live)
        guard isCurrentWatch(expectedGen) else { return .skipped }
        var targetDuration = max(refreshedInfo?.duration ?? 0, session.duration, live.duration)
        var serverConfirmedSec = max(live.watchedSeconds, (refreshedInfo?.playbackTimelongMs ?? 0) / 1000)
        var estimatedSec = serverConfirmedSec
        var serverPercent = max(live.percentValue, refreshedInfo?.percentValue ?? 0)
        let thresholdPercent = live.rt * 100.0

        var mergedStreams: [String] = []
        for raw in [refreshedInfo?.m3u8URL, session.playURL, refreshedInfo?.mp4URL, live.playURL, live.fallbackPlayURL] {
            if let trimmed = raw?.trimmingCharacters(in: .whitespacesAndNewlines),
               !trimmed.isEmpty,
               !mergedStreams.contains(trimmed) {
                mergedStreams.append(trimmed)
            }
        }
        updateWorker(id: live.id) { w in
            w.duration = max(1, targetDuration)
            w.confirmedSeconds = serverConfirmedSec
            w.displaySeconds = estimatedSec
            w.streamURLs = mergedStreams
            w.statusText = "发送直播起播心跳 (isStart=0)…"
        }

        if targetDuration > 0 && serverPercent >= thresholdPercent {
            if let checked = try? await dependencies.fetchChapterContent(client, live.courseId, live.classId, live.chapterId, live.cpi),
               checked.liveTasks.first(where: { $0.id == live.id })?.isPassed == true {
                return .passed
            }
        }

        do {
            guard isCurrentWatch(expectedGen) else { return .skipped }
            let startRes = try await dependencies.sendLiveHeartbeat(client, live, session, true, estimatedSec)
            guard isCurrentWatch(expectedGen) else { return .skipped }
            if startRes.duration > targetDuration {
                targetDuration = startRes.duration
                updateWorker(id: live.id) { w in w.duration = max(1, targetDuration) }
            }
            appendLog("📺 [\(live.title)] 起播心跳已接收 (isStart=0，已入库 \(String(format: "%.1f%%", serverPercent)))")
        } catch is CancellationError {
            return .skipped
        } catch ChaoxingError.networkError(let code, _) where code == 403 {
            return .rateLimited(restSeconds: Self.default403CooldownSec)
        } catch {
            return .failed("起播心跳失败：\(error.localizedDescription)")
        }

        var tickCount = 0
        var postThresholdTicks = 0
        var consecutiveErrors = 0
        var ticksSinceServerAdvance = 0
        var sessionRecoveryAttempts = 0
        let maxTicks = max(240, targetDuration > 0 ? (targetDuration / 30) + 30 : 240)

        while !Task.isCancelled && watchGeneration == expectedGen && tickCount < maxTicks {
            if skippedTaskIds.contains(live.id) { return .skipped }
            // Official `zhibo.chaoxing.com` cadence: watchTime == 1 (isStart=0) -> 30 (+29s) -> 60 (+30s)...
            let stepSec = tickCount == 0 ? 29 : 30

            for sec in 1...stepSec {
                if Task.isCancelled || watchGeneration != expectedGen || skippedTaskIds.contains(live.id) {
                    return .skipped
                }
                do {
                    try await dependencies.sleepMs(1_000)
                    await Task.yield()
                } catch {
                    return .skipped
                }
                guard isCurrentWatch(expectedGen) else { return .skipped }
                let visualSec = targetDuration > 0 ? min(targetDuration, estimatedSec + sec) : (estimatedSec + sec)
                let pctText = serverPercent > 0
                    ? " · 服务端入库 \(String(format: "%.1f%%", serverPercent))"
                    : " · 每60s入库同步"
                updateWorker(id: live.id) { w in
                    w.confirmedSeconds = serverConfirmedSec
                    w.displaySeconds = visualSec
                    w.nextReportCountdownSec = max(0, stepSec - sec)
                    w.statusText = "直播回放合法同步中\(pctText)"
                }
            }

            let candidateMoment = targetDuration > 0 ? min(targetDuration, estimatedSec + stepSec) : (estimatedSec + stepSec)
            updateWorker(id: live.id) { w in
                w.displaySeconds = candidateMoment
                w.nextReportCountdownSec = 0
                w.statusText = "上报直播心跳 (isStart=1)…"
            }

            do {
                let hbRes = try await dependencies.sendLiveHeartbeat(client, live, session, false, candidateMoment)
                guard isCurrentWatch(expectedGen) else { return .skipped }
                if hbRes.duration > targetDuration {
                    targetDuration = hbRes.duration
                    updateWorker(id: live.id) { w in w.duration = max(1, targetDuration) }
                }
                consecutiveErrors = 0
                tickCount += 1
                ticksSinceServerAdvance += 1
                estimatedSec = max(serverConfirmedSec, candidateMoment)
            } catch is CancellationError {
                return .skipped
            } catch ChaoxingError.networkError(let code, _) where code == 403 {
                return .rateLimited(restSeconds: Self.default403CooldownSec)
            } catch {
                guard isCurrentWatch(expectedGen) else { return .skipped }
                consecutiveErrors += 1
                appendLog("⚠️ [\(live.title)] 直播心跳重试 (\(consecutiveErrors)/4)：\(error.localizedDescription)", isError: true)
                if consecutiveErrors >= 4 {
                    return .failed("连续心跳失败：\(error.localizedDescription)")
                }
                updateWorker(id: live.id) { w in
                    w.nextReportCountdownSec = 5
                    w.statusText = "心跳波动，稍后重试…"
                }
                try? await dependencies.sleepMs(5_000)
                continue
            }

            let requiredSec = targetDuration > 0 ? Int(ceil(Double(targetDuration) * live.rt)) : 0
            let nearCompletion = requiredSec > 0 && estimatedSec >= requiredSec
            if tickCount % 2 == 0 || nearCompletion {
                if let info = try? await dependencies.fetchLiveInfo(client, live) {
                    guard isCurrentWatch(expectedGen) else { return .skipped }
                    if info.duration > targetDuration {
                        targetDuration = info.duration
                    }
                    let serverWatched = info.playbackTimelongMs / 1000
                    serverPercent = info.percentValue
                    if serverWatched > serverConfirmedSec {
                        serverConfirmedSec = serverWatched
                        estimatedSec = max(estimatedSec, serverConfirmedSec)
                        ticksSinceServerAdvance = 0
                        appendLog("📺 [\(live.title)] 服务端已入库 \(Self.formatTime(serverConfirmedSec)) (\(String(format: "%.1f%%", serverPercent)))")
                    }
                    updateWorker(id: live.id) { w in
                        w.duration = max(1, targetDuration)
                        w.confirmedSeconds = serverConfirmedSec
                        w.displaySeconds = estimatedSec
                        w.nextReportCountdownSec = 30
                        w.statusText = "直播回放 · 服务端已入库 \(String(format: "%.1f%%", serverPercent))"
                    }
                    let refreshedRequired = targetDuration > 0 ? Int(ceil(Double(targetDuration) * live.rt)) : 0
                    let reachedTarget = serverPercent >= thresholdPercent || (refreshedRequired > 0 && serverConfirmedSec >= refreshedRequired)
                    if reachedTarget {
                        postThresholdTicks += 1
                        if let refreshedChapter = try? await dependencies.fetchChapterContent(client, live.courseId, live.classId, live.chapterId, live.cpi),
                           refreshedChapter.liveTasks.first(where: { $0.id == live.id })?.isPassed == true {
                            return .passed
                        }
                        if postThresholdTicks >= 2 {
                            return .passed
                        }
                    } else if ticksSinceServerAdvance >= 6 && sessionRecoveryAttempts < 2 {
                        sessionRecoveryAttempts += 1
                        ticksSinceServerAdvance = 0
                        appendLog("⚠️ [\(live.title)] 3 分钟内服务端入库未更新，正在重新关联会话并重发 isStart=0…", isError: true)
                        if let refreshedSession = try? await dependencies.prepareLiveReplaySession(client, live) {
                            guard isCurrentWatch(expectedGen) else { return .skipped }
                            session = refreshedSession
                            _ = try? await dependencies.sendLiveHeartbeat(client, live, session, true, estimatedSec)
                        }
                    }
                }
            }
        }
        return Task.isCancelled ? .skipped : .failed("达到最大挂机时长仍未收到服务端通过确认")
    }

    // MARK: - Local Cache Updates & Helpers

    private func finishWatchSession(expectedGen: UInt64) {
        guard watchGeneration == expectedGen else { return }
        for (_, task) in activeWorkerTasks { task.cancel() }
        activeWorkerTasks.removeAll()
        activeWorkers = []
        isWatching = false
        activeWatchCourseId = nil
        scanProgress = ""
        cooldownRemainingSec = 0
        queueCount = 0
        watchTask = nil
        updateIdleTimer()
    }

    private func cancelAllStudyTasks() {
        watchTask?.cancel()
        watchTask = nil
        for (_, task) in activeWorkerTasks { task.cancel() }
        activeWorkerTasks.removeAll()
        for (_, task) in chapterLoadTasks { task.cancel() }
        chapterLoadTasks.removeAll()
        for (_, task) in contentLoadTasks { task.cancel() }
        contentLoadTasks.removeAll()
    }

    private func updateIdleTimer() {
        #if canImport(UIKit)
        UIApplication.shared.isIdleTimerDisabled = isWatching
        #endif
    }

    private func upsertWorker(_ worker: IOSBatchWatchWorker) {
        if let idx = activeWorkers.firstIndex(where: { $0.id == worker.id }) {
            activeWorkers[idx] = worker
        } else {
            activeWorkers.append(worker)
        }
    }

    private func updateWorker(id: String, mutate: (inout IOSBatchWatchWorker) -> Void) {
        guard let idx = activeWorkers.firstIndex(where: { $0.id == id }) else { return }
        var copy = activeWorkers[idx]
        mutate(&copy)
        activeWorkers[idx] = copy
    }

    private func removeWorker(id: String) {
        activeWorkers.removeAll { $0.id == id }
    }

    private func appendLog(_ message: String, isError: Bool = false) {
        logSequence += 1
        let entry = IOSStudyLogEntry(
            id: logSequence,
            timeLabel: Self.timeFormatter.string(from: Date()),
            message: message,
            isError: isError
        )
        logs.insert(entry, at: 0)
        if logs.count > Self.maxLogEntries {
            logs = Array(logs.prefix(Self.maxLogEntries))
        }
    }

    private func extractRestSeconds(from message: String) -> Int? {
        guard let regex = try? NSRegularExpression(pattern: #"\[restTime:(\d+)\]"#),
              let match = regex.firstMatch(in: message, range: NSRange(location: 0, length: (message as NSString).length)),
              match.numberOfRanges > 1 else {
            return nil
        }
        return Int((message as NSString).substring(with: match.range(at: 1)))
    }

    private func decrementPendingCount(courseKey: String, chapterId: String) {
        guard let cache = chaptersByCourse[courseKey] else { return }
        let updatedChapters = cache.chapters.map { node -> ChaoxingChapterNode in
            guard node.id == chapterId, let pending = node.pendingJobCount, pending > 0 else {
                return node
            }
            return ChaoxingChapterNode(
                id: node.id,
                title: node.title,
                sectionTitle: node.sectionTitle,
                courseId: node.courseId,
                classId: node.classId,
                pendingJobCount: max(0, pending - 1)
            )
        }
        chaptersByCourse[courseKey] = IOSCourseChaptersCache(cpi: cache.cpi, chapters: updatedChapters)
    }

    private func markVideoPassedLocally(courseKey: String, chapterKey: String, chapterId: String, videoId: String, endSec: Int) {
        if let content = contentByChapterKey[chapterKey] {
            let updatedVideos = content.videos.map { v in
                v.id == videoId ? v.updating(isPassed: true, headOffsetMs: endSec * 1000) : v
            }
            contentByChapterKey[chapterKey] = ChaoxingChapterContent(
                videos: updatedVideos,
                liveTasks: content.liveTasks,
                otherAttachments: content.otherAttachments
            )
        }
        decrementPendingCount(courseKey: courseKey, chapterId: chapterId)
    }

    private func markLivePassedLocally(courseKey: String, chapterKey: String, chapterId: String, liveId: String, durationSec: Int) {
        if let content = contentByChapterKey[chapterKey] {
            let updatedLives = content.liveTasks.map { l in
                l.id == liveId
                    ? l.updating(
                        isPassed: true,
                        playbackTimelongMs: max(l.playbackTimelongMs, durationSec * 1000),
                        percentValue: max(l.percentValue, 100.0)
                    )
                    : l
            }
            contentByChapterKey[chapterKey] = ChaoxingChapterContent(
                videos: content.videos,
                liveTasks: updatedLives,
                otherAttachments: content.otherAttachments
            )
        }
        decrementPendingCount(courseKey: courseKey, chapterId: chapterId)
    }

    private func markAttachmentPassedLocally(courseKey: String, chapterKey: String, chapterId: String, attachmentId: String) {
        if let content = contentByChapterKey[chapterKey] {
            let updatedOthers = content.otherAttachments.map { att in
                att.id == attachmentId ? att.updating(isPassed: true) : att
            }
            contentByChapterKey[chapterKey] = ChaoxingChapterContent(
                videos: content.videos,
                liveTasks: content.liveTasks,
                otherAttachments: updatedOthers
            )
        }
        decrementPendingCount(courseKey: courseKey, chapterId: chapterId)
    }
}
