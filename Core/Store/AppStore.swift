import Foundation
import Combine

@MainActor
final class AppStore: ObservableObject {
    @Published private(set) var snapshot: FireCoinsSnapshot
    @Published private(set) var activeRole: AppRole?
    @Published private(set) var isRestoringSession = true
    @Published private(set) var sessionError: String?
    @Published private(set) var settings = FireCoinsAppSettings()
    @Published private(set) var avatarData: Data?

    private let repository: FireCoinsRepository
    private let sessionManager: FireCoinsSessionManaging?

    init(repository: FireCoinsRepository) {
        self.repository = repository
        self.sessionManager = repository as? FireCoinsSessionManaging
        self.snapshot = repository.snapshot()
    }

    func restoreSession() async {
        isRestoringSession = true
        sessionError = nil
        defer { isRestoringSession = false }
        guard let sessionManager else {
            activeRole = nil
            return
        }
        do {
            activeRole = try await sessionManager.restoreSession()
            refresh()
            if activeRole != nil {
                await refreshAccountSettings()
            }
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
        do {
            try await sessionManager.refreshSnapshot()
            refresh()
            await refreshAccountSettings()
        } catch {
            // Authentication has succeeded; do not make the one-time invite unusable
            // by showing the invitation screen again for a temporary data-fetch error.
            sessionError = error.localizedDescription
        }
    }

    func signOut() async {
        if let token = FireCoinsPushCoordinator.currentToken {
            try? await sessionManager?.unregisterPushToken(token)
        }
        await sessionManager?.signOut()
        activeRole = nil
        sessionError = nil
        snapshot = repository.snapshot()
        settings = FireCoinsAppSettings()
        avatarData = nil
    }

    func reissueInvitation() async throws -> String {
        guard activeRole != nil, let sessionManager else {
            throw FireCoinsRemoteErrorForStore.notConfigured
        }
        return try await sessionManager.reissueInvitation()
    }

    func refreshAccountSettings() async {
        guard let sessionManager, let role = activeRole else { return }
        do {
            settings = try await sessionManager.loadSettings()
            avatarData = try await sessionManager.loadAvatar(for: role)
        } catch {
            // Profile content remains usable even if optional settings/avatar requests fail.
        }
    }

    func setSandboxMode(_ enabled: Bool) async throws {
        guard let sessionManager else { throw FireCoinsRemoteErrorForStore.notConfigured }
        settings = try await sessionManager.setSandboxMode(enabled)
        avatarData = try await sessionManager.loadAvatar(for: activeRole ?? .artemiy)
        try await sessionManager.refreshSnapshot()
        refresh()
    }

    func uploadAvatar(_ data: Data) async throws {
        guard let sessionManager else { throw FireCoinsRemoteErrorForStore.notConfigured }
        try await sessionManager.uploadAvatar(data)
        // The upload response confirms persistence. Use the selected image immediately
        // instead of doing a second GET that can keep the profile in a loading state.
        avatarData = data
    }

    func issueDatabaseResetChallenge() async throws -> String {
        guard activeRole == .father, let sessionManager else {
            throw FireCoinsRemoteErrorForStore.notConfigured
        }
        return try await sessionManager.issueDatabaseResetChallenge()
    }

    func resetCurrentDatabase(resetToken: String, confirmation: String) async throws {
        guard activeRole == .father, let sessionManager else {
            throw FireCoinsRemoteErrorForStore.notConfigured
        }
        try await sessionManager.resetCurrentDatabase(resetToken: resetToken, confirmation: confirmation)
        try await sessionManager.refreshSnapshot()
        refresh()
        await refreshAccountSettings()
    }

    func restartBackend() async throws {
        guard activeRole == .father, let sessionManager else {
            throw FireCoinsRemoteErrorForStore.notConfigured
        }
        try await sessionManager.restartBackend()
    }

    func registerPushToken(_ token: String, deviceID: String) async throws {
        guard let sessionManager else { throw FireCoinsRemoteErrorForStore.notConfigured }
        let previous = FireCoinsPushCoordinator.currentToken
        if let previous, previous != token {
            try? await sessionManager.unregisterPushToken(previous)
        }
        try await sessionManager.registerPushToken(token, deviceID: deviceID)
        FireCoinsPushCoordinator.storeToken(token)
    }

    func refreshRemote() async throws {
        try await sessionManager?.refreshSnapshot()
        sessionError = nil
        refresh()
        await refreshAccountSettings()
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
