import Foundation

/// Reads and writes beatoraja's config.json.
///
/// The file is only ever *edited*, never rewritten from a model: beatoraja fills in defaults
/// for anything absent, so a freshly written config holds a handful of keys while a running
/// game adds dozens more. Replacing the whole document would silently discard every setting
/// this launcher does not know about, so values are merged into the parsed JSON instead.
struct BeatorajaConfig {

    /// Where beatoraja is installed. Everything else is resolved relative to this.
    let root: URL

    private var json: [String: Any]

    var configURL: URL { root.appendingPathComponent("config.json") }

    init(root: URL) {
        self.root = root
        if let data = try? Data(contentsOf: root.appendingPathComponent("config.json")),
           let parsed = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
            json = parsed
        } else {
            json = [:]
        }
    }

    // MARK: - Song folders

    var songFolders: [String] {
        get { json["bmsroot"] as? [String] ?? [] }
        set { json["bmsroot"] = newValue }
    }

    // MARK: - Display

    /// beatoraja stores the resolution as an enum name, not width/height.
    var resolution: String {
        get { json["resolution"] as? String ?? "HD" }
        set { json["resolution"] = newValue }
    }

    static let resolutions = ["SD", "SVGA", "XGA", "HD", "FWXGA", "HDPLUS", "FULLHD", "WQHD", "ULTRAHD"]

    var fullscreen: Bool {
        get { (json["displaymode"] as? String ?? "WINDOW") != "WINDOW" }
        set { json["displaymode"] = newValue ? "FULLSCREEN" : "WINDOW" }
    }

    /// 0 = on, 1 = auto, 2 = off, matching Config.BGA_* in core.
    var bgaEnabled: Bool {
        get { (json["bga"] as? Int ?? 0) != 2 }
        set { json["bga"] = newValue ? 0 : 2 }
    }

    var vsync: Bool {
        get { json["vsync"] as? Bool ?? true }
        set { json["vsync"] = newValue }
    }

    // MARK: - Audio

    private var audio: [String: Any] {
        get { json["audio"] as? [String: Any] ?? [:] }
        set { json["audio"] = newValue }
    }

    /// The hard ceiling on concurrent keysounds. libGDX defaults to 16, which is far too low
    /// for dense charts - notes simply go silent once the voices run out.
    var simultaneousSources: Int {
        get { audio["deviceSimultaneousSources"] as? Int ?? 256 }
        set { audio["deviceSimultaneousSources"] = newValue }
    }

    /// Larger buffers trade latency for robustness against dropouts.
    var bufferSize: Int {
        get { audio["deviceBufferSize"] as? Int ?? 1024 }
        set { audio["deviceBufferSize"] = newValue }
    }

    // MARK: - Player

    var playerName: String {
        get { json["playername"] as? String ?? "player1" }
        set { json["playername"] = newValue }
    }

    // MARK: - Persistence

    func save() throws {
        let data = try JSONSerialization.data(
            withJSONObject: json,
            options: [.prettyPrinted, .sortedKeys, .withoutEscapingSlashes]
        )
        try data.write(to: configURL, options: .atomic)
    }

    // MARK: - Installation discovery

    /// A directory counts as an installation when it contains a skin folder.
    static func isInstallation(_ url: URL) -> Bool {
        var isDir: ObjCBool = false
        let skin = url.appendingPathComponent("skin").path
        return FileManager.default.fileExists(atPath: skin, isDirectory: &isDir) && isDir.boolValue
    }

    static func discoverRoot() -> URL? {
        let home = FileManager.default.homeDirectoryForCurrentUser
        let candidates = [
            home.appendingPathComponent("Games/beatoraja0.8.8-modernchic"),
            home.appendingPathComponent("Games/beatoraja"),
            home.appendingPathComponent("beatoraja"),
        ]
        return candidates.first(where: isInstallation)
    }
}
