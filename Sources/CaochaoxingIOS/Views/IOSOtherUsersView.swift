// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

import SwiftUI
import ChaoxingCore

struct IOSOtherUsersView: View {
    @Environment(IOSAppState.self) private var appState

    @State private var phoneNumber = ""
    @State private var password = ""
    @State private var isAdding = false

    @State private var batchTagText = ""
    @State private var editingAccountTags: ChaoxingAccountSession?

    var body: some View {
        NavigationStack {
            List {
                Section {
                    VStack(alignment: .leading, spacing: 10) {
                        HStack(spacing: 10) {
                            Image(systemName: "person.badge.plus")
                                .foregroundStyle(CxPalette.inkTertiary)
                                .frame(width: 18)
                            TextField("手机号或学号", text: $phoneNumber)
                                .keyboardType(.asciiCapable)
                                .textContentType(.username)
                                .textInputAutocapitalization(.never)
                                .autocorrectionDisabled()
                                .foregroundStyle(CxPalette.ink)
                        }
                        .cxInputField()

                        HStack(spacing: 10) {
                            Image(systemName: "key")
                                .foregroundStyle(CxPalette.inkTertiary)
                                .frame(width: 18)
                            SecureField("登录密码", text: $password)
                                .textContentType(.password)
                                .foregroundStyle(CxPalette.ink)
                        }
                        .cxInputField()

                        Button {
                            addAccount()
                        } label: {
                            HStack(spacing: 8) {
                                if isAdding {
                                    ProgressView()
                                        .tint(CxPalette.onAccent)
                                } else {
                                    Image(systemName: "plus.circle.fill")
                                }
                                Text(isAdding ? "正在添加…" : "添加账号")
                            }
                            .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(CxPrimaryButtonStyle())
                        .disabled(isAdding || phoneNumber.isEmpty || password.isEmpty)
                    }
                    .padding(.vertical, 4)
                    .cxCardRow()
                } header: {
                    CxSectionHeader("添加账号")
                }

                if !appState.accounts.isEmpty {
                    Section {
                        VStack(alignment: .leading, spacing: 10) {
                            HStack(spacing: 8) {
                                TextField("分组标签名（如：302寝室 / 实验A组）", text: $batchTagText)
                                    .cxInputField()

                                Button("全员打标") {
                                    let phones = Set(appState.accounts.map(\.phoneNumber))
                                    let tag = batchTagText
                                    batchTagText = ""
                                    Task { await appState.addTag(tag, to: phones) }
                                }
                                .buttonStyle(CxSecondaryButtonStyle(compact: true))
                                .disabled(batchTagText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                            }

                            if !appState.allAccountTags.isEmpty {
                                CxFlowLayout(horizontalSpacing: 8, verticalSpacing: 8) {
                                    ForEach(appState.allAccountTags, id: \.self) { tag in
                                        let count = appState.accounts.filter { $0.tags.contains(tag) }.count
                                        CxBadge("\(tag) · \(count)人", tone: .amber, icon: "tag.fill")
                                    }
                                }
                                .padding(.top, 2)
                            }
                        }
                        .padding(.vertical, 4)
                        .cxCardRow()
                    } header: {
                        CxSectionHeader("账号分组", trailing: "\(appState.allAccountTags.count) 个")
                    }
                }

                Section {
                    ForEach(appState.accounts) { account in
                        let isActive = account.phoneNumber == appState.activePhoneNumber
                        HStack(alignment: .center, spacing: 12) {
                            CxIconTile(
                                account.isPrimary ? "person.crop.circle.badge.checkmark" : "person.crop.circle",
                                tone: isActive ? .sage : (account.isPrimary ? .accent : .neutral),
                                size: 36
                            )

                            VStack(alignment: .leading, spacing: 4) {
                                HStack(spacing: 6) {
                                    Text(account.name.isEmpty ? account.phoneNumber : account.name)
                                        .font(CxTypography.serifHeadline(16))
                                        .foregroundStyle(CxPalette.ink)
                                        .fixedSize(horizontal: false, vertical: true)

                                    if account.isPrimary {
                                        CxBadge("主账号", tone: .accent)
                                    }
                                    if isActive {
                                        CxBadge("当前账号", tone: .sage, showDot: true)
                                    }
                                }

                                Text("\(account.phoneNumber)")
                                    .font(CxTypography.mono(11))
                                    .foregroundStyle(CxPalette.inkSecondary)
                                    .fixedSize(horizontal: false, vertical: true)

                                if !account.tags.isEmpty {
                                    HStack(spacing: 5) {
                                        ForEach(account.tags, id: \.self) { tag in
                                            CxBadge(tag, tone: .amber)
                                        }
                                    }
                                    .padding(.top, 1)
                                }
                            }

                            Spacer(minLength: 6)

                            VStack(alignment: .trailing, spacing: 6) {
                                Button("分组") {
                                    editingAccountTags = account
                                }
                                .buttonStyle(CxSecondaryButtonStyle(compact: true))

                                if !isActive {
                                    Button("切换") {
                                        Task { await appState.switchActiveAccount(to: account) }
                                    }
                                    .buttonStyle(CxSecondaryButtonStyle(compact: true))
                                }
                            }
                        }
                        .padding(.vertical, 4)
                        .cxCardRow()
                        .swipeActions(edge: .trailing) {
                            if !account.isPrimary {
                                Button("移除", role: .destructive) {
                                    Task { await appState.removeAccount(account) }
                                }
                                .tint(CxPalette.cinnabar)
                            }
                            Button("换设备码") {
                                Task { await appState.regenerateDeviceCode(for: account.phoneNumber) }
                            }
                            .tint(CxPalette.amber)
                        }
                    }
                } header: {
                    CxSectionHeader("已登录账号", trailing: "\(appState.accounts.count) 个")
                }
            }
            .listStyle(.insetGrouped)
            .cxEditorialList()
            .navigationTitle("账号")
            .sheet(item: $editingAccountTags) { target in
                AccountTagEditorSheet(account: target)
            }
        }
    }

    private func addAccount() {
        isAdding = true
        Task {
            defer { isAdding = false }
            do {
                try await appState.addOtherAccount(phoneNumber: phoneNumber, password: password)
                phoneNumber = ""
                password = ""
                appState.statusMessage = "已添加账号"
            } catch is CancellationError {
                // The account operation was invalidated by logout or cancellation.
            } catch {
                appState.errorMessage = error.localizedDescription
            }
        }
    }
}

private struct AccountTagEditorSheet: View {
    @Environment(IOSAppState.self) private var appState
    @Environment(\.dismiss) private var dismiss

    let account: ChaoxingAccountSession
    @State private var tags: [String]
    @State private var newTag = ""

    init(account: ChaoxingAccountSession) {
        self.account = account
        _tags = State(initialValue: account.tags)
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    if tags.isEmpty {
                        Text("尚未分配任何分组标签")
                            .font(.subheadline)
                            .foregroundStyle(CxPalette.inkSecondary)
                            .cxCardRow()
                    } else {
                        ForEach(tags, id: \.self) { tag in
                            HStack {
                                CxBadge(tag, tone: .amber, icon: "tag.fill")
                                Spacer()
                                Button("移除", role: .destructive) {
                                    tags.removeAll { $0 == tag }
                                }
                                .font(.caption.weight(.medium))
                                .foregroundStyle(CxPalette.cinnabar)
                                .buttonStyle(.borderless)
                            }
                            .cxCardRow()
                        }
                    }
                } header: {
                    CxSectionHeader("当前所属分组")
                }

                Section {
                    HStack(spacing: 8) {
                        TextField("输入分组名（如：302寝室）", text: $newTag)
                        Button("添加") {
                            let trimmed = newTag.trimmingCharacters(in: .whitespacesAndNewlines)
                            if !trimmed.isEmpty && !tags.contains(trimmed) {
                                tags.append(trimmed)
                            }
                            newTag = ""
                        }
                        .buttonStyle(CxSecondaryButtonStyle(compact: true))
                        .disabled(newTag.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    }
                    .cxCardRow()

                    let suggestions = appState.allAccountTags.filter { !tags.contains($0) }
                    if !suggestions.isEmpty {
                        CxFlowLayout(horizontalSpacing: 8, verticalSpacing: 8) {
                            ForEach(suggestions, id: \.self) { existing in
                                Button("+ \(existing)") {
                                    tags.append(existing)
                                }
                                .buttonStyle(CxSecondaryButtonStyle(compact: true))
                            }
                        }
                        .padding(.vertical, 4)
                        .cxCardRow()
                    }
                } header: {
                    CxSectionHeader("添加分组")
                }
            }
            .cxEditorialList()
            .navigationTitle("\(account.name.isEmpty ? account.phoneNumber : account.name) · 分组")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("保存") {
                        Task {
                            await appState.setTags(tags, for: account.phoneNumber)
                            dismiss()
                        }
                    }
                    .fontWeight(.semibold)
                }
            }
        }
        .tint(CxPalette.accent)
        .presentationDetents([.medium])
    }
}
