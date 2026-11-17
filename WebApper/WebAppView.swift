import SwiftUI
import WebKit

/// Full-screen web app: WKWebView with its own isolated website data store (cookies, storage,
/// logins are separate for every web app) and the add-ons' content scripts.
struct WebAppScreen: View {
    @EnvironmentObject var store: AppStore
    @State var app: WebApp
    let onClose: () -> Void
    @State private var showAddons = false
    @State private var reloadToken = UUID()

    var body: some View {
        ZStack(alignment: .topTrailing) {
            WebAppView(app: app)
                .id(reloadToken)
                .ignoresSafeArea(app.fullScreen ? .all : [])
            HStack(spacing: 8) {
                if !app.extensions.isEmpty {
                    overlayButton("puzzlepiece.extension", label: "Add-ons") { showAddons = true }
                }
                overlayButton("square.grid.2x2", label: "Back to WebApper", action: onClose)
            }
            .padding(.top, 4)
            .padding(.trailing, 8)
        }
        .statusBarHidden(app.fullScreen)
        .sheet(isPresented: $showAddons, onDismiss: { reloadToken = UUID() }) {
            AddonsView(app: $app)
                .onChange(of: app) { _, newValue in store.add(newValue) }
        }
    }

    private func overlayButton(_ symbol: String, label: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: symbol)
                .font(.system(size: 14, weight: .semibold))
                .padding(8)
                .background(.ultraThinMaterial, in: Circle())
        }
        .opacity(0.6)
        .accessibilityLabel(label)
    }
}

/// Turn this app's add-ons on or off (the page reloads when the sheet closes).
struct AddonsView: View {
    @Binding var app: WebApp
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                Section {
                    ForEach(app.extensions) { ext in
                        Toggle(isOn: Binding(
                            get: { !app.disabledExtensions.contains(ext.id) },
                            set: { on in
                                if on { app.disabledExtensions.remove(ext.id) } else { app.disabledExtensions.insert(ext.id) }
                            })) {
                            VStack(alignment: .leading) {
                                Text(ext.name)
                                Text(ext.source).font(.caption2).foregroundStyle(.secondary).lineLimit(1)
                            }
                        }
                    }
                } footer: {
                    Text("On iOS add-ons run their page scripts and styles. Changes apply when you close this screen.")
                }
            }
            .navigationTitle("Add-ons")
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }
    }
}

struct WebAppView: UIViewRepresentable {
    let app: WebApp

    func makeCoordinator() -> Coordinator { Coordinator(app: app) }

    func makeUIView(context: Context) -> WKWebView {
        let config = WKWebViewConfiguration()
        config.websiteDataStore = WKWebsiteDataStore(forIdentifier: app.id)
        config.allowsInlineMediaPlayback = true
        config.mediaTypesRequiringUserActionForPlayback = []
        config.defaultWebpagePreferences.preferredContentMode = app.desktopMode ? .desktop : .mobile
        let report = ExtensionScripts.load(for: app)
        for s in report.scripts { config.userContentController.addUserScript(s) }
        let view = WKWebView(frame: .zero, configuration: config)
        view.allowsBackForwardNavigationGestures = true
        view.navigationDelegate = context.coordinator
        view.uiDelegate = context.coordinator
        view.isInspectable = true
        let refresh = UIRefreshControl()
        refresh.addTarget(context.coordinator, action: #selector(Coordinator.reload(_:)), for: .valueChanged)
        view.scrollView.refreshControl = refresh
        context.coordinator.webView = view
        view.load(URLRequest(url: app.url))
        return view
    }

    func updateUIView(_ uiView: WKWebView, context: Context) {}

    final class Coordinator: NSObject, WKNavigationDelegate, WKUIDelegate, WKDownloadDelegate {
        let app: WebApp
        weak var webView: WKWebView?
        private var downloadDestinations: [ObjectIdentifier: URL] = [:]

        init(app: WebApp) { self.app = app }

        @objc func reload(_ sender: UIRefreshControl) {
            webView?.reload()
            sender.endRefreshing()
        }

        // Navigation: keep web links inside the app, hand everything else (mailto:, tel:, app links) to iOS.
        func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction,
                     decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
            guard let url = action.request.url, let scheme = url.scheme?.lowercased() else {
                decisionHandler(.allow); return
            }
            if ["http", "https", "about", "data", "blob", "file", "javascript"].contains(scheme) {
                decisionHandler(action.shouldPerformDownload ? .download : .allow)
            } else {
                UIApplication.shared.open(url)
                decisionHandler(.cancel)
            }
        }

        func webView(_ webView: WKWebView, decidePolicyFor response: WKNavigationResponse,
                     decisionHandler: @escaping (WKNavigationResponsePolicy) -> Void) {
            decisionHandler(response.canShowMIMEType ? .allow : .download)
        }

        func webView(_ webView: WKWebView, navigationAction: WKNavigationAction, didBecome download: WKDownload) {
            download.delegate = self
        }

        func webView(_ webView: WKWebView, navigationResponse: WKNavigationResponse, didBecome download: WKDownload) {
            download.delegate = self
        }

        func download(_ download: WKDownload, decideDestinationUsing response: URLResponse,
                      suggestedFilename: String, completionHandler: @escaping (URL?) -> Void) {
            let dir = Paths.documents.appendingPathComponent("Downloads", isDirectory: true)
            try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            var dest = dir.appendingPathComponent(suggestedFilename)
            var n = 1
            while FileManager.default.fileExists(atPath: dest.path) {
                n += 1
                let base = (suggestedFilename as NSString).deletingPathExtension
                let ext = (suggestedFilename as NSString).pathExtension
                dest = dir.appendingPathComponent(ext.isEmpty ? "\(base) \(n)" : "\(base) \(n).\(ext)")
            }
            downloadDestinations[ObjectIdentifier(download)] = dest
            completionHandler(dest)
        }

        func downloadDidFinish(_ download: WKDownload) {
            guard let dest = downloadDestinations.removeValue(forKey: ObjectIdentifier(download)) else { return }
            present(UIAlertController(title: "Downloaded",
                                      message: "\(dest.lastPathComponent) was saved to Files › WebApper › Downloads.",
                                      preferredStyle: .alert), ok: true)
        }

        // Pop-ups / target=_blank open in place (an app has no tabs).
        func webView(_ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration,
                     for action: WKNavigationAction, windowFeatures: WKWindowFeatures) -> WKWebView? {
            if let url = action.request.url { webView.load(URLRequest(url: url)) }
            return nil
        }

        private func present(_ alert: UIAlertController, ok: Bool) {
            if ok { alert.addAction(UIAlertAction(title: "OK", style: .default)) }
            guard let root = webView?.window?.rootViewController else { return }
            var top = root
            while let p = top.presentedViewController { top = p }
            top.present(alert, animated: true)
        }

        func webView(_ webView: WKWebView, runJavaScriptAlertPanelWithMessage message: String,
                     initiatedByFrame frame: WKFrameInfo, completionHandler: @escaping () -> Void) {
            let a = UIAlertController(title: frame.request.url?.host, message: message, preferredStyle: .alert)
            a.addAction(UIAlertAction(title: "OK", style: .default) { _ in completionHandler() })
            present(a, ok: false)
        }

        func webView(_ webView: WKWebView, runJavaScriptConfirmPanelWithMessage message: String,
                     initiatedByFrame frame: WKFrameInfo, completionHandler: @escaping (Bool) -> Void) {
            let a = UIAlertController(title: frame.request.url?.host, message: message, preferredStyle: .alert)
            a.addAction(UIAlertAction(title: "Cancel", style: .cancel) { _ in completionHandler(false) })
            a.addAction(UIAlertAction(title: "OK", style: .default) { _ in completionHandler(true) })
            present(a, ok: false)
        }

        func webView(_ webView: WKWebView, runJavaScriptTextInputPanelWithPrompt prompt: String,
                     defaultText: String?, initiatedByFrame frame: WKFrameInfo,
                     completionHandler: @escaping (String?) -> Void) {
            let a = UIAlertController(title: frame.request.url?.host, message: prompt, preferredStyle: .alert)
            a.addTextField { $0.text = defaultText }
            a.addAction(UIAlertAction(title: "Cancel", style: .cancel) { _ in completionHandler(nil) })
            a.addAction(UIAlertAction(title: "OK", style: .default) { _ in completionHandler(a.textFields?.first?.text) })
            present(a, ok: false)
        }

        func webView(_ webView: WKWebView, requestMediaCapturePermissionFor origin: WKSecurityOrigin,
                     initiatedByFrame frame: WKFrameInfo, type: WKMediaCaptureType,
                     decisionHandler: @escaping (WKPermissionDecision) -> Void) {
            decisionHandler(.prompt)
        }
    }
}
