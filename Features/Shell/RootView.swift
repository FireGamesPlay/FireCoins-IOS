import SwiftUI

struct RootView: View {
    @EnvironmentObject private var store: AppStore
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        Group {
            if store.isRestoringSession {
                ZStack {
                    FCTheme.background.ignoresSafeArea()
                    ProgressView()
                        .tint(FCTheme.accent)
                        .scaleEffect(1.1)
                }
            } else if let activeRole = store.activeRole {
                VStack(spacing: 0) {
                    if let sessionError = store.sessionError {
                        HStack(spacing: 10) {
                            Image(systemName: "wifi.exclamationmark")
                                .foregroundStyle(FCTheme.accent)
                            Text(sessionError)
                                .font(.system(size: 11, weight: .medium))
                                .foregroundStyle(FCTheme.text)
                                .frame(maxWidth: .infinity, alignment: .leading)
                            Button {
                                Task { try? await store.refreshRemote() }
                            } label: {
                                Image(systemName: "arrow.clockwise")
                                    .font(.system(size: 13, weight: .semibold))
                                    .foregroundStyle(FCTheme.accent)
                            }
                            .accessibilityLabel("Повторить подключение")
                        }
                        .padding(.horizontal, 16)
                        .padding(.vertical, 10)
                        .background(FCTheme.surfaceElevated)
                    }

                    MainTabView(role: activeRole) {
                        Task { await store.signOut() }
                    }
                }
                .transition(.opacity)
            } else if let sessionError = store.sessionError {
                SessionRecoveryView(message: sessionError) {
                    Task { await store.restoreSession() }
                }
                .transition(.opacity)
            } else {
                InvitationView { invitationCode in
                    try await store.signIn(invitationCode: invitationCode)
                }
                .transition(.opacity)
            }
        }
        .background(FCTheme.background)
        .animation(.easeInOut(duration: 0.2), value: store.activeRole)
        .task {
            await store.restoreSession()
        }
        .onChange(of: scenePhase) { _, phase in
            guard phase == .active, store.activeRole != nil else { return }
            Task { try? await store.refreshRemote() }
        }
    }
}

struct SessionRecoveryView: View {
    let message: String
    let onRetry: () -> Void

    var body: some View {
        ZStack {
            FCTheme.background.ignoresSafeArea()
            VStack(alignment: .leading, spacing: 18) {
                FireCoinsMark(size: 50)
                Text("Не удалось подключиться")
                    .font(.system(size: 28, weight: .semibold, design: .rounded))
                    .foregroundStyle(FCTheme.text)
                Text(message)
                    .font(.system(size: 14))
                    .lineSpacing(4)
                    .foregroundStyle(FCTheme.muted)
                PrimaryActionButton(title: "Повторить подключение", systemImage: "arrow.clockwise", action: onRetry)
                Text("Сохранённая сессия не удалена. Повторный код приглашения не требуется.")
                    .font(.system(size: 11))
                    .foregroundStyle(FCTheme.muted)
            }
            .padding(26)
        }
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
