import Foundation
import SwiftUI

/// A Firefox add-on that was downloaded and unpacked for one web app.
struct InstalledExtension: Codable, Hashable, Identifiable {
    var id: String
    var name: String
    var source: String
    /// Folder (relative to the app's extension directory) holding the unpacked XPI.
    var folder: String
}

/// One web app created with WebApper.
struct WebApp: Codable, Hashable, Identifiable {
    var id: UUID = UUID()
    var name: String
    var url: URL
    var extensions: [InstalledExtension] = []
    var desktopMode: Bool = false
    var fullScreen: Bool = true
    var iconPNG: Data?
    var created: Date = Date()
    /// Add-on ids the user turned off for this app.
    var disabledExtensions: Set<String> = []

    init(name: String, url: URL, desktopMode: Bool = false, fullScreen: Bool = true) {
        self.name = name
        self.url = url
        self.desktopMode = desktopMode
        self.fullScreen = fullScreen
    }

    // Tolerant decoding so apps saved by older versions keep loading.
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(UUID.self, forKey: .id)
        name = try c.decode(String.self, forKey: .name)
        url = try c.decode(URL.self, forKey: .url)
        extensions = try c.decodeIfPresent([InstalledExtension].self, forKey: .extensions) ?? []
        desktopMode = try c.decodeIfPresent(Bool.self, forKey: .desktopMode) ?? false
        fullScreen = try c.decodeIfPresent(Bool.self, forKey: .fullScreen) ?? true
        iconPNG = try c.decodeIfPresent(Data.self, forKey: .iconPNG)
        created = try c.decodeIfPresent(Date.self, forKey: .created) ?? Date()
        disabledExtensions = try c.decodeIfPresent(Set<String>.self, forKey: .disabledExtensions) ?? []
    }

    var openURL: URL { URL(string: "webapper://open/\(id.uuidString)")! }

    var icon: Image {
        if let data = iconPNG, let ui = UIImage(data: data) { return Image(uiImage: ui) }
        return Image(systemName: "globe")
    }
}

enum Paths {
    static var support: URL {
        let u = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("WebApper", isDirectory: true)
        try? FileManager.default.createDirectory(at: u, withIntermediateDirectories: true)
        return u
    }

    static func extensions(for app: UUID) -> URL {
        support.appendingPathComponent("extensions/\(app.uuidString)", isDirectory: true)
    }

    static var documents: URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
    }
}

/// Persistent list of created web apps.
@MainActor
final class AppStore: ObservableObject {
    @Published private(set) var apps: [WebApp] = []
    @Published var openApp: WebApp?

    private let file = Paths.support.appendingPathComponent("apps.json")

    init() {
        if let data = try? Data(contentsOf: file),
           let list = try? JSONDecoder().decode([WebApp].self, from: data) {
            apps = list
        }
    }

    private func save() {
        if let data = try? JSONEncoder().encode(apps) {
            try? data.write(to: file, options: .atomic)
        }
    }

    func add(_ app: WebApp) {
        apps.removeAll { $0.id == app.id }
        apps.append(app)
        save()
    }

    func remove(_ app: WebApp) {
        apps.removeAll { $0.id == app.id }
        try? FileManager.default.removeItem(at: Paths.extensions(for: app.id))
        save()
    }

    func app(with id: UUID) -> WebApp? { apps.first { $0.id == id } }

    /// Handles webapper://open/<uuid> (Home Screen icons) and imported .webapper files.
    func handle(url: URL) {
        if url.scheme == "webapper" {
            let parts = url.pathComponents.filter { $0 != "/" }
            let idString = url.host == "open" ? parts.first : parts.last
            if let s = idString, let id = UUID(uuidString: s), let app = app(with: id) {
                openApp = app
            }
            return
        }
        if url.pathExtension.lowercased() == "webapper" {
            let access = url.startAccessingSecurityScopedResource()
            defer { if access { url.stopAccessingSecurityScopedResource() } }
            if let data = try? Data(contentsOf: url), let app = try? PortablePackage.importPackage(data) {
                add(app)
                openApp = app
            }
        }
    }
}
