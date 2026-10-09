import CiaDPI
import Darwin
import Foundation

/// ByeDPI (`ciadpi`) внутри процесса (0.7.13): подпроцессы iOS запрещает, поэтому C-код (пакет CiaDPI) крутится в своём потоке.
/// Одновременно в процессе работает один экземпляр — глобальное состояние ByeDPI общее; между запусками оно сбрасывается
/// (`ciadpi_run`), так что мастер подбора может подряд проверять разные стратегии.
final class ByeDpiRunner: @unchecked Sendable {
    static let shared = ByeDpiRunner()

    enum RunnerError: Error, LocalizedError {
        case busy, notReady(Int)
        var errorDescription: String? {
            switch self {
            case .busy: "ByeDPI уже запущен"
            case .notReady(let p): "ByeDPI: порт \(p) не открылся"
            }
        }
    }

    private let lock = NSLock()
    private var running = false
    private var finished = DispatchSemaphore(value: 0)

    private var isRunning: Bool { lock.lock(); defer { lock.unlock() }; return running }

    /// Запускает ciadpi с аргументами `args` (без имени программы) и ждёт, пока порт начнёт слушать.
    func start(args: [String], port: Int, timeout: TimeInterval = 5) throws {
        lock.lock()
        if running { lock.unlock(); throw RunnerError.busy }
        running = true
        let sem = DispatchSemaphore(value: 0)
        finished = sem
        lock.unlock()

        let argv = ["ciadpi"] + args
        let thread = Thread { [self] in
            var cstrs: [UnsafeMutablePointer<CChar>?] = argv.map { strdup($0) } + [nil]
            _ = cstrs.withUnsafeMutableBufferPointer { ciadpi_run(Int32(argv.count), $0.baseAddress) }
            for p in cstrs { free(p) }
            lock.lock(); running = false; lock.unlock()
            sem.signal()
        }
        thread.name = "ciadpi"
        thread.stackSize = 2 << 20
        thread.start()

        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if !isRunning { throw RunnerError.notReady(port) }
            if Self.portOpen(port) { return }
            usleep(100_000)
        }
        stop()
        throw RunnerError.notReady(port)
    }

    func stop() {
        if !isRunning { return }
        ciadpi_stop()
        _ = finished.wait(timeout: .now() + 3)
    }

    static func portOpen(_ port: Int) -> Bool {
        let s = socket(AF_INET, SOCK_STREAM, 0)
        if s < 0 { return false }
        defer { close(s) }
        var a = sockaddr_in()
        a.sin_family = sa_family_t(AF_INET)
        a.sin_port = in_port_t(port).bigEndian
        a.sin_addr.s_addr = inet_addr("127.0.0.1")
        return withUnsafePointer(to: &a) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { connect(s, $0, socklen_t(MemoryLayout<sockaddr_in>.size)) == 0 }
        }
    }
}
