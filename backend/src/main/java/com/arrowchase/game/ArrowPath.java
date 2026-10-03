package com.arrowchase.game;

/** Produces the arrow's next position. Implementations decide the path live, one step at a time. */
@FunctionalInterface
public interface ArrowPath {
    ArrowState next(ArrowState current);
}
