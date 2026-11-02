package com.example.ollama.outsmart;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;

/** Coordinates Outsmart turns by collecting every player's move before applying the game rules. */
@Service
public class OutsmartGameRunner {

    private final OutsmartGameRegistry games;
    private final OutsmartMoveGenerator moveGenerator;

    /** Receives the in-memory game registry and the configured move generator. */
    public OutsmartGameRunner(OutsmartGameRegistry games, OutsmartMoveGenerator moveGenerator) {
        this.games = Objects.requireNonNull(games, "games");
        this.moveGenerator = Objects.requireNonNull(moveGenerator, "moveGenerator");
    }

    /** Collects one move per player from the same snapshot and commits exactly one turn. */
    public OutsmartGame runNextTurn(UUID gameId) {
        OutsmartGame snapshot = games.get(gameId);
        if (snapshot.finished()) {
            throw new IllegalStateException("The Outsmart game is finished");
        }

        Map<String, OutsmartDecision> decisions = new LinkedHashMap<>();
        // Keep the snapshot fixed while all models decide. A player's move or message from this
        // turn must not reach another player's prompt until the rules engine resolves the turn.
        for (OutsmartGame.PlayerState player : snapshot.players()) {
            decisions.put(player.name(), Objects.requireNonNull(
                    moveGenerator.decide(snapshot, player), "Outsmart move generator returned no decision"));
        }

        // Model calls happen outside the registry lock. If another caller advanced the game
        // meanwhile, expectedTurn rejects these stale decisions instead of overwriting that turn.
        return games.advance(gameId, snapshot.nextTurn(), decisions);
    }

    /** Runs successive turns until bankruptcy or the turn limit, returning the final snapshot. */
    public OutsmartGame runToCompletion(UUID gameId) {
        OutsmartGame game = games.get(gameId);
        // This method blocks for every local model call. A future HTTP endpoint should start it
        // in a background task if users need an immediate response or live progress updates.
        while (!game.finished()) {
            game = runNextTurn(gameId);
        }
        return game;
    }
}
