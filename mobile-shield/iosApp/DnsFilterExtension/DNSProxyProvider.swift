import NetworkExtension
import MobileShieldCore // the shared Kotlin/Native framework (see README)

/// iOS DNS filtering via a NetworkExtension DNS proxy. This is the iOS
/// counterpart of Android's `ShieldVpnService`: it applies the *same* shared
/// decision logic (`DomainBlocklistMatcher` / `DnsMessage` from `core`) to each
/// query, blocking disallowed names and forwarding the rest.
///
/// This is a skeleton: the shared logic calls are wired, but the per-flow
/// upstream forwarding and the UDP/TCP flow handling need to be completed and
/// tested on a device/simulator. It is kept here as the iOS shell and is not
/// part of any automated build.
class DNSProxyProvider: NEDNSProxyProvider {

    private let matcher = DomainBlocklistMatcher(
        blocklist: ["doubleclick.net", "ads.example.com"],
        allowlist: [],
        safeSearchRewrites: [
            "google.com": "forcesafesearch.google.com",
            "youtube.com": "restrict.youtube.com",
        ],
        blockKnownDohResolvers: true
    )

    override func startProxy(options: [String: Any]?, completionHandler: @escaping (Error?) -> Void) {
        completionHandler(nil)
    }

    override func stopProxy(with reason: NEProviderStopReason, completionHandler: @escaping () -> Void) {
        completionHandler()
    }

    /// Decide whether a DNS query flow is blocked by inspecting its first UDP
    /// datagram. Returning true means we take ownership of the flow.
    override func handleNewFlow(_ flow: NEAppProxyFlow) -> Bool {
        guard let udp = flow as? NEAppProxyUDPFlow else {
            return false // TCP DNS handling omitted in this skeleton
        }
        udp.open(withLocalEndpoint: nil) { [weak self] error in
            guard error == nil else { return }
            self?.readDatagrams(from: udp)
        }
        return true
    }

    private func readDatagrams(from udp: NEAppProxyUDPFlow) {
        udp.readDatagrams { [weak self] datagrams, endpoints, error in
            guard let self, let datagrams, let endpoints, error == nil else { return }
            for (data, endpoint) in zip(datagrams, endpoints) {
                self.handleQuery(data, endpoint: endpoint, udp: udp)
            }
            self.readDatagrams(from: udp) // keep draining
        }
    }

    private func handleQuery(_ data: Data, endpoint: NWEndpoint, udp: NEAppProxyUDPFlow) {
        let packet = data.toKotlinByteArray()
        guard let question = DnsMessage.shared.parseFirstQuestion(packet: packet) else {
            forward(data, endpoint: endpoint, udp: udp)
            return
        }

        let decision = matcher.decide(rawHost: question.name)
        switch decision.action {
        case .block:
            if let nx = DnsMessage.shared.buildNxDomainResponse(query: packet) {
                udp.writeDatagrams([nx.toData()], sentBy: [endpoint]) { _ in }
                return
            }
        case .rewrite:
            // SafeSearch: answer with a CNAME to the safe host.
            if let target = decision.rewriteTo,
               let cname = DnsMessage.shared.buildCnameResponse(query: packet, canonicalName: target, ttlSeconds: 60) {
                udp.writeDatagrams([cname.toData()], sentBy: [endpoint]) { _ in }
                return
            }
        default:
            break
        }
        forward(data, endpoint: endpoint, udp: udp)
    }

    private func forward(_ data: Data, endpoint: NWEndpoint, udp: NEAppProxyUDPFlow) {
        // TODO: forward `data` to the system/upstream resolver and write the
        // reply back with udp.writeDatagrams(...). Omitted in this skeleton.
    }
}

// MARK: - Data <-> KotlinByteArray bridging

private extension Data {
    func toKotlinByteArray() -> KotlinByteArray {
        let out = KotlinByteArray(size: Int32(count))
        for (i, byte) in enumerated() {
            out.set(index: Int32(i), value: Int8(bitPattern: byte))
        }
        return out
    }
}

private extension KotlinByteArray {
    func toData() -> Data {
        var bytes = [UInt8]()
        bytes.reserveCapacity(Int(size))
        for i in 0..<size {
            bytes.append(UInt8(bitPattern: get(index: i)))
        }
        return Data(bytes)
    }
}
