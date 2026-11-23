// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

import SwiftUI
import ChaoxingCore

private enum IOSCourseSplitSelection: Hashable {
    case course(ChaoxingCourse)
    case activity(ChaoxingSignActivity)
}

enum IOSCourseDetailSegment: String, CaseIterable, Identifiable {
    case activities = "签到活动"
    case study = "课程看课"

    var id: String { rawValue }
}

struct IOSCourseListView: View {
    @Environment(IOSAppState.self) private var appState
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass

    @State private var searchText = ""
    @State private var splitSelection: IOSCourseSplitSelection?
    @State private var preferredDetailSegment: IOSCourseDetailSegment = .activities
    @State private var compactNavigationPath = NavigationPath()

    private var filteredCourses: [ChaoxingCourse] {
        let query = searchText.trimmingCharacters(in: .whitespacesAndNewlines)
        let base: [ChaoxingCourse]
        if query.isEmpty {
            base = appState.courses
        } else {
            base = appState.courses.filter {
                $0.courseName.localizedCaseInsensitiveContains(query) ||
                $0.teacherName.localizedCaseInsensitiveContains(query)
            }
        }
        return base.sorted { lhs, rhs in
            let lStar = appState.starredCourseIds.contains(lhs.courseId)
            let rStar = appState.starredCourseIds.contains(rhs.courseId)
            if lStar != rStar { return lStar }
            return false
        }
    }

    var body: some View {
        Group {
            if horizontalSizeClass == .regular {
                iPadSplitLayout
            } else {
                iPhoneStackLayout
            }
        }
        .onChange(of: appState.activePhoneNumber) { _, _ in
            splitSelection = nil
            compactNavigationPath = NavigationPath()
        }
        .onChange(of: appState.courses) { _, newCourses in
            guard horizontalSizeClass == .regular else { return }
            if case .course(let current) = splitSelection,
               newCourses.contains(where: { $0.id == current.id }) {
                return
            }
            if splitSelection == nil, let first = filteredCourses.first {
                splitSelection = .course(first)
            }
        }
    }

    // MARK: - iPadOS Adaptive Two-Column Split Layout

    private var iPadSplitLayout: some View {
        NavigationSplitView {
            courseListContent(isSplitMode: true)
                .navigationTitle("课程")
                .searchable(text: $searchText, prompt: "搜索课程或教师")
                .refreshable {
                    await appState.refreshCourses()
                }
                .toolbar {
                    accountSwitcherToolbar
                }
        } detail: {
            NavigationStack {
                Group {
                    switch splitSelection {
                    case .course(let course):
                        IOSCourseDetailView(course: course, selectedSegment: $preferredDetailSegment)
                            .id(course.id)
                    case .activity(let activity):
                        IOSSignWorkspaceView(activity: activity)
                            .id(activity.id)
                    case nil:
                        if let first = filteredCourses.first {
                            IOSCourseDetailView(course: first, selectedSegment: $preferredDetailSegment)
                                .id(first.id)
                        } else {
                            ContentUnavailableView(
                                "选择一门课程",
                                systemImage: "books.vertical",
                                description: Text("在左侧选择课程查看签到活动、章节任务点或开启多路并行看课")
                            )
                            .foregroundStyle(CxPalette.inkSecondary)
                        }
                    }
                }
                .navigationDestination(for: ChaoxingSignActivity.self) { activity in
                    IOSSignWorkspaceView(activity: activity)
                }
            }
        }
        .navigationSplitViewStyle(.balanced)
    }

    // MARK: - iPhone Compact Stack Layout

    private var iPhoneStackLayout: some View {
        NavigationStack(path: $compactNavigationPath) {
            courseListContent(isSplitMode: false)
                .navigationTitle("课程")
                .searchable(text: $searchText, prompt: "搜索课程或教师")
                .refreshable {
                    await appState.refreshCourses()
                }
                .navigationDestination(for: ChaoxingCourse.self) { course in
                    IOSCourseDetailView(course: course, selectedSegment: $preferredDetailSegment)
                }
                .navigationDestination(for: ChaoxingSignActivity.self) { activity in
                    IOSSignWorkspaceView(activity: activity)
                }
                .toolbar {
                    accountSwitcherToolbar
                }
        }
    }

    // MARK: - Shared Account Switcher Menu

    @ToolbarContentBuilder
    private var accountSwitcherToolbar: some ToolbarContent {
        ToolbarItem(placement: .topBarTrailing) { AppUpdateButton() }
        if let account = appState.activeAccount {
            ToolbarItem(placement: .topBarTrailing) {
                Menu {
                    if appState.accounts.count > 1 {
                        Section("快速切换账号") {
                            ForEach(appState.accounts) { item in
                                let isCurrent = item.phoneNumber == account.phoneNumber
                                Button {
                                    Task { await appState.switchActiveAccount(to: item) }
                                } label: {
                                    let title = item.name.isEmpty ? item.phoneNumber : "\(item.name) (\(item.phoneNumber))"
                                    if isCurrent {
                                        Label(title, systemImage: "checkmark")
                                    } else {
                                        Text(title)
                                    }
                                }
                                .disabled(isCurrent)
                            }
                        }
                        Divider()
                    }

                    Button {
                        Task { await appState.refreshActiveAccountSession() }
                    } label: {
                        Label("刷新当前账号会话", systemImage: "arrow.clockwise")
                    }

                    Button {
                        appState.selectedTab = 3
                    } label: {
                        Label("添加或管理账号", systemImage: "person.2.badge.gearshape")
                    }
                } label: {
                    HStack(spacing: 5) {
                        Image(systemName: "person.crop.circle")
                            .font(.caption.weight(.semibold))
                        Text(account.name.isEmpty ? account.phoneNumber : account.name)
                            .font(.caption.weight(.medium))
                            .lineLimit(1)
                        if appState.accounts.count > 1 {
                            Text("\(appState.accounts.count)")
                                .font(CxTypography.mono(10, weight: .semibold))
                                .padding(.horizontal, 5)
                                .padding(.vertical, 1)
                                .background(CxPalette.accent.opacity(0.16), in: Capsule())
                                .foregroundStyle(CxPalette.accent)
                        }
                        Image(systemName: "chevron.down")
                            .font(.system(size: 9, weight: .semibold))
                            .foregroundStyle(CxPalette.inkTertiary)
                    }
                    .foregroundStyle(CxPalette.ink)
                    .padding(.horizontal, 9)
                    .padding(.vertical, 5)
                    .background(CxPalette.surfaceSecondary, in: Capsule())
                    .overlay(Capsule().strokeBorder(CxPalette.border, lineWidth: 0.8))
                }
            }
        }
    }

    // MARK: - Shared Course List Body

    private func courseListContent(isSplitMode: Bool) -> some View {
        let study = appState.studyController

        return List {
            // Active study floating banner
            if study.isWatching {
                Section {
                    VStack(alignment: .leading, spacing: 8) {
                        HStack(spacing: 10) {
                            CxIconTile("play.square.stack.fill", tone: .sage, size: 34)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(study.watchScopeLabel.isEmpty ? "正在同步看课" : study.watchScopeLabel)
                                    .font(.subheadline.weight(.semibold))
                                    .foregroundStyle(CxPalette.ink)
                                    .lineLimit(1)
                                Text("已完成 \(study.completedCount)/\(study.totalDiscoveredCount) · 并行 \(study.activeWorkers.count) 路\(study.cooldownRemainingSec > 0 ? " · 冷却 \(study.cooldownRemainingSec)s" : "")")
                                    .font(CxTypography.mono(11))
                                    .foregroundStyle(CxPalette.inkSecondary)
                            }
                            Spacer(minLength: 4)
                            Button("停止", role: .destructive) {
                                study.stopWatch()
                            }
                            .buttonStyle(.borderedProminent)
                            .tint(CxPalette.cinnabar)
                            .controlSize(.small)
                        }
                        if let activeId = study.activeWatchCourseId,
                           let studyingCourse = appState.courses.first(where: { $0.id == activeId }) {
                            Button {
                                preferredDetailSegment = .study
                                if isSplitMode {
                                    splitSelection = .course(studyingCourse)
                                } else {
                                    compactNavigationPath.append(studyingCourse)
                                }
                            } label: {
                                Label("查看并行看课面板", systemImage: "arrow.right.circle")
                                    .font(.caption.weight(.semibold))
                                    .foregroundStyle(CxPalette.accent)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .padding(.vertical, 4)
                    .cxCardRow()
                } header: {
                    CxSectionHeader("进行中的看课任务", trailing: "防息屏运行中")
                }
            }

            // Find pending activities in the selected courses.
            if !appState.courses.isEmpty {
                Section {
                    HStack(spacing: 12) {
                        CxIconTile("dot.radiowaves.left.and.right", tone: .accent, size: 38)

                        VStack(alignment: .leading, spacing: 3) {
                            Text("查找待签活动")
                                .font(CxTypography.serifHeadline(16))
                                .foregroundStyle(CxPalette.ink)
                            Text(appState.starredCourseIds.isEmpty
                                 ? "扫描近期我的课程中正在进行的考勤"
                                 : "优先扫描已标星的 \(appState.starredCourseIds.count) 门常用课程")
                                .font(.caption)
                                .foregroundStyle(CxPalette.inkSecondary)
                        }

                        Spacer(minLength: 8)

                        Button {
                            Task { await appState.scanPendingActivities() }
                        } label: {
                            HStack(spacing: 5) {
                                if appState.isScanningRadar {
                                    ProgressView()
                                        .controlSize(.small)
                                        .tint(CxPalette.onAccent)
                                } else {
                                    Image(systemName: "waveform.path.ecg")
                                        .font(.caption.weight(.semibold))
                                }
                                Text(appState.isScanningRadar ? "扫描中" : "扫描")
                                    .font(.caption.weight(.semibold))
                            }
                        }
                        .buttonStyle(CxPrimaryButtonStyle())
                        .disabled(appState.isScanningRadar)
                    }
                    .padding(.vertical, 4)
                    .cxCardRow()

                    ForEach(appState.pendingRadarActivities) { act in
                        if isSplitMode {
                            Button {
                                splitSelection = .activity(act)
                            } label: {
                                radarRowLabel(for: act)
                            }
                            .buttonStyle(.plain)
                            .cxCardRow()
                        } else {
                            NavigationLink(value: act) {
                                radarRowLabel(for: act)
                            }
                            .cxCardRow()
                        }
                    }
                } header: {
                    CxSectionHeader(
                        "待签活动",
                        trailing: appState.starredCourseIds.isEmpty
                            ? "全部课程"
                            : "常用课程 \(appState.starredCourseIds.count) 门"
                    )
                }
            }

            // Courses
            Section {
                if filteredCourses.isEmpty {
                    ContentUnavailableView(
                        searchText.isEmpty ? "暂无课程数据" : "未找到匹配课程",
                        systemImage: "books.vertical",
                        description: Text(searchText.isEmpty ? "下拉刷新以从学习通同步我的课程" : "请尝试更换课程名或教师姓名关键词")
                    )
                    .foregroundStyle(CxPalette.inkSecondary)
                    .cxCardRow()
                } else {
                    ForEach(filteredCourses) { course in
                        let isStarred = appState.starredCourseIds.contains(course.courseId)
                        let isSelectedInSplit: Bool = {
                            if case .course(let sel) = splitSelection { return sel.id == course.id }
                            return false
                        }()

                        Group {
                            if isSplitMode {
                                Button {
                                    splitSelection = .course(course)
                                } label: {
                                    courseRowLabel(for: course, isStarred: isStarred, isSelected: isSelectedInSplit)
                                }
                                .buttonStyle(.plain)
                            } else {
                                NavigationLink(value: course) {
                                    courseRowLabel(for: course, isStarred: isStarred, isSelected: false)
                                }
                            }
                        }
                        .cxCardRow()
                        .swipeActions(edge: .trailing) {
                            Button {
                                Task { await appState.toggleCourseStar(course.courseId) }
                            } label: {
                                Label(isStarred ? "取消常用" : "标为常用", systemImage: isStarred ? "bookmark.slash" : "bookmark.fill")
                            }
                            .tint(CxPalette.amber)
                        }
                    }
                }
            } header: {
                CxSectionHeader(
                    "我的课程",
                    trailing: "\(filteredCourses.count) 门 · 左滑设为常用"
                )
            }
        }
        .listStyle(.insetGrouped)
        .cxEditorialList()
    }

    private func radarRowLabel(for act: ChaoxingSignActivity) -> some View {
        HStack(spacing: 11) {
            CxIconTile(act.signType.systemImage, tone: .amber, size: 32)

            VStack(alignment: .leading, spacing: 3) {
                Text(act.courseName.isEmpty ? act.nameOne : "\(act.courseName) · \(act.nameOne)")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(CxPalette.ink)
                    .fixedSize(horizontal: false, vertical: true)
                Text(act.signType.displayName)
                    .font(CxTypography.mono(11))
                    .foregroundStyle(CxPalette.inkSecondary)
            }

            Spacer()

            CxBadge("待签到", tone: .amber, showDot: true)
        }
        .padding(.vertical, 2)
    }

    private func courseRowLabel(for course: ChaoxingCourse, isStarred: Bool, isSelected: Bool) -> some View {
        HStack(spacing: 13) {
            AsyncImage(url: URL(string: course.imageURL)) { image in
                image.resizable().scaledToFill()
            } placeholder: {
                ZStack {
                    CxPalette.surfaceSecondary
                    Image(systemName: "book.closed")
                        .font(.system(size: 16, weight: .medium))
                        .foregroundStyle(CxPalette.accent)
                }
            }
            .frame(width: 58, height: 44)
            .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 8, style: .continuous)
                    .strokeBorder(isSelected ? CxPalette.accent : CxPalette.border, lineWidth: isSelected ? 1.5 : 0.8)
            )

            VStack(alignment: .leading, spacing: 4) {
                HStack(spacing: 5) {
                    if isStarred {
                        Image(systemName: "bookmark.fill")
                            .font(.system(size: 11))
                            .foregroundStyle(CxPalette.amber)
                    }
                    Text(course.courseName)
                        .font(CxTypography.serifHeadline(16.5))
                        .foregroundStyle(isSelected ? CxPalette.accent : CxPalette.ink)
                        .fixedSize(horizontal: false, vertical: true)
                }

                HStack(spacing: 6) {
                    Text(course.teacherName.isEmpty ? "未提供教师信息" : course.teacherName)
                        .font(.caption)
                        .foregroundStyle(CxPalette.inkSecondary)
                        .fixedSize(horizontal: false, vertical: true)

                    if let bound = appState.boundLocation(forCourseId: course.courseId) {
                        CxBadge(bound.name, tone: .sage, icon: "mappin")
                    }
                }
            }

            if isSelected {
                Spacer(minLength: 4)
                Image(systemName: "chevron.right")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(CxPalette.accent)
            }
        }
        .padding(.vertical, 3)
        .contentShape(Rectangle())
    }
}

struct IOSCourseDetailView: View {
    @Environment(IOSAppState.self) private var appState
    let course: ChaoxingCourse
    @Binding var selectedSegment: IOSCourseDetailSegment

    @State private var activityList = IOSCourseActivityList()

    private var activityScope: IOSCourseActivityList.Scope {
        .init(session: appState.sessionToken, courseID: course.id)
    }

    init(course: ChaoxingCourse, selectedSegment: Binding<IOSCourseDetailSegment>) {
        self.course = course
        self._selectedSegment = selectedSegment
    }

    var body: some View {
        let scope = activityScope
        return VStack(spacing: 0) {
            Picker("功能视图", selection: $selectedSegment) {
                ForEach(IOSCourseDetailSegment.allCases) { seg in
                    Text(seg.rawValue).tag(seg)
                }
            }
            .pickerStyle(.segmented)
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
            .background(CxPalette.canvas)

            Divider()

            switch selectedSegment {
            case .activities:
                activitiesListView(for: scope)
            case .study:
                IOSCourseStudyView(course: course)
            }
        }
        .background(CxPalette.canvas)
        .navigationTitle(course.courseName)
        .navigationBarTitleDisplayMode(.inline)
        .task(id: scope) {
            await activityList.load(course, session: scope.session, in: appState)
        }
    }

    private func activitiesListView(for scope: IOSCourseActivityList.Scope) -> some View {
        let phase = activityList.phase(for: scope)
        return List {
            Section {
                HStack(spacing: 12) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(course.courseName)
                            .font(CxTypography.serifTitle(18))
                            .foregroundStyle(CxPalette.ink)
                        HStack(spacing: 8) {
                            if !course.teacherName.isEmpty {
                                Text(course.teacherName)
                                    .font(.caption)
                                    .foregroundStyle(CxPalette.inkSecondary)
                            }
                        }
                    }
                    Spacer()
                    if let bound = appState.boundLocation(forCourseId: course.courseId) {
                        CxBadge(bound.name, tone: .sage, icon: "mappin")
                    }
                }
                .padding(.vertical, 4)
                .cxCardRow()
            }

            Section {
                switch phase {
                case .loading(let previous):
                    ProgressView(previous == nil ? "正在加载签到活动…" : "正在刷新签到活动…")
                        .cxCardRow()
                case .failed(let message, let previous):
                    ContentUnavailableView {
                        Label(previous == nil ? "签到活动加载失败" : "签到活动刷新失败", systemImage: "exclamationmark.triangle")
                    } description: {
                        Text(message)
                    } actions: {
                        Button("重试") {
                            Task { await activityList.load(course, session: scope.session, in: appState) }
                        }
                        .buttonStyle(.bordered)
                    }
                    .cxCardRow()
                case .loaded(let activities):
                    if activities.isEmpty {
                        ContentUnavailableView("暂无签到活动", systemImage: "tray", description: Text("下拉可以重新查询这门课程的活动。"))
                            .foregroundStyle(CxPalette.inkSecondary)
                            .cxCardRow()
                    }
                }

                if let activities = phase.previousResults {
                    ForEach(activities) { activity in
                        let itemTone: CxTone = activity.isAlreadySignedByStatus
                            ? .sage
                            : (activity.isAvailable ? .accent : .neutral)

                        NavigationLink(value: activity) {
                            HStack(spacing: 12) {
                                CxIconTile(activity.signType.systemImage, tone: itemTone, size: 34)

                                VStack(alignment: .leading, spacing: 4) {
                                    HStack(spacing: 8) {
                                        Text(activity.nameOne)
                                            .font(CxTypography.serifHeadline(16))
                                            .foregroundStyle(CxPalette.ink)
                                            .fixedSize(horizontal: false, vertical: true)
                                        Spacer()
                                        statusBadge(for: activity)
                                    }

                                    HStack(spacing: 6) {
                                        Text(activity.signType.displayName)
                                            .font(.caption)
                                            .foregroundStyle(CxPalette.inkSecondary)
                                        if !activity.nameFour.isEmpty {
                                            Text("· \(activity.nameFour)")
                                                .font(CxTypography.mono(11))
                                                .foregroundStyle(CxPalette.inkTertiary)
                                                .fixedSize(horizontal: false, vertical: true)
                                        }
                                    }
                                }
                            }
                            .padding(.vertical, 3)
                        }
                        .disabled(!isCurrentResult(phase))
                        .cxCardRow()
                    }
                }
            } header: {
                CxSectionHeader("签到活动", trailing: phase.previousResults.map {
                    isCurrentResult(phase) ? "\($0.count) 项" : "上次查询 \($0.count) 项"
                })
            }
        }
        .listStyle(.insetGrouped)
        .cxEditorialList()
        .refreshable {
            await activityList.load(course, session: scope.session, in: appState)
        }
    }

    private func isCurrentResult(_ phase: IOSCourseActivityList.Phase) -> Bool {
        if case .loaded = phase { return true }
        return false
    }

    @ViewBuilder
    private func statusBadge(for activity: ChaoxingSignActivity) -> some View {
        if activity.isAlreadySignedByStatus {
            CxBadge("已签", tone: .sage, icon: "checkmark")
        } else if activity.isAvailable {
            CxBadge("进行中", tone: .accent, showDot: true)
        } else {
            CxBadge("已结束", tone: .neutral)
        }
    }
}
