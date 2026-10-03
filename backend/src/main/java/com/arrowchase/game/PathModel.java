package com.arrowchase.game;

import java.util.ArrayList;
import java.util.List;

/**
 * The arrow's movement rules as a Markov chain over (cell, heading) states.
 *
 * <p>From any state the arrow goes straight, turns left or turns right; it never reverses and never leaves the grid.
 * Options that would leave the grid are dropped and the remaining weights renormalised, so at a wall the arrow
 * "bounces" by turning. Both the live path ({@link RandomWalkPath}) and the pricing ({@link HitProbability}) read
 * this one table, which is what keeps quoted multipliers honest.
 */
public final class PathModel {

    private final int rows;
    private final int cols;
    private final int[][] nextStates;
    private final double[][] probabilities;

    public PathModel(int rows, int cols, double straightWeight, double turnWeight) {
        if (rows < 2 || cols < 2) {
            throw new IllegalArgumentException("grid must be at least 2x2");
        }
        if (straightWeight <= 0 || turnWeight <= 0) {
            throw new IllegalArgumentException("weights must be positive");
        }
        this.rows = rows;
        this.cols = cols;
        int n = rows * cols * 4;
        this.nextStates = new int[n][];
        this.probabilities = new double[n][];
        for (int s = 0; s < n; s++) {
            ArrowState state = stateAt(s);
            Direction heading = state.direction();
            Direction[] options = {heading, heading.turnLeft(), heading.turnRight()};
            double[] weights = {straightWeight, turnWeight, turnWeight};

            List<Integer> next = new ArrayList<>(3);
            List<Double> w = new ArrayList<>(3);
            double total = 0;
            for (int i = 0; i < options.length; i++) {
                Cell target = state.cell().step(options[i]);
                if (contains(target)) {
                    next.add(indexOf(new ArrowState(target, options[i])));
                    w.add(weights[i]);
                    total += weights[i];
                }
            }
            nextStates[s] = next.stream().mapToInt(Integer::intValue).toArray();
            probabilities[s] = new double[w.size()];
            for (int i = 0; i < w.size(); i++) {
                probabilities[s][i] = w.get(i) / total;
            }
        }
    }

    public int rows() {
        return rows;
    }

    public int cols() {
        return cols;
    }

    public int stateCount() {
        return nextStates.length;
    }

    public boolean contains(Cell cell) {
        return cell.row() >= 0 && cell.row() < rows && cell.col() >= 0 && cell.col() < cols;
    }

    public int indexOf(ArrowState state) {
        return (state.cell().row() * cols + state.cell().col()) * 4 + state.direction().ordinal();
    }

    public ArrowState stateAt(int index) {
        int cellIndex = index / 4;
        return new ArrowState(new Cell(cellIndex / cols, cellIndex % cols), Direction.values()[index % 4]);
    }

    /** Index of the cell (row * cols + col) that a state sits on. */
    public int cellIndexOf(int stateIndex) {
        return stateIndex / 4;
    }

    int[] nextStates(int stateIndex) {
        return nextStates[stateIndex];
    }

    double[] probabilities(int stateIndex) {
        return probabilities[stateIndex];
    }

    public record Transition(ArrowState next, double probability) {
    }

    public List<Transition> transitions(ArrowState from) {
        int s = indexOf(from);
        List<Transition> result = new ArrayList<>(nextStates[s].length);
        for (int i = 0; i < nextStates[s].length; i++) {
            result.add(new Transition(stateAt(nextStates[s][i]), probabilities[s][i]));
        }
        return result;
    }
}
