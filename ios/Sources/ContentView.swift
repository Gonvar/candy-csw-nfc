import SwiftUI

struct ContentView: View {
    @StateObject private var nfc = NFCManager()
    @State private var rawHex = "00 A4 04 00 07 D2 76 00 00 85 01 01 00"

    var body: some View {
        NavigationStack {
            VStack(spacing: 14) {

                ScrollViewReader { proxy in
                    ScrollView {
                        Text(nfc.log)
                            .font(.system(.footnote, design: .monospaced))
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .textSelection(.enabled)
                            .padding(8)
                            .id("logEnd")
                    }
                    .background(Color(.secondarySystemBackground))
                    .clipShape(RoundedRectangle(cornerRadius: 12))
                    .onChange(of: nfc.log) { _ in
                        proxy.scrollTo("logEnd", anchor: .bottom)
                    }
                }

                Button(action: nfc.dump) {
                    Label("Dump cycle data (read tag)", systemImage: "arrow.down.doc.fill")
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 6)
                }
                .buttonStyle(.borderedProminent)

                VStack(alignment: .leading, spacing: 6) {
                    Text("Raw APDU (hex) — advanced").font(.caption).foregroundStyle(.secondary)
                    TextField("hex bytes", text: $rawHex, axis: .vertical)
                        .font(.system(.footnote, design: .monospaced))
                        .textFieldStyle(.roundedBorder)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.characters)
                    Button("Send raw APDU") {
                        let bytes = parseHex(rawHex)
                        if bytes.isEmpty { nfc.appendLog("⚠️ no valid hex bytes") }
                        else { nfc.sendRaw([bytes]) }
                    }
                    .buttonStyle(.bordered)
                }

                HStack {
                    Button("Clear log") { nfc.log = "" }
                    Spacer()
                    ShareLink(item: nfc.log) {
                        Label("Share log", systemImage: "square.and.arrow.up")
                    }
                }
                .font(.subheadline)
            }
            .padding()
            .navigationTitle("Candy Control")
        }
    }

    /// Accepts "00 A4 04 ..", "00A404..", commas, or 0x-prefixed bytes.
    private func parseHex(_ s: String) -> [UInt8] {
        let cleaned = s.replacingOccurrences(of: "0x", with: " ")
                       .replacingOccurrences(of: "0X", with: " ")
        let tokens = cleaned.split(whereSeparator: { " ,\n\t".contains($0) }).map(String.init)

        // Single contiguous string of hex pairs.
        if tokens.count == 1, let t = tokens.first, t.count % 2 == 0, !t.isEmpty {
            var out: [UInt8] = []
            var idx = t.startIndex
            while idx < t.endIndex {
                let next = t.index(idx, offsetBy: 2)
                guard let b = UInt8(t[idx..<next], radix: 16) else { return [] }
                out.append(b); idx = next
            }
            return out
        }

        // Space/comma separated tokens.
        var out: [UInt8] = []
        for tok in tokens {
            guard let b = UInt8(tok, radix: 16) else { return [] }
            out.append(b)
        }
        return out
    }
}

#Preview {
    ContentView()
}
