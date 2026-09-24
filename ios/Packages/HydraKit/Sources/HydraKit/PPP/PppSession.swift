import Foundation

/// PPP (RFC 1661) без HDLC-обрамления — как его переносят SSTP и L2TP: протокол (2 байта) + данные.
/// Порт Ppp.kt.
public enum Ppp {
    public static let protoIP = 0x0021, protoLCP = 0xC021, protoPAP = 0xC023, protoCHAP = 0xC223
    public static let protoIPCP = 0x8021, protoCCP = 0x80FD
    public static let confReq = 1, confAck = 2, confNak = 3, confRej = 4, termReq = 5, termAck = 6
    public static let codeRej = 7, echoReq = 9, echoRep = 10
    public static let optMRU = 1, optAuth = 3, optMagic = 5, optPFC = 7, optACFC = 8
    public static let ipcpAddr = 3, ipcpDNS1 = 129, ipcpDNS2 = 131
    public static let authPAP = 0xC023, authCHAP = 0xC223, chapMsV2: UInt8 = 0x81
    public static let defaultMRU = 1400

    public struct Control: Equatable {
        public var code: Int, id: Int, data: [UInt8]
    }

    public struct Option: Equatable {
        public var type: Int, value: [UInt8]
        public var intValue: Int { value.reduce(0) { ($0 << 8) | Int($1) } }
        public var ipValue: String { value.map(String.init).joined(separator: ".") }
    }

    public static func frame(_ proto: Int, _ info: [UInt8]) -> [UInt8] { [UInt8(proto >> 8 & 0xFF), UInt8(proto & 0xFF)] + info }

    public static func parseFrame(_ f: [UInt8]) -> (Int, [UInt8])? {
        guard f.count >= 2 else { return nil }
        return (Int(f[0]) << 8 | Int(f[1]), Array(f[2...]))
    }

    public static func control(_ code: Int, _ id: Int, _ data: [UInt8]) -> [UInt8] {
        let n = 4 + data.count
        return [UInt8(code), UInt8(id & 0xFF), UInt8(n >> 8 & 0xFF), UInt8(n & 0xFF)] + data
    }

    public static func controlFrame(_ proto: Int, _ code: Int, _ id: Int, _ data: [UInt8]) -> [UInt8] {
        frame(proto, control(code, id, data))
    }

    public static func parseControl(_ info: [UInt8]) -> Control? {
        guard info.count >= 4 else { return nil }
        let n = Int(info[2]) << 8 | Int(info[3])
        return Control(code: Int(info[0]), id: Int(info[1]), data: n >= 4 && n <= info.count ? Array(info[4..<n]) : [])
    }

    public static func encode(_ opts: [Option]) -> [UInt8] { opts.flatMap { [UInt8($0.type), UInt8($0.value.count + 2)] + $0.value } }

    public static func parseOptions(_ d: [UInt8]) -> [Option] {
        var out: [Option] = [], i = 0
        while i + 1 < d.count {
            let n = Int(d[i + 1])
            if n < 2 || i + n > d.count { break }
            out.append(Option(type: Int(d[i]), value: Array(d[(i + 2)..<(i + n)])))
            i += n
        }
        return out
    }

    public static func u16(_ v: Int) -> [UInt8] { [UInt8(v >> 8 & 0xFF), UInt8(v & 0xFF)] }
    public static func u32(_ v: UInt32) -> [UInt8] { [UInt8(v >> 24 & 0xFF), UInt8(v >> 16 & 0xFF), UInt8(v >> 8 & 0xFF), UInt8(v & 0xFF)] }
    public static func ip(_ s: String) -> [UInt8] { s.split(separator: ".").map { UInt8($0) ?? 0 } }
}

#if canImport(CommonCrypto) && canImport(CryptoKit)

/// Машина состояний PPP: LCP → MS-CHAPv2 / PAP → IPCP → данные. Порт PppSession.kt (транспорт —
/// снаружи: SSTP поверх TLS или L2TP поверх UDP). Все вызовы — на одной последовательной очереди.
public final class PppSession: @unchecked Sendable {
    public enum Phase { case dead, lcp, auth, ipcp, open }

    public private(set) var phase = Phase.dead
    public private(set) var assignedIp: String?
    public private(set) var dns: [String] = []
    public private(set) var lastAuth: MsChapV2.AuthResult?

    private let user: String, password: String
    private let queue: DispatchQueue
    private let send: ([UInt8]) -> Void
    private let log: (String) -> Void
    public var onAuthenticated: ((MsChapV2.AuthResult?) -> Void)?
    public var onIpPacket: (([UInt8]) -> Void)?
    public var onUp: ((String, [String]) -> Void)?
    public var onDown: ((String) -> Void)?

    private var nextId = 0
    private var magic = UInt32.random(in: 1...UInt32.max)
    private var mru = Ppp.defaultMRU
    private var lcpAcked = false, peerAcked = false, ipcpAcked = false, peerIpcpAcked = false
    private var auth = 0
    private var requestedIp = "0.0.0.0"
    private var timer: DispatchSourceTimer?
    private var lastRx = Date(), lastLcpTx = Date.distantPast, lastIpcpTx = Date.distantPast, lastEcho = Date()
    private var lcpRetries = 0, ipcpRetries = 0, echoMisses = 0

    static let restart: TimeInterval = 3, maxConfigure = 10, echoIdle: TimeInterval = 15, maxEchoMisses = 3

    public init(user: String, password: String, queue: DispatchQueue, send: @escaping ([UInt8]) -> Void, log: @escaping (String) -> Void) {
        self.user = user; self.password = password; self.queue = queue; self.send = send; self.log = log
    }

    private func id() -> Int { defer { nextId = (nextId + 1) & 0xFF }; return nextId }

    public func start() {
        phase = .lcp
        sendLcpRequest()
        let t = DispatchSource.makeTimerSource(queue: queue)
        t.schedule(deadline: .now() + 1, repeating: 1)
        t.setEventHandler { [weak self] in self?.tick() }
        t.resume()
        timer = t
        lastRx = Date(); lastEcho = Date()
    }

    private func tick() {
        let now = Date()
        switch phase {
        case .lcp where !lcpAcked && now.timeIntervalSince(lastLcpTx) >= Self.restart:
            lcpRetries += 1
            if lcpRetries > Self.maxConfigure { terminate("LCP: сервер не ответил на Configure-Request") } else { sendLcpRequest() }
        case .ipcp where !ipcpAcked && now.timeIntervalSince(lastIpcpTx) >= Self.restart:
            ipcpRetries += 1
            if ipcpRetries > Self.maxConfigure { terminate("IPCP: сервер не ответил на Configure-Request") } else { sendIpcpRequest() }
        case .open where now.timeIntervalSince(lastRx) >= Self.echoIdle && now.timeIntervalSince(lastEcho) >= Self.echoIdle:
            if echoMisses >= Self.maxEchoMisses { terminate("LCP: сервер не отвечает на Echo-Request"); return }
            echoMisses += 1; lastEcho = now
            send(Ppp.controlFrame(Ppp.protoLCP, Ppp.echoReq, id(), Ppp.u32(magic)))
        default: break
        }
    }

    public func onFrame(_ f: [UInt8]) {
        lastRx = Date(); echoMisses = 0
        guard let (proto, info) = Ppp.parseFrame(f) else { return }
        switch proto {
        case Ppp.protoLCP: onLcp(info)
        case Ppp.protoCHAP: onChap(info)
        case Ppp.protoPAP: onPap(info)
        case Ppp.protoIPCP: onIpcp(info)
        case Ppp.protoCCP: onCcp(info)
        case Ppp.protoIP: if phase == .open { onIpPacket?(info) }
        default: if phase != .open { log(String(format: "PPP: неизвестный протокол 0x%04X", proto)) }
        }
    }

    public func sendIp(_ packet: [UInt8]) { if phase == .open { send(Ppp.frame(Ppp.protoIP, packet)) } }

    public func close() {
        timer?.cancel(); timer = nil
        if phase != .dead { send(Ppp.controlFrame(Ppp.protoLCP, Ppp.termReq, id(), [])); phase = .dead }
    }

    // MARK: LCP

    private func sendLcpRequest() {
        lastLcpTx = Date()
        // MRU — 2 байта (RFC 1661 §6.1). На Android до 0.6.24 уходило 4 — сервер отвергал ConfReq.
        var opts = [Ppp.Option(type: Ppp.optMRU, value: Ppp.u16(mru))]
        if magic != 0 { opts.append(Ppp.Option(type: Ppp.optMagic, value: Ppp.u32(magic))) }
        send(Ppp.controlFrame(Ppp.protoLCP, Ppp.confReq, id(), Ppp.encode(opts)))
    }

    private func onLcp(_ info: [UInt8]) {
        guard let p = Ppp.parseControl(info) else { return }
        switch p.code {
        case Ppp.confReq:
            let (code, opts) = evaluatePeerLcp(p.data)
            send(Ppp.controlFrame(Ppp.protoLCP, code, p.id, opts))
            if code == Ppp.confAck { peerAcked = true; advanceFromLcp() }
        case Ppp.confAck: lcpAcked = true; advanceFromLcp()
        case Ppp.confNak:
            for o in Ppp.parseOptions(p.data) {
                if o.type == Ppp.optMRU { mru = min(max(o.intValue, 576), 2000) }
                if o.type == Ppp.optAuth { auth = authOf(o) }
            }
            sendLcpRequest()
        case Ppp.confRej:
            if Ppp.parseOptions(p.data).contains(where: { $0.type == Ppp.optMagic }) { magic = 0 }
            sendLcpRequest()
        case Ppp.termReq:
            send(Ppp.controlFrame(Ppp.protoLCP, Ppp.termAck, p.id, []))
            terminate("LCP Terminate-Request от сервера")
        case Ppp.termAck: terminate("LCP Terminate-Ack")
        case Ppp.echoReq: send(Ppp.controlFrame(Ppp.protoLCP, Ppp.echoRep, p.id, Ppp.u32(magic)))
        case Ppp.codeRej: terminate("LCP Code-Reject")
        default: break
        }
    }

    private func evaluatePeerLcp(_ data: [UInt8]) -> (Int, [UInt8]) {
        var naks: [Ppp.Option] = []
        for o in Ppp.parseOptions(data) where o.type == Ppp.optAuth {
            let a = authOf(o)
            if a == Ppp.authCHAP {
                if o.value.count < 3 || o.value[2] != Ppp.chapMsV2 {
                    naks.append(Ppp.Option(type: Ppp.optAuth, value: [0xC2, 0x23, Ppp.chapMsV2]))
                } else { auth = Ppp.authCHAP }
            } else if a == Ppp.authPAP {
                auth = Ppp.authPAP
            } else if a != 0 {
                naks.append(Ppp.Option(type: Ppp.optAuth, value: [0xC0, 0x23])); auth = Ppp.authPAP
            }
        }
        return naks.isEmpty ? (Ppp.confAck, data) : (Ppp.confNak, Ppp.encode(naks))
    }

    private func authOf(_ o: Ppp.Option) -> Int { o.value.count >= 2 ? Int(o.value[0]) << 8 | Int(o.value[1]) : 0 }

    private func advanceFromLcp() {
        guard lcpAcked, peerAcked, phase == .lcp else { return }
        if auth == 0 { log("PPP LCP: поднято, аутентификация не требуется"); startIpcp(); return }
        phase = .auth
        log("PPP LCP: поднято, auth = \(auth == Ppp.authCHAP ? "MS-CHAPv2" : "PAP")")
        if auth == Ppp.authPAP {
            let u = Array(user.utf8), pw = Array(password.utf8)
            send(Ppp.controlFrame(Ppp.protoPAP, 1, id(), [UInt8(u.count)] + u + [UInt8(pw.count)] + pw))
        }
    }

    // MARK: CHAP / PAP

    private func onChap(_ info: [UInt8]) {
        guard let p = Ppp.parseControl(info) else { return }
        switch p.code {
        case 1:
            guard let n = p.data.first, p.data.count >= 1 + Int(n) else { return }
            let serverChallenge = Array(p.data[1..<(1 + Int(n))])
            let peerChallenge = (0..<16).map { _ in UInt8.random(in: 0...255) }
            let a = MsChapV2.authenticate(user: user, password: password, authenticatorChallenge: serverChallenge, peerChallenge: peerChallenge)
            lastAuth = a
            // Value-Size(49) + PeerChallenge(16) + Reserved(8) + NT-Response(24) + Flags(1) + Name
            let value = peerChallenge + [UInt8](repeating: 0, count: 8) + a.ntResponse + [0]
            send(Ppp.controlFrame(Ppp.protoCHAP, 2, p.id, [UInt8(value.count)] + value + Array(user.utf8)))
            log("PPP CHAP: MS-CHAPv2 Response отправлен")
        case 3:
            let msg = String(decoding: p.data, as: UTF8.self)
            if let r = msg.range(of: #"S=[0-9A-Fa-f]{40}"#, options: .regularExpression), let a = lastAuth,
               msg[r].uppercased() != a.authenticatorResponse.uppercased() {
                log("PPP CHAP: authenticator response не совпал (возможен MITM) — разрыв")
                terminate("CHAP authenticator mismatch"); return
            }
            log("PPP CHAP: аутентификация успешна ✓")
            onAuthenticated?(lastAuth)
            startIpcp()
        case 4:
            terminate("CHAP failure: \(String(decoding: p.data, as: UTF8.self))")
        default: break
        }
    }

    private func onPap(_ info: [UInt8]) {
        guard let p = Ppp.parseControl(info) else { return }
        if p.code == 2 { log("PPP PAP: аутентификация успешна ✓"); onAuthenticated?(nil); startIpcp() }
        if p.code == 3 { terminate("PAP failure") }
    }

    // MARK: IPCP

    private func startIpcp() { phase = .ipcp; sendIpcpRequest() }

    private func sendIpcpRequest() {
        lastIpcpTx = Date()
        let opts = [Ppp.Option(type: Ppp.ipcpAddr, value: Ppp.ip(requestedIp)),
                    Ppp.Option(type: Ppp.ipcpDNS1, value: Ppp.ip(dns.first ?? "0.0.0.0")),
                    Ppp.Option(type: Ppp.ipcpDNS2, value: Ppp.ip(dns.count > 1 ? dns[1] : "0.0.0.0"))]
        send(Ppp.controlFrame(Ppp.protoIPCP, Ppp.confReq, id(), Ppp.encode(opts)))
    }

    private func onIpcp(_ info: [UInt8]) {
        guard let p = Ppp.parseControl(info) else { return }
        switch p.code {
        case Ppp.confReq:
            send(Ppp.controlFrame(Ppp.protoIPCP, Ppp.confAck, p.id, p.data))
            peerIpcpAcked = true; ipcpUp()
        case Ppp.confAck: ipcpAcked = true; ipcpUp()
        case Ppp.confNak:
            var d1: String?, d2: String?
            for o in Ppp.parseOptions(p.data) {
                if o.type == Ppp.ipcpAddr { requestedIp = o.ipValue }
                if o.type == Ppp.ipcpDNS1 { d1 = o.ipValue }
                if o.type == Ppp.ipcpDNS2 { d2 = o.ipValue }
            }
            dns = [d1 ?? dns.first, d2 ?? (dns.count > 1 ? dns[1] : nil)].compactMap { $0 }
            sendIpcpRequest()
        case Ppp.confRej: sendIpcpRequest()
        default: break
        }
    }

    private func ipcpUp() {
        guard ipcpAcked, peerIpcpAcked, phase == .ipcp else { return }
        guard requestedIp != "0.0.0.0" else { terminate("сервер не назначил IP"); return }
        assignedIp = requestedIp
        phase = .open
        log("PPP IPCP: поднят ✓ IP=\(requestedIp) DNS=\(dns.joined(separator: ", "))")
        onUp?(requestedIp, dns.filter { $0 != "0.0.0.0" })
    }

    private func onCcp(_ info: [UInt8]) {
        guard let p = Ppp.parseControl(info), p.code == Ppp.confReq else { return }
        let mppe = Ppp.parseOptions(p.data).filter { $0.type == 18 }
        send(mppe.isEmpty ? Ppp.controlFrame(Ppp.protoCCP, Ppp.confAck, p.id, p.data)
                          : Ppp.controlFrame(Ppp.protoCCP, Ppp.confRej, p.id, Ppp.encode(mppe)))
    }

    private func terminate(_ reason: String) {
        timer?.cancel(); timer = nil
        phase = .dead
        log("PPP: сессия завершена: \(reason)")
        onDown?(reason)
    }
}

#endif
