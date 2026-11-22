// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

import SwiftUI
import UniformTypeIdentifiers
import ChaoxingCore

struct CaochaoxingBackupDocument: FileDocument {
    static let readableContentTypes: [UTType] = [.json]
    let data: Data

    init(data: Data) { self.data = data }

    init(configuration: ReadConfiguration) throws {
        guard let data = configuration.file.regularFileContents else {
            throw CocoaError(.fileReadCorruptFile)
        }
        self.data = data
    }

    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        FileWrapper(regularFileWithContents: data)
    }
}

struct IOSSettingsView: View {
    @Environment(IOSAppState.self) private var appState
    @State private var newProfileName = ""
    @State private var newProfileUA = ""
    @State private var manualFidText = ""
    @State private var includeAccountsInBackup = false
    @State private var showFileImporter = false
    @State private var showLocationManager = false
    @State private var showFileExporter = false
    @State private var exportDocument: CaochaoxingBackupDocument?
    @State private var exportFilename = "caochaoxing_presets_backup.json"
    @State private var showLogoutConfirmation = false

    var body: some View {
        @Bindable var state = appState

        NavigationStack {
            Form {
                if let account = appState.activeAccount {
                    Section {
                        LabeledContent("当前姓名") {
                            Text(account.name.isEmpty ? "未设置" : account.name)
                                .font(CxTypography.serifHeadline(15.5))
                                .foregroundStyle(CxPalette.ink)
                        }
                        .cxCardRow()

                        LabeledContent("绑定手机") {
                            Text(account.phoneNumber)
                                .font(CxTypography.mono(13))
                                .foregroundStyle(CxPalette.inkSecondary)
                        }
                        .cxCardRow()

                        if !account.schools.isEmpty {
                            Picker("所属单位 (FID)", selection: Binding(
                                get: { account.configuredFid },
                                set: { newFid in
                                    Task { await appState.updateFid(for: account, fid: newFid) }
                                }
                            )) {
                                ForEach(account.schools) { school in
                                    Text("\(school.name) (\(school.fid))").tag(school.fid)
                                }
                            }
                            .cxCardRow()
                        }

                        HStack(spacing: 8) {
                            TextField("手动指定单位 FID 编号", text: $manualFidText)
                                .keyboardType(.numberPad)
                                .font(CxTypography.mono(13))
                            Button("应用") {
                                if let fid = Int(manualFidText.trimmingCharacters(in: .whitespaces)) {
                                    Task {
                                        await appState.updateFid(for: account, fid: fid)
                                        manualFidText = ""
                                    }
                                }
                            }
                            .buttonStyle(CxSecondaryButtonStyle(compact: true))
                            .disabled(Int(manualFidText.trimmingCharacters(in: .whitespaces)) == nil)
                        }
                        .cxCardRow()
                    } header: {
                        CxSectionHeader("当前账号", trailing: "学校 \(account.configuredFid)")
                    }
                }

                Section {
                    Toggle("位置微调（约 ±5 米）", isOn: $state.enableLocationJitter)
                        .onChange(of: appState.enableLocationJitter) { _, _ in
                            Task { await appState.saveToDisk() }
                        }
                        .cxCardRow()

                    Picker("多账号错峰间隔", selection: $state.batchSignDelayMs) {
                        Text("无间隔").tag(0)
                        Text("约 1.2 秒").tag(1200)
                        Text("约 2.5 秒").tag(2500)
                        Text("约 4 秒").tag(4000)
                    }
                    .onChange(of: appState.batchSignDelayMs) { _, _ in
                        Task { await appState.saveToDisk() }
                    }
                    .cxCardRow()

                    Toggle("上传前轻微处理图片", isOn: $state.enableImageObfuscation)
                        .onChange(of: appState.enableImageObfuscation) { _, _ in
                            Task { await appState.saveToDisk() }
                        }
                        .cxCardRow()

                    Toggle("自动识别滑块位置", isOn: $state.autoSolveCaptcha)
                        .onChange(of: appState.autoSolveCaptcha) { _, _ in
                            Task { await appState.saveToDisk() }
                        }
                        .cxCardRow()
                } header: {
                    CxSectionHeader("签到选项")
                }

                Section {
                    Button {
                        showLocationManager = true
                    } label: {
                        Label("管理地点", systemImage: "mappin.and.ellipse")
                    }
                    .cxCardRow()
                    NavigationLink {
                        IOSSignHistoryView()
                    } label: {
                        HStack {
                            Label("签到历史", systemImage: "clock.arrow.circlepath")
                                .foregroundStyle(CxPalette.ink)
                            Spacer()
                            CxBadge("\(appState.signHistory.count) 条", tone: .neutral, monospaced: true)
                        }
                    }
                    .cxCardRow()

                    VStack(alignment: .leading, spacing: 8) {
                        Text("外部 URL Scheme 协议入口")
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(CxPalette.inkSecondary)

                        VStack(alignment: .leading, spacing: 5) {
                            Text("caochaoxing://sign?activeId=12345")
                            Text("caochaoxing://qr?data=SIGNIN%3A...")
                        }
                        .font(CxTypography.mono(11.5))
                        .foregroundStyle(CxPalette.ink)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .cxWell(padding: 10, cornerRadius: 8)
                    }
                    .padding(.vertical, 4)
                    .cxCardRow()
                } header: {
                    CxSectionHeader("历史与快捷入口")
                }

                Section {
                    Toggle("备份时包含账号登录凭据", isOn: $includeAccountsInBackup)
                        .cxCardRow()

                    Button {
                        do {
                            exportDocument = CaochaoxingBackupDocument(
                                data: try appState.exportBackupData(includeAccounts: includeAccountsInBackup)
                            )
                            exportFilename = includeAccountsInBackup
                                ? "caochaoxing_full_backup.json" : "caochaoxing_presets_backup.json"
                            showFileExporter = true
                        } catch {
                            appState.errorMessage = "导出备份失败：\(error.localizedDescription)"
                        }
                    } label: {
                        Label("导出备份", systemImage: "square.and.arrow.up")
                    }
                    .cxCardRow()

                    Button {
                        showFileImporter = true
                    } label: {
                        Label("导入备份", systemImage: "square.and.arrow.down")
                            .foregroundStyle(CxPalette.ink)
                    }
                    .cxCardRow()
                } header: {
                    CxSectionHeader("备份与恢复")
                } footer: {
                    Text(includeAccountsInBackup
                         ? "完整备份包含可用于登录的账号凭据，请勿分享给他人。导入会合并账号与历史，并恢复备份中的偏好设置。"
                         : "备份保留地点、课程名称和活动记录，省略账号凭据及历史备注。仅用于自己恢复数据，请勿上传至公开 Issue。")
                }

                Section {
                    Picker("内置客户端标识", selection: Binding(
                        get: { appState.selectedClientPreset },
                        set: { preset in
                            appState.selectedCustomClientId = nil
                            appState.customUserAgent = ""
                            appState.selectedClientPreset = preset
                            Task { await appState.saveToDisk() }
                        }
                    )) {
                        ForEach(ChaoxingClientPreset.allCases) { preset in
                            Text(preset.displayName).tag(preset)
                        }
                    }
                    .cxCardRow()

                    if !appState.customClientProfiles.isEmpty {
                        Picker("已保存校园定制版", selection: $state.selectedCustomClientId) {
                            Text("使用上方内置客户端").tag(UUID?.none)
                            ForEach(appState.customClientProfiles) { profile in
                                Text(profile.name).tag(UUID?.some(profile.id))
                            }
                        }
                        .onChange(of: appState.selectedCustomClientId) { _, _ in
                            Task { await appState.saveToDisk() }
                        }
                        .cxCardRow()
                    }
                } header: {
                    CxSectionHeader("客户端")
                }

                Section {
                    TextField("方案备注名（如：本校定制版）", text: $newProfileName)
                        .cxCardRow()
                    TextField("粘贴完整 User-Agent 字符串", text: $newProfileUA)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .font(CxTypography.mono(12))
                        .cxCardRow()
                    Button("保存并启用该方案") {
                        Task {
                            await appState.addCustomClientProfile(name: newProfileName, userAgent: newProfileUA)
                            newProfileName = ""
                            newProfileUA = ""
                        }
                    }
                    .foregroundStyle(CxPalette.accent)
                    .disabled(newProfileName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ||
                              newProfileUA.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    .cxCardRow()
                } header: {
                    CxSectionHeader("添加校园客户端")
                }

                Section {
                    Button(role: .destructive) {
                        showLogoutConfirmation = true
                    } label: {
                        HStack {
                            Spacer()
                            Label("退出所有账号", systemImage: "rectangle.portrait.and.arrow.right")
                                .fontWeight(.semibold)
                                .foregroundStyle(CxPalette.cinnabar)
                            Spacer()
                        }
                    }
                    .cxCardRow()
                }
            }
            .cxEditorialList()
            .navigationTitle("设置")
            .sheet(isPresented: $showLocationManager) { IOSLocationPresetsView() }
            .confirmationDialog("退出所有账号？", isPresented: $showLogoutConfirmation, titleVisibility: .visible) {
                Button("退出所有账号", role: .destructive) {
                    Task { await appState.logoutAll() }
                }
            } message: {
                Text("将删除本机登录会话并停止后续操作，保留偏好设置和签到历史。已发出的请求无法撤回。")
            }
            .fileExporter(isPresented: $showFileExporter, document: exportDocument,
                          contentType: .json, defaultFilename: exportFilename) { result in
                if case .failure(let error) = result {
                    appState.errorMessage = "保存备份失败：\(error.localizedDescription)"
                }
                exportDocument = nil
            }
            .fileImporter(
                isPresented: $showFileImporter,
                allowedContentTypes: [.json],
                allowsMultipleSelection: false
            ) { result in
                switch result {
                case .success(let urls):
                    guard let url = urls.first else { return }
                    let hasAccess = url.startAccessingSecurityScopedResource()
                    defer { if hasAccess { url.stopAccessingSecurityScopedResource() } }
                    if let data = try? Data(contentsOf: url) {
                        Task {
                            do {
                                try await appState.importBackupData(data)
                            } catch {
                                appState.errorMessage = "导入备份失败：\(error.localizedDescription)"
                            }
                        }
                    } else {
                        appState.errorMessage = "无法读取所选备份文件"
                    }
                case .failure(let error):
                    appState.errorMessage = error.localizedDescription
                }
            }
        }
    }
}

struct IOSSignHistoryView: View {
    @Environment(IOSAppState.self) private var appState
    @State private var showClearConfirmation = false

    var body: some View {
        List {
            if appState.signHistory.isEmpty {
                ContentUnavailableView(
                    "尚无本地执行记录",
                    systemImage: "clock.arrow.circlepath",
                    description: Text("完成任意签到操作后，结果摘要将归档于此")
                )
                .foregroundStyle(CxPalette.inkSecondary)
                .cxCardRow()
            } else {
                Section {
                    ForEach(appState.signHistory) { entry in
                        VStack(alignment: .leading, spacing: 6) {
                            Text(entry.activityName)
                                .font(CxTypography.serifHeadline(16))
                                .foregroundStyle(CxPalette.ink)
                            Text(entry.displayCounts)
                                .font(.subheadline)
                                .foregroundStyle(CxPalette.ink)

                            Text("\(entry.courseName) · AID \(entry.activeId)")
                                .font(CxTypography.mono(11.5))
                                .foregroundStyle(CxPalette.inkSecondary)

                            if !entry.summary.isEmpty && entry.summary != entry.displayCounts {
                                Text(entry.summary)
                                    .font(.caption)
                                    .foregroundStyle(CxPalette.inkSecondary)
                                    .lineLimit(2)
                            }

                            Text(entry.timestamp.formatted(date: .abbreviated, time: .shortened))
                                .font(CxTypography.mono(10.5))
                                .foregroundStyle(CxPalette.inkTertiary)
                        }
                        .padding(.vertical, 4)
                        .cxCardRow()
                    }
                } header: {
                    CxSectionHeader("历史归档", trailing: "\(appState.signHistory.count) 条")
                }
            }
        }
        .listStyle(.insetGrouped)
        .cxEditorialList()
        .navigationTitle("签到历史")
        .navigationBarTitleDisplayMode(.inline)
        .confirmationDialog("清空签到历史？", isPresented: $showClearConfirmation, titleVisibility: .visible) {
            Button("清空历史", role: .destructive) {
                Task { await appState.clearSignHistory() }
            }
        } message: {
            Text("只会清除本机记录，不影响服务器上的签到结果。")
        }
        .toolbar {
            if !appState.signHistory.isEmpty {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("清空", role: .destructive) {
                        showClearConfirmation = true
                    }
                    .foregroundStyle(CxPalette.cinnabar)
                }
            }
        }
    }
}
