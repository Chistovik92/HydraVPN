import SwiftUI

/// Палитра Hydra — те же цвета, что у тем Android (ui/theme/Color.kt): Ambient (изумрудный неон
/// на тёмном титане), Stealth (монохром с багровым ядром), AMOLED (чисто чёрный фон).
enum HydraTheme: String, CaseIterable, Identifiable {
    case ambient = "AMBIENT", stealth = "STEALTH", amoled = "AMOLED"
    var id: String { rawValue }

    var bg: Color {
        switch self {
        case .ambient: Color(red: 0.027, green: 0.039, blue: 0.051)
        case .stealth: Color(red: 0.035, green: 0.035, blue: 0.039)
        case .amoled: .black
        }
    }
    var card: Color {
        switch self {
        case .ambient: Color(red: 0.055, green: 0.094, blue: 0.106).opacity(0.85)
        case .stealth: Color(red: 0.07, green: 0.086, blue: 0.10).opacity(0.85)
        case .amoled: Color(white: 0.06)
        }
    }
    var accent: Color {
        switch self {
        case .ambient, .amoled: Color(red: 0, green: 0.898, blue: 0.6)      // #00E599
        case .stealth: Color(red: 0.86, green: 0.15, blue: 0.24)           // багровое ядро
        }
    }
    var labelKey: String {
        switch self {
        case .ambient: "theme_ambient"
        case .stealth: "theme_stealth"
        case .amoled: "theme_amoled"
        }
    }
}

extension Color {
    static let hydraMuted = Color(white: 0.55)
    static let hydraDanger = Color(red: 0.94, green: 0.33, blue: 0.31)
    static let hydraWarn = Color(red: 0.98, green: 0.72, blue: 0.2)
}
