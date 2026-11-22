// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

#if os(macOS)
import SwiftUI
import AppKit
import UniformTypeIdentifiers
import ChaoxingCore

private struct MacSignSheetPayload: Identifiable {
    let id = UUID()
    let activity: ChaoxingSignActivity
    let initialQRRaw: String
}

struct MacSignWorkspaceView: View {
    @Environment(MacAppState.self) private var appState
    @State private var searchText = ""
    @State private var activeSignSheet: MacSignSheetPayload?
    @State private var showStandaloneQRSheet = false

    private var sortedCourses: [ChaoxingCourse] {
        let q = searchText.trimmingCharacters(in: .whitespacesAndNewlines)
        let filtered = q.isEmpty ? appState.courses : appState.courses.filter {
            $0.courseName.localizedCaseInsensitiveContains(q) ||
            $0.teacherName.localizedCaseInsensitiveContains(q)
        }
        return filtered.sorted { lhs, rhs in
            let l = appState.starredCourseIds.contains(lhs.courseId)
            let r = appState.starredCourseIds.contains(rhs.courseId)
            if l != r { return l }
            return false
        }
    }

    var body: some View {
        NavigationSplitView {
            List {
                Section("雷达扫描与快捷入口") {
                    radarHeaderRow
                    if let message = appState.radarScanMessage {
                        Text(message)
                            .font(.caption)
                            .foregroundStyle(appState.radarFailedCourses > 0 ? CxPalette.cinnabar : .secondary)
                    }
                    Button {
                        showStandaloneQRSheet = true
                    } label: {
                        Label("独立扫码签到（粘贴链接 / 识别二维码图片）", systemImage: "qrcode.viewfinder")
                            .font(.subheadline)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    .buttonStyle(.bordered)
                    .controlSize(.small)

                    ForEach(appState.pendingRadarActivities) { act in
                        radarActivityRow(act)
                    }
                }

                Section("我的课程 (\(sortedCourses.count))") {
                    ForEach(sortedCourses) { course in
                        courseRow(course)
                    }
                }
            }
            .searchable(text: $searchText, prompt: "搜索课程")
            .navigationSplitViewColumnWidth(min: 260, ideal: 310, max: 380)
        } detail: {
            courseDetailPane
        }
        .sheet(item: $activeSignSheet) { payload in
            MacSignActionSheet(activity: payload.activity, initialQRRaw: payload.initialQRRaw)
        }
        .sheet(isPresented: $showStandaloneQRSheet) {
            MacStandaloneQRSheet { activity, qrRaw in
                showStandaloneQRSheet = false
                activeSignSheet = MacSignSheetPayload(activity: activity, initialQRRaw: qrRaw)
            }
        }
    }

    private var radarHeaderRow: some View {
        HStack(spacing: 10) {
            VStack(alignment: .leading, spacing: 2) {
                Text("待签活动雷达")
                    .font(.headline)
                Text(appState.starredCourseIds.isEmpty ? "扫描前 12 门课程考勤" : "扫描已标星 \(appState.starredCourseIds.count) 门课程")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Spacer()
            Button {
                appState.scanPendingActivities()
            } label: {
                if appState.isScanningRadar {
                    ProgressView().controlSize(.small)
                } else {
                    Label("扫描", systemImage: "waveform.path.ecg")
                }
            }
            .buttonStyle(.borderedProminent)
            .disabled(appState.isScanningRadar || appState.courses.isEmpty)
        }
        .padding(.vertical, 4)
    }

    private func radarActivityRow(_ act: ChaoxingSignActivity) -> some View {
        Button {
            activeSignSheet = MacSignSheetPayload(activity: act, initialQRRaw: "")
        } label: {
            HStack {
                Image(systemName: act.signType.systemImage)
                    .foregroundStyle(CxPalette.amber)
                VStack(alignment: .leading, spacing: 2) {
                    Text(act.courseName.isEmpty ? act.nameOne : "\(act.courseName) · \(act.nameOne)")
                        .font(.subheadline.weight(.semibold))
                        .lineLimit(2)
                    Text(act.signType.displayName)
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
                Spacer()
                CxBadge("待签", tone: .amber, showDot: true)
            }
        }
        .buttonStyle(.plain)
    }

    private func courseRow(_ course: ChaoxingCourse) -> some View {
        HStack {
            Button {
                appState.loadActivities(for: course)
            } label: {
                VStack(alignment: .leading, spacing: 2) {
                    Text(course.courseName)
                        .font(.subheadline.weight(.medium))
                        .foregroundStyle(appState.selectedSignCourse?.id == course.id ? CxPalette.accent : .primary)
                        .lineLimit(2)
                    if !course.teacherName.isEmpty {
                        Text(course.teacherName)
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)

            Button {
                Task { await appState.toggleStarCourse(course.courseId) }
            } label: {
                Image(systemName: appState.starredCourseIds.contains(course.courseId) ? "star.fill" : "star")
                    .foregroundStyle(appState.starredCourseIds.contains(course.courseId) ? CxPalette.amber : .secondary)
            }
            .buttonStyle(.borderless)
        }
    }

    @ViewBuilder
    private var courseDetailPane: some View {
        if let course = appState.selectedSignCourse {
            VStack(alignment: .leading, spacing: 0) {
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(course.courseName)
                            .font(.title3.weight(.semibold))
                        Text(course.teacherName)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    Spacer()
                    Button {
                        appState.selectedTab = .study
                        appState.selectStudyCourse(course)
                    } label: {
                        Label("去本课看课", systemImage: "play.circle")
                    }
                    .buttonStyle(.bordered)

                    Button {
                        appState.loadActivities(for: course)
                    } label: {
                        Label("刷新活动", systemImage: "arrow.clockwise")
                    }
                    .buttonStyle(.bordered)
                }
                .padding(16)
                Divider()

                if appState.isLoadingActivities {
                    ProgressView("加载签到活动...")
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else if let err = appState.signErrorMessage, appState.courseActivities.isEmpty {
                    ContentUnavailableView("加载失败", systemImage: "exclamationmark.triangle", description: Text(err))
                } else if appState.courseActivities.isEmpty {
                    ContentUnavailableView("暂无签到活动", systemImage: "checkmark.seal", description: Text("该课程当前没有记录到的签到任务"))
                } else {
                    List(appState.courseActivities) { act in
                        MacActivityRow(activity: act) {
                            activeSignSheet = MacSignSheetPayload(activity: act, initialQRRaw: "")
                        }
                    }
                }
            }
        } else {
            ContentUnavailableView("选择课程或扫描待签", systemImage: "location.viewfinder", description: Text("点击左侧课程查看所有签到活动，或直接点击“扫描”快速定位进行中的考勤"))
        }
    }
}

struct MacGroupSignWorkspaceView: View {
    @Environment(MacAppState.self) private var appState
    @State private var activeSignSheet: ChaoxingSignActivity?

    var body: some View {
        NavigationSplitView {
            groupListColumn
        } detail: {
            groupDetailColumn
        }
        .sheet(item: $activeSignSheet) { act in
            MacSignActionSheet(activity: act)
        }
    }

    private var groupListColumn: some View {
        VStack(spacing: 0) {
            HStack {
                Text("我的群聊 (\(appState.groups.count))")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.secondary)
                Spacer()
                Button {
                    Task { await appState.loadGroups() }
                } label: {
                    if appState.isLoadingGroups {
                        ProgressView().controlSize(.mini)
                    } else {
                        Image(systemName: "arrow.clockwise")
                    }
                }
                .buttonStyle(.borderless)
                .disabled(appState.isLoadingGroups)
                .help("刷新群聊")
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            Divider()

            List(appState.groups) { group in
                Button {
                    Task { await appState.loadGroupActivities(for: group) }
                } label: {
                    VStack(alignment: .leading, spacing: 3) {
                        Text(group.name)
                            .font(.subheadline.weight(.medium))
                            .foregroundStyle(appState.selectedGroup?.id == group.id ? CxPalette.accent : .primary)
                        Text("群聊 ID: \(group.id)")
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                    }
                    .padding(.vertical, 2)
                }
                .buttonStyle(.plain)
            }
        }
        .navigationSplitViewColumnWidth(min: 230, ideal: 280)
        .task {
            if appState.groups.isEmpty {
                await appState.loadGroups()
            }
        }
    }

    @ViewBuilder
    private var groupDetailColumn: some View {
        if let group = appState.selectedGroup {
            VStack(alignment: .leading, spacing: 0) {
                HStack {
                    Text(group.name)
                        .font(.title3.weight(.semibold))
                    Spacer()
                    Button("刷新活动") {
                        Task { await appState.loadGroupActivities(for: group) }
                    }
                }
                .padding(16)
                Divider()
                if appState.isLoadingActivities {
                    ProgressView("加载群聊活动...")
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else if appState.groupActivities.isEmpty {
                    ContentUnavailableView("暂无群聊签到", systemImage: "bubble.left")
                } else {
                    List(appState.groupActivities) { act in
                        MacActivityRow(activity: act) {
                            activeSignSheet = act
                        }
                    }
                }
            }
        } else {
            ContentUnavailableView("选择一个群聊", systemImage: "bubble.left.and.bubble.right")
        }
    }
}

struct MacSettingsWorkspaceView: View {
    @Environment(MacAppState.self) private var appState
    @State private var presetName = ""
    @State private var presetAddress = ""
    @State private var presetLat = ""
    @State private var presetLng = ""
    @State private var editingRemarkPhone: String?
    @State private var remarkDraft = ""

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                presetDefaultsBox
                accountsBox
                locationPresetsBox
                historyBox
            }
            .padding(20)
        }
    }

    private var presetDefaultsBox: some View {
        GroupBox("全部一键默认预设（已配好）") {
            VStack(alignment: .leading, spacing: 10) {
                HStack {
                    Picker("默认挂机并发路数", selection: Bindable(appState).maxConcurrentWatches) {
                        Text("1 路").tag(1)
                        Text("2 路").tag(2)
                        Text("3 路 (推荐)").tag(3)
                        Text("5 路").tag(5)
                    }
                    .frame(maxWidth: 260)
                    Spacer()
                }
                Toggle("单视频通过后自动连播下一节", isOn: Bindable(appState).autoAdvanceNextChapter)
                Toggle("同时看时自动匹配并续看其他课程（关闭则仅看当前选中课程）", isOn: Bindable(appState).watchAllCoursesAutomatically)
                Toggle("同时看时顺便自动完成 PDF/文档任务点", isOn: Bindable(appState).autoCompleteDocuments)
                Toggle("位置签到启用随机坐标防风控微调", isOn: Binding(
                    get: { appState.enableLocationJitter },
                    set: { appState.setEnableLocationJitter($0) }
                ))
                Toggle("打开应用登录后自动开始同时看（同步播放）", isOn: Bindable(appState).autoStartOnLaunch)
            }
            .padding(8)
        }
    }

    private var accountsBox: some View {
        GroupBox("多账号灵活切换与管理（已保存 \(appState.accounts.count) 个 · 支持代签）") {
            VStack(alignment: .leading, spacing: 10) {
                ForEach(Array(appState.accounts.enumerated()), id: \.element.id) { index, acc in
                    let isActive = acc.phoneNumber == appState.activeAccount?.phoneNumber
                    let isRefreshing = appState.refreshingAccountPhones.contains(acc.phoneNumber)
                    let remark = appState.accountRemark(for: acc)

                    VStack(alignment: .leading, spacing: 6) {
                        HStack(spacing: 10) {
                            Image(systemName: isActive ? "checkmark.circle.fill" : "person.circle")
                                .font(.title3)
                                .foregroundStyle(isActive ? CxPalette.sage : .secondary)

                            VStack(alignment: .leading, spacing: 2) {
                                HStack(spacing: 6) {
                                    Text(acc.name.isEmpty ? acc.phoneNumber : acc.name)
                                        .font(.subheadline.weight(.semibold))
                                    if !remark.isEmpty {
                                        CxBadge(remark, tone: .accent)
                                    }
                                    if isActive {
                                        CxBadge("当前使用", tone: .sage, showDot: true)
                                    }
                                    if index < 9 {
                                        Text("⌥⌘\(index + 1)")
                                            .font(CxTypography.mono(10))
                                            .foregroundStyle(.secondary)
                                    }
                                }
                                HStack(spacing: 8) {
                                    Text(acc.phoneNumber)
                                    if let school = acc.schools.first?.name, !school.isEmpty {
                                        Text("· \(school)")
                                    }
                                }
                                .font(.caption)
                                .foregroundStyle(.secondary)
                            }

                            Spacer()

                            if !isActive {
                                Button("切换使用") {
                                    Task { await appState.switchAccount(acc) }
                                }
                                .buttonStyle(.borderedProminent)
                                .tint(CxPalette.accent)
                                .controlSize(.small)
                                .disabled(appState.isSwitchingAccount)
                            }

                            Button {
                                if editingRemarkPhone == acc.phoneNumber {
                                    editingRemarkPhone = nil
                                } else {
                                    editingRemarkPhone = acc.phoneNumber
                                    remarkDraft = remark
                                }
                            } label: {
                                Image(systemName: "tag")
                            }
                            .buttonStyle(.bordered)
                            .controlSize(.small)
                            .help("设置/修改账号备注标签")

                            Button {
                                Task { await appState.refreshAccountSession(acc) }
                            } label: {
                                if isRefreshing {
                                    ProgressView().controlSize(.mini)
                                } else {
                                    Image(systemName: "arrow.clockwise")
                                }
                            }
                            .buttonStyle(.bordered)
                            .controlSize(.small)
                            .disabled(isRefreshing)
                            .help("重新登录刷新该账号 Cookie 会话")

                            Button(role: .destructive) {
                                Task { await appState.removeAccount(acc) }
                            } label: {
                                Image(systemName: "trash")
                            }
                            .buttonStyle(.bordered)
                            .controlSize(.small)
                            .help(isActive ? "退出并移除当前账号" : "移除该备用账号")
                        }

                        if editingRemarkPhone == acc.phoneNumber {
                            HStack(spacing: 8) {
                                TextField("输入备注标签（如 本人 / 小号 / 室友，留空则清除）", text: $remarkDraft)
                                    .textFieldStyle(.roundedBorder)
                                    .onSubmit {
                                        let draft = remarkDraft
                                        editingRemarkPhone = nil
                                        Task { await appState.updateAccountRemark(phoneNumber: acc.phoneNumber, remark: draft) }
                                    }
                                Button("保存") {
                                    let draft = remarkDraft
                                    editingRemarkPhone = nil
                                    Task { await appState.updateAccountRemark(phoneNumber: acc.phoneNumber, remark: draft) }
                                }
                                .buttonStyle(.borderedProminent)
                                .controlSize(.small)
                                Button("取消") {
                                    editingRemarkPhone = nil
                                }
                                .buttonStyle(.bordered)
                                .controlSize(.small)
                            }
                            .padding(.leading, 30)
                        }
                    }
                    .padding(8)
                    .background(
                        isActive ? CxPalette.sage.opacity(0.08) : CxPalette.surfaceSecondary.opacity(0.6),
                        in: RoundedRectangle(cornerRadius: 8)
                    )
                }

                HStack(spacing: 10) {
                    Button {
                        appState.loginErrorMessage = nil
                        appState.showAddAccountSheet = true
                    } label: {
                        Label("添加新账号", systemImage: "person.badge.plus")
                    }
                    .buttonStyle(.borderedProminent)

                    if appState.accounts.count > 1 {
                        Button {
                            Task { await appState.switchToNextAccount() }
                        } label: {
                            Label("切换下一个账号 (⌥⌘N)", systemImage: "arrow.triangle.2.circlepath")
                        }
                        .buttonStyle(.bordered)
                        .disabled(appState.isSwitchingAccount)
                    }

                    Spacer()

                    Text("顶部工具栏与菜单栏「账号」均可随时一键切换")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
            }
            .padding(8)
        }
    }

    private var locationPresetsBox: some View {
        GroupBox("常用签到位置预设") {
            VStack(alignment: .leading, spacing: 10) {
                if appState.locationPresets.isEmpty {
                    Text("尚未添加位置预设").font(.caption).foregroundStyle(.secondary)
                } else {
                    ForEach(appState.locationPresets) { preset in
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(preset.name).font(.subheadline.weight(.semibold))
                                Text("\(preset.address) (\(String(format: "%.6f, %.6f", preset.latitude, preset.longitude)))")
                                    .font(.caption2)
                                    .foregroundStyle(.secondary)
                            }
                            Spacer()
                            Button(role: .destructive) {
                                Task { await appState.deleteLocationPreset(preset.id) }
                            } label: {
                                Image(systemName: "trash")
                            }
                            .buttonStyle(.borderless)
                        }
                    }
                }

                Divider()
                HStack(spacing: 8) {
                    TextField("名称(如 教学楼)", text: $presetName)
                    TextField("详细地址", text: $presetAddress)
                    TextField("纬度 lat", text: $presetLat).frame(width: 110)
                    TextField("经度 lng", text: $presetLng).frame(width: 110)
                    Button("添加") {
                        guard let lat = Double(presetLat), let lng = Double(presetLng), !presetName.isEmpty else { return }
                        let addr = presetAddress.isEmpty ? presetName : presetAddress
                        Task {
                            await appState.addLocationPreset(name: presetName, address: addr, latitude: lat, longitude: lng)
                            presetName = ""; presetAddress = ""; presetLat = ""; presetLng = ""
                        }
                    }
                    .buttonStyle(.borderedProminent)
                }
            }
            .padding(8)
        }
    }

    private var historyBox: some View {
        GroupBox("最近签到记录 (\(appState.signHistory.count))") {
            VStack(alignment: .leading, spacing: 8) {
                if appState.signHistory.isEmpty {
                    Text("暂无签到记录").font(.caption).foregroundStyle(.secondary)
                } else {
                    ForEach(appState.signHistory.prefix(15)) { item in
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text("\(item.courseName.isEmpty ? "活动" : item.courseName) · \(item.activityName)")
                                    .font(.subheadline.weight(.medium))
                                Text(item.summary)
                                    .font(.caption2)
                                    .foregroundStyle(.secondary)
                            }
                            Spacer()
                            Text(item.displayCounts)
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                }
            }
            .padding(8)
        }
    }
}

private struct MacActivityRow: View {
    let activity: ChaoxingSignActivity
    let onTap: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: activity.signType.systemImage)
                .font(.title3)
                .foregroundStyle(activity.isAvailable ? CxPalette.accent : .secondary)
                .frame(width: 28)

            VStack(alignment: .leading, spacing: 3) {
                Text(activity.nameOne)
                    .font(.subheadline.weight(.semibold))
                Text("\(activity.signType.displayName) · 活动 ID: \(activity.id)")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Spacer()
            if activity.isAvailable {
                Button("去签到") { onTap() }
                    .buttonStyle(.borderedProminent)
            } else {
                CxBadge("已结束", tone: .neutral)
                Button("重试") { onTap() }
                    .buttonStyle(.bordered)
            }
        }
        .padding(.vertical, 4)
    }
}

private enum MacPhotoSignMode: String, CaseIterable, Identifiable {
    case fastClick = "无图快签"
    case profileFace = "使用底库头像"
    case localImage = "上传本地图片"
    var id: String { rawValue }
}

private struct MacStandaloneQRSheet: View {
    @Environment(MacAppState.self) private var appState
    @Environment(\.dismiss) private var dismiss

    let onResolved: (ChaoxingSignActivity, String) -> Void

    @State private var rawInput = ""
    @State private var isResolving = false
    @State private var errorText: String?
    @State private var resolutionTask: Task<Void, Never>?

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack {
                Text("独立扫码签到")
                    .font(.title3.weight(.semibold))
                Spacer()
                Button("关闭") { close() }
            }
            Text("粘贴超星签到二维码链接（SIGNIN:... 或 https://...）或选择本地二维码截图，自动解析活动并进入签到工作台。")
                .font(.caption)
                .foregroundStyle(.secondary)

            TextField("粘贴二维码内容或链接", text: $rawInput)
                .textFieldStyle(.roundedBorder)
                .onSubmit { resolve() }

            HStack(spacing: 10) {
                Button {
                    pickQRImage()
                } label: {
                    Label("从图片/截图识别二维码", systemImage: "photo.on.rectangle")
                }
                .buttonStyle(.bordered)

                Spacer()

                Button {
                    resolve()
                } label: {
                    if isResolving {
                        ProgressView().controlSize(.small)
                    } else {
                        Text("解析活动")
                    }
                }
                .buttonStyle(.borderedProminent)
                .disabled(isResolving || rawInput.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }

            if let errorText {
                Text(errorText)
                    .font(.caption)
                    .foregroundStyle(CxPalette.cinnabar)
            }
        }
        .padding(20)
        .frame(width: 480)
        .onChange(of: appState.signSessionID) { close() }
        .onDisappear { resolutionTask?.cancel() }
    }

    private func pickQRImage() {
        let panel = NSOpenPanel()
        panel.allowedContentTypes = [.image]
        panel.allowsMultipleSelection = false
        if panel.runModal() == .OK, let url = panel.url {
            if let decoded = MacAppState.detectQRCodeFromImageURL(url) {
                rawInput = decoded
                errorText = nil
            } else {
                errorText = "未在所选图片中识别到有效二维码"
            }
        }
    }

    private func resolve() {
        guard !isResolving else { return }
        isResolving = true
        errorText = nil
        let sessionID = appState.signSessionID
        resolutionTask = Task {
            do {
                let (act, raw) = try await appState.resolveStandaloneQRCode(rawInput)
                guard !Task.isCancelled, sessionID == appState.signSessionID else { return }
                onResolved(act, raw)
            } catch {
                guard !Task.isCancelled, sessionID == appState.signSessionID else { return }
                errorText = error.localizedDescription
            }
            isResolving = false
        }
    }

    private func close() {
        resolutionTask?.cancel()
        dismiss()
    }
}

struct MacSignActionSheet: View {
    @Environment(MacAppState.self) private var appState
    @Environment(\.dismiss) private var dismiss

    let activity: ChaoxingSignActivity
    var initialQRRaw: String = ""

    @State private var currentActivity: ChaoxingSignActivity
    @State private var isSignOutDrillDown = false
    @State private var selectedLocationId: UUID?
    @State private var rememberLocationForCourse = true
    @State private var codeInput = ""
    @State private var qrInput = ""
    @State private var photoMode: MacPhotoSignMode = .fastClick
    @State private var selectedPhotoURL: URL?
    @State private var selectedPhotoData: Data?
    @State private var prepared: MacPreparedSignWorkspace?
    @State private var isLoadingInfo = true
    @State private var isOpeningSignOut = false
    @State private var signOutError: String?
    @State private var selectedPhones: Set<String> = []
    @State private var owner = UUID()
    @State private var linkedActivityTask: Task<Void, Never>?
    @State private var results: [MacSignResult] = []

    private var currentPreparation: MacPreparedSignWorkspace? {
        guard let prepared, prepared.activityID == currentActivity.id,
              prepared.sessionID == appState.signSessionID else { return nil }
        return prepared
    }
    private var detailMeta: ChaoxingSignDetailMetadata? { currentPreparation?.detail }
    private var isSubmitting: Bool { appState.isSubmitting(owner: owner) }
    private var captchaSheet: Binding<MacCaptchaChallenge?> {
        let id = isSubmitting ? appState.activeCaptchaChallenge?.id : nil
        return Binding(
            get: { isSubmitting ? appState.activeCaptchaChallenge : nil },
            set: { if $0 == nil, let id { appState.cancelCaptchaChallenge(id: id) } }
        )
    }

    init(activity: ChaoxingSignActivity, initialQRRaw: String = "") {
        self.activity = activity
        self.initialQRRaw = initialQRRaw
        _currentActivity = State(initialValue: activity)
        _qrInput = State(initialValue: initialQRRaw)
    }

    private var effectiveSignType: ChaoxingSignType {
        currentPreparation?.signType(for: currentActivity) ?? currentActivity.signType
    }

    private var needsLocationPicker: Bool {
        effectiveSignType == .location || (detailMeta?.isPositionRequired ?? false)
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text((isSignOutDrillDown ? "[签退] " : "") + currentActivity.nameOne)
                            .font(.title3.weight(.semibold))
                        Text("\(currentActivity.courseName.isEmpty ? "活动 #\(currentActivity.id)" : currentActivity.courseName) · \(effectiveSignType.displayName)")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    Spacer()
                    if isLoadingInfo {
                        ProgressView().controlSize(.small)
                    }
                    if isSignOutDrillDown {
                        Button("返回签到") {
                            currentActivity = activity
                            isSignOutDrillDown = false
                            results = []
                        }
                        .buttonStyle(.bordered)
                        .disabled(isSubmitting)
                    }
                    Button("关闭") { close() }
                }

                // Live status & server requirement badges
                HStack(spacing: 6) {
                    if let status = currentPreparation?.status {
                        switch status {
                        case .readyToSign:
                            CxBadge("可签到", tone: .sage, showDot: true)
                        case .alreadySigned:
                            CxBadge("当前号已签", tone: .accent, showDot: true)
                        case .expired:
                            CxBadge("已截止", tone: .cinnabar, showDot: true)
                        }
                    } else if !isLoadingInfo {
                        Text("未能确认活动状态，请重新打开活动后再试")
                            .font(.caption)
                            .foregroundStyle(CxPalette.cinnabar)
                    }
                    if let detail = detailMeta {
                        if detail.isPhotoRequired { CxBadge("需拍照", tone: .amber) }
                        if detail.isPositionRequired { CxBadge("需位置", tone: .amber) }
                        if detail.isCaptchaRequired { CxBadge("需滑块", tone: .cinnabar) }
                        if detail.isFaceRequired { CxBadge("自动人脸核验", tone: .accent) }
                        if let range = detail.targetLocation?.rangeMeters, range > 0 {
                            CxBadge("围栏 \(range)m", tone: .neutral, monospaced: true)
                        }
                    }
                }

                // Linked sign-out activity drill-down
                if !isSignOutDrillDown, let signOutId = detailMeta?.signOutInfo.signOutId, signOutId > 0 {
                    HStack {
                        Image(systemName: "arrow.uturn.right.circle.fill")
                            .foregroundStyle(CxPalette.accent)
                        Text("检测到关联签退活动 (#\(signOutId))")
                            .font(.caption.weight(.medium))
                        Spacer()
                        Button {
                            openLinkedSignOut(signOutId: signOutId)
                        } label: {
                            if isOpeningSignOut {
                                ProgressView().controlSize(.mini)
                            } else {
                                Text("切换至签退")
                            }
                        }
                        .buttonStyle(.bordered)
                        .controlSize(.small)
                        .disabled(isOpeningSignOut || isSubmitting)
                    }
                    .padding(10)
                    .background(CxPalette.accent.opacity(0.08), in: RoundedRectangle(cornerRadius: 8))
                }

                if let signOutError {
                    Text(signOutError)
                        .font(.caption)
                        .foregroundStyle(CxPalette.cinnabar)
                }

                Divider()

                signInputSection

                if needsLocationPicker && effectiveSignType != .location {
                    Divider()
                    locationPickerBlock(title: "该活动同时要求位置校验：")
                }

                if appState.accounts.count > 1 {
                    Divider()
                    Text("选择执行账号（支持多账号错峰代签）：")
                        .font(.caption.weight(.semibold))
                    ForEach(appState.accounts) { acc in
                        Toggle("\(appState.accountDisplayTitle(for: acc)) (\(acc.phoneNumber))", isOn: Binding(
                            get: { selectedPhones.contains(acc.phoneNumber) },
                            set: { checked in
                                if checked { selectedPhones.insert(acc.phoneNumber) }
                                else { selectedPhones.remove(acc.phoneNumber) }
                            }
                        ))
                        .controlSize(.small)
                        .disabled(isSubmitting)
                    }
                }

                if !results.isEmpty {
                    Divider()
                    VStack(alignment: .leading, spacing: 4) {
                        Text(ChaoxingSignCounts(dispositions: results.map(\.disposition)).summary)
                            .font(.caption.weight(.semibold))
                        ForEach(results) { res in
                            HStack {
                                Text(res.accountName)
                                Spacer()
                                switch res.disposition {
                                case .accepted:
                                    Label("本次请求获接受", systemImage: "checkmark.circle.fill")
                                        .foregroundStyle(CxPalette.sage)
                                case .alreadySigned:
                                    Label("此前已签", systemImage: "clock")
                                        .foregroundStyle(.secondary)
                                case .failed:
                                    Label("失败或未确认 · \(res.message)", systemImage: "xmark.circle.fill")
                                        .foregroundStyle(CxPalette.cinnabar)
                                }
                            }
                            .font(.caption)
                        }
                    }
                }

                HStack {
                    if detailMeta?.isFaceRequired == true {
                        Text("提交时将自动附带各账号人脸底库核验凭证")
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                    }
                    Spacer()
                    Button {
                        submit()
                    } label: {
                        if isSubmitting {
                            ProgressView().controlSize(.small)
                        } else {
                            Text(isSignOutDrillDown ? "立即签退" : "立即签到")
                        }
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(isLoadingInfo || isOpeningSignOut || !appState.canExecuteSign(
                        activity: currentActivity, prepared: currentPreparation, targetPhones: selectedPhones
                    ))
                }
                .padding(.top, 6)
            }
            .padding(20)
        }
        .frame(width: 540, height: 480)
        .onAppear {
            if let bound = appState.boundLocation(for: currentActivity.courseId),
               let matching = appState.locationPresets.first(where: { $0.id == bound.id || $0.name == bound.name }) {
                selectedLocationId = matching.id
            } else if let firstLoc = appState.locationPresets.first {
                selectedLocationId = firstLoc.id
            }
            if let phone = appState.activeAccount?.phoneNumber {
                selectedPhones = [phone]
            }
        }
        .task(id: currentActivity.id) {
            isLoadingInfo = true
            prepared = nil
            let result = await appState.prepareSignWorkspace(for: currentActivity)
            guard !Task.isCancelled, result.activityID == currentActivity.id,
                  result.sessionID == appState.signSessionID else { return }
            prepared = result
            if result.detail?.isPhotoRequired == true && photoMode == .fastClick {
                photoMode = .profileFace
            }
            isLoadingInfo = false
        }
        .onChange(of: appState.signSessionID) { close() }
        .onDisappear { cancelSheetTasks() }
        .sheet(item: captchaSheet) { challenge in
            MacCaptchaSheet(challenge: challenge)
                .onDisappear { appState.cancelCaptchaChallenge(id: challenge.id) }
        }
    }

    @ViewBuilder
    private var signInputSection: some View {
        switch effectiveSignType {
        case .location:
            locationPickerBlock(title: "选择签到位置：")
        case .password, .gesture:
            TextField(effectiveSignType == .gesture ? "输入手势数字（如 1235789）" : "输入签到码", text: $codeInput)
                .textFieldStyle(.roundedBorder)
        case .qrCode:
            VStack(alignment: .leading, spacing: 8) {
                TextField("粘贴二维码链接或 enc 值", text: $qrInput)
                    .textFieldStyle(.roundedBorder)
                Button("从本地二维码图片/截图识别") {
                    pickQRImage()
                }
                .buttonStyle(.bordered)
            }
        case .unknown:
            Text("未能识别此活动类型")
                .font(.caption)
                .foregroundStyle(.secondary)
        case .photoOrClick:
            VStack(alignment: .leading, spacing: 10) {
                Picker("提交方式", selection: $photoMode) {
                    ForEach(MacPhotoSignMode.allCases) { mode in
                        Text(mode.rawValue).tag(mode)
                    }
                }
                .pickerStyle(.segmented)

                switch photoMode {
                case .fastClick:
                    Text("不上传照片直接提交，适用于普通点击签到。")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                case .profileFace:
                    Text("自动拉取各账号超星人脸底库照片作为签到图片提交。")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                case .localImage:
                    HStack(spacing: 8) {
                        Button("选择本地签到图片") {
                            pickSignPhoto()
                        }
                        .buttonStyle(.bordered)
                        if let photoURL = selectedPhotoURL {
                            Text(photoURL.lastPathComponent)
                                .font(.caption)
                                .foregroundStyle(CxPalette.sage)
                                .lineLimit(1)
                            Button("清除") {
                                selectedPhotoURL = nil
                                selectedPhotoData = nil
                            }
                            .buttonStyle(.borderless)
                            .font(.caption)
                        } else {
                            Text("未选择图片")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                }
            }
        }
    }

    @ViewBuilder
    private func locationPickerBlock(title: String) -> some View {
        if appState.locationPresets.isEmpty {
            Text("尚未配置位置预设，请先到「账号与设置」中添加经纬度预设。")
                .font(.caption)
                .foregroundStyle(CxPalette.cinnabar)
        } else {
            VStack(alignment: .leading, spacing: 8) {
                Picker(title, selection: $selectedLocationId) {
                    ForEach(appState.locationPresets, id: \.id) { preset in
                        Text("\(preset.name) (\(preset.address))").tag(Optional(preset.id))
                    }
                }
                Toggle("记住为本课默认签到位置", isOn: $rememberLocationForCourse)
                    .controlSize(.small)
            }
        }
    }

    private func openLinkedSignOut(signOutId: Int64) {
        isOpeningSignOut = true
        signOutError = nil
        let sessionID = appState.signSessionID
        linkedActivityTask = Task {
            do {
                let target = try await appState.buildLinkedSignOutActivity(signOutId: signOutId, source: currentActivity)
                guard !Task.isCancelled, sessionID == appState.signSessionID else { return }
                currentActivity = target
                isSignOutDrillDown = true
                results = []
            } catch {
                guard !Task.isCancelled, sessionID == appState.signSessionID else { return }
                signOutError = "打开签退活动失败：\(error.localizedDescription)"
            }
            isOpeningSignOut = false
        }
    }

    private func pickQRImage() {
        let panel = NSOpenPanel()
        panel.allowedContentTypes = [.image]
        panel.allowsMultipleSelection = false
        if panel.runModal() == .OK, let url = panel.url,
           let decoded = MacAppState.detectQRCodeFromImageURL(url) {
            qrInput = decoded
        }
    }

    private func pickSignPhoto() {
        let panel = NSOpenPanel()
        panel.allowedContentTypes = [.image]
        panel.allowsMultipleSelection = false
        if panel.runModal() == .OK, let url = panel.url,
           let data = try? Data(contentsOf: url) {
            selectedPhotoURL = url
            selectedPhotoData = data
        }
    }

    private func submit() {
        guard !isLoadingInfo, !isOpeningSignOut else { return }
        let loc = appState.locationPresets.first { $0.id == selectedLocationId }
        appState.executeSign(
            owner: owner, activity: currentActivity, prepared: currentPreparation,
            location: loc, code: codeInput, qrCodeRaw: qrInput, useProfileFacePhoto: photoMode == .profileFace,
            photoImageData: photoMode == .localImage ? selectedPhotoData : nil,
            rememberLocationForCourse: rememberLocationForCourse, targetPhones: selectedPhones
        ) { results = $0 }
    }

    private func cancelSheetTasks() {
        linkedActivityTask?.cancel()
        appState.cancelSignBatch(owner: owner)
    }

    private func close() {
        cancelSheetTasks()
        dismiss()
    }
}

private struct MacCaptchaSheet: View {
    @Environment(MacAppState.self) private var appState
    let challenge: MacCaptchaChallenge

    @State private var sliderX: Double = 0
    @State private var isVerifying = false
    @State private var feedback: String?
    @State private var verificationTask: Task<Void, Never>?

    private var shadeImage: NSImage? { NSImage(data: challenge.shadeImageData) }
    private var cutoutImage: NSImage? { NSImage(data: challenge.cutoutImageData) }

    private var bgWidth: Double {
        let w = Double(shadeImage?.size.width ?? 320)
        return max(100, w)
    }

    private var maxOffset: Double {
        let cw = Double(cutoutImage?.size.width ?? 56)
        return max(10, bgWidth - cw)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack {
                Text("手动完成滑块验证 · \(challenge.accountName)")
                    .font(.headline)
                Spacer()
                Button("取消") {
                    appState.cancelCaptchaChallenge(id: challenge.id)
                }
            }

            if let bg = shadeImage {
                ZStack(alignment: .topLeading) {
                    Image(nsImage: bg)
                        .resizable()
                        .frame(width: bgWidth, height: bg.size.height)
                    if let cut = cutoutImage {
                        Image(nsImage: cut)
                            .resizable()
                            .frame(width: cut.size.width, height: cut.size.height)
                            .offset(x: sliderX)
                    }
                }
                .clipShape(RoundedRectangle(cornerRadius: 8))
                .overlay(RoundedRectangle(cornerRadius: 8).stroke(CxPalette.border, lineWidth: 1))
            }

            Slider(value: $sliderX, in: 0...maxOffset)

            if let feedback {
                Text(feedback)
                    .font(.caption)
                    .foregroundStyle(CxPalette.cinnabar)
            }

            HStack {
                Text("滑块位置: \(Int(round(sliderX))) px")
                    .font(CxTypography.mono(11))
                    .foregroundStyle(.secondary)
                Spacer()
                Button {
                    verify()
                } label: {
                    if isVerifying {
                        ProgressView().controlSize(.small)
                    } else {
                        Text("提交验证")
                    }
                }
                .buttonStyle(.borderedProminent)
                .disabled(isVerifying)
            }
        }
        .padding(20)
        .frame(width: max(380, bgWidth + 40))
        .onDisappear { verificationTask?.cancel() }
    }

    private func verify() {
        isVerifying = true
        feedback = nil
        let xPos = Int(round(sliderX))
        verificationTask = Task {
            guard appState.activeCaptchaChallenge?.id == challenge.id else { return }
            do {
                if let validate = try await ChaoxingCaptchaService.verifyCaptcha(
                    client: challenge.client,
                    captcha: challenge.captcha,
                    xPosition: xPos,
                    refererURL: challenge.refererURL
                ), !validate.isEmpty {
                    guard !Task.isCancelled else { return }
                    appState.completeCaptchaChallenge(id: challenge.id, validate: validate)
                } else {
                    guard !Task.isCancelled else { return }
                    feedback = "滑块位置未对齐，请微调后再试"
                }
            } catch {
                guard !Task.isCancelled else { return }
                feedback = error.localizedDescription
            }
            isVerifying = false
        }
    }
}
#endif

