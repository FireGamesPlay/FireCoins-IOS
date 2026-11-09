import SwiftUI

struct CasinoView: View {
    @EnvironmentObject private var store: AppStore
    @State private var bet = "5"
    @State private var isSpinning = false
    @State private var result: CasinoRound?
    @State private var alertMessage: String?
    @State private var diceRotation = 0.0

    private var remainingRounds: Int {
        let weekAgo = Calendar.current.date(byAdding: .day, value: -7, to: .now) ?? .now
        return max(0, 2 - store.snapshot.casinoRounds.filter { $0.date >= weekAgo }.count)
    }

    var body: some View {
        ZStack {
            FCTheme.background.ignoresSafeArea()
            ScrollView(showsIndicators: false) {
                VStack(alignment: .leading, spacing: 22) {
                    VStack(alignment: .leading, spacing: 5) {
                        Text("Рулетка")
                            .font(.system(size: 30, weight: .semibold, design: .rounded))
                            .foregroundStyle(FCTheme.text)
                        Text("Немного удачи — и только в пределах лимита.")
                            .font(.system(size: 13))
                            .foregroundStyle(FCTheme.muted)
                    }

                    casinoHero
                    VStack(alignment: .leading, spacing: 13) {
                        SectionHeading(title: "Размер ставки")
                        HStack(spacing: 8) {
                            ForEach([1, 5, 10], id: \.self) { amount in
                                Button {
                                    bet = String(amount)
                                } label: {
                                    Text("\(amount) FC")
                                        .font(.system(size: 12, weight: .semibold))
                                        .foregroundStyle(bet == String(amount) ? Color(hex: 0x211A14) : FCTheme.text)
                                        .frame(maxWidth: .infinity)
                                        .padding(.vertical, 12)
                                        .background(bet == String(amount) ? FCTheme.accent : FCTheme.surface, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                                        .overlay { RoundedRectangle(cornerRadius: 12, style: .continuous).stroke(FCTheme.border, lineWidth: 1) }
                                }
                                .buttonStyle(.plain)
                            }
                        }
                        AmountTextField(title: "Или введи свою ставку", value: $bet)
                    }
                    PrimaryActionButton(title: isSpinning ? "Крутим…" : "Сделать ход", systemImage: "die.face.5", disabled: isSpinning || remainingRounds == 0, action: spin)

                    if let result {
                        resultCard(result)
                    }

                    VStack(alignment: .leading, spacing: 11) {
                        SectionHeading(title: "Последние раунды")
                        if store.snapshot.casinoRounds.isEmpty {
                            EmptyState(symbol: "dice", title: "Пока без ставок", subtitle: "Твои результаты появятся после первого запуска.")
                                .fcCard(padding: 12)
                        } else {
                            ForEach(store.snapshot.casinoRounds.prefix(5)) { round in
                                HStack {
                                    Image(systemName: round.won ? "sparkles" : "minus.circle")
                                        .foregroundStyle(round.won ? FCTheme.positive : FCTheme.negative)
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(round.won ? "Выигрыш" : "Проигрыш")
                                            .font(.system(size: 13, weight: .semibold)).foregroundStyle(FCTheme.text)
                                        Text(round.date.formatted(date: .abbreviated, time: .shortened))
                                            .font(.system(size: 10)).foregroundStyle(FCTheme.muted)
                                    }
                                    Spacer()
                                    Text(round.won ? "+\(round.payout) FC" : "−\(round.bet) FC")
                                        .font(.system(size: 14, weight: .semibold, design: .rounded))
                                        .foregroundStyle(round.won ? FCTheme.positive : FCTheme.negative)
                                }
                                .padding(13)
                                .background(FCTheme.surface, in: RoundedRectangle(cornerRadius: 15, style: .continuous))
                            }
                        }
                    }
                }
                .padding(.horizontal, 20)
                .padding(.top, 22)
                .padding(.bottom, 28)
            }
        }
        .navigationBarHidden(true)
        .alert("Fire Coins", isPresented: Binding(get: { alertMessage != nil }, set: { if !$0 { alertMessage = nil } })) {
            Button("Понятно", role: .cancel) { alertMessage = nil }
        } message: { Text(alertMessage ?? "") }
    }

    private var casinoHero: some View {
        VStack(spacing: 16) {
            HStack {
                PillLabel(text: "50 / 50", color: FCTheme.accentLight)
                Spacer()
                Text("ЛИМИТ НА 7 ДНЕЙ")
                    .font(.system(size: 9, weight: .bold))
                    .tracking(1.1)
                    .foregroundStyle(FCTheme.muted)
            }
            HStack(spacing: 13) {
                diceTile("die.face.2", offset: -6)
                diceTile("die.face.5", offset: 0)
                diceTile("die.face.4", offset: 6)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 8)
            HStack(alignment: .firstTextBaseline, spacing: 5) {
                Text("\(remainingRounds)")
                    .font(.system(size: 32, weight: .semibold, design: .rounded).monospacedDigit())
                    .foregroundStyle(FCTheme.text)
                Text("из 2 запусков осталось")
                    .font(.system(size: 12))
                    .foregroundStyle(FCTheme.muted)
            }
            HStack(spacing: 6) {
                ForEach(0..<2, id: \.self) { index in
                    Capsule().fill(index < remainingRounds ? FCTheme.accent : FCTheme.border).frame(height: 4)
                }
            }
        }
        .padding(19)
        .background(FCTheme.surface, in: RoundedRectangle(cornerRadius: 22, style: .continuous))
        .overlay { RoundedRectangle(cornerRadius: 22, style: .continuous).stroke(FCTheme.border, lineWidth: 1) }
    }

    private func diceTile(_ symbol: String, offset: CGFloat) -> some View {
        Image(systemName: symbol)
            .font(.system(size: 34, weight: .light))
            .foregroundStyle(FCTheme.accentLight)
            .frame(width: 72, height: 72)
            .background(LinearGradient(colors: [Color(hex: 0x34302B), Color(hex: 0x222326)], startPoint: .topLeading, endPoint: .bottomTrailing), in: RoundedRectangle(cornerRadius: 19, style: .continuous))
            .overlay { RoundedRectangle(cornerRadius: 19, style: .continuous).stroke(FCTheme.accent.opacity(0.2), lineWidth: 1) }
            .rotationEffect(.degrees(offset + (isSpinning ? diceRotation : 0)))
    }

    private func resultCard(_ round: CasinoRound) -> some View {
        HStack(spacing: 12) {
            Image(systemName: round.won ? "trophy.fill" : "leaf")
                .font(.system(size: 20))
                .foregroundStyle(round.won ? FCTheme.positive : FCTheme.muted)
                .frame(width: 43, height: 43)
                .background((round.won ? FCTheme.positive : FCTheme.muted).opacity(0.12), in: RoundedRectangle(cornerRadius: 13, style: .continuous))
            VStack(alignment: .leading, spacing: 3) {
                Text(round.won ? "Сегодня твой день" : "В следующий раз")
                    .font(.system(size: 14, weight: .semibold)).foregroundStyle(FCTheme.text)
                Text(round.won ? "Выплата: \(round.payout) FC" : "Ставка \(round.bet) FC списана")
                    .font(.system(size: 11)).foregroundStyle(FCTheme.muted)
            }
            Spacer()
            Text(round.won ? "+\(round.bet)" : "−\(round.bet)")
                .font(.system(size: 17, weight: .semibold, design: .rounded))
                .foregroundStyle(round.won ? FCTheme.positive : FCTheme.negative)
        }
        .fcCard(padding: 15)
    }

    private func spin() {
        guard let amount = Int(bet), amount > 0 else {
            alertMessage = FireCoinsError.invalidAmount.localizedDescription
            return
        }
        isSpinning = true
        result = nil
        withAnimation(.linear(duration: 0.9)) { diceRotation += 720 }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.9) {
            Task { @MainActor in
                do {
                    let round = try await store.playCasino(bet: amount)
                    withAnimation(.spring(response: 0.35, dampingFraction: 0.8)) { result = round }
                } catch {
                    alertMessage = error.localizedDescription
                }
                isSpinning = false
            }
        }
    }
}
