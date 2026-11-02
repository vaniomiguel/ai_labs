package com.example.ollama.outsmart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** Checks game setup, turn resolution, and the in-memory registry with scripted decisions. */
class OutsmartRuleEngineTest {

    private final OutsmartRuleEngine engine = new OutsmartRuleEngine();

    /** Confirms the initial balance and rejects names that differ only by case. */
    @Test
    void startsWithTwelveCoinsAndUniquePlayers() {
        OutsmartGame game = newGame();
        assertEquals(1, game.nextTurn());
        assertTrue(game.turns().isEmpty());
        assertTrue(game.players().stream().allMatch(player -> player.coins() == 12));
        assertThrows(IllegalArgumentException.class, () -> OutsmartGame.start(List.of(
                participant("Alex"), participant("alex"), participant("Drew"))));
    }

    /** Checks mutual-gift bonuses and each player's view of private messages. */
    @Test
    void allianceTransfersTwoExtraCoinsAndMessagesStayPrivate() {
        OutsmartGame game = engine.playTurn(newGame(), Map.of(
                "Alex", move("Alex", "Blake", "Charlie"),
                "Blake", move("Blake", "Alex", "Charlie"),
                "Charlie", move("Charlie", "Drew", "Alex"),
                "Drew", move("Drew", "Charlie", "Blake")));

        assertEquals(Map.of("Alex", 13, "Blake", 13, "Charlie", 9, "Drew", 13), coins(game));
        assertEquals(List.of("Blake"), record(game, "Alex").alliancesWith());
        assertEquals(List.of("Alex", "Blake"), record(game, "Charlie").alliancesAgainst());
        assertEquals(Map.of("Alex", "Alex to Blake", "Charlie", "Charlie to Blake",
                "Drew", "Drew to Blake"), record(game, "Blake").receivedMessages());
        assertFalse(record(game, "Blake").receivedMessages().containsKey("Blake"));
        assertFalse(game.finished());
        assertEquals(2, game.nextTurn());
    }

    /** Confirms a failed model response forfeits one coin and sends no messages. */
    @Test
    void invalidMoveForfeitsOneCoinAndDoesNotSendMessages() {
        OutsmartGame game = engine.playTurn(newGame(), Map.of(
                "Alex", new OutsmartDecision.Invalid("Malformed JSON"),
                "Blake", move("Blake", "Charlie", "Drew"),
                "Charlie", move("Charlie", "Drew", "Blake"),
                "Drew", move("Drew", "Blake", "Charlie")));

        assertEquals(Map.of("Alex", 11, "Blake", 12, "Charlie", 12, "Drew", 12), coins(game));
        assertInstanceOf(OutsmartDecision.Invalid.class, record(game, "Alex").decision());
        assertFalse(record(game, "Blake").receivedMessages().containsKey("Alex"));
    }

    /** Checks that illegal targets and incomplete messages are forfeited moves. */
    @Test
    void illegalTargetsAndMissingMessagesBecomeInvalidMoves() {
        OutsmartMove selfGift = new OutsmartMove("strategy", "Alex", "Charlie", messages("Alex"));
        OutsmartMove sameTarget = new OutsmartMove("strategy", "Blake", "Blake", messages("Alex"));
        OutsmartMove missingMessage = new OutsmartMove("strategy", "Blake", "Charlie", Map.of("Blake", "hi"));
        for (OutsmartMove illegal : List.of(selfGift, sameTarget, missingMessage)) {
            OutsmartGame game = engine.playTurn(newGame(), Map.of(
                    "Alex", new OutsmartDecision.Valid(illegal),
                    "Blake", move("Blake", "Charlie", "Drew"),
                    "Charlie", move("Charlie", "Drew", "Blake"),
                    "Drew", move("Drew", "Blake", "Charlie")));
            assertEquals(11, coins(game).get("Alex"));
            assertInstanceOf(OutsmartDecision.Invalid.class, record(game, "Alex").decision());
        }
    }

    /** Confirms extra messages are discarded without invalidating an otherwise legal move. */
    @Test
    void dropsMessagesToSelfAndUnknownPlayers() {
        Map<String, String> messages = messages("Alex");
        messages.put("Alex", "self message");
        messages.put("Zed", "unknown player");
        OutsmartGame game = engine.playTurn(newGame(), Map.of(
                "Alex", new OutsmartDecision.Valid(new OutsmartMove("strategy", "Blake", "Charlie", messages)),
                "Blake", move("Blake", "Charlie", "Drew"),
                "Charlie", move("Charlie", "Drew", "Blake"),
                "Drew", move("Drew", "Blake", "Charlie")));
        assertInstanceOf(OutsmartDecision.Valid.class, record(game, "Alex").decision());
        assertEquals("Alex to Blake", record(game, "Blake").receivedMessages().get("Alex"));
        assertFalse(record(game, "Alex").receivedMessages().containsKey("Alex"));
    }

    /** Checks early bankruptcy, tied winners, and the finished-game guard. */
    @Test
    void bankruptcyEndsEarlyAndMarksTiedWinners() {
        OutsmartGame game = newGame();
        for (int turn = 0; turn < 3; turn++) {
            game = engine.playTurn(game, Map.of(
                    "Alex", move("Alex", "Drew", "Blake"),
                    "Blake", move("Blake", "Charlie", "Alex"),
                    "Charlie", move("Charlie", "Blake", "Alex"),
                    "Drew", move("Drew", "Blake", "Alex")));
        }
        assertTrue(game.finished());
        assertEquals(3, game.turns().size());
        assertEquals(0, coins(game).get("Alex"));
        assertTrue(game.players().stream().filter(player -> player.name().equals("Alex"))
                .findFirst().orElseThrow().bankrupt());
        assertEquals(List.of("Blake", "Charlie"), game.players().stream()
                .filter(OutsmartGame.PlayerState::winner).map(OutsmartGame.PlayerState::name).toList());
        OutsmartGame finished = game;
        assertThrows(IllegalStateException.class, () -> engine.playTurn(finished, Map.of()));
    }

    /** Checks the normal turn limit when no player goes bankrupt. */
    @Test
    void tenTurnsFinishWithAllPlayersTied() {
        OutsmartGame game = newGame();
        Map<String, OutsmartDecision> moves = Map.of(
                "Alex", move("Alex", "Blake", "Charlie"),
                "Blake", move("Blake", "Charlie", "Drew"),
                "Charlie", move("Charlie", "Drew", "Alex"),
                "Drew", move("Drew", "Alex", "Blake"));
        for (int turn = 0; turn < OutsmartGame.MAX_TURNS; turn++) {
            game = engine.playTurn(game, moves);
        }
        assertTrue(game.finished());
        assertEquals(10, game.turns().size());
        assertTrue(game.players().stream().allMatch(player -> player.coins() == 12 && player.winner()));
    }

    /** Checks the memory limit, turn concurrency guard, and slot reuse after removal. */
    @Test
    void registryHasLimitAndRejectsStaleTurns() {
        OutsmartProperties properties = new OutsmartProperties(1,
                new OutsmartProperties.Ollama(URI.create("http://localhost:11434"), Duration.ofMinutes(2)));
        OutsmartGameRegistry registry = new OutsmartGameRegistry(properties, engine);
        OutsmartGame game = registry.create(participants());
        assertThrows(IllegalStateException.class, () -> registry.create(participants()));
        OutsmartGame advanced = registry.advance(game.id(), 1, Map.of(
                "Alex", move("Alex", "Blake", "Charlie"),
                "Blake", move("Blake", "Charlie", "Drew"),
                "Charlie", move("Charlie", "Drew", "Alex"),
                "Drew", move("Drew", "Alex", "Blake")));
        assertEquals(2, advanced.nextTurn());
        assertThrows(IllegalStateException.class, () -> registry.advance(game.id(), 1, Map.of()));
        assertTrue(registry.remove(game.id()));
        assertFalse(registry.remove(game.id()));
        assertEquals(12, registry.create(participants()).players().getFirst().coins());
    }

    /** Starts a fresh four-player fixture for a test. */
    private static OutsmartGame newGame() {
        return OutsmartGame.start(participants());
    }

    /** Defines the shared player lineup; update message fixtures if names change. */
    private static List<OutsmartGame.Participant> participants() {
        return List.of(participant("Alex"), participant("Blake"),
                participant("Charlie"), participant("Drew"));
    }

    /** Assigns a local placeholder model because these tests do not call Ollama. */
    private static OutsmartGame.Participant participant(String name) {
        return new OutsmartGame.Participant(name, new OutsmartGame.Model("ollama", "local-test-model"));
    }

    /** Builds a legal decision with one private message per opponent. */
    private static OutsmartDecision.Valid move(String sender, String give, String take) {
        return new OutsmartDecision.Valid(new OutsmartMove("strategy", give, take, messages(sender)));
    }

    /** Creates identifiable messages for all opponents in the test lineup. */
    private static Map<String, String> messages(String sender) {
        Map<String, String> messages = new LinkedHashMap<>();
        for (String name : List.of("Alex", "Blake", "Charlie", "Drew")) {
            if (!name.equals(sender)) {
                messages.put(name, sender + " to " + name);
            }
        }
        return messages;
    }

    /** Reduces a snapshot to balances for concise assertions. */
    private static Map<String, Integer> coins(OutsmartGame game) {
        Map<String, Integer> result = new LinkedHashMap<>();
        game.players().forEach(player -> result.put(player.name(), player.coins()));
        return result;
    }

    /** Looks up a player's result from the most recent turn. */
    private static OutsmartGame.TurnRecord record(OutsmartGame game, String name) {
        return game.turns().getLast().records().get(name);
    }
}
