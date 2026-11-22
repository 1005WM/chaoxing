// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

import SwiftUI
import PhotosUI
import UIKit
import ChaoxingCore

enum IOSPhotoSubmission: Sendable {
    case profileAvatar
    case imageData(Data)
    case objectId(String?)
}

struct IOSPhotoSignView: View {
    @Environment(IOSAppState.self) private var appState

    let activity: ChaoxingSignActivity
    var requiresPhoto = true
    let isSigning: Bool
    let onSubmit: (IOSPhotoSubmission) async -> Void

    @State private var selectedItem: PhotosPickerItem?
    @State private var previewImage: UIImage?
    @State private var imageData: Data?
    @State private var isLoadingImage = false
    @State private var imageRequest = UUID()

    var body: some View {
        @Bindable var state = appState

        VStack(alignment: .leading, spacing: 12) {
            if let previewImage {
                Image(uiImage: previewImage)
                    .resizable()
                    .scaledToFit()
                    .frame(maxHeight: 220)
                    .frame(maxWidth: .infinity)
                    .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                    .overlay(
                        RoundedRectangle(cornerRadius: 12, style: .continuous)
                            .strokeBorder(CxPalette.border, lineWidth: 1)
                    )
            } else {
                VStack(spacing: 8) {
                    Image(systemName: "photo.on.rectangle.angled")
                        .font(.system(size: 28, weight: .regular))
                        .foregroundStyle(CxPalette.inkTertiary)
                    Text("尚未选择现场照片")
                        .font(.caption.weight(.medium))
                        .foregroundStyle(CxPalette.inkSecondary)
                }
                .frame(maxWidth: .infinity)
                .frame(height: 136)
                .cxWell(padding: 12, cornerRadius: 12)
            }

            Toggle("图片像素防重微扰（多账号独立生成哈希）", isOn: $state.enableImageObfuscation)
                .font(.caption)
                .foregroundStyle(CxPalette.ink)

            PhotosPicker(selection: $selectedItem, matching: .images) {
                Label("从系统相册选取图片", systemImage: "photo")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(CxSecondaryButtonStyle())
            .onChange(of: selectedItem) { _, newItem in
                Task { await loadPickedImage(newItem) }
            }

            Button {
                guard let data = imageData else { return }
                appState.launchSessionTask { await onSubmit(.imageData(data)) }
            } label: {
                HStack(spacing: 6) {
                    if isLoadingImage {
                        ProgressView()
                            .tint(CxPalette.onAccent)
                    } else {
                        Image(systemName: "arrow.up.doc.fill")
                    }
                    Text("上传图片并签到")
                }
                .frame(maxWidth: .infinity)
            }
            .buttonStyle(CxPrimaryButtonStyle())
            .disabled(isSigning || isLoadingImage || imageData == nil)

            HStack(spacing: 10) {
                Button {
                    appState.launchSessionTask { await onSubmit(.profileAvatar) }
                } label: {
                    Text("使用底库头像")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(CxSecondaryButtonStyle(compact: true))
                .disabled(isSigning || isLoadingImage)

                if !requiresPhoto {
                Button {
                    appState.launchSessionTask { await onSubmit(.objectId(nil)) }
                } label: {
                    Text("免图直接提交")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(CxSecondaryButtonStyle(compact: true))
                .disabled(isSigning || isLoadingImage)
                }
            }
        }
        .padding(.vertical, 4)
    }

    private func loadPickedImage(_ item: PhotosPickerItem?) async {
        guard let item else { return }
        let request = UUID()
        imageRequest = request
        isLoadingImage = true
        previewImage = nil
        imageData = nil
        defer { if imageRequest == request { isLoadingImage = false } }
        guard let raw = try? await item.loadTransferable(type: Data.self),
              let uiImage = UIImage(data: raw) else {
            if imageRequest == request { appState.errorMessage = "无法读取所选照片，请重新选择" }
            return
        }
        guard imageRequest == request, !Task.isCancelled else { return }
        previewImage = uiImage
        imageData = uiImage.jpegData(compressionQuality: 0.85) ?? raw
    }

}
