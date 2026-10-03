package com.arrowchase.app;

import com.arrowchase.game.GameConfig;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Tunables from application.yml ({@code game.*}). */
@ConfigurationProperties("game")
public record GameProperties(
        int rows,
        int cols,
        long stepMillis,
        int betWindowSteps,
        double straightWeight,
        double turnWeight,
        double rtp,
        double jitter,
        int minMultiplierX100,
        int maxMultiplierX100,
        double minHitProbability,
        long minStakeCents,
        long maxStakeCents,
        long startingBalanceCents,
        int maxOpenBetsPerPlayer,
        int trailLength,
        Long seed) {

    public GameConfig toConfig() {
        return new GameConfig(rows, cols, betWindowSteps, minStakeCents, maxStakeCents,
                startingBalanceCents, maxOpenBetsPerPlayer, trailLength);
    }
}
