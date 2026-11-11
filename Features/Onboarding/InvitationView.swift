import SwiftUI

struct InvitationView: View {
    let onUnlock: (String) async throws -> Void
    @State private var invitationCode = ""
    @State private var errorMessage: String?
    @State private var isSubmitting = false
    @FocusState private var isCodeFocused: Bool

    var body: some View {
        ZStack {
            FCTheme.background.ignoresSafeArea()
                .onTapGesture { KeyboardDismiss.dismiss() }
            RadialGradient(colors: [FCTheme.accent.opacity(0.13), .clear], center: .top, startRadius: 15, endRadius: 370)
                .ignoresSafeArea()

            VStack(alignment: .leading, spacing: 0) {
                Spacer(minLength: 55)
                HStack(spacing: 13) {
                    FireCoinsMark(size: 50)
                    VStack(alignment: .leading, spacing: 3) {
                        Text("FIRE COINS")
                            .font(.system(size: 13, weight: .bold, design: .rounded))
                            .tracking(2.2)
                            .foregroundStyle(FCTheme.accent)
                        Text("Личный счёт")
                            .font(.system(size: 12, weight: .medium))
                            .foregroundStyle(FCTheme.muted)
                    }
                }

                Spacer().frame(height: 54)
                Text("Свои правила.\nСвоя система.")
                    .font(.system(size: 36, weight: .semibold, design: .rounded))
                    .tracking(-1.2)
                    .foregroundStyle(FCTheme.text)
                    .fixedSize(horizontal: false, vertical: true)
                Text("Закрытое пространство для двух аккаунтов. Вход доступен только по персональному приглашению.")
                    .font(.system(size: 15))
                    .lineSpacing(5)
                    .foregroundStyle(FCTheme.muted)
                    .padding(.top, 13)

                Spacer().frame(height: 38)
                VStack(alignment: .leading, spacing: 11) {
                    Text("КОД ПРИГЛАШЕНИЯ")
                        .font(.system(size: 10, weight: .bold))
                        .tracking(1.4)
                        .foregroundStyle(FCTheme.muted)
                    HStack(spacing: 10) {
                        Image(systemName: "key.horizontal")
                            .foregroundStyle(FCTheme.accent)
                        TextField("FC-XXXX-XXXX-XXXX-XXXX", text: $invitationCode)
                            .font(.system(size: 15, weight: .semibold, design: .monospaced))
                            .textInputAutocapitalization(.characters)
                            .autocorrectionDisabled()
                            .focused($isCodeFocused)
                            .submitLabel(.go)
                            .onSubmit(unlock)
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 17)
                    .background(FCTheme.surface, in: RoundedRectangle(cornerRadius: 15, style: .continuous))
                    .overlay { RoundedRectangle(cornerRadius: 15, style: .continuous).stroke(FCTheme.border, lineWidth: 1) }

                    if let errorMessage {
                        Text(errorMessage)
                            .font(.system(size: 12, weight: .medium))
                            .foregroundStyle(FCTheme.negative)
                    }
                }

                PrimaryActionButton(title: isSubmitting ? "Проверяем…" : "Продолжить", systemImage: "arrow.right", disabled: isSubmitting, action: unlock)
                    .padding(.top, 17)

                Spacer()
                HStack(spacing: 6) {
                    Image(systemName: "lock.fill")
                    Text("Доступ только по приглашению")
                }
                .font(.system(size: 11, weight: .medium))
                .foregroundStyle(FCTheme.muted.opacity(0.8))
                .frame(maxWidth: .infinity)
                .padding(.bottom, 22)
            }
            .padding(.horizontal, 26)
        }
    }

    private func unlock() {
        let code = invitationCode.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        guard !code.isEmpty else {
            errorMessage = "Введите персональный код приглашения."
            return
        }
        errorMessage = nil
        isCodeFocused = false
        isSubmitting = true
        Task { @MainActor in
            defer { isSubmitting = false }
            do {
                try await onUnlock(code)
            } catch {
                errorMessage = error.localizedDescription
            }
        }
    }
}
