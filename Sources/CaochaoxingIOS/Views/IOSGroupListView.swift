// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

import SwiftUI
import ChaoxingCore

struct IOSGroupListView: View {
    @Environment(IOSAppState.self) private var appState

    var body: some View {
        NavigationStack {
            List {
                Section {
                    if appState.groups.isEmpty {
                        ContentUnavailableView(
                            appState.isLoading ? "正在同步环信群聊..." : "暂无群聊会话",
                            systemImage: "bubble.left.and.bubble.right",
                            description: Text("下拉刷新可拉取学习通群聊中的签到通知")
                        )
                        .foregroundStyle(CxPalette.inkSecondary)
                        .cxCardRow()
                    } else {
                        ForEach(appState.groups) { group in
                            NavigationLink(value: group) {
                                HStack(spacing: 12) {
                                    CxIconTile("person.3.fill", tone: .accent, size: 34)

                                    VStack(alignment: .leading, spacing: 3) {
                                        Text(group.name)
                                            .font(CxTypography.serifHeadline(16))
                                            .foregroundStyle(CxPalette.ink)
                                            .fixedSize(horizontal: false, vertical: true)
                                        Text("群号 \(group.id)")
                                            .font(CxTypography.mono(11))
                                            .foregroundStyle(CxPalette.inkTertiary)
                                            .fixedSize(horizontal: false, vertical: true)
                                    }
                                }
                                .padding(.vertical, 3)
                            }
                            .cxCardRow()
                        }
                    }
                } header: {
                    CxSectionHeader("我的群聊", trailing: "\(appState.groups.count) 个")
                }
            }
            .listStyle(.insetGrouped)
            .cxEditorialList()
            .navigationTitle("群聊")
            .refreshable {
                await appState.refreshGroups()
            }
            .task {
                if appState.groups.isEmpty {
                    await appState.refreshGroups()
                }
            }
            .navigationDestination(for: ChaoxingIMGroup.self) { group in
                IOSGroupDetailView(group: group)
            }
        }
    }
}

struct IOSGroupDetailView: View {
    @Environment(IOSAppState.self) private var appState
    let group: ChaoxingIMGroup

    @State private var groupActivities: [ChaoxingGroupSignActivity] = []
    @State private var selectedActivity: ChaoxingSignActivity?
    @State private var isLoading = false
    @State private var loadID = UUID()
    @State private var openTask: Task<Void, Never>?

    var body: some View {
        List {
            Section {
                if groupActivities.isEmpty {
                    ContentUnavailableView(
                        isLoading ? "正在解析漫游签到消息..." : "暂无群聊卡片",
                        systemImage: "tray",
                        description: Text("近期消息中没有签到活动")
                    )
                    .foregroundStyle(CxPalette.inkSecondary)
                    .cxCardRow()
                } else {
                    ForEach(groupActivities) { item in
                        Button {
                            openActivity(item)
                        } label: {
                            HStack(spacing: 12) {
                                CxIconTile(
                                    item.signType.systemImage,
                                    tone: item.isUnsupportedLegacySign ? .neutral : .accent,
                                    size: 34
                                )

                                VStack(alignment: .leading, spacing: 4) {
                                    Text(item.title.isEmpty ? item.activeTypeName : item.title)
                                        .font(CxTypography.serifHeadline(16))
                                        .foregroundStyle(item.isUnsupportedLegacySign ? CxPalette.inkTertiary : CxPalette.ink)
                                        .fixedSize(horizontal: false, vertical: true)

                                    HStack(spacing: 6) {
                                        Text(item.courseName)
                                            .font(.caption)
                                            .foregroundStyle(CxPalette.inkSecondary)
                                            .fixedSize(horizontal: false, vertical: true)
                                        Text("· \(item.startTimeTitle)")
                                            .font(CxTypography.mono(11))
                                            .foregroundStyle(CxPalette.inkTertiary)
                                    }
                                }

                                Spacer()

                                if item.isUnsupportedLegacySign {
                                    CxBadge("旧版协议", tone: .neutral)
                                } else {
                                    CxBadge("进入", tone: .accent, icon: "arrow.up.right")
                                }
                            }
                            .padding(.vertical, 3)
                        }
                        .disabled(item.isUnsupportedLegacySign)
                        .cxCardRow()
                    }
                }
            } header: {
                CxSectionHeader("签到活动", trailing: "\(groupActivities.count) 项")
            }
        }
        .listStyle(.insetGrouped)
        .cxEditorialList()
        .navigationTitle(group.name)
        .navigationBarTitleDisplayMode(.inline)
        .refreshable {
            await loadMessages()
        }
        .task {
            await loadMessages()
        }
        .onDisappear { openTask?.cancel() }
        .navigationDestination(item: $selectedActivity) { activity in
            IOSSignWorkspaceView(activity: activity)
        }
    }

    private func loadMessages() async {
        guard let client = appState.activeClient else { return }
        let token = appState.sessionToken
        let request = UUID()
        loadID = request
        isLoading = true
        defer { if loadID == request { isLoading = false } }
        do {
            let config = try await appState.groupConfiguration(client: client)
            try appState.checkSession(token)
            guard loadID == request else { return }
            let fetched: [ChaoxingGroupSignActivity]
            do {
                fetched = try await ChaoxingGroupService.fetchGroupSignActivities(client: client, group: group, imConfig: config)
            } catch let error as ChaoxingError {
                guard case .sessionExpired = error else { throw error }
                let refreshed = try await appState.groupConfiguration(client: client, forceRefresh: true)
                try appState.checkSession(token)
                guard loadID == request else { return }
                fetched = try await ChaoxingGroupService.fetchGroupSignActivities(client: client, group: group, imConfig: refreshed)
            }
            try appState.checkSession(token)
            guard loadID == request else { return }
            groupActivities = fetched
        } catch is CancellationError {
            return
        } catch {
            guard appState.isCurrent(token), loadID == request else { return }
            if case ChaoxingError.sessionExpired = error { appState.imConfig = nil }
            appState.errorMessage = error.localizedDescription
        }
    }

    private func openActivity(_ item: ChaoxingGroupSignActivity) {
        guard let client = appState.activeClient else { return }
        let token = appState.sessionToken
        openTask?.cancel()
        openTask = appState.launchSessionTask {
            do {
                let detail = try await ChaoxingActivityService.fetchSignDetail(
                    client: client,
                    activeId: item.activeId,
                    fallbackClassId: item.classId,
                    fallbackCourseId: item.courseId
                )
                try appState.checkSession(token)
                selectedActivity = ChaoxingSignActivity(
                    id: item.activeId,
                    startTimeMs: item.messageTimestampMs,
                    endTimeMs: nil,
                    userStatus: 0,
                    otherId: detail.signType.rawValue,
                    isLook: true,
                    type: 2,
                    activeType: 2,
                    nameOne: item.title.isEmpty ? detail.title : item.title,
                    status: 1,
                    nameFour: item.startTimeTitle,
                    courseId: detail.signOutInfo.courseId,
                    classId: detail.signOutInfo.classId,
                    courseName: item.courseName,
                    extContent: "{}"
                )
            } catch is CancellationError {
                return
            } catch {
                guard appState.isCurrent(token) else { return }
                appState.errorMessage = error.localizedDescription
            }
        }
    }
}
