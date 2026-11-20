import Security
import SwiftUI
import WebKit

struct WebView: UIViewRepresentable {
    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIView(context: Context) -> WKWebView {
        let config = WKWebViewConfiguration()
        config.websiteDataStore = .nonPersistent()
        config.userContentController.addScriptMessageHandler(context.coordinator, contentWorld: .page, name: "FluxPanel")
        config.userContentController.addScriptMessageHandler(context.coordinator, contentWorld: .page, name: "FluxSession")

        let webView = WKWebView(frame: .zero, configuration: config)
        webView.navigationDelegate = context.coordinator
        if #available(iOS 16.4, *) { webView.isInspectable = false }
        webView.scrollView.showsHorizontalScrollIndicator = false
        webView.scrollView.showsVerticalScrollIndicator = false
        webView.scrollView.bounces = false
        if let url = context.coordinator.entryURL {
            webView.loadFileURL(url, allowingReadAccessTo: url.deletingLastPathComponent())
        }
        context.coordinator.removeLegacyWebsiteData()
        return webView
    }

    func updateUIView(_ uiView: WKWebView, context: Context) {}

    class Coordinator: NSObject, WKNavigationDelegate, WKScriptMessageHandlerWithReply {
        let entryURL = Bundle.main.url(forResource: "index", withExtension: "html", subdirectory: "WebApp")
        private let storageKey = "panel_addresses"
        private let sessionKeys = ["token", "role_id", "name", "admin"]

        private func isBundlePage(_ url: URL?) -> Bool {
            guard let url, let entryURL, url.isFileURL, url.query == nil else { return false }
            return url.standardizedFileURL.path == entryURL.standardizedFileURL.path
        }

        func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction,
                     decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
            if action.targetFrame?.isMainFrame == true && isBundlePage(action.request.url) {
                decisionHandler(.allow)
                return
            }
            let externalLink = action.sourceFrame.isMainFrame && action.targetFrame?.isMainFrame != false &&
                action.navigationType == .linkActivated && action.request.url?.scheme == "https"
            decisionHandler(.cancel)
            if externalLink, let url = action.request.url { UIApplication.shared.open(url) }
        }

        func userContentController(_ controller: WKUserContentController, didReceive message: WKScriptMessage,
                                   replyHandler: @escaping (Any?, String?) -> Void) {
            guard message.frameInfo.isMainFrame, isBundlePage(message.frameInfo.request.url),
                  isBundlePage(message.webView?.url), let request = message.body as? [String: Any] else {
                replyHandler(["ok": false], nil)
                return
            }
            switch message.name {
            case "FluxPanel": replyHandler(handlePanelRequest(request), nil)
            case "FluxSession": replyHandler(handleSessionRequest(request), nil)
            default: replyHandler(["ok": false], nil)
            }
        }

        private func isPanelAddress(_ value: String) -> Bool {
            guard value.utf8.count <= 2048, value.rangeOfCharacter(from: .whitespacesAndNewlines) == nil,
                  !value.contains("\\"), let url = URLComponents(string: value), url.scheme == "https",
                  let host = url.host, !host.isEmpty, host != "appassets.androidplatform.net",
                  url.user == nil, url.password == nil, url.query == nil, url.fragment == nil else { return false }
            return url.port.map { (1...65535).contains($0) } ?? true
        }

        private func loadAddresses() -> [[String: Any]] {
            guard let stored = UserDefaults.standard.string(forKey: storageKey), let data = stored.data(using: .utf8),
                  let addresses = (try? JSONSerialization.jsonObject(with: data)) as? [[String: Any]] else { return [] }
            return addresses.filter { $0["name"] is String && $0["address"] is String }
        }

        private func activeAddress(_ addresses: [[String: Any]]) -> String? {
            addresses.first { $0["inx"] as? Bool == true }?["address"] as? String
        }

        private func handlePanelRequest(_ request: [String: Any]) -> [String: Any] {
            let denied = ["ok": false]
            guard let action = request["action"] as? String else { return denied }
            var addresses = loadAddresses()
            if action == "list" { return ["ok": true, "addresses": addresses] }
            guard let name = request["name"] as? String, !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return denied }
            let oldAddress = activeAddress(addresses)
            let index = addresses.firstIndex { $0["name"] as? String == name }
            switch action {
            case "save":
                guard name.utf16.count <= 100, index == nil, let address = request["address"] as? String,
                      isPanelAddress(address) else { return denied }
                let select = oldAddress.map { !isPanelAddress($0) } ?? true
                if select { for i in addresses.indices { addresses[i]["inx"] = false } }
                addresses.append(["name": name, "address": address, "inx": select])
            case "select":
                guard let index, let address = addresses[index]["address"] as? String,
                      isPanelAddress(address) else { return denied }
                for i in addresses.indices { addresses[i]["inx"] = i == index }
            case "delete":
                guard index != nil else { return denied }
                addresses.removeAll { $0["name"] as? String == name }
            default:
                return denied
            }
            guard let data = try? JSONSerialization.data(withJSONObject: addresses),
                  let serialized = String(data: data, encoding: .utf8) else { return denied }
            if oldAddress != activeAddress(addresses) && !clearStoredSession() { return denied }
            UserDefaults.standard.set(serialized, forKey: storageKey)
            return ["ok": true, "addresses": addresses]
        }

        private var keychainQuery: [String: Any] {
            [kSecClass as String: kSecClassGenericPassword,
             kSecAttrService as String: "com.flux.panel.session",
             kSecAttrAccount as String: "active-panel",
             kSecAttrSynchronizable as String: false]
        }

        private func clearStoredSession() -> Bool {
            let status = SecItemDelete(keychainQuery as CFDictionary)
            return status == errSecSuccess || status == errSecItemNotFound
        }

        private func handleSessionRequest(_ request: [String: Any]) -> [String: Any] {
            let denied = ["ok": false]
            guard let action = request["action"] as? String else { return denied }
            if action == "clear" { return ["ok": clearStoredSession()] }
            guard let address = activeAddress(loadAddresses()), isPanelAddress(address),
                  request["panel"] as? String == address else { return denied }
            switch action {
            case "load":
                var query = keychainQuery
                query[kSecMatchLimit as String] = kSecMatchLimitOne
                query[kSecReturnData as String] = true
                var value: CFTypeRef?
                let status = SecItemCopyMatching(query as CFDictionary, &value)
                if status == errSecItemNotFound { return ["ok": true] }
                guard status == errSecSuccess, let data = value as? Data,
                      var session = (try? JSONSerialization.jsonObject(with: data)) as? [String: String],
                      session.removeValue(forKey: "panel") == address else { return denied }
                return ["ok": true, "session": session]
            case "save":
                guard var session = request["session"] as? [String: String], Set(session.keys) == Set(sessionKeys),
                      session.values.allSatisfy({ $0.utf8.count <= 16384 }), session["token"]?.isEmpty == false else { return denied }
                session["panel"] = address
                guard let data = try? JSONSerialization.data(withJSONObject: session) else { return denied }
                let attributes: [String: Any] = [kSecValueData as String: data,
                    kSecAttrAccessible as String: kSecAttrAccessibleWhenUnlockedThisDeviceOnly]
                let updated = SecItemUpdate(keychainQuery as CFDictionary, attributes as CFDictionary)
                if updated == errSecItemNotFound {
                    let item = keychainQuery.merging(attributes) { _, new in new }
                    return ["ok": SecItemAdd(item as CFDictionary, nil) == errSecSuccess]
                }
                return ["ok": updated == errSecSuccess]
            default:
                return denied
            }
        }

        func removeLegacyWebsiteData() {
            let key = "removed_legacy_persistent_web_data"
            guard !UserDefaults.standard.bool(forKey: key) else { return }
            WKWebsiteDataStore.default().removeData(ofTypes: WKWebsiteDataStore.allWebsiteDataTypes(),
                                                    modifiedSince: .distantPast) {
                UserDefaults.standard.set(true, forKey: key)
            }
        }
    }
}

struct ContentView: View {
    var body: some View { WebView() }
}

#Preview {
    ContentView()
}
