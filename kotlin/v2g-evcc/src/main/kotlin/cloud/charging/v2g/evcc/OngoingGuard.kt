package cloud.charging.v2g.evcc

/**
 * A deadline for a phase the station answers with `EVSEProcessing = Ongoing`.
 *
 * **Found live, not by reasoning.** Every poll loop in both EVCCs, in all three languages, used to be
 * `while (… != Finished)` with no counter and no deadline. Against EVerest's `EvseV2G` on 2026-08-02
 * that meant 1170 `AuthorizationReq` in three minutes: their station answered `OK` with `Ongoing` every
 * time, correctly — nothing had authorized the session — and the car had nothing that would ever make
 * it stop (`libs/Vanaheimr.V2G.Exi/docs/interop-runs/2026-08-02-everest-iso2-dc-notls/`).
 *
 * The gap sat between two timeouts that each looked like it covered the case: a per-message timeout
 * fires when a response is *late*, and all 1170 were fast; a cancellation ends the whole session rather
 * than one phase. What was missing is ISO 15118's EVCC-side *ongoing* timeout.
 *
 * The trace corpus could not have shown it: our own SECC answers `Finished` within a poll or two, so no
 * recorded session contains a station that keeps saying `Ongoing`.
 *
 * **One deliberate difference from the C# original.** There the deadline reads the session's injected
 * `TimeProvider`; this port's `Evcc2` has no clock parameter, so it uses a monotonic wall clock. The
 * measured quantity — real time spent waiting for a peer — is the same, and a monotonic source is if
 * anything the more correct one for a deadline.
 */
class OngoingGuard(
    private val phase: String,
    private val limitMillis: Long = 60_000,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) {

    private val started = nowMillis()

    /** Called once per poll. Throws when the phase has outlived its deadline. */
    fun tick() {
        val waited = nowMillis() - started
        if (waited > limitMillis)
            throw SessionAborted(
                "$phase: the station kept answering 'Ongoing' for ${waited / 1000.0} s " +
                "(limit ${limitMillis / 1000.0} s); the session ends here.")
    }

}
