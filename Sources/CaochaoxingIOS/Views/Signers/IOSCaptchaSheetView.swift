// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

import SwiftUI
import UIKit
import ChaoxingCore

struct IOSCaptchaSheetView: View {
    @Environment(IOSAppState.self) private var appState
    let challenge: IOSCaptchaChallengeRequest

    @State private var captcha: ChaoxingCaptchaData?
    @State private var shadeUIImage: UIImage?
    @State private var cutoutUIImage: UIImage?
    @State private var shadeRawData: Data?
    @State private var cutoutRawData: Data?
    @State private var dragOffsetX: CGFloat = 0
    @State private var isLoading = false
    @State private var isVerifying = false
    @State private var isSolving = false
    @State private var errorMessage: String?
    @State private var hasFinished = false
    @State private var requestVersion = UUID()
    @State private var workTask: Task<Void, Never>?
    @State private var didAutoAttempt = false

    private let displayWidth: CGFloat = 320
    private var displayHeight: CGFloat {
        guard let image = shadeUIImage, image.size.width > 0 else { return 160 }
        return displayWidth * (image.size.height / image.size.width)
    }

    private var scaleFactor: CGFloat {
        guard let image = shadeUIImage, image.size.width > 0 else { return 1 }
        return displayWidth / image.size.width
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 16) {
                Text("拖动下方滑块拼合缺口，或使用本地边缘算法自动定位")
                    .font(.caption)
                    .foregroundStyle(CxPalette.inkSecondary)
                    .multilineTextAlignment(.center)

                if let shade = shadeUIImage {
                    ZStack(alignment: .topLeading) {
                        Image(uiImage: shade)
                            .resizable()
                            .aspectRatio(contentMode: .fit)
                            .frame(width: displayWidth, height: displayHeight)
                            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))

                        if let cutout = cutoutUIImage {
                            let cutoutWidth = cutout.size.width * scaleFactor
                            let cutoutHeight = cutout.size.height * scaleFactor
                            Image(uiImage: cutout)
                                .resizable()
                                .frame(width: cutoutWidth, height: cutoutHeight)
                                .offset(x: dragOffsetX, y: 0)
                        }

                        if isLoading || isVerifying || isSolving {
                            ProgressView()
                                .tint(CxPalette.accent)
                                .frame(width: displayWidth, height: displayHeight)
                                .background(.ultraThinMaterial)
                                .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                        }
                    }
                    .frame(width: displayWidth, height: displayHeight)
                    .overlay(
                        RoundedRectangle(cornerRadius: 12, style: .continuous)
                            .strokeBorder(CxPalette.border, lineWidth: 1)
                    )

                    // 暖调滑块轨道
                    let maxSliderOffset = max(displayWidth - 46, 1)
                    ZStack(alignment: .leading) {
                        RoundedRectangle(cornerRadius: 22, style: .continuous)
                            .fill(CxPalette.surfaceSecondary)
                            .frame(width: displayWidth, height: 44)
                            .overlay(
                                RoundedRectangle(cornerRadius: 22, style: .continuous)
                                    .strokeBorder(CxPalette.border, lineWidth: 1)
                            )
                            .overlay(
                                Text("向右滑动完成核验")
                                    .font(CxTypography.mono(11.5))
                                    .foregroundStyle(CxPalette.inkTertiary)
                            )

                        RoundedRectangle(cornerRadius: 22, style: .continuous)
                            .fill(CxPalette.accent.opacity(0.22))
                            .frame(width: max(44, min(displayWidth, dragOffsetX + 44)), height: 44)

                        Circle()
                            .fill(CxPalette.accent)
                            .frame(width: 40, height: 40)
                            .overlay(
                                Image(systemName: "arrow.right")
                                    .foregroundStyle(CxPalette.onAccent)
                                    .font(.callout.weight(.bold))
                            )
                            .shadow(color: Color.black.opacity(0.12), radius: 4, x: 0, y: 2)
                            .offset(x: max(2, min(maxSliderOffset, dragOffsetX)))
                            .gesture(
                                DragGesture()
                                    .onChanged { value in
                                        guard !isVerifying && !isLoading && !isSolving else { return }
                                        dragOffsetX = max(0, min(maxSliderOffset, value.translation.width))
                                    }
                                    .onEnded { _ in
                                        guard !isVerifying && !isLoading && !isSolving else { return }
                                        workTask = appState.launchSessionTask { await verifyCurrentOffset() }
                                    }
                            )
                    }
                    .frame(width: displayWidth, height: 44)
                } else if isLoading {
                    VStack(spacing: 10) {
                        ProgressView()
                            .tint(CxPalette.accent)
                        Text("正在获取拼图图像...")
                            .font(.caption)
                            .foregroundStyle(CxPalette.inkSecondary)
                    }
                    .frame(width: displayWidth, height: 160)
                    .cxWell(padding: 16, cornerRadius: 12)
                }

                if let errorMessage {
                    CxBadge(errorMessage, tone: .cinnabar, icon: "exclamationmark.triangle")
                }

                HStack(spacing: 12) {
                    Button {
                        workTask = appState.launchSessionTask { await autoSolveAndVerify() }
                    } label: {
                        Label("自动识别缺口", systemImage: "wand.and.stars")
                    }
                    .buttonStyle(CxPrimaryButtonStyle())
                    .disabled(isLoading || isVerifying || isSolving || shadeRawData == nil || cutoutRawData == nil)

                    Button {
                        workTask = appState.launchSessionTask { await loadCaptcha() }
                    } label: {
                        Label("换一张", systemImage: "arrow.clockwise")
                    }
                    .buttonStyle(CxSecondaryButtonStyle())
                    .disabled(isLoading || isVerifying || isSolving)
                }

                Spacer()
            }
            .padding(20)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(CxPalette.canvas.ignoresSafeArea())
            .navigationTitle("滑块安全核验")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") {
                        cancelAndDismiss()
                    }
                }
            }
        }
        .tint(CxPalette.accent)
        .presentationDetents([.large])
        .interactiveDismissDisabled(isVerifying)
        .onAppear { appState.captchaSheetDidAppear(id: challenge.id) }
        .task {
            await loadCaptcha()
        }
        .onDisappear {
            requestVersion = UUID()
            workTask?.cancel()
            appState.completeCaptcha(id: challenge.id, result: .failure(CancellationError()))
        }
    }

    private func loadCaptcha() async {
        guard !hasFinished else { return }
        let version = UUID()
        requestVersion = version
        isLoading = true
        errorMessage = nil
        dragOffsetX = 0
        defer { if requestVersion == version { isLoading = false } }
        do {
            let result = try await ChaoxingCaptchaService.fetchCaptcha(client: challenge.client, refererURL: challenge.refererURL)
            try Task.checkCancellation()
            guard requestVersion == version, !hasFinished else { return }
            captcha = result.captcha
            shadeRawData = result.shadeImageData
            cutoutRawData = result.cutoutImageData
            shadeUIImage = UIImage(data: result.shadeImageData)
            cutoutUIImage = UIImage(data: result.cutoutImageData)
            if appState.autoSolveCaptcha && !didAutoAttempt {
                didAutoAttempt = true
                await autoSolveAndVerify(silentOnFailure: true)
            }
        } catch {
            if requestVersion == version, !hasFinished, !(error is CancellationError), !Task.isCancelled {
                errorMessage = error.localizedDescription
            }
        }
    }

    private func autoSolveAndVerify(silentOnFailure: Bool = false) async {
        guard !hasFinished, !isVerifying, !isSolving, let shadeRawData, let cutoutRawData else { return }
        isSolving = true
        defer { isSolving = false }
        let version = requestVersion
        let solver = Task.detached(priority: .userInitiated) {
            ChaoxingCaptchaSolver.estimateXPosition(shadeImageData: shadeRawData, cutoutImageData: cutoutRawData)
        }
        let estimate = await withTaskCancellationHandler {
            await solver.value
        } onCancel: { solver.cancel() }
        guard !Task.isCancelled, requestVersion == version, !hasFinished else { return }
        guard let estimatedX = estimate else {
            if !silentOnFailure { errorMessage = "自动识别未能定位缺口，请手动拖动" }
            return
        }
        dragOffsetX = CGFloat(estimatedX) * scaleFactor
        await verifyCurrentOffset()
    }

    private func verifyCurrentOffset() async {
        guard let captcha, !isVerifying, !hasFinished else { return }
        let version = requestVersion
        isVerifying = true
        errorMessage = nil
        defer { isVerifying = false }
        let xPosition = Int((dragOffsetX / max(scaleFactor, 0.01)).rounded())
        do {
            let validate = try await ChaoxingCaptchaService.verifyCaptcha(client: challenge.client, captcha: captcha, xPosition: xPosition, refererURL: challenge.refererURL)
            try Task.checkCancellation()
            guard requestVersion == version, !hasFinished else { return }
            if let validate {
                hasFinished = true
                appState.completeCaptcha(id: challenge.id, result: .success(validate))
            } else {
                await loadCaptcha()
                if !hasFinished { errorMessage = "滑块位置不准确，已刷新图片，请重试" }
            }
        } catch {
            if requestVersion == version, !hasFinished, !(error is CancellationError), !Task.isCancelled {
                errorMessage = error.localizedDescription
            }
        }
    }

    private func cancelAndDismiss() {
        hasFinished = true
        requestVersion = UUID()
        workTask?.cancel()
        appState.completeCaptcha(id: challenge.id, result: .failure(CancellationError()))
    }
}
