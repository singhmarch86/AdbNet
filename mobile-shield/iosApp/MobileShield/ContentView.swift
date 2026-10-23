import SwiftUI
import MobileShieldCore // the shared Kotlin/Native framework (see README)

/// UPI link checker driven by the shared `UpiLinkAnalyzer`. The content-filter
/// toggle installs the DNS proxy via `NEDNSProxyManager` (wired in the README).
struct ContentView: View {
    @State private var link: String = ""
    @State private var report: UpiRiskReport?

    private let analyzer = UpiLinkAnalyzer()

    var body: some View {
        NavigationView {
            Form {
                Section("Check a UPI link") {
                    TextField("upi://pay?...", text: $link)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                    Button("Check") {
                        report = analyzer.analyze(raw: link.trimmingCharacters(in: .whitespaces))
                    }
                    if let report {
                        riskView(report)
                    }
                }
            }
            .navigationTitle("Mobile Shield")
        }
    }

    @ViewBuilder
    private func riskView(_ report: UpiRiskReport) -> some View {
        if !report.isUpiLink {
            Text("Not a UPI link.").bold()
        } else {
            VStack(alignment: .leading, spacing: 6) {
                Text("\(levelName(report.level)) risk  (score \(report.score))")
                    .bold()
                    .foregroundColor(color(for: report.level))
                if report.flags.isEmpty {
                    Text("No risk signals found. Still confirm the payee before paying.")
                } else {
                    ForEach(Array(report.flags.enumerated()), id: \.offset) { _, flag in
                        Text("• \(flag.message)")
                    }
                }
            }
        }
    }

    private func levelName(_ level: RiskLevel) -> String {
        switch level {
        case .critical: return "CRITICAL"
        case .high: return "HIGH"
        case .medium: return "MEDIUM"
        default: return "LOW"
        }
    }

    private func color(for level: RiskLevel) -> Color {
        switch level {
        case .critical, .high: return .red
        case .medium: return .orange
        default: return .green
        }
    }
}
