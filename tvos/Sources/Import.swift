import Foundation
import CoreImage.CIFilterBuiltins
import Network
import SwiftUI

/// Bringing subscriptions over with a phone. The TV has no files to pick from, so while this
/// screen is open it runs a small web page on the home network: the phone scans the QR code,
/// picks its export file (or pastes channel links) and sends it here. Nothing goes through the
/// internet; the page closes with the screen.
struct ImportView: View {
    @State private var server = ImportServer()
    @State private var library = Library.shared

    var body: some View {
        HStack(alignment: .center, spacing: 90) {
            VStack(alignment: .leading, spacing: 30) {
                Text("Import your subscriptions").font(.system(size: 56, weight: .bold))
                Text("Scan the code with your phone (on the same Wi-Fi as this Apple TV), then pick the file you exported:")
                    .font(.system(size: 28)).foregroundStyle(.secondary)
                VStack(alignment: .leading, spacing: 14) {
                    step("YouTube", "takeout.google.com → only YouTube → subscriptions.csv")
                    step("NewPipe", "Subscriptions → ⋮ → Export to → File")
                    step("FreeTube", "Settings → Data settings → Export subscriptions")
                    step("Invidious", "Settings → Import/Export → Export subscriptions")
                    step("TubeHaus", "Library → Backup, or any OPML file")
                }
                if let url = server.url {
                    Text("Or open \(url) on any phone or computer").font(.system(size: 26, weight: .medium)).foregroundStyle(Theme.teal)
                }
                if !server.message.isEmpty {
                    Label(server.message, systemImage: server.failed ? "exclamationmark.triangle.fill" : "checkmark.circle.fill")
                        .font(.system(size: 30, weight: .semibold))
                        .foregroundStyle(server.failed ? .red : Theme.teal)
                }
                Text("\(library.subscriptions.count) channels on this Apple TV").font(.system(size: 24)).foregroundStyle(.secondary)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            VStack(spacing: 24) {
                if let url = server.url, let qr = qrCode(url) {
                    Image(uiImage: qr).interpolation(.none).resizable().scaledToFit()
                        .padding(30).background(.white, in: RoundedRectangle(cornerRadius: 24))
                        .frame(width: 520, height: 520)
                } else {
                    RoundedRectangle(cornerRadius: 24).fill(Theme.placeholder).frame(width: 520, height: 520)
                        .overlay { if server.url == nil && !server.failed { ProgressView() } }
                }
                Button("Done") { dismiss() }
            }
        }
        .padding(.horizontal, 60)
        .onAppear { server.start() }
        .onDisappear { server.stop() }
    }

    @Environment(\.dismiss) private var dismiss

    private func step(_ app: String, _ how: String) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 16) {
            Text(app).font(.system(size: 26, weight: .bold)).frame(width: 170, alignment: .leading)
            Text(how).font(.system(size: 24)).foregroundStyle(.secondary)
        }
    }

    private func qrCode(_ text: String) -> UIImage? {
        let f = CIFilter.qrCodeGenerator()
        f.message = Data(text.utf8)
        f.correctionLevel = "M"
        guard let out = f.outputImage?.transformed(by: CGAffineTransform(scaleX: 12, y: 12)),
              let cg = CIContext().createCGImage(out, from: out.extent) else { return nil }
        return UIImage(cgImage: cg)
    }
}

/// The page on the home network: GET / is the form, POST /import takes the file as text.
@MainActor
@Observable
final class ImportServer {
    private(set) var url: String?
    private(set) var message = ""
    private(set) var failed = false
    private var listener: NWListener?

    func start() {
        guard listener == nil else { return }
        // 8080 when it's free (the CI check sends a file there), any port otherwise
        let l = (try? NWListener(using: .tcp, on: 8080)) ?? (try? NWListener(using: .tcp))
        guard let l else { fail("Couldn't open the import page"); return }
        l.newConnectionHandler = { [weak self] c in
            Task { @MainActor in self?.serve(c) }
        }
        l.stateUpdateHandler = { [weak self] state in
            Task { @MainActor in
                guard let self else { return }
                switch state {
                case .ready:
                    if let port = l.port?.rawValue {
                        if let ip = Self.address() { self.url = "http://\(ip):\(port)/" } else { self.fail("This Apple TV isn't on a network") }
                    }
                case .failed(let e):
                    self.fail(e.localizedDescription)
                default: break
                }
            }
        }
        l.start(queue: .main)
        listener = l
    }

    func stop() {
        listener?.cancel()
        listener = nil
    }

    private func fail(_ m: String) {
        message = m
        failed = true
    }

    private func serve(_ c: NWConnection) {
        c.start(queue: .main)
        receive(c, Data())
    }

    private func receive(_ c: NWConnection, _ buffer: Data) {
        c.receive(minimumIncompleteLength: 1, maximumLength: 1 << 16) { [weak self] data, _, complete, error in
            Task { @MainActor in
                guard let self else { c.cancel(); return }
                var b = buffer
                if let data { b.append(data) }
                if let req = Request(b) {
                    await self.handle(req, c)
                } else if complete || error != nil || b.count > 32_000_000 {
                    c.cancel()
                } else {
                    self.receive(c, b)
                }
            }
        }
    }

    private func handle(_ r: Request, _ c: NWConnection) async {
        switch (r.method, r.path) {
        case ("GET", "/"), ("GET", "/index.html"):
            reply(c, 200, "text/html; charset=utf-8", Data(Self.page.utf8))
        case ("POST", "/import"):
            let text = String(decoding: r.body, as: UTF8.self)
            let n = await importText(text)
            reply(c, 200, "application/json", Data(EngineHost.json(["added": n.added, "found": n.found, "format": n.format]).utf8))
        default:
            reply(c, 404, "text/plain", Data("Not found".utf8))
        }
    }

    private func reply(_ c: NWConnection, _ status: Int, _ type: String, _ body: Data) {
        let head = "HTTP/1.1 \(status) \(status == 200 ? "OK" : "Not Found")\r\nContent-Type: \(type)\r\nContent-Length: \(body.count)\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
        c.send(content: Data(head.utf8) + body, completion: .contentProcessed { _ in c.cancel() })
    }

    /// reads the file, adds its channels, fills in names and pictures it didn't have
    private func importText(_ text: String) async -> (added: Int, found: Int, format: String) {
        let r = Transfer.parse(text)
        var channels = r.channels
        // links and @handles need YouTube to say which channel they are
        for link in r.links.prefix(200) {
            if let x = try? await YT.resolve(link), x.kind == "channel" {
                channels.append(Channel(id: x.id, name: "", avatar: ""))
            }
        }
        guard !channels.isEmpty else {
            fail("Nothing to import was found in that file")
            return (0, 0, r.format)
        }
        let added = Library.shared.subscribe(channels)
        failed = false
        message = added > 0 ? "Added \(added) channels (\(channels.count) in the file)" : "All \(channels.count) channels were already here"
        Task { await Self.fillIn() }
        Task { await SubFeed.shared.load(force: true) }
        return (added, channels.count, r.format)
    }

    /// channels imported without a name or picture get them from their pages, four at a time
    static func fillIn() async {
        let todo = Library.shared.subscriptions.filter { $0.avatar.isEmpty || $0.name.isEmpty }.map(\.id)
        await withTaskGroup(of: Channel?.self) { g in
            var it = todo.makeIterator()
            for _ in 0..<4 { if let id = it.next() { g.addTask { try? await YT.channel(id).asChannel } } }
            for await c in g {
                if let c { Library.shared.refresh(c) }
                if let id = it.next() { g.addTask { try? await YT.channel(id).asChannel } }
            }
        }
    }

    /// this TV's address on the home network (Ethernet or Wi-Fi)
    static func address() -> String? {
        var list: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&list) == 0, let first = list else { return nil }
        defer { freeifaddrs(list) }
        var found: [String: String] = [:]
        for p in sequence(first: first, next: { $0.pointee.ifa_next }) {
            let i = p.pointee
            guard let sa = i.ifa_addr, sa.pointee.sa_family == UInt8(AF_INET), (Int32(i.ifa_flags) & IFF_LOOPBACK) == 0 else { continue }
            var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            guard getnameinfo(sa, socklen_t(sa.pointee.sa_len), &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST) == 0 else { continue }
            let ip = String(cString: host)
            if ip.hasPrefix("169.254.") { continue }
            found[String(cString: i.ifa_name)] = ip
        }
        return found["en0"] ?? found["en1"] ?? found.sorted { $0.key < $1.key }.first?.value
    }

    /// one HTTP request, once all of it has arrived
    struct Request {
        var method: String
        var path: String
        var body: Data

        init?(_ b: Data) {
            guard let end = b.range(of: Data("\r\n\r\n".utf8)) else { return nil }
            let head = String(decoding: b[b.startIndex..<end.lowerBound], as: UTF8.self)
            let lines = head.components(separatedBy: "\r\n")
            let first = lines.first?.split(separator: " ") ?? []
            guard first.count >= 2 else { return nil }
            method = String(first[0])
            path = String(first[1].split(separator: "?").first ?? "/")
            var length = 0
            for l in lines.dropFirst() {
                let kv = l.split(separator: ":", maxSplits: 1)
                if kv.count == 2, kv[0].trimmingCharacters(in: .whitespaces).lowercased() == "content-length" {
                    length = Int(kv[1].trimmingCharacters(in: .whitespaces)) ?? 0
                }
            }
            let bodyStart = end.upperBound
            guard b.endIndex - bodyStart >= length else { return nil }
            body = b[bodyStart..<(bodyStart + length)]
        }
    }

    static let page = """
    <!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
    <title>TubeHaus · Import</title>
    <style>
    :root{color-scheme:dark}body{font:16px/1.5 -apple-system,system-ui,sans-serif;background:#0f0f0f;color:#f1f1f1;margin:0;padding:24px;max-width:560px;margin:auto}
    h1{font-size:24px;margin:8px 0 4px}p{color:#aaa;margin:4px 0 16px}
    .card{background:#212121;border-radius:14px;padding:16px;margin:12px 0}
    label{display:block;font-weight:600;margin-bottom:8px}input[type=file]{width:100%;color:#ccc}
    textarea{width:100%;box-sizing:border-box;min-height:110px;background:#121212;color:#f1f1f1;border:1px solid #333;border-radius:10px;padding:10px;font:inherit}
    button{width:100%;padding:14px;border:0;border-radius:12px;background:#2dd4bf;color:#062b27;font-size:17px;font-weight:700;margin-top:8px}
    #out{margin-top:16px;font-weight:600}small{color:#888}li{margin:4px 0}
    </style></head><body>
    <h1>Send subscriptions to the Apple TV</h1>
    <p>Everything stays on your home network.</p>
    <div class="card"><label>Export file</label><input id="file" type="file">
    <small><ul>
    <li><b>YouTube</b>: takeout.google.com → deselect all → YouTube → "All YouTube data included" → only subscriptions → unzip → <i>subscriptions.csv</i></li>
    <li><b>NewPipe</b>: Subscriptions → ⋮ → Export to → File</li>
    <li><b>FreeTube</b>: Settings → Data settings → Export subscriptions</li>
    <li><b>Invidious</b>: Settings → Import/Export</li>
    <li><b>TubeHaus</b> backup, or any OPML file</li></ul></small></div>
    <div class="card"><label>…or paste channel links, @handles or channel IDs (one per line)</label><textarea id="text" placeholder="https://www.youtube.com/@channel"></textarea></div>
    <button id="go">Send to Apple TV</button><div id="out"></div>
    <script>
    document.getElementById('go').onclick=async()=>{
      const out=document.getElementById('out');const f=document.getElementById('file').files[0];
      let body=document.getElementById('text').value;
      if(f){body=await f.text()+"\\n"+body}
      if(!body.trim()){out.textContent='Pick a file or paste some links first.';return}
      out.textContent='Sending…';
      try{const r=await fetch('/import',{method:'POST',headers:{'Content-Type':'text/plain'},body});const j=await r.json();
        out.textContent=j.found?('Done: '+j.added+' new channels ('+j.found+' found). Look at your TV.'):'Nothing to import was found in that.';}
      catch(e){out.textContent='Couldn\\'t reach the Apple TV. Is the import screen still open?'}
    };
    </script></body></html>
    """
}

extension ChannelPage {
    var asChannel: Channel { Channel(id: id, name: name, avatar: avatar) }
}

/// Reads another app's export: YouTube Takeout (CSV), NewPipe, FreeTube, Invidious, a TubeHaus
/// backup, OPML, or plain text with channel IDs, links and @handles. The same formats as the
/// Android app's Transfer.kt (subscriptions only).
enum Transfer {
    struct Result {
        var format = ""
        var channels: [Channel] = []
        /// links and @handles to resolve
        var links: [String] = []
    }

    private static let ucid = try! NSRegularExpression(pattern: "UC[\\w-]{22}")

    private static func ids(_ s: String) -> [String] {
        ucid.matches(in: s, range: NSRange(s.startIndex..., in: s)).compactMap { Range($0.range, in: s).map { String(s[$0]) } }
    }

    static func parse(_ raw: String) -> Result {
        let text = raw.trimmingCharacters(in: CharacterSet(charactersIn: "\u{FEFF}").union(.whitespacesAndNewlines))
        var r = Result()
        var seen = Set<String>()
        func add(_ id: String?, _ name: String? = nil, _ thumb: String? = nil) {
            guard let id, id.count == 24, id.hasPrefix("UC"), seen.insert(id).inserted else { return }
            r.channels.append(Channel(id: id, name: String((name ?? "").prefix(200)), avatar: thumb ?? ""))
        }
        let json = try? JSONSerialization.jsonObject(with: Data(text.utf8), options: [.fragmentsAllowed])

        if let o = json as? [String: Any] {
            if o["format"] as? String == "tubehaus-backup", let subs = o["subs"] as? [String: Any] {
                for (id, s) in subs { let d = s as? [String: Any]; add(id, d?["name"] as? String, d?["thumb"] as? String) }
                r.format = "TubeHaus"
            } else if let list = o["subscriptions"] as? [Any] {
                for s in list {
                    if let d = s as? [String: Any] {
                        if let url = d["url"] as? String {           // NewPipe
                            if (d["service_id"] as? NSNumber)?.intValue ?? 0 == 0 { add(ids(url).first, d["name"] as? String) }
                            r.format = "NewPipe"
                        } else {                                         // FreeTube
                            add(d["id"] as? String, d["name"] as? String, d["thumbnail"] as? String)
                            r.format = "FreeTube"
                        }
                    } else if let id = s as? String {                    // Invidious
                        add(id)
                        r.format = "Invidious"
                    }
                }
            } else {
                // PocketTube: groups of channel ids
                for (_, v) in o { (v as? [Any])?.forEach { e in add((e as? String) ?? ((e as? [String: Any])?["id"] as? String)) } }
                r.format = "PocketTube"
            }
        } else if let a = json as? [[String: Any]] {
            for s in a { add((s["authorId"] ?? s["id"] ?? s["channelId"]) as? String, (s["author"] ?? s["name"] ?? s["title"]) as? String) }
            r.format = "Invidious"
        } else if text.hasPrefix("{") && text.contains("\n") {
            // FreeTube's profiles: one JSON object per line
            for line in text.components(separatedBy: "\n") {
                guard let o = (try? JSONSerialization.jsonObject(with: Data(line.utf8))) as? [String: Any] else { continue }
                for s in o["subscriptions"] as? [[String: Any]] ?? [] { add(s["id"] as? String, s["name"] as? String, s["thumbnail"] as? String) }
            }
            r.format = "FreeTube"
        }
        if !r.channels.isEmpty { return r }

        if text.prefix(500).lowercased().contains("<opml") || text.contains("<outline") {
            let tag = try! NSRegularExpression(pattern: "<outline\\b[^>]*>")
            let title = try! NSRegularExpression(pattern: "\\b(?:title|text)=(?:\"([^\"]*)\"|'([^']*)')")
            for m in tag.matches(in: text, range: NSRange(text.startIndex..., in: text)) {
                guard let range = Range(m.range, in: text) else { continue }
                let t = String(text[range])
                var name: String?
                if let n = title.firstMatch(in: t, range: NSRange(t.startIndex..., in: t)) {
                    for g in 1...2 { if let gr = Range(n.range(at: g), in: t), !t[gr].isEmpty { name = String(t[gr]) } }
                }
                name = name?.replacingOccurrences(of: "&amp;", with: "&").replacingOccurrences(of: "&quot;", with: "\"")
                    .replacingOccurrences(of: "&#39;", with: "'").replacingOccurrences(of: "&lt;", with: "<").replacingOccurrences(of: "&gt;", with: ">")
                add(ids(t).first, name)
            }
            r.format = "OPML"
            if !r.channels.isEmpty { return r }
        }

        // CSV (YouTube Takeout: Channel Id,Channel Url,Channel Title) or plain lines
        for line in text.components(separatedBy: .newlines) {
            let l = line.trimmingCharacters(in: .whitespaces)
            if l.isEmpty { continue }
            if let id = ids(l).first {
                let cells = l.split(separator: ",", omittingEmptySubsequences: false).map { $0.trimmingCharacters(in: .whitespaces).trimmingCharacters(in: CharacterSet(charactersIn: "\"")) }
                let name = cells.count >= 3 && ids(cells[2]).isEmpty ? cells[2] : nil
                add(id, name)
            } else if l.hasPrefix("@") || l.contains("youtube.com/") || l.contains("youtu.be/") {
                r.links.append(l.components(separatedBy: .whitespaces)[0])
            }
        }
        r.format = r.format.isEmpty || r.format == "OPML" ? "YouTube (CSV)" : r.format
        return r
    }
}
