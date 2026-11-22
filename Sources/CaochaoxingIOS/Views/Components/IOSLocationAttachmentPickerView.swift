// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

import SwiftUI
import ChaoxingCore

struct IOSLocationAttachmentPickerView: View {
    @Environment(IOSAppState.self) private var appState
    let courseId: Int64
    let isPositionRequired: Bool
    @Binding var includeLocation: Bool
    @Binding var selectedPresetId: UUID?
    @State private var showLocationManager = false

    private var boundPreset: ChaoxingLocationPreset? {
        appState.boundLocation(forCourseId: courseId)
    }

    private var availablePresets: [ChaoxingLocationPreset] {
        let bound = boundPreset.map { [$0] } ?? []
        return bound + appState.locationPresets.filter { $0.id != boundPreset?.id }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Toggle(isPositionRequired ? "此活动需要附带位置" : "附带位置", isOn: $includeLocation)
                .disabled(isPositionRequired)
            if includeLocation && !availablePresets.isEmpty {
                Picker("地点", selection: $selectedPresetId) {
                    ForEach(availablePresets) { preset in
                        Text(preset.id == boundPreset?.id ? "\(preset.name)（课程默认）" : preset.name)
                            .tag(Optional(preset.id))
                    }
                }
                .pickerStyle(.menu)
            }
            if includeLocation && availablePresets.isEmpty {
                Label("请先添加一个地点，再提交。", systemImage: "mappin.and.ellipse")
                    .font(.footnote)
                    .foregroundStyle(CxPalette.inkSecondary)
            }
            Button("管理地点", systemImage: "mappin.and.ellipse") { showLocationManager = true }
                .buttonStyle(CxSecondaryButtonStyle(compact: true))
        }
        .font(.subheadline)
        .foregroundStyle(CxPalette.ink)
        .onAppear {
            if isPositionRequired || boundPreset != nil { includeLocation = true }
            reconcileSelection()
        }
        .onChange(of: isPositionRequired) { _, required in
            if required { includeLocation = true }
            reconcileSelection()
        }
        .onChange(of: availablePresets.map(\.id)) { _, _ in reconcileSelection() }
        .onChange(of: courseId) { _, _ in
            selectedPresetId = availablePresets.first?.id
            includeLocation = isPositionRequired || boundPreset != nil
        }
        .sheet(isPresented: $showLocationManager) { IOSLocationPresetsView() }
    }

    private func reconcileSelection() {
        if !availablePresets.contains(where: { $0.id == selectedPresetId }) {
            selectedPresetId = availablePresets.first?.id
        }
    }
}

/// Shared location management is accessible without a location-sign activity.
struct IOSLocationPresetsView: View {
    @Environment(IOSAppState.self) private var appState
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var address = ""
    @State private var latitude = ""
    @State private var longitude = ""
    @State private var query = ""
    @State private var isSearching = false
    @State private var isSaving = false
    @State private var requestID = UUID()
    @State private var searchTask: Task<Void, Never>?
    @State private var message: String?

    private var coordinates: (Double, Double)? {
        guard let lat = Double(latitude), let lon = Double(longitude),
              lat.isFinite, lon.isFinite, (-90...90).contains(lat), (-180...180).contains(lon) else { return nil }
        return (lat, lon)
    }

    var body: some View {
        NavigationStack {
            Form {
                if !appState.locationPresets.isEmpty {
                    Section("已保存地点") {
                        ForEach(appState.locationPresets) { preset in
                            VStack(alignment: .leading, spacing: 4) {
                                Text(preset.name).font(.headline)
                                Text(preset.address).font(.subheadline).foregroundStyle(.secondary)
                            }
                            .swipeActions {
                                Button("删除", role: .destructive) {
                                    Task { await appState.removeLocationPreset(preset) }
                                }
                            }
                        }
                    }
                }
                Section {
                    TextField("搜索学校、教室或地点", text: $query)
                        .submitLabel(.search)
                        .onSubmit { search() }
                    Button(action: search) {
                        HStack {
                            Text(isSearching ? "正在搜索…" : "搜索地点")
                            if isSearching { ProgressView() }
                        }
                    }
                    .disabled(query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || isSearching)
                } footer: {
                    Text("搜索会填入首个匹配结果。请核对名称、地址和坐标后保存。")
                }
                Section("添加地点") {
                    TextField("名称", text: $name)
                    TextField("地址", text: $address)
                    TextField("纬度（−90 至 90）", text: $latitude)
                        .keyboardType(.numbersAndPunctuation)
                    TextField("经度（−180 至 180）", text: $longitude)
                        .keyboardType(.numbersAndPunctuation)
                    Button(isSaving ? "正在保存…" : "保存地点") { save() }
                        .disabled(coordinates == nil || name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || address.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || isSaving)
                }
                if let message {
                    Section { Text(message).font(.footnote).foregroundStyle(.secondary) }
                }
            }
            .cxEditorialList()
            .navigationTitle("地点")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("完成") { dismiss() } }
            }
            .onDisappear { searchTask?.cancel(); requestID = UUID() }
        }
    }

    private func search() {
        let query = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !query.isEmpty else { return }
        let id = UUID()
        requestID = id
        searchTask?.cancel()
        isSearching = true
        message = nil
        searchTask = Task {
            let result = await IOSMapSearchHelper.searchFirstPlace(query: query)
            guard !Task.isCancelled, requestID == id else { return }
            isSearching = false
            guard let result else { message = "未找到地点，请调整关键词或手动填写坐标。"; return }
            name = result.name
            address = result.address
            latitude = String(format: "%.6f", result.coordinate.latitude)
            longitude = String(format: "%.6f", result.coordinate.longitude)
        }
    }

    private func save() {
        guard let (lat, lon) = coordinates, !isSaving else { return }
        isSaving = true
        Task {
            defer { isSaving = false }
            if await appState.addLocationPreset(name: name, address: address, latitude: lat, longitude: lon) {
                name = ""; address = ""; latitude = ""; longitude = ""
                message = "地点已保存，可以返回选择。"
            } else {
                message = appState.errorMessage ?? "保存失败，请重试。"
            }
        }
    }
}
