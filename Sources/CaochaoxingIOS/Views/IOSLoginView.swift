// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

import SwiftUI
import ChaoxingCore

struct IOSLoginView: View {
    @Environment(IOSAppState.self) private var appState
    @State private var phoneNumber = ""
    @State private var password = ""
    @State private var isLoggingIn = false
    @State private var showAdvanced = false
    @State private var localError: String?
    @FocusState private var focusedField: Field?
    private enum Field { case account, password }

    private var canLogin: Bool {
        !isLoggingIn && !phoneNumber.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && !password.isEmpty
    }

    var body: some View {
        @Bindable var state = appState
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 28) {
                    VStack(alignment: .leading, spacing: 10) {
                        Image(systemName: "book.closed.fill")
                            .font(.largeTitle)
                            .foregroundStyle(CxPalette.accent)
                            .accessibilityHidden(true)
                        Text("Caochaoxing")
                            .font(.largeTitle.bold())
                        Text("登录后查看课程与签到活动")
                            .font(.subheadline)
                            .foregroundStyle(CxPalette.inkSecondary)
                    }
                    .padding(.top, 28)

                    VStack(alignment: .leading, spacing: 18) {
                        VStack(alignment: .leading, spacing: 8) {
                            Text("账号").font(.subheadline.weight(.medium))
                            TextField("手机号或学号", text: $phoneNumber)
                                .textContentType(.username)
                                .keyboardType(.asciiCapable)
                                .textInputAutocapitalization(.never)
                                .autocorrectionDisabled()
                                .focused($focusedField, equals: .account)
                                .submitLabel(.next)
                                .onSubmit { focusedField = .password }
                                .cxInputField()
                        }
                        VStack(alignment: .leading, spacing: 8) {
                            Text("密码").font(.subheadline.weight(.medium))
                            SecureField("学习通登录密码", text: $password)
                                .textContentType(.password)
                                .focused($focusedField, equals: .password)
                                .submitLabel(.go)
                                .onSubmit { if canLogin { submitLogin() } }
                                .cxInputField()
                        }
                        if let localError {
                            Label(localError, systemImage: "exclamationmark.circle")
                                .font(.subheadline)
                                .foregroundStyle(CxPalette.cinnabar)
                                .fixedSize(horizontal: false, vertical: true)
                                .accessibilityIdentifier("login.error")
                        }
                        Button(action: submitLogin) {
                            HStack(spacing: 8) {
                                if isLoggingIn { ProgressView().tint(CxPalette.onAccent) }
                                Text(isLoggingIn ? "正在登录…" : "登录")
                            }
                            .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(CxPrimaryButtonStyle())
                        .disabled(!canLogin)
                        .accessibilityIdentifier("login.submit")
                    }

                    DisclosureGroup("客户端设置", isExpanded: $showAdvanced) {
                        VStack(alignment: .leading, spacing: 16) {
                            Picker("内置客户端", selection: Binding(
                                get: { appState.selectedClientPreset },
                                set: { preset in
                                    appState.selectedCustomClientId = nil
                                    appState.customUserAgent = ""
                                    appState.selectedClientPreset = preset
                                }
                            )) {
                                ForEach(ChaoxingClientPreset.allCases) { preset in
                                    Text(preset.displayName).tag(preset)
                                }
                            }
                            .pickerStyle(.menu)
                            if !appState.customClientProfiles.isEmpty {
                                Picker("校园客户端", selection: $state.selectedCustomClientId) {
                                    Text("使用内置客户端").tag(UUID?.none)
                                    ForEach(appState.customClientProfiles) { profile in
                                        Text(profile.name).tag(UUID?.some(profile.id))
                                    }
                                }
                                .pickerStyle(.menu)
                            }
                            Text("一般无需修改。学校使用定制客户端时可在此选择。")
                                .font(.footnote)
                                .foregroundStyle(CxPalette.inkSecondary)
                        }
                        .padding(.top, 12)
                    }
                    .font(.subheadline)
                    .tint(CxPalette.accent)

                    Text("登录信息保存在此设备。导出完整备份时会包含账号凭据，请妥善保管。")
                        .font(.footnote)
                        .foregroundStyle(CxPalette.inkSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .foregroundStyle(CxPalette.ink)
                .frame(maxWidth: 440)
                .padding(24)
                .frame(maxWidth: .infinity)
            }
            .scrollDismissesKeyboard(.interactively)
            .background(CxPalette.surface.ignoresSafeArea())
        }
    }

    private func submitLogin() {
        guard canLogin else { return }
        focusedField = nil
        localError = nil
        isLoggingIn = true
        let account = phoneNumber.trimmingCharacters(in: .whitespacesAndNewlines)
        let submittedPassword = password
        Task {
            defer { isLoggingIn = false }
            do {
                try await appState.loginPrimary(phoneNumber: account, password: submittedPassword)
                password = ""
            } catch is CancellationError {
                // A logout or superseding operation deliberately invalidated this login.
            } catch {
                localError = error.localizedDescription
            }
        }
    }
}
