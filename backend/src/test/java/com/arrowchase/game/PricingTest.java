package com.arrowchase.game;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/**
 * The house edge depends on two things being true: hit probabilities are right, and quotes never pay more than
 * they should. Both are checked here.
 */
class PricingTest {

    private static final int WINDOW = 10;
    private static final double RTP = 0.95;
    private static final double JITTER = 0.03;

    private final PathModel model = new PathModel(8, 8, 0.6, 0.2);

    @Test
    void one_step_probabilities_equal_the_transition_table() {
        ArrowState from = new ArrowState(new Cell(4, 4), Direction.RIGHT);
        double[][] p = HitProbability.withinSteps(model, from, 1);
        for (PathModel.Transition t : model.transitions(from)) {
            assertThat(p[t.next().cell().row()][t.next().cell().col()]).isCloseTo(t.probability(), within(1e-12));
        }
        assertThat(p[4][4]).isZero();
    }

    @Test
    void blocks_further_than_the_window_cannot_be_hit() {
        ArrowState corner = new ArrowState(new Cell(0, 0), Direction.RIGHT);
        double[][] p = HitProbability.withinSteps(model, corner, 3);
        assertThat(p[7][7]).isZero();
        assertThat(p[2][2]).isZero(); // 4 moves away
    }

    @Test
    void exact_probabilities_match_a_monte_carlo_simulation_of_the_live_path() {
        ArrowState from = new ArrowState(new Cell(2, 5), Direction.DOWN);
        double[][] exact = HitProbability.withinSteps(model, from, WINDOW);

        int trials = 200_000;
        int[][] hits = new int[8][8];
        RandomWalkPath path = new RandomWalkPath(model, new SplittableRandom(1));
        for (int t = 0; t < trials; t++) {
            boolean[][] seen = new boolean[8][8];
            ArrowState s = from;
            for (int k = 0; k < WINDOW; k++) {
                s = path.next(s);
                seen[s.cell().row()][s.cell().col()] = true;
            }
            for (int r = 0; r < 8; r++) {
                for (int c = 0; c < 8; c++) {
                    if (seen[r][c]) {
                        hits[r][c]++;
                    }
                }
            }
        }
        for (int r = 0; r < 8; r++) {
            for (int c = 0; c < 8; c++) {
                assertThat((double) hits[r][c] / trials)
                        .as("block (%d,%d)", r, c)
                        .isCloseTo(exact[r][c], within(0.005));
            }
        }
    }

    @Test
    void no_open_quote_anywhere_on_the_board_has_an_expected_return_of_one_or_more() {
        Pricer pricer = new Pricer(model, WINDOW, RTP, JITTER, 105, 5_000, 0.02, new SplittableRandom(3));
        double[][] table = HitProbability.table(model, WINDOW);
        for (int s = 0; s < model.stateCount(); s++) {
            ArrowState from = model.stateAt(s);
            double[][] p = HitProbability.asGrid(model, table[s]);
            int[][] quote = pricer.quote(from);
            for (int r = 0; r < 8; r++) {
                for (int c = 0; c < 8; c++) {
                    if (quote[r][c] > 0) {
                        double expectedReturn = quote[r][c] / 100.0 * p[r][c];
                        assertThat(expectedReturn).isLessThanOrEqualTo(RTP * (1 + JITTER));
                        assertThat(quote[r][c]).isBetween(105, 5_000);
                    }
                }
            }
        }
    }

    @Test
    void unlikely_and_near_certain_blocks_are_closed_rather_than_mispriced() {
        Pricer pricer = new Pricer(model, WINDOW, RTP, 0.0, 105, 5_000, 0.02, new SplittableRandom(3));
        assertThat(pricer.price(0.0)).isZero();
        assertThat(pricer.price(0.019)).isZero();
        assertThat(pricer.price(0.95)).isZero();  // would quote 1.00x
        assertThat(pricer.price(0.5)).isEqualTo(190);
        assertThat(pricer.price(0.021)).isEqualTo(4_523);
    }

    @Test
    void refuses_a_configuration_that_pays_out_more_than_it_takes() {
        assertThatThrownBy(() -> new Pricer(model, WINDOW, 0.98, 0.03, 105, 5_000, 0.02, new SplittableRandom(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
