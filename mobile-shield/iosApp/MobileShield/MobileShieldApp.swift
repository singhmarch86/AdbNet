import SwiftUI

// Entry point for the iOS app. See iosApp/README.md for how to produce and
// embed the shared MobileShieldCore framework that the views call into.
@main
struct MobileShieldApp: App {
    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
