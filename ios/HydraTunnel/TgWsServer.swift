import Foundation
import HydraKit
import Network
import Security

/// «Telegram через WebSocket» внутри расширения VPN (0.7.20) - порт `TgWsProxy` с Android/ПК. Локальный SOCKS5 на 127.0.0.1:
/// вместо обычного TCP до серверов Telegram (их режет и душит DPI) поднимает WebSocket поверх TLS 1.2 до `kws<N>.web.telegram.org/apiws`
/// и гонит по нему тот же MTProto-поток. Номер ЦОД читается из 64-байтового «obfuscated init» клиента. Не-Telegram адреса идут напрямую.
/// Если WebSocket недоступен (редирект, ошибка) - откат на прямой TCP. Кодеки и таблицы - `HydraKit.TgWs`.
/// Соединения самого расширения в туннель не попадают, поэтому «прямой» TCP здесь действительно прямой.
final class TgWsServer: @unchecked Sendable {
    private let queue = DispatchQueue(label: "ru.gidravpn.hydra.tgws")
    private let log: @Sendable (String) -> Void
    private var listener: NWListener?
    private var mtListener: NWListener?
    private let lock = NSLock()
    private var active = Set<ObjectIdentifier>()
    private var conns: [ObjectIdentifier: NWConnection] = [:]
    private var wsBlacklist = Set<String>()
    private var failUntil: [String: Date] = [:]
    private static let maxConnections = 256

    init(log: @escaping @Sendable (String) -> Void) { self.log = log }

    /// Поднимает слушатель на 127.0.0.1; бросает, если порт не удалось занять.
    func start(port: UInt16) throws {
        listener = try makeListener(port) { [weak self] c in self?.accept(c, mtProto: false) }
        log("TG WS: слушаю 127.0.0.1:\(port)")
    }

    /// MTProto-прокси (0.7.20): `tg://proxy` с секретом - так Telegram для Android подключается без сбоев.
    func startMtProto(port: UInt16) throws {
        mtListener = try makeListener(port) { [weak self] c in self?.accept(c, mtProto: true) }
        log("TG WS: MTProto-прокси 127.0.0.1:\(port)")
    }

    private func makeListener(_ port: UInt16, _ onConnection: @escaping (NWConnection) -> Void) throws -> NWListener {
        let params = NWParameters.tcp
        params.requiredInterfaceType = .loopback
        let l = try NWListener(using: params, on: NWEndpoint.Port(rawValue: port)!)
        let ready = DispatchSemaphore(value: 0)
        let box = StartBox()
        l.stateUpdateHandler = { state in
            switch state {
            case .ready: box.error = nil; ready.signal()
            case .failed(let e): box.error = e; ready.signal()
            default: break
            }
        }
        l.newConnectionHandler = onConnection
        l.start(queue: queue)
        if ready.wait(timeout: .now() + 3) == .timedOut { l.cancel(); throw NSError(domain: "TgWs", code: 1, userInfo: [NSLocalizedDescriptionKey: "слушатель не запустился"]) }
        if let e = box.error { l.cancel(); throw e }
        return l
    }

    private final class StartBox: @unchecked Sendable { var error: NWError? }

    func stop() {
        listener?.cancel(); listener = nil
        mtListener?.cancel(); mtListener = nil
        lock.lock(); let all = Array(conns.values); conns.removeAll(); active.removeAll(); lock.unlock()
        all.forEach { $0.cancel() }
    }

    private func accept(_ c: NWConnection, mtProto: Bool) {
        let id = ObjectIdentifier(c)
        lock.lock()
        let full = active.count >= Self.maxConnections
        if !full { active.insert(id); conns[id] = c }
        lock.unlock()
        if full { c.cancel(); return }
        Task { [weak self] in
            guard let self else { return }
            if mtProto { await self.handleMt(c) } else { await self.handle(c) }
            c.cancel()
            self.lock.lock(); self.active.remove(id); self.conns.removeValue(forKey: id); self.lock.unlock()
        }
    }

    // MARK: MTProto-прокси (0.7.20)

    /// Клиент говорит MTProto-прокси с секретом: расшифровываем init, берём ЦОД и тег, открываем WebSocket до ЦОД со своим obfuscated2
    /// и перешифровываем поток (как в `TgWsProxy.handleMt` на Android и ПК).
    private func handleMt(_ client: NWConnection) async {
        do {
            try await client.ready(queue)
            let hs = try await client.readExactly(64)
            guard let s = TgWs.MtSession(handshake: hs) else { log("TG WS: MTProto-прокси: неверный секрет или протокол"); return }
            let dc = abs(s.dcRaw)
            let media = s.dcRaw < 0
            guard let fallbackIp = TgWs.defaultDcIps[dc] else { log("TG WS: MTProto-прокси: неизвестный ЦОД \(dc)"); return }
            let obf = TgWs.ObfClient(tag: s.tag, dc: s.dcRaw)
            if let ws = await openWs(dc: dc, media: media) {
                try await ws.send(Data(obf.initBytes))
                await bridgePlain(client, ws, obf, TgWs.PlainFramer(tag: s.tag), cltDec: s.clientDec, cltEnc: s.clientEnc)
                return
            }
            // WebSocket недоступен - прямой TCP до ЦОД с тем же перешифрованием потока.
            log("TG WS: MTProto-прокси: WebSocket недоступен → прямой TCP до ЦОД\(dc)")
            let remote = NWConnection(host: NWEndpoint.Host(fallbackIp), port: 443, using: .tcp)
            try await remote.ready(queue)
            defer { remote.cancel() }
            try await remote.writeAll(obf.initBytes)
            await withTaskGroup(of: Void.self) { g in
                g.addTask {
                    while let d = try? await client.readSome(), !d.isEmpty {
                        if (try? await remote.writeAll(obf.encrypt(s.clientDec.process(Array(d))))) == nil { break }
                    }
                    remote.cancel()
                }
                g.addTask {
                    while let d = try? await remote.readSome(), !d.isEmpty {
                        if (try? await client.writeAll(s.clientEnc.process(obf.decrypt(Array(d))))) == nil { break }
                    }
                    client.cancel()
                }
            }
        } catch {
            // Клиент закрыл соединение или оборвалось - штатно.
        }
    }

    // MARK: SOCKS5

    private func handle(_ client: NWConnection) async {
        do {
            try await client.ready(queue)
            let hello = try await client.readExactly(2)
            guard hello[0] == 5 else { return }
            _ = try await client.readExactly(Int(hello[1]))
            try await client.writeAll([5, 0])

            let req = try await client.readExactly(4)
            guard req[1] == 1 else { try await client.writeAll(Self.reply(7)); return }
            let dst: String
            var v6: [UInt8]?
            switch req[3] {
            case 1: dst = try await client.readExactly(4).map { String($0) }.joined(separator: ".")
            case 3:
                let n = try await client.readExactly(1)[0]
                dst = String(decoding: try await client.readExactly(Int(n)), as: UTF8.self)
            case 4:
                // Telegram на устройстве с IPv6 использует адреса ЦОД по IPv6.
                let a = try await client.readExactly(16)
                v6 = a
                dst = Self.ipv6String(a)
            default: try await client.writeAll(Self.reply(8)); return
            }
            let p = try await client.readExactly(2)
            let port = (Int(p[0]) << 8) | Int(p[1])

            let isTelegram: Bool = v6.map { TgWs.v6Dc($0) != nil } ?? TgWs.isTelegramIp(dst)
            if !isTelegram { try await passthrough(client, dst: dst, port: port, first: nil, reply: true); return }

            try await client.writeAll(Self.reply(0))
            // Первый байт отличает обфусцированный init (случайный) от «чистого» транспорта Telegram для Android:
            // 0xEF (abridged) или 0xEEEEEEEE / 0xDDDDDDDD (intermediate).
            let b0 = try await client.readExactly(1)[0]
            var plainTag: UInt32?
            var prefix: [UInt8] = []
            var initBytes: [UInt8]?
            if b0 == 0xEF { plainTag = TgWs.tagAbridged; prefix = [0xEF] }
            else {
                let h = [b0] + (try await client.readExactly(3))
                if h.allSatisfy({ $0 == 0xEE }) { plainTag = TgWs.tagIntermediate; prefix = h }
                else if h.allSatisfy({ $0 == 0xDD }) { plainTag = TgWs.tagPadded; prefix = h }
                else { initBytes = h + (try await client.readExactly(60)) }
            }
            let byIp: (dc: Int, media: Bool)? = v6.flatMap { TgWs.v6Dc($0) } ?? TgWs.ipToDc[dst]

            if let tag = plainTag {
                guard let b = byIp, TgWs.defaultDcIps[b.dc] != nil else {
                    try await tcpFallback(client, dst: dst, port: port, first: Data(prefix), why: "неизвестный ЦОД"); return
                }
                guard let ws = await openWs(dc: b.dc, media: b.media) else {
                    try await tcpFallback(client, dst: dst, port: port, first: Data(prefix), why: "WebSocket недоступен"); return
                }
                // Ретранслятор Telegram ждёт obfuscated2, а клиент говорит открытым текстом - обфускацию строим сами.
                let obf = TgWs.ObfClient(tag: tag, dc: b.media ? -b.dc : b.dc)
                try await ws.send(Data(obf.initBytes))
                await bridgePlain(client, ws, obf, TgWs.PlainFramer(tag: tag))
                return
            }

            guard var initB = initBytes else { return }
            // HTTP-транспорт Telegram не поддерживается - клиент сам вернётся к MTProto.
            if ["POST ", "GET ", "HEAD "].contains(where: { initB.starts(with: Array($0.utf8)) }) { return }

            var dc: Int?
            var media = false
            var patched = false
            if let parsed = TgWs.dcFromInit(initB) { dc = parsed.dc; media = parsed.media }
            else if let b = byIp {
                // Мобильные клиенты без секрета оставляют случайные байты ЦОД - берём по адресу и правим init.
                dc = b.dc; media = b.media
                if TgWs.defaultDcIps[b.dc] != nil { initB = TgWs.patchInitDc(initB, dc: media ? -b.dc : b.dc); patched = true }
            }
            guard let dcn = dc, TgWs.defaultDcIps[dcn] != nil else {
                try await tcpFallback(client, dst: dst, port: port, first: Data(initB), why: "неизвестный ЦОД"); return
            }
            guard let ws = await openWs(dc: dcn, media: media) else {
                try await tcpFallback(client, dst: dst, port: port, first: Data(initB), why: "WebSocket недоступен"); return
            }
            let splitter = patched ? TgWs.MsgSplitter(initBytes: initB) : nil
            try await ws.send(Data(initB))
            await bridge(client, ws, splitter)
        } catch {
            // Клиент закрыл соединение или оборвалось - штатно.
        }
    }

    /// WebSocket до ЦОД: сначала домены `kws*`, у каждого - адреса из DNS и запасной. Помнит редиректы и недавние сбои.
    private func openWs(dc: Int, media: Bool) async -> WebSocketConn? {
        let key = "\(dc):\(media)"
        lock.lock(); let black = wsBlacklist.contains(key); let failing = (failUntil[key] ?? .distantPast) > Date(); lock.unlock()
        if black { return nil }
        var allRedirects = true
        let timeout: TimeInterval = failing ? 2 : 10
        for domain in TgWs.wsDomains(dc: dc, media: media) {
            for ip in await candidateIps(domain, fallback: TgWs.defaultDcIps[dc]!) {
                do { return try await WebSocketConn.connect(ip: ip, domain: domain, timeout: timeout, queue: queue) }
                catch WebSocketConn.Failure.redirect(let code) { log("TG WS: ЦОД\(dc) \(domain) (\(ip)) → редирект \(code)") }
                catch { allRedirects = false; log("TG WS: ЦОД\(dc) \(domain) (\(ip)): \(error.localizedDescription)") }
            }
        }
        lock.lock()
        if allRedirects { wsBlacklist.insert(key) } else { failUntil[key] = Date().addingTimeInterval(30) }
        lock.unlock()
        return nil
    }

    private static func ipv6String(_ a: [UInt8]) -> String {
        stride(from: 0, to: 16, by: 2).map { String((Int(a[$0]) << 8) | Int(a[$0 + 1]), radix: 16) }.joined(separator: ":")
    }

    /// Клиент говорит открытым MTProto: режем по границам сообщений, шифруем своим потоком; ответы расшифровываем.
    private func bridgePlain(_ client: NWConnection, _ ws: WebSocketConn, _ obf: TgWs.ObfClient, _ framer: TgWs.PlainFramer,
                             cltDec: AesCtr? = nil, cltEnc: AesCtr? = nil) async {
        await withTaskGroup(of: Void.self) { g in
            g.addTask {
                var framer = framer
                while let d = try? await client.readSome(), !d.isEmpty {
                    let chunk: [UInt8] = cltDec.map { $0.process(Array(d)) } ?? Array(d)
                    guard let messages = try? framer.feed(chunk) else { break }
                    for m in messages {
                        if (try? await ws.send(Data(obf.encrypt(m)))) == nil { ws.close(); return }
                    }
                }
                ws.close()
            }
            g.addTask {
                while let m = try? await ws.receive() {
                    let p = obf.decrypt(m)
                    if (try? await client.writeAll(cltEnc.map { $0.process(p) } ?? p)) == nil { break }
                }
                ws.close(); client.cancel()
            }
        }
    }

    private static func reply(_ status: UInt8) -> [UInt8] { [5, status, 0, 1, 0, 0, 0, 0, 0, 0] }

    /// Адреса для WebSocket: что отдаёт DNS для домена `kws*` (у части провайдеров заблокированы отдельные адреса ЦОД, а адреса фронтов
    /// живы) и запасной адрес из таблицы. Не больше трёх, без повторов.
    private func candidateIps(_ domain: String, fallback: String) async -> [String] {
        let fromDns: [String] = await withCheckedContinuation { cont in
            DispatchQueue.global().async {
                var hints = addrinfo(); hints.ai_family = AF_INET; hints.ai_socktype = SOCK_STREAM
                var res: UnsafeMutablePointer<addrinfo>?
                var out: [String] = []
                if getaddrinfo(domain, nil, &hints, &res) == 0 {
                    var p = res
                    while let a = p {
                        var buf = [CChar](repeating: 0, count: Int(INET_ADDRSTRLEN))
                        a.pointee.ai_addr.withMemoryRebound(to: sockaddr_in.self, capacity: 1) { sin in
                            var addr = sin.pointee.sin_addr
                            inet_ntop(AF_INET, &addr, &buf, socklen_t(INET_ADDRSTRLEN))
                        }
                        out.append(String(cString: buf))
                        p = a.pointee.ai_next
                    }
                    freeaddrinfo(res)
                }
                cont.resume(returning: out)
            }
        }
        var seen = Set<String>()
        return Array((fromDns + [fallback]).filter { seen.insert($0).inserted }.prefix(3))
    }

    private func tcpFallback(_ client: NWConnection, dst: String, port: Int, first: Data, why: String) async throws {
        log("TG WS: \(why) → прямой TCP до \(dst):\(port)")
        try await passthrough(client, dst: dst, port: port, first: first, reply: false)
    }

    /// Прямое соединение с адресом назначения и двусторонняя перекачка.
    private func passthrough(_ client: NWConnection, dst: String, port: Int, first: Data?, reply: Bool) async throws {
        guard let p = NWEndpoint.Port(rawValue: UInt16(clamping: port)) else { return }
        let remote = NWConnection(host: NWEndpoint.Host(dst), port: p, using: .tcp)
        do { try await remote.ready(queue) }
        catch { if reply { try? await client.writeAll(Self.reply(5)) }; remote.cancel(); return }
        defer { remote.cancel() }
        if reply { try await client.writeAll(Self.reply(0)) }
        if let first { try await remote.writeAll(Array(first)) }
        await withTaskGroup(of: Void.self) { g in
            g.addTask { while let d = try? await client.readSome(), !d.isEmpty { if (try? await remote.writeAll(Array(d))) == nil { break } }; remote.cancel() }
            g.addTask { while let d = try? await remote.readSome(), !d.isEmpty { if (try? await client.writeAll(Array(d))) == nil { break } }; client.cancel() }
        }
    }

    /// Клиент ⇄ WebSocket: пока одна сторона жива, перекладываем кадры; при закрытии любой - закрываем обе.
    private func bridge(_ client: NWConnection, _ ws: WebSocketConn, _ splitter: TgWs.MsgSplitter?) async {
        await withTaskGroup(of: Void.self) { g in
            g.addTask {
                while let d = try? await client.readSome(), !d.isEmpty {
                    let chunk = Array(d)
                    for part in splitter?.split(chunk) ?? [chunk] {
                        if (try? await ws.send(Data(part))) == nil { ws.close(); return }
                    }
                }
                ws.close()
            }
            g.addTask {
                while let m = try? await ws.receive() { if (try? await client.writeAll(Array(m))) == nil { break } }
                ws.close(); client.cancel()
            }
        }
    }
}

// MARK: NWConnection: async-обёртки

extension NWConnection {
    /// Ждёт `.ready`; бросает, если соединение не поднялось.
    func ready(_ queue: DispatchQueue, timeout: TimeInterval = 15) async throws {
        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
            let once = Once()
            stateUpdateHandler = { state in
                switch state {
                case .ready: if once.fire() { cont.resume() }
                case .failed(let e): if once.fire() { cont.resume(throwing: e) }
                case .cancelled: if once.fire() { cont.resume(throwing: NWError.posix(.ECANCELED)) }
                default: break
                }
            }
            start(queue: queue)
            queue.asyncAfter(deadline: .now() + timeout) { [weak self] in
                if once.fire() { self?.cancel(); cont.resume(throwing: NWError.posix(.ETIMEDOUT)) }
            }
        }
    }

    /// Следующая порция данных; пустая - соединение закрыто.
    func readSome(max: Int = 65536) async throws -> Data {
        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Data, Error>) in
            receive(minimumIncompleteLength: 1, maximumLength: max) { data, _, isComplete, error in
                if let error { cont.resume(throwing: error) }
                else if let data, !data.isEmpty { cont.resume(returning: data) }
                else if isComplete { cont.resume(returning: Data()) }
                else { cont.resume(returning: Data()) }
            }
        }
    }

    /// Ровно `n` байт; бросает, если соединение закрылось раньше.
    func readExactly(_ n: Int) async throws -> [UInt8] {
        if n == 0 { return [] }
        return try await withCheckedThrowingContinuation { (cont: CheckedContinuation<[UInt8], Error>) in
            receive(minimumIncompleteLength: n, maximumLength: n) { data, _, _, error in
                if let error { cont.resume(throwing: error) }
                else if let data, data.count == n { cont.resume(returning: Array(data)) }
                else { cont.resume(throwing: NWError.posix(.ECONNRESET)) }
            }
        }
    }

    func writeAll(_ bytes: [UInt8]) async throws {
        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
            send(content: Data(bytes), completion: .contentProcessed { error in
                if let error { cont.resume(throwing: error) } else { cont.resume() }
            })
        }
    }
}

/// Один раз: защита от повторного resume у continuation.
private final class Once: @unchecked Sendable {
    private let lock = NSLock()
    private var done = false
    func fire() -> Bool { lock.lock(); defer { lock.unlock() }; if done { return false }; done = true; return true }
}

// MARK: WebSocket поверх TLS 1.2 (ручной: нужен вход по IP с SNI другого имени и путь /apiws)

final class WebSocketConn: @unchecked Sendable {
    enum Failure: Error { case redirect(Int), badStatus(Int), closed, tooLarge }

    private let conn: NWConnection
    private var buffer = [UInt8]()
    private let sendLock = NSLock()
    private var closed = false

    private init(_ c: NWConnection) { conn = c }

    /// TLS до `ip`:443 с SNI/Host `domain` (проверка сертификата - по этому имени), затем `GET /apiws` с подпротоколом `binary`.
    static func connect(ip: String, domain: String, timeout: TimeInterval, queue: DispatchQueue) async throws -> WebSocketConn {
        let tls = NWProtocolTLS.Options()
        let sec = tls.securityProtocolOptions
        sec_protocol_options_set_tls_server_name(sec, domain)
        // Конечная точка Telegram отвечает только по TLS 1.2 (на 1.3 - alert protocol_version).
        sec_protocol_options_set_min_tls_protocol_version(sec, .TLSv12)
        sec_protocol_options_set_max_tls_protocol_version(sec, .TLSv12)
        sec_protocol_options_set_verify_block(sec, { _, trustRef, complete in
            let trust = sec_trust_copy_ref(trustRef).takeRetainedValue()
            SecTrustSetPolicies(trust, SecPolicyCreateSSL(true, domain as CFString))
            var err: CFError?
            complete(SecTrustEvaluateWithError(trust, &err))
        }, queue)
        let tcp = NWProtocolTCP.Options()
        tcp.noDelay = true
        let params = NWParameters(tls: tls, tcp: tcp)
        guard let p443 = NWEndpoint.Port(rawValue: 443) else { throw Failure.closed }
        let c = NWConnection(host: NWEndpoint.Host(ip), port: p443, using: params)
        do {
            try await c.ready(queue, timeout: timeout)
            let key = Data((0..<16).map { _ in UInt8.random(in: 0...255) }).base64EncodedString()
            let req = "GET /apiws HTTP/1.1\r\nHost: \(domain)\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                "Sec-WebSocket-Key: \(key)\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Protocol: binary\r\n\r\n"
            try await c.writeAll(Array(req.utf8))
            let ws = WebSocketConn(c)
            let head = try await ws.readHead()
            let code = head.split(separator: "\r\n").first.flatMap { $0.split(separator: " ").dropFirst().first }.flatMap { Int($0) } ?? 0
            if code == 101 { return ws }
            if [301, 302, 303, 307, 308].contains(code) { throw Failure.redirect(code) }
            throw Failure.badStatus(code)
        } catch { c.cancel(); throw error }
    }

    /// Читает до пустой строки; остаток после заголовков остаётся в буфере кадров.
    private func readHead() async throws -> String {
        while true {
            if let r = findHeaderEnd() {
                let head = String(decoding: buffer[0..<r], as: UTF8.self)
                buffer.removeFirst(r + 4)
                return head
            }
            let d = try await conn.readSome()
            if d.isEmpty { throw Failure.closed }
            buffer.append(contentsOf: d)
            if buffer.count > 16384 { throw Failure.tooLarge }
        }
    }

    private func findHeaderEnd() -> Int? {
        let pat: [UInt8] = [13, 10, 13, 10]
        if buffer.count < 4 { return nil }
        for i in 0...(buffer.count - 4) where Array(buffer[i..<i + 4]) == pat { return i }
        return nil
    }

    private func need(_ n: Int) async throws {
        while buffer.count < n {
            let d = try await conn.readSome()
            if d.isEmpty { throw Failure.closed }
            buffer.append(contentsOf: d)
        }
    }

    /// Следующее бинарное сообщение; nil - закрыто. Ping отвечаем pong.
    func receive() async throws -> [UInt8]? {
        var frag: [UInt8]?
        while !closed {
            try await need(2)
            let b0 = Int(buffer[0]), b1 = Int(buffer[1])
            let fin = b0 & 0x80 != 0
            let op = b0 & 0x0F
            var len = b1 & 0x7F
            var off = 2
            if len == 126 { try await need(4); len = (Int(buffer[2]) << 8) | Int(buffer[3]); off = 4 }
            else if len == 127 {
                try await need(10)
                len = 0
                for i in 2..<10 { len = (len << 8) | Int(buffer[i]) }
                off = 10
            }
            if len > 16 * 1024 * 1024 { throw Failure.tooLarge }
            let masked = b1 & 0x80 != 0
            if masked { try await need(off + 4 + len) } else { try await need(off + len) }
            var payload: [UInt8]
            if masked {
                let mask = Array(buffer[off..<off + 4])
                payload = Array(buffer[off + 4..<off + 4 + len])
                for i in payload.indices { payload[i] ^= mask[i & 3] }
                buffer.removeFirst(off + 4 + len)
            } else {
                payload = Array(buffer[off..<off + len])
                buffer.removeFirst(off + len)
            }
            switch op {
            case 8: close(); return nil
            case 9: try await sendFrame(op: 10, payload)
            case 10: break
            default:
                if fin && frag == nil { return payload }
                frag = (frag ?? []) + payload
                if fin { return frag }
            }
        }
        return nil
    }

    func send(_ data: Data) async throws { try await sendFrame(op: 2, Array(data)) }

    private func sendFrame(op: UInt8, _ data: [UInt8]) async throws {
        if closed { throw Failure.closed }
        var mask = [UInt8](repeating: 0, count: 4)
        for i in 0..<4 { mask[i] = UInt8.random(in: 0...255) }
        var f: [UInt8] = [0x80 | op]
        let n = data.count
        if n < 126 { f.append(0x80 | UInt8(n)) }
        else if n < 65536 { f.append(0x80 | 126); f.append(UInt8(n >> 8)); f.append(UInt8(n & 0xFF)) }
        else { f.append(0x80 | 127); for s in stride(from: 56, through: 0, by: -8) { f.append(UInt8((n >> s) & 0xFF)) } }
        f.append(contentsOf: mask)
        var masked = data
        for i in masked.indices { masked[i] ^= mask[i & 3] }
        f.append(contentsOf: masked)
        try await conn.writeAll(f)
    }

    func close() {
        sendLock.lock(); defer { sendLock.unlock() }
        if closed { return }
        closed = true
        conn.cancel()
    }
}
