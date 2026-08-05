import Foundation

/// A deadline for a phase the station answers with `EVSEProcessing = Ongoing`.
///
/// **Found live, not by reasoning.** Every poll loop in both EVCCs, in all three languages, used to be
/// `while … != .Finished` with no counter and no deadline. Against EVerest's `EvseV2G` on 2026-08-02
/// that meant 1170 `AuthorizationReq` in three minutes: their station answered `OK` with `Ongoing`
/// every time, correctly — nothing had authorized the session — and the car had nothing that would ever
/// make it stop (`../../docs/interop-runs/2026-08-02-everest-iso2-dc-notls/`).
///
/// The gap sat between two timeouts that each looked like it covered the case: a per-message timeout
/// fires when a response is *late*, and all 1170 were fast; a cancellation ends the whole session rather
/// than one phase. What was missing is ISO 15118's EVCC-side *ongoing* timeout.
///
/// The trace corpus could not have shown it: our own SECC answers `Finished` within a poll or two, so
/// no recorded session contains a station that keeps saying `Ongoing`.
///
/// **One deliberate difference from the C# original.** There the deadline reads the session's injected
/// `TimeProvider`; this port's `Evcc2` has no clock parameter, so it uses a monotonic clock. The
/// measured quantity — real time spent waiting for a peer — is the same, and a monotonic source is if
/// anything the more correct one for a deadline.
public struct OngoingGuard {

    private let phase: String
    private let limitMillis: UInt64
    private let nowMillis: () -> UInt64
    private let started: UInt64

    public init(_ phase: String,
                limitMillis: UInt64 = 60_000,
                nowMillis: @escaping () -> UInt64 = { DispatchTime.now().uptimeNanoseconds / 1_000_000 }) {
        self.phase = phase
        self.limitMillis = limitMillis
        self.nowMillis = nowMillis
        self.started = nowMillis()
    }

    /// Called once per poll. Throws when the phase has outlived its deadline.
    public func tick() throws {
        let waited = nowMillis() - started
        if waited > limitMillis {
            throw SessionAborted(
                "\(phase): the station kept answering 'Ongoing' for \(Double(waited) / 1000) s " +
                "(limit \(Double(limitMillis) / 1000) s); the session ends here.")
        }
    }

}
