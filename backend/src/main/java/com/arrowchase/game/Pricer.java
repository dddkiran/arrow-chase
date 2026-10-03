package com.arrowchase.game;

import java.util.random.RandomGenerator;

/**
 * Turns hit probabilities into live multipliers: {@code multiplier = rtp / P(hit) x jitter}, floored to 2 decimals.
 *
 * <p>Jitter keeps the board visibly alive while the expected return of every open block stays at or below
 * {@code rtp x (1 + jitter)}, which must be below 1. Blocks the arrow is very unlikely to reach, or so likely to be
 * hit that the multiplier would fall under the minimum, are closed (quoted as 0) rather than mispriced.
 */
public final class Pricer implements MultiplierSource {

    private final PathModel model;
    private final double[][] hitTable;
    private final double rtp;
    private final double jitter;
    private final int minMultiplierX100;
    private final int maxMultiplierX100;
    private final double minHitProbability;
    private final RandomGenerator random;

    public Pricer(PathModel model, int windowSteps, double rtp, double jitter,
                  int minMultiplierX100, int maxMultiplierX100, double minHitProbability, RandomGenerator random) {
        if (rtp * (1 + jitter) >= 1) {
            throw new IllegalArgumentException("rtp x (1 + jitter) must stay below 1 or the house loses money");
        }
        this.model = model;
        this.hitTable = HitProbability.table(model, windowSteps);
        this.rtp = rtp;
        this.jitter = jitter;
        this.minMultiplierX100 = minMultiplierX100;
        this.maxMultiplierX100 = maxMultiplierX100;
        this.minHitProbability = minHitProbability;
        this.random = random;
    }

    @Override
    public int[][] quote(ArrowState from) {
        double[] p = hitTable[model.indexOf(from)];
        int[][] quote = new int[model.rows()][model.cols()];
        for (int i = 0; i < p.length; i++) {
            quote[i / model.cols()][i % model.cols()] = price(p[i]);
        }
        return quote;
    }

    int price(double hitProbability) {
        if (hitProbability < minHitProbability) {
            return 0;
        }
        double noise = 1 + jitter * (2 * random.nextDouble() - 1);
        // The epsilon stops values like 1.9 * 100 = 189.99999... from flooring a whole cent short.
        int x100 = (int) Math.floor(rtp / hitProbability * noise * 100 + 1e-9);
        if (x100 < minMultiplierX100) {
            return 0;
        }
        return Math.min(x100, maxMultiplierX100);
    }
}
