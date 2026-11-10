import SwiftUI

struct HistoryView: View {
    let role: AppRole
    @EnvironmentObject private var store: AppStore
    @State private var period: HistoryPeriod = .all
    @State private var alertMessage: String?
    @State private var selectedProof: Data?
    @State private var showingProof = false

    private var filteredTransactions: [CoinTransaction] {
        store.snapshot.transactions
            .filter { period.includes($0.date) }
            .sorted { $0.date > $1.date }
    }

    private var disputableIDs: Set<UUID> {
        Set(store.snapshot.transactions.sorted { $0.date > $1.date }.prefix(10).filter { $0.disputeStatus == .none }.map(\.id))
    }

    var body: some View {
        ZStack {
            FCTheme.background.ignoresSafeArea()
            ScrollView(showsIndicators: false) {
                VStack(alignment: .leading, spacing: 20) {
                    VStack(alignment: .leading, spacing: 5) {
                        Text("История")
                            .font(.system(size: 30, weight: .semibold, design: .rounded))
                            .foregroundStyle(FCTheme.text)
                        Text("Каждое начисление и списание — в одном месте.")
                            .font(.system(size: 13))
                            .foregroundStyle(FCTheme.muted)
                    }
                    periodPicker
                    VStack(spacing: 2) {
                        if filteredTransactions.isEmpty {
                            EmptyState(symbol: "clock", title: "Операций нет", subtitle: "За выбранный период записей пока нет.")
                        } else {
                            ForEach(filteredTransactions) { transaction in
                                TransactionRow(
                                    transaction: transaction,
                                    trailingActionTitle: role == .artemiy && disputableIDs.contains(transaction.id) ? "Оспорить" : nil,
                                    trailingAction: { dispute(transaction) }
                                )
                                .contentShape(Rectangle())
                                .onTapGesture {
                                    if let proof = transaction.proofImageData {
                                        selectedProof = proof
                                        showingProof = true
                                    }
                                }
                                if transaction.id != filteredTransactions.last?.id {
                                    Divider().overlay(FCTheme.border).padding(.leading, 55)
                                }
                            }
                        }
                    }
                    .padding(.horizontal, 15)
                    .padding(.vertical, 8)
                    .background(FCTheme.surface, in: RoundedRectangle(cornerRadius: 21, style: .continuous))
                    .overlay { RoundedRectangle(cornerRadius: 21, style: .continuous).stroke(FCTheme.border, lineWidth: 1) }
                    Text("Нажми на операцию с иконкой скрепки, чтобы открыть приложенное фото.")
                        .font(.system(size: 11))
                        .foregroundStyle(FCTheme.muted)
                }
                .padding(.horizontal, 20)
                .padding(.top, 22)
                .padding(.bottom, 30)
            }
        }
        .navigationBarHidden(true)
        .alert("Fire Coins", isPresented: Binding(get: { alertMessage != nil }, set: { if !$0 { alertMessage = nil } })) {
            Button("Понятно", role: .cancel) { alertMessage = nil }
        } message: { Text(alertMessage ?? "") }
        .sheet(isPresented: $showingProof) {
            if let selectedProof {
                ProofPreviewSheet(imageData: selectedProof)
            }
        }
    }

    private var periodPicker: some View {
        HStack(spacing: 5) {
            ForEach(HistoryPeriod.allCases) { item in
                Button {
                    withAnimation(.easeInOut(duration: 0.18)) { period = item }
                } label: {
                    Text(item.rawValue)
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundStyle(period == item ? Color(hex: 0x211A14) : FCTheme.muted)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 11)
                        .background(period == item ? FCTheme.accent : Color.clear, in: Capsule())
                }
                .buttonStyle(.plain)
            }
        }
        .padding(5)
        .background(FCTheme.surface, in: Capsule())
        .overlay { Capsule().stroke(FCTheme.border, lineWidth: 1) }
    }

    private func dispute(_ transaction: CoinTransaction) {
        Task { @MainActor in
            do {
                try await store.submitDispute(transactionID: transaction.id)
                alertMessage = "Операция отправлена на рассмотрение."
            } catch {
                alertMessage = error.localizedDescription
            }
        }
    }
}
