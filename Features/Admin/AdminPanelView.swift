import SwiftUI
import PhotosUI

struct AdminPanelView: View {
    @EnvironmentObject private var store: AppStore
    @Environment(\.dismiss) private var dismiss
    @State private var kind: TransactionKind = .credit
    @State private var amount = ""
    @State private var reason = "Хорошая оценка"
    @State private var customReason = ""
    @State private var selectedPhoto: PhotosPickerItem?
    @State private var proofData: Data?
    @State private var alertMessage: String?
    @State private var selectedProofData: Data?
    @State private var showingProof = false

    private let creditReasons = ["Хорошая оценка", "Победа в конкурсе", "Другая причина"]
    private let debitReasons = ["Неубранные трусы", "Оставленные тапки", "Плохая оценка", "Другая причина"]

    private var pendingDisputes: [CoinTransaction] {
        store.snapshot.transactions.filter { $0.disputeStatus == .pending }
    }

    private var pendingWithdrawals: [WithdrawalRequest] {
        store.snapshot.withdrawals.filter { $0.status == .pending }
    }

    private var requiresProof: Bool {
        kind == .debit && ["Неубранные трусы", "Оставленные тапки"].contains(resolvedReason)
    }

    private var resolvedReason: String {
        reason == "Другая причина" ? customReason.trimmingCharacters(in: .whitespacesAndNewlines) : reason
    }

    var body: some View {
        ZStack {
            FCTheme.background.ignoresSafeArea()
            ScrollView(showsIndicators: false) {
                VStack(alignment: .leading, spacing: 23) {
                    VStack(alignment: .leading, spacing: 5) {
                        Text("Управление")
                            .font(.system(size: 29, weight: .semibold, design: .rounded)).foregroundStyle(FCTheme.text)
                        Text("Администрирование счёта Артемия.")
                            .font(.system(size: 13)).foregroundStyle(FCTheme.muted)
                    }
                    transactionComposer
                    disputeQueue
                    withdrawalQueue
                }
                .padding(.horizontal, 20)
                .padding(.top, 18)
                .padding(.bottom, 35)
            }
        }
        .navigationTitle("Панель управления")
        .navigationBarTitleDisplayMode(.inline)
        .toolbarColorScheme(.dark, for: .navigationBar)
        .toolbarBackground(FCTheme.background, for: .navigationBar)
        .toolbarBackground(.visible, for: .navigationBar)
        .onChange(of: kind) { _, newValue in
            reason = newValue == .credit ? creditReasons[0] : debitReasons[0]
            selectedPhoto = nil
            proofData = nil
        }
        .onChange(of: selectedPhoto) { _, newItem in
            Task {
                proofData = try? await newItem?.loadTransferable(type: Data.self)
            }
        }
        .sheet(isPresented: $showingProof) {
            if let selectedProofData {
                ProofPreviewSheet(imageData: selectedProofData)
            }
        }
        .alert("Fire Coins", isPresented: Binding(get: { alertMessage != nil }, set: { if !$0 { alertMessage = nil } })) {
            Button("Понятно", role: .cancel) { alertMessage = nil }
        } message: { Text(alertMessage ?? "") }
    }

    private var transactionComposer: some View {
        VStack(alignment: .leading, spacing: 16) {
            SectionHeading(title: "Операция")
            HStack(spacing: 7) {
                kindButton("Начислить", value: .credit, symbol: "plus.circle")
                kindButton("Списать", value: .debit, symbol: "minus.circle")
            }
            AmountTextField(title: "Количество баллов", value: $amount)
            HStack(spacing: 8) {
                ForEach([1, 2, 3, 5, 10], id: \.self) { value in
                    Button {
                        amount = String(value)
                    } label: {
                        Text(String(value))
                            .font(.system(size: 12, weight: .semibold).monospacedDigit())
                            .foregroundStyle(amount == String(value) ? Color(hex: 0x211A14) : FCTheme.text)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 10)
                            .background(amount == String(value) ? FCTheme.accent : FCTheme.surfaceElevated, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                    }
                    .buttonStyle(.plain)
                }
            }
            VStack(alignment: .leading, spacing: 8) {
                Text("ПРИЧИНА")
                    .font(.system(size: 10, weight: .semibold)).tracking(1.1).foregroundStyle(FCTheme.muted)
                Menu {
                    ForEach(kind == .credit ? creditReasons : debitReasons, id: \.self) { item in
                        Button(item) { reason = item }
                    }
                } label: {
                    HStack {
                        Text(reason).font(.system(size: 14, weight: .medium)).foregroundStyle(FCTheme.text)
                        Spacer()
                        Image(systemName: "chevron.down").font(.system(size: 11, weight: .semibold)).foregroundStyle(FCTheme.muted)
                    }
                    .padding(15)
                    .background(FCTheme.surfaceElevated, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                    .overlay { RoundedRectangle(cornerRadius: 14, style: .continuous).stroke(FCTheme.border, lineWidth: 1) }
                }
                if reason == "Другая причина" {
                    TextField("Укажи причину", text: $customReason)
                        .font(.system(size: 14)).foregroundStyle(FCTheme.text)
                        .padding(15).background(FCTheme.surfaceElevated, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                }
            }
            if requiresProof {
                VStack(alignment: .leading, spacing: 9) {
                    Text("ФОТОДОКАЗАТЕЛЬСТВО")
                        .font(.system(size: 10, weight: .semibold)).tracking(1.1).foregroundStyle(FCTheme.muted)
                    PhotosPicker(selection: $selectedPhoto, matching: .images) {
                        HStack(spacing: 9) {
                            Image(systemName: proofData == nil ? "camera" : "checkmark.circle.fill")
                            Text(proofData == nil ? "Добавить фотографию" : "Фотография прикреплена")
                        }
                        .font(.system(size: 13, weight: .medium))
                        .foregroundStyle(proofData == nil ? FCTheme.accent : FCTheme.positive)
                        .frame(maxWidth: .infinity)
                        .padding(14)
                        .background(FCTheme.surfaceElevated, in: RoundedRectangle(cornerRadius: 13, style: .continuous))
                        .overlay { RoundedRectangle(cornerRadius: 13, style: .continuous).stroke(FCTheme.border, lineWidth: 1) }
                    }
                }
            }
            PrimaryActionButton(title: kind == .credit ? "Начислить баллы" : "Списать баллы", systemImage: kind == .credit ? "plus" : "minus", action: saveTransaction)
            Text("Операция сразу меняет баланс и появляется в истории.")
                .font(.system(size: 10)).foregroundStyle(FCTheme.muted)
        }
        .fcCard()
    }

    private var disputeQueue: some View {
        VStack(alignment: .leading, spacing: 12) {
            SectionHeading(title: "Оспаривания", actionTitle: "\(pendingDisputes.count) ожидают")
            if pendingDisputes.isEmpty {
                EmptyState(symbol: "checkmark.seal", title: "Нет открытых споров", subtitle: "Когда пользователь оспорит операцию, она появится здесь.")
                    .fcCard(padding: 10)
            } else {
                ForEach(pendingDisputes) { transaction in
                    VStack(alignment: .leading, spacing: 11) {
                        TransactionRow(transaction: transaction, showDate: true)
                            .contentShape(Rectangle())
                            .onTapGesture {
                                if let proof = transaction.proofImageData {
                                    selectedProofData = proof
                                    showingProof = true
                                }
                            }
                        HStack(spacing: 9) {
                            smallAction("Отклонить", color: FCTheme.negative) { resolveDispute(transaction, approved: false) }
                            smallAction("Одобрить возврат", color: FCTheme.positive) { resolveDispute(transaction, approved: true) }
                        }
                    }
                    .padding(13)
                    .background(FCTheme.surface, in: RoundedRectangle(cornerRadius: 17, style: .continuous))
                }
            }
        }
    }

    private var withdrawalQueue: some View {
        VStack(alignment: .leading, spacing: 12) {
            SectionHeading(title: "Запросы на вывод", actionTitle: "\(pendingWithdrawals.count) ожидают")
            if pendingWithdrawals.isEmpty {
                EmptyState(symbol: "wallet.pass", title: "Нет активных заявок", subtitle: "Новые запросы на вывод появятся здесь.")
                    .fcCard(padding: 10)
            } else {
                ForEach(pendingWithdrawals) { request in
                    VStack(alignment: .leading, spacing: 10) {
                        HStack {
                            VStack(alignment: .leading, spacing: 3) {
                                Text("\(request.amount) FC").font(.system(size: 18, weight: .semibold, design: .rounded)).foregroundStyle(FCTheme.text)
                                Text(request.date.formatted(date: .abbreviated, time: .shortened)).font(.system(size: 10)).foregroundStyle(FCTheme.muted)
                            }
                            Spacer()
                            PillLabel(text: "Ожидает", color: FCTheme.accent)
                        }
                        HStack(spacing: 9) {
                            smallAction("Отклонить", color: FCTheme.negative) { resolveWithdrawal(request, approved: false) }
                            smallAction("Одобрить", color: FCTheme.positive) { resolveWithdrawal(request, approved: true) }
                        }
                    }
                    .padding(13)
                    .background(FCTheme.surface, in: RoundedRectangle(cornerRadius: 17, style: .continuous))
                }
            }
        }
    }

    private func kindButton(_ title: String, value: TransactionKind, symbol: String) -> some View {
        Button {
            kind = value
        } label: {
            Label(title, systemImage: symbol)
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(kind == value ? Color(hex: 0x211A14) : FCTheme.muted)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 12)
                .background(kind == value ? FCTheme.accent : FCTheme.surfaceElevated, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
        }
        .buttonStyle(.plain)
    }

    private func smallAction(_ title: String, color: Color, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title)
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(color)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 11)
                .background(color.opacity(0.1), in: RoundedRectangle(cornerRadius: 11, style: .continuous))
        }
        .buttonStyle(.plain)
    }

    private func saveTransaction() {
        guard let value = Int(amount), value > 0 else {
            alertMessage = FireCoinsError.invalidAmount.localizedDescription
            return
        }
        let transactionKind = kind
        let transactionReason = resolvedReason
        let transactionProof = proofData
        Task { @MainActor in
            do {
                try await store.applyTransaction(amount: value, kind: transactionKind, reason: transactionReason, proofImageData: transactionProof)
                amount = ""
                customReason = ""
                proofData = nil
                selectedPhoto = nil
                alertMessage = transactionKind == .credit ? "Баллы начислены." : "Баллы списаны."
            } catch {
                alertMessage = error.localizedDescription
            }
        }
    }

    private func resolveDispute(_ transaction: CoinTransaction, approved: Bool) {
        Task { @MainActor in
            do {
                try await store.resolveDispute(transactionID: transaction.id, approved: approved)
                alertMessage = approved ? "Спор одобрен. В истории создана обратная операция." : "Спор отклонён."
            } catch {
                alertMessage = error.localizedDescription
            }
        }
    }

    private func resolveWithdrawal(_ request: WithdrawalRequest, approved: Bool) {
        Task { @MainActor in
            do {
                try await store.resolveWithdrawal(requestID: request.id, approved: approved)
                alertMessage = approved ? "Заявка одобрена, баллы списаны." : "Заявка отклонена."
            } catch {
                alertMessage = error.localizedDescription
            }
        }
    }
}
