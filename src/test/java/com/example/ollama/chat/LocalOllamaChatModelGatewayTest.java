package com.example.ollama.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import com.example.ollama.config.LocalOllamaProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

class LocalOllamaChatModelGatewayTest {

    @Test
    void sendsPromptAndModelToLocalOllamaEndpoint() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        server.createContext("/v1/chat/completions", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = """
                    {"id":"chatcmpl-test","object":"chat.completion","created":0,
                     "model":"test-model","choices":[{"index":0,"finish_reason":"stop",
                     "message":{"role":"assistant","content":"Hello back"}}]}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        server.start();
        try {
            var properties = new LocalOllamaProperties(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "test-model");
            var gateway = new LocalOllamaChatModelGateway(properties);

            assertEquals("Hello back", gateway.complete("Hello"));
            assertEquals("/v1/chat/completions", path.get());
            assertTrue(body.get().contains("\"model\":\"test-model\""));
            assertTrue(body.get().contains("\"content\":\"Hello\""));
        } finally {
            server.stop(0);
        }
    }
}
