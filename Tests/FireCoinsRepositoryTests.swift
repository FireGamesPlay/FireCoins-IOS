import XCTest
@testable import FireCoins

final class FireCoinsRepositoryTests: XCTestCase {
    @MainActor
    func testCreditChangesBalanceAndAddsLedgerEntry() async throws {
        let repository = InMemoryFireCoinsRepository()
        let initialBalance = repository.snapshot().balance

        try await repository.applyTransaction(amount: 12, kind: .credit, reason: "Хорошая оценка", proofImageData: nil)

        XCTAssertEqual(repository.snapshot().balance, initialBalance + 12)
        XCTAssertEqual(repository.snapshot().transactions.first?.signedAmount, 12)
    }

    @MainActor
    func testWithdrawalDebitsOnlyAfterApproval() async throws {
        let repository = InMemoryFireCoinsRepository()
        let initialBalance = repository.snapshot().balance
        try await repository.requestWithdrawal(amount: 100)

        let request = try XCTUnwrap(repository.snapshot().withdrawals.first)
        XCTAssertEqual(repository.snapshot().balance, initialBalance)

        try await repository.resolveWithdrawal(requestID: request.id, approved: true)
        XCTAssertEqual(repository.snapshot().balance, initialBalance - 100)
        XCTAssertEqual(repository.snapshot().withdrawals.first?.status, .approved)
    }

    @MainActor
    func testApprovedDisputeCreatesCompensatingEntry() async throws {
        let repository = InMemoryFireCoinsRepository()
        let original = try XCTUnwrap(repository.snapshot().transactions.first)
        let initialBalance = repository.snapshot().balance

        try await repository.submitDispute(transactionID: original.id)
        try await repository.resolveDispute(transactionID: original.id, approved: true)

        XCTAssertEqual(repository.snapshot().balance, initialBalance - original.signedAmount)
        XCTAssertEqual(repository.snapshot().transactions.first?.reason, "Возврат по спору: \(original.reason)")
        XCTAssertEqual(repository.snapshot().transactions.first(where: { $0.id == original.id })?.disputeStatus, .approved)
    }

    @MainActor
    func testPhotoIsRequiredForEvidencePenalties() async throws {
        let repository = InMemoryFireCoinsRepository()

        do {
            try await repository.applyTransaction(amount: 5, kind: .debit, reason: "Оставленные тапки", proofImageData: nil)
            XCTFail("Penalty without a photo should be rejected")
        } catch {
            XCTAssertEqual(error as? FireCoinsError, .proofRequired)
        }

        try await repository.applyTransaction(amount: 5, kind: .debit, reason: "Оставленные тапки", proofImageData: Data([1, 2, 3]))
        XCTAssertEqual(repository.snapshot().transactions.first?.reason, "Оставленные тапки")
    }

    @MainActor
    func testAdministrativeDebitCanProduceNegativeBalance() async throws {
        let repository = InMemoryFireCoinsRepository()
        try await repository.applyTransaction(amount: 2_000, kind: .debit, reason: "Штраф", proofImageData: nil)
        XCTAssertEqual(repository.snapshot().balance, -760)
    }

    @MainActor
    func testCasinoAllowsOnlyTwoRoundsInSevenDays() async throws {
        let repository = InMemoryFireCoinsRepository()
        _ = try await repository.playCasino(bet: 1)
        _ = try await repository.playCasino(bet: 1)

        do {
            _ = try await repository.playCasino(bet: 1)
            XCTFail("Third weekly spin should be blocked")
        } catch {
            XCTAssertEqual(error as? FireCoinsError, .weeklyCasinoLimit)
        }
    }
}
