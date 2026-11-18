import CryptoKit
import SwiftUI

/// The people who use this Apple TV, each with their own library, recommendations and TubeHaus
/// server login (Library.swift, Server.swift keep them in that person's UserDefaults). The app asks
/// "Who's watching?" each time it opens; someone can protect theirs with a PIN. As the Android TV app.
enum People {
    struct List: Codable {
        /// who is using it now; "" is the first person, whose things were always in the TV's own UserDefaults
        var current = ""
        /// everyone as "id:name" ("" for the first person)
        var names: [String] = []
        var firstName = ""
        /// person id → their PIN, hashed
        var pins: [String: String] = [:]
    }

    static var list: List {
        get { UserDefaults.standard.data(forKey: "people").flatMap { try? JSONDecoder().decode(List.self, from: $0) } ?? List() }
        set { if let d = try? JSONEncoder().encode(newValue) { UserDefaults.standard.set(d, forKey: "people") } }
    }

    static var current: String { list.current }

    static func defaults(for id: String) -> UserDefaults {
        id.isEmpty ? .standard : (UserDefaults(suiteName: "tubehaus.person." + id) ?? .standard)
    }

    static func id(_ entry: String) -> String { entry.isEmpty ? "" : String(entry.prefix { $0 != ":" }) }

    static func name(_ entry: String) -> String {
        if entry.isEmpty { return list.firstName.isEmpty ? "First person" : list.firstName }
        guard let i = entry.firstIndex(of: ":") else { return entry }
        return String(entry[entry.index(after: i)...])
    }

    /// everyone, the first person included even before anyone was added
    static var everyone: [String] { list.names.isEmpty ? [""] : list.names }

    static func add(_ name: String, firstName: String?) -> String {
        var l = list
        let id = "p" + String(Int(Date().timeIntervalSince1970 * 1000), radix: 36)
        if l.names.isEmpty { l.names = [""]; if let f = firstName { l.firstName = f } }
        l.names.append("\(id):\(name.replacingOccurrences(of: ":", with: ""))")
        list = l
        return id
    }

    /// someone else leaves: their things on this TV are deleted (never the one using it now)
    static func remove(_ id: String) {
        guard id != current else { return }
        if !id.isEmpty { UserDefaults.standard.removePersistentDomain(forName: "tubehaus.person." + id) }
        var l = list
        l.names.removeAll { Self.id($0) == id }
        l.pins[id] = nil
        list = l
    }

    static func hasPin(_ id: String) -> Bool { list.pins[id] != nil }
    static func pinOK(_ id: String, _ pin: String) -> Bool { list.pins[id].map { $0 == hash(id, pin) } ?? true }
    static func setPin(_ id: String, _ pin: String?) {
        var l = list
        l.pins[id] = pin.map { hash(id, $0) }
        list = l
    }

    private static func hash(_ id: String, _ pin: String) -> String {
        SHA256.hash(data: Data("tubehaus-person:\(id):\(pin)".utf8)).map { String(format: "%02x", $0) }.joined()
    }

    /// someone else uses the TV now: their library, their server, Home again
    @MainActor
    static func switchTo(_ id: String) {
        guard id != current else { return }
        PlayerModel.shared.close()
        MusicPlayer.shared.stop()
        var l = list
        l.current = id
        list = l
        Library.shared.switchTo(id)
        Server.shared.reload()
        Recommender.shared.clearCache()
        Nav.shared.go(.home)
        Task { await Server.shared.sync() }
    }
}

/// "Who's watching?": everyone as a big round letter and "Add a person"; OK picks (their PIN first, if they set one)
struct WhoIsWatching: View {
    let done: () -> Void
    @State private var people = People.list
    @State private var asking: String?
    @State private var adding = false

    var body: some View {
        VStack(spacing: 64) {
            Text("Who's watching?").font(.system(size: 64, weight: .semibold))
            HStack(spacing: 56) {
                ForEach(People.everyone, id: \.self) { e in
                    let id = People.id(e)
                    Tile(letter: String(People.name(e).prefix(1)).uppercased(), name: People.name(e), locked: People.hasPin(id)) {
                        if People.hasPin(id) && id != People.current { asking = id } else { pick(id) }
                    }
                    .modifier(FocusIf(on: id == people.current))
                }
                Tile(letter: "+", name: "Add a person", locked: false) { adding = true }
            }
            .focusSection()
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Theme.bg)
        .foregroundStyle(Theme.text)
        .sheet(item: Binding(get: { asking.map(Asked.init) }, set: { asking = $0?.id })) { a in
            PinEntry(title: "PIN for \(People.name(People.everyone.first { People.id($0) == a.id } ?? ""))") { pin in
                guard People.pinOK(a.id, pin) else { return false }
                asking = nil
                pick(a.id)
                return true
            }
        }
        .sheet(isPresented: $adding) {
            AddPerson { adding = false; people = People.list; done() }
        }
    }

    private func pick(_ id: String) {
        People.switchTo(id)
        done()
    }

    private struct Asked: Identifiable { let id: String }

    private struct FocusIf: ViewModifier {
        let on: Bool
        func body(content: Content) -> some View { if on { content.firstFocus() } else { content } }
    }

    struct Tile: View {
        let letter: String
        let name: String
        let locked: Bool
        let action: () -> Void

        var body: some View {
            Button(action: action) { EmptyView() }
                .buttonStyle(Look(letter: letter, name: name, locked: locked))
        }

        private struct Look: ButtonStyle {
            let letter: String, name: String, locked: Bool
            func makeBody(configuration: Configuration) -> some View { Face(letter: letter, name: name, locked: locked, pressed: configuration.isPressed) }
        }

        private struct Face: View {
            let letter: String, name: String, locked: Bool, pressed: Bool
            @Environment(\.isFocused) private var focused

            var body: some View {
                VStack(spacing: 24) {
                    Text(letter).font(.system(size: 88, weight: .bold))
                        .foregroundStyle(focused ? Color.black : Theme.teal)
                        .frame(width: 220, height: 220)
                        .background(Circle().fill(focused ? Color.white : Theme.chip))
                    Text(locked ? "\(name) 🔒" : name).font(.system(size: 32, weight: focused ? .semibold : .regular))
                        .lineLimit(1).frame(maxWidth: 280)
                }
                .scaleEffect(pressed ? 0.95 : focused ? 1.08 : 1)
                .animation(.easeOut(duration: 0.15), value: focused)
            }
        }
    }
}

/// four to eight digits; `check` says whether it was right (wrong: it empties and says so)
struct PinEntry: View {
    let title: String
    let check: (String) -> Bool
    @State private var pin = ""
    @State private var wrong = false
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(spacing: 40) {
            Text(title).font(Fonts.titleLarge)
            SecureField("PIN", text: $pin)
                .keyboardType(.numberPad)
                .font(Fonts.bodyLarge).frame(width: 600)
                .onSubmit(submit)
                .firstFocus()
            if wrong { Text("Wrong PIN").font(Fonts.bodyMedium).foregroundStyle(Theme.error) }
            HStack(spacing: 24) {
                FilledButton("OK", enabled: pin.count >= 4, action: submit)
                TextButton("Cancel") { dismiss() }
            }
        }
        .padding(80)
    }

    private func submit() {
        let p = pin.filter(\.isNumber)
        guard p.count >= 4 else { return }
        if !check(p) { wrong = true; pin = "" }
    }
}

/// a name for someone new; the first time, a name for the person whose library is here already too
struct AddPerson: View {
    let done: () -> Void
    @State private var name = ""
    @State private var first = People.list.firstName
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(alignment: .leading, spacing: 32) {
            Text("Add a person").font(Fonts.titleLarge)
            Text("They start with an empty library: their own subscriptions, history and recommendations, and their own TubeHaus server login.")
                .font(Fonts.bodySmall).foregroundStyle(Theme.sub).frame(maxWidth: 1100, alignment: .leading)
            if People.list.names.isEmpty {
                Text("Your name (the library on this TV now)").font(Fonts.bodySmall).foregroundStyle(Theme.sub)
                TextField("Your name", text: $first).font(Fonts.bodyLarge).frame(width: 800)
            }
            Text("Their name").font(Fonts.bodySmall).foregroundStyle(Theme.sub)
            TextField("Name", text: $name).font(Fonts.bodyLarge).frame(width: 800).firstFocus()
            HStack(spacing: 24) {
                FilledButton("Add", enabled: !name.trimmingCharacters(in: .whitespaces).isEmpty) {
                    let n = String(name.trimmingCharacters(in: .whitespaces).prefix(30))
                    let id = People.add(n, firstName: People.list.names.isEmpty ? first.trimmingCharacters(in: .whitespaces) : nil)
                    People.switchTo(id)
                    dismiss()
                    done()
                }
                TextButton("Cancel") { dismiss() }
            }
        }
        .padding(80)
    }
}

/// a new PIN for the person using the TV, typed twice; or none
struct SetPin: View {
    @State private var pin = ""
    @State private var again = ""
    @Environment(\.dismiss) private var dismiss
    private let me = People.current

    var body: some View {
        VStack(alignment: .leading, spacing: 32) {
            Text("PIN for \(People.name(People.everyone.first { People.id($0) == me } ?? ""))").font(Fonts.titleLarge)
            Text("Asked before switching to you, so others can't open your library").font(Fonts.bodySmall).foregroundStyle(Theme.sub)
            SecureField("PIN (4 to 8 digits)", text: $pin).keyboardType(.numberPad).font(Fonts.bodyLarge).frame(width: 600).firstFocus()
            SecureField("PIN again", text: $again).keyboardType(.numberPad).font(Fonts.bodyLarge).frame(width: 600)
            if again.count >= 4 && again != pin { Text("The two PINs are different").font(Fonts.bodySmall).foregroundStyle(Theme.error) }
            HStack(spacing: 24) {
                let ok = pin.filter(\.isNumber).count >= 4 && pin.count <= 8 && pin == again && pin.allSatisfy(\.isNumber)
                FilledButton("OK", enabled: ok) { People.setPin(me, pin); dismiss() }
                if People.hasPin(me) { TextButton("Remove PIN", color: Theme.error) { People.setPin(me, nil); dismiss() } }
                TextButton("Cancel") { dismiss() }
            }
        }
        .padding(80)
    }
}
