package com.example.ollama.outsmart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Checks the real Outsmart HTTP routes with scripted moves instead of a running Ollama model. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OutsmartGameControllerTest {

    @Value("${local.server.port}")
    private int port;

    private final HttpClient client = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    /** Exercises creation, public reads, one turn, deletion, and a missing-game response. */
    @Test
    void gameLifecycleUsesOnlyPublicResponseFields() throws IOException, InterruptedException {
        String request = """
                {"participants":[
                  {"name":"Alex","model":{"provider":"ollama","id":"gemma3:1b"}},
                  {"name":"Blake","model":{"provider":"ollama","id":"qwen3.5:4b"}},
                  {"name":"Charlie","model":{"provider":"ollama","id":"gemma3:1b"}}
                ]}
                """;
        HttpResponse<String> created = send("POST", "/api/outsmart/games", request);
        assertEquals(201, created.statusCode());
        JsonNode initial = mapper.readTree(created.body());
        assertEquals(0, initial.path("turnsCompleted").asInt());
        assertEquals("qwen3.5:4b", initial.path("players").get(1).path("modelId").asString());
        String gamePath = "/api/outsmart/games/" + initial.path("id").asString();
        assertEquals(gamePath, created.headers().firstValue("Location").orElseThrow());

        HttpResponse<String> played = send("POST", gamePath + "/turns", null);
        assertEquals(200, played.statusCode());
        JsonNode state = mapper.readTree(played.body());
        assertEquals(1, state.path("turnsCompleted").asInt());
        assertEquals(3, state.path("turns").get(0).path("coinsAfter").size());
        assertFalse(played.body().contains("private plan"));
        assertFalse(played.body().contains("private_messages"));
        assertFalse(played.body().contains("secretStrategy"));

        assertEquals(200, send("GET", gamePath, null).statusCode());
        assertEquals(204, send("DELETE", gamePath, null).statusCode());
        assertEquals(404, send("GET", gamePath, null).statusCode());
    }

    /** Rejects a provider that the Outsmart runner cannot call yet. */
    @Test
    void unsupportedProviderReturnsBadRequest() throws IOException, InterruptedException {
        String request = """
                {"participants":[
                  {"name":"Alex","model":{"provider":"openrouter","id":"some-model"}},
                  {"name":"Blake","model":{"provider":"ollama","id":"gemma3:1b"}},
                  {"name":"Charlie","model":{"provider":"ollama","id":"gemma3:1b"}}
                ]}
                """;

        HttpResponse<String> response = send("POST", "/api/outsmart/games", request);

        assertEquals(400, response.statusCode());
        assertTrue(response.body().contains("provider must be ollama"));
    }

    /** Renders the setup page and replaces the game panel after create, play, and delete actions. */
    @Test
    void browserFlowRendersThymeleafAndHtmxFragments() throws IOException, InterruptedException {
        HttpResponse<String> setup = send("GET", "/outsmart", null);
        assertEquals(200, setup.statusCode());
        assertTrue(setup.body().contains("/vendor/htmx.min.js"));
        assertTrue(setup.body().contains("Choose your players"));

        String form = "name=Alex&name=Blake&name=Charlie&name=&name=&name=&name=&name="
                + "&model=gemma3%3A1b&model=gemma3%3A1b&model=gemma3%3A1b"
                + "&model=gemma3%3A1b&model=gemma3%3A1b&model=gemma3%3A1b"
                + "&model=gemma3%3A1b&model=gemma3%3A1b";
        HttpResponse<String> created = sendForm("/outsmart/games", form);
        assertEquals(200, created.statusCode());
        assertTrue(created.body().contains("Play next turn"));
        assertFalse(created.body().contains("<html"));
        String gamePath = created.headers().firstValue("HX-Push-Url").orElseThrow();
        assertTrue(gamePath.startsWith("/outsmart/games/"));

        HttpResponse<String> played = sendForm(gamePath + "/turns", "");
        assertEquals(200, played.statusCode());
        assertTrue(played.body().contains("Turn 2 of 10"));
        assertTrue(played.body().contains("Coin balances"));
        assertFalse(played.body().contains("private plan"));

        HttpResponse<String> deleted = sendForm(gamePath + "/delete", "");
        assertEquals(200, deleted.statusCode());
        assertEquals("/outsmart", deleted.headers().firstValue("HX-Push-Url").orElseThrow());
        assertTrue(deleted.body().contains("Choose your players"));
    }

    /** Keeps an invalid optional player visible so the user can correct the model ID. */
    @Test
    void browserFormErrorPreservesOptionalPlayer() throws IOException, InterruptedException {
        String form = "name=Alex&name=Blake&name=Charlie&name=Dana&name=&name=&name=&name="
                + "&model=gemma3%3A1b&model=gemma3%3A1b&model=gemma3%3A1b"
                + "&model=&model=gemma3%3A1b&model=gemma3%3A1b"
                + "&model=gemma3%3A1b&model=gemma3%3A1b";

        HttpResponse<String> response = sendForm("/outsmart/games", form);

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("model IDs up to 160 characters"));
        assertTrue(response.body().contains("value=\"Dana\""));
        assertTrue(response.body().contains("class=\"extra-players\" open"));
    }

    /** Sends a request to the temporary Spring Boot port and returns the raw HTTP response. */
    private HttpResponse<String> send(String method, String path, String body)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Sends a browser-style form request and asks the server for an HTML fragment. */
    private HttpResponse<String> sendForm(String path, String body) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("HX-Request", "true")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /** Supplies deterministic local moves so the HTTP test exercises orchestration without Ollama. */
    @TestConfiguration(proxyBeanMethods = false)
    static class ScriptedMoves {

        /** Overrides the model call only inside this test application context. */
        @Bean
        @Primary
        OutsmartMoveGenerator scriptedMoveGenerator() {
            return (game, player) -> {
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
                return new OutsmartDecision.Valid(new OutsmartMove("private plan", give, take,
                        Map.of("Alex", "to Alex", "Blake", "to Blake", "Charlie", "to Charlie")));
            };
        }
    }
}
