package com.arrowchase.game;

/** Where the arrow is and which way it is heading. The heading matters: the arrow never reverses. */
public record ArrowState(Cell cell, Direction direction) {
}
