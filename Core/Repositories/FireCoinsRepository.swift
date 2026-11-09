import Foundation

@MainActor
protocol FireCoinsRepository: AnyObject {
    func snapshot() -> FireCoinsSnapshot
    func applyTransaction(amount: Int, kind: TransactionKind, reason: String, proofImageData: Data?) async throws
    func submitDispute(transactionID: UUID) async throws
    func resolveDispute(transactionID: UUID, approved: Bool) async throws
    func requestWithdrawal(amount: Int) async throws
    func resolveWithdrawal(requestID: UUID, approved: Bool) async throws
    func playCasino(bet: Int) async throws -> CasinoRound
    func sendNote(from role: AppRole, text: String) async throws
}
