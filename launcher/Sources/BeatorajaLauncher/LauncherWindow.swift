import AppKit

/// The launcher window: tabs across the top, a form per tab, and a Play button in the footer.
///
/// The shape follows upstream beatoraja's configuration window, which is what anyone coming
/// from the Windows build expects. Layout uses NSGridView so labels and controls line up on
/// a real baseline grid rather than being stacked by hand.
///
/// AppKit rather than SwiftUI: SwiftUI's property wrappers are macros in current SDKs, and
/// the macro plugins ship only with Xcode, not with the Command Line Tools.
final class LauncherWindow: NSWindowController {

    private var config: BeatorajaConfig?
    private let runner = GameRunner()

    // Header
    private let iconView = NSImageView()
    private let rootLabel = NSTextField(labelWithString: "")

    // Library tab
    private let folderTable = NSTableView()
    private var folders: [String] = []
    private let removeButton = NSButton()

    // Display tab
    private let resolutionPopup = NSPopUpButton()
    private let fullscreenSwitch = NSSwitch()
    private let bgaSwitch = NSSwitch()
    private let vsyncSwitch = NSSwitch()
    private let fpsSlider = NSSlider()
    private let fpsValue = NSTextField(labelWithString: "60")

    // Audio tab
    private let sourcesSlider = NSSlider()
    private let sourcesValue = NSTextField(labelWithString: "256")
    private let bufferPopup = NSPopUpButton()

    // Player tab
    private let nameField = NSTextField()

    // Footer
    private let statusLabel = NSTextField(labelWithString: "")
    private let spinner = NSProgressIndicator()
    private let playButton = NSButton()

    private static let bufferSizes = [256, 512, 1024, 2048, 4096, 8192]

    convenience init() {
        let window = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 660, height: 430),
            styleMask: [.titled, .closable, .miniaturizable],
            backing: .buffered,
            defer: false
        )
        window.title = "beatoraja"
        window.titlebarAppearsTransparent = true
        window.center()
        self.init(window: window)
        buildUI()
        loadConfig()
    }

    // MARK: - Layout

    private func buildUI() {
        guard let content = window?.contentView else { return }

        let root = NSStackView()
        root.orientation = .vertical
        root.alignment = .leading
        root.spacing = 0
        root.translatesAutoresizingMaskIntoConstraints = false
        content.addSubview(root)
        NSLayoutConstraint.activate([
            root.leadingAnchor.constraint(equalTo: content.leadingAnchor),
            root.trailingAnchor.constraint(equalTo: content.trailingAnchor),
            root.topAnchor.constraint(equalTo: content.topAnchor),
            root.bottomAnchor.constraint(equalTo: content.bottomAnchor),
        ])

        root.addArrangedSubview(makeHeader())
        root.addArrangedSubview(makeTabs())
        root.addArrangedSubview(makeFooter())
    }

    private func makeHeader() -> NSView {
        iconView.image = NSImage(named: NSImage.applicationIconName)
        iconView.imageScaling = .scaleProportionallyUpOrDown
        iconView.translatesAutoresizingMaskIntoConstraints = false
        iconView.widthAnchor.constraint(equalToConstant: 42).isActive = true
        iconView.heightAnchor.constraint(equalToConstant: 42).isActive = true

        let title = NSTextField(labelWithString: "beatoraja")
        title.font = .systemFont(ofSize: 15, weight: .semibold)

        let subtitle = NSTextField(labelWithString: "Apple Silicon")
        subtitle.font = .systemFont(ofSize: 11)
        subtitle.textColor = .secondaryLabelColor

        let titles = NSStackView(views: [title, subtitle])
        titles.orientation = .vertical
        titles.alignment = .leading
        titles.spacing = 1

        rootLabel.font = .monospacedSystemFont(ofSize: 10, weight: .regular)
        rootLabel.textColor = .tertiaryLabelColor
        rootLabel.lineBreakMode = .byTruncatingHead
        rootLabel.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)

        let changeButton = NSButton(title: "Change…", target: self, action: #selector(chooseRoot))
        changeButton.bezelStyle = .rounded
        changeButton.controlSize = .small

        let row = NSStackView(views: [iconView, titles, NSView(), rootLabel, changeButton])
        row.spacing = 10
        row.alignment = .centerY
        row.edgeInsets = NSEdgeInsets(top: 12, left: 20, bottom: 12, right: 20)
        row.translatesAutoresizingMaskIntoConstraints = false
        row.widthAnchor.constraint(equalToConstant: 660).isActive = true
        return row
    }

    private func makeTabs() -> NSTabView {
        let tabs = NSTabView()
        tabs.translatesAutoresizingMaskIntoConstraints = false
        tabs.widthAnchor.constraint(equalToConstant: 660).isActive = true
        tabs.heightAnchor.constraint(equalToConstant: 300).isActive = true

        tabs.addTabViewItem(tab("Library", libraryTab()))
        tabs.addTabViewItem(tab("Display", displayTab()))
        tabs.addTabViewItem(tab("Audio", audioTab()))
        tabs.addTabViewItem(tab("Player", playerTab()))
        return tabs
    }

    private func tab(_ label: String, _ view: NSView) -> NSTabViewItem {
        let item = NSTabViewItem(identifier: label)
        item.label = label
        let host = NSView()
        view.translatesAutoresizingMaskIntoConstraints = false
        host.addSubview(view)
        NSLayoutConstraint.activate([
            view.leadingAnchor.constraint(equalTo: host.leadingAnchor, constant: 18),
            view.trailingAnchor.constraint(equalTo: host.trailingAnchor, constant: -18),
            view.topAnchor.constraint(equalTo: host.topAnchor, constant: 16),
        ])
        item.view = host
        return item
    }

    // MARK: - Tabs

    private func libraryTab() -> NSView {
        folderTable.headerView = nil
        folderTable.rowHeight = 20
        folderTable.style = .inset
        folderTable.dataSource = self
        folderTable.delegate = self
        folderTable.target = self
        folderTable.action = #selector(folderSelectionChanged)
        let column = NSTableColumn(identifier: .init("path"))
        column.width = 560
        folderTable.addTableColumn(column)

        let scroll = NSScrollView()
        scroll.documentView = folderTable
        scroll.hasVerticalScroller = true
        scroll.borderType = .bezelBorder
        scroll.translatesAutoresizingMaskIntoConstraints = false
        scroll.heightAnchor.constraint(equalToConstant: 150).isActive = true

        let add = toolButton("plus", #selector(addFolder), "Add a song folder")
        removeButton.image = NSImage(systemSymbolName: "minus", accessibilityDescription: "Remove")
        removeButton.bezelStyle = .smallSquare
        removeButton.target = self
        removeButton.action = #selector(removeFolder)
        removeButton.isEnabled = false
        removeButton.translatesAutoresizingMaskIntoConstraints = false
        removeButton.widthAnchor.constraint(equalToConstant: 28).isActive = true

        let rescan = NSButton(title: "Rescan library", target: self, action: #selector(rescan))
        rescan.bezelStyle = .rounded

        let buttons = NSStackView(views: [add, removeButton, NSView(), rescan])
        buttons.spacing = 6
        buttons.alignment = .centerY

        let note = hint("Charts are found by walking these folders. Rescan after adding or removing any.")

        let stack = NSStackView(views: [scroll, buttons, note])
        stack.orientation = .vertical
        stack.alignment = .leading
        stack.spacing = 8
        scroll.widthAnchor.constraint(equalTo: stack.widthAnchor).isActive = true
        buttons.widthAnchor.constraint(equalTo: stack.widthAnchor).isActive = true
        return stack
    }

    private func displayTab() -> NSView {
        resolutionPopup.addItems(withTitles: BeatorajaConfig.resolutions)
        resolutionPopup.target = self
        resolutionPopup.action = #selector(displayChanged)

        for sw in [fullscreenSwitch, bgaSwitch, vsyncSwitch] {
            sw.target = self
            sw.action = #selector(displayChanged)
        }

        fpsSlider.minValue = 30
        fpsSlider.maxValue = 240
        fpsSlider.numberOfTickMarks = 8
        fpsSlider.allowsTickMarkValuesOnly = true
        fpsSlider.target = self
        fpsSlider.action = #selector(fpsChanged)
        fpsSlider.translatesAutoresizingMaskIntoConstraints = false
        fpsSlider.widthAnchor.constraint(equalToConstant: 180).isActive = true
        fpsValue.font = .monospacedDigitSystemFont(ofSize: 11, weight: .regular)
        fpsValue.textColor = .secondaryLabelColor

        let grid = NSGridView(views: [
            [label("Resolution"), resolutionPopup],
            [label("Fullscreen"), fullscreenSwitch],
            [label("Background animation"), bgaSwitch],
            [label("Vertical sync"), vsyncSwitch],
            [label("Frame cap"), pair(fpsSlider, fpsValue)],
        ])
        grid.rowSpacing = 12
        grid.columnSpacing = 14
        grid.column(at: 0).xPlacement = .trailing

        let note = hint("GLFW does not reliably honour vsync in windowed mode on macOS, so the rate is capped explicitly as well.")
        let stack = NSStackView(views: [grid, note])
        stack.orientation = .vertical
        stack.alignment = .leading
        stack.spacing = 14
        return stack
    }

    private func audioTab() -> NSView {
        sourcesSlider.minValue = 16
        sourcesSlider.maxValue = 1024
        sourcesSlider.target = self
        sourcesSlider.action = #selector(audioChanged)
        sourcesSlider.translatesAutoresizingMaskIntoConstraints = false
        sourcesSlider.widthAnchor.constraint(equalToConstant: 180).isActive = true
        sourcesValue.font = .monospacedDigitSystemFont(ofSize: 11, weight: .regular)
        sourcesValue.textColor = .secondaryLabelColor

        bufferPopup.addItems(withTitles: Self.bufferSizes.map(String.init))
        bufferPopup.target = self
        bufferPopup.action = #selector(audioChanged)

        let grid = NSGridView(views: [
            [label("Simultaneous keysounds"), pair(sourcesSlider, sourcesValue)],
            [label("Buffer size"), bufferPopup],
        ])
        grid.rowSpacing = 12
        grid.columnSpacing = 14
        grid.column(at: 0).xPlacement = .trailing

        let note = hint("Notes fall silent once the voices run out — 16 is the libGDX default and far too low for dense charts. Larger buffers trade latency for fewer dropouts.")
        let stack = NSStackView(views: [grid, note])
        stack.orientation = .vertical
        stack.alignment = .leading
        stack.spacing = 14
        return stack
    }

    private func playerTab() -> NSView {
        nameField.translatesAutoresizingMaskIntoConstraints = false
        nameField.widthAnchor.constraint(equalToConstant: 220).isActive = true
        nameField.target = self
        nameField.action = #selector(playerChanged)

        let grid = NSGridView(views: [[label("Name"), nameField]])
        grid.rowSpacing = 12
        grid.columnSpacing = 14
        grid.column(at: 0).xPlacement = .trailing

        let note = hint("Scores and settings are stored per player under player/ in the installation folder.")
        let stack = NSStackView(views: [grid, note])
        stack.orientation = .vertical
        stack.alignment = .leading
        stack.spacing = 14
        return stack
    }

    private func makeFooter() -> NSView {
        statusLabel.font = .systemFont(ofSize: 11)
        statusLabel.textColor = .secondaryLabelColor

        spinner.style = .spinning
        spinner.controlSize = .small
        spinner.isDisplayedWhenStopped = false
        spinner.translatesAutoresizingMaskIntoConstraints = false
        spinner.widthAnchor.constraint(equalToConstant: 16).isActive = true

        playButton.title = "Play"
        playButton.bezelStyle = .rounded
        playButton.controlSize = .large
        playButton.keyEquivalent = "\r"
        playButton.target = self
        playButton.action = #selector(play)

        let row = NSStackView(views: [spinner, statusLabel, NSView(), playButton])
        row.spacing = 8
        row.alignment = .centerY
        row.edgeInsets = NSEdgeInsets(top: 10, left: 20, bottom: 14, right: 20)
        row.translatesAutoresizingMaskIntoConstraints = false
        row.widthAnchor.constraint(equalToConstant: 660).isActive = true
        return row
    }

    // MARK: - Small builders

    private func label(_ text: String) -> NSTextField {
        let l = NSTextField(labelWithString: text)
        l.font = .systemFont(ofSize: 12)
        return l
    }

    private func hint(_ text: String) -> NSTextField {
        let l = NSTextField(wrappingLabelWithString: text)
        l.font = .systemFont(ofSize: 10)
        l.textColor = .tertiaryLabelColor
        l.translatesAutoresizingMaskIntoConstraints = false
        l.widthAnchor.constraint(equalToConstant: 600).isActive = true
        return l
    }

    private func pair(_ a: NSView, _ b: NSView) -> NSStackView {
        let s = NSStackView(views: [a, b])
        s.spacing = 8
        s.alignment = .centerY
        return s
    }

    private func toolButton(_ symbol: String, _ action: Selector, _ tip: String) -> NSButton {
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
        folders = cfg.songFolders
        rootLabel.stringValue = root.path
        folderTable.reloadData()
        resolutionPopup.selectItem(withTitle: cfg.resolution)
        fullscreenSwitch.state = cfg.fullscreen ? .on : .off
        bgaSwitch.state = cfg.bgaEnabled ? .on : .off
        vsyncSwitch.state = cfg.vsync ? .on : .off
        fpsSlider.doubleValue = 60
        fpsValue.stringValue = "60"
        sourcesSlider.doubleValue = Double(cfg.simultaneousSources)
        sourcesValue.stringValue = String(cfg.simultaneousSources)
        bufferPopup.selectItem(withTitle: String(cfg.bufferSize))
        nameField.stringValue = cfg.playerName
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

    @objc private func displayChanged() {
        guard var cfg = config else { return }
        cfg.resolution = resolutionPopup.titleOfSelectedItem ?? "HD"
        cfg.fullscreen = fullscreenSwitch.state == .on
        cfg.bgaEnabled = bgaSwitch.state == .on
        cfg.vsync = vsyncSwitch.state == .on
        config = cfg
        persist()
    }

    @objc private func fpsChanged() {
        fpsValue.stringValue = String(Int(fpsSlider.doubleValue))
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
        guard var cfg = config, !nameField.stringValue.isEmpty else { return }
        cfg.playerName = nameField.stringValue
        config = cfg
        persist()
    }

    @objc private func folderSelectionChanged() {
        removeButton.isEnabled = folderTable.selectedRow >= 0
    }

    @objc private func addFolder() {
        let panel = NSOpenPanel()
        panel.canChooseDirectories = true
        panel.canChooseFiles = false
        panel.prompt = "Add"
        guard panel.runModal() == .OK, let url = panel.url, var cfg = config else { return }
        guard !folders.contains(url.path) else { return }
        folders.append(url.path)
        cfg.songFolders = folders
        config = cfg
        folderTable.reloadData()
        persist()
    }

    @objc private func removeFolder() {
        let row = folderTable.selectedRow
        guard row >= 0, row < folders.count, var cfg = config else { return }
        folders.remove(at: row)
        cfg.songFolders = folders
        config = cfg
        folderTable.reloadData()
        folderSelectionChanged()
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
        runner.launch(app: app, root: cfg.root, fpsCap: Int(fpsSlider.doubleValue))
        statusLabel.stringValue = runner.lastError ?? "Launched"
    }
}

// MARK: - Folder table

extension LauncherWindow: NSTableViewDataSource, NSTableViewDelegate {

    func numberOfRows(in tableView: NSTableView) -> Int {
        folders.count
    }

    func tableView(_ tableView: NSTableView, viewFor tableColumn: NSTableColumn?, row: Int) -> NSView? {
        let cell = NSTableCellView()
        let text = NSTextField(labelWithString: folders[row])
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
