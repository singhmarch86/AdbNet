import IdentityLookup
import MobileShieldCore // the shared Kotlin/Native framework (see ../README.md)

/// iOS counterpart of Android's SMS smishing scanner. An `ILMessageFilterExtension`
/// is the only way a third-party iOS app can auto-classify SMS, and only for
/// messages from unknown senders. It runs the shared `SmishingClassifier`
/// entirely on-device and maps the verdict to an iOS filter action.
///
/// The user enables it under Settings → Messages → Unknown & Spam → SMS
/// Filtering. Source only; it compiles on macOS with Xcode as its own extension
/// target (entitlement: com.apple.developer.identitylookup.message-filter).
final class MessageFilterExtension: ILMessageFilterExtension {}

extension MessageFilterExtension: ILMessageFilterQueryHandling {

    private var classifier: SmishingClassifier { SmishingClassifier() }

    func handle(
        _ queryRequest: ILMessageFilterQueryRequest,
        context: ILMessageFilterExtensionContext,
        completion: @escaping (ILMessageFilterQueryResponse) -> Void
    ) {
        let response = ILMessageFilterQueryResponse()
        let body = queryRequest.messageBody ?? ""
        let report = classifier.classify(text: body, sender: queryRequest.sender)

        switch report.level {
        case .critical, .high:
            response.action = .junk
        case .medium:
            // Surface as promotional on OS versions that support sub-actions;
            // falls back to .none where unavailable.
            response.action = .promotion
        default:
            response.action = .none
        }
        completion(response)
    }
}
