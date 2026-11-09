import SwiftUI

struct RootView: View {
    @AppStorage("firecoins.activeRole") private var activeRoleRaw = ""
    @EnvironmentObject private var store: AppStore

    private var activeRole: AppRole? { AppRole(rawValue: activeRoleRaw) }

    var body: some View {
        Group {
            if let activeRole {
                MainTabView(role: activeRole) {
                    activeRoleRaw = ""
                }
                .transition(.opacity)
            } else {
                InvitationView { role in
                    withAnimation(.easeInOut(duration: 0.25)) {
                        activeRoleRaw = role.rawValue
                    }
                }
                .transition(.opacity)
            }
        }
        .background(FCTheme.background)
        .animation(.easeInOut(duration: 0.2), value: activeRoleRaw)
    }
}

enum MainTab: Hashable {
    case home, history, casino, wallet, profile
}

struct MainTabView: View {
    let role: AppRole
    let onLogout: () -> Void
    @State private var selection: MainTab = .home

    var body: some View {
        TabView(selection: $selection) {
            NavigationStack { HomeView(role: role, selectedTab: $selection) }
                .tabItem { Label("Главная", systemImage: "square.grid.2x2") }
                .tag(MainTab.home)
            NavigationStack { HistoryView(role: role) }
                .tabItem { Label("История", systemImage: "clock.arrow.circlepath") }
                .tag(MainTab.history)
            if role == .artemiy {
                NavigationStack { CasinoView() }
                    .tabItem { Label("Рулетка", systemImage: "die.face.5") }
                    .tag(MainTab.casino)
            } else {
                NavigationStack { AdminPanelView() }
                    .tabItem { Label("Управление", systemImage: "slider.horizontal.3") }
                    .tag(MainTab.casino)
            }
            NavigationStack { WalletView(role: role) }
                .tabItem { Label("Кошелёк", systemImage: "creditcard") }
                .tag(MainTab.wallet)
            NavigationStack { ProfileView(role: role, onLogout: onLogout) }
                .tabItem { Label("Профиль", systemImage: "person.crop.circle") }
                .tag(MainTab.profile)
        }
        .tint(FCTheme.accent)
        .toolbarBackground(FCTheme.surface, for: .tabBar)
        .toolbarBackground(.visible, for: .tabBar)
        .toolbarColorScheme(.dark, for: .tabBar)
    }
}
