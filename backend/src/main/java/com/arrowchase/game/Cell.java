package com.arrowchase.game;

/** A block on the grid, addressed by zero-based row and column. */
public record Cell(int row, int col) {

    public Cell step(Direction direction) {
        return new Cell(row + direction.dRow, col + direction.dCol);
    }
}
