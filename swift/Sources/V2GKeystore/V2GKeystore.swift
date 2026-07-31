import Foundation

/// A key the app holds, with everything a user needs to be told about it.
public struct V2GStoredKey: Sendable {

    /// Stable within this app; not a wire identifier.
    public let id: String

    /// What the key is for, in the user's terms — "Contract certificate for DE8AA1A2B3C4D5".
    public let label: String

    public let signer: any V2GSigner

    public var curve: V2GKeyCurve { signer.curve }
    public var protection: V2GKeyProtection { signer.protection }

    /// Whether using this key requires the user to authenticate.
    ///
    /// §3.4 asks for biometric gating on **use** rather than on storage, and the distinction is not
    /// pedantry: gating storage protects a key at rest, which platform encryption already does, while
    /// gating use is what stops a stolen unlocked phone from starting a charging session.
    public let requiresUserAuthenticationToUse: Bool

    public init(id: String, label: String, signer: any V2GSigner,
                requiresUserAuthenticationToUse: Bool = false) {
        self.id = id
        self.label = label
        self.signer = signer
        self.requiresUserAuthenticationToUse = requiresUserAuthenticationToUse
    }

    /// The sentence to put next to this key in a list. Never optimistic.
    public var disclosure: String {
        var text = protection.disclosure
        if requiresUserAuthenticationToUse {
            text += " Using it requires you to authenticate."
        }
        return text
    }
}


/// What protection is achievable for a curve on this device — the question a key-creation screen has
/// to answer before it offers anything.
public struct V2GProtectionAvailability: Sendable, Equatable {

    public let curve: V2GKeyCurve
    public let hardwareAvailable: Bool
    /// Why not, when not. Shown as-is: §3.4 wants the asymmetry visible rather than inferred from a
    /// disabled control.
    public let reason: String?

    public init(curve: V2GKeyCurve, hardwareAvailableOnThisDevice: Bool) {
        self.curve = curve
        if !curve.canBeHardwareBacked {
            self.hardwareAvailable = false
            self.reason = "\(curve.rawValue.uppercased()) keys cannot go in a secure element on any "
                        + "current phone — the Secure Enclave and StrongBox hold P-256 and nothing "
                        + "else. This key will be stored in software."
        } else if !hardwareAvailableOnThisDevice {
            self.hardwareAvailable = false
            self.reason = "This device has no secure element available, so the key will be stored in "
                        + "software."
        } else {
            self.hardwareAvailable = true
            self.reason = nil
        }
    }
}


/// The keys the app holds.
///
/// Persistence is deliberately absent, as with ``V2GTrustStore``: Keychain, EncryptedSharedPreferences
/// and a test double all obey the same rules, and where a key lives is an app decision while what may
/// be *claimed* about it is not.
public protocol V2GKeystore {

    var keys: [V2GStoredKey] { get }

    mutating func add(_ key: V2GStoredKey)
    mutating func remove(id: String)

    /// Where a key of this curve can be kept on this device.
    func availability(for curve: V2GKeyCurve) -> V2GProtectionAvailability
}

public extension V2GKeystore {

    func key(id: String) -> V2GStoredKey? { keys.first { $0.id == id } }

    /// Keys usable for a given protocol's contract signing.
    ///
    /// The split §3.4 is about, as a query: a -2 session wants P-256 and may get a hardware-backed
    /// one; a -20 session wants P-521 or Ed448 and cannot.
    func keys(forCurve curve: V2GKeyCurve) -> [V2GStoredKey] {
        keys.filter { $0.curve == curve }
    }

    /// Every key whose protection is weaker than a user might assume — the list a wallet screen should
    /// be able to show without any further reasoning.
    var softwareOnlyKeys: [V2GStoredKey] {
        keys.filter { !$0.protection.isHardwareBacked }
    }
}


public struct InMemoryKeystore: V2GKeystore {

    public private(set) var keys: [V2GStoredKey]

    /// Whether this device is claimed to have a secure element at all. A parameter rather than a
    /// probe so tests can put both worlds side by side.
    private let hardwareAvailableOnThisDevice: Bool

    public init(keys: [V2GStoredKey] = [], hardwareAvailableOnThisDevice: Bool = false) {
        self.keys = keys
        self.hardwareAvailableOnThisDevice = hardwareAvailableOnThisDevice
    }

    public mutating func add(_ key: V2GStoredKey) {
        keys.removeAll { $0.id == key.id }
        keys.append(key)
    }

    public mutating func remove(id: String) {
        keys.removeAll { $0.id == id }
    }

    public func availability(for curve: V2GKeyCurve) -> V2GProtectionAvailability {
        V2GProtectionAvailability(curve: curve,
                                  hardwareAvailableOnThisDevice: hardwareAvailableOnThisDevice)
    }
}
