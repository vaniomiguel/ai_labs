package com.example.ollama.outsmart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

/** Checks background game progress and the one-execution-per-game guard. */
class OutsmartGameExecutionServiceTest {

    /** A second run reuses progress, while manual turns and deletion wait for the active run. */
    @Test
    void backgroundRunPollsAndCompletesWithoutOverlappingWork() {
        OutsmartGameRegistry games = games();
        OutsmartGame game = games.create(participants());
        AtomicReference<Runnable> pending = new AtomicReference<>();
        OutsmartGameRunner runner = new OutsmartGameRunner(games, this::balancedMove);
        OutsmartGameExecutionService executions = new OutsmartGameExecutionService(games, runner, pending::set);

        OutsmartRunStatus started = executions.startRun(game.id());
        assertEquals(OutsmartRunStatus.State.RUNNING, started.state());
        assertSame(started, executions.startRun(game.id()));
        assertThrows(IllegalStateException.class, () -> executions.playTurn(game.id()));
        assertThrows(IllegalStateException.class, () -> executions.delete(game.id()));
        assertEquals(0, games.get(game.id()).turns().size());

        pending.get().run();

        assertEquals(OutsmartRunStatus.State.COMPLETE, executions.status(game.id()).state());
        assertEquals(10, games.get(game.id()).turns().size());
        assertEquals(OutsmartRunStatus.State.COMPLETE, executions.startRun(game.id()).state());
        assertTrue(executions.delete(game.id()));
        assertThrows(java.util.NoSuchElementException.class, () -> executions.status(game.id()));
    }

    /** Preserves completed turns and reports the failure when a model stops mid-game. */
    @Test
    void failedRunKeepsLastCommittedTurn() {
        OutsmartGameRegistry games = games();
        OutsmartGame game = games.create(participants());
        AtomicReference<Runnable> pending = new AtomicReference<>();
        OutsmartMoveGenerator generator = (snapshot, player) -> {
            if (snapshot.nextTurn() == 2) {
                throw new IllegalStateException("Model unavailable");
            }
            return balancedMove(snapshot, player);
        };
        OutsmartGameExecutionService executions = new OutsmartGameExecutionService(games,
                new OutsmartGameRunner(games, generator), pending::set);

        executions.startRun(game.id());
        pending.get().run();

        assertEquals(OutsmartRunStatus.State.FAILED, executions.status(game.id()).state());
        assertTrue(executions.status(game.id()).error().contains("Model unavailable"));
        assertEquals(1, games.get(game.id()).turns().size());
        assertFalse(games.get(game.id()).finished());
    }

    /** Creates the same bounded in-memory registry used by the HTTP and UI layers. */
    private OutsmartGameRegistry games() {
        return new OutsmartGameRegistry(new OutsmartProperties(1,
                new OutsmartProperties.Ollama(URI.create("http://localhost:11434"), Duration.ofMinutes(2))),
                new OutsmartRuleEngine());
    }

    /** Provides three local-model participants for the scripted run. */
    private List<OutsmartGame.Participant> participants() {
        OutsmartGame.Model model = new OutsmartGame.Model("ollama", "scripted");
        return List.of(new OutsmartGame.Participant("Alex", model),
                new OutsmartGame.Participant("Blake", model),
                new OutsmartGame.Participant("Charlie", model));
    }

    /** Gives and takes in a circle, allowing the game to reach turn ten without bankruptcy. */
    private OutsmartDecision balancedMove(OutsmartGame game, OutsmartGame.PlayerState player) {
        String give = switch (player.name()) {
            case "Alex" -> "Blake";
            case "Blake" -> "Charlie";
            default -> "Alex";
        };
        String take = switch (player.name()) {
            case "Alex" -> "Charlie";
            case "Blake" -> "Alex";
            default -> "Blake";
        };
        return new OutsmartDecision.Valid(new OutsmartMove("plan", give, take,
                Map.of("Alex", "to Alex", "Blake", "to Blake", "Charlie", "to Charlie")));
    }
}
