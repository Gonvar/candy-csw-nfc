import Foundation
@preconcurrency import CoreNFC

/// Talks to the Candy washer's ST M24SR (ISO 14443-4 / ISO 7816) tag via raw APDUs.
///
/// Phase 1 goal: DUMP the NDEF file so we can reverse the cycle byte format.
/// The same `transmit` path will later send STORE_PROGRAM_CYCLE / START_PROGRAM_CYCLE.
///
/// Core NFC's tag/session types aren't `Sendable`. We run a single session at a time and
/// marshal every UI mutation back to the main thread, so `@unchecked Sendable` is safe here.
final class NFCManager: NSObject, ObservableObject, @unchecked Sendable {

    @Published var log: String = "Ready. Load a program in the Candy app, then tap Dump.\n"

    private var session: NFCTagReaderSession?
    private var pendingJob: Job = .dump

    enum Job {
        case dump
        case rawAPDUs([[UInt8]])
    }

    /// NFC Forum NDEF Tag Application AID (declared in Info.plist select-identifiers).
    private let aidNDEF: [UInt8] = [0xD2, 0x76, 0x00, 0x00, 0x85, 0x01, 0x01]
    /// Standard NDEF file identifier on a Type 4 tag.
    private let efNDEF: [UInt8] = [0xE1, 0x04]

    // MARK: - Logging (always on main)

    func appendLog(_ s: String) {
        if Thread.isMainThread {
            log += s + "\n"
        } else {
            DispatchQueue.main.async { self.log += s + "\n" }
        }
    }

    func clear() { log = "" }

    // MARK: - Public actions

    func dump() {
        begin(.dump, prompt: "Hold the top of your iPhone to the washer's NFC pad")
    }

    func sendRaw(_ apdus: [[UInt8]]) {
        begin(.rawAPDUs(apdus), prompt: "Hold the top of your iPhone to the NFC pad")
    }

    private func begin(_ job: Job, prompt: String) {
        guard NFCTagReaderSession.readingAvailable else {
            appendLog("⚠️ NFC not available on this device.")
            return
        }
        pendingJob = job
        session = NFCTagReaderSession(pollingOption: .iso14443, delegate: self, queue: nil)
        session?.alertMessage = prompt
        session?.begin()
    }
}

// MARK: - NFCTagReaderSessionDelegate

extension NFCManager: NFCTagReaderSessionDelegate {

    func tagReaderSessionDidBecomeActive(_ session: NFCTagReaderSession) {}

    func tagReaderSession(_ session: NFCTagReaderSession, didInvalidateWithError error: Error) {
        appendLog("Session closed: \(error.localizedDescription)")
    }

    func tagReaderSession(_ session: NFCTagReaderSession, didDetect tags: [NFCTag]) {
        guard let tag = tags.first, case let .iso7816(iso) = tag else {
            session.invalidate(errorMessage: "Unexpected tag type (not ISO 7816).")
            return
        }
        // Core NFC objects aren't Sendable; we own their lifetime for this session.
        nonisolated(unsafe) let isoTag = iso
        nonisolated(unsafe) let sess = session
        nonisolated(unsafe) let detected = tag
        Task {
            do {
                try await sess.connect(to: detected)
            } catch {
                sess.invalidate(errorMessage: "Connect failed: \(error.localizedDescription)")
                return
            }
            await self.run(isoTag, session: sess)
        }
    }
}

// MARK: - APDU flows

extension NFCManager {

    private struct APDUResult {
        let data: Data
        let sw1: UInt8
        let sw2: UInt8
        var ok: Bool { sw1 == 0x90 && sw2 == 0x00 }
    }

    private func run(_ tag: NFCISO7816Tag, session: NFCTagReaderSession) async {
        switch pendingJob {
        case .dump:
            await dumpFlow(tag, session: session)
        case .rawAPDUs(let list):
            for raw in list { _ = await transmit(raw, to: tag, label: "APDU") }
            session.alertMessage = "Done ✓"
            session.invalidate()
        }
    }

    private func dumpFlow(_ tag: NFCISO7816Tag, session: NFCTagReaderSession) async {
        appendLog("\n— DUMP \(timestamp()) —")

        // 1. SELECT NDEF application (Core NFC may have pre-selected it; harmless to repeat).
        let selApp: [UInt8] = [0x00, 0xA4, 0x04, 0x00, 0x07] + aidNDEF + [0x00]
        guard let r1 = await transmit(selApp, to: tag, label: "SELECT NDEF app"), r1.ok else {
            session.invalidate(errorMessage: "SELECT NDEF app failed"); return
        }

        // 2. SELECT NDEF file (EF E104).
        let selFile: [UInt8] = [0x00, 0xA4, 0x00, 0x0C, 0x02] + efNDEF
        guard let r2 = await transmit(selFile, to: tag, label: "SELECT NDEF file"), r2.ok else {
            session.invalidate(errorMessage: "SELECT NDEF file failed"); return
        }

        // 3. READ the 2-byte length field (NLEN) at offset 0.
        let readLen: [UInt8] = [0x00, 0xB0, 0x00, 0x00, 0x02]
        guard let r3 = await transmit(readLen, to: tag, label: "READ length"), r3.ok, r3.data.count >= 2 else {
            session.invalidate(errorMessage: "READ length failed"); return
        }
        let nlen = Int(r3.data[0]) << 8 | Int(r3.data[1])
        appendLog("NDEF file content length = \(nlen) bytes")

        if nlen == 0 {
            appendLog("⚠️ File is EMPTY. The cycle write is likely TRANSIENT (the washer ingested and cleared it). Read-after-write won't work — we'd need live sniffing.")
            session.alertMessage = "File empty"
            session.invalidate(); return
        }

        // 4. READ the content in chunks, starting after the 2-byte length.
        var collected = Data()
        var offset = 2
        var remaining = nlen
        while remaining > 0 {
            let chunk = min(remaining, 0xFA)
            let p1 = UInt8((offset >> 8) & 0xFF)
            let p2 = UInt8(offset & 0xFF)
            let read: [UInt8] = [0x00, 0xB0, p1, p2, UInt8(chunk)]
            guard let r = await transmit(read, to: tag, label: "READ @\(offset)"), r.ok else {
                session.invalidate(errorMessage: "READ content failed"); return
            }
            collected.append(r.data)
            offset += chunk
            remaining -= chunk
        }

        appendLog("===== CYCLE PAYLOAD (\(collected.count) bytes) =====")
        appendLog(collected.hexDump())
        appendLog("=================================================")
        session.alertMessage = "Read complete ✓"
        session.invalidate()
    }

    @discardableResult
    private func transmit(_ raw: [UInt8], to tag: NFCISO7816Tag, label: String) async -> APDUResult? {
        guard let apdu = NFCISO7816APDU(data: Data(raw)) else {
            appendLog("\(label): invalid APDU bytes"); return nil
        }
        do {
            let (data, sw1, sw2) = try await tag.sendCommand(apdu: apdu)
            appendLog("→ \(label): \(Data(raw).hexString())")
            appendLog(String(format: "← SW=%02X%02X  data=%@", sw1, sw2, data.hexString()))
            return APDUResult(data: data, sw1: sw1, sw2: sw2)
        } catch {
            appendLog("\(label): error \(error.localizedDescription)")
            return nil
        }
    }

    private func timestamp() -> String {
        let f = DateFormatter(); f.dateFormat = "HH:mm:ss"; return f.string(from: Date())
    }
}

// MARK: - Hex helpers

extension Data {
    func hexString() -> String {
        isEmpty ? "(none)" : map { String(format: "%02X", $0) }.joined(separator: " ")
    }

    func hexDump() -> String {
        var out = ""
        let bytes = Array(self)
        for i in stride(from: 0, to: bytes.count, by: 16) {
            let row = Array(bytes[i..<Swift.min(i + 16, bytes.count)])
            let hex = row.map { String(format: "%02X", $0) }.joined(separator: " ")
            let padded = hex.padding(toLength: 47, withPad: " ", startingAt: 0)
            let ascii = row.map { (32...126).contains($0) ? String(UnicodeScalar($0)) : "." }.joined()
            out += String(format: "%04X  ", i) + padded + "  " + ascii + "\n"
        }
        return out
    }
}
