package com.arrowchase.game;

import java.util.List;

/** Everything that changed in one tick: the new arrow position, fresh multipliers and the bets that settled. */
public record StepResult(long step, ArrowState arrow, int[][] multipliersX100, List<Bet> settled) {
}
