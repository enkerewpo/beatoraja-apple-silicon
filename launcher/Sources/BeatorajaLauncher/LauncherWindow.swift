import AppKit

/// The launcher window.
///
/// Laid out to mirror upstream beatoraja's JavaFX configuration window - the player row on
/// top, the same tabs and field groups, the same action buttons along the bottom - so anyone
/// coming from the Windows build finds what they expect, but built from native macOS
/// controls: NSTabView, NSGridView for baseline-aligned forms, NSBox for the group headers,
/// NSSwitch, NSSlider and NSTableView.
///
/// Only the tabs this port can actually back with settings are present. Upstream also has
/// Input, Music Select, Play Option, Skin, IR, Table and Stream; showing empty shells of
/// those would be worse than leaving them out.
///
/// AppKit rather than SwiftUI: SwiftUI's property wrappers are macros in current SDKs and
/// the macro plugins ship only with Xcode, not with the Command Line Tools.
final class LauncherWindow: NSWindowController {

    private var config: BeatorajaConfig?
    private let runner = GameRunner()

    // Player row
    private let playerPopup = NSPopUpButton()
    private let playerField = NSTextField()

    // Video
    private let displayModePopup = NSPopUpButton()
    private let resolutionPopup = NSPopUpButton()
    private let vsyncSwitch = NSSwitch()
    private let maxFpsField = NSTextField()
    private let bgaPopup = NSPopUpButton()

    // Audio
    private let driverPopup = NSPopUpButton()
    private let bufferPopup = NSPopUpButton()
    private let sourcesSlider = NSSlider()
    private let sourcesValue = NSTextField(labelWithString: "256")

    // Resource
    private let pathTable = NSTableView()
    private var songPaths: [String] = []
    private let removePathButton = NSButton()

    // Footer
    private let statusLabel = NSTextField(labelWithString: "")
    private let spinner = NSProgressIndicator()

    private static let bufferSizes = [256, 384, 512, 1024, 2048, 4096, 8192]
    private static let bgaModes = ["On", "Auto", "Off"]
    private static let displayModes = ["Window", "Borderless", "Fullscreen"]

    convenience init() {
        let window = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 700, height: 470),
            styleMask: [.titled, .closable, .miniaturizable],
            backing: .buffered,
            defer: false
        )
        window.title = "beatoraja configuration"
        window.center()
        self.init(window: window)
        buildUI()
        loadConfig()
    }

    // MARK: - Layout

    private func buildUI() {
        guard let content = window?.contentView else { return }

        // Explicit constraints rather than one top-to-bottom stack: with fixed-height
        // children a stack pinned at both ends leaves its slack below the last view, which
        // stranded the buttons well above the window edge. Pin the ends, let the tabs take
        // whatever is left.
        let player = playerRow()
        let tabView = tabs()
        let actions = footer()

        for view in [player, tabView, actions] {
            view.translatesAutoresizingMaskIntoConstraints = false
            content.addSubview(view)
        }

        NSLayoutConstraint.activate([
            player.leadingAnchor.constraint(equalTo: content.leadingAnchor),
            player.trailingAnchor.constraint(equalTo: content.trailingAnchor),
            player.topAnchor.constraint(equalTo: content.topAnchor),

            tabView.leadingAnchor.constraint(equalTo: content.leadingAnchor),
            tabView.trailingAnchor.constraint(equalTo: content.trailingAnchor),
            tabView.topAnchor.constraint(equalTo: player.bottomAnchor),

            actions.leadingAnchor.constraint(equalTo: content.leadingAnchor),
            actions.trailingAnchor.constraint(equalTo: content.trailingAnchor),
            actions.topAnchor.constraint(equalTo: tabView.bottomAnchor),
            actions.bottomAnchor.constraint(equalTo: content.bottomAnchor),
        ])
    }

    /// Mirrors upstream's "Player ID  [combo] [name] [+]" row above the tabs.
    private func playerRow() -> NSView {
        let label = NSTextField(labelWithString: "Player ID")
        label.font = .systemFont(ofSize: 12)
        label.translatesAutoresizingMaskIntoConstraints = false
        label.widthAnchor.constraint(equalToConstant: 70).isActive = true

        playerPopup.translatesAutoresizingMaskIntoConstraints = false
        playerPopup.widthAnchor.constraint(equalToConstant: 130).isActive = true
        playerPopup.target = self
        playerPopup.action = #selector(playerChanged)

        playerField.target = self
        playerField.action = #selector(playerChanged)

        let add = NSButton(title: "+", target: self, action: #selector(addPlayer))
        add.bezelStyle = .rounded
        add.translatesAutoresizingMaskIntoConstraints = false
        add.widthAnchor.constraint(equalToConstant: 32).isActive = true

        let row = NSStackView(views: [label, playerPopup, playerField, add])
        row.spacing = 8
        row.alignment = .centerY
        row.edgeInsets = NSEdgeInsets(top: 14, left: 20, bottom: 10, right: 20)
        row.translatesAutoresizingMaskIntoConstraints = false
        return row
    }

    private func tabs() -> NSTabView {
        let view = NSTabView()
        view.translatesAutoresizingMaskIntoConstraints = false
        view.heightAnchor.constraint(equalToConstant: 340).isActive = true
        view.addTabViewItem(tab("Video", videoTab()))
        view.addTabViewItem(tab("Audio", audioTab()))
        view.addTabViewItem(tab("Resource", resourceTab()))
        // Upstream opens on Video; match that rather than whatever AppKit picks last
        view.selectTabViewItem(at: 0)
        return view
    }

    private func tab(_ label: String, _ body: NSView) -> NSTabViewItem {
        let item = NSTabViewItem(identifier: label)
        item.label = label
        let host = NSView()
        body.translatesAutoresizingMaskIntoConstraints = false
        host.addSubview(body)
        NSLayoutConstraint.activate([
            body.leadingAnchor.constraint(equalTo: host.leadingAnchor, constant: 20),
            body.trailingAnchor.constraint(lessThanOrEqualTo: host.trailingAnchor, constant: -20),
            body.topAnchor.constraint(equalTo: host.topAnchor, constant: 18),
        ])
        item.view = host
        return item
    }

    // MARK: - Video tab, mirroring DISPLAY and BGA groups

    private func videoTab() -> NSView {
        displayModePopup.addItems(withTitles: Self.displayModes)
        displayModePopup.target = self
        displayModePopup.action = #selector(videoChanged)

        resolutionPopup.addItems(withTitles: BeatorajaConfig.resolutions)
        resolutionPopup.target = self
        resolutionPopup.action = #selector(videoChanged)

        vsyncSwitch.target = self
        vsyncSwitch.action = #selector(videoChanged)

        maxFpsField.translatesAutoresizingMaskIntoConstraints = false
        maxFpsField.widthAnchor.constraint(equalToConstant: 70).isActive = true
        maxFpsField.target = self
        maxFpsField.action = #selector(videoChanged)

        bgaPopup.addItems(withTitles: Self.bgaModes)
        bgaPopup.target = self
        bgaPopup.action = #selector(videoChanged)

        let grid = form([
            .header("Display"),
            .field("Display mode", displayModePopup),
            .field("Resolution", resolutionPopup),
            .field("Vertical sync", vsyncSwitch),
            .field("Max FPS", maxFpsField),
            .header("BGA"),
            .field("Background animation", bgaPopup),
        ])

        let note = hint("GLFW does not reliably honour vsync in windowed mode on macOS, so Max FPS is enforced on top of it.")
        return column([grid, note])
    }

    // MARK: - Audio tab

    private func audioTab() -> NSView {
        driverPopup.addItems(withTitles: ["OpenAL"])
        driverPopup.isEnabled = false
        driverPopup.toolTip = "PortAudio ships Windows binaries only, so OpenAL is the sole option here."

        bufferPopup.addItems(withTitles: Self.bufferSizes.map(String.init))
        bufferPopup.target = self
        bufferPopup.action = #selector(audioChanged)

        sourcesSlider.minValue = 16
        sourcesSlider.maxValue = 1024
        sourcesSlider.target = self
        sourcesSlider.action = #selector(audioChanged)
        sourcesSlider.translatesAutoresizingMaskIntoConstraints = false
        sourcesSlider.widthAnchor.constraint(equalToConstant: 190).isActive = true
        sourcesValue.font = .monospacedDigitSystemFont(ofSize: 11, weight: .regular)
        sourcesValue.textColor = .secondaryLabelColor

        let grid = form([
            .header("Audio output"),
            .field("Driver", driverPopup),
            .field("Buffer size", bufferPopup),
            .field("Simultaneous sources", pair(sourcesSlider, sourcesValue)),
        ])

        let note = hint("Notes fall silent once the voices run out — 16 is the libGDX default and far too low for dense charts. Larger buffers trade latency for fewer dropouts.")
        return column([grid, note])
    }

    // MARK: - Resource tab

    private func resourceTab() -> NSView {
        pathTable.headerView = nil
        pathTable.rowHeight = 20
        pathTable.style = .inset
        pathTable.dataSource = self
        pathTable.delegate = self
        pathTable.target = self
        pathTable.action = #selector(pathSelectionChanged)
        let pathColumn = NSTableColumn(identifier: .init("path"))
        pathColumn.width = 580
        pathTable.addTableColumn(pathColumn)

        let scroll = NSScrollView()
        scroll.documentView = pathTable
        scroll.hasVerticalScroller = true
        scroll.borderType = .bezelBorder
        scroll.translatesAutoresizingMaskIntoConstraints = false
        scroll.heightAnchor.constraint(equalToConstant: 140).isActive = true
        scroll.widthAnchor.constraint(equalToConstant: 620).isActive = true

        let add = squareButton("plus", #selector(addPath), "Add a BMS folder")
        removePathButton.image = NSImage(systemSymbolName: "minus", accessibilityDescription: "Remove")
        removePathButton.bezelStyle = .smallSquare
        removePathButton.target = self
        removePathButton.action = #selector(removePath)
        removePathButton.isEnabled = false
        removePathButton.translatesAutoresizingMaskIntoConstraints = false
        removePathButton.widthAnchor.constraint(equalToConstant: 28).isActive = true

        let update = NSButton(title: "Update song database", target: self, action: #selector(rescan))
        update.bezelStyle = .rounded

        let buttons = NSStackView(views: [add, removePathButton, NSView(), update])
        buttons.spacing = 6
        buttons.alignment = .centerY
        buttons.translatesAutoresizingMaskIntoConstraints = false
        buttons.widthAnchor.constraint(equalToConstant: 620).isActive = true

        let header = groupLabel("BMS path")
        return column([header, scroll, buttons])
    }

    // MARK: - Footer, mirroring upstream's action row

    private func footer() -> NSView {
        statusLabel.font = .systemFont(ofSize: 11)
        statusLabel.textColor = .secondaryLabelColor

        spinner.style = .spinning
        spinner.controlSize = .small
        spinner.isDisplayedWhenStopped = false
        spinner.translatesAutoresizingMaskIntoConstraints = false
        spinner.widthAnchor.constraint(equalToConstant: 16).isActive = true

        let play = NSButton(title: "Play", target: self, action: #selector(play))
        play.bezelStyle = .rounded
        play.controlSize = .large
        play.keyEquivalent = "\r"

        let exit = NSButton(title: "Exit", target: self, action: #selector(quit))
        exit.bezelStyle = .rounded

        let row = NSStackView(views: [spinner, statusLabel, NSView(), exit, play])
        row.spacing = 8
        row.alignment = .centerY
        row.edgeInsets = NSEdgeInsets(top: 12, left: 20, bottom: 12, right: 20)
        row.translatesAutoresizingMaskIntoConstraints = false
        return row
    }

    // MARK: - Builders

    /// One row of a form: either a group caption spanning both columns, or a label/control pair.
    enum FormRow {
        case header(String)
        case field(String, NSView)
    }

    /// Builds the whole tab as a single NSGridView.
    ///
    /// One grid per tab rather than one per group: separate grids size their columns
    /// independently, so controls in different groups end up a few points apart and the form
    /// visibly fails to line up. Group captions are merged across both columns so they sit at
    /// the left edge of the label column instead of floating far from it.
    private func form(_ rows: [FormRow]) -> NSGridView {
        let grid = NSGridView(numberOfColumns: 2, rows: 0)
        grid.rowSpacing = 10
        grid.columnSpacing = 12
        // Left-aligned labels, as upstream's window has them. With right alignment the
        // section captions sit at the column's left edge while the labels end at its right,
        // leaving a wide gap between the two; sharing one left edge reads as a single form.
        grid.column(at: 0).xPlacement = .leading
        grid.column(at: 0).width = 150

        for (index, row) in rows.enumerated() {
            switch row {
            case .header(let title):
                let caption = groupLabel(title)
                let gridRow = grid.addRow(with: [caption, NSGridCell.emptyContentView])
                grid.mergeCells(inHorizontalRange: NSRange(location: 0, length: 2),
                                verticalRange: NSRange(location: index, length: 1))
                // Breathing room above a caption, except the first one
                gridRow.topPadding = index == 0 ? 0 : 10

            case .field(let title, let control):
                let caption = NSTextField(labelWithString: title)
                caption.font = .systemFont(ofSize: 12)
                grid.addRow(with: [caption, control])
            }
        }
        return grid
    }

    private func groupLabel(_ text: String) -> NSTextField {
        let l = NSTextField(labelWithString: text.uppercased())
        l.font = .systemFont(ofSize: 10, weight: .semibold)
        l.textColor = .secondaryLabelColor
        return l
    }

    private func hint(_ text: String) -> NSTextField {
        let l = NSTextField(wrappingLabelWithString: text)
        l.font = .systemFont(ofSize: 10)
        l.textColor = .tertiaryLabelColor
        l.translatesAutoresizingMaskIntoConstraints = false
        l.widthAnchor.constraint(equalToConstant: 620).isActive = true
        return l
    }

    private func column(_ views: [NSView]) -> NSStackView {
        let s = NSStackView(views: views)
        s.orientation = .vertical
        s.alignment = .leading
        s.spacing = 18
        return s
    }

    private func pair(_ a: NSView, _ b: NSView) -> NSStackView {
        let s = NSStackView(views: [a, b])
        s.spacing = 8
        s.alignment = .centerY
        return s
    }

    private func squareButton(_ symbol: String, _ action: Selector, _ tip: String) -> NSButton {
        let b = NSButton()
        b.image = NSImage(systemSymbolName: symbol, accessibilityDescription: tip)
        b.bezelStyle = .smallSquare
        b.target = self
        b.action = action
        b.toolTip = tip
        b.translatesAutoresizingMaskIntoConstraints = false
        b.widthAnchor.constraint(equalToConstant: 28).isActive = true
        return b
    }

    // MARK: - Config

    private func loadConfig() {
        guard let root = BeatorajaConfig.discoverRoot() else {
            statusLabel.stringValue = "No installation found"
            chooseRoot()
            return
        }
        adopt(root)
    }

    private func adopt(_ root: URL) {
        let cfg = BeatorajaConfig(root: root)
        config = cfg
        songPaths = cfg.songFolders
        pathTable.reloadData()

        playerPopup.removeAllItems()
        playerPopup.addItems(withTitles: cfg.knownPlayers)
        playerPopup.selectItem(withTitle: cfg.playerName)
        playerField.stringValue = cfg.playerName

        displayModePopup.selectItem(withTitle: cfg.fullscreen ? "Fullscreen" : "Window")
        resolutionPopup.selectItem(withTitle: cfg.resolution)
        vsyncSwitch.state = cfg.vsync ? .on : .off
        maxFpsField.stringValue = String(cfg.maxFps)
        bgaPopup.selectItem(at: cfg.bgaMode)

        bufferPopup.selectItem(withTitle: String(cfg.bufferSize))
        sourcesSlider.doubleValue = Double(cfg.simultaneousSources)
        sourcesValue.stringValue = String(cfg.simultaneousSources)

        window?.title = "beatoraja configuration — \(root.lastPathComponent)"
        statusLabel.stringValue = "Ready"
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

    @objc private func chooseRoot() {
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

    @objc private func videoChanged() {
        guard var cfg = config else { return }
        cfg.fullscreen = displayModePopup.titleOfSelectedItem != "Window"
        cfg.resolution = resolutionPopup.titleOfSelectedItem ?? "HD"
        cfg.vsync = vsyncSwitch.state == .on
        if let fps = Int(maxFpsField.stringValue), fps > 0 { cfg.maxFps = fps }
        cfg.bgaMode = bgaPopup.indexOfSelectedItem
        config = cfg
        persist()
    }

    @objc private func audioChanged() {
        guard var cfg = config else { return }
        let sources = Int(sourcesSlider.doubleValue)
        sourcesValue.stringValue = String(sources)
        cfg.simultaneousSources = sources
        cfg.bufferSize = Int(bufferPopup.titleOfSelectedItem ?? "1024") ?? 1024
        config = cfg
        persist()
    }

    @objc private func playerChanged() {
        guard var cfg = config else { return }
        let name = playerField.stringValue.isEmpty
            ? (playerPopup.titleOfSelectedItem ?? "player1")
            : playerField.stringValue
        cfg.playerName = name
        playerField.stringValue = name
        config = cfg
        persist()
    }

    @objc private func addPlayer() {
        let name = playerField.stringValue
        guard !name.isEmpty, playerPopup.itemTitles.contains(name) == false else { return }
        playerPopup.addItem(withTitle: name)
        playerPopup.selectItem(withTitle: name)
        playerChanged()
    }

    @objc private func pathSelectionChanged() {
        removePathButton.isEnabled = pathTable.selectedRow >= 0
    }

    @objc private func addPath() {
        let panel = NSOpenPanel()
        panel.canChooseDirectories = true
        panel.canChooseFiles = false
        panel.prompt = "Add"
        guard panel.runModal() == .OK, let url = panel.url, var cfg = config else { return }
        guard !songPaths.contains(url.path) else { return }
        songPaths.append(url.path)
        cfg.songFolders = songPaths
        config = cfg
        pathTable.reloadData()
        persist()
    }

    @objc private func removePath() {
        let row = pathTable.selectedRow
        guard row >= 0, row < songPaths.count, var cfg = config else { return }
        songPaths.remove(at: row)
        cfg.songFolders = songPaths
        config = cfg
        pathTable.reloadData()
        pathSelectionChanged()
        persist()
    }

    @objc private func rescan() {
        guard let root = config?.root else { return }
        statusLabel.stringValue = "Scanning…"
        spinner.startAnimation(nil)
        runner.scanLibrary(root: root) { [weak self] result in
            self?.spinner.stopAnimation(nil)
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
        runner.launch(app: app, root: cfg.root, fpsCap: cfg.maxFps)
        statusLabel.stringValue = runner.lastError ?? "Launched"
    }

    @objc private func quit() {
        NSApp.terminate(nil)
    }
}

// MARK: - BMS path table

extension LauncherWindow: NSTableViewDataSource, NSTableViewDelegate {

    func numberOfRows(in tableView: NSTableView) -> Int {
        songPaths.count
    }

    func tableView(_ tableView: NSTableView, viewFor tableColumn: NSTableColumn?, row: Int) -> NSView? {
        let cell = NSTableCellView()
        let text = NSTextField(labelWithString: songPaths[row])
        text.font = .monospacedSystemFont(ofSize: 11, weight: .regular)
        text.lineBreakMode = .byTruncatingHead
        text.translatesAutoresizingMaskIntoConstraints = false
        cell.addSubview(text)
        cell.textField = text
        NSLayoutConstraint.activate([
            text.leadingAnchor.constraint(equalTo: cell.leadingAnchor, constant: 4),
            text.trailingAnchor.constraint(equalTo: cell.trailingAnchor, constant: -4),
            text.centerYAnchor.constraint(equalTo: cell.centerYAnchor),
        ])
        return cell
    }
}
