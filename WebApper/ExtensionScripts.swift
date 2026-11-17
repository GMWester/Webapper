import Foundation
import WebKit

/// Runs the content scripts and styles of Firefox add-ons (WebExtensions) inside WKWebView.
///
/// iOS only allows Apple's WebKit engine for third-party apps, so Gecko cannot be embedded and
/// full add-on support is impossible. What WebKit *can* do is inject scripts and CSS into pages,
/// which is how most page-modifying add-ons (dark modes, ad/element hiders, tweakers, userscript
/// style add-ons) work. This loader reads each add-on's manifest.json and injects its
/// `content_scripts` with the declared match patterns, run_at timing and frame settings, plus a
/// small `browser`/`chrome` API shim (storage.local, i18n, runtime.getURL/getManifest/id).
enum ExtensionScripts {
    struct Report {
        var scripts: [WKUserScript] = []
        var notes: [String] = []
    }

    static func load(for app: WebApp) -> Report {
        var report = Report()
        let root = Paths.extensions(for: app.id)
        for ext in app.extensions {
            let dir = root.appendingPathComponent(ext.folder, isDirectory: true)
            guard let data = try? Data(contentsOf: dir.appendingPathComponent("manifest.json")),
                  let manifest = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
                report.notes.append("\(ext.name): manifest.json missing")
                continue
            }
            let messages = loadMessages(dir: dir, manifest: manifest)
            let shim = apiShim(extID: ext.id, manifest: data, messages: messages)
            let contentScripts = manifest["content_scripts"] as? [[String: Any]] ?? []
            if contentScripts.isEmpty {
                report.notes.append("\(ext.name): has no content scripts (background-only features need Firefox)")
            }
            for cs in contentScripts {
                let matches = (cs["matches"] as? [String] ?? []).map(patternToRegex)
                let excludes = (cs["exclude_matches"] as? [String] ?? []).map(patternToRegex)
                let allFrames = cs["all_frames"] as? Bool ?? false
                let runAt = cs["run_at"] as? String ?? "document_idle"
                var body = ""
                for css in cs["css"] as? [String] ?? [] {
                    if let text = try? String(contentsOf: dir.appendingPathComponent(css), encoding: .utf8) {
                        body += "__webapperCSS(\(jsString(text)));\n"
                    }
                }
                for js in cs["js"] as? [String] ?? [] {
                    if let text = try? String(contentsOf: dir.appendingPathComponent(js), encoding: .utf8) {
                        body += "try {\n\(text)\n} catch (e) { console.error('[\(ext.name)]', e); }\n"
                    }
                }
                if body.isEmpty { continue }
                let source = """
                (function() {
                  const __m = [\(matches.map(jsString).joined(separator: ","))].map(r => new RegExp(r));
                  const __x = [\(excludes.map(jsString).joined(separator: ","))].map(r => new RegExp(r));
                  const __u = location.href;
                  if (!__m.some(r => r.test(__u)) || __x.some(r => r.test(__u))) return;
                  function __webapperCSS(css) {
                    const add = () => { const s = document.createElement('style'); s.textContent = css;
                      (document.head || document.documentElement).appendChild(s); };
                    if (document.documentElement) add(); else document.addEventListener('DOMContentLoaded', add);
                  }
                  \(shim)
                  \(body)
                })();
                """
                let time: WKUserScriptInjectionTime = runAt == "document_start" ? .atDocumentStart : .atDocumentEnd
                report.scripts.append(WKUserScript(source: source, injectionTime: time, forMainFrameOnly: !allFrames))
            }
        }
        return report
    }

    static func jsString(_ s: String) -> String {
        let data = try? JSONSerialization.data(withJSONObject: [s], options: [.fragmentsAllowed])
        let arr = data.flatMap { String(data: $0, encoding: .utf8) } ?? "[\"\"]"
        return String(arr.dropFirst().dropLast())
    }

    /// WebExtension match pattern -> JavaScript regular expression source.
    static func patternToRegex(_ p: String) -> String {
        if p == "<all_urls>" { return "^(https?|file|ftp)://" }
        guard let schemeEnd = p.range(of: "://") else { return "^$" }
        let scheme = String(p[..<schemeEnd.lowerBound])
        let rest = String(p[schemeEnd.upperBound...])
        let slash = rest.firstIndex(of: "/") ?? rest.endIndex
        let host = String(rest[..<slash])
        let path = slash < rest.endIndex ? String(rest[slash...]) : "/"
        func esc(_ s: String) -> String { NSRegularExpression.escapedPattern(for: s) }
        let schemeRe = scheme == "*" ? "https?" : esc(scheme)
        var hostRe: String
        if host == "*" {
            hostRe = "[^/]+"
        } else if host.hasPrefix("*.") {
            hostRe = "([^/]+\\.)?" + esc(String(host.dropFirst(2)))
        } else {
            hostRe = esc(host)
        }
        hostRe += "(:\\d+)?"
        let pathRe = path.split(separator: "*", omittingEmptySubsequences: false).map { esc(String($0)) }.joined(separator: ".*")
        return "^\(schemeRe)://\(hostRe)\(pathRe)$"
    }

    private static func loadMessages(dir: URL, manifest: [String: Any]) -> [String: String] {
        let lang = Locale.current.language.languageCode?.identifier ?? "en"
        let def = manifest["default_locale"] as? String ?? "en"
        for loc in [lang, def, "en", "en_US"] {
            let f = dir.appendingPathComponent("_locales/\(loc)/messages.json")
            if let d = try? Data(contentsOf: f), let o = try? JSONSerialization.jsonObject(with: d) as? [String: Any] {
                var out: [String: String] = [:]
                for (k, v) in o { if let m = (v as? [String: Any])?["message"] as? String { out[k] = m } }
                return out
            }
        }
        return [:]
    }

    private static func apiShim(extID: String, manifest: Data, messages: [String: String]) -> String {
        let manifestJSON = String(data: manifest, encoding: .utf8) ?? "{}"
        let msgs = (try? JSONSerialization.data(withJSONObject: messages)).flatMap { String(data: $0, encoding: .utf8) } ?? "{}"
        return """
        const __id = \(jsString(extID));
        const __ns = 'webapper-ext:' + __id + ':';
        const __msgs = \(msgs);
        const __store = {
          get(keys) {
            const out = {};
            const all = () => { for (let i = 0; i < localStorage.length; i++) { const k = localStorage.key(i);
              if (k.startsWith(__ns)) out[k.slice(__ns.length)] = JSON.parse(localStorage.getItem(k)); } };
            if (keys == null) all();
            else if (typeof keys === 'string') { const v = localStorage.getItem(__ns + keys); if (v !== null) out[keys] = JSON.parse(v); }
            else if (Array.isArray(keys)) keys.forEach(k => { const v = localStorage.getItem(__ns + k); if (v !== null) out[k] = JSON.parse(v); });
            else Object.keys(keys).forEach(k => { const v = localStorage.getItem(__ns + k); out[k] = v !== null ? JSON.parse(v) : keys[k]; });
            return Promise.resolve(out);
          },
          set(items) { Object.keys(items).forEach(k => localStorage.setItem(__ns + k, JSON.stringify(items[k]))); return Promise.resolve(); },
          remove(keys) { [].concat(keys).forEach(k => localStorage.removeItem(__ns + k)); return Promise.resolve(); },
          clear() { Object.keys(localStorage).filter(k => k.startsWith(__ns)).forEach(k => localStorage.removeItem(k)); return Promise.resolve(); },
          onChanged: { addListener() {}, removeListener() {} }
        };
        const __noop = { addListener() {}, removeListener() {}, hasListener() { return false; } };
        const browser = {
          runtime: { id: __id, getManifest: () => (\(manifestJSON)), getURL: p => p,
                     sendMessage: () => Promise.resolve(undefined), onMessage: __noop, connect: () => ({ postMessage() {}, onMessage: __noop, onDisconnect: __noop, disconnect() {} }) },
          storage: { local: __store, sync: __store, onChanged: __noop },
          i18n: { getMessage: (k, subs) => { let m = __msgs[k] || ''; [].concat(subs || []).forEach((s, i) => m = m.replace('$' + (i + 1), s)); return m; },
                  getUILanguage: () => navigator.language },
          extension: { getURL: p => p, inIncognitoContext: false }
        };
        const chrome = browser;
        """
    }
}
