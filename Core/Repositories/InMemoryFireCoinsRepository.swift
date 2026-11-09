import Foundation

@MainActor
final class InMemoryFireCoinsRepository: FireCoinsRepository {
    private var state: FireCoinsSnapshot

    init() {
        let calendar = Calendar.current
        let now = Date.now
        let transactions = [
            CoinTransaction(amount: 25, kind: .credit, reason: "Хорошая оценка", date: calendar.date(byAdding: .hour, value: -5, to: now) ?? now),
            CoinTransaction(amount: 100, kind: .credit, reason: "Победа в конкурсе", date: calendar.date(byAdding: .day, value: -2, to: now) ?? now),
            CoinTransaction(amount: 15, kind: .debit, reason: "Оставленные тапки", date: calendar.date(byAdding: .day, value: -4, to: now) ?? now)
        ]

        state = FireCoinsSnapshot(
            balance: 1_240,
            transactions: transactions,
            withdrawals: [],
            notes: [
                PrivateNote(sender: .father, recipient: .artemiy, text: "За хорошую неделю добавил тебе немного баллов. Так держать.", date: calendar.date(byAdding: .day, value: -1, to: now) ?? now)
            ],
            casinoRounds: []
        )
    }

    func snapshot() -> FireCoinsSnapshot { state }

    func applyTransaction(amount: Int, kind: TransactionKind, reason: String, proofImageData: Data?) async throws {
        guard amount > 0 else { throw FireCoinsError.invalidAmount }
        guard !reason.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { throw FireCoinsError.emptyText }
        let proofReasons = ["Неубранные трусы", "Оставленные тапки"]
        if kind == .debit, proofReasons.contains(reason), proofImageData == nil {
            throw FireCoinsError.proofRequired
        }

        let transaction = CoinTransaction(
            amount: amount,
            kind: kind,
            reason: reason,
            proofImageData: proofImageData
        )
        state.transactions.insert(transaction, at: 0)
        state.balance += transaction.signedAmount
    }

    func submitDispute(transactionID: UUID) async throws {
        let latestTen = Array(state.transactions.sorted { $0.date > $1.date }.prefix(10))
        guard let index = state.transactions.firstIndex(where: { $0.id == transactionID }),
              latestTen.contains(where: { $0.id == transactionID }),
              state.transactions[index].disputeStatus == .none else {
            throw FireCoinsError.transactionUnavailable
        }
        state.transactions[index].disputeStatus = .pending
    }

    func resolveDispute(transactionID: UUID, approved: Bool) async throws {
        guard let index = state.transactions.firstIndex(where: { $0.id == transactionID }),
              state.transactions[index].disputeStatus == .pending else {
            throw FireCoinsError.transactionUnavailable
        }

        let original = state.transactions[index]
        state.transactions[index].disputeStatus = approved ? .approved : .rejected

        if approved {
            let reverseKind: TransactionKind = original.kind == .credit ? .debit : .credit
            let reversal = CoinTransaction(
                amount: original.amount,
                kind: reverseKind,
                reason: "Возврат по спору: \(original.reason)"
            )
            state.transactions.insert(reversal, at: 0)
            state.balance += reversal.signedAmount
        }
    }

    func requestWithdrawal(amount: Int) async throws {
        guard amount > 0 else { throw FireCoinsError.invalidAmount }
        state.withdrawals.insert(WithdrawalRequest(amount: amount), at: 0)
    }

    func resolveWithdrawal(requestID: UUID, approved: Bool) async throws {
        guard let index = state.withdrawals.firstIndex(where: { $0.id == requestID }),
              state.withdrawals[index].status == .pending else {
            throw FireCoinsError.requestUnavailable
        }

        if approved {
            guard state.balance >= state.withdrawals[index].amount else { throw FireCoinsError.insufficientBalance }
            let amount = state.withdrawals[index].amount
            state.balance -= amount
            state.transactions.insert(
                CoinTransaction(amount: amount, kind: .debit, reason: "Вывод баллов"),
                at: 0
            )
            state.withdrawals[index].status = .approved
        } else {
            state.withdrawals[index].status = .rejected
        }
    }

    func playCasino(bet: Int) async throws -> CasinoRound {
        guard bet > 0 else { throw FireCoinsError.invalidAmount }
        let weekAgo = Calendar.current.date(byAdding: .day, value: -7, to: .now) ?? .now
        let recentRounds = state.casinoRounds.filter { $0.date >= weekAgo }
        guard recentRounds.count < 2 else { throw FireCoinsError.weeklyCasinoLimit }
        guard state.balance >= bet else { throw FireCoinsError.insufficientBalance }

        state.balance -= bet
        let won = Bool.random()
        let payout = won ? bet * 2 : 0
        if won {
            state.balance += payout
        }

        let round = CasinoRound(bet: bet, won: won, payout: payout)
        state.casinoRounds.insert(round, at: 0)
        state.transactions.insert(
            CoinTransaction(amount: bet, kind: .debit, reason: "Ставка в рулетке"),
            at: 0
        )
        if won {
            state.transactions.insert(
                CoinTransaction(amount: payout, kind: .credit, reason: "Выигрыш в казино"),
                at: 0
            )
        }
        return round
    }

    func sendNote(from role: AppRole, text: String) async throws {
        let cleanText = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !cleanText.isEmpty else { throw FireCoinsError.emptyText }
        let recipient: AppRole = role == .father ? .artemiy : .father
        state.notes.insert(PrivateNote(sender: role, recipient: recipient, text: cleanText), at: 0)
    }
}
