import Foundation
import Combine

@MainActor
final class AppStore: ObservableObject {
    @Published private(set) var snapshot: FireCoinsSnapshot
    private let repository: FireCoinsRepository

    init(repository: FireCoinsRepository) {
        self.repository = repository
        self.snapshot = repository.snapshot()
    }

    func refresh() {
        snapshot = repository.snapshot()
    }

    func applyTransaction(amount: Int, kind: TransactionKind, reason: String, proofImageData: Data?) async throws {
        try await repository.applyTransaction(amount: amount, kind: kind, reason: reason, proofImageData: proofImageData)
        refresh()
    }

    func submitDispute(transactionID: UUID) async throws {
        try await repository.submitDispute(transactionID: transactionID)
        refresh()
    }

    func resolveDispute(transactionID: UUID, approved: Bool) async throws {
        try await repository.resolveDispute(transactionID: transactionID, approved: approved)
        refresh()
    }

    func requestWithdrawal(amount: Int) async throws {
        try await repository.requestWithdrawal(amount: amount)
        refresh()
    }

    func resolveWithdrawal(requestID: UUID, approved: Bool) async throws {
        try await repository.resolveWithdrawal(requestID: requestID, approved: approved)
        refresh()
    }

    func playCasino(bet: Int) async throws -> CasinoRound {
        let round = try await repository.playCasino(bet: bet)
        refresh()
        return round
    }

    func sendNote(from role: AppRole, text: String) async throws {
        try await repository.sendNote(from: role, text: text)
        refresh()
    }
}
