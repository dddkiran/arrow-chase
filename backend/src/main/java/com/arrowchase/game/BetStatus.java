package com.arrowchase.game;

public enum BetStatus {
    /** Accepted, multiplier locked, waiting for the arrow. */
    OPEN,
    /** The arrow entered the block within the bet window. */
    WON,
    /** The bet window passed without the arrow entering the block. */
    LOST
}
