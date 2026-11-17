import SwiftUI

@main
struct WebApperApp: App {
    @StateObject private var store = AppStore()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(store)
                .onOpenURL { store.handle(url: $0) }
                .fullScreenCover(item: $store.openApp) { app in
                    WebAppScreen(app: app) { store.openApp = nil }
                }
        }
    }
}

struct ContentView: View {
    @EnvironmentObject var store: AppStore
    @State private var creating = false
    @State private var shareURL: URL?
    @State private var errorText: String?

    var body: some View {
        NavigationStack {
            List {
                if store.apps.isEmpty {
                    Section {
                        VStack(alignment: .leading, spacing: 8) {
                            Text("Turn any website into an app").font(.headline)
                            Text("Enter a web address and optional Firefox add-on links, pick Local or Portable, and tap Create.")
                                .font(.subheadline).foregroundStyle(.secondary)
                        }
                        .padding(.vertical, 4)
                    }
                }
                ForEach(store.apps) { app in
                    Button {
                        store.openApp = app
                    } label: {
                        HStack(spacing: 12) {
                            app.icon.resizable().frame(width: 44, height: 44)
                                .clipShape(RoundedRectangle(cornerRadius: 10))
                            VStack(alignment: .leading) {
                                Text(app.name).font(.body).foregroundStyle(.primary)
                                Text(app.url.host ?? app.url.absoluteString).font(.caption).foregroundStyle(.secondary)
                                if !app.extensions.isEmpty {
                                    Text("\(app.extensions.count) add-on(s)").font(.caption2).foregroundStyle(.secondary)
                                }
                            }
                        }
                    }
                    .contextMenu {
                        Button("Add to Home Screen", systemImage: "plus.app") { addToHome(app) }
                        Button("Export portable file", systemImage: "square.and.arrow.up") { export(app) }
                        Button("Delete", systemImage: "trash", role: .destructive) { store.remove(app) }
                    }
                }
                .onDelete { idx in idx.map { store.apps[$0] }.forEach(store.remove) }
            }
            .navigationTitle("WebApper")
            .toolbar {
                Button { creating = true } label: { Image(systemName: "plus") }
                    .accessibilityLabel("Create web app")
            }
            .sheet(isPresented: $creating) {
                CreateView { app, mode in
                    store.add(app)
                    creating = false
                    if mode == .portable { export(app) } else { addToHome(app) }
                }
            }
            .sheet(item: $shareURL) { url in ShareSheet(items: [url]) }
            .alert("WebApper", isPresented: Binding(get: { errorText != nil }, set: { if !$0 { errorText = nil } })) {
                Button("OK", role: .cancel) {}
            } message: { Text(errorText ?? "") }
        }
    }

    private func addToHome(_ app: WebApp) {
        do { try HomeScreen.install(app) } catch { errorText = error.localizedDescription }
    }

    private func export(_ app: WebApp) {
        do { shareURL = try PortablePackage.export(app) } catch { errorText = error.localizedDescription }
    }
}

extension URL: @retroactive Identifiable {
    public var id: String { absoluteString }
}

struct ShareSheet: UIViewControllerRepresentable {
    let items: [Any]
    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }
    func updateUIViewController(_ vc: UIActivityViewController, context: Context) {}
}

enum InstallMode: String, CaseIterable, Identifiable {
    case local, portable
    var id: String { rawValue }
    var title: String {
        switch self {
        case .local: return "Local (Home Screen icon on this device)"
        case .portable: return "Portable (.webapper file for any iPhone/iPad)"
        }
    }
}

struct CreateView: View {
    var onCreated: (WebApp, InstallMode) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var url = ""
    @State private var name = ""
    @State private var extensions = ""
    @State private var mode: InstallMode = .local
    @State private var desktop = false
    @State private var fullScreen = true
    @State private var busy = false
    @State private var status = ""

    var body: some View {
        NavigationStack {
            Form {
                Section("Website") {
                    TextField("https://example.com", text: $url)
                        .keyboardType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled()
                    TextField("App name (optional – uses the page title)", text: $name)
                }
                Section {
                    TextEditor(text: $extensions)
                        .frame(minHeight: 90)
                        .textInputAutocapitalization(.never).autocorrectionDisabled()
                        .font(.footnote.monospaced())
                } header: {
                    Text("Firefox add-ons (one link per line)")
                } footer: {
                    Text("addons.mozilla.org links or .xpi URLs. iOS only allows Apple's WebKit engine, so add-ons run their page scripts and styles; features that need Firefox's background pages are not available.")
                }
                Section("Install type") {
                    Picker("Install type", selection: $mode) {
                        ForEach(InstallMode.allCases) { Text($0.title).tag($0) }
                    }
                    .pickerStyle(.inline)
                    .labelsHidden()
                }
                Section("Options") {
                    Toggle("Request desktop site", isOn: $desktop)
                    Toggle("Full screen", isOn: $fullScreen)
                }
                Section {
                    Button {
                        Task { await create() }
                    } label: {
                        HStack {
                            Spacer()
                            if busy { ProgressView().padding(.trailing, 6) }
                            Text("Create").bold()
                            Spacer()
                        }
                    }
                    .disabled(busy || url.trimmingCharacters(in: .whitespaces).isEmpty)
                    if !status.isEmpty { Text(status).font(.footnote).foregroundStyle(.secondary) }
                }
            }
            .navigationTitle("New web app")
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } } }
        }
    }

    @MainActor
    private func create() async {
        busy = true
        defer { busy = false }
        do {
            let app = try await Builder.create(name: name, urlText: url, extensionsText: extensions,
                                               desktopMode: desktop, fullScreen: fullScreen) { status = $0 }
            status = "Created \(app.name)."
            onCreated(app, mode)
        } catch {
            status = "Error: \(error.localizedDescription)"
        }
    }
}
