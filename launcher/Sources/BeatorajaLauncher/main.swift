import AppKit

// AppKit rather than SwiftUI, and a plain main.swift rather than @main: SwiftUI's property
// wrappers are macros in current SDKs and the macro plugins ship only with Xcode, not with
// the Command Line Tools.
let app = NSApplication.shared
app.setActivationPolicy(.regular)

let controller = LauncherWindow()
controller.showWindow(nil)
controller.window?.makeKeyAndOrderFront(nil)
app.activate(ignoringOtherApps: true)

// Quit when the window closes; this app has exactly one.
final class AppDelegate: NSObject, NSApplicationDelegate {
    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool { true }
}
let delegate = AppDelegate()
app.delegate = delegate

app.run()
