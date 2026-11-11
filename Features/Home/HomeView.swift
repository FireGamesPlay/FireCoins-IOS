import SwiftUI

struct HomeView: View {
    let role: AppRole
    @Binding var selectedTab: MainTab
    @EnvironmentObject private var store: AppStore

    private var latestTransactions: [CoinTransaction] {
        Array(store.snapshot.transactions.sorted { $0.date > $1.date }.prefix(3))
    }

    var body: some View {
        ZStack {
            FCTheme.background.ignoresSafeArea()
            ScrollView(showsIndicators: false) {
                VStack(alignment: .leading, spacing: 25) {
                    header
                    balanceCard
                    quickActions
                    recentActivity
                }
                .padding(.horizontal, 20)
                .padding(.top, 12)
                .padding(.bottom, 30)
            }
        }
        .toolbar(.hidden, for: .navigationBar)
    }

    private var header: some View {
        HStack(spacing: 12) {
            FireCoinsMark(size: 40)
            VStack(alignment: .leading, spacing: 2) {
                Text("FIRE COINS")
                    .font(.system(size: 12, weight: .bold, design: .rounded))
                    .tracking(1.7)
                    .foregroundStyle(FCTheme.accent)
                Text(role == .father ? "Семейный счёт" : "Личный кабинет")
                    .font(.system(size: 12))
                    .foregroundStyle(FCTheme.muted)
            }
            Spacer()
            VStack(alignment: .trailing, spacing: 3) {
                Text(role == .father ? "Макс" : "Артемий")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(FCTheme.text)
                Text(role.subtitle)
                    .font(.system(size: 10))
                    .foregroundStyle(FCTheme.muted)
            }
            Text(role.initials)
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(FCTheme.accentLight)
                .frame(width: 36, height: 36)
                .background(FCTheme.surfaceElevated, in: Circle())
                .overlay { Circle().stroke(FCTheme.border, lineWidth: 1) }
        }
    }

    private var balanceCard: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Text("ТЕКУЩИЙ БАЛАНС")
                    .font(.system(size: 10, weight: .bold))
                    .tracking(1.5)
                    .foregroundStyle(Color.white.opacity(0.66))
                Spacer()
                PillLabel(text: "FC ACCOUNT", color: FCTheme.accentLight)
            }
            HStack(alignment: .firstTextBaseline, spacing: 10) {
                Text(store.snapshot.balance.formatted())
                    .font(.system(size: 42, weight: .semibold, design: .rounded).monospacedDigit())
                    .tracking(-1.5)
                    .foregroundStyle(.white)
                    .contentTransition(.numericText())
                Text("FC")
                    .font(.system(size: 14, weight: .bold, design: .rounded))
                    .foregroundStyle(Color.white.opacity(0.72))
            }
            .padding(.top, 13)
            Text(role == .father ? "Баланс аккаунта Артемия" : "Твои доступные баллы")
                .font(.system(size: 12))
                .foregroundStyle(Color.white.opacity(0.68))
                .padding(.top, 3)

            Rectangle()
                .fill(Color.white.opacity(0.16))
                .frame(height: 1)
                .padding(.vertical, 19)

            HStack {
                Label("Персональная система", systemImage: "shield.lefthalf.filled")
                Spacer()
                Text("Закрытый доступ")
            }
            .font(.system(size: 10, weight: .medium))
            .foregroundStyle(Color.white.opacity(0.76))
        }
        .padding(22)
        .background {
            ZStack(alignment: .topTrailing) {
                RoundedRectangle(cornerRadius: 25, style: .continuous)
                    .fill(LinearGradient(colors: [Color(hex: 0x34302B), Color(hex: 0x201D1B), Color(hex: 0x17191D)], startPoint: .topLeading, endPoint: .bottomTrailing))
                Circle()
                    .fill(FCTheme.accent.opacity(0.15))
                    .frame(width: 160, height: 160)
                    .blur(radius: 28)
                    .offset(x: 42, y: -62)
                Circle()
                    .stroke(Color.white.opacity(0.07), lineWidth: 1)
                    .frame(width: 190, height: 190)
                    .offset(x: 50, y: -70)
            }
        }
        .overlay { RoundedRectangle(cornerRadius: 25, style: .continuous).stroke(Color.white.opacity(0.09), lineWidth: 1) }
        .shadow(color: .black.opacity(0.22), radius: 18, y: 10)
    }

    private var quickActions: some View {
        VStack(alignment: .leading, spacing: 13) {
            SectionHeading(title: "Быстрый доступ")
            HStack(spacing: 11) {
                QuickActionTile(title: "История", subtitle: "Все операции", symbol: "list.bullet.rectangle", tint: FCTheme.blue) {
                    selectedTab = .history
                }
                QuickActionTile(
                    title: role == .father ? "Управление" : "Рулетка",
                    subtitle: role == .father ? "Панель администратора" : "2 запуска в неделю",
                    symbol: role == .father ? "slider.horizontal.3" : "die.face.5",
                    tint: FCTheme.accent
                ) {
                    selectedTab = .casino
                }
                QuickActionTile(title: "Вывод", subtitle: "Заявки и статус", symbol: "arrow.up.right.square", tint: FCTheme.positive) {
                    selectedTab = .wallet
                }
            }
        }
    }

    private var recentActivity: some View {
        VStack(alignment: .leading, spacing: 10) {
            SectionHeading(title: "Последние операции", actionTitle: "Вся история") {
                selectedTab = .history
            }
            VStack(spacing: 3) {
                if latestTransactions.isEmpty {
                    EmptyState(symbol: "tray", title: "Пока тихо", subtitle: "Новые операции появятся здесь.")
                } else {
                    ForEach(latestTransactions) { transaction in
                        TransactionRow(transaction: transaction)
                        if transaction.id != latestTransactions.last?.id {
                            Divider().overlay(FCTheme.border).padding(.leading, 55)
                        }
                    }
                }
            }
            .padding(.horizontal, 15)
            .padding(.vertical, 7)
            .background(FCTheme.surface, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
            .overlay { RoundedRectangle(cornerRadius: 20, style: .continuous).stroke(FCTheme.border, lineWidth: 1) }
        }
    }
}

private struct QuickActionTile: View {
    let title: String
    let subtitle: String
    let symbol: String
    let tint: Color
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 10) {
                Image(systemName: symbol)
                    .font(.system(size: 17, weight: .medium))
                    .foregroundStyle(tint)
                    .frame(width: 35, height: 35)
                    .background(tint.opacity(0.12), in: RoundedRectangle(cornerRadius: 11, style: .continuous))
                VStack(alignment: .leading, spacing: 3) {
                    Text(title).font(.system(size: 13, weight: .semibold)).foregroundStyle(FCTheme.text)
                    Text(subtitle).font(.system(size: 10)).foregroundStyle(FCTheme.muted).lineLimit(1)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(13)
            .background(FCTheme.surface, in: RoundedRectangle(cornerRadius: 17, style: .continuous))
            .overlay { RoundedRectangle(cornerRadius: 17, style: .continuous).stroke(FCTheme.border, lineWidth: 1) }
        }
        .buttonStyle(.plain)
    }
}
