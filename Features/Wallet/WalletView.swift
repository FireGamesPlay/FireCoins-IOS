import SwiftUI

struct WalletView: View {
    let role: AppRole
    @EnvironmentObject private var store: AppStore
    @State private var amount = ""
    @State private var alertMessage: String?

    var body: some View {
        ZStack {
            FCTheme.background.ignoresSafeArea()
            ScrollView(showsIndicators: false) {
                VStack(alignment: .leading, spacing: 22) {
                    VStack(alignment: .leading, spacing: 5) {
                        Text("Кошелёк")
                            .font(.system(size: 30, weight: .semibold, design: .rounded))
                            .foregroundStyle(FCTheme.text)
                        Text("Заявки на вывод баллов и их статус.")
                            .font(.system(size: 13)).foregroundStyle(FCTheme.muted)
                    }

                    HStack {
                        VStack(alignment: .leading, spacing: 5) {
                            Text("ДОСТУПНО")
                                .font(.system(size: 10, weight: .bold)).tracking(1.2).foregroundStyle(FCTheme.muted)
                            Text("\(store.snapshot.balance.formatted()) FC")
                                .font(.system(size: 28, weight: .semibold, design: .rounded).monospacedDigit()).foregroundStyle(FCTheme.text)
                        }
                        Spacer()
                        Image(systemName: "wallet.pass")
                            .font(.system(size: 24, weight: .light)).foregroundStyle(FCTheme.accent)
                            .frame(width: 54, height: 54).background(FCTheme.accent.opacity(0.1), in: Circle())
                    }
                    .fcCard()

                    if role == .artemiy {
                        VStack(alignment: .leading, spacing: 14) {
                            SectionHeading(title: "Новая заявка")
                            AmountTextField(title: "Сколько баллов вывести", value: $amount)
                            Text("Баллы спишутся только после одобрения заявки администратором.")
                                .font(.system(size: 11)).foregroundStyle(FCTheme.muted)
                            PrimaryActionButton(title: "Отправить заявку", systemImage: "arrow.up.right", action: submit)
                        }
                        .fcCard()
                    } else {
                        HStack(spacing: 10) {
                            Image(systemName: "info.circle.fill").foregroundStyle(FCTheme.accent)
                            Text("Заявки пользователя отображаются ниже. Решения доступны в панели администратора.")
                                .font(.system(size: 12)).foregroundStyle(FCTheme.muted)
                        }
                        .fcCard(padding: 14)
                    }

                    VStack(alignment: .leading, spacing: 11) {
                        SectionHeading(title: "Заявки")
                        if store.snapshot.withdrawals.isEmpty {
                            EmptyState(symbol: "tray", title: "Заявок пока нет", subtitle: "Здесь будет отображаться история вывода.")
                                .fcCard(padding: 10)
                        } else {
                            ForEach(store.snapshot.withdrawals) { request in
                                HStack(spacing: 12) {
                                    Image(systemName: "arrow.up.right")
                                        .foregroundStyle(FCTheme.accent)
                                        .frame(width: 40, height: 40)
                                        .background(FCTheme.accent.opacity(0.1), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                                    VStack(alignment: .leading, spacing: 3) {
                                        Text("Вывод \(request.amount) FC")
                                            .font(.system(size: 14, weight: .semibold)).foregroundStyle(FCTheme.text)
                                        Text(request.date.formatted(date: .abbreviated, time: .shortened))
                                            .font(.system(size: 10)).foregroundStyle(FCTheme.muted)
                                    }
                                    Spacer()
                                    PillLabel(text: request.status.title, color: statusColor(request.status))
                                }
                                .padding(13)
                                .background(FCTheme.surface, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                            }
                        }
                    }
                }
                .padding(.horizontal, 20)
                .padding(.top, 22)
                .padding(.bottom, 30)
            }
        }
        .navigationBarHidden(true)
        .alert("Fire Coins", isPresented: Binding(get: { alertMessage != nil }, set: { if !$0 { alertMessage = nil } })) {
            Button("Понятно", role: .cancel) { alertMessage = nil }
        } message: { Text(alertMessage ?? "") }
    }

    private func submit() {
        guard let value = Int(amount), value > 0 else {
            alertMessage = FireCoinsError.invalidAmount.localizedDescription
            return
        }
        Task { @MainActor in
            do {
                try await store.requestWithdrawal(amount: value)
                amount = ""
                alertMessage = "Заявка отправлена администратору."
            } catch {
                alertMessage = error.localizedDescription
            }
        }
    }

    private func statusColor(_ status: WithdrawalStatus) -> Color {
        switch status {
        case .pending: return FCTheme.accent
        case .approved: return FCTheme.positive
        case .rejected: return FCTheme.negative
        }
    }
}
