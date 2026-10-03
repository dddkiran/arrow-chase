package com.arrowchase.game;

import java.util.Arrays;

/**
 * Exact probability that the arrow enters each block within the next {@code steps} moves, by dynamic programming
 * over the {@link PathModel} Markov chain.
 *
 * <p>One backward pass per target block yields that block's hit probability from <i>every</i> starting state at once,
 * so the full table costs O(cells x steps x states x 3), about 0.5M operations for an 8x8 grid and a 10-step
 * window, and is computed once at startup.
 */
public final class HitProbability {

    private HitProbability() {
    }

    /** {@code table[stateIndex][cellIndex]} = P(arrow enters cell within {@code steps} moves | arrow in state). */
    public static double[][] table(PathModel model, int steps) {
        int n = model.stateCount();
        int cells = model.rows() * model.cols();
        double[][] table = new double[n][cells];

        double[] prev = new double[n];
        double[] cur = new double[n];
        for (int target = 0; target < cells; target++) {
            // prev[s] = P(arrow enters target within k moves | arrow currently in state s)
            Arrays.fill(prev, 0);
            for (int k = 1; k <= steps; k++) {
                for (int s = 0; s < n; s++) {
                    int[] next = model.nextStates(s);
                    double[] p = model.probabilities(s);
                    double hit = 0;
                    for (int i = 0; i < next.length; i++) {
                        hit += p[i] * (model.cellIndexOf(next[i]) == target ? 1.0 : prev[next[i]]);
                    }
                    cur[s] = hit;
                }
                double[] swap = prev;
                prev = cur;
                cur = swap;
            }
            for (int s = 0; s < n; s++) {
                table[s][target] = prev[s];
            }
        }
        return table;
    }

    /** Convenience view of one row of {@link #table} as a grid. */
    public static double[][] withinSteps(PathModel model, ArrowState from, int steps) {
        return asGrid(model, table(model, steps)[model.indexOf(from)]);
    }

    static double[][] asGrid(PathModel model, double[] byCell) {
        double[][] grid = new double[model.rows()][model.cols()];
        for (int i = 0; i < byCell.length; i++) {
            grid[i / model.cols()][i % model.cols()] = byCell[i];
        }
        return grid;
    }
}
