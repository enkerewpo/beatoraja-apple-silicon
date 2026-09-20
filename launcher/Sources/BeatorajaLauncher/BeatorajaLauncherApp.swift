import SwiftUI

@main
struct BeatorajaLauncherApp: App {
    var body: some Scene {
        Window("beatoraja configuration", id: "launcher") {
            LauncherView()
        }
        .windowResizability(.contentSize)
        .commands {
            // A launcher has nothing to put in a New menu
            CommandGroup(replacing: .newItem) { }
        }
    }
}
