// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

import SwiftUI
import ChaoxingCore

struct IOSPasswordSignView: View {
    @Environment(IOSAppState.self) private var appState

    let activity: ChaoxingSignActivity
    let isSigning: Bool
    let isPositionRequired: Bool
    let onSubmit: (String, ChaoxingLocationPreset?) async -> Void

    @State private var signCode = ""
    @State private var verificationResult: Bool?
    @State private var isChecking = false
    @State private var verificationRequest = UUID()
    @State private var includeLocation = false
    @State private var selectedPresetId: UUID?

    private var activeLocationPreset: ChaoxingLocationPreset? {
        guard includeLocation else { return nil }
        return appState.locationPresets.first(where: { $0.id == selectedPresetId })
            ?? appState.boundLocation(forCourseId: activity.courseId)
            ?? appState.locationPresets.first
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 8) {
                HStack(spacing: 8) {
                    Text("签到码")
                        .font(CxTypography.mono(11, weight: .semibold))
                        .foregroundStyle(CxPalette.inkTertiary)
                    TextField("输入 4–8 位课堂签到码", text: $signCode)
                        .font(CxTypography.mono(15, weight: .semibold))
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                }
                .cxInputField()

                Button("预校验") {
                    Task { await verifyCode() }
                }
                .buttonStyle(CxSecondaryButtonStyle())
                .disabled(isChecking || signCode.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
            .onChange(of: signCode) { _, _ in
                verificationResult = nil
            }

            if isChecking {
                HStack(spacing: 6) {
                    ProgressView().controlSize(.small)
                    Text("正在核验签到码...")
                        .font(.caption)
                        .foregroundStyle(CxPalette.inkSecondary)
                }
            } else if let verificationResult {
                CxBadge(
                    verificationResult ? "签到码核验通过" : "签到码有误",
                    tone: verificationResult ? .sage : .cinnabar,
                    icon: verificationResult ? "checkmark" : "xmark"
                )
            }

            IOSLocationAttachmentPickerView(
                courseId: activity.courseId,
                isPositionRequired: isPositionRequired,
                includeLocation: $includeLocation,
                selectedPresetId: $selectedPresetId
            )

            Button {
                let trimmed = signCode.trimmingCharacters(in: .whitespacesAndNewlines)
                guard !isPositionRequired || activeLocationPreset != nil else { appState.errorMessage = "该活动要求位置，请先选择有效预设"; return }
                appState.launchSessionTask { await onSubmit(trimmed, activeLocationPreset) }
            } label: {
                HStack(spacing: 6) {
                    Image(systemName: "number.square.fill")
                    Text("提交签到码签到")
                }
                .frame(maxWidth: .infinity)
            }
            .buttonStyle(CxPrimaryButtonStyle())
            .disabled(isSigning || signCode.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
        }
        .padding(.vertical, 4)
    }

    private func verifyCode() async {
        let code = signCode.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let client = appState.activeClient, !code.isEmpty else { return }
        let token = appState.sessionToken
        let request = UUID()
        verificationRequest = request
        isChecking = true
        defer { if verificationRequest == request { isChecking = false } }
        do {
            let result = try await ChaoxingSignExecutor.checkSignCode(client: client, activeId: activity.id, signCode: code)
            try appState.checkSession(token)
            guard verificationRequest == request, signCode.trimmingCharacters(in: .whitespacesAndNewlines) == code else { return }
            verificationResult = result
        } catch {
            if appState.isCurrent(token), !(error is CancellationError), verificationRequest == request { appState.errorMessage = error.localizedDescription }
        }
    }
}
