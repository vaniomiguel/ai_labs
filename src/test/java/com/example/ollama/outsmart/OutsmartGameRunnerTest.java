package com.example.ollama.outsmart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

/** Checks turn coordination with scripted moves, without requiring Ollama or HTTP endpoints. */
class OutsmartGameRunnerTest {

    /** Gives every player the same pre-turn snapshot and reveals messages only on the next turn. */
    @Test
    void collectsAllDecisionsBeforeAdvancing() {
        OutsmartGameRegistry registry = registry();
        OutsmartGame created = registry.create(participants());
        AtomicReference<OutsmartGame> firstSnapshot = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        OutsmartMoveGenerator generator = (snapshot, player) -> {
            calls.incrementAndGet();
            if (snapshot.nextTurn() == 1) {
                if (firstSnapshot.get() == null) {
                    firstSnapshot.set(snapshot);
                }
                assertSame(firstSnapshot.get(), snapshot);
                assertTrue(snapshot.turns().isEmpty());
            } else {
                assertEquals(2, snapshot.nextTurn());
                assertEquals(1, snapshot.turns().size());
                assertFalse(snapshot.turns().getFirst().records().get(player.name())
                        .receivedMessages().isEmpty());
            }
            return decision(player.name());
        };
        OutsmartGameRunner runner = new OutsmartGameRunner(registry, generator);

        OutsmartGame first = runner.runNextTurn(created.id());
        OutsmartGame second = runner.runNextTurn(created.id());

        assertEquals(6, calls.get());
        assertEquals(2, second.turns().size());
        assertEquals(1, first.turns().size());
        assertEquals(12, second.players().getFirst().coins());
    }

    /** Leaves the stored snapshot unchanged when a model fails before all moves are collected. */
    @Test
    void generatorFailureDoesNotPartiallyAdvance() {
        OutsmartGameRegistry registry = registry();
        OutsmartGame created = registry.create(participants());
        OutsmartMoveGenerator generator = (snapshot, player) -> {
            if (player.name().equals("Blake")) {
                throw new IllegalStateException("Provider unavailable");
            }
            return decision(player.name());
        };

        assertThrows(IllegalStateException.class,
                () -> new OutsmartGameRunner(registry, generator).runNextTurn(created.id()));
        assertSame(created, registry.get(created.id()));
    }

    /** Rejects collected decisions when another caller has already committed that turn. */
    @Test
    void staleTurnCannotOverwriteStoredGame() {
        OutsmartGameRegistry registry = registry();
        OutsmartGame created = registry.create(participants());
        OutsmartMoveGenerator generator = (snapshot, player) -> {
            if (player.name().equals("Blake")) {
                registry.advance(created.id(), snapshot.nextTurn(), decisions());
            }
            return decision(player.name());
        };

        assertThrows(IllegalStateException.class,
                () -> new OutsmartGameRunner(registry, generator).runNextTurn(created.id()));
        assertEquals(1, registry.get(created.id()).turns().size());
    }

    /** Stops automatically at the ten-turn limit when balanced moves avoid bankruptcy. */
    @Test
    void runsGameToCompletion() {
        OutsmartGameRegistry registry = registry();
        OutsmartGame created = registry.create(participants());
        AtomicInteger calls = new AtomicInteger();
        OutsmartMoveGenerator generator = (snapshot, player) -> {
            calls.incrementAndGet();
            return decision(player.name());
        };
        OutsmartGameRunner runner = new OutsmartGameRunner(registry, generator);

        OutsmartGame finished = runner.runToCompletion(created.id());

        assertTrue(finished.finished());
        assertEquals(10, finished.turns().size());
        assertEquals(30, calls.get());
        assertTrue(finished.players().stream().allMatch(player -> player.coins() == 12));
        assertSame(finished, runner.runToCompletion(created.id()));
        assertEquals(30, calls.get());
        assertThrows(IllegalStateException.class, () -> runner.runNextTurn(created.id()));
    }

    /** Creates an in-memory registry with enough room for one fixture game. */
    private OutsmartGameRegistry registry() {
        OutsmartProperties properties = new OutsmartProperties(1,
                new OutsmartProperties.Ollama(URI.create("http://localhost:11434"), Duration.ofMinutes(2)));
        return new OutsmartGameRegistry(properties, new OutsmartRuleEngine());
    }

    /** Defines a three-player lineup for each test. */
    private List<OutsmartGame.Participant> participants() {
        OutsmartGame.Model model = new OutsmartGame.Model("ollama", "scripted-model");
        return List.of(new OutsmartGame.Participant("Alex", model),
                new OutsmartGame.Participant("Blake", model),
                new OutsmartGame.Participant("Charlie", model));
    }

    /** Gives and takes in a circle so every player's balance remains unchanged. */
    private OutsmartDecision.Valid decision(String player) {
        String give = switch (player) {
            case "Alex" -> "Blake";
            case "Blake" -> "Charlie";
            case "Charlie" -> "Alex";
            default -> throw new IllegalArgumentException("Unknown test player: " + player);
        };
        String take = switch (player) {
            case "Alex" -> "Charlie";
            case "Blake" -> "Alex";
            case "Charlie" -> "Blake";
            default -> throw new IllegalArgumentException("Unknown test player: " + player);
        };
        Map<String, String> messages = new LinkedHashMap<>();
        for (OutsmartGame.Participant opponent : participants()) {
            if (!opponent.name().equals(player)) {
                messages.put(opponent.name(), player + " to " + opponent.name());
            }
        }
        return new OutsmartDecision.Valid(new OutsmartMove("scripted plan", give, take, messages));
    }

    /** Builds one scripted decision for each player when simulating a competing turn. */
    private Map<String, OutsmartDecision> decisions() {
        Map<String, OutsmartDecision> result = new LinkedHashMap<>();
        for (OutsmartGame.Participant participant : participants()) {
            result.put(participant.name(), decision(participant.name()));
        }
        return result;
    }
}
