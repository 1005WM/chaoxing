// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

import SwiftUI
import ChaoxingCore

struct IOSContentView: View {
    @Environment(IOSAppState.self) private var appState
    @State private var showRecoveryConfirmation = false

    var body: some View {
        @Bindable var state = appState

        ZStack {
            CxPalette.canvas.ignoresSafeArea()

            if appState.isRestoring {
                ProgressView("正在恢复本地数据…")
            } else if appState.persistenceBlocked {
                ContentUnavailableView {
                    Label("无法恢复本地数据", systemImage: "externaldrive.badge.exclamationmark")
                } description: {
                    Text("原文件已保留。你可以先备份原文件并重置本地状态，然后重新登录或导入有效备份。")
                } actions: {
                    Button("备份原文件并重置") { showRecoveryConfirmation = true }
                        .buttonStyle(CxPrimaryButtonStyle())
                }
            } else if appState.activeAccount == nil {
                IOSLoginView()
            } else {
                TabView(selection: $state.selectedTab) {
                    IOSCourseListView()
                        .tabItem {
                            Label("课程", systemImage: "books.vertical")
                        }
                        .tag(0)

                    IOSGroupListView()
                        .tabItem {
                            Label("群聊", systemImage: "bubble.left.and.bubble.right")
                        }
                        .tag(1)

                    IOSStandaloneQRCodeSignView()
                        .tabItem {
                            Label("扫码", systemImage: "qrcode.viewfinder")
                        }
                        .tag(2)

                    IOSOtherUsersView()
                        .tabItem {
                            Label("账号", systemImage: "person.2")
                        }
                        .tag(3)

                    IOSSettingsView()
                        .tabItem {
                            Label("设置", systemImage: "gearshape")
                        }
                        .tag(4)
                }
                .id(appState.activePhoneNumber)
            }
        }
        .tint(CxPalette.accent)
        .sheet(item: $state.captchaChallenge, onDismiss: {
            appState.captchaSheetDidDismiss()
        }) { challenge in
            IOSCaptchaSheetView(challenge: challenge)
                .id(challenge.id)
        }
        .sheet(item: $state.deepLinkActivity) { activity in
            NavigationStack {
                IOSSignWorkspaceView(activity: activity)
                    .toolbar {
                        ToolbarItem(placement: .cancellationAction) {
                            Button("关闭") { appState.deepLinkActivity = nil }
                        }
                    }
            }
            .tint(CxPalette.accent)
        }
        .confirmationDialog("备份原文件并重置？", isPresented: $showRecoveryConfirmation, titleVisibility: .visible) {
            Button("备份并重置", role: .destructive) {
                Task { await appState.recoverLocalState() }
            }
        } message: {
            Text("原文件会在本机保留恢复副本。当前状态将重置为初始值；如果无法保留副本，则不会重置。")
        }
        .alert("提示", isPresented: Binding(
            get: { appState.errorMessage != nil },
            set: { if !$0 { appState.errorMessage = nil } }
        )) {
            Button("确定", role: .cancel) {
                appState.errorMessage = nil
            }
        } message: {
            Text(appState.errorMessage ?? "")
        }
        .overlay(alignment: .top) {
            if let status = appState.statusMessage {
                HStack(spacing: 8) {
                    Circle()
                        .fill(CxPalette.sage)
                        .frame(width: 7, height: 7)
                    Text(status)
                        .font(.subheadline.weight(.medium))
                        .foregroundStyle(CxPalette.ink)
                }
                .padding(.horizontal, 15)
                .padding(.vertical, 9)
                .background(CxPalette.surface, in: Capsule())
                .overlay(Capsule().strokeBorder(CxPalette.border, lineWidth: 1))
                .shadow(color: Color.black.opacity(0.08), radius: 12, x: 0, y: 4)
                .padding(.top, 8)
                .transition(.opacity)
                .task(id: status) {
                    do { try await Task.sleep(for: .seconds(3)) }
                    catch { return }
                    if appState.statusMessage == status {
                        withAnimation(.easeInOut(duration: 0.2)) {
                            appState.statusMessage = nil
                        }
                    }
                }
            }
        }
    }
}
