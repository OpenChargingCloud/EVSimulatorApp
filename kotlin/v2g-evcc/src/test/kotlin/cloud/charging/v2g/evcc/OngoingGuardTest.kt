package cloud.charging.v2g.evcc

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith

/**
 * The deadline that ends a phase the station keeps answering with `Ongoing`.
 *
 * Written because a live peer needed it: EVerest's `EvseV2G` answered 1170 authorization polls with
 * `OK`/`Ongoing` and this EVCC had nothing that would ever stop
 * (`libs/Vanaheimr.V2G.Exi/docs/interop-runs/2026-08-02-everest-iso2-dc-notls/`). The trace corpus
 * cannot contain such a station, because our own SECC always finishes.
 */
class OngoingGuardTest {

    @Test
    fun aPhaseInsideItsLimitIsLeftAlone() {
        var now = 0L
        val guard = OngoingGuard("Authorization", limitMillis = 60_000) { now }

        guard.tick()
        now = 59_000
        guard.tick()
    }


    @Test
    fun aPhaseThatOutlivesItsLimitEndsTheSession() {
        var now = 0L
        val guard = OngoingGuard("Authorization", limitMillis = 60_000) { now }

        now = 61_000
        val thrown = assertFailsWith<SessionAborted> { guard.tick() }

        // Both halves belong in the message: which phase, and how long it actually waited.
        assertContains(thrown.message!!, "Authorization")
        assertContains(thrown.message!!, "61")
        assertContains(thrown.message!!, "Ongoing")
    }

}
