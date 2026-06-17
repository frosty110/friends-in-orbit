package app.orbit.domain.usecase

/**
 * Structural surface for mutation use cases that previously swallowed missing
 * preconditions via `?: return`. Closing review finding H7: the difference
 * between "the caller can't act on this contact today" (Success — already
 * handled state, idempotent) and "the precondition vanished mid-flight" (a
 * race between dispatch and write) was indistinguishable when the caller saw
 * `Unit`. Returning [MutationResult] keeps the existing call sites compiling
 * (they ignore [Success]) while making the missing-row case structurally
 * surfaced for any future caller that wants to react.
 *
 * Why a sealed interface, not Kotlin's stdlib `Result<Unit>`: stdlib Result
 * conflates exceptional throws with semantic absence; here, a missing
 * membership row is a normal outcome, not an exception. We do NOT add Android
 * logging — project convention forbids `Log.*` / `println` in committed code;
 * the sealed return type is the surface.
 */
sealed interface MutationResult {
    /** The mutation reached the DAO and either wrote a row or completed cleanly. */
    data object Success : MutationResult

    /**
     * The targeted (contact, list) membership row was absent at write time.
     * Caller can decide whether to retry, surface a UI hint, or silently ignore
     * — the use case has nothing more to do.
     */
    data object MembershipMissing : MutationResult
}
