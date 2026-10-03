package com.arrowchase.game;

/**
 * Immutable view of a bet. Money is in cents, multipliers are x100, so there is no floating point in settlement.
 *
 * <p>A bet placed while the engine is at step {@code placedStep} covers the arrow's positions at steps
 * {@code placedStep + 1} through {@code expiresAtStep} inclusive.
 */
public record Bet(
        String id,
        String playerId,
        String requestId,
        Cell cell,
        long stakeCents,
        int lockedMultiplierX100,
        long placedStep,
        long expiresAtStep,
        BetStatus status,
        long payoutCents,
        Long settledStep) {

    public Bet settle(BetStatus outcome, long atStep) {
        long payout = outcome == BetStatus.WON ? payoutFor(stakeCents, lockedMultiplierX100) : 0;
        return new Bet(id, playerId, requestId, cell, stakeCents, lockedMultiplierX100,
                placedStep, expiresAtStep, outcome, payout, atStep);
    }

    /** Payout rounds down to the cent, never in the player's favour by accident. */
    public static long payoutFor(long stakeCents, int multiplierX100) {
        return Math.multiplyExact(stakeCents, (long) multiplierX100) / 100;
    }
}
