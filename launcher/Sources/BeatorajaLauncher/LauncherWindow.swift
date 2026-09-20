import AppKit

/// The launcher window.
///
/// Built with AppKit rather than SwiftUI on purpose: SwiftUI's property wrappers are macros
/// in current SDKs, and the macro plugins ship only with Xcode, not with the Command Line
/// Tools. AppKit needs neither, so this builds anywhere Swift does.
final class LauncherWindow: NSWindowController {

    private var config: BeatorajaConfig?
    private let runner = GameRunner()

    private let rootLabel = NSTextField(labelWithString: "")
    private let statusLabel = NSTextField(labelWithString: "")
    private let folderList = NSTextView()
    private let resolutionPopup = NSPopUpButton()
    private let fullscreenBox = NSButton(checkboxWithTitle: "Fullscreen", target: nil, action: nil)
    private let bgaBox = NSButton(checkboxWithTitle: "Background animation (BGA)", target: nil, action: nil)
    private let vsyncBox = NSButton(checkboxWithTitle: "Vertical sync", target: nil, action: nil)
    private let sourcesField = NSTextField()
    private let bufferField = NSTextField()
    private let fpsField = NSTextField()
    private let nameField = NSTextField()
    private let playButton = NSButton(title: "Play", target: nil, action: nil)

    convenience init() {
        let window = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 540, height: 600),
            styleMask: [.titled, .closable, .miniaturizable],
            backing: .buffered,
            defer: false
        )
        window.title = "beatoraja — Apple Silicon"
        window.center()
        self.init(window: window)
        buildUI()
        loadConfig()
    }

    // MARK: - Layout

    private func buildUI() {
        guard let content = window?.contentView else { return }

        let stack = NSStackView()
        stack.orientation = .vertical
        stack.alignment = .leading
        stack.spacing = 14
        stack.edgeInsets = NSEdgeInsets(top: 18, left: 20, bottom: 18, right: 20)
        stack.translatesAutoresizingMaskIntoConstraints = false
        content.addSubview(stack)
        NSLayoutConstraint.activate([
            stack.leadingAnchor.constraint(equalTo: content.leadingAnchor),
            stack.trailingAnchor.constraint(equalTo: content.trailingAnchor),
            stack.topAnchor.constraint(equalTo: content.topAnchor),
            stack.bottomAnchor.constraint(equalTo: content.bottomAnchor),
        ])

        // Header
        let title = NSTextField(labelWithString: "beatoraja")
        title.font = .systemFont(ofSize: 20, weight: .semibold)
        stack.addArrangedSubview(title)

        rootLabel.font = .monospacedSystemFont(ofSize: 10, weight: .regular)
        rootLabel.textColor = .secondaryLabelColor
        rootLabel.lineBreakMode = .byTruncatingHead
        stack.addArrangedSubview(rootLabel)

        stack.addArrangedSubview(separator())

        // Song folders
        stack.addArrangedSubview(sectionLabel("Song folders"))
        folderList.isEditable = false
        folderList.font = .monospacedSystemFont(ofSize: 10, weight: .regular)
        folderList.drawsBackground = false
        let scroll = NSScrollView()
        scroll.documentView = folderList
        scroll.hasVerticalScroller = true
        scroll.borderType = .bezelBorder
        scroll.translatesAutoresizingMaskIntoConstraints = false
        scroll.heightAnchor.constraint(equalToConstant: 64).isActive = true
        scroll.widthAnchor.constraint(equalToConstant: 500).isActive = true
        stack.addArrangedSubview(scroll)

        let folderButtons = NSStackView(views: [
            button("Add folder…", #selector(addFolder)),
            button("Rescan library", #selector(rescan)),
        ])
        folderButtons.spacing = 8
        stack.addArrangedSubview(folderButtons)

        stack.addArrangedSubview(separator())

        // Display
        stack.addArrangedSubview(sectionLabel("Display"))
        resolutionPopup.addItems(withTitles: BeatorajaConfig.resolutions)
        resolutionPopup.target = self
        resolutionPopup.action = #selector(displayChanged)
        stack.addArrangedSubview(row("Resolution", resolutionPopup))

        for box in [fullscreenBox, bgaBox, vsyncBox] {
            box.target = self
            box.action = #selector(displayChanged)
            stack.addArrangedSubview(box)
        }

        configure(fpsField, width: 70)
        fpsField.stringValue = "60"
        stack.addArrangedSubview(row("Frame cap", fpsField))
        stack.addArrangedSubview(hint("GLFW does not reliably honour vsync in windowed mode on macOS, so the rate is also capped explicitly."))

        stack.addArrangedSubview(separator())

        // Audio
        stack.addArrangedSubview(sectionLabel("Audio"))
        configure(sourcesField, width: 70)
        stack.addArrangedSubview(row("Simultaneous keysounds", sourcesField))
        stack.addArrangedSubview(hint("Notes go silent once this runs out. 16 is the libGDX default and far too low for dense charts."))
        configure(bufferField, width: 70)
        stack.addArrangedSubview(row("Buffer size", bufferField))
        stack.addArrangedSubview(hint("Larger buffers trade latency for fewer dropouts."))

        stack.addArrangedSubview(separator())

        // Player
        stack.addArrangedSubview(sectionLabel("Player"))
        configure(nameField, width: 200)
        stack.addArrangedSubview(row("Name", nameField))

        stack.addArrangedSubview(NSView())

        // Footer
        statusLabel.font = .systemFont(ofSize: 11)
        statusLabel.textColor = .secondaryLabelColor
        playButton.target = self
        playButton.action = #selector(play)
        playButton.keyEquivalent = "\r"
        playButton.bezelStyle = .rounded
        let footer = NSStackView(views: [statusLabel, NSView(), playButton])
        footer.spacing = 10
        footer.translatesAutoresizingMaskIntoConstraints = false
        footer.widthAnchor.constraint(equalToConstant: 500).isActive = true
        stack.addArrangedSubview(footer)
    }

    // MARK: - Small builders

    private func sectionLabel(_ text: String) -> NSTextField {
        let label = NSTextField(labelWithString: text)
        label.font = .systemFont(ofSize: 12, weight: .semibold)
        return label
    }

    private func hint(_ text: String) -> NSTextField {
        let label = NSTextField(wrappingLabelWithString: text)
        label.font = .systemFont(ofSize: 10)
        label.textColor = .tertiaryLabelColor
        label.translatesAutoresizingMaskIntoConstraints = false
        label.widthAnchor.constraint(equalToConstant: 500).isActive = true
        return label
    }

    private func separator() -> NSBox {
        let box = NSBox()
        box.boxType = .separator
        box.translatesAutoresizingMaskIntoConstraints = false
        box.widthAnchor.constraint(equalToConstant: 500).isActive = true
        return box
    }

    private func row(_ label: String, _ control: NSView) -> NSStackView {
        let text = NSTextField(labelWithString: label)
        text.font = .systemFont(ofSize: 12)
        text.translatesAutoresizingMaskIntoConstraints = false
        text.widthAnchor.constraint(equalToConstant: 180).isActive = true
        let stack = NSStackView(views: [text, control])
        stack.spacing = 10
        stack.alignment = .centerY
        return stack
    }

    private func button(_ title: String, _ action: Selector) -> NSButton {
        let b = NSButton(title: title, target: self, action: action)
        b.bezelStyle = .rounded
        return b
    }

    private func configure(_ field: NSTextField, width: CGFloat) {
        field.font = .systemFont(ofSize: 12)
        field.target = self
        field.action = #selector(fieldChanged)
        field.translatesAutoresizingMaskIntoConstraints = false
        field.widthAnchor.constraint(equalToConstant: width).isActive = true
    }

    // MARK: - Config

    private func loadConfig() {
        guard let root = BeatorajaConfig.discoverRoot() else {
            statusLabel.stringValue = "No installation found — choose one"
            chooseRoot()
            return
        }
        adopt(root)
    }

    private func adopt(_ root: URL) {
        let cfg = BeatorajaConfig(root: root)
        config = cfg
        rootLabel.stringValue = root.path
        folderList.string = cfg.songFolders.joined(separator: "\n")
        resolutionPopup.selectItem(withTitle: cfg.resolution)
        fullscreenBox.state = cfg.fullscreen ? .on : .off
        bgaBox.state = cfg.bgaEnabled ? .on : .off
        vsyncBox.state = cfg.vsync ? .on : .off
        sourcesField.stringValue = String(cfg.simultaneousSources)
        bufferField.stringValue = String(cfg.bufferSize)
        nameField.stringValue = cfg.playerName
        statusLabel.stringValue = "Ready"
    }

    private func chooseRoot() {
        let panel = NSOpenPanel()
        panel.canChooseDirectories = true
        panel.canChooseFiles = false
        panel.prompt = "Use folder"
        panel.message = "Pick the folder containing skin/ and config.json"
        guard panel.runModal() == .OK, let url = panel.url else { return }
        guard BeatorajaConfig.isInstallation(url) else {
            statusLabel.stringValue = "That folder has no skin/ inside"
            return
        }
        adopt(url)
    }

    /// Persist immediately: a config half-applied between launcher and game would be worse
    /// than the extra write.
    private func persist() {
        guard let cfg = config else { return }
        do {
            try cfg.save()
            statusLabel.stringValue = "Saved"
        } catch {
            statusLabel.stringValue = "Could not save: \(error.localizedDescription)"
        }
    }

    // MARK: - Actions

    @objc private func displayChanged() {
        guard var cfg = config else { return }
        cfg.resolution = resolutionPopup.titleOfSelectedItem ?? "HD"
        cfg.fullscreen = fullscreenBox.state == .on
        cfg.bgaEnabled = bgaBox.state == .on
        cfg.vsync = vsyncBox.state == .on
        config = cfg
        persist()
    }

    @objc private func fieldChanged() {
        guard var cfg = config else { return }
        if let v = Int(sourcesField.stringValue), v > 0 { cfg.simultaneousSources = v }
        if let v = Int(bufferField.stringValue), v > 0 { cfg.bufferSize = v }
        if !nameField.stringValue.isEmpty { cfg.playerName = nameField.stringValue }
        config = cfg
        persist()
    }

    @objc private func addFolder() {
        let panel = NSOpenPanel()
        panel.canChooseDirectories = true
        panel.canChooseFiles = false
        panel.prompt = "Add"
        guard panel.runModal() == .OK, let url = panel.url, var cfg = config else { return }
        guard !cfg.songFolders.contains(url.path) else { return }
        cfg.songFolders.append(url.path)
        config = cfg
        folderList.string = cfg.songFolders.joined(separator: "\n")
        persist()
    }

    @objc private func rescan() {
        guard let root = config?.root else { return }
        statusLabel.stringValue = "Scanning…"
        runner.scanLibrary(root: root) { [weak self] result in
            self?.statusLabel.stringValue = result
        }
    }

    @objc private func play() {
        guard let cfg = config else { return }
        try? cfg.save()
        guard let app = GameRunner.findGameApp(near: Bundle.main.bundleURL, root: cfg.root) else {
            statusLabel.stringValue = "beatoraja.app not found next to this launcher"
            return
        }
        runner.launch(app: app, root: cfg.root, fpsCap: Int(fpsField.stringValue) ?? 60)
        statusLabel.stringValue = runner.lastError ?? "Launched"
    }
}
