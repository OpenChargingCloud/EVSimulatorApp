package cloud.charging.v2g.keystore

import java.security.PrivateKey
import java.security.Signature

/** The curves ISO 15118 credentials actually use. */
enum class V2GKeyCurve {

    /** ISO 15118-2's contract and TLS keys, and the only curve any current secure element holds. */
    P256,

    /** One of ISO 15118-20's two mandatory signature suites. */
    P521,

    /** The other. The JDK has no Ed448 at all — see §3.3. */
    ED448;

    /**
     * Whether a secure element can hold this key **at all**.
     *
     * This is the whole of `docs/CONCEPT.md` §3.4 expressed as a value rather than a paragraph: the
     * iOS Secure Enclave does P-256 and nothing else, and Android's StrongBox/TEE does P-256 and RSA
     * where it does anything. So a -2 contract key can be hardware-backed and a -20 one cannot, on
     * either platform, for reasons no application code can work around.
     */
    val canBeHardwareBacked: Boolean get() = this == P256
}


/**
 * Where a private key actually lives, and therefore what may be claimed about it.
 *
 * §3.4 asks for the asymmetry to be explicit in the UI, because "a simulator that quietly pretends
 * its keys are hardware-protected is worse than one that says they aren't". A protection level that
 * is a value can be displayed, asserted and got wrong loudly; one that is an implementation detail
 * can only be got wrong quietly.
 */
sealed class V2GKeyProtection(
    /**
     * What to show a user, in the plainest terms that are still true.
     *
     * Both software cases open with the word "software" on purpose. An earlier version said only
     * "not by separate hardware", which is accurate and requires the reader to work out the
     * consequence — and §3.4's whole point is that a user should not have to. The word is what a
     * person scanning a list of keys is looking for.
     */
    val disclosure: String,
) {

    /** A secure element — StrongBox, a TEE, the Secure Enclave. The private key has **no bytes** the
     *  app can hold: it signs on request and cannot be exported. That is what makes it worth having,
     *  and why [V2GSigner] asks for a signature rather than for a key. */
    class Hardware(val element: String) : V2GKeyProtection(
        "Protected by this device's $element. The private key cannot be read, exported or copied — " +
        "not by this app and not by anything else.")

    /** A software key at rest in platform-encrypted storage — Keystore, EncryptedSharedPreferences.
     *  Protected by the device, not by a separate processor. */
    data object SoftwareInSecureStorage : V2GKeyProtection(
        "A software key, stored encrypted by the device. The private key exists as data this app " +
        "can read, so it is protected by the device's encryption and not by separate hardware.")

    /** A software key in process memory. Tests and ephemeral material. */
    data object SoftwareInMemory : V2GKeyProtection(
        "A software key, held in memory only. Not stored, not protected, and gone when " +
        "the app closes.")

    val isHardwareBacked: Boolean get() = this is Hardware
}


/** Why a key could not be created or stored as asked. */
sealed class V2GKeyException(message: String) : Exception(message) {

    /** Hardware was requested for a curve no secure element can hold. Refused rather than downgraded:
     *  silently storing software where hardware was asked for is the "quietly pretends" case. */
    class CurveCannotBeHardwareBacked(curve: V2GKeyCurve) : V2GKeyException(
        "$curve cannot be held in a secure element — the Secure Enclave and StrongBox do P-256 only " +
        "(§3.4). A software key is available; it must be asked for, not substituted.")

    /** A software signer was asked to describe itself as hardware-backed. Distinct from
     *  [CurveCannotBeHardwareBacked] on purpose: P-256 *can* live in a secure element, just not in
     *  this object, and blaming the curve would send someone looking in the wrong place. */
    class SoftwareSignerCannotClaimHardware : V2GKeyException(
        "this signer holds the key in software and cannot describe itself as hardware-backed. A " +
        "hardware key comes from the platform, never from a key this code was handed.")
}


/**
 * A private key that can sign but need not be readable.
 *
 * **The shape is the point.** A secure-element key cannot be exported, so any interface handing out
 * private key material rules out hardware backing entirely — whatever the curve. Asking for a
 * signature instead is what leaves the door open, and is why `PncEvccOptions` takes one of these
 * rather than a `PrivateKey`.
 */
interface V2GSigner {
    val curve: V2GKeyCurve
    val protection: V2GKeyProtection
    val publicKeyDer: ByteArray

    /** Raw `r‖s`, never DER — the wire field is sized to the curve, so the usual mistake fails
     *  loudly rather than silently. */
    fun signature(octets: ByteArray): ByteArray
}


/** A software P-256 signer. What the tests use, and an app's fallback before it binds to the platform. */
class InMemoryP256Signer(
    private val key: PrivateKey,
    override val publicKeyDer: ByteArray,
    override val protection: V2GKeyProtection = V2GKeyProtection.SoftwareInMemory,
) : V2GSigner {

    init {
        // The reason is that this object holds bytes, not the curve: P-256 can live in a secure
        // element, simply not in an object it was handed to.
        if (protection.isHardwareBacked) throw V2GKeyException.SoftwareSignerCannotClaimHardware()
    }

    override val curve get() = V2GKeyCurve.P256

    override fun signature(octets: ByteArray): ByteArray =
        Signature.getInstance("SHA256withECDSAinP1363Format").apply {
            initSign(key)
            update(octets)
        }.sign()
}


/** A key the app holds, with everything a user needs to be told about it. */
class V2GStoredKey(
    val id: String,
    val label: String,
    val signer: V2GSigner,
    /**
     * Whether using this key requires the user to authenticate.
     *
     * §3.4 asks for biometric gating on **use** rather than on storage, and the distinction is not
     * pedantry: gating storage protects a key at rest, which platform encryption already does, while
     * gating use is what stops a stolen unlocked phone from starting a charging session.
     */
    val requiresUserAuthenticationToUse: Boolean = false,
) {
    val curve get() = signer.curve
    val protection get() = signer.protection

    /** The sentence to put next to this key in a list. Never optimistic. */
    val disclosure: String
        get() = protection.disclosure +
                if (requiresUserAuthenticationToUse) " Using it requires you to authenticate." else ""
}


/** What protection is achievable for a curve on this device. */
class V2GProtectionAvailability(val curve: V2GKeyCurve, hardwareAvailableOnThisDevice: Boolean) {

    val hardwareAvailable: Boolean
    /** Why not, when not. Shown as-is: §3.4 wants the asymmetry visible rather than inferred from a
     *  disabled control. */
    val reason: String?

    init {
        when {
            !curve.canBeHardwareBacked -> {
                hardwareAvailable = false
                reason = "$curve keys cannot go in a secure element on any current phone — the " +
                         "Secure Enclave and StrongBox hold P-256 and nothing else. This key will " +
                         "be stored in software."
            }
            !hardwareAvailableOnThisDevice -> {
                hardwareAvailable = false
                reason = "This device has no secure element available, so the key will be stored " +
                         "in software."
            }
            else -> { hardwareAvailable = true; reason = null }
        }
    }
}


/**
 * The keys the app holds.
 *
 * Persistence deliberately absent, as with the trust store: Keystore, EncryptedSharedPreferences and
 * a test double obey the same rules, and where a key lives is an app decision while what may be
 * *claimed* about it is not.
 */
interface V2GKeystore {

    val keys: List<V2GStoredKey>

    fun add(key: V2GStoredKey)
    fun remove(id: String)

    fun availability(curve: V2GKeyCurve): V2GProtectionAvailability

    fun key(id: String): V2GStoredKey? = keys.firstOrNull { it.id == id }

    /** The §3.4 split as a query: a -2 session wants P-256 and may get a hardware-backed one, a -20
     *  session wants P-521 or Ed448 and cannot. */
    fun keys(curve: V2GKeyCurve): List<V2GStoredKey> = keys.filter { it.curve == curve }

    /** Every key whose protection is weaker than a user might assume — the list a wallet screen
     *  should be able to show without further reasoning. */
    val softwareOnlyKeys: List<V2GStoredKey> get() = keys.filter { !it.protection.isHardwareBacked }
}


class InMemoryKeystore(
    keys: List<V2GStoredKey> = emptyList(),
    private val hardwareAvailableOnThisDevice: Boolean = false,
) : V2GKeystore {

    private val stored = keys.toMutableList()

    override val keys: List<V2GStoredKey> get() = stored.toList()

    override fun add(key: V2GStoredKey) {
        stored.removeAll { it.id == key.id }
        stored += key
    }

    override fun remove(id: String) {
        stored.removeAll { it.id == id }
    }

    override fun availability(curve: V2GKeyCurve) =
        V2GProtectionAvailability(curve, hardwareAvailableOnThisDevice)
}
