import SwiftUI
import AppKit

/// The launcher window.
///
/// The field layout is taken from upstream beatoraja's JavaFX views — VideoConfigurationView,
/// AudioConfigurationView and ResourceConfigurationView — control for control, including the
/// spinner ranges, so a value accepted here is one the Windows launcher would also accept.
/// What changes is the rendering: SwiftUI's grouped form rather than a JavaFX GridPane.
///
/// Only the tabs this port can actually back with settings are present. Upstream also has
/// Input, Music Select, Play Option, Skin, IR, Table and Stream; empty shells of those would
/// be worse than their absence.
struct LauncherView: View {

    @State private var model = LauncherModel()
    @State private var newPlayer = ""

    var body: some View {
        VStack(spacing: 0) {
            playerRow
            Divider()
            tabs
            Divider()
            footer
        }
        .frame(width: 640, height: 560)
        .onAppear {
            model.load()
            // Without this the first combo box grabs the caret on open and shows a focus ring
            // before the user has done anything.
            DispatchQueue.main.async { NSApp.keyWindow?.makeFirstResponder(nil) }
        }
    }

    // MARK: - Player row

    private var playerRow: some View {
        HStack(spacing: 10) {
            Text("Player ID")
                .frame(width: 66, alignment: .leading)

            Picker("", selection: $model.playerName) {
                ForEach(model.knownPlayers, id: \.self) { Text($0) }
            }
            .labelsHidden()
            .frame(width: 140)
            .onChange(of: model.playerName) { model.apply() }

            TextField("New profile", text: $newPlayer)
                .textFieldStyle(.roundedBorder)
                .onSubmit { addPlayer() }

            Button("+", action: addPlayer)
                .disabled(newPlayer.isEmpty)
        }
        .padding(.horizontal, 20)
        .padding(.vertical, 12)
    }

    private func addPlayer() {
        model.addPlayer(newPlayer)
        newPlayer = ""
    }

    // MARK: - Tabs

    private var tabs: some View {
        TabView {
            videoTab.tabItem { Text("Video") }
            audioTab.tabItem { Text("Audio") }
            resourceTab.tabItem { Text("Resource") }
        }
        .padding(.horizontal, 12)
        .padding(.top, 10)
    }

    // MARK: - Video

    private var videoTab: some View {
        Form {
            Section("Display") {
                Picker("Display mode", selection: $model.displayMode) {
                    ForEach(LauncherModel.displayModes, id: \.self) { Text($0) }
                }
                Picker("Resolution", selection: $model.resolution) {
                    ForEach(BeatorajaConfig.resolutions, id: \.self) { Text($0) }
                }
                Toggle("Vertical sync", isOn: $model.vsync)
                Toggle("Limit frame rate", isOn: $model.capFps)
                numberField("Max FPS", value: $model.maxFps,
                            options: LauncherModel.frameRates,
                            range: LauncherModel.frameRateRange)
                    .disabled(!model.capFps)
            }

            Section {
                Picker("Background animation", selection: $model.bgaMode) {
                    ForEach(Array(LauncherModel.bgaModes.enumerated()), id: \.offset) { index, name in
                        Text(name).tag(index)
                    }
                }
                Picker("Expand", selection: $model.bgaExpand) {
                    ForEach(Array(LauncherModel.bgaExpandModes.enumerated()), id: \.offset) { index, name in
                        Text(name).tag(index)
                    }
                }
            } header: {
                Text("BGA")
            } footer: {
                Text("GLFW does not reliably honour vsync in windowed mode on macOS, so Max FPS is enforced on top of it.")
            }
        }
        .formStyle(.grouped)
        .onChange(of: model.displayMode) { model.apply() }
        .onChange(of: model.resolution) { model.apply() }
        .onChange(of: model.vsync) { model.apply() }
        .onChange(of: model.maxFps) { model.apply() }
        .onChange(of: model.capFps) { model.apply() }
        .onChange(of: model.bgaMode) { model.apply() }
        .onChange(of: model.bgaExpand) { model.apply() }
    }

    // MARK: - Audio

    private var audioTab: some View {
        Form {
            Section {
                LabeledContent("Driver") {
                    Text("OpenAL").foregroundStyle(.secondary)
                }
                Picker("Sample rate", selection: $model.sampleRate) {
                    ForEach(LauncherModel.sampleRates, id: \.self) { rate in
                        Text(rate == 0 ? "Device default" : "\(rate) Hz").tag(rate)
                    }
                }
                numberField("Buffer size", value: $model.bufferSize,
                            options: LauncherModel.bufferSizes,
                            range: LauncherModel.bufferRange)
                numberField("Simultaneous sources", value: $model.simultaneousSources,
                            options: LauncherModel.sourceCounts,
                            range: LauncherModel.sourcesRange)
            } header: {
                Text("Output")
            } footer: {
                Text("Notes fall silent once the voices run out — libGDX defaults to 16, far too low for dense charts. PortAudio ships Windows binaries only, so OpenAL is the sole driver here.")
            }

            Section("Volume") {
                volume("System", value: $model.systemVolume)
                volume("Key sounds", value: $model.keyVolume)
                volume("BGM", value: $model.bgVolume)
            }
        }
        .formStyle(.grouped)
        .onChange(of: model.sampleRate) { model.apply() }
        .onChange(of: model.bufferSize) { model.apply() }
        .onChange(of: model.simultaneousSources) { model.apply() }
    }

    // MARK: - Resource

    private var resourceTab: some View {
        Form {
            Section {
                if model.songPaths.isEmpty {
                    Text("No folders yet")
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(model.songPaths, id: \.self) { path in
                        HStack(spacing: 8) {
                            Image(systemName: "folder")
                                .foregroundStyle(.secondary)
                            Text(path)
                                .lineLimit(1)
                                .truncationMode(.head)
                                .help(path)
                            Spacer(minLength: 8)
                            Button {
                                remove(path)
                            } label: {
                                Image(systemName: "minus.circle")
                            }
                            .buttonStyle(.borderless)
                            .foregroundStyle(.secondary)
                            .help("Remove this folder")
                        }
                    }
                }
                Button("Add folder\u{2026}", action: chooseSongFolder)
            } header: {
                Text("BMS path")
            } footer: {
                Text("Charts are found by walking these folders.")
            }

            Section {
                LabeledContent("Song database") {
                    Button("Update", action: model.rescan)
                        .disabled(model.isScanning || model.songPaths.isEmpty)
                }
            } footer: {
                Text("Run this after adding or removing a folder, or after dropping new charts into one.")
            }
        }
        .formStyle(.grouped)
    }

    private func remove(_ path: String) {
        guard let index = model.songPaths.firstIndex(of: path) else { return }
        model.removeSongPaths(IndexSet(integer: index))
    }

    // MARK: - Footer

    private var footer: some View {
        HStack(spacing: 10) {
            if model.isScanning {
                ProgressView().controlSize(.small)
            }
            Text(model.status)
                .font(.caption)
                .foregroundStyle(.secondary)
                .lineLimit(1)

            Spacer()

            if model.root == nil {
                Button("Choose folder…", action: chooseRoot)
            }
            Button("Exit") { NSApp.terminate(nil) }
            Button("Play", action: model.play)
                .keyboardShortcut(.defaultAction)
                .disabled(model.root == nil)
        }
        .padding(.horizontal, 20)
        .padding(.vertical, 12)
    }

    // MARK: - Reusable controls

    /// Upstream uses an editable NumericSpinner here. A combo box is the same idea with the
    /// common values already on the list, so neither typing nor picking is ruled out.
    private func numberField(
        _ label: String,
        value: Binding<Int>,
        options: [Int],
        range: ClosedRange<Int>
    ) -> some View {
        LabeledContent(label) {
            NumericComboBox(value: value, options: options, range: range)
                .frame(width: 96)
        }
    }

    private func volume(_ label: String, value: Binding<Double>) -> some View {
        LabeledContent(label) {
            HStack(spacing: 10) {
                Slider(value: value, in: 0...1)
                    .frame(width: 180)
                Text(value.wrappedValue.formatted(.percent.precision(.fractionLength(0))))
                    .monospacedDigit()
                    .foregroundStyle(.secondary)
                    .frame(width: 44, alignment: .trailing)
            }
        }
        .onChange(of: value.wrappedValue) { model.apply() }
    }

    // MARK: - Panels

    private func chooseRoot() {
        guard let url = pickDirectory(prompt: "Use folder",
                                      message: "Pick the folder containing skin/ and config.json") else { return }
        model.adopt(url)
    }

    private func chooseSongFolder() {
        guard let url = pickDirectory(prompt: "Add", message: "Pick a folder containing BMS charts") else { return }
        model.addSongPath(url)
    }

    private func pickDirectory(prompt: String, message: String) -> URL? {
        let panel = NSOpenPanel()
        panel.canChooseDirectories = true
        panel.canChooseFiles = false
        panel.allowsMultipleSelection = false
        panel.prompt = prompt
        panel.message = message
        return panel.runModal() == .OK ? panel.url : nil
    }
}
