package com.example.ollama.outsmart;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Prevents overlapping Outsmart turns and runs whole games in background virtual threads. */
@Service
public class OutsmartGameExecutionService {

    private final OutsmartGameRegistry games;
    private final OutsmartGameRunner runner;
    private final Executor executor;
    private final ConcurrentHashMap<UUID, Execution> executions = new ConcurrentHashMap<>();

    /** Creates the production executor; each active game uses at most one background thread. */
    @Autowired
    public OutsmartGameExecutionService(OutsmartGameRegistry games, OutsmartGameRunner runner) {
        this(games, runner, Executors.newVirtualThreadPerTaskExecutor());
    }

    /** Accepts a test executor so asynchronous behavior can be checked deterministically. */
    OutsmartGameExecutionService(OutsmartGameRegistry games, OutsmartGameRunner runner, Executor executor) {
        this.games = games;
        this.runner = runner;
        this.executor = executor;
    }

    /** Runs exactly one turn, rejecting a simultaneous manual turn or full-game run. */
    public OutsmartGame playTurn(UUID gameId) {
        Execution execution = acquireTurn(gameId);
        try {
            OutsmartGame updated = runner.runNextTurn(gameId);
            execution.status = new OutsmartRunStatus(updated.finished()
                    ? OutsmartRunStatus.State.COMPLETE : OutsmartRunStatus.State.IDLE, null);
            return updated;
        } finally {
            synchronized (execution) {
                execution.busy = false;
            }
        }
    }

    /** Starts a whole game once; repeated Run Game calls reuse its current running status. */
    public OutsmartRunStatus startRun(UUID gameId) {
        Execution execution;
        synchronized (this) {
            OutsmartGame current = games.get(gameId);
            if (current.finished()) {
                return new OutsmartRunStatus(OutsmartRunStatus.State.COMPLETE, null);
            }
            execution = executions.computeIfAbsent(gameId, ignored -> new Execution());
            if (execution.busy) {
                if (execution.fullGame) {
                    return execution.status;
                }
                throw new IllegalStateException("The Outsmart game is already advancing");
            }
            execution.busy = true;
            execution.fullGame = true;
            execution.status = new OutsmartRunStatus(OutsmartRunStatus.State.RUNNING, null);
        }
        try {
            CompletableFuture.runAsync(() -> finishRun(gameId, execution), executor);
        } catch (RuntimeException exception) {
            execution.status = new OutsmartRunStatus(OutsmartRunStatus.State.FAILED,
                    "Could not start the Outsmart run");
            synchronized (execution) {
                execution.fullGame = false;
                execution.busy = false;
            }
            throw exception;
        }
        return execution.status;
    }

    /** Returns the background run state alongside the game's separately stored turn count. */
    public OutsmartRunStatus status(UUID gameId) {
        OutsmartGame game = games.get(gameId);
        Execution execution = executions.get(gameId);
        if (execution == null) {
            return new OutsmartRunStatus(game.finished()
                    ? OutsmartRunStatus.State.COMPLETE : OutsmartRunStatus.State.IDLE, null);
        }
        if (game.finished() && !execution.busy) {
            return new OutsmartRunStatus(OutsmartRunStatus.State.COMPLETE, null);
        }
        return execution.status;
    }

    /** Frees the game's memory only when no model calls are still advancing it. */
    public synchronized boolean delete(UUID gameId) {
        games.get(gameId);
        Execution execution = executions.get(gameId);
        if (execution != null && execution.busy) {
            throw new IllegalStateException("Wait for the Outsmart game to finish before deleting it");
        }
        executions.remove(gameId);
        return games.remove(gameId);
    }

    /** Claims a game turn before any model calls, sharing the short lock with start and delete. */
    private synchronized Execution acquireTurn(UUID gameId) {
        OutsmartGame game = games.get(gameId);
        if (game.finished()) {
            throw new IllegalStateException("The Outsmart game is finished");
        }
        Execution execution = executions.computeIfAbsent(gameId, ignored -> new Execution());
        if (execution.busy) {
            throw new IllegalStateException("The Outsmart game is already advancing");
        }
        execution.busy = true;
        return execution;
    }

    /** Resolves turns in the background and leaves a pollable outcome on success or failure. */
    private void finishRun(UUID gameId, Execution execution) {
        try {
            runner.runToCompletion(gameId);
            execution.status = new OutsmartRunStatus(OutsmartRunStatus.State.COMPLETE, null);
        } catch (RuntimeException exception) {
            // Keep the last committed snapshot. A failed run can be retried from that turn.
            execution.status = new OutsmartRunStatus(OutsmartRunStatus.State.FAILED,
                    exception.getMessage() == null ? "The Outsmart run failed" : exception.getMessage());
        } finally {
            synchronized (execution) {
                execution.fullGame = false;
                execution.busy = false;
            }
        }
    }

    /** Stops accepting new background tasks when Spring shuts down. */
    @PreDestroy
    public void close() {
        if (executor instanceof ExecutorService service) {
            service.shutdownNow();
        }
    }

    /** Holds one game's current gate and last reported background-run outcome. */
    private static final class Execution {
        private volatile boolean busy;
        private volatile boolean fullGame;
        private volatile OutsmartRunStatus status = new OutsmartRunStatus(OutsmartRunStatus.State.IDLE, null);
    }
}
