import Foundation
import Combine

@MainActor
final class AppStore: ObservableObject {
    @Published private(set) var snapshot: FireCoinsSnapshot
    @Published private(set) var activeRole: AppRole?
    @Published private(set) var isRestoringSession = true
    @Published private(set) var sessionError: String?

    private let repository: FireCoinsRepository
    private let sessionManager: FireCoinsSessionManaging?

    init(repository: FireCoinsRepository) {
        self.repository = repository
        self.sessionManager = repository as? FireCoinsSessionManaging
        self.snapshot = repository.snapshot()
    }

    func restoreSession() async {
        defer { isRestoringSession = false }
        guard let sessionManager else {
            activeRole = nil
            return
        }
        do {
            activeRole = try await sessionManager.restoreSession()
            refresh()
        } catch {
            activeRole = nil
            sessionError = error.localizedDescription
        }
    }

    func signIn(invitationCode: String) async throws {
        guard let sessionManager else {
            throw FireCoinsRemoteErrorForStore.notConfigured
        }
        sessionError = nil
        let role = try await sessionManager.signIn(invitationCode: invitationCode)
        activeRole = role
        refresh()
    }

    func signOut() async {
        await sessionManager?.signOut()
        activeRole = nil
        sessionError = nil
        snapshot = repository.snapshot()
    }

    func refreshRemote() async throws {
        try await sessionManager?.refreshSnapshot()
        refresh()
    }

    func refresh() {
        snapshot = repository.snapshot()
    }

    func loadProof(transactionID: UUID) async throws -> Data {
        if let cached = snapshot.transactions.first(where: { $0.id == transactionID })?.proofImageData {
            return cached
        }
        guard let sessionManager else {
            throw FireCoinsError.transactionUnavailable
        }
        let imageData = try await sessionManager.loadProof(transactionID: transactionID)
        if let index = snapshot.transactions.firstIndex(where: { $0.id == transactionID }) {
            snapshot.transactions[index].proofImageData = imageData
            snapshot.transactions[index].proofAvailable = true
        }
        return imageData
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

private enum FireCoinsRemoteErrorForStore: LocalizedError {
    case notConfigured

    var errorDescription: String? {
        "Серверная авторизация не настроена."
    }
}
