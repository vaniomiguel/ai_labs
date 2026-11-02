package com.example.ollama.outsmart;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/** Holds game snapshots in RAM for this app instance; it does not persist games or rankings. */
@Component
public class OutsmartGameRegistry {

    private final int maxGames;
    private final OutsmartRuleEngine ruleEngine;
    private final Map<UUID, OutsmartGame> games = new ConcurrentHashMap<>();

    /** Reads the game limit and uses the rules engine to advance stored snapshots. */
    public OutsmartGameRegistry(OutsmartProperties properties, OutsmartRuleEngine ruleEngine) {
        if (properties.maxGames() < 1) {
            throw new IllegalArgumentException("outsmart.max-games must be positive");
        }
        this.maxGames = properties.maxGames();
        this.ruleEngine = ruleEngine;
    }

    /** Creates a game if capacity remains; finished games count until removed or the app restarts. */
    public synchronized OutsmartGame create(List<OutsmartGame.Participant> participants) {
        if (games.size() >= maxGames) {
            throw new IllegalStateException("Outsmart game limit reached");
        }
        OutsmartGame game = OutsmartGame.start(participants);
        games.put(game.id(), game);
        return game;
    }

    /** Returns the current immutable snapshot or reports an unknown game ID. */
    public OutsmartGame get(UUID id) {
        OutsmartGame game = games.get(Objects.requireNonNull(id, "id"));
        if (game == null) {
            throw new NoSuchElementException("Outsmart game not found: " + id);
        }
        return game;
    }

    /** Atomically applies one turn, rejecting results prepared from a stale game snapshot. */
    public OutsmartGame advance(UUID id, int expectedTurn, Map<String, OutsmartDecision> decisions) {
        Objects.requireNonNull(id, "id");
        // Keep the turn check and state replacement inside one map operation. Future model calls
        // can happen outside this lock, then pass the turn number from the snapshot they read.
        OutsmartGame game = games.compute(id, (ignored, current) -> {
            if (current == null) {
                throw new NoSuchElementException("Outsmart game not found: " + id);
            }
            if (current.nextTurn() != expectedTurn) {
                throw new IllegalStateException("Outsmart game turn has changed");
            }
            return ruleEngine.playTurn(current, decisions);
        });
        return game;
    }

    /** Releases a game's memory slot; future HTTP deletion can delegate here. */
    public boolean remove(UUID id) {
        return games.remove(Objects.requireNonNull(id, "id")) != null;
    }
}
