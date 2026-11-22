// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

import SwiftUI
@preconcurrency import MapKit
import ChaoxingCore

struct IOSLocationSignView: View {
    @Environment(IOSAppState.self) private var appState

    let activity: ChaoxingSignActivity
    let detail: ChaoxingSignDetailMetadata?
    let isSigning: Bool
    let onSubmit: (ChaoxingLocationSignInfo) async -> Void

    @State private var searchQuery = ""
    @State private var address = ""
    @State private var latitudeText = ""
    @State private var longitudeText = ""
    @State private var presetName = ""
    @State private var cameraPosition: MapCameraPosition = .region(
        MKCoordinateRegion(
            center: CLLocationCoordinate2D(latitude: 39.9042, longitude: 116.4074),
            span: MKCoordinateSpan(latitudeDelta: 0.02, longitudeDelta: 0.02)
        )
    )
    @State private var markerCoordinate = CLLocationCoordinate2D(latitude: 39.9042, longitude: 116.4074)
    @State private var isSearching = false
    @State private var searchRequest = UUID()

    private var parsedLocation: ChaoxingLocationSignInfo? {
        guard let lat = Double(latitudeText.trimmingCharacters(in: .whitespaces)),
              let lon = Double(longitudeText.trimmingCharacters(in: .whitespaces)),
              lat.isFinite, lon.isFinite, (-90...90).contains(lat), (-180...180).contains(lon) else {
            return nil
        }
        let addr = address.trimmingCharacters(in: .whitespacesAndNewlines)
        return ChaoxingLocationSignInfo(
            latitude: lat,
            longitude: lon,
            address: addr.isEmpty ? "当前定位" : addr
        )
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            if let target = detail?.targetLocation,
               let tLat = target.latitude, let tLon = target.longitude {
                HStack(spacing: 10) {
                    CxIconTile("scope", tone: .accent, size: 32)
                    VStack(alignment: .leading, spacing: 2) {
                        Text("教师电子围栏 · 半径 \(target.rangeMeters ?? 0)m")
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(CxPalette.ink)
                        Text(String(format: "%.6f, %.6f", tLat, tLon))
                            .font(CxTypography.mono(11))
                            .foregroundStyle(CxPalette.inkSecondary)
                    }
                    Spacer()
                    Button("采用中心点") {
                        applyCoordinates(
                            latitude: tLat,
                            longitude: tLon,
                            addressText: address.isEmpty ? "指定签到范围" : address
                        )
                    }
                    .buttonStyle(CxSecondaryButtonStyle(compact: true))
                }
                .cxWell(padding: 10, cornerRadius: 10)
            }

            if detail?.isFaceRequired == true {
                HStack(spacing: 8) {
                    Image(systemName: "faceid")
                        .foregroundStyle(CxPalette.plum)
                    Text("已开启人脸核验：提交时自动为各账号拉取底库照片。")
                        .font(.caption2)
                        .foregroundStyle(CxPalette.plum)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .cxWell(padding: 9, cornerRadius: 9)
            }

            if let bound = appState.boundLocation(forCourseId: activity.courseId) {
                HStack(spacing: 8) {
                    Image(systemName: "pin.fill")
                        .foregroundStyle(CxPalette.sage)
                    Text("课程绑定教室：\(bound.name)")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(CxPalette.sage)
                    Spacer()
                    Button("解除绑定") {
                        Task { await appState.unbindLocation(forCourseId: activity.courseId) }
                    }
                    .font(.caption.weight(.medium))
                    .foregroundStyle(CxPalette.inkSecondary)
                    .buttonStyle(.borderless)
                }
                .cxWell(padding: 9, cornerRadius: 9)
            }

            if !appState.locationPresets.isEmpty {
                CxFlowLayout(horizontalSpacing: 8, verticalSpacing: 8) {
                    ForEach(appState.locationPresets) { preset in
                        Button {
                            applyCoordinates(
                                latitude: preset.latitude,
                                longitude: preset.longitude,
                                addressText: preset.address
                            )
                        } label: {
                            HStack(spacing: 5) {
                                Image(systemName: "mappin.circle.fill")
                                    .font(.caption)
                                    .foregroundStyle(CxPalette.accent)
                                Text(preset.name)
                                    .font(.caption.weight(.medium))
                                    .foregroundStyle(CxPalette.ink)
                            }
                            .padding(.horizontal, 10)
                            .padding(.vertical, 5.5)
                            .background(CxPalette.surfaceSecondary, in: Capsule())
                            .overlay(Capsule().strokeBorder(CxPalette.border, lineWidth: 0.8))
                        }
                        .buttonStyle(.plain)
                    }
                }
            }

            HStack(spacing: 8) {
                TextField("搜索教学楼 / 校区地标", text: $searchQuery)
                    .cxInputField()
                    .submitLabel(.search)
                    .onSubmit {
                        Task { await searchPlace() }
                    }
                Button {
                    Task { await searchPlace() }
                } label: {
                    if isSearching {
                        ProgressView()
                            .controlSize(.small)
                    } else {
                        Text("检索")
                    }
                }
                .buttonStyle(CxSecondaryButtonStyle())
                .disabled(isSearching || searchQuery.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }

            MapReader { proxy in
                Map(position: $cameraPosition) {
                    Marker(address.isEmpty ? "签到位置" : address, coordinate: markerCoordinate)
                        .tint(CxPalette.accent)

                    if let target = detail?.targetLocation,
                       let tLat = target.latitude, let tLon = target.longitude {
                        MapCircle(
                            center: CLLocationCoordinate2D(latitude: tLat, longitude: tLon),
                            radius: CLLocationDistance(target.rangeMeters ?? 100)
                        )
                        .foregroundStyle(CxPalette.accent.opacity(0.16))
                        .stroke(CxPalette.accent, lineWidth: 1.5)
                    }
                }
                .frame(height: 220)
                .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                .overlay(
                    RoundedRectangle(cornerRadius: 12, style: .continuous)
                        .strokeBorder(CxPalette.border, lineWidth: 1)
                )
                .onTapGesture { point in
                    if let coord = proxy.convert(point, from: .local) {
                        applyCoordinates(
                            latitude: coord.latitude,
                            longitude: coord.longitude,
                            addressText: address.isEmpty ? "地图选点" : address
                        )
                    }
                }
            }

            HStack {
                Text("轻点地图可精确拾取坐标")
                    .font(.caption2)
                    .foregroundStyle(CxPalette.inkTertiary)
                Spacer()
                if !latitudeText.isEmpty && !longitudeText.isEmpty {
                    Text("\(latitudeText), \(longitudeText)")
                        .font(CxTypography.mono(11))
                        .foregroundStyle(CxPalette.inkSecondary)
                }
            }

            TextField("签到显示地址（如：第六教学楼 302）", text: $address)
                .cxInputField()

            HStack(spacing: 8) {
                HStack(spacing: 6) {
                    Text("纬度")
                        .font(CxTypography.mono(10.5, weight: .semibold))
                        .foregroundStyle(CxPalette.inkTertiary)
                    TextField("纬度", text: $latitudeText)
                        .font(CxTypography.mono(13))
                        .keyboardType(.decimalPad)
                }
                .cxInputField()

                HStack(spacing: 6) {
                    Text("经度")
                        .font(CxTypography.mono(10.5, weight: .semibold))
                        .foregroundStyle(CxPalette.inkTertiary)
                    TextField("经度", text: $longitudeText)
                        .font(CxTypography.mono(13))
                        .keyboardType(.decimalPad)
                }
                .cxInputField()
            }

            HStack(spacing: 8) {
                TextField("常用地点备注名（可选）", text: $presetName)
                    .cxInputField()
                Button("存为预设") {
                    guard let loc = parsedLocation else { return }
                    let name = presetName.trimmingCharacters(in: .whitespacesAndNewlines)
                    Task {
                        await appState.addLocationPreset(
                            name: name.isEmpty ? loc.address : name,
                            address: loc.address,
                            latitude: loc.latitude,
                            longitude: loc.longitude
                        )
                        presetName = ""
                    }
                }
                .buttonStyle(CxSecondaryButtonStyle())
                .disabled(parsedLocation == nil)
            }

            if activity.courseId != 0 {
                Button {
                    guard let loc = parsedLocation else { return }
                    let name = presetName.trimmingCharacters(in: .whitespacesAndNewlines)
                    let preset = ChaoxingLocationPreset(
                        name: name.isEmpty ? loc.address : name,
                        address: loc.address,
                        latitude: loc.latitude,
                        longitude: loc.longitude
                    )
                    Task { await appState.bindLocation(preset, toCourseId: activity.courseId) }
                } label: {
                    Label("设为《\(activity.courseName.isEmpty ? "本课程" : activity.courseName)》默认教室", systemImage: "pin")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(CxSecondaryButtonStyle())
                .disabled(parsedLocation == nil)
            }

            Button {
                guard let loc = parsedLocation else { return }
                appState.launchSessionTask { await onSubmit(loc) }
            } label: {
                HStack(spacing: 6) {
                    Image(systemName: "location.fill")
                    Text("提交位置签到")
                }
                .frame(maxWidth: .infinity)
            }
            .buttonStyle(CxPrimaryButtonStyle())
            .disabled(isSigning || parsedLocation == nil)
        }
        .padding(.vertical, 4)
        .onAppear {
            if latitudeText.isEmpty {
                if let bound = appState.boundLocation(forCourseId: activity.courseId) {
                    applyCoordinates(
                        latitude: bound.latitude,
                        longitude: bound.longitude,
                        addressText: bound.address
                    )
                } else if let target = detail?.targetLocation,
                          let tLat = target.latitude, let tLon = target.longitude {
                    applyCoordinates(
                        latitude: tLat,
                        longitude: tLon,
                        addressText: "教师指定位置"
                    )
                } else if let first = appState.locationPresets.first {
                    applyCoordinates(
                        latitude: first.latitude,
                        longitude: first.longitude,
                        addressText: first.address
                    )
                }
            }
        }
    }

    private func applyCoordinates(latitude: Double, longitude: Double, addressText: String) {
        let coord = CLLocationCoordinate2D(latitude: latitude, longitude: longitude)
        markerCoordinate = coord
        cameraPosition = .region(
            MKCoordinateRegion(
                center: coord,
                span: MKCoordinateSpan(latitudeDelta: 0.008, longitudeDelta: 0.008)
            )
        )
        latitudeText = String(format: "%.6f", latitude)
        longitudeText = String(format: "%.6f", longitude)
        address = addressText
    }

    private func searchPlace() async {
        let request = UUID()
        searchRequest = request
        let query = searchQuery
        let token = appState.sessionToken
        isSearching = true
        defer { if searchRequest == request { isSearching = false } }
        if let result = await IOSMapSearchHelper.searchFirstPlace(query: query) {
            guard searchRequest == request, query == searchQuery, appState.isCurrent(token), !Task.isCancelled else { return }
            applyCoordinates(
                latitude: result.coordinate.latitude,
                longitude: result.coordinate.longitude,
                addressText: result.address
            )
        } else {
            guard searchRequest == request, query == searchQuery, appState.isCurrent(token), !Task.isCancelled else { return }
            appState.errorMessage = "未找到匹配地点，请换个关键词或直接输入经纬度"
        }
    }
}
