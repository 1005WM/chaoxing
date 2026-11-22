// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

import SwiftUI
import ChaoxingCore

struct IOSAccountGroupSelectorView: View {
    @Environment(IOSAppState.self) private var appState
    @Binding var selectedPhones: Set<String>

    private var selectedCount: Int {
        appState.accounts.filter { selectedPhones.contains($0.phoneNumber) }.count
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            // 群模式快速分组芯片栏（自动流式换行）
            CxFlowLayout(horizontalSpacing: 8, verticalSpacing: 8) {
                filterChip(
                    title: "全部 (\(appState.accounts.count))",
                    icon: "checkmark.circle",
                    isSelected: selectedCount == appState.accounts.count
                ) {
                    selectedPhones = Set(appState.accounts.map(\.phoneNumber))
                }

                if let primary = appState.activeAccount?.phoneNumber {
                    let isOnlyPrimary = selectedPhones == Set([primary])
                    filterChip(
                        title: "仅主视角",
                        icon: "person",
                        isSelected: isOnlyPrimary
                    ) {
                        selectedPhones = Set([primary])
                    }
                }

                ForEach(appState.allAccountTags, id: \.self) { tag in
                    let groupMembers = Set(appState.accounts.filter { $0.tags.contains(tag) }.map(\.phoneNumber))
                    let isExactGroup = !groupMembers.isEmpty && selectedPhones == groupMembers
                    filterChip(
                        title: "\(tag) (\(groupMembers.count))",
                        icon: "tag.fill",
                        isSelected: isExactGroup,
                        isTag: true
                    ) {
                        selectedPhones = groupMembers
                    }
                }
            }

            Divider()
                .overlay(CxPalette.border)

            ForEach(appState.accounts) { account in
                let isSelected = selectedPhones.contains(account.phoneNumber)
                Button {
                    if isSelected {
                        selectedPhones.remove(account.phoneNumber)
                    } else {
                        selectedPhones.insert(account.phoneNumber)
                    }
                } label: {
                    HStack(spacing: 10) {
                        Image(systemName: isSelected ? "checkmark.square.fill" : "square")
                            .font(.title3.weight(.medium))
                            .foregroundStyle(isSelected ? CxPalette.accent : CxPalette.inkTertiary)

                        VStack(alignment: .leading, spacing: 3) {
                            HStack(spacing: 6) {
                                Text(account.name.isEmpty ? account.phoneNumber : account.name)
                                    .font(.subheadline.weight(.semibold))
                                    .foregroundStyle(CxPalette.ink)
                                if account.phoneNumber == appState.activeAccount?.phoneNumber {
                                    CxBadge("当前账号", tone: .accent)
                                }
                            }
                            if !account.name.isEmpty {
                                Text(account.phoneNumber)
                                    .font(.caption)
                                    .foregroundStyle(CxPalette.inkSecondary)
                            }
                            if !account.tags.isEmpty {
                                Text(account.tags.joined(separator: " · "))
                                    .font(.caption2)
                                    .foregroundStyle(CxPalette.amber)
                            }
                        }

                        Spacer()
                    }
                    .padding(.vertical, 6)
                    .frame(minHeight: 44)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(account.name.isEmpty ? account.phoneNumber : "\(account.name)，\(account.phoneNumber)")
                .accessibilityValue(isSelected ? "已选择" : "未选择")
                .accessibilityAddTraits(isSelected ? .isSelected : [])
            }
        }
    }

    @ViewBuilder
    private func filterChip(
        title: String,
        icon: String,
        isSelected: Bool,
        isTag: Bool = false,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            HStack(spacing: 5) {
                Image(systemName: icon)
                    .font(.caption.weight(.semibold))
                Text(title)
                    .font(.subheadline.weight(.medium))
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 8)
            .frame(minHeight: 44)
            .foregroundStyle(
                isSelected
                    ? CxPalette.onAccent
                    : (isTag ? CxPalette.amber : CxPalette.ink)
            )
            .background(
                isSelected
                    ? CxPalette.accent
                    : (isTag ? CxPalette.amber.opacity(0.12) : CxPalette.surfaceSecondary),
                in: Capsule()
            )
            .overlay(
                Capsule()
                    .strokeBorder(
                        isSelected
                            ? Color.clear
                            : (isTag ? CxPalette.amber.opacity(0.3) : CxPalette.border),
                        lineWidth: 0.8
                    )
            )
        }
        .buttonStyle(.plain)
        .accessibilityValue(isSelected ? "已选择" : "未选择")
        .accessibilityAddTraits(isSelected ? .isSelected : [])
    }
}
