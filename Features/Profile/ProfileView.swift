import SwiftUI
import PhotosUI
import UIKit

struct ProfileView: View {
    let role: AppRole
    let onLogout: () -> Void
    @EnvironmentObject private var store: AppStore
    @State private var noteText = ""
    @State private var alertMessage: String?
    @State private var showSettings = false
    @State private var selectedPhoto: PhotosPickerItem?

    private var ownNotes: [PrivateNote] {
        store.snapshot.notes.sorted { $0.date > $1.date }
    }

    var body: some View {
        ZStack {
            FCTheme.background.ignoresSafeArea()
                .onTapGesture { KeyboardDismiss.dismiss() }
            ScrollView(showsIndicators: false) {
                VStack(alignment: .leading, spacing: 21) {
                    VStack(alignment: .leading, spacing: 5) {
                        Text("Профиль")
                            .font(.system(size: 30, weight: .semibold, design: .rounded))
                            .foregroundStyle(FCTheme.text)
                        Text("Настройки аккаунта и связь внутри системы.")
                            .font(.system(size: 13))
                            .foregroundStyle(FCTheme.muted)
                    }

                    HStack(spacing: 14) {
                        ZStack(alignment: .bottomTrailing) {
                            profileAvatar
                                .frame(width: 68, height: 68)
                                .clipShape(Circle())
                                .overlay { Circle().stroke(FCTheme.border, lineWidth: 1) }

                            PhotosPicker(selection: $selectedPhoto, matching: .images, photoLibrary: .shared()) {
                                Image(systemName: "camera.fill")
                                    .font(.system(size: 10, weight: .bold))
                                    .foregroundStyle(Color(hex: 0x211A14))
                                    .frame(width: 25, height: 25)
                                    .background(FCTheme.accentLight, in: Circle())
                                    .overlay { Circle().stroke(FCTheme.background, lineWidth: 2) }
                            }
                            .accessibilityLabel("Изменить аватар")
                            .offset(x: 3, y: 3)
                        }

                        VStack(alignment: .leading, spacing: 4) {
                            Text(role.title)
                                .font(.system(size: 18, weight: .semibold))
                                .foregroundStyle(FCTheme.text)
                            Text(role.subtitle)
                                .font(.system(size: 12))
                                .foregroundStyle(FCTheme.muted)
                            HStack(spacing: 8) {
                                PillLabel(text: "АКТИВНЫЙ АККАУНТ", color: FCTheme.positive)
                                Button {
                                    showSettings = true
                                } label: {
                                    Image(systemName: "gearshape.fill")
                                        .font(.system(size: 15, weight: .semibold))
                                        .foregroundStyle(FCTheme.accentLight)
                                        .frame(width: 32, height: 32)
                                        .background(FCTheme.surfaceElevated, in: Circle())
                                        .overlay { Circle().stroke(FCTheme.border, lineWidth: 1) }
                                }
                                .buttonStyle(.plain)
                                .accessibilityLabel("Настройки профиля")
                            }
                        }
                        Spacer(minLength: 0)
                    }
                    .fcCard()

                    VStack(alignment: .leading, spacing: 13) {
                        SectionHeading(title: "Личные заметки")
                        Text("Сообщение появится в общей ленте заметок на обоих устройствах.")
                            .font(.system(size: 11))
                            .foregroundStyle(FCTheme.muted)
                        TextField("Напиши сообщение…", text: $noteText, axis: .vertical)
                            .font(.system(size: 14))
                            .lineLimit(3...5)
                            .foregroundStyle(FCTheme.text)
                            .padding(14)
                            .background(FCTheme.surfaceElevated, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                            .overlay { RoundedRectangle(cornerRadius: 14, style: .continuous).stroke(FCTheme.border, lineWidth: 1) }
                        PrimaryActionButton(title: "Отправить заметку", systemImage: "paperplane", action: sendNote)
                        if ownNotes.isEmpty {
                            EmptyState(symbol: "bubble.left", title: "Заметок пока нет", subtitle: "Здесь будут ваши сообщения.")
                        } else {
                            ForEach(ownNotes.prefix(5)) { note in
                                VStack(alignment: .leading, spacing: 7) {
                                    HStack {
                                        Text(note.sender.title)
                                            .font(.system(size: 11, weight: .semibold))
                                            .foregroundStyle(FCTheme.accent)
                                        Spacer()
                                        Text(note.date.formatted(date: .abbreviated, time: .shortened))
                                            .font(.system(size: 10))
                                            .foregroundStyle(FCTheme.muted)
                                    }
                                    Text(note.text)
                                        .font(.system(size: 13))
                                        .foregroundStyle(FCTheme.text)
                                        .fixedSize(horizontal: false, vertical: true)
                                }
                                .padding(13)
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .background(FCTheme.surface, in: RoundedRectangle(cornerRadius: 15, style: .continuous))
                            }
                        }
                    }
                    .fcCard()

                    Button(role: .destructive, action: onLogout) {
                        HStack {
                            Image(systemName: "rectangle.portrait.and.arrow.right")
                            Text("Выйти из аккаунта")
                        }
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(FCTheme.negative)
                        .frame(maxWidth: .infinity)
                        .padding(15)
                        .background(FCTheme.negative.opacity(0.08), in: RoundedRectangle(cornerRadius: 15, style: .continuous))
                    }
                    .buttonStyle(.plain)

                    Text("FIRE COINS · V2")
                        .font(.system(size: 9, weight: .medium))
                        .tracking(1.5)
                        .foregroundStyle(FCTheme.muted.opacity(0.6))
                        .frame(maxWidth: .infinity)
                }
                .padding(.horizontal, 20)
                .padding(.top, 22)
                .padding(.bottom, 30)
            }
        }
        .navigationBarHidden(true)
        .sheet(isPresented: $showSettings) {
            ProfileSettingsView(role: role)
                .environmentObject(store)
        }
        .onChange(of: selectedPhoto) { _, item in
            guard let item else { return }
            Task { @MainActor in
                do {
                    guard let data = try await item.loadTransferable(type: Data.self) else {
                        throw ProfilePhotoError.invalid
                    }
                    try await store.uploadAvatar(data)
                    alertMessage = "Аватар обновлён."
                } catch {
                    alertMessage = error.localizedDescription
                }
                selectedPhoto = nil
            }
        }
        .alert("Fire Coins", isPresented: Binding(get: { alertMessage != nil }, set: { if !$0 { alertMessage = nil } })) {
            Button("Понятно", role: .cancel) { alertMessage = nil }
        } message: {
            Text(alertMessage ?? "")
        }
    }

    @ViewBuilder
    private var profileAvatar: some View {
        if let data = store.avatarData, let image = UIImage(data: data) {
            Image(uiImage: image)
                .resizable()
                .scaledToFill()
        } else {
            ZStack {
                FCTheme.surfaceElevated
                Text(role.initials)
                    .font(.system(size: 25, weight: .semibold, design: .rounded))
                    .foregroundStyle(FCTheme.accentLight)
            }
        }
    }

    private func sendNote() {
        Task { @MainActor in
            do {
                try await store.sendNote(from: role, text: noteText)
                noteText = ""
                alertMessage = "Заметка отправлена."
            } catch {
                alertMessage = error.localizedDescription
            }
        }
    }
}

private enum ProfilePhotoError: LocalizedError {
    case invalid
    var errorDescription: String? { "Не удалось прочитать выбранное изображение." }
}

private struct ProfileSettingsView: View {
    let role: AppRole
    @EnvironmentObject private var store: AppStore
    @Environment(\.dismiss) private var dismiss
    @State private var isBusy = false
    @State private var reissuedCode: String?
    @State private var showResetSheet = false
    @State private var showBackendRestartConfirmation = false
    @State private var errorMessage: String?
    @State private var infoMessage: String?

    var body: some View {
        ZStack {
            FCTheme.background.ignoresSafeArea()
            ScrollView(showsIndicators: false) {
                VStack(alignment: .leading, spacing: 18) {
                    HStack {
                        VStack(alignment: .leading, spacing: 5) {
                            Text("Настройки")
                                .font(.system(size: 27, weight: .semibold, design: .rounded))
                                .foregroundStyle(FCTheme.text)
                            Text("Аккаунт · \(role.title)")
                                .font(.system(size: 12))
                                .foregroundStyle(FCTheme.muted)
                        }
                        Spacer()
                        Button("Готово") { dismiss() }
                            .font(.system(size: 13, weight: .semibold))
                            .foregroundStyle(FCTheme.accentLight)
                    }

                    VStack(alignment: .leading, spacing: 12) {
                        SectionHeading(title: "Безопасность аккаунта")
                        Text("Перевыпуск создаёт новый одноразовый код именно для этого аккаунта. Старый код перестаёт работать, но текущая сессия сохраняется.")
                            .font(.system(size: 11))
                            .lineSpacing(3)
                            .foregroundStyle(FCTheme.muted)

                        if let reissuedCode {
                            Text("НОВЫЙ КОД · ПОКАЖЕТСЯ ОДИН РАЗ")
                                .font(.system(size: 9, weight: .bold))
                                .tracking(1.1)
                                .foregroundStyle(FCTheme.muted)
                            Text(reissuedCode)
                                .font(.system(size: 16, weight: .bold, design: .monospaced))
                                .foregroundStyle(FCTheme.accentLight)
                                .textSelection(.enabled)
                            Button {
                                UIPasteboard.general.string = reissuedCode
                                infoMessage = "Код скопирован."
                            } label: {
                                Label("Скопировать код", systemImage: "doc.on.doc")
                                    .font(.system(size: 12, weight: .semibold))
                                    .foregroundStyle(FCTheme.accentLight)
                            }
                            .buttonStyle(.plain)
                        }

                        settingsButton("Перевыпустить мой код", icon: "key.fill", disabled: isBusy) {
                            reissueInvitation()
                        }
                    }
                    .fcCard()

                    VStack(alignment: .leading, spacing: 13) {
                        SectionHeading(title: "Среда приложения")
                        HStack(alignment: .top, spacing: 12) {
                            Image(systemName: "testtube.2")
                                .font(.system(size: 18))
                                .foregroundStyle(FCTheme.accentLight)
                                .frame(width: 38, height: 38)
                                .background(FCTheme.accent.opacity(0.12), in: RoundedRectangle(cornerRadius: 12))
                            VStack(alignment: .leading, spacing: 4) {
                                Text("Тестовый режим")
                                    .font(.system(size: 14, weight: .semibold))
                                    .foregroundStyle(FCTheme.text)
                                Text("Отдельная пустая база. Все операции в приложении изолированы от рабочих данных. Доступно обоим аккаунтам.")
                                    .font(.system(size: 10))
                                    .lineSpacing(3)
                                    .foregroundStyle(FCTheme.muted)
                            }
                            Spacer(minLength: 0)
                            Toggle(
                                "Тестовый режим",
                                isOn: Binding(
                                    get: { store.settings.isSandbox },
                                    set: { enabled in switchSandbox(enabled) }
                                )
                            )
                            .labelsHidden()
                            .tint(FCTheme.negative)
                            .disabled(isBusy)
                        }

                        if store.settings.isSandbox {
                            Text("ТЕСТ РЕЖИМ АКТИВЕН")
                                .font(.system(size: 11, weight: .black))
                                .tracking(1.1)
                                .foregroundStyle(.red)
                        } else {
                            Text("Сейчас используется рабочая база.")
                                .font(.system(size: 10))
                                .foregroundStyle(FCTheme.muted)
                        }
                    }
                    .fcCard()

                    VStack(alignment: .leading, spacing: 12) {
                        HStack(spacing: 8) {
                            Image(systemName: "exclamationmark.triangle.fill")
                                .foregroundStyle(FCTheme.negative)
                            Text("DANGER ZONE")
                                .font(.system(size: 12, weight: .black))
                                .tracking(1.5)
                                .foregroundStyle(FCTheme.negative)
                        }
                        Text("Операции в этом разделе могут привести к полной потере данных.")
                            .font(.system(size: 11))
                            .foregroundStyle(FCTheme.muted)
                        if role == .father {
                            Button {
                                showResetSheet = true
                            } label: {
                                HStack(spacing: 10) {
                                    Image(systemName: "trash.fill")
                                    VStack(alignment: .leading, spacing: 3) {
                                        Text("СБРОС ТЕКУЩЕЙ БД")
                                            .font(.system(size: 12, weight: .bold))
                                        Text("Полное удаление данных текущей среды")
                                            .font(.system(size: 10))
                                            .opacity(0.8)
                                    }
                                    Spacer()
                                    Image(systemName: "chevron.right")
                                }
                                .foregroundStyle(FCTheme.negative)
                                .padding(13)
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .background(FCTheme.negative.opacity(0.07), in: RoundedRectangle(cornerRadius: 13))
                                .overlay {
                                    RoundedRectangle(cornerRadius: 13)
                                        .stroke(FCTheme.negative.opacity(0.75), lineWidth: 1.2)
                                }
                            }
                            .buttonStyle(.plain)
                            .disabled(isBusy)

                            Button {
                                showBackendRestartConfirmation = true
                            } label: {
                                HStack(spacing: 10) {
                                    Image(systemName: "server.rack")
                                    VStack(alignment: .leading, spacing: 3) {
                                        Text("ПЕРЕЗАГРУЗИТЬ BACKEND")
                                            .font(.system(size: 12, weight: .bold))
                                        Text("Мягкий перезапуск серверного API")
                                            .font(.system(size: 10))
                                            .opacity(0.8)
                                    }
                                    Spacer()
                                    Image(systemName: "chevron.right")
                                }
                                .foregroundStyle(FCTheme.negative)
                                .padding(13)
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .background(FCTheme.negative.opacity(0.07), in: RoundedRectangle(cornerRadius: 13))
                                .overlay {
                                    RoundedRectangle(cornerRadius: 13)
                                        .stroke(FCTheme.negative.opacity(0.75), lineWidth: 1.2)
                                }
                            }
                            .buttonStyle(.plain)
                            .disabled(isBusy)
                        } else {
                            Text("Сброс базы и перезапуск Backend доступны только администратору.")
                                .font(.system(size: 10))
                                .foregroundStyle(FCTheme.muted)
                        }
                        Text("В рабочем режиме будут удалены транзакции, заявки и баланс. Операция не создаёт резервную копию и не может быть отменена.")
                            .font(.system(size: 10))
                            .lineSpacing(3)
                            .foregroundStyle(FCTheme.negative.opacity(0.9))
                    }
                    .padding(16)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(FCTheme.surface, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
                    .overlay {
                        RoundedRectangle(cornerRadius: 20, style: .continuous)
                            .stroke(FCTheme.negative, lineWidth: 1.4)
                    }

                    if store.settings.pushConfigured {
                        Label("Push-сервис настроен на сервере", systemImage: "bell.badge.fill")
                            .font(.system(size: 10, weight: .medium))
                            .foregroundStyle(FCTheme.positive)
                    } else {
                        Text("Системные push-уведомления ожидают подключения Firebase и APNs.")
                            .font(.system(size: 10))
                            .foregroundStyle(FCTheme.muted)
                    }

                    if let errorMessage {
                        Text(errorMessage)
                            .font(.system(size: 11))
                            .foregroundStyle(FCTheme.negative)
                    }
                    if let infoMessage {
                        Text(infoMessage)
                            .font(.system(size: 11))
                            .foregroundStyle(FCTheme.positive)
                    }
                    if isBusy {
                        HStack {
                            ProgressView().tint(FCTheme.accent)
                            Text("Выполняется операция…")
                                .font(.system(size: 11))
                                .foregroundStyle(FCTheme.muted)
                        }
                    }
                }
                .padding(20)
                .padding(.bottom, 24)
            }
        }
        .preferredColorScheme(.dark)
        .presentationDetents([.large])
        .presentationDragIndicator(.visible)
        .task { await store.refreshAccountSettings() }
        .sheet(isPresented: $showResetSheet) {
            ResetDatabaseConfirmationView()
                .environmentObject(store)
        }
        .alert("Перезагрузить Backend?", isPresented: $showBackendRestartConfirmation) {
            Button("Отмена", role: .cancel) { }
            Button("Перезагрузить", role: .destructive) {
                restartBackend()
            }
        } message: {
            Text("Серверный API будет перезапущен через несколько секунд. Приложение может временно потерять соединение, затем подключится снова.")
        }
    }

    private func settingsButton(
        _ title: String,
        icon: String,
        disabled: Bool,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            Label(title, systemImage: icon)
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(FCTheme.accentLight)
                .frame(maxWidth: .infinity)
                .padding(13)
                .background(FCTheme.surfaceElevated, in: RoundedRectangle(cornerRadius: 13))
                .overlay { RoundedRectangle(cornerRadius: 13).stroke(FCTheme.border, lineWidth: 1) }
        }
        .buttonStyle(.plain)
        .disabled(disabled)
    }

    private func reissueInvitation() {
        isBusy = true
        errorMessage = nil
        infoMessage = nil
        Task { @MainActor in
            defer { isBusy = false }
            do {
                reissuedCode = try await store.reissueInvitation()
                infoMessage = "Старый код этого аккаунта больше не действует."
            } catch {
                errorMessage = error.localizedDescription
            }
        }
    }

    private func restartBackend() {
        guard !isBusy else { return }
        isBusy = true
        errorMessage = nil
        infoMessage = nil
        Task { @MainActor in
            defer { isBusy = false }
            do {
                try await store.restartBackend()
                infoMessage = "Запрос на перезапуск Backend принят. Соединение восстановится автоматически."
            } catch {
                errorMessage = error.localizedDescription
            }
        }
    }

    private func switchSandbox(_ enabled: Bool) {
        guard !isBusy else { return }
        isBusy = true
        errorMessage = nil
        infoMessage = nil
        Task { @MainActor in
            defer { isBusy = false }
            do {
                try await store.setSandboxMode(enabled)
                infoMessage = enabled
                    ? "Тестовая среда включена. Рабочая база не изменена."
                    : "Тестовые данные удалены. Рабочая среда восстановлена."
            } catch {
                errorMessage = error.localizedDescription
                await store.refreshAccountSettings()
            }
        }
    }
}

private struct ResetDatabaseConfirmationView: View {
    @EnvironmentObject private var store: AppStore
    @Environment(\.dismiss) private var dismiss
    @State private var confirmationText = ""
    @State private var resetToken: String?
    @State private var errorMessage: String?
    @State private var isLoading = false
    @State private var didReset = false

    private var confirmationMatches: Bool {
        confirmationText == "УДАЛИТЬ БД"
    }

    var body: some View {
        ZStack {
            FCTheme.background.ignoresSafeArea()
            VStack(alignment: .leading, spacing: 17) {
                HStack(spacing: 10) {
                    Image(systemName: "exclamationmark.octagon.fill")
                        .font(.system(size: 24))
                        .foregroundStyle(FCTheme.negative)
                    Text("Необратимый сброс")
                        .font(.system(size: 21, weight: .bold, design: .rounded))
                        .foregroundStyle(FCTheme.text)
                }

                Text("Вы действительно хотите полностью удалить текущую базу данных без возможности восстановления?")
                    .font(.system(size: 13))
                    .lineSpacing(4)
                    .foregroundStyle(FCTheme.text)

                Text("Будут удалены записи текущей среды. Рабочая база не затрагивается, если включён тестовый режим.")
                    .font(.system(size: 11))
                    .lineSpacing(3)
                    .foregroundStyle(FCTheme.negative)

                Text("Для подтверждения вручную введите фразу:")
                    .font(.system(size: 11))
                    .foregroundStyle(FCTheme.muted)
                Text("УДАЛИТЬ БД")
                    .font(.system(size: 15, weight: .black, design: .monospaced))
                    .tracking(1.2)
                    .foregroundStyle(FCTheme.negative)
                TextField("Введите УДАЛИТЬ БД", text: $confirmationText)
                    .textInputAutocapitalization(.characters)
                    .autocorrectionDisabled()
                    .font(.system(size: 14, weight: .semibold, design: .monospaced))
                    .foregroundStyle(FCTheme.text)
                    .padding(13)
                    .background(FCTheme.surfaceElevated, in: RoundedRectangle(cornerRadius: 13))
                    .overlay {
                        RoundedRectangle(cornerRadius: 13)
                            .stroke(confirmationMatches ? FCTheme.negative : FCTheme.border, lineWidth: 1)
                    }

                if let errorMessage {
                    Text(errorMessage)
                        .font(.system(size: 11))
                        .foregroundStyle(FCTheme.negative)
                }

                HStack(spacing: 10) {
                    Button("Отмена") { dismiss() }
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(FCTheme.muted)
                        .frame(maxWidth: .infinity)
                        .padding(14)
                        .background(FCTheme.surfaceElevated, in: RoundedRectangle(cornerRadius: 13))
                    Button {
                        executeReset()
                    } label: {
                        HStack {
                            if isLoading { ProgressView().tint(.white) }
                            Text("УДАЛИТЬ БД")
                        }
                        .font(.system(size: 12, weight: .bold))
                        .foregroundStyle(.white)
                        .frame(maxWidth: .infinity)
                        .padding(14)
                        .background(confirmationMatches && resetToken != nil ? FCTheme.negative : FCTheme.negative.opacity(0.3), in: RoundedRectangle(cornerRadius: 13))
                    }
                    .disabled(!confirmationMatches || resetToken == nil || isLoading)
                }
            }
            .padding(22)
        }
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
        .task {
            isLoading = true
            do {
                resetToken = try await store.issueDatabaseResetChallenge()
            } catch {
                errorMessage = error.localizedDescription
            }
            isLoading = false
        }
        .alert("База сброшена", isPresented: $didReset) {
            Button("Понятно") { dismiss() }
        } message: {
            Text("Текущая база очищена. Баланс сброшен до нуля.")
        }
    }

    private func executeReset() {
        guard confirmationMatches, let resetToken else { return }
        isLoading = true
        errorMessage = nil
        Task { @MainActor in
            defer { isLoading = false }
            do {
                try await store.resetCurrentDatabase(resetToken: resetToken, confirmation: confirmationText)
                didReset = true
            } catch {
                errorMessage = error.localizedDescription
                self.resetToken = nil
            }
        }
    }
}
