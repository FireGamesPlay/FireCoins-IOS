import SwiftUI
import UIKit

struct ProfileView: View {
    let role: AppRole
    let onLogout: () -> Void
    @EnvironmentObject private var store: AppStore
    @State private var noteText = ""
    @State private var alertMessage: String?
    @State private var showSettings = false

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
                        profileAvatar
                            .frame(width: 68, height: 68)
                            .clipShape(Circle())
                            .overlay { Circle().stroke(FCTheme.border, lineWidth: 1) }

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
        .alert("Fire Coins", isPresented: Binding(get: { alertMessage != nil }, set: { if !$0 { alertMessage = nil } })) {
            Button("Понятно", role: .cancel) { alertMessage = nil }
        } message: {
            Text(alertMessage ?? "")
        }
    }

    @ViewBuilder
    private var profileAvatar: some View {
        ZStack {
            FCTheme.surfaceElevated
            Text(role.initials)
                .font(.system(size: 25, weight: .semibold, design: .rounded))
                .foregroundStyle(FCTheme.accentLight)
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
