import SwiftUI
import MobileShieldCore // the shared Kotlin/Native framework (see ../README.md)

/// Manual "check a message" screen — the permission-free path that mirrors the
/// Android message checker. Runs the shared `SmishingClassifier` on-device.
struct MessageCheckView: View {
    @State private var text: String = ""
    @State private var report: SmishingReport?

    private let classifier = SmishingClassifier()

    var body: some View {
        Form {
            Section("Check a message") {
                TextField("Paste SMS text", text: $text, axis: .vertical)
                    .lineLimit(2...6)
                Button("Check") {
                    report = classifier.classify(text: text, sender: nil)
                }
                if let report {
                    resultView(report)
                }
            }
        }
        .navigationTitle("Message check")
    }

    @ViewBuilder
    private func resultView(_ report: SmishingReport) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("\(levelName(report.level)) risk  (score \(report.score))")
                .bold()
                .foregroundColor(color(for: report.level))
            if report.signals.isEmpty {
                Text("No scam signals found. Still be cautious with unexpected messages.")
            } else {
                ForEach(Array(report.signals.enumerated()), id: \.offset) { _, signal in
                    Text("• \(signal.message)")
                }
            }
        }
    }

    private func levelName(_ level: SmishingLevel) -> String {
        switch level {
        case .critical: return "CRITICAL"
        case .high: return "HIGH"
        case .medium: return "MEDIUM"
        default: return "LOW"
        }
    }

    private func color(for level: SmishingLevel) -> Color {
        switch level {
        case .critical, .high: return .red
        case .medium: return .orange
        default: return .green
        }
    }
}
