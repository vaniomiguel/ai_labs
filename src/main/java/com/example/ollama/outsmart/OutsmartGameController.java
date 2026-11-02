package com.example.ollama.outsmart;

import java.net.URI;
import java.util.UUID;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Exposes Outsmart game creation, public state, turn advancement, and removal over HTTP. */
@RestController
@RequestMapping("/api/outsmart/games")
public class OutsmartGameController {

    private final OutsmartGameRegistry games;
    private final OutsmartGameRunner runner;

    /** Connects the Outsmart-only API to in-memory storage and turn orchestration. */
    public OutsmartGameController(OutsmartGameRegistry games, OutsmartGameRunner runner) {
        this.games = games;
        this.runner = runner;
    }

    /** Creates a game with caller-selected local models; model calls begin on the first turn request. */
    @PostMapping
    public ResponseEntity<OutsmartGameResponse> create(@Valid @RequestBody OutsmartCreateGameRequest request) {
        OutsmartGame game = games.create(request.toParticipants());
        return ResponseEntity.created(URI.create("/api/outsmart/games/" + game.id()))
                .body(OutsmartGameResponse.from(game));
    }

    /** Returns public progress for an existing in-memory game. */
    @GetMapping("/{gameId}")
    public OutsmartGameResponse get(@PathVariable UUID gameId) {
        return OutsmartGameResponse.from(games.get(gameId));
    }

    /** Blocks for one decision per player, then returns the newly completed turn. */
    @PostMapping("/{gameId}/turns")
    public OutsmartGameResponse playTurn(@PathVariable UUID gameId) {
        return OutsmartGameResponse.from(runner.runNextTurn(gameId));
    }

    /** Removes a game and frees its in-memory slot. */
    @DeleteMapping("/{gameId}")
    public ResponseEntity<Void> delete(@PathVariable UUID gameId) {
        if (!games.remove(gameId)) {
            throw new java.util.NoSuchElementException("Outsmart game not found: " + gameId);
        }
        return ResponseEntity.noContent().build();
    }
}
