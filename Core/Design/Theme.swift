import SwiftUI

// Два локальных приглашения для прототипа. Перед релизом проверку нужно перенести на сервер.
enum InviteCodeConfiguration {
    static let artemiyCode = "FC-7KQ9-MX4P-2VND"
    static let fatherCode = "FC-3X9M-L2CW-8RHT"

    static func role(for input: String) -> AppRole? {
        let normalized = input.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        switch normalized {
        case artemiyCode: return .artemiy
        case fatherCode: return .father
        default: return nil
        }
    }
}

enum FCTheme {
    static let background = Color(hex: 0x0C0E11)
    static let surface = Color(hex: 0x15191E)
    static let surfaceElevated = Color(hex: 0x1C2229)
    static let border = Color(hex: 0x292F36)
    static let accent = Color(hex: 0xD5A16D)
    static let accentLight = Color(hex: 0xE8BE91)
    static let text = Color(hex: 0xF3F0EB)
    static let muted = Color(hex: 0x9299A2)
    static let positive = Color(hex: 0x8FBE9A)
    static let negative = Color(hex: 0xD97D72)
    static let blue = Color(hex: 0x8CA8C8)
}

extension Color {
    init(hex: UInt, opacity: Double = 1) {
        self.init(
            .sRGB,
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255,
            opacity: opacity
        )
    }
}

extension View {
    func fcCard(padding: CGFloat = 18) -> some View {
        self
            .padding(padding)
            .background(FCTheme.surface, in: RoundedRectangle(cornerRadius: 22, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: 22, style: .continuous)
                    .stroke(FCTheme.border.opacity(0.8), lineWidth: 1)
            }
    }
}
