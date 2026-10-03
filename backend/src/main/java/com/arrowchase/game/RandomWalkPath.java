package com.arrowchase.game;

import java.util.random.RandomGenerator;

/** Samples the next arrow state from {@link PathModel}. The future path does not exist until it is drawn. */
public final class RandomWalkPath implements ArrowPath {

    private final PathModel model;
    private final RandomGenerator random;

    public RandomWalkPath(PathModel model, RandomGenerator random) {
        this.model = model;
        this.random = random;
    }

    @Override
    public ArrowState next(ArrowState current) {
        int s = model.indexOf(current);
        int[] next = model.nextStates(s);
        double[] p = model.probabilities(s);
        double u = random.nextDouble();
        double cumulative = 0;
        for (int i = 0; i < next.length; i++) {
            cumulative += p[i];
            if (u < cumulative) {
                return model.stateAt(next[i]);
            }
        }
        return model.stateAt(next[next.length - 1]);
    }
}
