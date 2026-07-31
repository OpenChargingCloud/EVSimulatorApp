package cloud.charging.v2g.keystore

import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The key store, and the asymmetry `docs/CONCEPT.md` §3.4 asks to be designed around rather than
 * discovered.
 *
 * > iOS Secure Enclave: P-256 only. Android StrongBox/TEE: P-256, RSA. So -2 PnC keys can be
 * > enclave-backed and -20 keys cannot… Be explicit about it in the UI — a simulator that quietly
 * > pretends its keys are hardware-protected is worse than one that says they aren't.
 *
 * Everything below exists to make "quietly pretends" impossible: the protection level is a value
 * rather than an implementation detail, so it can be displayed, asserted, and got wrong loudly.
 *
 * The mirror of Swift's `V2GKeystoreTests`. No shared corpus here — this is a policy the app defines,
 * not bytes another implementation produced — so what keeps the two honest is that they assert the
 * same things in the same words.
 */
class V2GKeystoreTest {

    private fun signer(protection: V2GKeyProtection = V2GKeyProtection.SoftwareInMemory): V2GSigner {
        val pair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        return InMemoryP256Signer(pair.private, pair.public.encoded, protection)
    }

    private fun storedKey(id: String = "k1", label: String = "Contract") =
        V2GStoredKey(id, label, signer())

    // ── the asymmetry itself ──────────────────────────────────────────────

    /** The table from §3.4, as a value. */
    @Test
    fun onlyP256CanBeHardwareBacked() {
        assertTrue(V2GKeyCurve.P256.canBeHardwareBacked)
        assertFalse(V2GKeyCurve.P521.canBeHardwareBacked)
        assertFalse(V2GKeyCurve.ED448.canBeHardwareBacked)
    }

    /** A -20 curve is refused hardware **even on a device that has a secure element**, and says why
     *  in terms a user can act on. Silently storing it in software is the exact failure §3.4 names. */
    @Test
    fun aMinus20CurveIsRefusedHardwareEvenWhereHardwareExists() {

        val store = InMemoryKeystore(hardwareAvailableOnThisDevice = true)

        for (curve in listOf(V2GKeyCurve.P521, V2GKeyCurve.ED448)) {
            val availability = store.availability(curve)
            assertFalse(availability.hardwareAvailable, "$curve must not claim hardware")
            assertTrue(availability.reason != null, "a refusal without a reason is a disabled control")
            assertTrue(availability.reason!!.contains("P-256"),
                       "the reason should name the actual constraint: ${availability.reason}")
        }

        assertTrue(store.availability(V2GKeyCurve.P256).hardwareAvailable)
        assertNull(store.availability(V2GKeyCurve.P256).reason)
    }

    /** Two causes that look identical from a call site must not read identically to a user: one is
     *  about the curve forever, the other about this phone today. */
    @Test
    fun theTwoReasonsForRefusingHardwareAreDistinguishable() {

        val p256OnPlainDevice = InMemoryKeystore(hardwareAvailableOnThisDevice = false)
                                    .availability(V2GKeyCurve.P256)
        val p521OnGoodDevice  = InMemoryKeystore(hardwareAvailableOnThisDevice = true)
                                    .availability(V2GKeyCurve.P521)

        assertFalse(p256OnPlainDevice.hardwareAvailable)
        assertFalse(p521OnGoodDevice.hardwareAvailable)
        assertNotEquals(p256OnPlainDevice.reason, p521OnGoodDevice.reason)
        assertTrue(p256OnPlainDevice.reason!!.contains("device"))
    }

    /** A software signer cannot describe itself as hardware-backed — and the reason is *that it holds
     *  bytes*, not the curve. P-256 can live in an enclave; it cannot live in an object handed one. */
    @Test
    fun aSoftwareSignerCannotClaimHardwareBacking() {
        assertFailsWith<V2GKeyException.SoftwareSignerCannotClaimHardware> {
            signer(V2GKeyProtection.Hardware("StrongBox"))
        }
    }

    // ── disclosure ────────────────────────────────────────────────────────

    /** The sentence shown next to a key must never be more reassuring than the truth. */
    @Test
    fun softwareKeysNeverDescribeThemselvesAsProtectedByHardware() {

        for (protection in listOf(V2GKeyProtection.SoftwareInSecureStorage,
                                  V2GKeyProtection.SoftwareInMemory)) {
            val text = protection.disclosure.lowercase()
            assertFalse(text.contains("strongbox"))
            assertFalse(text.contains("cannot be read"))
            assertFalse(protection.isHardwareBacked)
        }

        val hardware = V2GKeyProtection.Hardware("StrongBox")
        assertTrue(hardware.disclosure.contains("cannot be read"))
        assertTrue(hardware.isHardwareBacked)
    }

    /** Gating on **use** is a separate promise from gating on storage, and §3.4 asks for the former.
     *  A gated key is still a software key and must still say so. */
    @Test
    fun authenticationOnUseIsDisclosedSeparatelyFromStorage() {

        val plain = storedKey()
        val gated = V2GStoredKey("k2", "Contract",
                                 signer(V2GKeyProtection.SoftwareInSecureStorage),
                                 requiresUserAuthenticationToUse = true)

        assertFalse(plain.disclosure.contains("authenticate"))
        assertTrue(gated.disclosure.contains("authenticate"))
        assertTrue(gated.disclosure.contains("software"))
    }

    // ── the store ─────────────────────────────────────────────────────────

    @Test
    fun softwareOnlyKeysIsTheListAWalletScreenNeeds() {
        val store = InMemoryKeystore()
        store.add(storedKey("a"))
        store.add(storedKey("b"))
        assertEquals(2, store.softwareOnlyKeys.size)
    }

    @Test
    fun addingByTheSameIdReplacesRatherThanDuplicates() {

        val store = InMemoryKeystore()
        store.add(V2GStoredKey("a", "first", signer()))
        store.add(V2GStoredKey("a", "second", signer()))

        assertEquals(1, store.keys.size)
        assertEquals("second", store.key("a")?.label)

        store.remove("a")
        assertTrue(store.keys.isEmpty())
    }

    @Test
    fun keysCanBeSelectedByCurve() {
        val store = InMemoryKeystore()
        store.add(storedKey("a"))
        assertEquals(1, store.keys(V2GKeyCurve.P256).size)
        assertTrue(store.keys(V2GKeyCurve.P521).isEmpty())
    }

    /** A signer signs, and that is all the EVCC needs of it — the point of the shape. Raw `r‖s`,
     *  64 bytes for P-256, never DER. */
    @Test
    fun aSignerProducesRawRsWithoutRevealingTheKey() {
        val s = signer()
        assertEquals(64, s.signature("hello".toByteArray()).size,
                     "P-256 raw r‖s; a DER signature would be longer and vary")
        assertTrue(s.publicKeyDer.isNotEmpty())
    }
}
