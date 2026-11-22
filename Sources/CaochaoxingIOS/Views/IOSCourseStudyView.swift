// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

import SwiftUI
import AVFoundation
import AVKit
import ChaoxingCore

// MARK: - Inline Multi-Stream AVPlayer Controller (m3u8 -> mp4 Auto-Fallback)

@MainActor
@Observable
final class IOSLivePlayerController {
    let player = AVPlayer()
    var isMuted = true
    var isReady = false
    var isBuffering = false
    var errorMessage: String?

    @ObservationIgnored private var candidateURLs: [String] = []
    @ObservationIgnored private var currentIndex = 0
    @ObservationIgnored private var isLiveReplay = false
    @ObservationIgnored private var legalRate: Float = 1.0
    @ObservationIgnored private var pendingSeekSec: Int?
    @ObservationIgnored private var itemStatusObserver: NSKeyValueObservation?
    @ObservationIgnored private var timeControlObserver: NSKeyValueObservation?
    @ObservationIgnored private var endObserverToken: NSObjectProtocol?
    @ObservationIgnored private var bufferingWatchdog: Task<Void, Never>?

    init(streamURLs: [String], isLiveReplay: Bool, legalRate: Double, startAtSec: Int) {
        self.candidateURLs = Self.deduplicated(streamURLs)
        self.isLiveReplay = isLiveReplay
        self.legalRate = Float(max(1.0, min(2.0, legalRate)))
        self.player.isMuted = true
        self.player.allowsExternalPlayback = false
        self.player.automaticallyWaitsToMinimizeStalling = true
        startCurrentStream(startAtSec: startAtSec)
    }

    func updateStreamURLs(_ urls: [String], startAtSec: Int) {
        let hadCount = candidateURLs.count
        for raw in Self.deduplicated(urls) where !candidateURLs.contains(raw) {
            candidateURLs.append(raw)
        }
        guard candidateURLs.count > hadCount else { return }
        if !isReady && (player.currentItem == nil || errorMessage != nil) {
            currentIndex = hadCount
            startCurrentStream(startAtSec: startAtSec)
        }
    }

    func toggleMute() {
        isMuted.toggle()
        player.isMuted = isMuted
    }

    func stopAndCleanup() {
        bufferingWatchdog?.cancel()
        bufferingWatchdog = nil
        if let token = endObserverToken {
            NotificationCenter.default.removeObserver(token)
            endObserverToken = nil
        }
        itemStatusObserver?.invalidate()
        itemStatusObserver = nil
        timeControlObserver?.invalidate()
        timeControlObserver = nil
        player.pause()
        player.replaceCurrentItem(with: nil)
    }

    private func startCurrentStream(startAtSec: Int) {
        bufferingWatchdog?.cancel()
        bufferingWatchdog = nil
        if let token = endObserverToken {
            NotificationCenter.default.removeObserver(token)
            endObserverToken = nil
        }
        itemStatusObserver?.invalidate()
        itemStatusObserver = nil
        timeControlObserver?.invalidate()
        timeControlObserver = nil

        guard currentIndex < candidateURLs.count,
              let url = URL(string: candidateURLs[currentIndex]) else {
            isBuffering = false
            isReady = false
            errorMessage = "没有可播放的媒体地址"
            return
        }

        let referer = isLiveReplay
            ? "\(ChaoxingCourseStudyService.zhiboBase)/"
            : "https://mooc1-2.chaoxing.com/ananas/modules/video/index.html"
        let headers = [
            "Referer": referer,
            "User-Agent": ChaoxingCourseStudyService.pcWebUserAgent
        ]
        let asset = AVURLAsset(url: url, options: ["AVURLAssetHTTPHeaderFieldsKey": headers])
        let item = AVPlayerItem(asset: asset)
        // Bound forward buffer per tile so 3-5 simultaneous streams play smoothly on iPad & iPhone.
        item.preferredForwardBufferDuration = 10

        pendingSeekSec = startAtSec > 0 ? startAtSec : nil
        isReady = false
        isBuffering = true
        errorMessage = nil

        player.defaultRate = legalRate
        player.replaceCurrentItem(with: item)

        if isLiveReplay {
            endObserverToken = NotificationCenter.default.addObserver(
                forName: .AVPlayerItemDidPlayToEndTime,
                object: item,
                queue: .main
            ) { [weak self] _ in
                Task { @MainActor [weak self] in
                    guard let self else { return }
                    self.player.seek(to: .zero) { [weak self] _ in
                        Task { @MainActor [weak self] in
                            guard let self else { return }
                            self.player.play()
                            self.player.rate = self.legalRate
                        }
                    }
                }
            }
        }

        timeControlObserver = player.observe(\.timeControlStatus, options: [.initial, .new]) { [weak self] p, _ in
            let status = p.timeControlStatus
            Task { @MainActor [weak self] in
                guard let self else { return }
                switch status {
                case .playing:
                    self.isBuffering = false
                    self.bufferingWatchdog?.cancel()
                    self.bufferingWatchdog = nil
                case .waitingToPlayAtSpecifiedRate:
                    self.isBuffering = true
                case .paused:
                    self.isBuffering = !self.isReady && self.errorMessage == nil
                @unknown default:
                    break
                }
            }
        }

        itemStatusObserver = item.observe(\.status, options: [.initial, .new]) { [weak self] observed, _ in
            let st = observed.status
            let errText = observed.error?.localizedDescription
            Task { @MainActor [weak self] in
                guard let self, self.player.currentItem === observed else { return }
                switch st {
                case .readyToPlay:
                    self.isReady = true
                    self.errorMessage = nil
                    self.bufferingWatchdog?.cancel()
                    self.bufferingWatchdog = nil
                    if let seek = self.pendingSeekSec {
                        self.pendingSeekSec = nil
                        let target = CMTime(seconds: Double(seek), preferredTimescale: 600)
                        self.player.seek(to: target, toleranceBefore: .zero, toleranceAfter: .zero) { [weak self] _ in
                            Task { @MainActor [weak self] in
                                guard let self else { return }
                                self.player.play()
                                self.player.rate = self.legalRate
                            }
                        }
                    } else {
                        self.player.play()
                        self.player.rate = self.legalRate
                    }
                case .failed:
                    self.bufferingWatchdog?.cancel()
                    self.bufferingWatchdog = nil
                    if self.tryFallbackStream() { return }
                    self.isReady = false
                    self.isBuffering = false
                    self.errorMessage = errText ?? "媒体加载失败"
                case .unknown:
                    self.isBuffering = true
                @unknown default:
                    break
                }
            }
        }

        if currentIndex + 1 < candidateURLs.count {
            let watchedItem = item
            bufferingWatchdog = Task { [weak self] in
                try? await Task.sleep(for: .seconds(15))
                guard !Task.isCancelled else { return }
                await MainActor.run { [weak self] in
                    guard let self, self.player.currentItem === watchedItem, !self.isReady else { return }
                    _ = self.tryFallbackStream()
                }
            }
        }

        if pendingSeekSec == nil {
            player.play()
        }
    }

    private func tryFallbackStream() -> Bool {
        let next = currentIndex + 1
        guard next < candidateURLs.count else { return false }
        currentIndex = next
        startCurrentStream(startAtSec: pendingSeekSec ?? 0)
        return true
    }

    private static func deduplicated(_ urls: [String]) -> [String] {
        var out: [String] = []
        for raw in urls {
            let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
            if !trimmed.isEmpty && !out.contains(trimmed) {
                out.append(trimmed)
            }
        }
        return out
    }
}

@MainActor
@Observable
final class IOSLivePlayerPool {
    private(set) var players: [String: IOSLivePlayerController] = [:]

    func sync(with workers: [IOSBatchWatchWorker], enabled: Bool) {
        guard enabled else {
            stopAll()
            return
        }
        let activeIds = Set(workers.map(\.id))
        for (id, ctrl) in players where !activeIds.contains(id) {
            ctrl.stopAndCleanup()
            players.removeValue(forKey: id)
        }
        for worker in workers {
            if let existing = players[worker.id] {
                existing.updateStreamURLs(worker.streamURLs, startAtSec: worker.displaySeconds)
            } else {
                players[worker.id] = IOSLivePlayerController(
                    streamURLs: worker.streamURLs,
                    isLiveReplay: worker.isLiveReplay,
                    legalRate: worker.speedMultiplier,
                    startAtSec: worker.displaySeconds
                )
            }
        }
    }

    func stopAll() {
        for ctrl in players.values {
            ctrl.stopAndCleanup()
        }
        players.removeAll()
    }
}

private struct IOSAVPlayerLayerView: UIViewRepresentable {
    let player: AVPlayer

    final class PlayerContainerView: UIView {
        override static var layerClass: AnyClass { AVPlayerLayer.self }
        var playerLayer: AVPlayerLayer { layer as! AVPlayerLayer }
    }

    func makeUIView(context: Context) -> PlayerContainerView {
        let view = PlayerContainerView()
        view.backgroundColor = .black
        view.playerLayer.videoGravity = .resizeAspect
        view.playerLayer.player = player
        return view
    }

    func updateUIView(_ uiView: PlayerContainerView, context: Context) {
        if uiView.playerLayer.player !== player {
            uiView.playerLayer.player = player
        }
    }
}

private struct IOSMediaPreviewItem: Identifiable {
    let id = UUID()
    let title: String
    let streamURLs: [String]
    let isLiveReplay: Bool
    let startAtSec: Int
    let webPageURL: String?
}

private struct IOSMediaPreviewSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL
    let item: IOSMediaPreviewItem
    @State private var controller: IOSLivePlayerController?

    var body: some View {
        NavigationStack {
            ZStack {
                Color.black.ignoresSafeArea()
                if let ctrl = controller {
                    VideoPlayer(player: ctrl.player)
                        .ignoresSafeArea(edges: .bottom)
                    if ctrl.isBuffering && !ctrl.isReady {
                        ProgressView("正在缓冲视频流…")
                            .tint(.white)
                            .foregroundStyle(.white)
                            .padding(14)
                            .background(.black.opacity(0.65), in: RoundedRectangle(cornerRadius: 12))
                    } else if let err = ctrl.errorMessage {
                        VStack(spacing: 10) {
                            Image(systemName: "exclamationmark.triangle.fill")
                                .font(.title2)
                                .foregroundStyle(CxPalette.amber)
                            Text(err)
                                .font(.subheadline)
                                .foregroundStyle(.white)
                                .multilineTextAlignment(.center)
                            if let web = item.webPageURL, let url = URL(string: web) {
                                Button("在浏览器中打开原页面") {
                                    openURL(url)
                                }
                                .buttonStyle(.borderedProminent)
                            }
                        }
                        .padding(20)
                    }
                }
            }
            .navigationTitle(item.title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("关闭") { dismiss() }
                }
                if let web = item.webPageURL, let url = URL(string: web) {
                    ToolbarItem(placement: .topBarTrailing) {
                        Button {
                            openURL(url)
                        } label: {
                            Image(systemName: "safari")
                        }
                    }
                }
            }
            .onAppear {
                let ctrl = IOSLivePlayerController(
                    streamURLs: item.streamURLs,
                    isLiveReplay: item.isLiveReplay,
                    legalRate: 1.0,
                    startAtSec: item.startAtSec
                )
                ctrl.toggleMute() // unmute for interactive preview
                controller = ctrl
            }
            .onDisappear {
                controller?.stopAndCleanup()
                controller = nil
            }
        }
        .tint(CxPalette.accent)
    }
}

// MARK: - Main Course Study Panel (iPhone & iPad Adaptive)

struct IOSCourseStudyView: View {
    @Environment(IOSAppState.self) private var appState
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.openURL) private var openURL

    let course: ChaoxingCourse

    @State private var expandedChapterIds: Set<String> = []
    @State private var showLogConsole = false
    @State private var playerPool = IOSLivePlayerPool()
    @State private var previewItem: IOSMediaPreviewItem?
    @State private var pageID = UUID()
    @State private var pageIsVisible = false

    private var study: IOSCourseStudyController { appState.studyController }

    private var playbackPage: IOSStudyPlaybackPage { .init(courseID: course.id, id: pageID) }

    private var canShowPlaybackPage: Bool {
        pageIsVisible && scenePhase == .active && appState.selectedTab == 0 && previewItem == nil
            && appState.deepLinkActivity == nil && appState.captchaChallenge == nil && appState.errorMessage == nil
    }

    private func updatePlaybackVisibility() {
        if canShowPlaybackPage {
            study.setPlaybackPage(playbackPage, visible: true)
        } else {
            releasePlaybackPage()
        }
    }

    private func releasePlaybackPage() {
        study.setPlaybackPage(playbackPage, visible: false)
        playerPool.stopAll()
    }

    private func presentPreview(_ item: IOSMediaPreviewItem) {
        releasePlaybackPage()
        previewItem = item
    }

    private var courseCache: IOSCourseChaptersCache? {
        study.chaptersByCourse[course.id]
    }

    private var displayedChapters: [ChaoxingChapterNode] {
        let all = courseCache?.chapters ?? []
        if study.onlyPendingChapters {
            return all.filter { ($0.pendingJobCount ?? 1) > 0 }
        }
        return all
    }

    private var isRegularWidth: Bool {
        horizontalSizeClass == .regular
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                controlCenterCard

                if !study.activeWorkers.isEmpty {
                    activeWorkersSection
                }

                chaptersSection

                logConsoleCard
            }
            .padding(.horizontal, isRegularWidth ? 22 : 16)
            .padding(.vertical, 14)
        }
        .background(CxPalette.canvas)
        .refreshable {
            study.loadChapters(for: course, forceRefresh: true)
        }
        .task(id: course.id) {
            expandedChapterIds.removeAll()
            study.loadChapters(for: course)
        }
        .onAppear {
            pageIsVisible = true
            updatePlaybackVisibility()
        }
        .onChange(of: course.id) { updatePlaybackVisibility() }
        .onChange(of: canShowPlaybackPage) { updatePlaybackVisibility() }
        .onChange(of: study.activeWorkers) { _, newWorkers in
            playerPool.sync(with: newWorkers, enabled: study.canStartWatch(from: playbackPage))
        }
        .onChange(of: study.showLiveVideoPreviews) {
            playerPool.sync(with: study.activeWorkers, enabled: study.canStartWatch(from: playbackPage))
        }
        .onChange(of: playerPool.players.values.contains { $0.errorMessage != nil }) { _, failed in
            if failed { study.playbackBecameUnavailable(on: playbackPage) }
        }
        .onDisappear {
            pageIsVisible = false
            releasePlaybackPage()
        }
        .sheet(item: $previewItem, onDismiss: updatePlaybackVisibility) { item in
            IOSMediaPreviewSheet(item: item)
        }
    }

    // MARK: - Control Center Card

    private var controlCenterCard: some View {
        @Bindable var controller = study
        let isWatching = study.isWatching

        return VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .top, spacing: 12) {
                CxIconTile("play.square.stack.fill", tone: isWatching ? .sage : .accent, size: 38)
                VStack(alignment: .leading, spacing: 3) {
                    Text("课程播放与同步")
                        .font(CxTypography.serifHeadline(16.5))
                        .foregroundStyle(CxPalette.ink)
                    Text("离开本页、进入后台或打开单独预览时，本次同步会停止。")
                        .font(.caption)
                        .foregroundStyle(CxPalette.inkSecondary)
                }
            }

            // Concurrency selector
            HStack(spacing: 8) {
                Text("同时路数")
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(CxPalette.inkSecondary)
                Spacer(minLength: 4)
                HStack(spacing: 6) {
                    ForEach([1, 2, 3, 5], id: \.self) { count in
                        let selected = study.maxConcurrentWatches == count
                        Button("\(count) 路") {
                            study.maxConcurrentWatches = count
                        }
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(selected ? CxPalette.onAccent : CxPalette.ink)
                        .padding(.horizontal, 11)
                        .padding(.vertical, 6)
                        .background(
                            selected ? CxPalette.accent : CxPalette.surfaceSecondary,
                            in: Capsule()
                        )
                        .disabled(isWatching)
                    }
                }
            }

            Divider()

            VStack(spacing: 10) {
                Toggle(isOn: $controller.watchAllCoursesAutomatically) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("自动匹配其他课程")
                            .font(.subheadline.weight(.medium))
                            .foregroundStyle(CxPalette.ink)
                        Text(study.watchAllCoursesAutomatically
                             ? "已开启：先看本课，再自动续看其余 \(max(0, appState.courses.count - 1)) 门课程"
                             : "默认关闭：仅观看当前选中的这门课程")
                            .font(.caption)
                            .foregroundStyle(CxPalette.inkSecondary)
                    }
                }
                .disabled(isWatching)

                Toggle(isOn: $controller.autoCompleteDocuments) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("顺便完成 PDF / 文档任务点")
                            .font(.subheadline.weight(.medium))
                            .foregroundStyle(CxPalette.ink)
                        Text("扫描章节时自动将可完成的 PDF / PPT 标为已完成")
                            .font(.caption)
                            .foregroundStyle(CxPalette.inkSecondary)
                    }
                }
                .disabled(isWatching)

                Toggle(isOn: $controller.showLiveVideoPreviews) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("并行卡片内显示实时视频画面")
                            .font(.subheadline.weight(.medium))
                            .foregroundStyle(CxPalette.ink)
                        Text("默认静音播放。关闭画面会停止同步，再次开启后可手动开始。")
                            .font(.caption)
                            .foregroundStyle(CxPalette.inkSecondary)
                    }
                }
            }

            if isWatching {
                Button(role: .destructive) {
                    study.stopWatch()
                } label: {
                    HStack(spacing: 6) {
                        Image(systemName: "stop.fill")
                        Text("停止同步看课")
                    }
                    .frame(maxWidth: .infinity)
                }
                .font(.headline)
                .foregroundStyle(CxPalette.onAccent)
                .padding(.vertical, 11)
                .frame(minHeight: 44)
                .background(CxPalette.cinnabar, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
            } else {
                let buttonTitle = (study.watchAllCoursesAutomatically && appState.courses.count > 1)
                    ? "一键跨课同时看 (\(study.maxConcurrentWatches) 路)"
                    : "一键同时看本课 (\(study.maxConcurrentWatches) 路)"
                Button {
                    study.startWatch(primaryCourse: course, allCourses: appState.courses, from: playbackPage)
                } label: {
                    HStack(spacing: 6) {
                        Image(systemName: "play.fill")
                        Text(buttonTitle)
                    }
                    .frame(maxWidth: .infinity)
                }
                .buttonStyle(CxPrimaryButtonStyle())
                .disabled(!study.canStartWatch(from: playbackPage))
            }

            if isWatching || study.totalDiscoveredCount > 0 || study.completedDocsCount > 0 {
                VStack(alignment: .leading, spacing: 8) {
                    if !study.watchScopeLabel.isEmpty {
                        Text(study.watchScopeLabel)
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(CxPalette.ink)
                    }
                    if !study.scanProgress.isEmpty {
                        HStack(spacing: 8) {
                            ProgressView().controlSize(.small)
                            Text(study.scanProgress)
                                .font(.caption)
                                .foregroundStyle(CxPalette.inkSecondary)
                        }
                    }
                    if study.cooldownRemainingSec > 0 {
                        HStack(spacing: 6) {
                            Image(systemName: "clock.badge.exclamationmark")
                                .foregroundStyle(CxPalette.amber)
                            Text("触发服务端限流保护，冷却剩余 \(study.cooldownRemainingSec)s 后自动继续")
                                .font(.caption.weight(.medium))
                                .foregroundStyle(CxPalette.amber)
                        }
                        .padding(8)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(CxPalette.amber.opacity(0.14), in: RoundedRectangle(cornerRadius: 8))
                    }
                    CxFlowLayout(horizontalSpacing: 6, verticalSpacing: 6) {
                        CxBadge("已完成 \(study.completedCount)/\(study.totalDiscoveredCount)", tone: .sage, showDot: isWatching, monospaced: true)
                        if study.activeWorkers.count > 0 {
                            CxBadge("并行中 \(study.activeWorkers.count) 路", tone: .accent, monospaced: true)
                        }
                        if study.queueCount > 0 {
                            CxBadge("排队 \(study.queueCount)", tone: .neutral, monospaced: true)
                        }
                        if study.completedDocsCount > 0 {
                            CxBadge("文档 \(study.completedDocsCount)", tone: .sage, monospaced: true)
                        }
                        if study.skippedCount > 0 {
                            CxBadge("跳过 \(study.skippedCount)", tone: .amber, monospaced: true)
                        }
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .cxWell(padding: 12)
            }
        }
        .cxCard()
        .overlay(
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .strokeBorder(CxPalette.border, lineWidth: 0.8)
        )
    }

    // MARK: - Active Parallel Workers Grid (2-Col on iPad, 1-Col on iPhone)

    private var activeWorkersSection: some View {
        let columns: [GridItem] = isRegularWidth
            ? [GridItem(.adaptive(minimum: 310), spacing: 12)]
            : [GridItem(.flexible())]

        return VStack(alignment: .leading, spacing: 10) {
            CxSectionHeader("正在并行观看", trailing: "\(study.activeWorkers.count) 路 · 防息屏已开启")

            LazyVGrid(columns: columns, spacing: 12) {
                ForEach(study.activeWorkers) { worker in
                    IOSActiveWorkerCard(
                        worker: worker,
                        playerController: study.showLiveVideoPreviews ? playerPool.players[worker.id] : nil,
                        onSkip: { study.skipWorker(taskId: worker.id) },
                        onExpand: {
                            presentPreview(IOSMediaPreviewItem(
                                title: worker.title,
                                streamURLs: worker.streamURLs,
                                isLiveReplay: worker.isLiveReplay,
                                startAtSec: worker.displaySeconds,
                                webPageURL: worker.webPageURL
                            ))
                        },
                        onOpenWeb: { raw in
                            if let url = URL(string: raw) { openURL(url) }
                        }
                    )
                    .onDisappear {
                        if study.activeWorkers.contains(where: { $0.id == worker.id }) {
                            study.playbackBecameUnavailable(on: playbackPage)
                        }
                    }
                }
            }
        }
    }

    // MARK: - Chapters & Task Points Section

    private var chaptersSection: some View {
        @Bindable var controller = study
        let isLoadingChapters = study.loadingChapterCourseIds.contains(course.id)
        let chapterError = study.chapterErrorsByCourse[course.id]
        let allCount = courseCache?.chapters.count ?? 0

        return VStack(alignment: .leading, spacing: 10) {
            HStack {
                CxSectionHeader("课程章节与任务点", trailing: "\(displayedChapters.count)/\(allCount)")
                Button {
                    study.loadChapters(for: course, forceRefresh: true)
                } label: {
                    Image(systemName: "arrow.clockwise")
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(CxPalette.accent)
                }
                .buttonStyle(.plain)
            }

            VStack(alignment: .leading, spacing: 10) {
                Toggle("仅看未完成章节", isOn: $controller.onlyPendingChapters)
                    .font(.subheadline)
                    .tint(CxPalette.accent)

                Divider()

                if courseCache != nil {
                    if let chapterError {
                        Text("章节刷新失败，当前显示上次加载的结果。\n\(chapterError)")
                            .font(.caption)
                            .foregroundStyle(CxPalette.cinnabar)
                    } else if isLoadingChapters {
                        ProgressView("正在刷新章节，当前显示上次加载的结果。")
                            .font(.caption)
                    }
                }

                if courseCache == nil && chapterError == nil {
                    HStack(spacing: 10) {
                        ProgressView()
                        Text("正在解析课程章节目录…")
                            .font(.subheadline)
                            .foregroundStyle(CxPalette.inkSecondary)
                    }
                    .padding(.vertical, 12)
                } else if let err = chapterError, courseCache == nil {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("章节加载失败：\(err)")
                            .font(.subheadline)
                            .foregroundStyle(CxPalette.cinnabar)
                        Button("重新加载") {
                            study.loadChapters(for: course, forceRefresh: true)
                        }
                        .buttonStyle(CxSecondaryButtonStyle(compact: true))
                    }
                    .padding(.vertical, 6)
                } else if displayedChapters.isEmpty {
                    Text(allCount == 0 ? "没有查到在线章节" : "当前筛选下没有未完成章节")
                        .font(.subheadline)
                        .foregroundStyle(CxPalette.inkSecondary)
                        .padding(.vertical, 10)
                } else {
                    LazyVStack(spacing: 0) {
                        ForEach(Array(displayedChapters.enumerated()), id: \.element.id) { idx, chapter in
                            let chKey = IOSCourseStudyController.chapterKey(for: chapter)
                            let isExpanded = expandedChapterIds.contains(chapter.id)
                            IOSChapterRowView(
                                course: course,
                                chapter: chapter,
                                isExpanded: isExpanded,
                                isLoadingContent: study.loadingChapterKeys.contains(chKey),
                                content: study.contentByChapterKey[chKey],
                                completingDocumentIds: study.completingDocumentIds,
                                activeWorkerIds: Set(study.activeWorkers.map(\.id)),
                                maxConcurrentWatches: study.maxConcurrentWatches,
                                isWatching: study.isWatching,
                                canStartWatch: study.canStartWatch(from: playbackPage),
                                onToggleExpand: {
                                    withAnimation(.easeInOut(duration: 0.18)) {
                                        if isExpanded {
                                            expandedChapterIds.remove(chapter.id)
                                        } else {
                                            expandedChapterIds.insert(chapter.id)
                                            study.loadChapterContent(for: course, chapter: chapter)
                                        }
                                    }
                                },
                                onRefreshChapter: {
                                    study.loadChapterContent(for: course, chapter: chapter, forceRefresh: true)
                                },
                                onWatchSingleVideo: { video in
                                    study.startSingleVideoWatch(course: course, chapter: chapter, video: video, from: playbackPage)
                                },
                                onPreviewVideo: { video in
                                    presentPreview(IOSMediaPreviewItem(
                                        title: video.name,
                                        streamURLs: video.videoPlayURL.map { [$0] } ?? [],
                                        isLiveReplay: false,
                                        startAtSec: video.headOffsetMs / 1000,
                                        webPageURL: nil
                                    ))
                                },
                                onWatchSingleLive: { live in
                                    study.startSingleLiveWatch(course: course, chapter: chapter, live: live, from: playbackPage)
                                },
                                onPreviewLive: { live in
                                    presentPreview(IOSMediaPreviewItem(
                                        title: live.title,
                                        streamURLs: live.allPlayURLs,
                                        isLiveReplay: true,
                                        startAtSec: live.watchedSeconds,
                                        webPageURL: live.webPageURL
                                    ))
                                },
                                onStartParallelCourse: {
                                    study.startWatch(primaryCourse: course, allCourses: appState.courses, from: playbackPage)
                                },
                                onCompleteDocument: { doc in
                                    study.completeDocument(courseKey: course.id, chapter: chapter, attachment: doc)
                                },
                                onOpenExternalURL: { raw in
                                    if let url = URL(string: raw) { openURL(url) }
                                }
                            )
                            if idx < displayedChapters.count - 1 {
                                Divider()
                            }
                        }
                    }
                }
            }
            .cxCard()
            .overlay(
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .strokeBorder(CxPalette.border, lineWidth: 0.8)
            )
        }
    }

    // MARK: - Collapsible Log Console

    private var logConsoleCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Button {
                    withAnimation(.easeInOut(duration: 0.18)) {
                        showLogConsole.toggle()
                    }
                } label: {
                    HStack(spacing: 6) {
                        Image(systemName: showLogConsole ? "chevron.down" : "chevron.right")
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(CxPalette.inkTertiary)
                        Text("看课与心跳日志 (\(study.logs.count))")
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(CxPalette.ink)
                    }
                }
                .buttonStyle(.plain)

                Spacer()

                if !study.logs.isEmpty {
                    Button("清空") {
                        study.clearLogs()
                    }
                    .font(.caption.weight(.medium))
                    .foregroundStyle(CxPalette.inkSecondary)
                }
            }

            if showLogConsole {
                if study.logs.isEmpty {
                    Text("暂无看课日志")
                        .font(.caption)
                        .foregroundStyle(CxPalette.inkTertiary)
                } else {
                    VStack(alignment: .leading, spacing: 6) {
                        ForEach(study.logs.prefix(40)) { entry in
                            HStack(alignment: .top, spacing: 8) {
                                Text(entry.timeLabel)
                                    .font(CxTypography.mono(10.5))
                                    .foregroundStyle(CxPalette.inkTertiary)
                                Text(entry.message)
                                    .font(CxTypography.mono(11))
                                    .foregroundStyle(entry.isError ? CxPalette.cinnabar : CxPalette.ink)
                                    .fixedSize(horizontal: false, vertical: true)
                            }
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .cxWell(padding: 10)
                }
            }
        }
        .cxCard(padding: 14)
        .overlay(
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .strokeBorder(CxPalette.border, lineWidth: 0.8)
        )
    }
}

// MARK: - Active Worker Tile

private struct IOSActiveWorkerCard: View {
    let worker: IOSBatchWatchWorker
    let playerController: IOSLivePlayerController?
    let onSkip: () -> Void
    let onExpand: () -> Void
    let onOpenWeb: (String) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            if let ctrl = playerController {
                ZStack {
                    IOSAVPlayerLayerView(player: ctrl.player)

                    if ctrl.isBuffering && !ctrl.isReady {
                        ZStack {
                            Color.black.opacity(0.45)
                            HStack(spacing: 6) {
                                ProgressView().controlSize(.small).tint(.white)
                                Text("画面缓冲中…")
                                    .font(.caption2.weight(.medium))
                                    .foregroundStyle(.white)
                            }
                            .padding(.horizontal, 10)
                            .padding(.vertical, 5)
                            .background(.black.opacity(0.65), in: Capsule())
                        }
                    } else if let err = ctrl.errorMessage {
                        VStack(spacing: 4) {
                            Image(systemName: "waveform.path")
                                .foregroundStyle(CxPalette.amber)
                            Text(err)
                                .font(.caption2)
                                .foregroundStyle(.white)
                                .multilineTextAlignment(.center)
                        }
                        .padding(10)
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                        .background(.black.opacity(0.75))
                    }

                    // Overlay quick controls (mute + fullscreen expand)
                    VStack {
                        HStack {
                            Spacer()
                            HStack(spacing: 6) {
                                Button {
                                    ctrl.toggleMute()
                                } label: {
                                    Image(systemName: ctrl.isMuted ? "speaker.slash.fill" : "speaker.wave.2.fill")
                                        .font(.caption.weight(.semibold))
                                        .foregroundStyle(.white)
                                        .padding(7)
                                        .background(.black.opacity(0.55), in: Circle())
                                }
                                Button(action: onExpand) {
                                    Image(systemName: "arrow.up.left.and.arrow.down.right")
                                        .font(.caption.weight(.semibold))
                                        .foregroundStyle(.white)
                                        .padding(7)
                                        .background(.black.opacity(0.55), in: Circle())
                                }
                            }
                            .padding(8)
                        }
                        Spacer()
                    }
                }
                .aspectRatio(16 / 9, contentMode: .fit)
                .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
            }

            HStack(alignment: .top, spacing: 10) {
                CxIconTile(
                    worker.isLiveReplay ? "dot.radiowaves.left.and.right" : "play.rectangle.fill",
                    tone: worker.isLiveReplay ? .amber : .accent,
                    size: 32
                )

                VStack(alignment: .leading, spacing: 3) {
                    Text(worker.title)
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(CxPalette.ink)
                        .lineLimit(2)
                    Text("\(worker.courseName) · \(worker.chapterTitle)")
                        .font(.caption2)
                        .foregroundStyle(CxPalette.inkSecondary)
                        .lineLimit(1)
                    CxFlowLayout(horizontalSpacing: 6, verticalSpacing: 4) {
                        if worker.isLiveReplay {
                            CxBadge("直播回放 · 30s心跳", tone: .amber, monospaced: true)
                        } else {
                            CxBadge(
                                IOSCourseStudyController.formatRate(worker.speedMultiplier),
                                tone: worker.speedMultiplier >= 1.99 ? .sage : .neutral,
                                monospaced: true
                            )
                        }
                        if worker.requiresFaceCapture {
                            CxBadge("人脸抓拍提醒", tone: .amber, icon: "exclamationmark.triangle")
                        }
                    }
                }

                Spacer(minLength: 4)

                HStack(spacing: 4) {
                    if let web = worker.webPageURL {
                        Button {
                            onOpenWeb(web)
                        } label: {
                            Image(systemName: "safari")
                                .font(.subheadline)
                                .foregroundStyle(CxPalette.inkSecondary)
                                .padding(6)
                        }
                        .buttonStyle(.plain)
                    }
                    Button(action: onSkip) {
                        Image(systemName: "forward.end.fill")
                            .font(.subheadline)
                            .foregroundStyle(CxPalette.inkSecondary)
                            .padding(6)
                    }
                    .buttonStyle(.plain)
                    .disabled(worker.isSkipping)
                }
            }

            ProgressView(value: worker.progressFraction)
                .tint(worker.isLiveReplay ? CxPalette.amber : CxPalette.sage)

            HStack {
                let confirmedPrefix = worker.isLiveReplay ? "入库" : "已确认"
                Text("\(IOSCourseStudyController.formatTime(worker.displaySeconds)) / \(IOSCourseStudyController.formatTime(worker.duration))（\(confirmedPrefix) \(IOSCourseStudyController.formatTime(worker.confirmedSeconds))）")
                    .font(CxTypography.mono(10.5))
                    .foregroundStyle(CxPalette.inkSecondary)
                Spacer(minLength: 4)
                if worker.nextReportCountdownSec > 0 {
                    Text("\(worker.nextReportCountdownSec)s 后上报")
                        .font(CxTypography.mono(10.5))
                        .foregroundStyle(CxPalette.accent)
                }
            }

            if !worker.statusText.isEmpty {
                Text(worker.statusText)
                    .font(.caption2)
                    .foregroundStyle(CxPalette.inkTertiary)
                    .lineLimit(1)
            }
        }
        .cxCard(padding: 13)
        .overlay(
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .strokeBorder(CxPalette.border, lineWidth: 0.8)
        )
    }
}

// MARK: - Expandable Chapter Row

private struct IOSChapterRowView: View {
    let course: ChaoxingCourse
    let chapter: ChaoxingChapterNode
    let isExpanded: Bool
    let isLoadingContent: Bool
    let content: ChaoxingChapterContent?
    let completingDocumentIds: Set<String>
    let activeWorkerIds: Set<String>
    let maxConcurrentWatches: Int
    let isWatching: Bool
    let canStartWatch: Bool
    let onToggleExpand: () -> Void
    let onRefreshChapter: () -> Void
    let onWatchSingleVideo: (ChaoxingVideoTask) -> Void
    let onPreviewVideo: (ChaoxingVideoTask) -> Void
    let onWatchSingleLive: (ChaoxingLiveTask) -> Void
    let onPreviewLive: (ChaoxingLiveTask) -> Void
    let onStartParallelCourse: () -> Void
    let onCompleteDocument: (ChaoxingChapterAttachment) -> Void
    let onOpenExternalURL: (String) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Button(action: onToggleExpand) {
                HStack(spacing: 10) {
                    VStack(alignment: .leading, spacing: 2) {
                        if !chapter.sectionTitle.isEmpty {
                            Text(chapter.sectionTitle)
                                .font(.caption2.weight(.medium))
                                .foregroundStyle(CxPalette.inkTertiary)
                                .lineLimit(1)
                        }
                        Text(chapter.title)
                            .font(.subheadline.weight(.medium))
                            .foregroundStyle(CxPalette.ink)
                            .multilineTextAlignment(.leading)
                    }

                    Spacer(minLength: 8)

                    if let pending = chapter.pendingJobCount {
                        if pending == 0 {
                            CxBadge("已完成", tone: .sage, icon: "checkmark")
                        } else {
                            CxBadge("待做 \(pending)", tone: .amber, monospaced: true)
                        }
                    } else {
                        CxBadge("待检查", tone: .neutral)
                    }

                    Image(systemName: isExpanded ? "chevron.up" : "chevron.down")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(CxPalette.inkTertiary)
                }
                .padding(.vertical, 11)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)

            if isExpanded {
                VStack(alignment: .leading, spacing: 10) {
                    if isLoadingContent && content == nil {
                        HStack(spacing: 8) {
                            ProgressView().controlSize(.small)
                            Text("正在解析本节任务点…")
                                .font(.caption)
                                .foregroundStyle(CxPalette.inkSecondary)
                        }
                    } else if let content {
                        if content.videos.isEmpty && content.liveTasks.isEmpty && content.otherAttachments.isEmpty {
                            Text("本节暂无任务点")
                                .font(.caption)
                                .foregroundStyle(CxPalette.inkSecondary)
                        } else {
                            // Standard videos & audios
                            ForEach(content.videos) { video in
                                HStack(alignment: .center, spacing: 8) {
                                    Image(systemName: video.isPassed ? "checkmark.circle.fill" : (video.dtype == "Audio" ? "waveform.circle.fill" : "play.circle.fill"))
                                        .foregroundStyle(video.isPassed ? CxPalette.sage : (video.isJob ? CxPalette.accent : CxPalette.inkTertiary))
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(video.name)
                                            .font(.caption.weight(.semibold))
                                            .foregroundStyle(CxPalette.ink)
                                        Text("\(IOSCourseStudyController.formatTime(video.totalDuration)) · \(video.dtype == "Audio" ? "音频 · " : "")服务端 \(IOSCourseStudyController.formatRate(video.allowedMaxRate))\(video.requiresFaceCapture ? " · 含人脸抓拍" : "")")
                                            .font(.caption2)
                                            .foregroundStyle(CxPalette.inkSecondary)
                                    }
                                    Spacer(minLength: 4)
                                    if video.videoPlayURL != nil {
                                        Button {
                                            onPreviewVideo(video)
                                        } label: {
                                            Image(systemName: "play.tv")
                                                .font(.caption.weight(.semibold))
                                        }
                                        .buttonStyle(.bordered)
                                        .controlSize(.mini)
                                    }
                                    if activeWorkerIds.contains(video.id) {
                                        CxBadge("同步中", tone: .accent, showDot: true)
                                    } else if video.isPassed {
                                        CxBadge("已通过", tone: .sage)
                                    } else if video.isReportableTask {
                                        Button("开始同步") {
                                            onWatchSingleVideo(video)
                                        }
                                        .buttonStyle(.borderedProminent)
                                        .tint(CxPalette.accent)
                                        .controlSize(.mini)
                                        .disabled(!canStartWatch || video.videoPlayURL?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty != false)
                                    } else {
                                        CxBadge("非任务点", tone: .neutral)
                                    }
                                }
                            }

                            // External zhibo.chaoxing.com live replays
                            ForEach(content.liveTasks) { live in
                                VStack(alignment: .leading, spacing: 6) {
                                    HStack(alignment: .center, spacing: 8) {
                                        Image(systemName: live.isPassed ? "checkmark.circle.fill" : "dot.radiowaves.left.and.right")
                                            .foregroundStyle(live.isPassed ? CxPalette.sage : (live.isJob ? CxPalette.amber : CxPalette.inkTertiary))
                                        VStack(alignment: .leading, spacing: 2) {
                                            Text(live.title)
                                                .font(.caption.weight(.semibold))
                                                .foregroundStyle(CxPalette.ink)
                                            Text("\(live.statusDescription) · \(IOSCourseStudyController.formatTime(live.duration)) · 达标 \(Int(round(live.rt * 100)))% · 入库 \(String(format: "%.1f%%", live.percentValue))")
                                                .font(.caption2)
                                                .foregroundStyle(CxPalette.inkSecondary)
                                        }
                                        Spacer(minLength: 4)
                                    }
                                    if live.duration > 0 {
                                        ProgressView(value: min(100.0, max(0.0, live.percentValue)), total: 100.0)
                                            .tint(live.isPassed ? CxPalette.sage : CxPalette.amber)
                                    }
                                    CxFlowLayout(horizontalSpacing: 6, verticalSpacing: 6) {
                                        if activeWorkerIds.contains(live.id) {
                                            CxBadge("同步中", tone: .amber, showDot: true)
                                        } else if live.isPassed {
                                            CxBadge("已通过", tone: .sage)
                                        } else if live.isReportableTask {
                                            Button("同步回放") {
                                                onWatchSingleLive(live)
                                            }
                                            .buttonStyle(.borderedProminent)
                                            .tint(CxPalette.accent)
                                            .controlSize(.mini)
                                            .disabled(!canStartWatch || live.allPlayURLs.isEmpty)

                                            if !isWatching {
                                                Button("并行看本课 (\(maxConcurrentWatches)路)") {
                                                    onStartParallelCourse()
                                                }
                                                .buttonStyle(.borderedProminent)
                                                .tint(CxPalette.sage)
                                                .controlSize(.mini)
                                                .disabled(!canStartWatch)
                                            }
                                        } else {
                                            CxBadge(live.isJob ? live.statusDescription : "非任务点", tone: .neutral)
                                        }

                                        if !live.allPlayURLs.isEmpty {
                                            Button("预览回放") {
                                                onPreviewLive(live)
                                            }
                                            .buttonStyle(.bordered)
                                            .controlSize(.mini)
                                        }

                                        if let web = live.webPageURL {
                                            Button("浏览器打开") {
                                                onOpenExternalURL(web)
                                            }
                                            .buttonStyle(.bordered)
                                            .controlSize(.mini)
                                        }
                                    }
                                }
                                .padding(.vertical, 2)
                            }

                            // Documents, Quizzes, External Links
                            ForEach(content.otherAttachments) { att in
                                HStack(alignment: .center, spacing: 8) {
                                    Image(systemName: att.isPassed ? "checkmark.circle.fill" : att.kind.systemImage)
                                        .foregroundStyle(att.isPassed ? CxPalette.sage : (att.isJob ? CxPalette.accent : CxPalette.inkTertiary))
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(att.name)
                                            .font(.caption.weight(.medium))
                                            .foregroundStyle(CxPalette.ink)
                                        Text("\(att.kind.displayName) · \(att.isPassed ? "已完成" : (att.canCompleteAsDocument ? "支持一键标为已读" : (att.isJob ? "任务点" : "参考资料")))")
                                            .font(.caption2)
                                            .foregroundStyle(CxPalette.inkSecondary)
                                    }
                                    Spacer(minLength: 4)
                                    if att.canCompleteAsDocument {
                                        Button {
                                            onCompleteDocument(att)
                                        } label: {
                                            if completingDocumentIds.contains(att.id) {
                                                ProgressView().controlSize(.mini)
                                            } else {
                                                Text("一键完成")
                                            }
                                        }
                                        .buttonStyle(.borderedProminent)
                                        .tint(CxPalette.sage)
                                        .controlSize(.mini)
                                        .disabled(completingDocumentIds.contains(att.id))
                                    } else if let ext = att.externalURL {
                                        Button("打开外链") {
                                            onOpenExternalURL(ext)
                                        }
                                        .buttonStyle(.bordered)
                                        .controlSize(.mini)
                                    } else {
                                        CxBadge(att.isPassed ? "已完成" : att.kind.displayName, tone: att.isPassed ? .sage : .neutral)
                                    }
                                }
                            }
                        }

                        HStack {
                            Spacer()
                            Button {
                                onRefreshChapter()
                            } label: {
                                Label("刷新本节", systemImage: "arrow.clockwise")
                                    .font(.caption2.weight(.medium))
                            }
                            .buttonStyle(.plain)
                            .foregroundStyle(CxPalette.accent)
                        }
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .cxWell(padding: 10)
                .padding(.bottom, 10)
            }
        }
    }
}
