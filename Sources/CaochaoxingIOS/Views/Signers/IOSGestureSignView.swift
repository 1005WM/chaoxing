// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

import SwiftUI
import ChaoxingCore

struct IOSGestureSignView: View {
    @Environment(IOSAppState.self) private var appState

    let activity: ChaoxingSignActivity
    let isSigning: Bool
    let isPositionRequired: Bool
    let onSubmit: (String, ChaoxingLocationPreset?) async -> Void

    @State private var selectedNodes: [Int] = []
    @State private var currentPoint: CGPoint?
    @State private var verificationResult: Bool?
    @State private var isChecking = false
    @State private var verificationRequest = UUID()
    @State private var includeLocation = false
    @State private var selectedPresetId: UUID?

    private let gridSize: CGFloat = 246
    private let nodeRadius: CGFloat = 23

    private var codeString: String {
        selectedNodes.map(String.init).joined()
    }

    private var formattedSequence: String {
        selectedNodes.isEmpty ? "—" : selectedNodes.map(String.init).joined(separator: " → ")
    }

    private var activeLocationPreset: ChaoxingLocationPreset? {
        guard includeLocation else { return nil }
        return appState.locationPresets.first(where: { $0.id == selectedPresetId })
            ?? appState.boundLocation(forCourseId: activity.courseId)
            ?? appState.locationPresets.first
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("按教师公布的手势轨迹滑动连接节点（编号 1–9）")
                .font(.caption)
                .foregroundStyle(CxPalette.inkSecondary)

            HStack {
                Spacer()
                ZStack {
                    RoundedRectangle(cornerRadius: 16, style: .continuous)
                        .fill(CxPalette.surfaceSecondary)
                        .frame(width: gridSize, height: gridSize)
                        .overlay(
                            RoundedRectangle(cornerRadius: 16, style: .continuous)
                                .strokeBorder(CxPalette.border, lineWidth: 1)
                        )

                    Canvas { context, size in
                        guard !selectedNodes.isEmpty else { return }
                        var path = Path()
                        for (index, node) in selectedNodes.enumerated() {
                            let pt = centerPoint(for: node, in: size)
                            if index == 0 {
                                path.move(to: pt)
                            } else {
                                path.addLine(to: pt)
                            }
                        }
                        if let currentPoint {
                            path.addLine(to: currentPoint)
                        }
                        context.stroke(
                            path,
                            with: .color(CxPalette.accent.opacity(0.85)),
                            style: StrokeStyle(lineWidth: 4, lineCap: .round, lineJoin: .round)
                        )
                    }
                    .frame(width: gridSize, height: gridSize)

                    ForEach(1...9, id: \.self) { node in
                        let pt = centerPoint(for: node, in: CGSize(width: gridSize, height: gridSize))
                        let orderIdx = selectedNodes.firstIndex(of: node)
                        let isSelected = orderIdx != nil

                        ZStack {
                            if isSelected {
                                Circle()
                                    .fill(CxPalette.accent.opacity(0.16))
                                    .frame(width: nodeRadius * 2 + 10, height: nodeRadius * 2 + 10)
                            }

                            Circle()
                                .fill(isSelected ? CxPalette.accent : CxPalette.surface)
                                .frame(width: nodeRadius * 2, height: nodeRadius * 2)
                                .overlay(
                                    Circle()
                                        .strokeBorder(
                                            isSelected ? CxPalette.accent : CxPalette.border,
                                            lineWidth: 1.2
                                        )
                                )

                            Text("\(node)")
                                .font(CxTypography.mono(13, weight: isSelected ? .bold : .medium))
                                .foregroundStyle(isSelected ? CxPalette.onAccent : CxPalette.inkSecondary)
                        }
                        .position(pt)
                    }
                }
                .frame(width: gridSize, height: gridSize)
                .contentShape(Rectangle())
                .gesture(
                    DragGesture(minimumDistance: 0)
                        .onChanged { value in
                            if currentPoint == nil {
                                selectedNodes = []
                                verificationResult = nil
                            }
                            currentPoint = value.location
                            if let hit = hitNode(at: value.location, in: CGSize(width: gridSize, height: gridSize)),
                               !selectedNodes.contains(hit) {
                                selectedNodes.append(hit)
                            }
                        }
                        .onEnded { _ in
                            currentPoint = nil
                            if !codeString.isEmpty {
                                Task { await verifyGestureCode() }
                            }
                        }
                )
                Spacer()
            }

            HStack {
                Text("已选择：\(formattedSequence)")
                    .font(CxTypography.mono(12))
                    .foregroundStyle(CxPalette.ink)
                Spacer()
                if isChecking {
                    ProgressView()
                        .controlSize(.small)
                } else if let verificationResult {
                    CxBadge(
                        verificationResult ? "手势正确" : "手势不符",
                        tone: verificationResult ? .sage : .cinnabar,
                        icon: verificationResult ? "checkmark" : "xmark"
                    )
                }
            }
            .cxWell(padding: 10, cornerRadius: 9)

            IOSLocationAttachmentPickerView(
                courseId: activity.courseId,
                isPositionRequired: isPositionRequired,
                includeLocation: $includeLocation,
                selectedPresetId: $selectedPresetId
            )

            HStack(spacing: 10) {
                Button("清空") {
                    selectedNodes = []
                    currentPoint = nil
                    verificationResult = nil
                }
                .buttonStyle(CxSecondaryButtonStyle())

                Button {
                    guard !isPositionRequired || activeLocationPreset != nil else { appState.errorMessage = "该活动要求位置，请先选择有效预设"; return }
                    appState.launchSessionTask { await onSubmit(codeString, activeLocationPreset) }
                } label: {
                    HStack(spacing: 6) {
                        Image(systemName: "hand.draw.fill")
                        Text("提交手势签到")
                    }
                    .frame(maxWidth: .infinity)
                }
                .buttonStyle(CxPrimaryButtonStyle())
                .disabled(isSigning || codeString.isEmpty)
            }
        }
        .padding(.vertical, 4)
    }

    private func centerPoint(for node: Int, in size: CGSize) -> CGPoint {
        let index = node - 1
        let row = index / 3
        let col = index % 3
        let stepX = size.width / 3.0
        let stepY = size.height / 3.0
        return CGPoint(
            x: stepX * (CGFloat(col) + 0.5),
            y: stepY * (CGFloat(row) + 0.5)
        )
    }

    private func hitNode(at location: CGPoint, in size: CGSize) -> Int? {
        for node in 1...9 {
            let center = centerPoint(for: node, in: size)
            let dx = location.x - center.x
            let dy = location.y - center.y
            if hypot(dx, dy) <= nodeRadius + 10 {
                return node
            }
        }
        return nil
    }

    private func verifyGestureCode() async {
        let code = codeString.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let client = appState.activeClient, !code.isEmpty else { return }
        let token = appState.sessionToken
        let request = UUID()
        verificationRequest = request
        isChecking = true
        defer { if verificationRequest == request { isChecking = false } }
        do {
            let result = try await ChaoxingSignExecutor.checkSignCode(client: client, activeId: activity.id, signCode: code)
            try appState.checkSession(token)
            guard verificationRequest == request, codeString.trimmingCharacters(in: .whitespacesAndNewlines) == code else { return }
            verificationResult = result
        } catch {
            if appState.isCurrent(token), !(error is CancellationError), verificationRequest == request { appState.errorMessage = error.localizedDescription }
        }
    }
}
