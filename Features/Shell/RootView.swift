import SwiftUI

struct RootView: View {
    @EnvironmentObject private var store: AppStore
    @Environment(\.scenePhase) private var scenePhase
    @State private var welcomeRole: AppRole?
    @State private var lastWelcomedRole: AppRole?

    var body: some View {
        ZStack {
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

            if let welcomeRole {
                FireCoinsWelcomeView(role: welcomeRole) {
                    withAnimation(.easeOut(duration: 0.35)) {
                        self.welcomeRole = nil
                    }
                }
                .transition(.opacity)
                .zIndex(10)
            }
        }
        .background(FCTheme.background)
        .animation(.easeInOut(duration: 0.2), value: store.activeRole)
        .onChange(of: store.activeRole) { _, role in
            guard let role else {
                lastWelcomedRole = nil
                welcomeRole = nil
                return
            }
            guard lastWelcomedRole != role else { return }
            lastWelcomedRole = role
            withAnimation(.easeIn(duration: 0.4)) {
                welcomeRole = role
            }
        }
        .task {
            await store.restoreSession()
        }
        .onChange(of: scenePhase) { _, phase in
            guard phase == .active, store.activeRole != nil else { return }
            Task { try? await store.refreshRemote() }
        }
    }
}

private struct FireCoinsWelcomeView: View {
    let role: AppRole
    let onContinue: () -> Void

    @State private var revealed = false
    @State private var glowing = false
    @State private var didFinish = false

    private var greeting: String {
        role == .father ? "Привет, Макс!" : "Привет, Артемий!"
    }

    private var subtitle: String {
        role == .father ? "АДМИНИСТРАТОР" : "ЛИЧНЫЙ КАБИНЕТ"
    }

    var body: some View {
        ZStack {
            FCTheme.background.ignoresSafeArea()

            Circle()
                .fill(
                    RadialGradient(
                        colors: [FCTheme.accent.opacity(0.22), FCTheme.accent.opacity(0.02), .clear],
                        center: .center,
                        startRadius: 8,
                        endRadius: 180
                    )
                )
                .frame(width: 360, height: 360)
                .blur(radius: 24)
                .scaleEffect(glowing ? 1.12 : 0.86)
                .offset(y: -92)

            Circle()
                .stroke(FCTheme.accent.opacity(glowing ? 0.24 : 0.08), lineWidth: 1)
                .frame(width: 244, height: 244)
                .scaleEffect(glowing ? 1.12 : 0.88)
                .offset(y: -92)

            VStack(spacing: 0) {
                Image("FireCoinsWelcomeLogo")
                    .resizable()
                    .scaledToFit()
                    .frame(width: 190, height: 190)
                    .scaleEffect(revealed ? 1 : 0.72)
                    .rotationEffect(.degrees(revealed ? 0 : -12))
                    .opacity(revealed ? 1 : 0)
                    .shadow(color: FCTheme.accent.opacity(0.28), radius: 34, y: 0)

                Text("FIRE COINS")
                    .font(.system(size: 11, weight: .bold, design: .rounded))
                    .tracking(5)
                    .foregroundStyle(FCTheme.accent)
                    .padding(.top, 24)
                    .opacity(revealed ? 1 : 0)
                    .offset(y: revealed ? 0 : 12)

                Text(greeting)
                    .font(.system(size: 34, weight: .semibold, design: .rounded))
                    .tracking(-0.8)
                    .foregroundStyle(FCTheme.text)
                    .padding(.top, 14)
                    .opacity(revealed ? 1 : 0)
                    .offset(y: revealed ? 0 : 18)

                Text(subtitle)
                    .font(.system(size: 10, weight: .semibold))
                    .tracking(2.5)
                    .foregroundStyle(FCTheme.muted)
                    .padding(.top, 10)
                    .opacity(revealed ? 1 : 0)

                Button(action: finish) {
                    Text("Продолжить")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(Color(hex: 0x211A14))
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 15)
                        .background(
                            LinearGradient(
                                colors: [FCTheme.accentLight, FCTheme.accent],
                                startPoint: .topLeading,
                                endPoint: .bottomTrailing
                            ),
                            in: RoundedRectangle(cornerRadius: 16, style: .continuous)
                        )
                }
                .buttonStyle(.plain)
                .padding(.top, 36)
                .opacity(revealed ? 1 : 0)
                .offset(y: revealed ? 0 : 16)
            }
            .padding(.horizontal, 34)
        }
        .onAppear {
            withAnimation(.spring(response: 0.8, dampingFraction: 0.78).delay(0.08)) {
                revealed = true
            }
            withAnimation(.easeInOut(duration: 2.3).repeatForever(autoreverses: true)) {
                glowing = true
            }
            Task {
                try? await Task.sleep(nanoseconds: 2_900_000_000)
                finish()
            }
        }
    }

    private func finish() {
        guard !didFinish else { return }
        didFinish = true
        onContinue()
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
