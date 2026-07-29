package cloud.charging.v2g.iso20.ac

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Substitution members are dispatched with `is`, which matches subtypes — so a type the schema set
 * does not know can take its nearest ancestor's branch and be written with that member's event code
 * and encoder. The generated classes are `open` exactly where that is possible, because the schema
 * derives from them; nothing stops a consumer doing the same.
 *
 * Every derived type in a schema set is itself a member, so this cannot be reached from generated
 * code — only from outside, which is why it is tested from outside.
 */
class SubstitutionGuardTest {

    private fun rational(value: Short = 1) = RationalNumberType(exponent = 0, value = value)

    private fun mode() = AC_CPDReqEnergyTransferModeType(
        eVMaximumChargePower    = rational(),
        eVMaximumChargePower_L2 = null,
        eVMaximumChargePower_L3 = null,
        eVMinimumChargePower    = rational(),
        eVMinimumChargePower_L2 = null,
        eVMinimumChargePower_L3 = null,
    )

    /** What a consumer might write: a member type extended outside the schema. */
    private class HomeGrownMode : AC_CPDReqEnergyTransferModeType(
        eVMaximumChargePower    = RationalNumberType(exponent = 0, value = 1),
        eVMaximumChargePower_L2 = null,
        eVMaximumChargePower_L3 = null,
        eVMinimumChargePower    = RationalNumberType(exponent = 0, value = 1),
        eVMinimumChargePower_L2 = null,
        eVMinimumChargePower_L3 = null,
    )

    private fun request(mode: AC_CPDReqEnergyTransferModeType) =
        AC_ChargeParameterDiscoveryReq(
            header = MessageHeaderType(sessionID = ByteArray(8), timeStamp = 1_700_000_000uL, signature = null),
            aC_CPDReqEnergyTransferMode = mode,
        )

    @Test
    fun `a member type encodes`() {
        // The guard must not stand in the way of the thing it guards.
        assertTrue(ACCodec.encode(request(mode())).isNotEmpty())
    }

    @Test
    fun `a type outside the substitution group is refused`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            ACCodec.encode(request(HomeGrownMode()))
        }

        // Without the guard this would encode as AC_CPDReqEnergyTransferMode and say nothing.
        assertEquals(
            "AC_CPDReqEnergyTransferMode: HomeGrownMode is not a substitution member",
            failure.message,
            "the message must name the field and the offending type",
        )
    }

    @Test
    fun `a real member subtype still takes its own branch`() {
        // BPT_ is a member and derives from the head: the guard must not mistake it for an intruder,
        // and it must keep its own event code rather than the head's.
        val bpt = BPT_AC_CPDReqEnergyTransferModeType(
            eVMaximumChargePower    = rational(),
            eVMaximumChargePower_L2 = null,
            eVMaximumChargePower_L3 = null,
            eVMinimumChargePower    = rational(),
            eVMinimumChargePower_L2 = null,
            eVMinimumChargePower_L3 = null,
            eVMaximumDischargePower    = rational(),
            eVMaximumDischargePower_L2 = null,
            eVMaximumDischargePower_L3 = null,
            eVMinimumDischargePower    = rational(),
            eVMinimumDischargePower_L2 = null,
            eVMinimumDischargePower_L3 = null,
        )

        val asBpt  = ACCodec.encode(request(bpt))
        val asHead = ACCodec.encode(request(mode()))

        assertTrue(asBpt.isNotEmpty())
        assertTrue(!asBpt.contentEquals(asHead), "the two members must not encode identically")
    }
}
