package com.arrowchase.game;

/** Rules the engine enforces. Money is in cents. */
public record GameConfig(
        int rows,
        int cols,
        int betWindowSteps,
        long minStakeCents,
        long maxStakeCents,
        long startingBalanceCents,
        int maxOpenBetsPerPlayer,
        int trailLength) {

    public GameConfig {
        if (rows < 2 || cols < 2) {
            throw new IllegalArgumentException("grid must be at least 2x2");
        }
        if (betWindowSteps < 1) {
            throw new IllegalArgumentException("bet window must be at least one step");
        }
        if (minStakeCents < 1 || maxStakeCents < minStakeCents) {
            throw new IllegalArgumentException("invalid stake limits");
        }
    }
}
