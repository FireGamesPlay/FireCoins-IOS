import Foundation

struct FireCoinsAppSettings: Equatable {
    var isSandbox: Bool = false
    var environmentLabel: String = "Рабочая среда"
    var canResetDatabase: Bool = false
    var pushConfigured: Bool = false
}

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

@MainActor
protocol FireCoinsSessionManaging: AnyObject {
    func signIn(invitationCode: String) async throws -> AppRole
    func restoreSession() async throws -> AppRole?
    func signOut() async
    func refreshSnapshot() async throws
    func loadProof(transactionID: UUID) async throws -> Data
    func reissueInvitation() async throws -> String
    func loadSettings() async throws -> FireCoinsAppSettings
    func setSandboxMode(_ enabled: Bool) async throws -> FireCoinsAppSettings
    func issueDatabaseResetChallenge() async throws -> String
    func resetCurrentDatabase(resetToken: String, confirmation: String) async throws
    func uploadAvatar(_ data: Data) async throws
    func loadAvatar(for role: AppRole) async throws -> Data?
    func registerPushToken(_ token: String, deviceID: String) async throws
    func unregisterPushToken(_ token: String) async throws
    func restartBackend() async throws
}
