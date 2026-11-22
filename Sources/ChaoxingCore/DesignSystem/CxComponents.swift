// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

import SwiftUI

/// Semantic mineral tone for badges, banners, and status pills.
public enum CxTone: Sendable {
    case accent
    case sage
    case amber
    case cinnabar
    case plum
    case neutral

    public var foreground: Color {
        switch self {
        case .accent: return CxPalette.accent
        case .sage: return CxPalette.sage
        case .amber: return CxPalette.amber
        case .cinnabar: return CxPalette.cinnabar
        case .plum: return CxPalette.plum
        case .neutral: return CxPalette.inkSecondary
        }
    }

    public var softFill: Color {
        switch self {
        case .neutral:
            return CxPalette.surfaceSecondary
        default:
            return foreground.opacity(0.13)
        }
    }

    public var hairline: Color {
        switch self {
        case .neutral:
            return CxPalette.border
        default:
            return foreground.opacity(0.28)
        }
    }
}

/// Compact status label with text as well as color.
public struct CxBadge: View {
    public let text: String
    public let tone: CxTone
    public let icon: String?
    public let showDot: Bool
    public let monospaced: Bool

    public init(
        _ text: String,
        tone: CxTone = .neutral,
        icon: String? = nil,
        showDot: Bool = false,
        monospaced: Bool = false
    ) {
        self.text = text
        self.tone = tone
        self.icon = icon
        self.showDot = showDot
        self.monospaced = monospaced
    }

    public var body: some View {
        HStack(spacing: 4.5) {
            if showDot {
                Circle()
                    .fill(tone.foreground)
                    .frame(width: 5.5, height: 5.5)
            } else if let icon {
                Image(systemName: icon)
                    .font(.caption.weight(.semibold))
            }
            Text(text)
                .font(monospaced ? CxTypography.mono(11, weight: .semibold) : .caption.weight(.medium))
                .fixedSize(horizontal: false, vertical: true)
        }
        .foregroundStyle(tone.foreground)
        .padding(.horizontal, 8)
        .padding(.vertical, 3.5)
        .background(tone.softFill, in: RoundedRectangle(cornerRadius: 6, style: .continuous))
    }
}

/// A quiet, scalable section label.
public struct CxSectionHeader: View {
    public let title: String
    public let trailing: String?

    public init(_ title: String, trailing: String? = nil) {
        self.title = title
        self.trailing = trailing
    }

    public var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 6) {
            Text(title)
                .font(.subheadline.weight(.medium))
                .foregroundStyle(CxPalette.inkSecondary)
                .textCase(nil)
            Spacer(minLength: 8)
            if let trailing {
                Text(trailing)
                    .font(CxTypography.mono(11))
                    .foregroundStyle(CxPalette.inkTertiary)
                    .textCase(nil)
            }
        }
    }
}

/// A small semantic icon.
public struct CxIconTile: View {
    public let systemName: String
    public let tone: CxTone
    public let size: CGFloat

    public init(_ systemName: String, tone: CxTone = .accent, size: CGFloat = 34) {
        self.systemName = systemName
        self.tone = tone
        self.size = size
    }

    public var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: size * 0.26, style: .continuous)
                .fill(tone.softFill)
                .frame(width: size, height: size)
                .overlay(
                    RoundedRectangle(cornerRadius: size * 0.26, style: .continuous)
                        .strokeBorder(tone.hairline, lineWidth: 0.8)
                )
            Image(systemName: systemName)
                .font(.system(size: size * 0.44, weight: .medium))
                .foregroundStyle(tone.foreground)
        }
    }
}

/// Accessible primary action with a minimum touch height.
public struct CxPrimaryButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var isEnabled

    public init() {}

    public func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.headline)
            .foregroundStyle(isEnabled ? CxPalette.onAccent : CxPalette.inkTertiary)
            .padding(.horizontal, 16)
            .padding(.vertical, 11)
            .frame(minHeight: 44)
            .background(isEnabled ? CxPalette.accent : CxPalette.surfaceSecondary,
                        in: RoundedRectangle(cornerRadius: 10))
            .opacity(configuration.isPressed ? 0.8 : 1)
    }
}

/// Secondary action with a minimum touch height.
public struct CxSecondaryButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var isEnabled
    public let compact: Bool

    public init(compact: Bool = false) { self.compact = compact }

    public func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.subheadline.weight(.medium))
            .foregroundStyle(isEnabled ? CxPalette.accent : CxPalette.inkTertiary)
            .padding(.horizontal, compact ? 10 : 14)
            .padding(.vertical, 8)
            .frame(minHeight: 44)
            .background(CxPalette.surfaceSecondary, in: RoundedRectangle(cornerRadius: 10))
            .opacity(configuration.isPressed ? 0.7 : 1)
    }
}

/// True multi-row wrapping layout for chips and tags.
public struct CxFlowLayout: Layout {
    public var horizontalSpacing: CGFloat
    public var verticalSpacing: CGFloat

    public init(horizontalSpacing: CGFloat = 8, verticalSpacing: CGFloat = 8) {
        self.horizontalSpacing = horizontalSpacing
        self.verticalSpacing = verticalSpacing
    }

    public func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let maxWidth = proposal.width ?? .infinity
        var currentX: CGFloat = 0
        var currentY: CGFloat = 0
        var rowHeight: CGFloat = 0
        var usedWidth: CGFloat = 0

        for subview in subviews {
            let size = subview.sizeThatFits(ProposedViewSize(width: maxWidth.isFinite ? maxWidth : nil, height: nil))
            if currentX > 0 && currentX + size.width > maxWidth {
                currentX = 0
                currentY += rowHeight + verticalSpacing
                rowHeight = 0
            }
            usedWidth = max(usedWidth, currentX + size.width)
            currentX += size.width + horizontalSpacing
            rowHeight = max(rowHeight, size.height)
        }

        return CGSize(width: proposal.width ?? usedWidth, height: currentY + rowHeight)
    }

    public func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var currentX = bounds.minX
        var currentY = bounds.minY
        var rowHeight: CGFloat = 0

        for subview in subviews {
            let size = subview.sizeThatFits(ProposedViewSize(width: bounds.width, height: nil))
            if currentX > bounds.minX && currentX + size.width > bounds.maxX {
                currentX = bounds.minX
                currentY += rowHeight + verticalSpacing
                rowHeight = 0
            }
            subview.place(
                at: CGPoint(x: currentX, y: currentY),
                proposal: ProposedViewSize(width: size.width, height: size.height)
            )
            currentX += size.width + horizontalSpacing
            rowHeight = max(rowHeight, size.height)
        }
    }
}

extension View {
    /// Plain grouped surface.
    public func cxCard(padding: CGFloat = 16, cornerRadius: CGFloat = 14) -> some View {
        self
            .padding(padding)
            .background(CxPalette.surface, in: RoundedRectangle(cornerRadius: cornerRadius, style: .continuous))
    }

    /// Secondary surface for related details.
    public func cxWell(padding: CGFloat = 10, cornerRadius: CGFloat = 10) -> some View {
        self
            .padding(padding)
            .background(CxPalette.surfaceSecondary, in: RoundedRectangle(cornerRadius: cornerRadius, style: .continuous))
    }

    /// Standard field container.
    public func cxInputField() -> some View {
        self
            .textFieldStyle(.plain)
            .padding(.horizontal, 12)
            .padding(.vertical, 9.5)
            .background(CxPalette.surface, in: RoundedRectangle(cornerRadius: 9, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 9, style: .continuous)
                    .strokeBorder(CxPalette.border, lineWidth: 1)
            )
    }

    /// Applies the grouped page background.
    public func cxEditorialList() -> some View {
        self
            .scrollContentBackground(.hidden)
            .background(CxPalette.canvas.ignoresSafeArea())
    }

    /// Consistent grouped list row.
    public func cxCardRow() -> some View {
        self
            .listRowBackground(CxPalette.surface)
            .listRowSeparatorTint(CxPalette.border)
    }
}
