import SwiftUI
import UIKit


enum KeyboardDismiss {
    static func dismiss() {
        UIApplication.shared.sendAction(
            #selector(UIResponder.resignFirstResponder),
            to: nil,
            from: nil,
            for: nil
        )
    }
}

/// Installs non-blocking keyboard dismissal gestures on the app window.
/// Taps inside text inputs keep their normal focus behavior; a tap elsewhere
/// or a downward swipe resigns the active first responder.
struct KeyboardDismissGesture: UIViewRepresentable {
    func makeCoordinator() -> Coordinator {
        Coordinator()
    }

    func makeUIView(context: Context) -> UIView {
        let view = UIView(frame: .zero)
        view.backgroundColor = .clear
        view.isUserInteractionEnabled = false
        DispatchQueue.main.async {
            context.coordinator.attach(to: view.window)
        }
        return view
    }

    func updateUIView(_ uiView: UIView, context: Context) {
        DispatchQueue.main.async {
            context.coordinator.attach(to: uiView.window)
        }
    }

    static func dismantleUIView(_ uiView: UIView, coordinator: Coordinator) {
        coordinator.detach()
    }

    final class Coordinator: NSObject, UIGestureRecognizerDelegate {
        private weak var attachedWindow: UIWindow?
        private var tapRecognizer: UITapGestureRecognizer?
        private var panRecognizer: UIPanGestureRecognizer?

        func attach(to window: UIWindow?) {
            guard let window, attachedWindow !== window else { return }
            detach()

            let tap = UITapGestureRecognizer(target: self, action: #selector(handleTap))
            tap.cancelsTouchesInView = false
            tap.delaysTouchesBegan = false
            tap.delaysTouchesEnded = false
            tap.delegate = self
            window.addGestureRecognizer(tap)

            let pan = UIPanGestureRecognizer(target: self, action: #selector(handlePan(_:)))
            pan.cancelsTouchesInView = false
            pan.delaysTouchesBegan = false
            pan.delaysTouchesEnded = false
            pan.maximumNumberOfTouches = 1
            pan.delegate = self
            window.addGestureRecognizer(pan)

            attachedWindow = window
            tapRecognizer = tap
            panRecognizer = pan
        }

        func detach() {
            if let tapRecognizer {
                attachedWindow?.removeGestureRecognizer(tapRecognizer)
            }
            if let panRecognizer {
                attachedWindow?.removeGestureRecognizer(panRecognizer)
            }
            tapRecognizer = nil
            panRecognizer = nil
            attachedWindow = nil
        }

        func gestureRecognizer(
            _ gestureRecognizer: UIGestureRecognizer,
            shouldReceive touch: UITouch
        ) -> Bool {
            guard gestureRecognizer === tapRecognizer else { return true }
            return !isTextInput(touch.view)
        }

        func gestureRecognizer(
            _ gestureRecognizer: UIGestureRecognizer,
            shouldRecognizeSimultaneouslyWith otherGestureRecognizer: UIGestureRecognizer
        ) -> Bool {
            true
        }

        @objc private func handleTap() {
            KeyboardDismiss.dismiss()
        }

        @objc private func handlePan(_ recognizer: UIPanGestureRecognizer) {
            guard recognizer.state == .ended else { return }
            let translation = recognizer.translation(in: recognizer.view)
            let velocity = recognizer.velocity(in: recognizer.view)
            if translation.y > 48 && velocity.y > 60 {
                KeyboardDismiss.dismiss()
            }
        }

        private func isTextInput(_ view: UIView?) -> Bool {
            var current = view
            while let candidate = current {
                if candidate is UITextField || candidate is UITextView {
                    return true
                }
                current = candidate.superview
            }
            return false
        }
    }
}

struct FireCoinsMark: View {
    var size: CGFloat = 42

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: size * 0.32, style: .continuous)
                .fill(LinearGradient(colors: [FCTheme.accentLight, FCTheme.accent], startPoint: .topLeading, endPoint: .bottomTrailing))
            Image(systemName: "flame.fill")
                .font(.system(size: size * 0.47, weight: .semibold))
                .foregroundStyle(Color(hex: 0x282018))
                .offset(y: -1)
        }
        .frame(width: size, height: size)
    }
}

struct SectionHeading: View {
    let title: String
    var actionTitle: String? = nil
    var action: (() -> Void)? = nil

    var body: some View {
        HStack(alignment: .firstTextBaseline) {
            Text(title)
                .font(.system(size: 19, weight: .semibold, design: .rounded))
                .foregroundStyle(FCTheme.text)
            Spacer()
            if let actionTitle, let action {
                Button(actionTitle, action: action)
                    .font(.system(size: 13, weight: .medium))
                    .foregroundStyle(FCTheme.accent)
            } else if let actionTitle {
                Text(actionTitle)
                    .font(.system(size: 11, weight: .medium))
                    .foregroundStyle(FCTheme.muted)
            }
        }
    }
}

struct PillLabel: View {
    let text: String
    var color: Color = FCTheme.accent

    var body: some View {
        Text(text)
            .font(.system(size: 11, weight: .semibold))
            .foregroundStyle(color)
            .padding(.horizontal, 10)
            .padding(.vertical, 6)
            .background(color.opacity(0.12), in: Capsule())
    }
}

struct PrimaryActionButton: View {
    let title: String
    var systemImage: String? = nil
    var disabled: Bool = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 9) {
                if let systemImage { Image(systemName: systemImage) }
                Text(title).font(.system(size: 15, weight: .semibold))
            }
            .foregroundStyle(Color(hex: 0x211A14))
            .frame(maxWidth: .infinity)
            .padding(.vertical, 15)
            .background(
                LinearGradient(colors: [FCTheme.accentLight, FCTheme.accent], startPoint: .topLeading, endPoint: .bottomTrailing),
                in: RoundedRectangle(cornerRadius: 15, style: .continuous)
            )
        }
        .buttonStyle(.plain)
        .disabled(disabled)
        .opacity(disabled ? 0.45 : 1)
    }
}

struct EmptyState: View {
    let symbol: String
    let title: String
    let subtitle: String

    var body: some View {
        VStack(spacing: 10) {
            Image(systemName: symbol)
                .font(.system(size: 25, weight: .light))
                .foregroundStyle(FCTheme.muted)
                .frame(width: 58, height: 58)
                .background(FCTheme.surfaceElevated, in: Circle())
            Text(title)
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(FCTheme.text)
            Text(subtitle)
                .font(.system(size: 13))
                .foregroundStyle(FCTheme.muted)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 28)
    }
}

struct TransactionRow: View {
    let transaction: CoinTransaction
    var showDate: Bool = true
    var trailingActionTitle: String? = nil
    var trailingAction: (() -> Void)? = nil

    private var amountColor: Color { transaction.kind == .credit ? FCTheme.positive : FCTheme.negative }

    var body: some View {
        HStack(spacing: 13) {
            ZStack {
                RoundedRectangle(cornerRadius: 13, style: .continuous)
                    .fill(amountColor.opacity(0.12))
                    .frame(width: 43, height: 43)
                Image(systemName: transaction.kind == .credit ? "arrow.down.left" : "arrow.up.right")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(amountColor)
            }
            VStack(alignment: .leading, spacing: 4) {
                Text(transaction.reason)
                    .font(.system(size: 14, weight: .medium))
                    .foregroundStyle(FCTheme.text)
                    .lineLimit(1)
                if showDate {
                    Text(transaction.date.formatted(date: .abbreviated, time: .shortened))
                        .font(.system(size: 11))
                        .foregroundStyle(FCTheme.muted)
                }
                if transaction.disputeStatus != .none {
                    Text(transaction.disputeStatus.title)
                        .font(.system(size: 10, weight: .medium))
                        .foregroundStyle(transaction.disputeStatus == .pending ? FCTheme.accent : FCTheme.muted)
                }
            }
            Spacer(minLength: 4)
            VStack(alignment: .trailing, spacing: 5) {
                Text("\(transaction.kind.symbol)\(transaction.amount)")
                    .font(.system(size: 15, weight: .semibold, design: .rounded).monospacedDigit())
                    .foregroundStyle(amountColor)
                if transaction.proofAvailable {
                    Image(systemName: "paperclip")
                        .font(.system(size: 10))
                        .foregroundStyle(FCTheme.muted)
                }
                if let trailingActionTitle, let trailingAction {
                    Button(trailingActionTitle, action: trailingAction)
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundStyle(FCTheme.accent)
                }
            }
        }
        .padding(.vertical, 7)
    }
}

struct AmountTextField: View {
    let title: String
    @Binding var value: String

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title.uppercased())
                .font(.system(size: 10, weight: .semibold))
                .tracking(1.1)
                .foregroundStyle(FCTheme.muted)
            TextField("0", text: $value)
                .font(.system(size: 22, weight: .semibold, design: .rounded).monospacedDigit())
                .keyboardType(.numberPad)
                .foregroundStyle(FCTheme.text)
                .padding(15)
                .background(FCTheme.surfaceElevated, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                .overlay {
                    RoundedRectangle(cornerRadius: 14, style: .continuous).stroke(FCTheme.border, lineWidth: 1)
                }
        }
    }
}


struct ProofPreviewSheet: View {
    let imageData: Data
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Group {
                if let image = UIImage(data: imageData) {
                    Image(uiImage: image)
                        .resizable()
                        .scaledToFit()
                        .padding()
                } else {
                    EmptyState(symbol: "photo", title: "Фото недоступно", subtitle: "Изображение не удалось открыть.")
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(FCTheme.background)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Готово") { dismiss() }
                        .foregroundStyle(FCTheme.accent)
                }
            }
        }
        .presentationDetents([.medium, .large])
    }
}
