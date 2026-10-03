package com.arrowchase.game;

/**
 * Quotes the live multiplier for every block given the arrow's current state.
 * Values are multipliers x100 (e.g. 245 = 2.45x); 0 means the block is closed for betting.
 */
@FunctionalInterface
public interface MultiplierSource {
    int[][] quote(ArrowState from);
}
