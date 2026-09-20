import Foundation
import Observation

/// Holds the launcher's state and mediates between the views and `BeatorajaConfig`.
///
/// Every change writes through to config.json immediately. There is no Save button on
/// purpose: a config half-applied between the launcher and the game would be worse than the
/// extra write, and the game reads the file at startup regardless.
@Observable
final class LauncherModel {

    var root: URL?
    var status: String = ""
    var isScanning = false
    var isRunning = false

    var songPaths: [String] = []
    var resolution: String = "HD"
    var displayMode: String = "Window"
    var vsync = true
    var maxFps: Int = 300
    /// Upstream stores "no cap" as maxFramePerSecond = 0 and offers no other way to say it,
    /// so the spinner doubles as an on/off switch there. The file keeps that encoding; the
    /// UI does not, because a frame rate of zero is not a frame rate.
    var capFps = true
    var bgaMode: Int = 0
    var bufferSize: Int = 384
    var simultaneousSources: Int = 256
    var bgaExpand: Int = 1
    var sampleRate: Int = 0
    var systemVolume: Double = 1.0
    var keyVolume: Double = 0.5
    var bgVolume: Double = 0.5
    var playerName: String = "player1"
    var knownPlayers: [String] = []

    static let displayModes = ["Window", "Borderless", "Fullscreen"]
    static let bgaModes = ["On", "Auto", "Off"]
    static let bgaExpandModes = ["Full", "Keep aspect ratio", "Off"]
    /// 0 lets the audio device choose.
    static let sampleRates = [0, 44100, 48000, 88200, 96000]

    // The spinner ranges below are copied from upstream's FXML value factories so a value
    // accepted here is a value the JavaFX launcher would also accept.
    static let frameRateRange = 1...50_000
    static let frameRates = [30, 60, 75, 90, 120, 144, 165, 240]
    static let bufferSizes = [128, 256, 384, 512, 768, 1024]
    static let sourceCounts = [32, 64, 128, 256, 512, 1024]
    static let bufferRange = 16...1024
    static let sourcesRange = 8...1024

    private var config: BeatorajaConfig?
    private let runner = GameRunner()
    /// Suppresses write-back while the published values are being filled from disk.
    private var loading = false
    /// SwiftUI fires onChange as the controls take their loaded values, so a write happens
    /// before the user has touched anything. Reporting that as "Saved" is a lie, so the
    /// status only changes when the file on disk actually changed.
    private var lastWritten: Data?

    // MARK: - Loading

    func load() {
        guard let found = BeatorajaConfig.discoverRoot() else {
            status = "No installation found"
            return
        }
        adopt(found)
    }

    func adopt(_ url: URL) {
        guard BeatorajaConfig.isInstallation(url) else {
            status = "That folder has no skin/ inside"
            return
        }
        let cfg = BeatorajaConfig(root: url)
        loading = true
        config = cfg
        root = url
        songPaths = cfg.songFolders
        resolution = cfg.resolution
        displayMode = cfg.fullscreen ? "Fullscreen" : "Window"
        vsync = cfg.vsync
        capFps = cfg.maxFps > 0
        maxFps = cfg.maxFps > 0 ? cfg.maxFps : 300
        bgaMode = cfg.bgaMode
        bufferSize = cfg.bufferSize
        simultaneousSources = cfg.simultaneousSources
        bgaExpand = cfg.bgaExpand
        sampleRate = cfg.sampleRate
        systemVolume = cfg.systemVolume
        keyVolume = cfg.keyVolume
        bgVolume = cfg.bgVolume
        playerName = cfg.playerName
        knownPlayers = cfg.knownPlayers
        loading = false
        // Reading fills in keys the file never had (volumes, sample rate, BGA expand), so the
        // very first write is real but is the launcher normalising, not the user saving.
        apply()
        status = "Ready"
    }

    // MARK: - Saving

    func apply() {
        guard !loading, var cfg = config else { return }
        cfg.songFolders = songPaths
        cfg.resolution = resolution
        cfg.fullscreen = displayMode != "Window"
        cfg.vsync = vsync
        cfg.maxFps = capFps ? maxFps : 0
        cfg.bgaMode = bgaMode
        cfg.bufferSize = bufferSize
        cfg.simultaneousSources = simultaneousSources
        cfg.bgaExpand = bgaExpand
        cfg.sampleRate = sampleRate
        cfg.systemVolume = systemVolume
        cfg.keyVolume = keyVolume
        cfg.bgVolume = bgVolume
        cfg.playerName = playerName
        config = cfg
        do {
            let data = try cfg.encoded()
            guard data != lastWritten else { return }
            try cfg.save()
            let normalising = lastWritten == nil
            lastWritten = data
            if !normalising { status = "Saved" }
        } catch {
            status = "Could not save: \(error.localizedDescription)"
        }
    }

    // MARK: - Actions

    func addSongPath(_ url: URL) {
        guard !songPaths.contains(url.path) else { return }
        songPaths.append(url.path)
        apply()
    }

    func removeSongPaths(_ offsets: IndexSet) {
        songPaths.remove(atOffsets: offsets)
        apply()
    }

    func addPlayer(_ name: String) {
        guard !name.isEmpty, !knownPlayers.contains(name) else { return }
        knownPlayers.append(name)
        playerName = name
        apply()
    }

    func rescan() {
        guard let root else { return }
        isScanning = true
        status = "Scanning…"
        runner.scanLibrary(root: root) { [weak self] result in
            self?.isScanning = false
            self?.status = result
        }
    }

    func play() {
        guard let cfg = config, let root else { return }
        apply()
        guard let app = GameRunner.findGameApp(near: Bundle.main.bundleURL, root: root) else {
            status = "beatoraja.app not found next to this launcher"
            return
        }
        runner.launch(app: app, root: cfg.root, fpsCap: maxFps)
        status = runner.lastError ?? "Launched"
    }
}
