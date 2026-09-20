import Foundation

/// Starts the game in a separate process.
///
/// It has to be a separate process, not a thread. GLFW requires the first thread of the
/// process on macOS (`-XstartOnFirstThread`), and so does any AppKit or JavaFX event loop -
/// they cannot share one. That is exactly why upstream's JavaFX config window deadlocks the
/// LWJGL3 backend, and why this launcher is a separate app rather than a screen inside the
/// game.
final class GameRunner: ObservableObject {

    @Published private(set) var isRunning = false
    @Published private(set) var lastError: String?

    private var process: Process?

    /// Locate the packaged game. The launcher normally sits next to beatoraja.app inside the
    /// same folder, but a development build is found through the Gradle output too.
    static func findGameApp(near launcherURL: URL, root: URL) -> URL? {
        let candidates = [
            launcherURL.deletingLastPathComponent().appendingPathComponent("beatoraja.app"),
            root.appendingPathComponent("beatoraja.app"),
            FileManager.default.homeDirectoryForCurrentUser
                .appendingPathComponent("Code/beatoraja-desktop/desktop/build/jpackage/beatoraja.app"),
        ]
        return candidates.first { FileManager.default.fileExists(atPath: $0.path) }
    }

    func launch(app: URL, root: URL, fpsCap: Int) {
        guard !isRunning else { return }

        let executable = app
            .appendingPathComponent("Contents/MacOS/beatoraja")

        guard FileManager.default.isExecutableFile(atPath: executable.path) else {
            lastError = "No runnable game at \(executable.path)"
            return
        }

        let task = Process()
        task.executableURL = executable
        // The bundle's launch script picks the installation up from here, so the launcher and
        // the game always agree on which directory they are working with.
        var env = ProcessInfo.processInfo.environment
        env["BEATORAJA_ROOT"] = root.path
        env["ORAJA_FPS"] = String(fpsCap)
        task.environment = env

        task.terminationHandler = { [weak self] _ in
            DispatchQueue.main.async {
                self?.isRunning = false
                self?.process = nil
            }
        }

        do {
            try task.run()
            process = task
            isRunning = true
            lastError = nil
        } catch {
            lastError = "Failed to start: \(error.localizedDescription)"
        }
    }

    func terminate() {
        process?.terminate()
    }

    /// Rebuild the song database by invoking the headless scan tool that ships with the game
    /// bundle. Runs off the main thread so the window stays responsive.
    func scanLibrary(root: URL, completion: @escaping (String) -> Void) {
        DispatchQueue.global(qos: .userInitiated).async {
            guard let app = GameRunner.findGameApp(near: Bundle.main.bundleURL, root: root) else {
                DispatchQueue.main.async { completion("beatoraja.app not found") }
                return
            }
            let java = app.appendingPathComponent("Contents/runtime/Contents/Home/bin/java")
            let classpath = app.appendingPathComponent("Contents/app").path + "/*"

            let task = Process()
            task.executableURL = java
            task.arguments = [
                "-Dbeatoraja.root=\(root.path)",
                "-cp", classpath,
                "com.starxh.beatoraja.desktop.ScanTool",
            ]
            task.currentDirectoryURL = root

            let pipe = Pipe()
            task.standardOutput = pipe
            task.standardError = pipe

            do {
                try task.run()
                let data = pipe.fileHandleForReading.readDataToEndOfFile()
                task.waitUntilExit()
                let output = String(data: data, encoding: .utf8) ?? ""
                // The tool prints a summary line; surface that rather than the whole log.
                let summary = output
                    .split(separator: "\n")
                    .last { $0.contains("scan finished") }
                    .map(String.init) ?? "Scan finished"
                DispatchQueue.main.async { completion(summary.trimmingCharacters(in: .whitespaces)) }
            } catch {
                DispatchQueue.main.async { completion("Scan failed: \(error.localizedDescription)") }
            }
        }
    }
}
