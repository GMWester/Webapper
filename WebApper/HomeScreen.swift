import Foundation
import Network
import UIKit

/// Adds a web app to the Home Screen as its own icon.
///
/// iOS has no API for apps to place icons on the Home Screen, but a configuration profile with a
/// Web Clip payload can. The Web Clip points at webapper://open/<id>, so tapping the icon opens
/// that web app full screen in WebApper. Safari must download the profile, so WebApper serves it
/// from a short-lived local web server and opens it in Safari; the user then installs it in
/// Settings › Profile Downloaded.
enum HomeScreen {
    static func profile(for app: WebApp) throws -> Data {
        let clipUUID = UUID().uuidString
        var clip: [String: Any] = [
            "PayloadType": "com.apple.webClip.managed",
            "PayloadVersion": 1,
            "PayloadIdentifier": "io.webapper.webclip.\(app.id.uuidString)",
            "PayloadUUID": clipUUID,
            "PayloadDisplayName": app.name,
            "Label": app.name,
            "URL": app.openURL.absoluteString,
            "IsRemovable": true,
            "FullScreen": false,
            "Precomposed": true,
        ]
        if let icon = app.iconPNG { clip["Icon"] = icon }
        let root: [String: Any] = [
            "PayloadType": "Configuration",
            "PayloadVersion": 1,
            "PayloadIdentifier": "io.webapper.profile.\(app.id.uuidString)",
            "PayloadUUID": UUID().uuidString,
            "PayloadDisplayName": "\(app.name) (Home Screen icon)",
            "PayloadDescription": "Adds \(app.name) to your Home Screen. Made with WebApper.",
            "PayloadOrganization": "WebApper",
            "PayloadRemovalDisallowed": false,
            "PayloadContent": [clip],
        ]
        return try PropertyListSerialization.data(fromPropertyList: root, format: .xml, options: 0)
    }

    static func profileFile(for app: WebApp) throws -> URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("\(app.name).mobileconfig")
        try profile(for: app).write(to: url, options: .atomic)
        return url
    }

    private static var server: ProfileServer?

    /// Serves the profile on localhost and opens it in Safari.
    @MainActor
    static func install(_ app: WebApp) throws {
        let data = try profile(for: app)
        server?.stop()
        let s = try ProfileServer(payload: data)
        server = s
        s.start { port in
            DispatchQueue.main.async {
                let url = URL(string: "http://localhost:\(port)/\(app.id.uuidString).mobileconfig")!
                UIApplication.shared.open(url)
            }
        }
    }
}

/// Minimal one-file HTTP server (Network framework) kept alive briefly in the background.
final class ProfileServer {
    private let listener: NWListener
    private let payload: Data
    private var task: UIBackgroundTaskIdentifier = .invalid

    init(payload: Data) throws {
        self.payload = payload
        let params = NWParameters.tcp
        params.requiredInterfaceType = .loopback
        listener = try NWListener(using: params, on: .any)
    }

    func start(ready: @escaping (UInt16) -> Void) {
        task = UIApplication.shared.beginBackgroundTask(withName: "profile") { [weak self] in self?.stop() }
        listener.stateUpdateHandler = { [weak self] state in
            if case .ready = state, let port = self?.listener.port?.rawValue { ready(port) }
        }
        listener.newConnectionHandler = { [weak self] conn in self?.serve(conn) }
        listener.start(queue: .global())
        DispatchQueue.main.asyncAfter(deadline: .now() + 120) { [weak self] in self?.stop() }
    }

    private func serve(_ conn: NWConnection) {
        conn.start(queue: .global())
        conn.receive(minimumIncompleteLength: 1, maximumLength: 65536) { [payload] _, _, _, _ in
            var head = "HTTP/1.1 200 OK\r\n"
            head += "Content-Type: application/x-apple-aspen-config\r\n"
            head += "Content-Length: \(payload.count)\r\n"
            head += "Connection: close\r\n\r\n"
            var out = Data(head.utf8)
            out.append(payload)
            conn.send(content: out, completion: .contentProcessed { _ in conn.cancel() })
        }
    }

    func stop() {
        listener.cancel()
        if task != .invalid {
            UIApplication.shared.endBackgroundTask(task)
            task = .invalid
        }
    }
}
