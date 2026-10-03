package com.arrowchase.game;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/**
 * Plays thousands of steps with the real path and pricer and random players, then checks invariants that must hold
 * for any sequence of events: money is conserved, every bet settles, and the house edge shows up.
 */
class GameSimulationTest {

    private static final int STEPS = 20_000;
    private static final int PLAYERS = 5;

    @Test
    void money_is_conserved_every_bet_settles_and_the_house_keeps_its_edge() {
        SplittableRandom random = new SplittableRandom(2024);
        PathModel model = new PathModel(8, 8, 0.6, 0.2);
        GameConfig config = new GameConfig(8, 8, 10, 10, 10_000, 100_000_000L, 20, 6);
        GameEngine engine = new GameEngine(config,
                new RandomWalkPath(model, random.split()),
                new Pricer(model, 10, 0.95, 0.03, 105, 5_000, 0.02, random.split()),
                new ArrowState(new Cell(4, 4), Direction.RIGHT));

        Map<String, Long> staked = new HashMap<>();
        Map<String, Long> paid = new HashMap<>();
        long accepted = 0;
        long settled = 0;
        for (int p = 0; p < PLAYERS; p++) {
            engine.join("p" + p);
        }

        for (int step = 0; step < STEPS; step++) {
            for (int p = 0; p < PLAYERS; p++) {
                String player = "p" + p;
                Cell cell = new Cell(random.nextInt(8), random.nextInt(8));
                long stake = 10 + random.nextInt(1_000);
                PlaceBetResult result = engine.placeBet(player, step + "-" + p, cell, stake);
                if (result instanceof PlaceBetResult.Accepted) {
                    staked.merge(player, stake, Long::sum);
                    accepted++;
                }
            }
            for (Bet bet : engine.step().settled()) {
                assertThat(bet.settledStep()).isBetween(bet.placedStep() + 1, bet.expiresAtStep());
                paid.merge(bet.playerId(), bet.payoutCents(), Long::sum);
                settled++;
            }
        }
        // Let the last bets run out.
        for (int i = 0; i < 10; i++) {
            for (Bet bet : engine.step().settled()) {
                paid.merge(bet.playerId(), bet.payoutCents(), Long::sum);
                settled++;
            }
        }

        assertThat(settled).isEqualTo(accepted);
        long totalStaked = 0;
        long totalPaid = 0;
        for (int p = 0; p < PLAYERS; p++) {
            String player = "p" + p;
            long s = staked.getOrDefault(player, 0L);
            long w = paid.getOrDefault(player, 0L);
            assertThat(engine.balanceOf(player)).isEqualTo(config.startingBalanceCents() - s + w);
            assertThat(engine.openBetsOf(player)).isEmpty();
            totalStaked += s;
            totalPaid += w;
        }

        double rtp = (double) totalPaid / totalStaked;
        assertThat(accepted).isGreaterThan(STEPS);
        assertThat(rtp).as("observed return to player").isBetween(0.85, 0.99);
    }
}
