// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

#if os(macOS)
import SwiftUI
import AppKit
import AVKit
import ChaoxingCore

private struct MacWindowCloseHandler: NSViewRepresentable {
    let onClose: () -> Void

    final class Observer: NSView {
        var onClose: (() -> Void)?

        override func viewWillMove(toWindow newWindow: NSWindow?) {
            let center = NotificationCenter.default
            if let window {
                center.removeObserver(self, name: NSWindow.willCloseNotification, object: window)
            }
            if let newWindow {
                center.addObserver(self, selector: #selector(windowClosing),
                                   name: NSWindow.willCloseNotification, object: newWindow)
            }
            super.viewWillMove(toWindow: newWindow)
        }

        @objc private func windowClosing(_ notification: Notification) { onClose?() }
    }

    func makeNSView(context: Context) -> Observer { Observer() }
    func updateNSView(_ view: Observer, context: Context) { view.onClose = onClose }
}

struct MacCourseStudyView: View {
    @Environment(MacAppState.self) private var appState
    @State private var searchText = ""
    @State private var scrubSeconds: Double?

    private var filteredCourses: [ChaoxingCourse] {
        let q = searchText.trimmingCharacters(in: .whitespacesAndNewlines)
        let base = q.isEmpty ? appState.courses : appState.courses.filter {
            $0.courseName.localizedCaseInsensitiveContains(q) ||
            $0.teacherName.localizedCaseInsensitiveContains(q)
        }
        return base.sorted { lhs, rhs in
            let l = appState.starredCourseIds.contains(lhs.courseId)
            let r = appState.starredCourseIds.contains(rhs.courseId)
            if l != r { return l }
            return false
        }
    }

    private var displayedChapters: [ChaoxingChapterNode] {
        if appState.onlyPendingChapters {
            return appState.chapters.filter { ($0.pendingJobCount ?? 1) > 0 }
        }
        return appState.chapters
    }

    var body: some View {
        Group {
            if appState.activeAccount == nil {
                MacLoginScreen()
            } else {
                mainWorkspace
            }
        }
        .sheet(isPresented: Bindable(appState).showAddAccountSheet) {
            MacLoginScreen(isSheet: true)
                .frame(width: 440, height: 420)
        }
        .alert("账号变更未能保存", isPresented: Binding(
            get: { appState.accountChangeError != nil },
            set: { if !$0 { appState.accountChangeError = nil } }
        )) {
            Button("好") { appState.accountChangeError = nil }
        } message: {
            Text(appState.accountChangeError ?? "")
        }
        .background { MacWindowCloseHandler { appState.cancelSignBatch() }.frame(width: 0, height: 0) }
        .onDisappear { appState.cancelSignBatch() }
    }

    private var mainWorkspace: some View {
        VStack(spacing: 0) {
            HStack(spacing: 12) {
                Picker("功能模块", selection: Bindable(appState).selectedTab) {
                    ForEach(MacWorkspaceTab.allCases) { tab in
                        Label(tab.rawValue, systemImage: tab.systemImage).tag(tab)
                    }
                }
                .pickerStyle(.segmented)
                .frame(maxWidth: 520)

                Spacer()

                if appState.selectedTab == .study {
                    if appState.isBatchWatching {
                        Button {
                            appState.stopBatchAutoWatch()
                        } label: {
                            Label("停止同时看 (\(appState.batchActiveWorkers.count)路中)", systemImage: "stop.circle.fill")
                        }
                        .buttonStyle(.borderedProminent)
                        .tint(CxPalette.cinnabar)
                    } else {
                        Button {
                            Task { await appState.startSimultaneousWatch() }
                        } label: {
                            Label(
                                appState.watchAllCoursesAutomatically
                                    ? "跨课同时看 (\(appState.maxConcurrentWatches)路同步播)"
                                    : "同时看本课 (\(appState.maxConcurrentWatches)路同步播)",
                                systemImage: "play.square.stack.fill"
                            )
                        }
                        .buttonStyle(.borderedProminent)
                        .tint(CxPalette.sage)
                        .disabled(appState.courses.isEmpty && appState.isLoadingCourses)
                    }
                } else if appState.selectedTab == .sign {
                    Button {
                        appState.scanPendingActivities()
                    } label: {
                        Label(appState.isScanningRadar ? "扫描中..." : "一键扫待签", systemImage: "waveform.path.ecg")
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(CxPalette.accent)
                    .disabled(appState.isScanningRadar || appState.courses.isEmpty)
                }

                if let active = appState.activeAccount {
                    globalAccountSwitcherMenu(active: active)
                }

                Button {
                    Task { await appState.loadCourses(forceRefresh: true) }
                } label: {
                    Label("刷新", systemImage: "arrow.clockwise")
                }
                .disabled(appState.isLoadingCourses)
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 8)
            .background(CxPalette.surface)
            Divider()

            Group {
                switch appState.selectedTab {
                case .study:
                    studySplitView
                case .sign:
                    MacSignWorkspaceView()
                case .groups:
                    MacGroupSignWorkspaceView()
                case .settings:
                    MacSettingsWorkspaceView()
                }
            }
        }
    }

    private var studySplitView: some View {
        NavigationSplitView {
            VStack(spacing: 0) {
                accountHeader
                Divider()
                List(filteredCourses, selection: Binding(
                    get: { appState.selectedStudyCourse },
                    set: { course in
                        if let course {
                            appState.selectStudyCourse(course)
                        }
                    }
                )) { course in
                    HStack {
                        VStack(alignment: .leading, spacing: 3) {
                            Text(course.courseName)
                                .font(.subheadline.weight(.semibold))
                                .lineLimit(2)
                            if !course.teacherName.isEmpty {
                                Text(course.teacherName)
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                        }
                        Spacer()
                        if appState.starredCourseIds.contains(course.courseId) {
                            Image(systemName: "star.fill")
                                .font(.caption2)
                                .foregroundStyle(CxPalette.amber)
                        }
                    }
                    .padding(.vertical, 3)
                    .tag(course)
                }
                .searchable(text: $searchText, prompt: "搜索课程或教师")
            }
            .navigationSplitViewColumnWidth(min: 220, ideal: 260, max: 320)
        } content: {
            chapterAndTaskColumn
                .navigationSplitViewColumnWidth(min: 260, ideal: 310, max: 380)
        } detail: {
            videoStudyWorkbench
        }
    }

    private func globalAccountSwitcherMenu(active: ChaoxingAccountSession) -> some View {
        Menu {
            Section("切换账号 (\(appState.accounts.count))") {
                ForEach(appState.accounts) { acc in
                    Button {
                        Task { await appState.switchAccount(acc) }
                    } label: {
                        if acc.phoneNumber == active.phoneNumber {
                            Label("\(appState.accountDisplayTitle(for: acc)) (\(acc.phoneNumber))", systemImage: "checkmark")
                        } else {
                            Text("\(appState.accountDisplayTitle(for: acc)) (\(acc.phoneNumber))")
                        }
                    }
                    .disabled(acc.phoneNumber == active.phoneNumber || appState.isSwitchingAccount)
                }
            }
            if appState.accounts.count > 1 {
                Button {
                    Task { await appState.switchToNextAccount() }
                } label: {
                    Label("切换下一个账号", systemImage: "arrow.triangle.2.circlepath")
                }
                .disabled(appState.isSwitchingAccount)
            }
            Divider()
            Button {
                appState.loginErrorMessage = nil
                appState.showAddAccountSheet = true
            } label: {
                Label("添加新账号...", systemImage: "person.badge.plus")
            }
            Button {
                Task { await appState.refreshAccountSession(active) }
            } label: {
                Label("刷新当前账号会话", systemImage: "arrow.clockwise")
            }
            Button {
                appState.selectedTab = .settings
            } label: {
                Label("管理所有账号", systemImage: "person.2.badge.gearshape")
            }
            Divider()
            Button(role: .destructive) {
                Task { await appState.logoutCurrent() }
            } label: {
                Label("退出当前账号", systemImage: "rectangle.portrait.and.arrow.right")
            }
        } label: {
            HStack(spacing: 6) {
                if appState.isSwitchingAccount {
                    ProgressView().controlSize(.mini)
                } else {
                    Image(systemName: "person.crop.circle.fill")
                        .foregroundStyle(CxPalette.accent)
                }
                Text(appState.accountDisplayTitle(for: active))
                    .font(.caption.weight(.medium))
                    .lineLimit(1)
                if appState.accounts.count > 1 {
                    Text("\(appState.accounts.count)")
                        .font(CxTypography.mono(10))
                        .padding(.horizontal, 5)
                        .padding(.vertical, 1)
                        .background(CxPalette.accent.opacity(0.16), in: Capsule())
                        .foregroundStyle(CxPalette.accent)
                }
            }
            .padding(.horizontal, 8)
            .padding(.vertical, 4)
            .background(CxPalette.surfaceSecondary, in: RoundedRectangle(cornerRadius: 7))
            .overlay(RoundedRectangle(cornerRadius: 7).stroke(CxPalette.border, lineWidth: 1))
        }
        .menuStyle(.borderlessButton)
        .fixedSize()
        .help("点击快速切换或添加超星账号")
    }

    private var accountHeader: some View {
        HStack(spacing: 8) {
            Menu {
                ForEach(appState.accounts) { acc in
                    Button {
                        Task { await appState.switchAccount(acc) }
                    } label: {
                        if acc.phoneNumber == appState.activeAccount?.phoneNumber {
                            Label("\(appState.accountDisplayTitle(for: acc)) (\(acc.phoneNumber))", systemImage: "checkmark")
                        } else {
                            Text("\(appState.accountDisplayTitle(for: acc)) (\(acc.phoneNumber))")
                        }
                    }
                    .disabled(acc.phoneNumber == appState.activeAccount?.phoneNumber || appState.isSwitchingAccount)
                }
                Divider()
                Button {
                    appState.loginErrorMessage = nil
                    appState.showAddAccountSheet = true
                } label: {
                    Label("添加新账号...", systemImage: "person.badge.plus")
                }
                if let current = appState.activeAccount {
                    Button {
                        Task { await appState.refreshAccountSession(current) }
                    } label: {
                        Label("刷新会话 Cookie", systemImage: "arrow.clockwise")
                    }
                }
                Divider()
                Button("退出当前账号", role: .destructive) {
                    Task { await appState.logoutCurrent() }
                }
            } label: {
                HStack(spacing: 8) {
                    if appState.isSwitchingAccount {
                        ProgressView().controlSize(.small)
                    } else {
                        Image(systemName: "person.crop.circle.fill")
                            .font(.title2)
                            .foregroundStyle(CxPalette.accent)
                    }
                    VStack(alignment: .leading, spacing: 2) {
                        Text(appState.activeAccount.map { appState.accountDisplayTitle(for: $0) } ?? "未登录")
                            .font(.subheadline.weight(.semibold))
                            .lineLimit(1)
                        Text(appState.activeAccount?.phoneNumber ?? "")
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                    }
                    Spacer(minLength: 4)
                    Image(systemName: "chevron.up.chevron.down")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
                .contentShape(Rectangle())
            }
            .menuStyle(.borderlessButton)

            Button {
                appState.loginErrorMessage = nil
                appState.showAddAccountSheet = true
            } label: {
                Image(systemName: "person.badge.plus")
                    .font(.subheadline)
            }
            .buttonStyle(.borderless)
            .help("添加新账号")
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
    }

    private var chapterAndTaskColumn: some View {
        VStack(spacing: 0) {
            if let course = appState.selectedStudyCourse {
                HStack {
                    Toggle("仅看未完成", isOn: Bindable(appState).onlyPendingChapters)
                        .toggleStyle(.switch)
                        .controlSize(.mini)
                    Spacer()
                    Button {
                        appState.selectStudyCourse(course, forceRefresh: true)
                    } label: {
                        Image(systemName: "arrow.clockwise")
                    }
                    .buttonStyle(.borderless)
                    .help("强制刷新章节")
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                Divider()
            }

            if appState.selectedStudyCourse == nil {
                ContentUnavailableView("选择一门课程", systemImage: "book.closed", description: Text("在左侧选择课程以加载章节目录与视频任务点"))
            } else if appState.isLoadingChapters {
                ProgressView("正在解析课程章节...")
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if appState.chapters.isEmpty {
                VStack(spacing: 10) {
                    if let err = appState.studyErrorMessage {
                        ContentUnavailableView(
                            "加载章节失败",
                            systemImage: "exclamationmark.triangle",
                            description: Text(err)
                        )
                    } else {
                        ContentUnavailableView(
                            "暂无已发布章节",
                            systemImage: "doc.text",
                            description: Text("教师尚未在该课程下发布在线章节内容")
                        )
                    }
                    if let course = appState.selectedStudyCourse {
                        Button("重新加载章节") {
                            appState.selectStudyCourse(course, forceRefresh: true)
                        }
                        .buttonStyle(.borderedProminent)
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                List {
                    Section("章节目录 (\(displayedChapters.count)/\(appState.chapters.count))") {
                        ForEach(displayedChapters) { chapter in
                            Button {
                                appState.selectChapter(chapter)
                            } label: {
                                HStack {
                                    VStack(alignment: .leading, spacing: 2) {
                                        if !chapter.sectionTitle.isEmpty {
                                            Text(chapter.sectionTitle)
                                                .font(.caption2)
                                                .foregroundStyle(.secondary)
                                                .lineLimit(1)
                                        }
                                        Text(chapter.title)
                                            .font(.subheadline)
                                            .foregroundStyle(appState.selectedChapter?.id == chapter.id ? CxPalette.accent : .primary)
                                            .lineLimit(2)
                                    }
                                    Spacer()
                                    if let pending = chapter.pendingJobCount {
                                        if pending == 0 {
                                            Image(systemName: "checkmark.circle.fill")
                                                .foregroundStyle(CxPalette.sage)
                                                .font(.caption)
                                        } else {
                                            CxBadge("\(pending)", tone: .amber, monospaced: true)
                                        }
                                    } else {
                                        Image(systemName: "circle.dotted")
                                            .foregroundStyle(.secondary)
                                            .font(.caption)
                                            .help("待检查任务点")
                                    }
                                }
                                .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                        }
                    }

                    if appState.selectedChapter != nil {
                        Section("本节任务点 (\(appState.videos.count + appState.liveTasks.count + appState.chapterAttachments.count))") {
                            if appState.isLoadingVideos {
                                ProgressView("解析多页签任务点...")
                            } else if let err = appState.studyErrorMessage, appState.videos.isEmpty && appState.liveTasks.isEmpty && appState.chapterAttachments.isEmpty {
                                Text("加载失败: \(err)")
                                    .font(.caption)
                                    .foregroundStyle(CxPalette.cinnabar)
                            } else if appState.videos.isEmpty && appState.liveTasks.isEmpty && appState.chapterAttachments.isEmpty {
                                Text("本节暂无任务点")
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            } else {
                                ForEach(appState.videos) { video in
                                    Button {
                                        appState.selectVideo(video)
                                    } label: {
                                        HStack(spacing: 8) {
                                            Image(systemName: video.isPassed ? "checkmark.circle.fill" : (video.isJob ? (video.dtype == "Audio" ? "waveform.circle.fill" : "play.circle.fill") : "film"))
                                                .foregroundStyle(video.isPassed ? CxPalette.sage : (video.isJob ? CxPalette.amber : .secondary))
                                            VStack(alignment: .leading, spacing: 2) {
                                                Text(video.name)
                                                    .font(.subheadline.weight(.medium))
                                                    .foregroundStyle(appState.selectedVideo?.id == video.id ? CxPalette.accent : .primary)
                                                    .lineLimit(2)
                                                HStack(spacing: 6) {
                                                    Text("\(formatDuration(video.totalDuration)) · 服务端\(video.doublespeed == 0 ? "1x" : "2x")")
                                                    if video.dtype == "Audio" {
                                                        Text("音频").foregroundStyle(CxPalette.accent)
                                                    }
                                                    if !video.isJob {
                                                        Text("非任务点").foregroundStyle(.secondary)
                                                    } else if video.requiresFaceCapture {
                                                        Text("含人脸抓拍").foregroundStyle(CxPalette.cinnabar)
                                                    }
                                                }
                                                .font(.caption2)
                                                .foregroundStyle(.secondary)
                                            }
                                            Spacer()
                                        }
                                        .contentShape(Rectangle())
                                    }
                                    .buttonStyle(.plain)
                                }

                                ForEach(appState.liveTasks) { live in
                                    HStack(spacing: 8) {
                                        Image(systemName: live.isPassed ? "checkmark.circle.fill" : "dot.radiowaves.left.and.right")
                                            .foregroundStyle(live.isPassed ? CxPalette.sage : (live.isJob ? CxPalette.amber : .secondary))
                                        VStack(alignment: .leading, spacing: 2) {
                                            Text(live.title)
                                                .font(.subheadline.weight(.medium))
                                                .lineLimit(2)
                                            HStack(spacing: 6) {
                                                Text(live.duration > 0 ? "\(formatDuration(live.duration)) · \(live.statusDescription)" : live.statusDescription)
                                                if live.isPassed {
                                                    Text("已通过").foregroundStyle(CxPalette.sage)
                                                } else if !live.isJob {
                                                    Text("非任务点").foregroundStyle(.secondary)
                                                } else if live.percentValue > 0 {
                                                    Text(String(format: "已看 %.1f%%", live.percentValue)).foregroundStyle(CxPalette.accent)
                                                }
                                            }
                                            .font(.caption2)
                                            .foregroundStyle(.secondary)
                                        }
                                        Spacer()
                                        if live.isReportableTask && !live.isPassed {
                                            Button("挂机") {
                                                appState.startSingleLiveWatch(live)
                                            }
                                            .buttonStyle(.bordered)
                                            .controlSize(.mini)
                                        }
                                        if let webStr = live.webPageURL, let webURL = URL(string: webStr) {
                                            Button {
                                                NSWorkspace.shared.open(webURL)
                                            } label: {
                                                Image(systemName: "safari")
                                            }
                                            .buttonStyle(.borderless)
                                            .help("在浏览器打开 zhibo.chaoxing.com 回放页")
                                        }
                                    }
                                }

                                ForEach(appState.chapterAttachments) { att in
                                    HStack(spacing: 8) {
                                        Image(systemName: att.isPassed ? "checkmark.circle.fill" : att.kind.systemImage)
                                            .foregroundStyle(att.isPassed ? CxPalette.sage : (att.isJob ? CxPalette.accent : .secondary))
                                        VStack(alignment: .leading, spacing: 2) {
                                            Text(att.name)
                                                .font(.caption.weight(.medium))
                                                .lineLimit(2)
                                            Text(att.kind.displayName + (att.isPassed ? " · 已完成" : (att.isJob ? " · 任务点" : " · 资料")))
                                                .font(.caption2)
                                                .foregroundStyle(.secondary)
                                        }
                                        Spacer()
                                        if att.canCompleteAsDocument {
                                            Button {
                                                Task { await appState.completeDocument(att) }
                                            } label: {
                                                if appState.completingDocumentIds.contains(att.id) {
                                                    ProgressView().controlSize(.mini)
                                                } else {
                                                    Text("完成")
                                                }
                                            }
                                            .buttonStyle(.bordered)
                                            .controlSize(.mini)
                                            .disabled(appState.completingDocumentIds.contains(att.id))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @ViewBuilder
    private var videoStudyWorkbench: some View {
        if !appState.batchActiveWorkers.isEmpty || appState.isBatchWatching {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    if let course = appState.selectedStudyCourse {
                        batchCourseAutoWatchCard(for: course)
                    } else if let firstCourse = appState.courses.first {
                        batchCourseAutoWatchCard(for: firstCourse)
                    }
                    logConsoleBox
                }
                .padding(18)
            }
            .background(CxPalette.canvas)
        } else if let video = appState.selectedVideo {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    if let course = appState.selectedStudyCourse {
                        batchCourseAutoWatchCard(for: course)
                    }

                    // Single-video player box with poster & buffering overlay
                    ZStack {
                        MacAVPlayerView(player: appState.playerController.player)
                        if appState.playerController.isBuffering && !appState.playerController.isReady {
                            playerPosterOverlay(
                                screenshotURL: video.screenshotURL,
                                message: "正在缓冲视频流..."
                            )
                        } else if let err = appState.playerController.playerErrorMessage {
                            VStack(spacing: 8) {
                                Image(systemName: "exclamationmark.triangle.fill")
                                    .font(.title2)
                                    .foregroundStyle(CxPalette.amber)
                                Text(err)
                                    .font(.caption)
                                    .foregroundStyle(.white)
                                Button("重新加载视频") {
                                    appState.selectVideo(video, forceReload: true)
                                }
                                .buttonStyle(.borderedProminent)
                                .controlSize(.small)
                            }
                            .padding(16)
                            .frame(maxWidth: .infinity, maxHeight: .infinity)
                            .background(.black.opacity(0.72))
                        }
                    }
                    .frame(height: 340)
                    .clipShape(RoundedRectangle(cornerRadius: 12))
                    .overlay(
                        RoundedRectangle(cornerRadius: 12)
                            .stroke(CxPalette.border, lineWidth: 1)
                    )

                    // Playback speed & transport controls
                    VStack(alignment: .leading, spacing: 10) {
                        HStack {
                            Button {
                                appState.playerController.togglePlayPause()
                            } label: {
                                Label(
                                    appState.playerController.isPlaying ? "暂停" : "播放",
                                    systemImage: appState.playerController.isPlaying ? "pause.fill" : "play.fill"
                                )
                            }
                            .buttonStyle(.borderedProminent)

                            let shownCur = scrubSeconds ?? appState.playerController.currentSeconds
                            let shownDur = max(appState.playerController.durationSeconds, Double(video.totalDuration))
                            Text("\(formatDuration(Int(shownCur))) / \(formatDuration(Int(shownDur)))")
                                .font(CxTypography.mono(12))
                                .foregroundStyle(.secondary)

                            if appState.playerController.isBuffering {
                                ProgressView().controlSize(.small)
                            }

                            Spacer()

                            HStack(spacing: 6) {
                                Text("播放倍速:")
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                                ForEach(ChaoxingPlaybackSpeed.allCases) { speed in
                                    Button(speed.label) {
                                        appState.playerController.setSpeed(speed)
                                    }
                                    .buttonStyle(.bordered)
                                    .tint(appState.playerController.currentSpeed == speed ? CxPalette.accent : .secondary)
                                }
                            }
                        }

                        if video.totalDuration > 0 {
                            let maxSlider = Double(max(1, Int(max(appState.playerController.durationSeconds, Double(video.totalDuration)))))
                            Slider(
                                value: Binding(
                                    get: { min(maxSlider, max(0, scrubSeconds ?? appState.playerController.currentSeconds)) },
                                    set: { newVal in
                                        scrubSeconds = newVal
                                        appState.playerController.seek(to: newVal, emitLog: false)
                                    }
                                ),
                                in: 0...maxSlider,
                                onEditingChanged: { editing in
                                    if !editing, let finalVal = scrubSeconds {
                                        scrubSeconds = nil
                                        appState.playerController.seek(to: finalVal, emitLog: true)
                                    }
                                }
                            )
                        }

                        HStack(spacing: 10) {
                            CxBadge(
                                "内置心跳间隔 \(String(format: "%.1f", appState.playerHeartbeatIntervalSec))s (\(appState.playerController.currentSpeed.label))",
                                tone: appState.playerController.isPlaying ? .accent : .neutral,
                                showDot: true,
                                monospaced: true
                            )
                            if appState.playerController.isPlaying && appState.playerHeartbeatCountdownSec > 0 {
                                Text("下次上报 \(String(format: "%.1f", appState.playerHeartbeatCountdownSec))s")
                                    .font(CxTypography.mono(11))
                                    .foregroundStyle(CxPalette.accent)
                            }
                            if appState.heartbeatSentCount > 0 {
                                Text("已发 \(appState.heartbeatSentCount) 次")
                                    .font(CxTypography.mono(11))
                                    .foregroundStyle(.secondary)
                            }
                            Spacer()
                        }

                        if let summary = appState.lastHeartbeatSummary {
                            Text("最近响应: \(summary)")
                                .font(CxTypography.mono(11))
                                .foregroundStyle(.secondary)
                                .lineLimit(2)
                        }
                    }
                    .padding(14)
                    .background(CxPalette.surface, in: RoundedRectangle(cornerRadius: 12))
                    .overlay(RoundedRectangle(cornerRadius: 12).stroke(CxPalette.border, lineWidth: 1))

                    // Auto-watch legal heartbeat console
                    autoWatchControlCard(for: video)

                    // Protocol log console
                    logConsoleBox
                }
                .padding(18)
            }
            .background(CxPalette.canvas)
        } else if let course = appState.selectedStudyCourse {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    batchCourseAutoWatchCard(for: course)

                    if !appState.liveTasks.isEmpty {
                        liveReplayTasksCard
                    }

                    if !appState.chapterAttachments.isEmpty {
                        nonVideoAttachmentsCard
                    } else if appState.liveTasks.isEmpty {
                        ContentUnavailableView(
                            appState.chapters.isEmpty ? "当前课程暂无在线章节" : "选择视频/直播回放章节或点击「同时看」",
                            systemImage: appState.chapters.isEmpty ? "doc.text" : "play.square.stack",
                            description: Text(
                                appState.chapters.isEmpty
                                    ? "可在左侧切换其他有章节的课程，或开启“自动匹配其他课程”后再点击同时看"
                                    : "点击上方“同时看本课”将 6 路并发高速扫描并立即同步开播 \(appState.maxConcurrentWatches) 路画面（自动含普通视频与 zhibo.chaoxing.com 直播回放）"
                            )
                        )
                        .frame(minHeight: 220)
                    }

                    logConsoleBox
                }
                .padding(18)
            }
            .background(CxPalette.canvas)
        } else {
            ContentUnavailableView("选择视频任务点", systemImage: "play.rectangle", description: Text("在左侧选择一门课程即可开启同步看课"))
        }
    }

    private var liveReplayTasksCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text("当前章节直播回放（zhibo.chaoxing.com · \(appState.liveTasks.count)）")
                    .font(.headline)
                Spacer()
                if let course = appState.selectedStudyCourse, !appState.isBatchWatching {
                    Button("并行同时看本课 (\(appState.maxConcurrentWatches)路)") {
                        appState.startCourseBatchAutoWatch(for: course)
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(CxPalette.sage)
                    .controlSize(.small)
                }
            }
            Text("按官方 30s 真实时钟心跳同步（服务端每 60s 入库刷新），支持多场直播回放同时并行播放")
                .font(.caption)
                .foregroundStyle(.secondary)
            ForEach(appState.liveTasks) { live in
                VStack(alignment: .leading, spacing: 6) {
                    HStack(spacing: 10) {
                        Image(systemName: live.isPassed ? "checkmark.circle.fill" : "dot.radiowaves.left.and.right")
                            .foregroundStyle(live.isPassed ? CxPalette.sage : (live.isJob ? CxPalette.amber : .secondary))
                        VStack(alignment: .leading, spacing: 2) {
                            Text(live.title)
                                .font(.subheadline.weight(.medium))
                            Text("\(live.statusDescription) · 时长 \(formatDuration(live.duration)) · 达标 \(Int(round(live.rt * 100)))% · 服务端已计 \(String(format: "%.1f%%", live.percentValue))")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                        Spacer()
                        if live.isPassed {
                            CxBadge("已通过", tone: .sage)
                        } else if !live.isJob {
                            CxBadge("非任务点", tone: .neutral)
                        } else if live.isReportableTask {
                            Button("单场合法挂机") {
                                appState.startSingleLiveWatch(live)
                            }
                            .buttonStyle(.borderedProminent)
                            .tint(CxPalette.accent)
                            .controlSize(.small)
                        } else {
                            CxBadge(live.statusDescription, tone: .neutral)
                        }
                        if let webStr = live.webPageURL, let webURL = URL(string: webStr) {
                            Button("浏览器打开") {
                                NSWorkspace.shared.open(webURL)
                            }
                            .buttonStyle(.bordered)
                            .controlSize(.small)
                        }
                    }
                    if live.duration > 0 {
                        ProgressView(value: min(100.0, max(0.0, live.percentValue)), total: 100.0)
                            .tint(live.isPassed ? CxPalette.sage : CxPalette.accent)
                    }
                }
                .padding(.vertical, 4)
            }
        }
        .padding(14)
        .background(CxPalette.surface, in: RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(CxPalette.border, lineWidth: 1))
    }

    private var nonVideoAttachmentsCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("当前章节非视频任务点 (\(appState.chapterAttachments.count))")
                .font(.headline)
            ForEach(appState.chapterAttachments) { att in
                HStack(spacing: 10) {
                    Image(systemName: att.isPassed ? "checkmark.circle.fill" : att.kind.systemImage)
                        .foregroundStyle(att.isPassed ? CxPalette.sage : CxPalette.accent)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(att.name)
                            .font(.subheadline.weight(.medium))
                        Text("\(att.kind.displayName) · \(att.isPassed ? "已完成" : (att.canCompleteAsDocument ? "支持一键标记已读" : "需在网页/客户端内完成"))")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    Spacer()
                    if att.canCompleteAsDocument {
                        Button {
                            Task { await appState.completeDocument(att) }
                        } label: {
                            if appState.completingDocumentIds.contains(att.id) {
                                ProgressView().controlSize(.small)
                            } else {
                                Text("一键完成文档")
                            }
                        }
                        .buttonStyle(.borderedProminent)
                        .tint(CxPalette.sage)
                        .disabled(appState.completingDocumentIds.contains(att.id))
                    } else if let extStr = att.externalURL, let extURL = URL(string: extStr) {
                        Button("打开外链") {
                            NSWorkspace.shared.open(extURL)
                        }
                        .buttonStyle(.bordered)
                        .controlSize(.small)
                    } else {
                        CxBadge(att.isPassed ? "已通过" : att.kind.displayName, tone: att.isPassed ? .sage : .neutral)
                    }
                }
                .padding(.vertical, 4)
            }
        }
        .padding(14)
        .background(CxPalette.surface, in: RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(CxPalette.border, lineWidth: 1))
    }

    private func playerPosterOverlay(screenshotURL: String?, message: String) -> some View {
        ZStack {
            if let shot = screenshotURL, let url = URL(string: shot) {
                AsyncImage(url: url) { phase in
                    if case .success(let image) = phase {
                        image
                            .resizable()
                            .scaledToFill()
                    } else {
                        Color.black
                    }
                }
            } else {
                Color.black
            }
            Color.black.opacity(0.45)
            HStack(spacing: 8) {
                ProgressView()
                    .controlSize(.small)
                    .tint(.white)
                Text(message)
                    .font(.caption.weight(.medium))
                    .foregroundStyle(.white)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
            .background(.black.opacity(0.65), in: Capsule())
        }
    }

    private var logConsoleBox: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text("播放与心跳日志")
                    .font(.headline)
                Spacer()
                Button("清空") { appState.logs.removeAll() }
                    .buttonStyle(.borderless)
                    .font(.caption)
            }
            if appState.logs.isEmpty {
                Text("暂无日志")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            } else {
                ForEach(appState.logs.prefix(30)) { line in
                    HStack(alignment: .top, spacing: 8) {
                        Text(line.time)
                            .font(CxTypography.mono(11))
                            .foregroundStyle(.secondary)
                        Text(line.message)
                            .font(CxTypography.mono(11))
                            .foregroundStyle(line.isError ? CxPalette.cinnabar : .primary)
                    }
                }
            }
        }
        .padding(14)
        .background(CxPalette.surface, in: RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(CxPalette.border, lineWidth: 1))
    }

    private func batchCourseAutoWatchCard(for course: ChaoxingCourse) -> some View {
        let headerTitle = appState.isBatchWatching && !appState.batchScopeLabel.isEmpty
            ? "同时看（多路同步播放）· \(appState.batchScopeLabel)"
            : "同时看（多路同步播放）· \(course.courseName)"

        return VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 3) {
                    Text(headerTitle)
                        .font(.headline)
                    Text(
                        appState.watchAllCoursesAutomatically
                            ? "已开启跨课续看：先看本课，再自动匹配并续看其他课程未完成任务点"
                            : "仅看当前选中课程未完成任务点（跳过已完成章节，防休眠 + 合法心跳）"
                    )
                    .font(.caption)
                    .foregroundStyle(.secondary)
                }
                Spacer()

                if appState.isBatchWatching {
                    Button("停止同时看", role: .destructive) {
                        appState.stopBatchAutoWatch()
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(CxPalette.cinnabar)
                } else {
                    Button(
                        appState.watchAllCoursesAutomatically
                            ? "跨课同时看 (\(appState.maxConcurrentWatches)路)"
                            : "同时看本课 (\(appState.maxConcurrentWatches)路)"
                    ) {
                        appState.startCourseBatchAutoWatch(for: course)
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(CxPalette.sage)
                }
            }

            HStack(spacing: 14) {
                Picker("同时路数", selection: Bindable(appState).maxConcurrentWatches) {
                    Text("1 路").tag(1)
                    Text("2 路").tag(2)
                    Text("3 路").tag(3)
                    Text("5 路").tag(5)
                }
                .frame(width: 125)
                .disabled(appState.isBatchWatching)

                Toggle("自动匹配其他课程", isOn: Bindable(appState).watchAllCoursesAutomatically)
                    .toggleStyle(.checkbox)
                    .font(.caption)
                    .disabled(appState.isBatchWatching)
                    .help("关闭时仅观看当前选中的一门课程；开启后看完本课会自动续看其他课程")

                Toggle("顺便完成文档", isOn: Bindable(appState).autoCompleteDocuments)
                    .toggleStyle(.checkbox)
                    .font(.caption)
                    .disabled(appState.isBatchWatching)

                Spacer()
            }

            if let scanMsg = appState.batchScanProgress {
                HStack(spacing: 8) {
                    ProgressView().controlSize(.small)
                    Text(scanMsg)
                        .font(.caption)
                        .foregroundStyle(CxPalette.accent)
                }
            }

            if appState.batchCooldownRemainingSec > 0 {
                HStack(spacing: 8) {
                    Image(systemName: "clock.badge.exclamationmark")
                        .foregroundStyle(CxPalette.amber)
                    Text("服务端 403 限流冷却中：剩余 \(appState.batchCooldownRemainingSec) 秒后自动补位队列...")
                        .font(.caption.weight(.medium))
                        .foregroundStyle(CxPalette.amber)
                }
                .padding(.horizontal, 10)
                .padding(.vertical, 6)
                .background(CxPalette.amber.opacity(0.12), in: RoundedRectangle(cornerRadius: 8))
            }

            if appState.isBatchWatching || appState.batchTotalDiscoveredCount > 0 || appState.batchCompletedDocsCount > 0 {
                HStack(spacing: 10) {
                    CxBadge(
                        "已完成 \(appState.batchCompletedCount)/\(appState.batchTotalDiscoveredCount)",
                        tone: appState.batchCompletedCount == appState.batchTotalDiscoveredCount && appState.batchTotalDiscoveredCount > 0 ? .sage : .accent,
                        showDot: true,
                        monospaced: true
                    )
                    Text("同步播放中 \(appState.batchActiveWorkers.count) 路 · 队列剩余 \(appState.batchQueueCount)")
                        .font(CxTypography.mono(11))
                        .foregroundStyle(.secondary)
                    if appState.batchCompletedDocsCount > 0 {
                        Text("· 已完成文档 \(appState.batchCompletedDocsCount)")
                            .font(CxTypography.mono(11))
                            .foregroundStyle(CxPalette.sage)
                    }
                    if appState.batchSkippedCount > 0 {
                        Text("· 跳过 \(appState.batchSkippedCount)")
                            .font(CxTypography.mono(11))
                            .foregroundStyle(CxPalette.amber)
                    }
                }
            }

            if !appState.batchActiveWorkers.isEmpty {
                let columns = [GridItem(.adaptive(minimum: 280), spacing: 12)]
                LazyVGrid(columns: columns, spacing: 12) {
                    ForEach(appState.batchActiveWorkers) { worker in
                        MacBatchWorkerTile(
                            worker: worker,
                            onToggleMute: { appState.toggleBatchPlayerMute(taskId: worker.id) },
                            onSkip: { appState.skipBatchWorker(taskId: worker.id) }
                        )
                    }
                }
            }
        }
        .padding(14)
        .background(CxPalette.surface, in: RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(CxPalette.border, lineWidth: 1))
    }

    private func autoWatchControlCard(for video: ChaoxingVideoTask) -> some View {
        let isThisWatching = appState.isAutoWatching && appState.autoWatchTaskId == video.id
        let duration = max(1, video.effectiveClipEnd)
        let displayed = isThisWatching ? appState.autoWatchDisplayedSec : (video.isPassed ? duration : video.headOffsetMs / 1000)

        return VStack(alignment: .leading, spacing: 12) {
            HStack {
                VStack(alignment: .leading, spacing: 3) {
                    Text(video.name)
                        .font(.headline)
                    Text("区间 \(video.effectiveClipTime) · 达标比例 rt=\(String(format: "%.0f%%", video.rt * 100))（需 \(formatDuration(video.requiredWatchSeconds))）· 心跳间隔 \(video.reportTimeInterval)s · 服务端 \(Int(video.allowedMaxRate))x")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Spacer()
                CxBadge(
                    video.isPassed ? "已通过" : (!video.isJob ? "非任务点" : (isThisWatching ? "挂机中" : "未完成")),
                    tone: video.isPassed ? .sage : (!video.isJob ? .neutral : (isThisWatching ? .accent : .amber)),
                    showDot: true
                )
            }

            ProgressView(value: Double(min(displayed, duration)), total: Double(duration))
                .tint(video.isPassed ? CxPalette.sage : CxPalette.accent)

            HStack {
                Text("有效进度: \(formatDuration(displayed)) / \(formatDuration(duration))")
                    .font(CxTypography.mono(12))
                if isThisWatching && appState.autoWatchNextTickInSec > 0 {
                    Text("· 下次心跳 \(appState.autoWatchNextTickInSec)s")
                        .font(CxTypography.mono(12))
                        .foregroundStyle(CxPalette.accent)
                }
                Spacer()
                Toggle("通过后自动挂下一节", isOn: Bindable(appState).autoAdvanceNextChapter)
                    .toggleStyle(.switch)
                    .controlSize(.small)

                if isThisWatching {
                    Button("停止挂机", role: .destructive) {
                        appState.stopAutoWatch()
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(CxPalette.cinnabar)
                } else {
                    Button(video.isPassed ? "重新挂机" : "单视频合法挂机") {
                        appState.startAutoWatch(for: video)
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(CxPalette.accent)
                    .disabled(!video.isReportableTask)
                }
            }
        }
        .padding(14)
        .background(CxPalette.surface, in: RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(CxPalette.border, lineWidth: 1))
    }

    private func formatDuration(_ sec: Int) -> String {
        let s = max(0, sec)
        let m = s / 60
        let r = s % 60
        return String(format: "%02d:%02d", m, r)
    }
}

/// Isolated tile view observing its own `@Observable` `MacBatchWatchWorker` so 500ms progress ticks
/// never force sibling tiles or `MacAVPlayerView` wrappers to re-evaluate.
private struct MacBatchWorkerTile: View {
    let worker: MacBatchWatchWorker
    let onToggleMute: () -> Void
    let onSkip: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            ZStack {
                MacAVPlayerView(player: worker.player)
                if worker.isBuffering && !worker.isPlayerReady {
                    ZStack {
                        if let shot = worker.screenshotURL, let url = URL(string: shot) {
                            AsyncImage(url: url) { phase in
                                if case .success(let image) = phase {
                                    image.resizable().scaledToFill()
                                } else {
                                    Color.black
                                }
                            }
                        } else {
                            Color.black
                        }
                        Color.black.opacity(0.45)
                        HStack(spacing: 6) {
                            ProgressView().controlSize(.small).tint(.white)
                            Text("画面缓冲中...")
                                .font(.caption2.weight(.medium))
                                .foregroundStyle(.white)
                        }
                        .padding(.horizontal, 10)
                        .padding(.vertical, 4)
                        .background(.black.opacity(0.65), in: Capsule())
                    }
                } else if let err = worker.playerError {
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
            }
            .frame(height: 170)
            .clipShape(RoundedRectangle(cornerRadius: 8))

            HStack(spacing: 6) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("[\(worker.courseName)] \(worker.chapterTitle)")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                    HStack(spacing: 4) {
                        if worker.isLiveReplay {
                            CxBadge("直播回放", tone: .amber)
                        }
                        Text(worker.title)
                            .font(.caption.weight(.semibold))
                            .lineLimit(1)
                    }
                }
                Spacer()
                if let webStr = worker.webPageURL, let webURL = URL(string: webStr) {
                    Button {
                        NSWorkspace.shared.open(webURL)
                    } label: {
                        Image(systemName: "safari")
                            .foregroundStyle(.secondary)
                    }
                    .buttonStyle(.borderless)
                    .help("在浏览器中打开原直播回放页")
                }
                Button(action: onToggleMute) {
                    Image(systemName: worker.isMuted ? "speaker.slash.fill" : "speaker.wave.2.fill")
                        .foregroundStyle(worker.isMuted ? .secondary : CxPalette.accent)
                }
                .buttonStyle(.borderless)
                .help(worker.isMuted ? "取消静音" : "静音")

                Button(action: onSkip) {
                    Image(systemName: "forward.end.fill")
                        .foregroundStyle(.secondary)
                }
                .buttonStyle(.borderless)
                .help("跳过此视频，立即切下一节")
            }

            ProgressView(
                value: Double(min(worker.displayedSec, max(1, worker.duration))),
                total: Double(max(1, worker.duration))
            )
            .tint(CxPalette.sage)

            HStack {
                Text("\(formatDuration(worker.displayedSec)) / \(formatDuration(worker.duration))")
                    .font(CxTypography.mono(11))
                Text(worker.isLiveReplay ? "(入库 \(formatDuration(worker.reportedSec)))" : "(确认 \(formatDuration(worker.reportedSec)))")
                    .font(CxTypography.mono(10))
                    .foregroundStyle(.secondary)
                Spacer()
                if worker.nextTickInSec > 0 {
                    Text(worker.isLiveReplay ? "30s心跳 · \(worker.nextTickInSec)s" : "\(Int(worker.legalRate))x · 心跳 \(worker.nextTickInSec)s")
                        .font(CxTypography.mono(11))
                        .foregroundStyle(CxPalette.accent)
                }
            }
        }
        .padding(10)
        .background(CxPalette.surfaceSecondary, in: RoundedRectangle(cornerRadius: 10))
    }

    private func formatDuration(_ sec: Int) -> String {
        let s = max(0, sec)
        let m = s / 60
        let r = s % 60
        return String(format: "%02d:%02d", m, r)
    }
}

/// `AVPlayerView` subclass that prevents macOS trackpad / mouse-wheel scrolling from
/// accidentally scrubbing or fast-forwarding the video timeline, forwarding scroll gestures
/// cleanly to the enclosing `NSScrollView` instead.
private final class ScrollSafeAVPlayerView: AVPlayerView {
    private var scrollMonitor: Any?

    override func viewDidMoveToWindow() {
        super.viewDidMoveToWindow()
        if window != nil {
            installScrollMonitorIfNeeded()
        } else {
            removeScrollMonitor()
        }
    }

    override func scrollWheel(with event: NSEvent) {
        if let scrollView = findEnclosingScrollView() {
            scrollView.scrollWheel(with: event)
        }
    }

    func installScrollMonitorIfNeeded() {
        guard scrollMonitor == nil else { return }
        scrollMonitor = NSEvent.addLocalMonitorForEvents(matching: .scrollWheel) { [weak self] event in
            guard let self,
                  let win = self.window,
                  event.window === win,
                  !self.isHiddenOrHasHiddenAncestor,
                  let contentView = win.contentView else {
                return event
            }
            let targetPoint = contentView.superview?.convert(event.locationInWindow, from: nil) ?? event.locationInWindow
            guard let hitView = contentView.hitTest(targetPoint),
                  hitView.isDescendant(of: self) else {
                return event
            }
            if let scrollView = self.findEnclosingScrollView() {
                scrollView.scrollWheel(with: event)
            }
            return nil
        }
    }

    func removeScrollMonitor() {
        if let monitor = scrollMonitor {
            NSEvent.removeMonitor(monitor)
            scrollMonitor = nil
        }
    }

    private func findEnclosingScrollView() -> NSScrollView? {
        if let direct = enclosingScrollView {
            return direct
        }
        var current = superview
        while let view = current {
            if let sv = view as? NSScrollView {
                return sv
            }
            current = view.superview
        }
        return nil
    }
}

private struct MacAVPlayerView: NSViewRepresentable {
    let player: AVPlayer

    func makeNSView(context: Context) -> ScrollSafeAVPlayerView {
        let view = ScrollSafeAVPlayerView()
        view.controlsStyle = .inline
        view.player = player
        return view
    }

    func updateNSView(_ nsView: ScrollSafeAVPlayerView, context: Context) {
        if nsView.player !== player {
            nsView.player = player
        }
    }

    static func dismantleNSView(_ nsView: ScrollSafeAVPlayerView, coordinator: ()) {
        nsView.removeScrollMonitor()
        nsView.player = nil
    }
}

struct MacLoginScreen: View {
    @Environment(MacAppState.self) private var appState
    @Environment(\.dismiss) private var dismiss
    var isSheet = false

    @State private var phone = ""
    @State private var password = ""
    @State private var remark = ""
    @State private var switchImmediately = true

    var body: some View {
        VStack(spacing: 16) {
            Image(systemName: isSheet ? "person.badge.plus" : "graduationcap.circle.fill")
                .font(.system(size: 44))
                .foregroundStyle(CxPalette.accent)
            Text(isSheet ? "添加超星账号" : "登录超星学习通")
                .font(.title2.weight(.semibold))
            Text("多账号凭据仅保存在本机 ~/Library/Application Support/Caochaoxing/state.json")
                .font(.caption)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)

            VStack(spacing: 10) {
                TextField("手机号", text: $phone)
                    .textFieldStyle(.roundedBorder)
                SecureField("密码", text: $password)
                    .textFieldStyle(.roundedBorder)
                    .onSubmit { submit() }
                TextField("备注标签（可选，如 本人 / 小号 / 室友）", text: $remark)
                    .textFieldStyle(.roundedBorder)
                    .onSubmit { submit() }
                if isSheet && appState.activeAccount != nil {
                    Toggle("登录后立即切换为当前账号", isOn: $switchImmediately)
                        .toggleStyle(.checkbox)
                        .font(.caption)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
            .frame(maxWidth: 320)

            if let error = appState.loginErrorMessage, !error.isEmpty {
                Text(error)
                    .font(.caption)
                    .foregroundStyle(CxPalette.cinnabar)
            }

            HStack(spacing: 12) {
                if isSheet {
                    Button("取消") { dismiss() }
                        .buttonStyle(.bordered)
                }
                Button {
                    submit()
                } label: {
                    if appState.isLoggingIn {
                        ProgressView().controlSize(.small)
                    } else {
                        Text(isSheet ? (switchImmediately ? "登录并切换" : "添加账号") : "登录")
                    }
                }
                .buttonStyle(.borderedProminent)
                .disabled(appState.isLoggingIn)
            }
        }
        .padding(28)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private func submit() {
        Task {
            await appState.login(
                phoneNumber: phone,
                password: password,
                remark: remark,
                switchImmediately: isSheet ? switchImmediately : true
            )
            if isSheet && appState.loginErrorMessage == nil {
                dismiss()
            }
        }
    }
}
#endif
