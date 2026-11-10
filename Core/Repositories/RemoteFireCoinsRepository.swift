import Foundation
import Security
import CryptoKit

private enum FireCoinsAPIConfiguration {
    static let baseURL = URL(string: "https://app.firegames-it.tech")!
    static let keychainService = "ru.firedev.firecoins.session"
}

private struct MobileTokens: Codable {
    let accessToken: String
    let refreshToken: String
}

private struct APIErrorBody: Decodable {
    let code: String?
    let message: String?
}

private struct APIErrorEnvelope: Decodable {
    let error: APIErrorBody?
}

private struct MobileSessionDTO: Decodable {
    let accessToken: String
    let refreshToken: String
    let tokenType: String?
    let accessExpiresIn: Int?
    let refreshExpiresIn: Int?
}

private struct AuthUserDTO: Decodable {
    let id: Int64
    let role: String
}

private struct AuthEnvelopeDTO: Decodable {
    let user: AuthUserDTO
    let session: MobileSessionDTO
}

private struct RefreshEnvelopeDTO: Decodable {
    let session: MobileSessionDTO
}

private struct MeEnvelopeDTO: Decodable {
    let user: AuthUserDTO
}

private struct DashboardEnvelopeDTO: Decodable {
    let data: DashboardDTO
}

private struct DashboardDTO: Decodable {
    let balance: BalanceDTO
}

private struct BalanceDTO: Decodable {
    let balance: Int
}

private struct TransactionsEnvelopeDTO: Decodable {
    let items: [TransactionDTO]
}

private struct TransactionDTO: Decodable {
    let id: Int64
    let type: String
    let amount: Int
    let reason: String
    let timestamp: String
    let disputeStatus: String?
    let hasProof: Bool?
}

private struct WithdrawalsEnvelopeDTO: Decodable {
    let items: [WithdrawalDTO]
}

private struct WithdrawalDTO: Decodable {
    let id: Int64
    let amount: Int
    let timestamp: String
    let status: String
}

private struct NotesEnvelopeDTO: Decodable {
    let items: [NoteDTO]
}

private struct NoteDTO: Decodable {
    let id: String
    let senderId: Int64
    let senderRole: String
    let recipientId: Int64
    let text: String
    let timestamp: String
}

private struct CasinoStatusEnvelopeDTO: Decodable {
    let casino: CasinoStatusDTO
}

private struct CasinoStatusDTO: Decodable {
    let spinsLeft: Int?
    let rounds: [CasinoRoundDTO]?
}

private struct CasinoRoundDTO: Decodable {
    let id: Int64
    let bet: Int
    let won: Bool
    let payout: Int
    let timestamp: String
}

private struct CasinoResultEnvelopeDTO: Decodable {
    let result: CasinoResultDTO
}

private struct CasinoResultDTO: Decodable {
    let win: Bool
    let bet: Int
    let payout: Int
}

private struct ProofUploadEnvelopeDTO: Decodable {
    let proofFileId: String
}

private struct EmptyEnvelopeDTO: Decodable {
    let ok: Bool?
}

private enum FireCoinsRemoteError: LocalizedError {
    case invalidServerResponse
    case unauthorized
    case missingSession
    case unsupportedRole
    case invalidIdentifier
    case server(String)
    case imageUploadFailed

    var errorDescription: String? {
        switch self {
        case .invalidServerResponse:
            return "Сервер вернул некорректный ответ."
        case .unauthorized:
            return "Сессия истекла. Войдите по приглашению ещё раз."
        case .missingSession:
            return "Нет активной сессии."
        case .unsupportedRole:
            return "Сервер вернул неизвестную роль аккаунта."
        case .invalidIdentifier:
            return "Не удалось определить идентификатор операции."
        case .server(let message):
            return message
        case .imageUploadFailed:
            return "Не удалось загрузить фотографию."
        }
    }
}

private final class KeychainTokenStore {
    func load() -> MobileTokens? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: FireCoinsAPIConfiguration.keychainService,
            kSecAttrAccount as String: "mobile-session",
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]
        var result: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess,
              let data = result as? Data else {
            return nil
        }
        return try? JSONDecoder().decode(MobileTokens.self, from: data)
    }

    func save(_ tokens: MobileTokens) throws {
        let data = try JSONEncoder().encode(tokens)
        let key: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: FireCoinsAPIConfiguration.keychainService,
            kSecAttrAccount as String: "mobile-session"
        ]
        let update: [String: Any] = [
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        ]
        let status = SecItemUpdate(key as CFDictionary, update as CFDictionary)
        if status == errSecItemNotFound {
            var insert = key
            update.forEach { insert[$0.key] = $0.value }
            let addStatus = SecItemAdd(insert as CFDictionary, nil)
            guard addStatus == errSecSuccess else {
                throw FireCoinsRemoteError.server("Не удалось безопасно сохранить сессию на устройстве.")
            }
        } else if status != errSecSuccess {
            throw FireCoinsRemoteError.server("Не удалось обновить защищённую сессию.")
        }
    }

    func clear() {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: FireCoinsAPIConfiguration.keychainService,
            kSecAttrAccount as String: "mobile-session"
        ]
        SecItemDelete(query as CFDictionary)
    }
}

@MainActor
private final class FireCoinsAPIClient {
    private let tokenStore = KeychainTokenStore()
    private let session: URLSession
    private let decoder: JSONDecoder
    private var tokens: MobileTokens?

    init() {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.waitsForConnectivity = true
        configuration.timeoutIntervalForRequest = 25
        configuration.timeoutIntervalForResource = 45
        self.session = URLSession(configuration: configuration)
        let decoder = JSONDecoder()
        decoder.keyDecodingStrategy = .convertFromSnakeCase
        self.decoder = decoder
        self.tokens = tokenStore.load()
    }

    var hasStoredSession: Bool { tokens != nil }

    func signIn(invitationCode: String) async throws -> AppRole {
        let data = try JSONSerialization.data(withJSONObject: ["invitation_code": invitationCode])
        let response: AuthEnvelopeDTO = try await send(
            path: "/api/mobile/auth",
            method: "POST",
            body: data,
            authenticated: false
        )
        let sessionTokens = MobileTokens(
            accessToken: response.session.accessToken,
            refreshToken: response.session.refreshToken
        )
        try tokenStore.save(sessionTokens)
        tokens = sessionTokens
        guard let role = AppRole(rawValue: response.user.role) else {
            tokenStore.clear()
            tokens = nil
            throw FireCoinsRemoteError.unsupportedRole
        }
        return role
    }

    func restoreSession() async throws -> AppRole? {
        guard tokens != nil else { return nil }
        do {
            try await refreshSession()
            let response: MeEnvelopeDTO = try await send(path: "/api/me", method: "GET")
            guard let role = AppRole(rawValue: response.user.role) else {
                throw FireCoinsRemoteError.unsupportedRole
            }
            return role
        } catch {
            tokenStore.clear()
            tokens = nil
            if case FireCoinsRemoteError.server = error {
                throw error
            }
            return nil
        }
    }

    func signOut() async {
        if let current = tokens,
           let body = try? JSONSerialization.data(withJSONObject: ["refresh_token": current.refreshToken]) {
            _ = try? await sendData(
                path: "/api/mobile/logout",
                method: "POST",
                body: body,
                authenticated: false,
                retryAfterRefresh: false
            )
        }
        tokenStore.clear()
        tokens = nil
    }

    func get<T: Decodable>(_ path: String) async throws -> T {
        try await send(path: path, method: "GET")
    }

    func post<T: Decodable>(_ path: String, payload: [String: Any]) async throws -> T {
        let body = try JSONSerialization.data(withJSONObject: payload)
        return try await send(path: path, method: "POST", body: body)
    }

    func postEmpty(_ path: String, payload: [String: Any]) async throws {
        let _: EmptyEnvelopeDTO = try await post(path, payload: payload)
    }

    func uploadProof(_ imageData: Data, reason: String) async throws -> String {
        let boundary = "FireCoins-\(UUID().uuidString)"
        var body = Data()
        body.append("--\(boundary)\r\n")
        body.append("Content-Disposition: form-data; name=\"reason\"\r\n\r\n")
        body.append("\(reason)\r\n")
        body.append("--\(boundary)\r\n")
        body.append("Content-Disposition: form-data; name=\"photo\"; filename=\"proof.jpg\"\r\n")
        body.append("Content-Type: image/jpeg\r\n\r\n")
        body.append(imageData)
        body.append("\r\n--\(boundary)--\r\n")

        let data = try await sendData(
            path: "/api/upload-proof",
            method: "POST",
            body: body,
            contentType: "multipart/form-data; boundary=\(boundary)"
        )
        let response = try decoder.decode(ProofUploadEnvelopeDTO.self, from: data)
        return response.proofFileId
    }

    func downloadProof(transactionID: Int64) async throws -> Data {
        try await sendData(path: "/api/proof/\(transactionID)", method: "GET", contentType: nil)
    }

    private func refreshSession() async throws {
        guard let current = tokens else { throw FireCoinsRemoteError.missingSession }
        let body = try JSONSerialization.data(withJSONObject: ["refresh_token": current.refreshToken])
        do {
            let response: RefreshEnvelopeDTO = try await send(
                path: "/api/mobile/refresh",
                method: "POST",
                body: body,
                authenticated: false,
                retryAfterRefresh: false
            )
            let refreshed = MobileTokens(
                accessToken: response.session.accessToken,
                refreshToken: response.session.refreshToken
            )
            try tokenStore.save(refreshed)
            tokens = refreshed
        } catch {
            tokenStore.clear()
            tokens = nil
            throw FireCoinsRemoteError.unauthorized
        }
    }

    private func send<T: Decodable>(
        path: String,
        method: String,
        body: Data? = nil,
        authenticated: Bool = true,
        retryAfterRefresh: Bool = true
    ) async throws -> T {
        let data = try await sendData(
            path: path,
            method: method,
            body: body,
            authenticated: authenticated,
            retryAfterRefresh: retryAfterRefresh
        )
        do {
            return try decoder.decode(T.self, from: data)
        } catch {
            throw FireCoinsRemoteError.invalidServerResponse
        }
    }

    private func sendData(
        path: String,
        method: String,
        body: Data? = nil,
        authenticated: Bool = true,
        retryAfterRefresh: Bool = true,
        contentType: String? = "application/json"
    ) async throws -> Data {
        guard let url = URL(string: path, relativeTo: FireCoinsAPIConfiguration.baseURL) else {
            throw FireCoinsRemoteError.invalidServerResponse
        }
        var request = URLRequest(url: url)
        request.httpMethod = method
        request.httpBody = body
        if let contentType {
            request.setValue(contentType, forHTTPHeaderField: "Content-Type")
        }
        request.setValue("application/json", forHTTPHeaderField: "Accept")

        if authenticated {
            guard let current = tokens else { throw FireCoinsRemoteError.missingSession }
            request.setValue("Bearer \(current.accessToken)", forHTTPHeaderField: "Authorization")
        }

        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await session.data(for: request)
        } catch {
            throw FireCoinsRemoteError.server("Нет соединения с сервером. Проверьте интернет и повторите попытку.")
        }

        guard let http = response as? HTTPURLResponse else {
            throw FireCoinsRemoteError.invalidServerResponse
        }

        if http.statusCode == 401, authenticated, retryAfterRefresh {
            try await refreshSession()
            return try await sendData(
                path: path,
                method: method,
                body: body,
                authenticated: authenticated,
                retryAfterRefresh: false,
                contentType: contentType
            )
        }

        guard (200..<300).contains(http.statusCode) else {
            let envelope = try? decoder.decode(APIErrorEnvelope.self, from: data)
            let message = envelope?.error?.message ?? "Ошибка сервера (\(http.statusCode))."
            if http.statusCode == 401 {
                throw FireCoinsRemoteError.unauthorized
            }
            throw FireCoinsRemoteError.server(message)
        }
        return data
    }
}

private extension Data {
    mutating func append(_ value: String) {
        if let data = value.data(using: .utf8) {
            append(data)
        }
    }
}

private extension Date {
    static func fireCoinsDate(_ value: String) -> Date {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(secondsFromGMT: 0)
        formatter.dateFormat = "yyyy-MM-dd HH:mm:ss"
        if let date = formatter.date(from: value) { return date }
        let iso = ISO8601DateFormatter()
        return iso.date(from: value) ?? .now
    }
}

private extension UUID {
    init(fireCoinsServerID value: Int64) {
        let bytes = withUnsafeBytes(of: UInt64(bitPattern: value).bigEndian, Array.init)
        self.init(uuid: (
            0, 0, 0, 0, 0, 0, 0, 0,
            bytes[0], bytes[1], bytes[2], bytes[3],
            bytes[4], bytes[5], bytes[6], bytes[7]
        ))
    }

    var fireCoinsServerID: Int64? {
        let bytes = withUnsafeBytes(of: uuid, Array.init)
        guard bytes.count == 16, bytes[0..<8].allSatisfy({ $0 == 0 }) else { return nil }
        var value: UInt64 = 0
        for byte in bytes[8..<16] {
            value = (value << 8) | UInt64(byte)
        }
        return Int64(bitPattern: value)
    }

    static func stableFireCoinsID(_ value: String) -> UUID {
        let digest = SHA256.hash(data: Data(value.utf8))
        let bytes = Array(digest.prefix(16))
        return UUID(uuid: (
            bytes[0], bytes[1], bytes[2], bytes[3],
            bytes[4], bytes[5], bytes[6], bytes[7],
            bytes[8], bytes[9], bytes[10], bytes[11],
            bytes[12], bytes[13], bytes[14], bytes[15]
        ))
    }
}

@MainActor
final class RemoteFireCoinsRepository: FireCoinsRepository, FireCoinsSessionManaging {
    private let api = FireCoinsAPIClient()
    private var currentRole: AppRole?
    private var state = FireCoinsSnapshot(
        balance: 0,
        transactions: [],
        withdrawals: [],
        notes: [],
        casinoRounds: []
    )

    func snapshot() -> FireCoinsSnapshot { state }

    func signIn(invitationCode: String) async throws -> AppRole {
        let role = try await api.signIn(invitationCode: invitationCode)
        currentRole = role
        try await refreshSnapshot()
        return role
    }

    func restoreSession() async throws -> AppRole? {
        guard let role = try await api.restoreSession() else {
            currentRole = nil
            return nil
        }
        currentRole = role
        try await refreshSnapshot()
        return role
    }

    func signOut() async {
        await api.signOut()
        currentRole = nil
        state = FireCoinsSnapshot(balance: 0, transactions: [], withdrawals: [], notes: [], casinoRounds: [])
    }

    func refreshSnapshot() async throws {
        guard let role = currentRole else { throw FireCoinsRemoteError.missingSession }

        let dashboard: DashboardEnvelopeDTO = try await api.get("/api/dashboard")
        let history: TransactionsEnvelopeDTO = try await api.get("/api/history?period=all&limit=200")
        let withdrawals: WithdrawalsEnvelopeDTO = try await api.get("/api/withdrawals")
        let notes: NotesEnvelopeDTO = try await api.get("/api/notes?limit=100")

        var rounds: [CasinoRound] = []
        if role == .artemiy {
            let casino: CasinoStatusEnvelopeDTO = try await api.get("/api/casino/status")
            rounds = (casino.casino.rounds ?? []).map {
                CasinoRound(
                    id: UUID(fireCoinsServerID: $0.id),
                    bet: $0.bet,
                    won: $0.won,
                    date: .fireCoinsDate($0.timestamp),
                    payout: $0.payout
                )
            }
        }

        state = FireCoinsSnapshot(
            balance: dashboard.data.balance.balance,
            transactions: history.items.map(Self.mapTransaction),
            withdrawals: withdrawals.items.map(Self.mapWithdrawal),
            notes: notes.items.compactMap(Self.mapNote),
            casinoRounds: rounds
        )
    }

    func loadProof(transactionID: UUID) async throws -> Data {
        guard let serverID = transactionID.fireCoinsServerID else {
            throw FireCoinsRemoteError.invalidIdentifier
        }
        return try await api.downloadProof(transactionID: serverID)
    }

    func applyTransaction(amount: Int, kind: TransactionKind, reason: String, proofImageData: Data?) async throws {
        guard amount > 0 else { throw FireCoinsError.invalidAmount }
        var proofFileID: String?
        if let proofImageData {
            proofFileID = try await api.uploadProof(proofImageData, reason: reason)
        }
        var payload: [String: Any] = ["amount": amount, "reason": reason]
        if let proofFileID { payload["proof_file_id"] = proofFileID }
        let path = kind == .credit ? "/api/add" : "/api/remove"
        try await api.postEmpty(path, payload: payload)
        try await refreshSnapshot()
    }

    func submitDispute(transactionID: UUID) async throws {
        guard let serverID = transactionID.fireCoinsServerID else {
            throw FireCoinsRemoteError.invalidIdentifier
        }
        try await api.postEmpty("/api/dispute", payload: ["transaction_id": serverID])
        try await refreshSnapshot()
    }

    func resolveDispute(transactionID: UUID, approved: Bool) async throws {
        guard let serverID = transactionID.fireCoinsServerID else {
            throw FireCoinsRemoteError.invalidIdentifier
        }
        try await api.postEmpty(
            "/api/disputes/\(serverID)/resolve",
            payload: ["action": approved ? "approve" : "reject"]
        )
        try await refreshSnapshot()
    }

    func requestWithdrawal(amount: Int) async throws {
        guard amount > 0 else { throw FireCoinsError.invalidAmount }
        try await api.postEmpty("/api/withdraw", payload: ["amount": amount])
        try await refreshSnapshot()
    }

    func resolveWithdrawal(requestID: UUID, approved: Bool) async throws {
        guard let serverID = requestID.fireCoinsServerID else {
            throw FireCoinsRemoteError.invalidIdentifier
        }
        try await api.postEmpty(
            "/api/withdrawals/\(serverID)/resolve",
            payload: ["action": approved ? "approve" : "reject"]
        )
        try await refreshSnapshot()
    }

    func playCasino(bet: Int) async throws -> CasinoRound {
        guard bet > 0 else { throw FireCoinsError.invalidAmount }
        let response: CasinoResultEnvelopeDTO = try await api.post("/api/casino", payload: ["amount": bet])
        try await refreshSnapshot()
        let round = state.casinoRounds.first
        return round ?? CasinoRound(
            bet: response.result.bet,
            won: response.result.win,
            payout: response.result.payout
        )
    }

    func sendNote(from role: AppRole, text: String) async throws {
        _ = role
        try await api.postEmpty("/api/note", payload: ["text": text])
        try await refreshSnapshot()
    }

    private static func mapTransaction(_ item: TransactionDTO) -> CoinTransaction {
        let dispute = DisputeStatus(rawValue: item.disputeStatus ?? "none") ?? .none
        return CoinTransaction(
            id: UUID(fireCoinsServerID: item.id),
            amount: item.amount,
            kind: item.type == "add" ? .credit : .debit,
            reason: item.reason,
            date: .fireCoinsDate(item.timestamp),
            proofAvailable: item.hasProof ?? false,
            disputeStatus: dispute
        )
    }

    private static func mapWithdrawal(_ item: WithdrawalDTO) -> WithdrawalRequest {
        WithdrawalRequest(
            id: UUID(fireCoinsServerID: item.id),
            amount: item.amount,
            date: .fireCoinsDate(item.timestamp),
            status: WithdrawalStatus(rawValue: item.status) ?? .pending
        )
    }

    private static func mapNote(_ item: NoteDTO) -> PrivateNote? {
        guard let sender = AppRole(rawValue: item.senderRole) else { return nil }
        let recipient: AppRole = sender == .father ? .artemiy : .father
        return PrivateNote(
            id: UUID.stableFireCoinsID(item.id),
            sender: sender,
            recipient: recipient,
            text: item.text,
            date: .fireCoinsDate(item.timestamp)
        )
    }
}
