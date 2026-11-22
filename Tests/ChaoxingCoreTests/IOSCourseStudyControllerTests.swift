// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

import Foundation
import Testing
@testable import ChaoxingCore
@testable import IOSAppStateSupport

private actor StudyTestClock {
    private var currentMs: Int64 = 1_700_000_000_000
    func advance(by ms: Int) {
        currentMs += Int64(ms)
    }
    func now() -> Int64 { currentMs }
}

private actor StudyProbeStore {
    var scannedCourseIds: [Int64] = []
    var completedDocIds: [String] = []
    var videoCallsById: [String: Int] = [:]
    var videoPassedIds: Set<String> = []
    var liveTicksById: [String: Int] = [:]
    var liveMomentsById: [String: [Int]] = [:]
    var livePassedIds: Set<String> = []
    var rateLimitHitsRemaining: Int = 0

    func recordCourseScan(_ id: Int64) { scannedCourseIds.append(id) }
    func recordDoc(_ id: String) { completedDocIds.append(id) }

    func setRateLimitHits(_ count: Int) { rateLimitHitsRemaining = count }

    func handleVideoLog(taskId: String, event: ChaoxingVideoDragEvent) throws -> ChaoxingVideoLogResponse {
        if rateLimitHitsRemaining > 0 && event == .play {
            rateLimitHitsRemaining -= 1
            throw ChaoxingError.networkError(statusCode: 403, message: "本次学习时长已达上限，请休息 1 分钟 [restTime:5]")
        }
        let next = (videoCallsById[taskId] ?? 0) + 1
        videoCallsById[taskId] = next
        if event == .ended {
            videoPassedIds.insert(taskId)
            return ChaoxingVideoLogResponse(isPassed: true)
        }
        return ChaoxingVideoLogResponse(isPassed: false)
    }

    func isVideoPassed(_ id: String) -> Bool { videoPassedIds.contains(id) }

    func handleLiveHeartbeat(taskId: String, isStart: Bool, watchMomentSec: Int) -> ChaoxingLiveHeartbeatResponse {
        if !isStart {
            liveTicksById[taskId] = (liveTicksById[taskId] ?? 0) + 1
            liveMomentsById[taskId, default: []].append(watchMomentSec)
        }
        return ChaoxingLiveHeartbeatResponse(result: 1, accepted: true, duration: 120, liveStatus: 4)
    }

    func fetchLiveInfo(task: ChaoxingLiveTask) -> ChaoxingLiveInfo {
        // Simulate Chaoxing's real 60s flush lag: flush trails by 1 tick before persisting.
        let ticks = liveTicksById[task.id] ?? 0
        let flushedTicks = max(0, ticks - 1)
        let playedMs = min(120_000, flushedTicks * 30_000)
        let pct = (Double(playedMs) / 1000.0 / 120.0) * 100.0
        if pct >= 90.0 {
            livePassedIds.insert(task.id)
        }
        return ChaoxingLiveInfo(
            resolvedLiveId: task.liveId,
            title: task.title,
            liveStatus: 4,
            ifReview: 0,
            duration: 120,
            playbackTimelongMs: playedMs,
            percentValue: pct,
            streamName: task.streamName,
            vdoid: task.vdoid,
            m3u8URL: "https://example.invalid/\(task.liveId).m3u8",
            mp4URL: "https://example.invalid/\(task.liveId).mp4"
        )
    }

    func isLivePassed(_ id: String) -> Bool { livePassedIds.contains(id) }
    func liveMoments(for id: String) -> [Int] { liveMomentsById[id] ?? [] }
}

@Suite("iOS / iPadOS CourseStudyController regressions")
@MainActor
struct IOSCourseStudyControllerTests {
    private func demoClient(phone: String = "13800000001") -> ChaoxingHTTPClient {
        ChaoxingHTTPClient(
            session: ChaoxingAccountSession(
                phoneNumber: phone,
                encryptedPassword: "enc",
                name: "测试用户",
                uid: 1000001,
                puid: 1000001,
                deviceCode: "demo-device",
                isPrimary: true
            )
        )
    }

    private func demoVideo(courseId: Int64, classId: Int, chapterId: String, jobid: String, name: String, duration: Int = 30) -> ChaoxingVideoTask {
        ChaoxingVideoTask(
            courseId: courseId,
            classId: classId,
            chapterId: chapterId,
            cpi: 900001,
            userid: "1000001",
            jobid: jobid,
            objectId: "obj_\(jobid)",
            name: name,
            otherInfo: "nodeId_\(chapterId)",
            dtoken: "dtoken_\(jobid)",
            duration: duration,
            attDuration: duration,
            attDurationEnc: "enc",
            rt: 0.9,
            doublespeed: 1,
            reportTimeInterval: 15,
            isPassed: false,
            isJob: true,
            reportUrl: "https://mooc1-2.chaoxing.com/mooc-ans/multimedia/log/a/900001",
            videoPlayURL: "https://example.invalid/\(jobid).mp4"
        )
    }

    @Test("Default watch scope only scans selected course and auto-completes PDF documents")
    func testSingleCourseDefaultAndDocumentAutoComplete() async {
        let probe = StudyProbeStore()
        let courseA = ChaoxingCourse(courseName: "课程A", teacherName: "甲", courseId: 300001, classId: 200001, imageURL: "", cpi: 900001)
        let courseB = ChaoxingCourse(courseName: "课程B", teacherName: "乙", courseId: 300002, classId: 200002, imageURL: "", cpi: 900002)
        let chA = ChaoxingChapterNode(id: "8001", title: "1.1 导学", courseId: 300001, classId: 200001, pendingJobCount: 2)
        let vidA = demoVideo(courseId: 300001, classId: 200001, chapterId: "8001", jobid: "v1", name: "导学视频.mp4", duration: 20)
        let docA = ChaoxingChapterAttachment(
            kind: .document, courseId: 300001, classId: 200001, chapterId: "8001",
            jobid: "d1", jtoken: "jt1", name: "导学讲义.pdf", isJob: true, isPassed: false
        )

        let deps = IOSCourseStudyDependencies(
            fetchCourseChapters: { _, cid, _, _ in
                await probe.recordCourseScan(cid)
                let passed = await probe.isVideoPassed(vidA.id)
                let docsDone = await probe.completedDocIds.contains(docA.id)
                let pending = (passed ? 0 : 1) + (docsDone ? 0 : 1)
                return (900001, [ChaoxingChapterNode(id: chA.id, title: chA.title, courseId: chA.courseId, classId: chA.classId, pendingJobCount: pending)])
            },
            fetchChapterContent: { _, _, _, _, _ in
                let vPassed = await probe.isVideoPassed(vidA.id)
                let dPassed = await probe.completedDocIds.contains(docA.id)
                return ChaoxingChapterContent(
                    videos: [vidA.updating(isPassed: vPassed)],
                    otherAttachments: [docA.updating(isPassed: dPassed)]
                )
            },
            sendVideoLog: { _, task, _, event in
                try await probe.handleVideoLog(taskId: task.id, event: event)
            },
            completeDocumentTask: { _, att in
                await probe.recordDoc(att.id)
                return true
            },
            sleepMs: { _ in }
        )

        let client = demoClient()
        let controller = IOSCourseStudyController(clientProvider: { client }, dependencies: deps)
        #expect(controller.watchAllCoursesAutomatically == false)
        #expect(controller.autoCompleteDocuments == true)

        let page = IOSStudyPlaybackPage(courseID: courseA.id)
        controller.setPlaybackPage(page, visible: true)
        await controller.startWatch(primaryCourse: courseA, allCourses: [courseA, courseB], from: page)?.value

        #expect(controller.isWatching == false)
        #expect(controller.completedCount == 1)
        #expect(controller.completedDocsCount == 1)
        #expect(controller.skippedCount == 0)
        let scanned = await probe.scannedCourseIds
        #expect(scanned.allSatisfy { $0 == courseA.courseId })
        #expect(!scanned.contains(courseB.courseId))
    }

    @Test("Three parallel zhibo.chaoxing.com live replays finish without backward clamping under 60s flush lag")
    func testParallelLiveReplaysCompleteWithoutRegression() async {
        let probe = StudyProbeStore()
        let course = ChaoxingCourse(courseName: "国家安全演示课", teacherName: "测试", courseId: 300010, classId: 200010, imageURL: "", cpi: 900010)
        let chapters = (1...3).map { idx in
            ChaoxingChapterNode(id: "900\(idx)", title: "\(idx).1 专题讲座直播", courseId: course.courseId, classId: course.classId, pendingJobCount: 1)
        }
        let livesByChapter: [String: ChaoxingLiveTask] = Dictionary(uniqueKeysWithValues: chapters.map { ch in
            (
                ch.id,
                ChaoxingLiveTask(
                    courseId: course.courseId,
                    classId: course.classId,
                    chapterId: ch.id,
                    cpi: 900010,
                    userid: "1000001",
                    jobid: "live-500012345678900\(ch.id)",
                    liveId: "500012345678900\(ch.id)",
                    streamName: "STREAM_DEMO_\(ch.id)",
                    vdoid: "vdoid_demo_\(ch.id)",
                    title: "示范直播-\(ch.id)",
                    rt: 0.9,
                    isJob: true,
                    isPassed: false,
                    liveStatus: 4,
                    ifReview: 0,
                    duration: 120,
                    playURL: "https://example.invalid/live_\(ch.id).m3u8",
                    fallbackPlayURL: "https://example.invalid/live_\(ch.id).mp4"
                )
            )
        })

        let deps = IOSCourseStudyDependencies(
            fetchCourseChapters: { _, _, _, _ in
                var nodes: [ChaoxingChapterNode] = []
                for ch in chapters {
                    let live = livesByChapter[ch.id]!
                    let passed = await probe.isLivePassed(live.id)
                    nodes.append(ChaoxingChapterNode(id: ch.id, title: ch.title, courseId: ch.courseId, classId: ch.classId, pendingJobCount: passed ? 0 : 1))
                }
                return (900010, nodes)
            },
            fetchChapterContent: { _, _, _, chId, _ in
                let live = livesByChapter[chId]!
                let passed = await probe.isLivePassed(live.id)
                return ChaoxingChapterContent(videos: [], liveTasks: [live.updating(isPassed: passed)])
            },
            sendVideoLog: { _, _, _, _ in ChaoxingVideoLogResponse(isPassed: true) },
            completeDocumentTask: { _, _ in true },
            fetchLiveInfo: { _, task in
                await probe.fetchLiveInfo(task: task)
            },
            prepareLiveReplaySession: { _, task in
                ChaoxingLiveSessionContext(
                    pageURL: "https://zhibo.chaoxing.com/\(task.liveId)",
                    shortLiveId: "99001001",
                    uInfo: "demo_u",
                    streamName: task.streamName,
                    vdoid: task.vdoid,
                    duration: 120,
                    playURL: task.playURL
                )
            },
            sendLiveHeartbeat: { _, task, _, isStart, momentSec in
                await probe.handleLiveHeartbeat(taskId: task.id, isStart: isStart, watchMomentSec: momentSec)
            },
            sleepMs: { _ in }
        )

        let client = demoClient()
        let controller = IOSCourseStudyController(clientProvider: { client }, dependencies: deps)
        controller.maxConcurrentWatches = 3

        let page = IOSStudyPlaybackPage(courseID: course.id)
        controller.setPlaybackPage(page, visible: true)
        await controller.startWatch(primaryCourse: course, allCourses: [course], from: page)?.value

        #expect(controller.isWatching == false)
        #expect(controller.completedCount == 3)
        #expect(controller.skippedCount == 0)

        for live in livesByChapter.values {
            let moments = await probe.liveMoments(for: live.id)
            #expect(moments.count >= 4)
            for idx in 1..<moments.count {
                #expect(moments[idx] >= moments[idx - 1])
            }
        }
    }

    @Test("403 rate limit cools down, downgrades concurrency, and retries cleanly")
    func testRateLimit403RecoversAndCompletes() async {
        let probe = StudyProbeStore()
        await probe.setRateLimitHits(1)
        let clock = StudyTestClock()
        let course = ChaoxingCourse(courseName: "限流测试课", teacherName: "丙", courseId: 300020, classId: 200020, imageURL: "", cpi: 900020)
        let ch = ChaoxingChapterNode(id: "9101", title: "1.1 视频", courseId: 300020, classId: 200020, pendingJobCount: 1)
        let video = demoVideo(courseId: 300020, classId: 200020, chapterId: "9101", jobid: "v403", name: "测试视频.mp4", duration: 20)

        let deps = IOSCourseStudyDependencies(
            fetchCourseChapters: { _, _, _, _ in
                let passed = await probe.isVideoPassed(video.id)
                return (900020, [ChaoxingChapterNode(id: ch.id, title: ch.title, courseId: ch.courseId, classId: ch.classId, pendingJobCount: passed ? 0 : 1)])
            },
            fetchChapterContent: { _, _, _, _, _ in
                let passed = await probe.isVideoPassed(video.id)
                return ChaoxingChapterContent(videos: [video.updating(isPassed: passed)])
            },
            sendVideoLog: { _, task, _, event in
                try await probe.handleVideoLog(taskId: task.id, event: event)
            },
            completeDocumentTask: { _, _ in true },
            sleepMs: { ms in await clock.advance(by: max(ms, 60_000)) },
            nowMs: { Int64(Date().timeIntervalSince1970 * 1000) }
        )

        // Override nowMs with deterministic advancing value via isolated closure
        let nowBox = LockedNowBox()
        var deterministicDeps = deps
        deterministicDeps.sleepMs = { ms in nowBox.advance(by: max(ms, 61_000)) }
        deterministicDeps.nowMs = { nowBox.get() }

        let client = demoClient()
        let controller = IOSCourseStudyController(clientProvider: { client }, dependencies: deterministicDeps)
        controller.maxConcurrentWatches = 3

        let page = IOSStudyPlaybackPage(courseID: course.id)
        controller.setPlaybackPage(page, visible: true)
        await controller.startWatch(primaryCourse: course, allCourses: [course], from: page)?.value

        #expect(controller.completedCount == 1)
        #expect(controller.skippedCount == 0)
        #expect(controller.logs.contains(where: { $0.message.contains("403") }))
    }

    @Test("Switching active account on IOSAppState isolates and resets studyController")
    func testAccountSwitchResetsStudyController() async {
        let store = ChaoxingPersistenceStore(
            customFileURL: FileManager.default.temporaryDirectory.appendingPathComponent("Caochaoxing-ios-study-\(UUID())/state.json")
        )
        let offlineDeps = IOSAppDependencies(
            login: { _, _, _, _ in throw ChaoxingError.invalidInput("offline") },
            fetchCourses: { _ in [] },
            fetchIMConfig: { _ in throw ChaoxingError.invalidInput("offline") },
            fetchGroups: { _, _ in [] },
            fetchActivities: { _, _ in [] },
            fetchDetail: { _, _, _, _ in throw ChaoxingError.invalidInput("offline") },
            prepareSign: { _, _ in .readyToSign },
            sleep: { _ in }
        )
        let studyDeps = IOSCourseStudyDependencies(
            fetchCourseChapters: { _, cid, classId, _ in
                (900001, [ChaoxingChapterNode(id: "ch1", title: "章节1", courseId: cid, classId: classId, pendingJobCount: 1)])
            },
            fetchChapterContent: { _, _, _, _, _ in ChaoxingChapterContent(videos: []) },
            sendVideoLog: { _, _, _, _ in ChaoxingVideoLogResponse(isPassed: true) },
            completeDocumentTask: { _, _ in true },
            sleepMs: { _ in }
        )

        let state = IOSAppState(store: store, dependencies: offlineDeps, studyDependencies: studyDeps, autoLoad: false)
        let accA = ChaoxingAccountSession(phoneNumber: "A", encryptedPassword: "p", name: "甲", deviceCode: "d1", isPrimary: true)
        let accB = ChaoxingAccountSession(phoneNumber: "B", encryptedPassword: "p", name: "乙", deviceCode: "d2", isPrimary: false)
        state.accounts = [accA, accB]
        state.activePhoneNumber = "A"

        let courseA = ChaoxingCourse(courseName: "A课程", teacherName: "", courseId: 1, classId: 1, imageURL: "", cpi: 900001)
        state.studyController.loadChapters(for: courseA)
        while state.studyController.chaptersByCourse[courseA.id] == nil {
            await Task.yield()
        }
        #expect(state.studyController.activeAccountPhone == "A")
        #expect(!state.studyController.chaptersByCourse.isEmpty)

        await state.switchActiveAccount(to: accB)
        #expect(state.studyController.activeAccountPhone == "B")
        #expect(state.studyController.chaptersByCourse.isEmpty)
        #expect(state.studyController.isWatching == false)
    }

    @Test("Leaving the playback page before a queued player starts prevents its first write")
    func testStopBeforePlayerStarts() async {
        let course = ChaoxingCourse(courseName: "Synthetic", teacherName: "", courseId: 1, classId: 2, imageURL: "")
        let chapter = ChaoxingChapterNode(id: "3", title: "Test", courseId: 1, classId: 2)
        let video = demoVideo(courseId: 1, classId: 2, chapterId: "3", jobid: "v", name: "Synthetic")
        let deps = IOSCourseStudyDependencies(
            fetchCourseChapters: { _, _, _, _ in (0, []) },
            fetchChapterContent: { _, _, _, _, _ in ChaoxingChapterContent(videos: []) },
            sendVideoLog: { _, _, _, _ in
                Issue.record("Stopped player sent a progress request")
                return ChaoxingVideoLogResponse(isPassed: true)
            },
            completeDocumentTask: { _, _ in false }
        )
        let client = demoClient()
        let controller = IOSCourseStudyController(clientProvider: { client }, dependencies: deps)
        let page = IOSStudyPlaybackPage(courseID: course.id)
        controller.setPlaybackPage(page, visible: true)
        let task = controller.startSingleVideoWatch(course: course, chapter: chapter, video: video, from: page)
        #expect(task != nil)
        controller.setPlaybackPage(page, visible: false)
        await task?.value
        #expect(!controller.isWatching)
        #expect(controller.completedCount == 0)
        #expect(controller.activeWorkers.isEmpty)
    }

    @Test("Replacing the page stops old playback and a hidden or disabled player cannot start again")
    func testPlaybackPageOwnershipAndDisabledPreviews() async throws {
        let course = ChaoxingCourse(courseName: "Shared", teacherName: "", courseId: 1, classId: 2, imageURL: "")
        let chapter = ChaoxingChapterNode(id: "3", title: "Test", courseId: 1, classId: 2)
        let video = demoVideo(courseId: 1, classId: 2, chapterId: "3", jobid: "v", name: "Synthetic")
        let (requests, calls) = AsyncStream<CheckedContinuation<ChaoxingVideoLogResponse, Never>>.makeStream()
        var incoming = requests.makeAsyncIterator()
        let dependencies = IOSCourseStudyDependencies(
            fetchCourseChapters: { _, _, _, _ in Issue.record("Unexpected scan"); return (0, []) },
            fetchChapterContent: { _, _, _, _, _ in ChaoxingChapterContent(videos: []) },
            sendVideoLog: { _, _, _, _ in await withCheckedContinuation { calls.yield($0) } },
            completeDocumentTask: { _, _ in false }
        )
        let client = demoClient()
        let controller = IOSCourseStudyController(clientProvider: { client }, dependencies: dependencies)
        let first = IOSStudyPlaybackPage(courseID: course.id), second = IOSStudyPlaybackPage(courseID: course.id)
        #expect(controller.startWatch(primaryCourse: course, allCourses: [course], from: first) == nil)

        controller.setPlaybackPage(first, visible: true)
        let old = try #require(controller.startSingleVideoWatch(course: course, chapter: chapter, video: video, from: first))
        let delayed = try #require(await incoming.next())
        controller.setPlaybackPage(second, visible: true)
        #expect(!controller.isWatching)
        #expect(controller.startSingleVideoWatch(course: course, chapter: chapter, video: video, from: first) == nil)
        let current = try #require(controller.startSingleVideoWatch(course: course, chapter: chapter, video: video, from: second))
        let latest = try #require(await incoming.next())
        controller.setPlaybackPage(first, visible: false)
        delayed.resume(returning: ChaoxingVideoLogResponse(isPassed: true))
        await old.value
        #expect(controller.isWatching)
        #expect(controller.activeWorkers.map(\.id) == [video.id])
        #expect(controller.completedCount == 0)

        controller.showLiveVideoPreviews = false
        #expect(!controller.isWatching)
        #expect(controller.startSingleVideoWatch(course: course, chapter: chapter, video: video, from: second) == nil)
        latest.resume(returning: ChaoxingVideoLogResponse(isPassed: true))
        await current.value
        #expect(controller.completedCount == 0)
        controller.setPlaybackPage(second, visible: false)
        controller.showLiveVideoPreviews = true
        #expect(!controller.canStartWatch(from: second))
        #expect(!controller.isWatching)
        calls.finish()
    }
}

private final class LockedNowBox: @unchecked Sendable {
    private let lock = NSLock()
    private var value: Int64 = 1_700_000_000_000
    func get() -> Int64 { lock.withLock { value } }
    func advance(by ms: Int) { lock.withLock { value += Int64(ms) } }
}
