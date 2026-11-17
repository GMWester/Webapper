import Foundation
import UIKit
import ZIPFoundation

enum BuildError: LocalizedError {
    case message(String)
    var errorDescription: String? {
        switch self { case .message(let m): return m }
    }
}

/// Resolves add-on links, fetches icons and creates `WebApp`s.
enum Builder {
    static let userAgent = "WebApper-iOS/1.0"

    static func get(_ url: URL, limit: Int = 0) async throws -> (Data, URLResponse) {
        var req = URLRequest(url: url, timeoutInterval: 60)
        req.setValue(userAgent, forHTTPHeaderField: "User-Agent")
        let (data, resp) = try await URLSession.shared.data(for: req)
        if let http = resp as? HTTPURLResponse, http.statusCode >= 400 {
            throw BuildError.message("\(url.absoluteString): HTTP \(http.statusCode)")
        }
        return (limit > 0 && data.count > limit ? data.prefix(limit) : data, resp)
    }

    static func normalize(_ text: String) throws -> URL {
        var t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if t.isEmpty { throw BuildError.message("Please enter a website address.") }
        if t.range(of: "^[a-zA-Z][a-zA-Z0-9+.-]*://", options: .regularExpression) == nil { t = "https://" + t }
        guard let u = URL(string: t), let scheme = u.scheme, ["http", "https"].contains(scheme), u.host != nil else {
            throw BuildError.message("'\(text)' is not a valid web address.")
        }
        return u
    }

    static func splitExtensions(_ text: String) -> [String] {
        var seen = Set<String>()
        return text.components(separatedBy: CharacterSet(charactersIn: " ,\n\t\r"))
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty && seen.insert($0).inserted }
    }

    static func amoSlug(_ source: String) -> String? {
        let pattern = "^https?://addons\\.mozilla\\.org/(?:[a-zA-Z-]+/)?(?:firefox|android)/addon/([^/?#]+)"
        if let re = try? NSRegularExpression(pattern: pattern, options: .caseInsensitive),
           let m = re.firstMatch(in: source, range: NSRange(source.startIndex..., in: source)),
           let r = Range(m.range(at: 1), in: source) {
            return String(source[r]).removingPercentEncoding
        }
        if !source.contains("://"), !source.lowercased().hasSuffix(".xpi"),
           source.range(of: "^[A-Za-z0-9._@{}-]+$", options: .regularExpression) != nil {
            return source
        }
        return nil
    }

    struct Resolved {
        var id: String
        var name: String
        var xpi: Data
    }

    static func resolveExtension(_ source: String) async throws -> Resolved {
        var id = "", name = "", xpiURL: URL?
        if let slug = amoSlug(source) {
            let enc = slug.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? slug
            let api = URL(string: "https://addons.mozilla.org/api/v5/addons/addon/\(enc)/")!
            let (data, _) = try await get(api)
            guard let o = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
                throw BuildError.message("Unexpected answer from addons.mozilla.org for \(slug)")
            }
            id = o["guid"] as? String ?? ""
            if let n = o["name"] as? [String: Any] {
                name = (n["en-US"] as? String) ?? (n.values.first as? String) ?? slug
            } else {
                name = o["name"] as? String ?? slug
            }
            let file = ((o["current_version"] as? [String: Any])?["file"] as? [String: Any])?["url"] as? String
            xpiURL = URL(string: file ?? "https://addons.mozilla.org/firefox/downloads/latest/\(enc)/latest.xpi")
        } else if let u = URL(string: source), ["http", "https"].contains(u.scheme ?? "") {
            xpiURL = u
            name = u.lastPathComponent
        } else {
            throw BuildError.message("'\(source)' is not an addons.mozilla.org link or .xpi URL.")
        }
        guard let xu = xpiURL else { throw BuildError.message("No download for \(source)") }
        let (xpi, _) = try await get(xu)
        return Resolved(id: id.isEmpty ? name : id, name: name, xpi: xpi)
    }

    /// Unpacks an XPI into the app's extension folder and returns the folder name.
    static func unpack(_ xpi: Data, app: UUID, index: Int) throws -> String {
        let dir = Paths.extensions(for: app)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let folder = "ext\(index)"
        let dest = dir.appendingPathComponent(folder, isDirectory: true)
        try? FileManager.default.removeItem(at: dest)
        try FileManager.default.createDirectory(at: dest, withIntermediateDirectories: true)
        let tmp = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".xpi")
        try xpi.write(to: tmp)
        defer { try? FileManager.default.removeItem(at: tmp) }
        try FileManager.default.unzipItem(at: tmp, to: dest)
        return folder
    }

    // MARK: icons

    static func fetchSite(_ url: URL) async -> (title: String?, icon: UIImage?) {
        var base = url
        var html = ""
        if let res = try? await get(url, limit: 1 << 20) {
            base = res.1.url ?? url
            html = String(decoding: res.0, as: UTF8.self)
        }
        var title: String?
        if let r = html.range(of: "(?is)<title[^>]*>(.*?)</title>", options: .regularExpression) {
            title = String(html[r])
                .replacingOccurrences(of: "(?is)</?title[^>]*>", with: "", options: .regularExpression)
                .replacingOccurrences(of: "&amp;", with: "&")
                .replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression)
                .trimmingCharacters(in: .whitespacesAndNewlines)
        }
        var candidates: [(Int, URL)] = []
        let linkRe = try! NSRegularExpression(pattern: "<link\\b[^>]*>", options: .caseInsensitive)
        for m in linkRe.matches(in: html, range: NSRange(html.startIndex..., in: html)) {
            guard let r = Range(m.range, in: html) else { continue }
            let tag = String(html[r])
            func attr(_ name: String) -> String? {
                let re = try! NSRegularExpression(pattern: "\(name)\\s*=\\s*[\"']?([^\"'>\\s]+)", options: .caseInsensitive)
                guard let mm = re.firstMatch(in: tag, range: NSRange(tag.startIndex..., in: tag)),
                      let rr = Range(mm.range(at: 1), in: tag) else { return nil }
                return String(tag[rr])
            }
            guard let rel = attr("rel")?.lowercased(), rel.contains("icon"),
                  let href = attr("href"), let u = URL(string: href, relativeTo: base)?.absoluteURL,
                  !u.path.lowercased().hasSuffix(".svg") else { continue }
            var size = rel.contains("apple-touch-icon") ? 180 : 0
            if let s = attr("sizes"), let n = Int(s.split(separator: "x").first ?? "") { size = n }
            candidates.append((size, u))
        }
        if let host = base.host, let scheme = base.scheme {
            candidates.append((-1, URL(string: "\(scheme)://\(host)/apple-touch-icon.png")!))
            candidates.append((-2, URL(string: "\(scheme)://\(host)/favicon.ico")!))
        }
        candidates.sort { $0.0 > $1.0 }
        var best: UIImage?
        for (_, u) in candidates {
            guard let res = try? await get(u, limit: 4 << 20), let img = UIImage(data: res.0) else { continue }
            if best == nil || img.size.width > best!.size.width { best = img }
            if (best?.size.width ?? 0) >= 120 { break }
        }
        return (title, best)
    }

    static func iconPNG(_ site: UIImage?, name: String) -> Data? {
        let size = CGSize(width: 180, height: 180)
        let r = UIGraphicsImageRenderer(size: size)
        let img = r.image { ctx in
            if let site {
                UIColor.white.setFill()
                ctx.fill(CGRect(origin: .zero, size: size))
                let inner: CGFloat = 140
                let scale = min(inner / site.size.width, inner / site.size.height)
                let w = site.size.width * scale, h = site.size.height * scale
                site.draw(in: CGRect(x: (size.width - w) / 2, y: (size.height - h) / 2, width: w, height: h))
            } else {
                let hue = CGFloat(abs(name.hashValue % 360)) / 360
                UIColor(hue: hue, saturation: 0.55, brightness: 0.85, alpha: 1).setFill()
                ctx.fill(CGRect(origin: .zero, size: size))
                let letter = String(name.trimmingCharacters(in: .whitespaces).prefix(1)).uppercased()
                let attrs: [NSAttributedString.Key: Any] = [
                    .font: UIFont.boldSystemFont(ofSize: 110), .foregroundColor: UIColor.white,
                ]
                let ts = (letter as NSString).size(withAttributes: attrs)
                (letter as NSString).draw(at: CGPoint(x: (size.width - ts.width) / 2, y: (size.height - ts.height) / 2),
                                          withAttributes: attrs)
            }
        }
        return img.pngData()
    }

    // MARK: create

    static func create(name: String, urlText: String, extensionsText: String,
                       desktopMode: Bool, fullScreen: Bool,
                       progress: @escaping @MainActor (String) -> Void) async throws -> WebApp {
        let url = try normalize(urlText)
        await progress("Fetching the site title and icon…")
        let site = await fetchSite(url)
        var finalName = name.trimmingCharacters(in: .whitespacesAndNewlines)
        if finalName.isEmpty { finalName = site.title ?? url.host ?? "Web App" }
        var app = WebApp(name: String(finalName.prefix(50)), url: url, desktopMode: desktopMode, fullScreen: fullScreen)
        app.iconPNG = iconPNG(site.icon, name: finalName)
        let sources = splitExtensions(extensionsText)
        for (i, src) in sources.enumerated() {
            await progress("Downloading add-on \(i + 1)/\(sources.count): \(src)")
            let r = try await resolveExtension(src)
            let folder = try unpack(r.xpi, app: app.id, index: i + 1)
            app.extensions.append(InstalledExtension(id: r.id, name: r.name, source: src, folder: folder))
        }
        return app
    }
}

/// Portable install: one `.webapper` file (app settings + add-ons) that can be AirDropped or
/// copied to another iPhone/iPad and opened there with WebApper.
enum PortablePackage {
    struct Package: Codable {
        var format = 1
        var app: WebApp
        var xpis: [String: Data] // folder -> zipped add-on
    }

    static func export(_ app: WebApp) throws -> URL {
        var xpis: [String: Data] = [:]
        let dir = Paths.extensions(for: app.id)
        for ext in app.extensions {
            let src = dir.appendingPathComponent(ext.folder)
            let tmp = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".zip")
            try FileManager.default.zipItem(at: src, to: tmp, shouldKeepParent: false)
            xpis[ext.folder] = try Data(contentsOf: tmp)
            try? FileManager.default.removeItem(at: tmp)
        }
        let data = try JSONEncoder().encode(Package(app: app, xpis: xpis))
        let safe = app.name.replacingOccurrences(of: "[/\\\\:*?\"<>|]", with: "_", options: .regularExpression)
        let out = FileManager.default.temporaryDirectory.appendingPathComponent("\(safe).webapper")
        try data.write(to: out, options: .atomic)
        return out
    }

    static func importPackage(_ data: Data) throws -> WebApp {
        let pkg = try JSONDecoder().decode(Package.self, from: data)
        let app = pkg.app
        for (folder, zip) in pkg.xpis {
            let tmp = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".zip")
            try zip.write(to: tmp)
            let dest = Paths.extensions(for: app.id).appendingPathComponent(folder, isDirectory: true)
            try? FileManager.default.removeItem(at: dest)
            try FileManager.default.createDirectory(at: dest, withIntermediateDirectories: true)
            try FileManager.default.unzipItem(at: tmp, to: dest)
            try? FileManager.default.removeItem(at: tmp)
        }
        return app
    }
}
