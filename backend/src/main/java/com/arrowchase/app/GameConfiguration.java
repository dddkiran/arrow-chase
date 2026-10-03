package com.arrowchase.app;

import com.arrowchase.game.ArrowState;
import com.arrowchase.game.Cell;
import com.arrowchase.game.Direction;
import com.arrowchase.game.GameEngine;
import com.arrowchase.game.PathModel;
import com.arrowchase.game.Pricer;
import com.arrowchase.game.RandomWalkPath;
import java.security.SecureRandom;
import java.util.SplittableRandom;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(GameProperties.class)
public class GameConfiguration {

    @Bean
    GameEngine gameEngine(GameProperties props) {
        // A fixed seed replays the same game (useful for debugging); otherwise seed from a secure source.
        long seed = props.seed() != null ? props.seed() : new SecureRandom().nextLong();
        SplittableRandom root = new SplittableRandom(seed);

        PathModel model = new PathModel(props.rows(), props.cols(), props.straightWeight(), props.turnWeight());
        RandomWalkPath path = new RandomWalkPath(model, root.split());
        Pricer pricer = new Pricer(model, props.betWindowSteps(), props.rtp(), props.jitter(),
                props.minMultiplierX100(), props.maxMultiplierX100(), props.minHitProbability(), root.split());
        ArrowState start = new ArrowState(new Cell(props.rows() / 2, props.cols() / 2), Direction.RIGHT);
        return new GameEngine(props.toConfig(), path, pricer, start);
    }

    @Bean
    GameLoop gameLoop(GameEngine engine, GameEvents events, GameProperties props) {
        return new GameLoop(engine, events, props.stepMillis());
    }
}
