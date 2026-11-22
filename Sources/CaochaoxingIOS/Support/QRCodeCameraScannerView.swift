// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

import SwiftUI
@preconcurrency import AVFoundation
import ChaoxingCore

struct QRCodeCameraScannerView: View {
    let onScanned: (String) -> Void
    @Environment(\.scenePhase) private var scenePhase
    @State private var cameraError: String?
    @State private var cameraSessionID = UUID()

    var body: some View {
        ZStack {
            QRCodeCameraPreviewRepresentable(onScanned: onScanned, onError: { cameraError = $0 })
                .id(cameraSessionID)
                .ignoresSafeArea()

            if let cameraError {
                VStack(spacing: 16) {
                    Image(systemName: "camera.fill").font(.largeTitle)
                    Text(cameraError).multilineTextAlignment(.center)
                    Button("打开系统设置") {
                        if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
                    }
                    .buttonStyle(.borderedProminent)
                }
                .foregroundStyle(.white)
                .padding(28)
                .background(.black.opacity(0.8), in: RoundedRectangle(cornerRadius: 16))
                .padding()
            } else {
            VStack(spacing: 24) {
                ZStack {
                    RoundedRectangle(cornerRadius: 24, style: .continuous)
                        .strokeBorder(Color.white.opacity(0.22), lineWidth: 1)
                        .frame(width: 240, height: 240)

                    ViewfinderCornersShape(cornerLength: 26, cornerRadius: 24)
                        .stroke(
                            CxPalette.accent,
                            style: StrokeStyle(lineWidth: 3.5, lineCap: .round, lineJoin: .round)
                        )
                        .frame(width: 240, height: 240)
                }

                HStack(spacing: 7) {
                    Circle()
                        .fill(CxPalette.accent)
                        .frame(width: 6, height: 6)
                    Text("将二维码置于取景框内自动识别")
                        .font(CxTypography.mono(12))
                        .foregroundStyle(CxPalette.onAccent)
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 8)
                .background(Color.black.opacity(0.62), in: Capsule())
                .overlay(Capsule().strokeBorder(Color.white.opacity(0.16), lineWidth: 0.8))
            }
            }
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active, cameraError != nil {
                cameraError = nil
                cameraSessionID = UUID()
            }
        }
    }
}


private struct ViewfinderCornersShape: Shape {
    let cornerLength: CGFloat
    let cornerRadius: CGFloat

    func path(in rect: CGRect) -> Path {
        var p = Path()
        let r = cornerRadius
        let l = cornerLength

        // Top-left
        p.move(to: CGPoint(x: rect.minX, y: rect.minY + l))
        p.addLine(to: CGPoint(x: rect.minX, y: rect.minY + r))
        p.addQuadCurve(to: CGPoint(x: rect.minX + r, y: rect.minY), control: CGPoint(x: rect.minX, y: rect.minY))
        p.addLine(to: CGPoint(x: rect.minX + l, y: rect.minY))

        // Top-right
        p.move(to: CGPoint(x: rect.maxX - l, y: rect.minY))
        p.addLine(to: CGPoint(x: rect.maxX - r, y: rect.minY))
        p.addQuadCurve(to: CGPoint(x: rect.maxX, y: rect.minY + r), control: CGPoint(x: rect.maxX, y: rect.minY))
        p.addLine(to: CGPoint(x: rect.maxX, y: rect.minY + l))

        // Bottom-right
        p.move(to: CGPoint(x: rect.maxX, y: rect.maxY - l))
        p.addLine(to: CGPoint(x: rect.maxX, y: rect.maxY - r))
        p.addQuadCurve(to: CGPoint(x: rect.maxX - r, y: rect.maxY), control: CGPoint(x: rect.maxX, y: rect.maxY))
        p.addLine(to: CGPoint(x: rect.maxX - l, y: rect.maxY))

        // Bottom-left
        p.move(to: CGPoint(x: rect.minX + l, y: rect.maxY))
        p.addLine(to: CGPoint(x: rect.minX + r, y: rect.maxY))
        p.addQuadCurve(to: CGPoint(x: rect.minX, y: rect.maxY - r), control: CGPoint(x: rect.minX, y: rect.maxY))
        p.addLine(to: CGPoint(x: rect.minX, y: rect.maxY - l))

        return p
    }
}

private struct QRCodeCameraPreviewRepresentable: UIViewControllerRepresentable {
    let onScanned: (String) -> Void
    let onError: (String) -> Void

    func makeUIViewController(context: Context) -> ScannerViewController {
        let controller = ScannerViewController()
        controller.onScanned = onScanned
        controller.onError = onError
        return controller
    }

    func updateUIViewController(_ controller: ScannerViewController, context: Context) {
        controller.onScanned = onScanned
        controller.onError = onError
    }

    static func dismantleUIViewController(_ controller: ScannerViewController, coordinator: ()) {
        controller.tearDown()
    }

    final class ScannerViewController: UIViewController, AVCaptureMetadataOutputObjectsDelegate {
        var onScanned: ((String) -> Void)?
        var onError: ((String) -> Void)?
        private let camera = SerializedCameraSession()
        private var previewLayer: AVCaptureVideoPreviewLayer?
        private var didEmit = false
        private var isVisible = false
        private var appearanceID = UUID()

        override func viewDidLoad() {
            super.viewDidLoad()
            view.backgroundColor = .black
            let preview = AVCaptureVideoPreviewLayer(session: camera.session)
            preview.frame = view.bounds
            preview.videoGravity = .resizeAspectFill
            view.layer.addSublayer(preview)
            previewLayer = preview
        }

        override func viewDidLayoutSubviews() {
            super.viewDidLayoutSubviews()
            previewLayer?.frame = view.bounds
        }

        override func viewWillAppear(_ animated: Bool) {
            super.viewWillAppear(animated)
            isVisible = true
            didEmit = false
            appearanceID = UUID()
            let request = appearanceID
            switch AVCaptureDevice.authorizationStatus(for: .video) {
            case .authorized: startCamera(for: request)
            case .notDetermined:
                AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
                    Task { @MainActor in
                        guard let self, self.isVisible, self.appearanceID == request else { return }
                        if granted { self.startCamera(for: request) }
                        else { self.onError?("相机权限未开启。请在系统设置中允许访问相机，或关闭后改用相册识码。") }
                    }
                }
            case .denied, .restricted:
                onError?("无法访问相机。请检查系统设置中的相机权限，或关闭后改用相册识码。")
            @unknown default: onError?("当前设备无法使用相机，请改用相册或粘贴二维码。")
            }
        }

        private func startCamera(for request: UUID) {
            guard isVisible, appearanceID == request else { return }
            camera.start(delegate: self) { [weak self] message in
                guard let self, self.isVisible, self.appearanceID == request else { return }
                self.onError?(message)
            }
        }

        override func viewWillDisappear(_ animated: Bool) {
            super.viewWillDisappear(animated)
            tearDown()
        }

        func tearDown() {
            isVisible = false
            appearanceID = UUID()
            camera.stop()
        }

        nonisolated func metadataOutput(_ output: AVCaptureMetadataOutput, didOutput metadataObjects: [AVMetadataObject], from connection: AVCaptureConnection) {
            guard let readable = metadataObjects.first as? AVMetadataMachineReadableCodeObject,
                  let value = readable.stringValue else { return }
            Task { @MainActor in
                guard self.isVisible, !self.didEmit else { return }
                self.didEmit = true
                self.camera.stop()
                self.onScanned?(value)
            }
        }
    }
}

/// Only this serial queue configures or starts/stops the capture session.
/// The unchecked conformance is confined to AVFoundation's queue-bound session ownership.
private final class SerializedCameraSession: @unchecked Sendable {
    let session = AVCaptureSession()
    private let queue = DispatchQueue(label: "Caochaoxing.camera.session", qos: .userInitiated)
    private var configured = false

    func start(delegate: QRCodeCameraPreviewRepresentable.ScannerViewController, onError: @escaping @MainActor @Sendable (String) -> Void) {
        queue.async { [self] in
            do {
                if !configured {
                    guard let device = AVCaptureDevice.default(for: .video) else {
                        throw ChaoxingError.invalidInput("未找到可用相机，请改用相册或粘贴二维码。")
                    }
                    let input = try AVCaptureDeviceInput(device: device)
                    let output = AVCaptureMetadataOutput()
                    session.beginConfiguration()
                    defer { session.commitConfiguration() }
                    guard session.canAddInput(input), session.canAddOutput(output) else {
                        throw ChaoxingError.invalidInput("相机暂不可用，请关闭后重试或使用相册识码。")
                    }
                    session.addInput(input)
                    session.addOutput(output)
                    output.setMetadataObjectsDelegate(delegate, queue: .main)
                    output.metadataObjectTypes = [.qr]
                    configured = true
                }
                if !session.isRunning { session.startRunning() }
            } catch {
                let message = error.localizedDescription
                Task { @MainActor in onError(message) }
            }
        }
    }

    func stop() {
        queue.async { [self] in
            if session.isRunning { session.stopRunning() }
        }
    }
}
