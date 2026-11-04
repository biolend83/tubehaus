import Foundation
import JavaScriptCore
import os

private let log = Logger(subsystem: "io.github.biolend83.tubehaus.tv", category: "engine")

/// The same YouTube engine as the iPad app (ios/engine/engine.js), run in Apple's JavaScriptCore.
/// It decides which requests to make and how to read the answers; this file only does what it
/// asks for: HTTP requests, the visitor identity, and a new identity when YouTube walls the old one.
///
/// Which engine.js: the one built into the app, or a newer one from GitHub kept in Caches,
/// whichever has the higher ENGINE_VERSION. GitHub is checked every six hours at most.
struct EngineError: Error, LocalizedError {
    let kind: String
    let message: String
    var errorDescription: String? {
        switch kind {
        // YouTube's own words ("Sign in to confirm you're not a bot") don't say what to do about it
        case "LOGIN": return "YouTube wants this network to sign in, which it often does for VPNs and data centers. Try again without a VPN, or on another network."
        case "AGE": return "This video has an age check, and YouTube only shows it to a signed-in account. You can watch it on YouTube."
        default: return message
        }
    }
}

actor Engine {
    static let shared = Engine()

    private static let updateURL = URL(string: "https://raw.githubusercontent.com/biolend83/tubehaus/ios-app/ios/engine/engine.js")!
    private static let checkEvery: TimeInterval = 6 * 3600

    private var ctx: JSContext?
    private var nextCall = 0
    private var lastCheck = Date.distantPast
    private(set) var version = 0
    private(set) var source = ""

    private var cacheFile: URL {
        FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("engine.js")
    }

    private static func versionOf(_ src: String?) -> Int {
        guard let src, let r = src.range(of: #"var ENGINE_VERSION = \d+"#, options: .regularExpression) else { return -1 }
        return Int(src[r].split(separator: " ").last ?? "") ?? -1
    }

    /// a context with `src` loaded, or nil when it doesn't load or isn't an engine
    private static func start(_ src: String) -> JSContext? {
        guard let c = JSContext() else { return nil }
        c.evaluateScript(src)
        if c.exception != nil { return nil }
        let ok = c.evaluateScript("typeof OPS === 'object' && typeof __run === 'function'")?.toBool() ?? false
        return ok && c.exception == nil ? c : nil
    }

    private func load() throws -> JSContext {
        let builtIn = Bundle.main.url(forResource: "engine", withExtension: "js").flatMap { try? String(contentsOf: $0, encoding: .utf8) }
        let cached = try? String(contentsOf: cacheFile, encoding: .utf8)
        let order = [(cached, "downloaded"), (builtIn, "built-in")]
            .compactMap { s, from in s.map { ($0, Self.versionOf($0), from) } }
            .sorted { $0.1 > $1.1 }
        for (src, v, from) in order {
            guard let c = Self.start(src) else { continue }   // a broken download: fall back to the built-in one
            version = v; source = from
            return c
        }
        throw EngineError(kind: "OTHER", message: "The YouTube engine is missing")
    }

    /// a Swift string as a JavaScript string literal (JSON is valid JavaScript)
    private static func lit(_ s: String) -> String {
        let d = (try? JSONSerialization.data(withJSONObject: s, options: .fragmentsAllowed)) ?? Data("\"\"".utf8)
        return String(decoding: d, as: UTF8.self)
    }

    private func run(_ c: JSContext, _ js: String) throws -> String {
        c.exception = nil
        let v = c.evaluateScript(js)
        if let e = c.exception { throw EngineError(kind: "OTHER", message: "Engine: \(e.toString() ?? "error")") }
        return v?.toString() ?? ""
    }

    private func env() -> String {
        EngineHost.json(["hl": Settings.hl, "gl": Settings.gl, "players": NSNull()])
    }

    /// runs `op` of engine.js with `args` and returns what it gives back (JSON as Foundation objects)
    func call(_ op: String, _ args: [Any]) async throws -> Any {
        let c: JSContext
        if let x = ctx { c = x } else { c = try load(); ctx = c }
        nextCall += 1
        let id = nextCall
        let argsJson = String(decoding: try JSONSerialization.data(withJSONObject: args), as: UTF8.self)
        _ = try run(c, "__run(\(id), \(Self.lit(op)), \(Self.lit(argsJson)), \(Self.lit(env())))")
        let started = Date()
        log.info("\(op, privacy: .public) #\(id) started")
        var idle = 0
        while true {
            let t = try run(c, "__take(\(id))")
            let d = (try? JSONSerialization.jsonObject(with: Data(t.utf8))) as? [String: Any] ?? [:]
            if let state = d["state"] as? String, state != "pending" {
                let ms = Int(Date().timeIntervalSince(started) * 1000)
                if state == "ok" {
                    log.info("\(op, privacy: .public) #\(id) ok in \(ms) ms")
                    return d["value"] ?? NSNull()
                }
                let message = d["message"] as? String ?? "YouTube engine error"
                log.error("\(op, privacy: .public) #\(id) failed in \(ms) ms: \(message, privacy: .public)")
                throw EngineError(kind: d["kind"] as? String ?? "OTHER", message: message)
            }
            let drained = try run(c, "__drain()")
            let reqs = (try? JSONSerialization.jsonObject(with: Data(drained.utf8))) as? [[String: Any]] ?? []
            if reqs.isEmpty {
                // nothing asked: the engine is between steps; look again shortly
                idle += 1
                if idle > 3000 || Date().timeIntervalSince(started) > 60 {
                    log.error("\(op, privacy: .public) #\(id) gave no answer")
                    throw EngineError(kind: "OTHER", message: "The YouTube engine didn't answer")
                }
                try await Task.sleep(nanoseconds: 5_000_000)
                continue
            }
            idle = 0
            let answers = await withTaskGroup(of: (Int, String).self) { g in
                for r in reqs {
                    let rid = (r["rid"] as? NSNumber)?.intValue ?? 0
                    let req = Request(r)
                    g.addTask { (rid, await EngineHost.answer(req)) }
                }
                var out: [(Int, String)] = []
                for await a in g { out.append(a) }
                return out
            }
            for (rid, a) in answers { _ = try run(c, "__reply(\(rid), \(Self.lit(a)))") }
        }
    }

    /// Every six hours at most: a newer engine.js from GitHub is kept and used from the next call.
    /// A file that doesn't load as an engine is never saved.
    func update() async {
        guard Date().timeIntervalSince(lastCheck) > Self.checkEvery else { return }
        lastCheck = Date()
        guard let (data, resp) = try? await URLSession.shared.data(from: Self.updateURL),
              (resp as? HTTPURLResponse)?.statusCode == 200,
              let text = String(data: data, encoding: .utf8) else { return }
        if ctx == nil { ctx = try? load() }
        guard Self.versionOf(text) > version, Self.start(text) != nil else { return }
        try? text.write(to: cacheFile, atomically: true, encoding: .utf8)
        ctx = nil
    }
}

/// One thing the engine asks for, copied out of the JSON so it can cross into a task.
struct Request: Sendable {
    let kind: String
    let method: String
    let url: String
    let headers: [String: String]
    let body: String?

    init(_ r: [String: Any]) {
        kind = r["kind"] as? String ?? ""
        method = r["method"] as? String ?? "GET"
        url = r["url"] as? String ?? ""
        headers = (r["headers"] as? [String: Any] ?? [:]).compactMapValues { $0 as? String }
        body = r["body"] as? String
    }
}

/// What the app does for one thing the engine asks for; the answer as JSON text.
enum EngineHost {
    static func answer(_ r: Request) async -> String {
        switch r.kind {
        case "visitor":
            if let v = await Visitor.shared.current() { return json(["value": v]) }
            return json(["value": NSNull()])
        case "reroll":
            return json(["value": await Visitor.shared.reroll()])
        case "http":
            do {
                let (status, body) = try await http(r.method, r.url, r.headers, r.body)
                return json(["status": status, "body": body])
            } catch {
                return json(["error": error.localizedDescription])
            }
        default:
            return json(["error": "unknown request"])
        }
    }

    static func http(_ method: String, _ url: String, _ headers: [String: String], _ body: String?) async throws -> (Int, String) {
        guard let u = URL(string: url) else { throw URLError(.badURL) }
        var req = URLRequest(url: u, timeoutInterval: 20)
        req.httpMethod = method
        for (k, v) in headers { req.setValue(v, forHTTPHeaderField: k) }
        if let body { req.httpBody = Data(body.utf8) }
        let (data, resp) = try await URLSession.shared.data(for: req)
        return ((resp as? HTTPURLResponse)?.statusCode ?? 0, String(decoding: data, as: UTF8.self))
    }

    static func json(_ o: Any) -> String {
        String(decoding: (try? JSONSerialization.data(withJSONObject: o)) ?? Data("{}".utf8), as: UTF8.self)
    }
}

/// The visitor identity ("visitorData", what a browser keeps in a cookie): kept for a week.
/// YouTube's bot wall is tied to it, so a walled one is replaced, at most twice in six hours.
actor Visitor {
    static let shared = Visitor()
    private static let keepFor: TimeInterval = 7 * 24 * 3600
    private static let rerollLimit = 2
    private static let rerollWindow: TimeInterval = 6 * 3600
    private static let webVersion = "2.20260708.00.00"
    private static let webAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36"

    private var value: String?
    private var rerolls: [Date] = []

    func current() async -> String? {
        if let value { return value }
        let d = UserDefaults.standard
        if let v = d.string(forKey: "visitorData"), Date().timeIntervalSince1970 - d.double(forKey: "visitorAt") < Self.keepFor {
            value = v
            return v
        }
        value = await fetch()
        return value
    }

    func reroll() async -> Bool {
        let now = Date()
        rerolls.removeAll { now.timeIntervalSince($0) >= Self.rerollWindow }
        guard rerolls.count < Self.rerollLimit else { return false }
        rerolls.append(now)
        guard let fresh = await fetch() else { return false }
        value = fresh
        return true
    }

    private func fetch() async -> String? {
        let body = EngineHost.json(["context": ["client": ["clientName": "WEB", "clientVersion": Self.webVersion,
                                                     "hl": Settings.hl, "gl": Settings.gl, "userAgent": Self.webAgent]]])
        let headers = ["User-Agent": Self.webAgent, "X-YouTube-Client-Name": "1", "X-YouTube-Client-Version": Self.webVersion,
                       "Origin": "https://www.youtube.com", "Referer": "https://www.youtube.com/",
                       "Accept-Language": Settings.hl, "Content-Type": "application/json"]
        guard let (status, text) = try? await EngineHost.http("POST", "https://www.youtube.com/youtubei/v1/visitor_id?prettyPrint=false", headers, body),
              status == 200,
              let d = (try? JSONSerialization.jsonObject(with: Data(text.utf8))) as? [String: Any],
              let v = (d["responseContext"] as? [String: Any])?["visitorData"] as? String, !v.isEmpty else { return nil }
        UserDefaults.standard.set(v, forKey: "visitorData")
        UserDefaults.standard.set(Date().timeIntervalSince1970, forKey: "visitorAt")
        return v
    }
}
