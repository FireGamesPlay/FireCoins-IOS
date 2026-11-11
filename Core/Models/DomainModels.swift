import Foundation

enum AppRole: String, CaseIterable, Identifiable, Equatable {
    case artemiy
    case father

    var id: String { rawValue }

    var title: String {
        switch self {
        case .artemiy: return "Артемий"
        case .father: return "Макс"
        }
    }

    var subtitle: String {
        switch self {
        case .artemiy: return "Участник"
        case .father: return "Администратор"
        }
    }

    var initials: String {
        switch self {
        case .artemiy: return "А"
        case .father: return "М"
        }
    }
}

enum TransactionKind: String, Equatable {
    case credit
    case debit

    var symbol: String { self == .credit ? "+" : "−" }
    var tintName: String { self == .credit ? "positive" : "negative" }
}

enum DisputeStatus: String, Equatable {
    case none
    case pending
    case approved
    case rejected

    var title: String {
        switch self {
        case .none: return ""
        case .pending: return "На рассмотрении"
        case .approved: return "Баллы возвращены"
        case .rejected: return "Отклонено"
        }
    }
}

struct CoinTransaction: Identifiable, Equatable {
    let id: UUID
    let amount: Int
    let kind: TransactionKind
    let reason: String
    let date: Date
    var proofImageData: Data?
    var proofAvailable: Bool
    var disputeStatus: DisputeStatus

    init(
        id: UUID = UUID(),
        amount: Int,
        kind: TransactionKind,
        reason: String,
        date: Date = .now,
        proofImageData: Data? = nil,
        proofAvailable: Bool = false,
        disputeStatus: DisputeStatus = .none
    ) {
        self.id = id
        self.amount = amount
        self.kind = kind
        self.reason = reason
        self.date = date
        self.proofImageData = proofImageData
        self.proofAvailable = proofAvailable || proofImageData != nil
        self.disputeStatus = disputeStatus
    }

    var signedAmount: Int { kind == .credit ? amount : -amount }
}

enum WithdrawalStatus: String, Equatable {
    case pending
    case approved
    case rejected

    var title: String {
        switch self {
        case .pending: return "Ожидает решения"
        case .approved: return "Одобрено"
        case .rejected: return "Отклонено"
        }
    }
}

struct WithdrawalRequest: Identifiable, Equatable {
    let id: UUID
    let amount: Int
    let date: Date
    var status: WithdrawalStatus

    init(id: UUID = UUID(), amount: Int, date: Date = .now, status: WithdrawalStatus = .pending) {
        self.id = id
        self.amount = amount
        self.date = date
        self.status = status
    }
}

struct PrivateNote: Identifiable, Equatable {
    let id: UUID
    let sender: AppRole
    let recipient: AppRole
    let text: String
    let date: Date

    init(id: UUID = UUID(), sender: AppRole, recipient: AppRole, text: String, date: Date = .now) {
        self.id = id
        self.sender = sender
        self.recipient = recipient
        self.text = text
        self.date = date
    }
}

struct CasinoRound: Identifiable, Equatable {
    let id: UUID
    let bet: Int
    let won: Bool
    let date: Date
    let payout: Int

    init(id: UUID = UUID(), bet: Int, won: Bool, date: Date = .now, payout: Int) {
        self.id = id
        self.bet = bet
        self.won = won
        self.date = date
        self.payout = payout
    }
}

struct FireCoinsSnapshot {
    var balance: Int
    var transactions: [CoinTransaction]
    var withdrawals: [WithdrawalRequest]
    var notes: [PrivateNote]
    var casinoRounds: [CasinoRound]
}

enum HistoryPeriod: String, CaseIterable, Identifiable, Equatable {
    case day = "День"
    case week = "Неделя"
    case month = "Месяц"
    case all = "Всё время"

    var id: String { rawValue }

    func includes(_ date: Date, now: Date = .now) -> Bool {
        let calendar = Calendar.current
        switch self {
        case .day:
            return date >= (calendar.date(byAdding: .day, value: -1, to: now) ?? now)
        case .week:
            return date >= (calendar.date(byAdding: .day, value: -7, to: now) ?? now)
        case .month:
            return date >= (calendar.date(byAdding: .day, value: -30, to: now) ?? now)
        case .all:
            return true
        }
    }
}

enum FireCoinsError: LocalizedError, Equatable {
    case invalidAmount
    case insufficientBalance
    case weeklyCasinoLimit
    case transactionUnavailable
    case requestUnavailable
    case proofRequired
    case emptyText

    var errorDescription: String? {
        switch self {
        case .invalidAmount: return "Введите положительное целое число."
        case .insufficientBalance: return "На балансе недостаточно баллов."
        case .weeklyCasinoLimit: return "На этой неделе уже использованы оба запуска."
        case .transactionUnavailable: return "Эту операцию уже нельзя оспорить."
        case .requestUnavailable: return "Заявка уже обработана или не найдена."
        case .proofRequired: return "Для этой причины нужно приложить фотографию."
        case .emptyText: return "Поле не должно быть пустым."
        }
    }
}
